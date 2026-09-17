package com.fpb.vault.session

import com.fpb.vault.codec.NoteCodec
import com.fpb.vault.codec.NoteIndexCodec
import com.fpb.vault.crypto.Aad
import com.fpb.vault.crypto.AeadCipher
import com.fpb.vault.crypto.Argon2Kdf
import com.fpb.vault.crypto.KdfParams
import com.fpb.vault.crypto.SecureBytes
import com.fpb.vault.crypto.UnlockOutcome
import com.fpb.vault.crypto.VaultDek
import com.fpb.vault.crypto.VaultDomain
import com.fpb.vault.data.BlobReader
import com.fpb.vault.data.BlobSink
import com.fpb.vault.data.ByteArrayBlobReader
import com.fpb.vault.data.ChunkedBlobFormat
import com.fpb.vault.data.ChunkedBlobReader
import com.fpb.vault.data.CipherRow
import com.fpb.vault.data.CipherRowStore
import com.fpb.vault.data.RowIds
import com.fpb.vault.data.toHex
import com.fpb.vault.model.ImageRef
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.VaultNote
import com.fpb.vault.model.VideoRef
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 标签及其条目数量。 */
data class TagCount(val name: String, val count: Int)

/**
 * 一段刚入库的视频：它的 blobId 与明文长度。
 *
 * 明文的**宽高与时长不在这里** —— 那要靠 `MediaMetadataRetriever` 解出来，
 * 而那需要 Android 运行时。会话层是纯 JVM（整个 `crypto` / `session` / `codec`
 * 都是这样，好在单测里跑），所以探测元数据这件事交给上层，见
 * [com.fpb.vault.vault.VideoPipeline]。
 */
class StoredVideo(val blobId: String, val plainBytes: Long)

/**
 * 一次"打开着的保险库"。
 *
 * 它同时是三样东西，因为它们必须共享同一个生命周期：
 * 1. **DEK 的唯一持有者** —— 谁也没有第二份副本
 * 2. **锁定状态机** —— 切后台计时、回到前台判定、到期清零
 * 3. **内存中的明文索引** —— 解锁时一次性解密，之后所有读取都走内存
 *
 * ## 关于"解锁时全量解密"
 *
 * 这是个需要辩护的选择。看起来"打开列表要把所有笔记都解密一遍"很浪费，
 * 但它是这套设计的必然结果：字段全部加密意味着**没有任何明文字段可以用来
 * 分页、排序或筛选**。数据库无法替我们做这些事，只能一次性把明文拿进内存。
 *
 * 代价可以算清楚：一条笔记约 1 KB，1000 条就是 1 MB 的 AES-GCM 解密，
 * 在现代手机上是个位数毫秒级，一次性发生在解锁瞬间。换来的好处是
 * 搜索、标签统计、排序全部变成本地操作，且**锁定时这些数据整体消失** ——
 * 不存在"留在磁盘上的缓存让加密变成摆设"。
 *
 * ## 真库与诱饵库共用一张表
 *
 * 两个库的行**混在同一个数据库文件里**，靠 DEK 不同来隔离。
 * 如果让它们各占一个数据库文件（`vault.db` / `decoy.db`），
 * 任何人翻一下文件列表就知道"这台设备设有第二密码" —— 那么在被胁迫时
 * 交出假密码就完全失去了意义，因为对方会直接追问"另一个库呢"。
 *
 * 文件名和行数都藏不住，但**"存在第二个库"这件事必须藏住**。
 *
 * 代价是"哪些行是我的"必须靠 [NoteIndexCodec] 那份加密清单来回答。
 */
class VaultSession(
    private val rows: CipherRowStore,
    private val blobs: BlobSink,
    var autoLockMillis: Long = DEFAULT_AUTO_LOCK_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = RowIds::random,
) : AutoCloseable {

    enum class State { LOCKED, UNLOCKED }

    /** 锁定状态下试图读取明文时抛出。宁可让调用方崩溃，也不返回空数据。 */
    class LockedException(message: String = "保险库已锁定，拒绝访问明文数据") : IllegalStateException(message)

    /** 解锁后加载索引的结果。**存在的意义就是不让"笔记消失"变成一件无声的事。** */
    data class LoadReport(
        val noteCount: Int,
        /** 清单里有、数据库里找不到的行数。 */
        val missingRows: Int,
        /** 行存在但解密失败的行数（被篡改、或存储介质损坏）。 */
        val unreadableRows: Int,
        /** 清单本身读不出来。此时无法判断库里有什么，**绝不允许继续写入**。 */
        val manifestUnreadable: Boolean,
        /** 全新的库，还没有清单。 */
        val isFresh: Boolean,
    ) {
        val isClean: Boolean
            get() = missingRows == 0 && unreadableRows == 0 && !manifestUnreadable

        val problemCount: Int get() = missingRows + unreadableRows

        companion object {
            fun fresh() = LoadReport(0, 0, 0, manifestUnreadable = false, isFresh = true)
        }
    }

    // ==================== 状态 ====================

    private var dek: VaultDek? = null

    private val index = LinkedHashMap<String, NotePayload>()

    /**
     * 与 [index] 同步维护的小写检索文本。
     *
     * 每次按键都重新拼一遍所有笔记的可搜索文本，在几百条时就已经能感觉到卡顿；
     * 而这段文本只在写入时变化，因此缓存起来是几乎零成本的优化。
     */
    private val searchCache = HashMap<String, String>()

    /**
     * 清单里存在、但行缺失或解不开的条目 id。
     *
     * 这些 id **必须跟着清单一起落盘**，否则任何一次写入都会把它们从清单里抹掉：
     * 问题报告从此消失（"笔记消失变成无声的事"），而这些行也就永久失去了
     * "属于本域"的唯一凭证 —— 既不能归属、不能清理、也不能被另一个库认领。
     * 保留它们，让每次解锁都继续把问题报出来，直到存储层真正修复。
     */
    private val brokenIds = LinkedHashSet<String>()

    private var backgroundedAt: Long? = null

    private var loadReport: LoadReport = LoadReport.fresh()

    /** 状态变化回调，供界面在自动锁定时跳回解锁页。 */
    var onStateChanged: ((State) -> Unit)? = null

    val state: State get() = if (dek != null) State.UNLOCKED else State.LOCKED

    val isUnlocked: Boolean get() = dek != null

    /** 当前打开的库。锁定时为 null。 */
    val domain: VaultDomain? get() = dek?.domain

    /** 是否为诱饵库。为 true 时上层必须禁用改密码、换恢复码等会污染真库密钥的操作。 */
    val isDecoy: Boolean get() = dek?.domain == VaultDomain.DECOY

    val lastLoadReport: LoadReport get() = loadReport

    val noteCount: Int get() = index.size

    /**
     * 当前解锁态下的 DEK。
     *
     * **只允许"密钥维护"这类操作使用** —— 目前有两处：启用生物识别（要把 DEK
     * 用 Keystore 硬件密钥再包裹一份写进新槽位）、更换恢复码（重新包裹恢复码槽）。
     * 这两件事都需要把 DEK 交给另一把 KEK，而它们属于密钥环的职责，
     * 界面层不该拿到 DEK 本身。
     *
     * 返回的是**内部持有的那一个实例**，调用方**绝不能 close 它** ——
     * 关掉之后整个会话的 DEK 就没了，表现为"点一下启用生物识别，保险库随机锁定"。
     * 需要独立生命周期时请用 [VaultDek] 的拷贝语义自己包一层。
     */
    fun currentDek(): VaultDek? = dek

    // ==================== 生命周期 ====================

    /**
     * 用解锁结果打开保险库。[outcome] 的所有权被移交：成功则内部持有其 DEK，
     * 失败则就地销毁，调用方不需要（也不应该）再碰它。
     */
    fun unlock(outcome: UnlockOutcome.Unlocked): LoadReport {
        // 幂等：先无条件清掉可能残留的旧会话，避免两把 DEK 同时存在。
        // 这里刻意**不发 LOCKED 回调** —— 回调表达的应当是"状态迁移"，
        // 而这一次只是内部清理。若它发出 LOCKED，已经解锁的会话再次解锁
        // （界面重建、解锁失败后重试）时回调序列会变成 LOCKED → UNLOCKED，
        // 界面按 LOCKED 跳回解锁页，用户看到一次莫名其妙的闪回。
        val wasUnlocked = dek != null
        clearState()

        val newDek = outcome.takeDek()
        dek = newDek
        try {
            loadReport = loadIndex()
            if (loadReport.manifestUnreadable) {
                // 清单读不出来时不能假装库是空的：后续任何一次写入都会用
                // 一份只有新条目的清单覆盖掉原清单，等于把整个索引丢掉。
                // 因此这里直接判定解锁失败，让用户从备份恢复。
                throw LockedException("索引清单无法解读，保险库内容不可信任")
            }
        } catch (t: Throwable) {
            clearState()
            newDek.close()
            throw t
        }
        // 只在"锁定 → 解锁"这次真实迁移上通知界面
        if (!wasUnlocked) onStateChanged?.invoke(State.UNLOCKED)
        return loadReport
    }

    /** 锁定并清零密钥。可重复调用。 */
    fun lock() {
        val wasUnlocked = dek != null
        clearState()
        if (wasUnlocked) onStateChanged?.invoke(State.LOCKED)
    }

    /** 清空一切解锁态数据，不发任何回调。 */
    private fun clearState() {
        dek?.close()
        dek = null
        index.clear()
        searchCache.clear()
        brokenIds.clear()
        backgroundedAt = null
        loadReport = LoadReport.fresh()
    }

    override fun close() = lock()

    /** 界面进入后台时调用。只有解锁状态下才计时。 */
    fun onBackgrounded(now: Long = clock()) {
        if (dek != null) backgroundedAt = now
    }

    /**
     * 界面回到前台时调用。
     *
     * @return true 表示已经因为超时被自动锁定
     */
    fun onForegrounded(now: Long = clock()): Boolean {
        val since = backgroundedAt ?: return false
        backgroundedAt = null
        if (now - since < autoLockMillis) return false
        lock()
        return true
    }

    /**
     * 后台存活期间的定期检查。
     *
     * 只在回到前台时才判断超时是不够的：那样"离开一小时"的整个过程中，
     * DEK 一直躺在内存里。由界面层挂一个定时器（不必很密，十几秒一次即可）
     * 驱动这里，超时当场清零。
     */
    fun tick(now: Long = clock()) {
        val since = backgroundedAt ?: return
        if (now - since >= autoLockMillis) lock()
    }

    // ==================== 读 ====================

    /**
     * 全部笔记，按最后修改时间倒序。
     *
     * 锁定状态下**抛出异常而不是返回空列表**。空列表会被界面渲染成
     * "你还没有任何笔记"，用户看到的第一反应是"数据丢了" ——
     * 对一个内容只存在本机的应用，这个误会比一次崩溃严重得多。
     */
    fun notes(): List<VaultNote> {
        requireUnlocked()
        return index.entries
            .map { (id, payload) -> VaultNote(id, payload) }
            .sortedWith(compareByDescending<VaultNote> { it.updatedAt }.thenBy { it.id })
    }

    fun note(id: String): VaultNote? {
        requireUnlocked()
        return index[id]?.let { VaultNote(id, it) }
    }

    /**
     * 内存中的全文检索。**锁定时必然不可用** —— 密文无法被模糊匹配，
     * 这是全字段加密的直接代价，而不是实现上的疏忽。
     */
    fun search(query: String): List<VaultNote> {
        requireUnlocked()
        val keyword = query.trim().lowercase()
        if (keyword.isEmpty()) return notes()
        return notes().filter { searchCache[it.id]?.contains(keyword) == true }
    }

    fun tagCounts(): List<TagCount> {
        requireUnlocked()
        return index.values
            .flatMap { it.tags }
            .groupingBy { it }
            .eachCount()
            .map { TagCount(it.key, it.value) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.name })
    }

    /** 数据库密文与附件密文的总占用。不含 SQLite 页开销。 */
    fun storageBytes(): Long = rows.totalBytes() + blobs.totalBytes()

    // ==================== 写 ====================

    fun create(payload: NotePayload): VaultNote {
        requireUnlocked()
        requireManifestCapacity(1)

        val now = clock()
        val normalized = payload.copy(
            createdAt = if (payload.createdAt > 0) payload.createdAt else now,
            updatedAt = now,
        ).normalized()

        val id = idFactory()

        // 不可逆数据丢失必须"大声失败"。128 位随机 id 碰撞概率约等于零，
        // 但自定义 idFactory（测试、将来的备份导入）一旦碰撞，静默覆盖的
        // 是旧条目的内存索引与磁盘密文 —— 原内容再也找不回来。
        // 同理也挡住与清单行的碰撞：writeNote 一旦写到清单行 id 上，
        // 被覆盖的就是"哪些笔记存在"这份唯一凭证。
        check(id !in index) { "生成的条目 id 与现有条目冲突，拒绝覆盖：$id" }
        check(id != manifestRowId()) { "生成的条目 id 与清单行冲突，拒绝覆盖：$id" }

        // 内存索引必须**先**更新，因为 persistIndex() 是靠 index.keys 生成清单的；
        // 但一旦落盘失败，就必须把它一并撤回 —— 否则内存里有一条、磁盘上没有，
        // 用户会看到一条"关掉应用就消失"的笔记。
        indexPut(id, normalized)
        try {
            rows.inTransaction {
                writeNote(id, normalized)
                persistIndex()
            }
        } catch (t: Throwable) {
            indexRemove(id)
            throw t
        }
        return VaultNote(id, normalized)
    }

    /** @return 更新后的笔记；id 不存在时返回 null。 */
    fun update(id: String, payload: NotePayload): VaultNote? {
        requireUnlocked()
        val existing = index[id] ?: return null
        val normalized = payload.copy(
            createdAt = existing.createdAt,
            updatedAt = clock(),
        ).normalized()

        indexPut(id, normalized)
        try {
            rows.inTransaction {
                writeNote(id, normalized)
                persistIndex()
            }
        } catch (t: Throwable) {
            indexPut(id, existing)
            throw t
        }
        return VaultNote(id, normalized)
    }

    /**
     * @return 是否确实删掉了东西。
     *
     * ## 为什么图片放在最后删
     *
     * 图片密文是整个应用里**唯一不可再生的数据**：笔记正文可以从别处誊抄，
     * 而一张已加密的照片删掉就没了。所以删除顺序按"可恢复性"从低到高排：
     *
     * 1. 行 + 清单（同一个事务）—— 失败则整体回滚，什么都没变
     * 2. 图片密文 —— 即使这一步失败，留下的也只是可被 [purgeOrphanBlobs]
     *    扫掉的孤儿文件，而不是"条目还在、照片打不开"
     */
    fun delete(id: String): Boolean {
        requireUnlocked()
        val existing = index[id] ?: return false

        indexRemove(id)
        try {
            rows.inTransaction {
                rows.delete(id)
                persistIndex()
            }
        } catch (t: Throwable) {
            indexPut(id, existing)
            throw t
        }

        // 图片与视频的密文都要销毁。**漏掉 videos 会得到一个很隐蔽的后果**：
        // 记录没了、界面上干净了，但 GB 级的视频密文永远留在附件目录里，
        // 占用页面上也只会看到"存储占用"莫名其妙很高，而没有任何一条记录对得上。
        existing.images.forEach { blobs.delete(it.blobId) }
        existing.videos.forEach { blobs.delete(it.blobId) }
        return true
    }

    // ==================== 附件（图片 / 视频） ====================

    /**
     * 加密保存一张图片，返回它的 blobId。
     *
     * 入参是**相册交出来的原始字节**（原图直存，用户明确要求）：本层不压缩、
     * 不重编码、不动 EXIF，写出的密文 = 12 字节 nonce + 明文 + 16 字节认证标签，
     * 因此"库里的图片"与"相册里的原图"是同一份像素数据，体积也基本相等。
     * 代价是原图自带的拍摄位置、设备型号等元数据会一并进库。
     *
     * 与"副本"相关的两点行为，排查存储问题时容易踩：
     * 1. 写入即与相册脱钩——只留一个随机 blobId，相册侧删改不影响本库；
     * 2. 读取是按需单张（[image]），解锁时不会批量解密图片。
     *    新增照片不会拖慢解锁，只会线性占用沙箱空间。
     */
    fun putImage(plaintext: ByteArray): String {
        // 上限兜底：原图直存后单张体积由相册决定，常见手机照 2~12 MB，
        // 高像素 RAW / 全景 / 扫描件可能几十 MB。这里挡的是"解码侧被拖垮"——
        // 图片要解成 bitmap 才能显示，一张巨图足以让低端机 OOM，
        // 而不是担心解锁（解锁只读索引，不碰图片字节）。
        require(plaintext.size <= MAX_IMAGE_PLAINTEXT_BYTES) {
            val mb = plaintext.size / (1024.0 * 1024.0)
            "图片有 %.1f MB，超过单张 %d MB 的上限，请先在系统相册里裁剪或缩小后再导入"
                .format(mb, MAX_IMAGE_PLAINTEXT_BYTES / (1024 * 1024))
        }
        val blobId = idFactory()
        val sealed = AeadCipher.seal(requireUnlocked(), plaintext, Aad.blob(blobId))
        blobs.write(blobId, sealed.nonce, sealed.ciphertext)
        return blobId
    }

    /**
     * 读一份**整块格式**的附件（图片、实况照片）。
     *
     * ## 分块格式必须在这里就被挡掉（这条是补上的，不是一开始就想清楚的）
     *
     * [BlobSink.read] 是 `file.readBytes()` —— 整份密文先进堆。对图片这没问题（几 MB），
     * 对视频则是**一次 OOM**：视频上限 2 GiB，而"图片路径"有好几个入口
     * （缩略图、大图、实况探测）都可能被一个视频 blob 撞上，
     * 撞上之后的下场不是"报错说这不是图片"，而是整个进程被系统杀掉。
     *
     * 所以这里按魔数先分流，视频一律返回 null（= 读不出来），
     * 走视频的一律用 [openBlob] 拿随机访问读取器。
     */
    fun image(blobId: String): ByteArray? {
        if (!RowIds.isValid(blobId)) return null
        if (isChunked(blobId)) return null
        return readWhole(blobId)
    }

    /**
     * 整块格式的解密读取。**调用方必须先确认它不是分块格式** ——
     * 这里不做检查，是因为 [openBlob] 已经查过一遍，再查就是把文件头读两遍。
     */
    private fun readWhole(blobId: String): ByteArray? {
        val blob = blobs.read(blobId) ?: return null
        return AeadCipher.open(requireUnlocked(), blob.nonce, blob.ciphertext, Aad.blob(blobId))
    }

    /**
     * 这个附件是不是分块格式（视频）。
     *
     * 只读 24 字节文件头 + 一次 `length()`，可以放心放在热路径上；
     * 判据的可靠性由 [BlobSink.chunkHeader] 负责（魔数 + 长度双校验）。
     */
    private fun isChunked(blobId: String): Boolean = blobs.chunkHeader(blobId) != null

    fun deleteImage(blobId: String): Boolean {
        requireUnlocked()
        if (!RowIds.isValid(blobId)) return false
        return blobs.delete(blobId)
    }

    // ==================== 视频 ====================

    /**
     * 流式加密保存一段视频，返回它的 blobId 与明文长度。
     *
     * ## 为什么不是 `putVideo(bytes: ByteArray)`
     *
     * 上限是 [MAX_VIDEO_PLAINTEXT_BYTES]（2 GiB）。把 2 GiB 先读进堆再交给这里，
     * 等于在设计上就写死了"这段功能只能用于小视频" —— 而手机随手一段 4K 就几百 MB。
     * 所以入参是**流**：本层按 [ChunkedBlobFormat.DEFAULT_CHUNK_BYTES] 一块一块地读、
     * 加密、落盘，任何时刻只有两块（含超前读的那一块）明文在内存里。
     *
     * 超限时 [BlobSink.writeChunked] 直接抛 [com.fpb.vault.data.BlobTooLargeException]，
     * 且**不留下任何文件**（临时文件在 finally 里删掉）。这里刻意不把它转成 `require`：
     * "素材太大"与"代码写错了"必须能被上层区分开 —— 前者要告诉用户
     * "这个视频有 2.4 GB，超过上限"，后者只该走崩溃或日志。
     *
     * 明文的宽高与时长不在这里取（那要 Android 运行时，见 [StoredVideo] 的说明）。
     */
    fun putVideo(source: InputStream): StoredVideo {
        val dek = requireUnlocked()
        val blobId = idFactory()
        val chunkSize = ChunkedBlobFormat.DEFAULT_CHUNK_BYTES
        val write = blobs.writeChunked(
            blobId = blobId,
            source = source,
            chunkSize = chunkSize,
            limitBytes = MAX_VIDEO_PLAINTEXT_BYTES,
        ) { index, plain, length, isLast ->
            // 只加密这一块实际有的字节。多送一个字节都不行 ——
            // 块边界的字节数直接决定偏移算术，而偏移是**不查表**算出来的。
            AeadCipher.seal(
                dek,
                plain.copyOf(length),
                Aad.blobChunk(blobId, index, chunkSize, isLast),
            )
        }
        return StoredVideo(blobId, write.plainBytes)
    }

    /**
     * 打开任意附件的**随机访问**读取器（图片、视频通用）。
     *
     * 按文件内部的魔数自动分流：
     * - 分块格式（视频）→ [ChunkedBlobReader]，读哪块解哪块；
     * - 整块格式（图片、实况照片）→ 整份解密后包成 [ByteArrayBlobReader]。
     *
     * 返回 null 表示"读不出来"（文件不存在、密文损坏、认证失败），
     * 调用方一律按"这个附件打不开"处理 —— 与 [image] 的约定一致。
     *
     * 调用方负责 [BlobReader.close]。
     */
    fun openBlob(blobId: String): BlobReader? {
        val dek = requireUnlocked()
        if (!RowIds.isValid(blobId)) return null

        val header = blobs.chunkHeader(blobId)
        if (header != null) {
            // AAD 里的块大小取自**从文件读回来的头**，与写入时回填的那个必须是同一个值。
            // 头被改过时，块大小一变，AAD 就对不上，解密一律失败 —— 这是有意的。
            return blobs.openChunked(blobId) { index, nonce, ciphertext, isLast ->
                AeadCipher.open(
                    dek,
                    nonce,
                    ciphertext,
                    Aad.blobChunk(blobId, index, header.chunkSize, isLast),
                )
            }
        }

        val plaintext = readWhole(blobId) ?: return null
        return ByteArrayBlobReader(plaintext)
    }

    /**
     * 这个附件是不是可以用当前 DEK 解开的（归属判定，供孤儿清扫使用）。
     *
     * ## 视频这里有一条必须守住的规模纪律
     *
     * 老实现（只处理图片）是"把整个密文解一遍"。对视频**绝不能照搬** ——
     * 一个 2 GiB 的视频会让这一步直接把堆吃光，而孤儿清扫是启动路径上的操作。
     * 分块格式存在的意义正是"不必整段解密"，所以视频只解**第 0 块**：
     * 第 0 块的 AAD 绑定了 blobId 与块大小，能解开就说明它属于当前域。
     */
    private fun ownedByThisVault(blobId: String): Boolean {
        val dek = requireUnlocked()
        val header = blobs.chunkHeader(blobId)
        if (header != null) {
            val reader = blobs.openChunked(blobId) { index, nonce, ciphertext, isLast ->
                AeadCipher.open(
                    dek,
                    nonce,
                    ciphertext,
                    Aad.blobChunk(blobId, index, header.chunkSize, isLast),
                )
            } ?: return false
            return try {
                // 只碰第一块，读到 1 个字节就够：认证发生在解密的那一刻。
                reader.readAt(0, ByteArray(1), 0, 1) == 1
            } catch (e: java.io.IOException) {
                false
            } finally {
                reader.close()
            }
        }

        val blob = blobs.read(blobId) ?: return false
        return AeadCipher.open(dek, blob.nonce, blob.ciphertext, Aad.blob(blobId)) != null
    }

    /**
     * 清理不再被任何笔记引用的附件密文（图片与视频）。
     *
     * ## 这里有个会毁掉另一个库的陷阱
     *
     * 最直觉的写法是"磁盘上存在、但当前索引里没引用到的 blobId 就是孤儿"。
     * 但如果当前会话是**诱饵库**，真库的附件恰好全部符合这个描述 ——
     * 一次"清理垃圾"就会把真库的照片与视频全部删掉，而且悄无声息。
     *
     * 因此这里加了一道判定：只有能用**当前域的 DEK 解开**的附件才算自己的孤儿
     * （见 [ownedByThisVault]，它对视频只解第一块，不会把一个 2 GiB 的视频读进内存）。
     * 代价是要对密文跑一次认证（几 MB 的图片约几毫秒），
     * 但这是个手动触发、低频次的操作，换来的是"不可能误删另一个库"。
     */
    fun purgeOrphanBlobs(): Int {
        // **视频也要算进"活着"的集合**。漏掉的话，每一次孤儿清扫都会把库里
        // 全部视频的密文当作垃圾删掉 —— 而记录还留着，表现为"视频全变成打不开的黑块"。
        val live = index.values
            .flatMap { it.images.map(ImageRef::blobId) + it.videos.map(VideoRef::blobId) }
            .toSet()

        var removed = blobs.purgeStaleTempFiles()
        blobs.listIds().forEach { blobId ->
            if (blobId in live) return@forEach
            if (ownedByThisVault(blobId) && blobs.delete(blobId)) removed++
        }
        return removed
    }

    // ==================== 内部：索引清单 ====================

    /**
     * 本域清单所在行的 id。
     *
     * 用 DEK 做 HMAC 派生，而不是直接写个固定名字（如 `"__index__"`）：
     * - 没有 DEK 就猜不出这个 id，也就无法针对性地破坏它
     * - 它和普通笔记的 id **形状完全一致**（都是 32 位随机十六进制），
     *   因此从数据库里看，它与其它行无法区分 —— 不会暴露"哪一行是索引"
     * - 两个域各自得到不同的 id，于是"存在两个清单"也不会显露出来
     */
    private fun manifestRowId(): String {
        val dek = requireUnlocked()
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(dek.expose(), HMAC_ALGORITHM))
        return mac.doFinal(MANIFEST_MAC_INPUT).copyOf(MANIFEST_ID_BYTES).toHex()
    }

    private fun loadIndex(): LoadReport {
        index.clear()
        searchCache.clear()
        brokenIds.clear()

        val manifestId = manifestRowId()
        val manifestRow = rows.load(manifestId) ?: return LoadReport.fresh()
        // ↑ 清单行**不存在**时一律当作"全新的库"。
        //
        // 这是本层唯一一处无法彻底消除的模糊判断，必须说清楚它为什么只能这样：
        // 数据库文件里同时躺着真库和诱饵库的行，而它们的形状完全一样。
        // 因此"清单行不见了"有两种可能 —— 这是一个还没有任何内容的新库，
        // 或者清单行随损坏一起丢了。二者在字节层面**不可区分**。
        //
        // 若改成"只要库里还有行就拒绝解锁"，就会误伤诱饵库第一次被打开的场景
        // （真库有行、诱饵库还没写过，于是假密码反而打不开）——
        // 那等于把胁迫防护直接废掉，代价比这个模糊判断严重得多。
        //
        // 真正把这条窗口关掉的是写入侧的原子性（见 create/update/delete 里的事务）：
        // 只要清单与笔记行同时提交，"写了一半"就不可能发生，
        // 剩下的只有真正的存储介质损坏。

        val listBytes = openRow(manifestId, manifestRow, INDEX_AAD_FIELD)
            ?: return LoadReport(0, 0, 0, manifestUnreadable = true, isFresh = false)

        val ids = try {
            NoteIndexCodec.decode(listBytes)
        } finally {
            SecureBytes.zeroize(listBytes)
        } ?: return LoadReport(0, 0, 0, manifestUnreadable = true, isFresh = false)

        var missing = 0
        var unreadable = 0
        for (id in ids) {
            if (index.containsKey(id)) continue
            val row = rows.load(id)
            if (row == null) {
                missing++
                // 记入 brokenIds，随下一次写入一起保留在清单里。
                // 若不保留，这次解锁报告的"缺失/不可读"会在任何一次写入后
                // 被静默遗忘，且这些行从此失去域归属凭证（见字段说明）。
                brokenIds.add(id)
                continue
            }
            val payload = readPayload(id, row)
            if (payload == null) {
                unreadable++
                brokenIds.add(id)
                continue
            }
            indexPut(id, payload)
        }

        return LoadReport(
            noteCount = index.size,
            missingRows = missing,
            unreadableRows = unreadable,
            manifestUnreadable = false,
            isFresh = false,
        )
    }

    private fun persistIndex() {
        val manifestId = manifestRowId()
        // 清单 = 可读条目 + 仍然缺失/不可读的条目。后者必须保留：
        // 它们是"这行属于本域"的唯一记录，也是每次解锁持续报告问题的依据。
        val listBytes = NoteIndexCodec.encode(index.keys.toList() + brokenIds)
        try {
            val sealed = AeadCipher.seal(
                requireUnlocked(),
                listBytes,
                Aad.entryField(manifestId, INDEX_AAD_FIELD),
            )
            rows.upsert(CipherRow(manifestId, sealed.nonce, sealed.ciphertext))
        } finally {
            SecureBytes.zeroize(listBytes)
        }
    }

    // ==================== 内部：笔记读写 ====================

    private fun writeNote(id: String, payload: NotePayload) {
        val plain = NoteCodec.encode(payload)
        try {
            val sealed = AeadCipher.seal(
                requireUnlocked(),
                plain,
                Aad.entryField(id, PAYLOAD_AAD_FIELD),
            )
            rows.upsert(CipherRow(id, sealed.nonce, sealed.ciphertext))
        } finally {
            // 编码后的明文是这段内容在内存里存在时间最长的一份副本，
            // 用完立刻覆盖，缩小它被后续内存转储捞到的窗口。
            SecureBytes.zeroize(plain)
        }
    }

    private fun readPayload(id: String, row: CipherRow): NotePayload? {
        val plain = openRow(id, row, PAYLOAD_AAD_FIELD) ?: return null
        return try {
            NoteCodec.decode(plain)
        } finally {
            SecureBytes.zeroize(plain)
        }
    }

    private fun openRow(id: String, row: CipherRow, field: String): ByteArray? =
        AeadCipher.open(
            requireUnlocked(),
            row.nonce,
            row.ciphertext,
            Aad.entryField(id, field),
        )

    private fun indexPut(id: String, payload: NotePayload) {
        index[id] = payload
        searchCache[id] = payload.searchableText()
    }

    private fun indexRemove(id: String) {
        index.remove(id)
        searchCache.remove(id)
    }

    private fun requireUnlocked(): VaultDek =
        dek ?: throw LockedException()

    /**
     * 在写入之前就挡住"清单会写不下"的情况。
     *
     * 如果等到 [persistIndex] 落盘时才发现超限，就已经晚了：那一次清单写入
     * 要么让整个事务回滚（做了白做），要么在最坏的情况下写出一份解不回来的清单，
     * 从此每次解锁都判定"清单不可读"。因此这里在**动任何数据之前**先拒绝，
     * 让失败发生在用户能理解、能处理的位置。
     */
    private fun requireManifestCapacity(adding: Int) {
        // 清单长度既含可读条目也含 brokenIds（它们随清单一起落盘），
        // 超限必须发生在动任何数据之前，而不是清单写入的事务里。
        val next = index.size + brokenIds.size + adding
        if (next > NoteIndexCodec.MAX_ENTRIES) {
            throw IllegalStateException(
                "条目数量已达上限 ${NoteIndexCodec.MAX_ENTRIES}，无法继续新增" +
                    "（当前 ${index.size} 条，另有 ${brokenIds.size} 条待修复记录）",
            )
        }
    }

    companion object {

        /** 默认自动锁定 1 分钟 —— 与 UI 原型确认的取值一致。 */
        const val DEFAULT_AUTO_LOCK_MILLIS = 60_000L

        /**
         * 单张图片明文允许的最大字节数（32 MiB），仅对导入生效。
         *
         * 原图直存后单张体积由相册决定，这个上限挡的是**显示侧解码**：
         * 一张 32 MiB 的 JPEG 解成 bitmap 可达数百 MB，低端机会直接 OOM。
         * 与解锁无关——解锁只读索引清单，不解密图片字节。
         */
        const val MAX_IMAGE_PLAINTEXT_BYTES = 32 * 1024 * 1024

        /**
         * 单条视频明文允许的最大字节数（2 GiB）。
         *
         * 它比图片上限大两个数量级，是因为**约束的性质完全不同**：
         * 图片那条 32 MiB 挡的是"解码成 bitmap 时的内存"（一张 32 MiB 的 JPEG
         * 解出来是几百 MB），而视频走分块解密，播放时内存只占一个缓存窗口。
         * 视频这里挡的是**磁盘占用**与"用户是不是选错了文件"。
         *
         * 2 GiB 覆盖了手机上几乎所有日常拍摄：1080p30 约 2 MB/s（约 17 分钟），
         * 4K60 约 20 MB/s（约 100 秒）。再长的素材放不进来，会有明确提示，
         * 而不是静默失败。
         *
         * 注意它与 [ChunkedBlobFormat.MAX_PLAIN_BYTES]（4 GiB）不是一回事：
         * 那是格式层面的合理性边界（防一个被改过的头让我们去分配天文数字），
         * 这里是产品决定。将来放宽这里，格式那边不用动。
         */
        const val MAX_VIDEO_PLAINTEXT_BYTES = 2L * 1024 * 1024 * 1024

        private const val MANIFEST_ID_BYTES = 16
        private const val INDEX_AAD_FIELD = "index"
        private const val PAYLOAD_AAD_FIELD = "payload"
        private const val HMAC_ALGORITHM = "HmacSHA256"

        private val MANIFEST_MAC_INPUT = "mixia:index:v1".toByteArray(StandardCharsets.US_ASCII)

        /**
         * 提前把 Argon2id 的代码路径焐热。
         *
         * 模拟器实测：首次派生 **29 046 ms**，预热后 **1 144 ms**。差 25 倍，
         * 全部来自 ART 的 JIT 解释执行 + 首次类加载 + 64 MiB 大数组的首次分配。
         * 这段开销只发生在进程启动后的第一次派生，但它恰好落在用户
         * "输完密码按下解锁"的那一刻 —— 不处理的话，首次解锁要干等半分钟。
         *
         * **必须在后台线程调用**，否则会把界面卡住 1 秒以上。
         */
        fun warmUpKdf(params: KdfParams = KdfParams.standard()) {
            val kek = Argon2Kdf.derive(WARMUP_PASSWORD, params)
            kek.close()
        }

        private val WARMUP_PASSWORD = "mixia-kdf-warmup".toCharArray()
    }
}
