package com.fpb.vault.session

import com.fpb.vault.codec.NoteCodec
import com.fpb.vault.codec.NoteIndexCodec
import com.fpb.vault.crypto.Aad
import com.fpb.vault.crypto.AeadCipher
import com.fpb.vault.crypto.Argon2Kdf
import com.fpb.vault.crypto.KdfParams
import com.fpb.vault.crypto.SecureBytes
import com.fpb.vault.crypto.TransientSecretKey
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

        /**
         * 给用户看的那句话。库干净时是 null（界面据此不显示告警条）。
         *
         * **两类问题必须并列出现，不能挑一个说。** 它们的原因和修法都不同：
         * 解密失败指向"这一段字节坏了"（存储介质出问题、或被改过），
         * 记录丢失指向"数据库里那一行没了"（被清理、或清单与库不同步）。
         * 用一个 `when` 只报前一类，另一类就永远看不见 —— 用户照着提示去查存储，
         * 而真正少掉的那几条还是不见踪影，最后只能得出"这个应用会自己丢数据"。
         *
         * 放在数据类上而不是界面里，是因为它是这个数据**自己的**说法：
         * 界面只负责把它显示出来。这样它也就成了可以直接断言的东西
         * —— 这条缺陷原先正因为藏在界面的私有方法里，才躲过了所有测试。
         */
        fun warning(): String? {
            val parts = listOfNotNull(
                unreadableRows.takeIf { it > 0 }
                    ?.let { "$it 条内容解密失败（存储可能已损坏）" },
                missingRows.takeIf { it > 0 }
                    ?.let { "$it 条记录在数据库里找不到了" },
            )
            return if (parts.isEmpty()) null else parts.joinToString("、", prefix = "有")
        }

        companion object {
            fun fresh() = LoadReport(0, 0, 0, manifestUnreadable = false, isFresh = true)
        }
    }

    /**
     * 本域审计槽的状态。
     *
     * ## 为什么"没有槽"与"槽读不出来"必须分开
     *
     * 两者在读数上都是"读不到诱饵那本账"，而它们对用户的意义相反：
     *
     * - [Absent]：这个功能还没建立。属于**正常状态**，界面给一个补建的入口就好。
     * - [Damaged]：槽在，但解不出来（被改过、或存储损坏）。这是**要报出来的事**。
     *
     * 合并成一个可空值，就会把第二种显示成第一种 —— 而"把一次可能的入侵
     * 说成一切正常"正是这个功能最不该犯的错。
     */
    sealed interface AuditSlot {
        /** 没有槽：还没建立审计通道（或已经被拆掉）。 */
        data object Absent : AuditSlot

        /** 槽在，但解不出来。调用方**不要**把它当成"没有槽"。 */
        data object Damaged : AuditSlot

        /** 槽可用。调用方负责把它 [AuditKey.close]。 */
        class Present(val key: AuditKey) : AuditSlot
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

    /** 本域登录账本。解锁时载入，锁定时随其余明文一起清掉（见 [clearState]）。 */
    private var loginLog: LoginLog = LoginLog.EMPTY

    /**
     * 账本**读不出来**（而不是"一条都没有"）。
     *
     * 与 [loginLog] 并存而不是从"账本为空"反推：两者在读数上一模一样，
     * 而它们对用户的意义相反 —— 一个是"没人进来过"，一个是"记录被人动过手"。
     */
    private var loginLogUnreadable: Boolean = false

    /**
     * 诱饵会话的账本钥匙。
     *
     * ## 两本账用的是两把不同的钥匙，这是本文件的重点
     *
     * | 会话 | 账本钥匙 | 行 id 的派生材料 |
     * | --- | --- | --- |
     * | 真库 | 真库 DEK | 真库 DEK |
     * | 诱饵库 | [AuditKey] | [AuditKey] |
     *
     * 两边**都不用诱饵 DEK**。于是：
     *
     * - 真库 DEK 泄露 → 能读假密码的登录时间线，**读不到诱饵库的任何笔记**
     * - 诱饵 DEK 泄露 → 能读诱饵库全部内容，**读不到真库那本账**
     *   （连它的行 id 都算不出来）
     *
     * 这就是「真库借出的是一把只够看账本的钥匙」在代码里的落点。
     * 换回"真库侧存一份诱饵 DEK 副本"的做法会让第一条失效 ——
     * 那时真库 DEK 一泄露就等于诱饵库整个泄掉，而被胁迫时交出去的那个库
     * 本来正是最后一道掩护。
     *
     * 只有诱饵会话会在解锁时给它赋值（见 [prepareLoginLog]），真库侧恒为 null：
     * 真库读那本账靠的是本域的**审计槽**（见 [auditSlot]），那是一条不同的路径。
     */
    private var auditKey: AuditKey? = null

    /**
     * 是否处于解锁态。**这是"当前锁没锁"的唯一事实来源。**
     *
     * 这里原先还有一套 `State` 枚举 + `state` 属性 + `onStateChanged` 回调，
     * 注释说它是"供界面在自动锁定时跳回解锁页"。实际上全工程没有一处给那个回调
     * 赋过值，跳回解锁页是由界面自己的 `onForegrounded()` / `tick()` 完成的
     * —— 而它们本来就掌握"锁定发生"这一刻，不需要谁来通知。
     *
     * 一套只被测试用、却被注释描述成正在干活的机制，比一段明显的死代码更危险：
     * 读代码的人会以为锁定的通知走它，于是漏掉真正的路径。
     */
    val isUnlocked: Boolean get() = dek != null

    /** 当前打开的库。锁定时为 null。 */
    val domain: VaultDomain? get() = dek?.domain

    /** 是否为诱饵库。为 true 时上层必须禁用改密码、换恢复码等会污染真库密钥的操作。 */
    val isDecoy: Boolean get() = dek?.domain == VaultDomain.DECOY

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
        // 注意这一次清理**不代表"用户被锁了"**：真正的锁定只由 [lock] 表达，
        // 界面也只在 [lock] 之后才回解锁页。把这里当成锁定事件会让
        // "界面重建""解锁失败后重试"这类正常路径触发一次莫名其妙的闪回。
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
            // 账本与清单的处理刻意不同：清单坏了解锁必须失败（写进去会毁掉索引），
            // 而账本坏了只是"这一段记录看不到"，不该把用户挡在门外 ——
            // 它只落到 loginLogUnreadable 上，由界面如实说出"读不出来"。
            prepareLoginLog()
        } catch (t: Throwable) {
            clearState()
            newDek.close()
            throw t
        }
        return loadReport
    }

    /** 锁定并清零密钥。可重复调用。 */
    fun lock() {
        clearState()
    }

    /** 清空一切解锁态数据。 */
    private fun clearState() {
        dek?.close()
        dek = null
        index.clear()
        searchCache.clear()
        brokenIds.clear()
        backgroundedAt = null
        loadReport = LoadReport.fresh()
        // 账本里的时刻是"谁什么时候进来过"，属于明文，必须随其余明文一起消失。
        // 漏掉这两行的后果是锁屏之后内存里还躺着一份完整的出入记录。
        loginLog = LoginLog.EMPTY
        loginLogUnreadable = false
        // 审计钥匙同理：它不是笔记的钥匙，但它是"这个库被人用过"的完整线索。
        // 留在内存里等于把诱饵库那本账的入口挂在锁屏之后。
        auditKey?.close()
        auditKey = null
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

    /**
     * 库占用的**分类明细**。
     *
     * [storageBytes] 只回答"一共占了多少"，而设置页里用户想问的是
     * "**是什么**占了这么多" —— 尤其是"我删了那么多记录，空间为什么没降下来"，
     * 答案通常落在 [orphanBytes] 或 [tempBytes] 上。
     *
     * ## 明文大小为什么要单独算
     *
     * 磁盘上存的是密文，比明文多出固定开销（每块 28 字节，分块格式再加 24 字节头）。
     * 用户心里的"大小"是**明文的那个数**（"我导入的是一段 2.1 GB 的视频"），
     * 两个数都给出来，才不会出现"应用说占 2.1 GB、我明明只放了 2.1 GB"的疑惑。
     *
     * 视频的明文长度直接从分块头读（[BlobSink.chunkHeader]），不必额外落一个字段 ——
     * 头部本来就要记它，否则读不出块边界。
     */
    data class StorageUsage(
        /** 数据库里全部行（含清单行）的密文占用。 */
        val rowBytes: Long,
        val photoBytes: Long,
        val videoBytes: Long,
        val photoPlainBytes: Long,
        val videoPlainBytes: Long,
        /**
         * 磁盘上**真实存在**的附件数量。
         *
         * 与 [photoBytes] 同源：两者都只在"这个文件确实躺在附件目录里"时才计数。
         * 拿"记录里引用了几个"来代替会得到一行"照片 5 张 · 3 MB"，
         * 而其中一张的文件其实早就没了 —— 用户从这一行上看不出任何异常，
         * 也不会想到那条记录里的某张图已经永远打不开了。
         */
        val photoCount: Int,
        val videoCount: Int,
        /**
         * 记录里引用了、文件却不在磁盘上的附件数。
         *
         * 与 [orphanCount] 是**方向相反**的两件事：孤儿是"文件多出来了"，
         * 这一项是"文件不见了"。两者都不能藏：前者白占空间，后者意味着
         * 某条记录里已经有东西打不开，而在此之前界面上没有任何地方提过它。
         *
         * 这一项不并进 [totalBytes] —— 它描述的是**缺失**，不是占用。
         */
        val missingPhotoCount: Int,
        val missingVideoCount: Int,
        /**
         * 目录里存在、但没有任何本库记录引用的附件。
         *
         * 这里**不判** [ownedByThisVault]：那是"能不能删"的判据，要跑一次密文认证；
         * 而统计只是报数。代价是它可能把另一个域（诱饵库）的附件也数进来 ——
         * 所以界面上这一项的文案是"未被引用的附件"，动作是用户自己点清理，
         * 而真正的删除永远由 [purgeOrphanBlobs] 按域判定，绝不会误删另一个库。
         */
        val orphanBytes: Long,
        val orphanCount: Int,
        /** 写入中断留下的半截文件（[BlobSink.staleTempBytes]）。 */
        val tempBytes: Long,
    ) {
        val totalBytes: Long get() = rowBytes + photoBytes + videoBytes + orphanBytes + tempBytes
    }

    /**
     * 统计占用明细。
     *
     * 锁定时返回 null，而**不是**一份"全部都是孤儿"的表：分类靠的是内存索引里
     * "哪些 blobId 被引用"，而索引在锁定时是空的 —— 那种回答不是不精确，是反的。
     */
    fun storageUsage(): StorageUsage? {
        if (dek == null) return null

        val (referencedPhotos, referencedVideos) = referencedBlobIds()

        var photoBytes = 0L
        var photoPlainBytes = 0L
        var photoCount = 0
        var videoBytes = 0L
        var videoPlainBytes = 0L
        var videoCount = 0
        var orphanBytes = 0L
        var orphanCount = 0

        blobs.listIds().forEach { blobId ->
            val stored = blobs.sizeOf(blobId)
            when {
                blobId in referencedPhotos -> {
                    photoBytes += stored
                    photoPlainBytes += plainBytesOf(blobId, stored)
                    photoCount++
                }

                blobId in referencedVideos -> {
                    videoBytes += stored
                    videoPlainBytes += plainBytesOf(blobId, stored)
                    videoCount++
                }

                else -> {
                    orphanBytes += stored
                    orphanCount++
                }
            }
        }

        return StorageUsage(
            rowBytes = rows.totalBytes(),
            photoBytes = photoBytes,
            videoBytes = videoBytes,
            photoPlainBytes = photoPlainBytes,
            videoPlainBytes = videoPlainBytes,
            // 张数与字节在**同一个分支里一起累加**，因此两者不可能对不上：
            // 一个文件要么同时进了字节和张数，要么两边都没进。
            photoCount = photoCount,
            videoCount = videoCount,
            missingPhotoCount = referencedPhotos.size - photoCount,
            missingVideoCount = referencedVideos.size - videoCount,
            orphanBytes = orphanBytes,
            orphanCount = orphanCount,
            tempBytes = blobs.staleTempBytes(),
        )
    }

    /**
     * 索引里被引用的附件 id，按引用它的字段分成两组。
     *
     * **这个集合在全工程只能有一处定义。** 它有两个消费者，而两边的出错后果不对等：
     * - [purgeOrphanBlobs] 拿它决定"什么算垃圾"。漏掉一种附件类型，清理就会
     *   把那一类全部**当垃圾删掉**，而记录还留着 —— 用户看到的是"附件全变成
     *   打不开的黑块"，且不可逆。
     * - [storageUsage] 拿它给占用分类。漏掉只会让那一类显示成"未被引用"。
     *
     * 这个坑踩过一次：原先只算了图片，视频被当孤儿。当时的对策是在原地加一句
     * 注释提醒"视频也要算进去"。但注释拦不住第二个人、也拦不住第二次 ——
     * 只有把集合收敛成一处，新增一种附件类型（比如音频）时才不存在
     * "改了一处忘了另一处"的可能。
     */
    private fun referencedBlobIds(): Pair<Set<String>, Set<String>> {
        val photos = HashSet<String>()
        val videos = HashSet<String>()
        index.values.forEach { note ->
            note.images.forEach { photos += it.blobId }
            note.videos.forEach { videos += it.blobId }
        }
        return photos to videos
    }

    /**
     * 附件密文对应的明文字节数。
     *
     * 视频（分块格式）从头部读；其余（整块格式的图片）用总长减固定开销反推 ——
     * 整块格式就是 `nonce + 密文`，而密文 = 明文 + 认证标签，所以减法成立。
     * 这样统计几百张图时只需要读几个文件头，不必把图**解密**出来只为量一下大小。
     */
    private fun plainBytesOf(blobId: String, storedBytes: Long): Long {
        blobs.chunkHeader(blobId)?.let { return it.plainSize }
        val overhead = AeadCipher.OVERHEAD_BYTES.toLong()
        return if (storedBytes > overhead) storedBytes - overhead else 0L
    }

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
        val (imageIds, videoIds) = referencedBlobIds()
        val live = imageIds + videoIds

        var removed = blobs.purgeStaleTempFiles()
        blobs.listIds().forEach { blobId ->
            if (blobId in live) return@forEach
            if (ownedByThisVault(blobId) && blobs.delete(blobId)) removed++
        }
        return removed
    }

    // ==================== 登录记录 ====================
    //
    // 这一段回答的问题是"谁在什么时候进来过"。三样落盘物，全部沿用本类已有的两条纪律
    // （行 id 由 DEK 做 HMAC 派生、每域各自一份密文），因此：
    //
    // | 落盘物 | 行 id 由谁算出 | 用什么封 |
    // | --- | --- | --- |
    // | 本域登录账本 | 本域 DEK | 本域 DEK |
    // | 真库侧的诱饵钥匙镜像 | 真库 DEK | 真库 DEK |
    //
    // ## 为什么必须多存一份"诱饵钥匙的镜像"
    //
    // 真库与诱饵库的行混在同一张表里、各自只能用各自的 DEK 解（见类注释）。
    // 于是"假密码登录过"这件事，**真库会话在没有额外帮助的情况下永远读不到** ——
    // 它连那本账所在行的 id 都算不出来（id 是 `HMAC(诱饵DEK, 标签)`）。
    //
    // 所以真库侧额外保存一份**诱饵 DEK 的副本**（用真库 DEK 封），
    // 解锁时用它把假库那本账读出来。方向是**单向的**：
    // - 真库 → 能读两本账（真库自己那把 + 镜像给的诱饵钥匙）
    // - 假库 → 只能读自己那本。它没有真库 DEK，**离线也算不出真库那行叫什么名字**，
    //   因此被胁迫时交出假密码，对方在应用里看不到任何"真库被用过"的痕迹。
    //
    // 反过来的设计（两域共用一把"审计密钥"）实现更省事，但会让假密码能解出
    // **全部**记录、包括真密码的登录时间 —— 那正好是被胁迫场景里最不该给出去的东西。
    //
    // ## 代价（要如实写下来）
    //
    // 真库持有诱饵 DEK 的副本，意味着**知道真密码的人也能打开诱饵库那本账**。
    // 这不是新增的泄露：拿到真密码的人本来就拿到了全部内容，
    // 而诱饵库的用途恰恰是"给人看的那个库"。
    // 真正需要守住的方向是反过来的那一侧，而它守住了。

    /**
     * 本域登录账本。解锁时已载入内存，因此这里不会碰磁盘。
     *
     * 锁定状态下抛出异常而不是返回空账本 —— 理由与 [notes] 相同，
     * 而且在"专门用来看有没有人进来过"的功能上更致命：空的会被读成"没人进来过"。
     */
    fun loginLog(): LoginLog {
        requireUnlocked()
        return loginLog
    }

    /**
     * 本域账本**读不出来**（损坏、或被改过），而不是"一条都没有"。
     *
     * 这两种情况在读数上一模一样（都是空），因此必须分开表达：界面要能把
     * "读不出来"说出来，而不是显示一个干净的"还没有记录"。
     */
    fun loginLogUnreadable(): Boolean = loginLogUnreadable

    /**
     * 记一笔。写入失败会抛异常，由调用方决定要不要让用户知道。
     *
     * **调用方不该让"记账失败"阻断解锁**：记录不是用户的内容，
     * 为它把用户挡在门外是本末倒置。但也不能静默吞掉（见 [VaultAppState] 的处理）。
     */
    fun recordLogin(kind: LoginKind, at: Long, detail: Int = 0) {
        recordLogins(listOf(LoginEvent(kind, at, detail)))
    }

    /**
     * 一次写入多条。
     *
     * ## 为什么必须能"一次写多条"
     *
     * 成功解锁时要写两件事：这次登录本身，以及"在此之前有人输错过 N 次"。
     * 分成两次写的话，中间那次失败会留下一个坏状态：失败次数已经清零、
     * 而记录没写进去 —— 那批失败**凭空消失**。合成一次写，
     * 要么两条都在，要么一条都没有（这时调用方还不该清零计数）。
     */
    fun recordLogins(events: List<LoginEvent>) {
        requireUnlocked()
        if (events.isEmpty()) return
        // 本域还没有账本通道（诱饵库未配对）时**静默跳过**。
        //
        // 这里绝不能抛异常、更不能提示：唯一会走到这条路的正是"用假密码进来的人"，
        // 对着他弹一句"登录记录写入失败"，等于当场告诉他这是个被监视的诱饵库。
        // 真库那边会明确显示"假密码那一半还没开始记录"，由用户自己补一次绑定。
        if (logKeyOrNull() == null) return
        var next = loginLog
        events.forEach { next = next.appended(it) }
        persistLoginLog(next)
        loginLog = next
        loginLogUnreadable = false
    }

    /**
     * 整本换掉（合并"另一本账"、或清空时用）。
     *
     * 与 [recordLogins] 分开，是因为合并进来的记录**不一定都比现有的新**：
     * 对着一个按时间递增的列表做追加，会得到一个乱序的列表，
     * 而 [LoginLog] 截断时丢的是"从头数起的那几条"—— 乱序之后丢的就不是最旧的了。
     */
    fun replaceLoginLog(log: LoginLog) {
        requireUnlocked()
        if (logKeyOrNull() == null) return
        persistLoginLog(log)
        loginLog = log
        loginLogUnreadable = false
    }

    /**
     * 清空本域账本。
     *
     * 清空与"账本损坏"是两件事，因此这里**只清内存与密文，不把 [loginLogUnreadable] 抹成 false**
     * 之外的东西 —— 同时也就意味着"清空之后确实读得出来了"（新写的空账本一定是合法的）。
     */
    fun clearLoginLog() {
        requireUnlocked()
        persistLoginLog(LoginLog.EMPTY)
        loginLog = LoginLog.EMPTY
        loginLogUnreadable = false
    }

    /**
     * 用**审计钥匙**读诱饵库那本账。
     *
     * 这是"有人用过假密码"能被真库发现的唯一入口。
     *
     * [auditKey] 必须来自本域审计槽（[auditSlot]）。**故意不接受诱饵 DEK**：
     * 那会把"真库只借出一把够看账本的钥匙"这条性质拆掉，而接口一旦留下这个口子，
     * 迟早会有人图省事把诱饵 DEK 传进来。
     *
     * @return `null` 表示那一行存在但读不出来（损坏/被改）；空账本表示"确实还没记过"。
     */
    fun decoyLoginLog(auditKey: AuditKey): LoginLog? {
        requireUnlocked()
        val rowId = decoyLogRowId(auditKey)
        val row = rows.load(rowId) ?: return LoginLog.EMPTY
        val plain = AeadCipher.open(
            auditKey.expose(),
            row.nonce,
            row.ciphertext,
            Aad.entryField(rowId, DECOY_LOG_AAD_FIELD),
        ) ?: return null
        return try {
            LoginLogCodec.decode(plain)
        } finally {
            SecureBytes.zeroize(plain)
        }
    }

    /**
     * 清空**诱饵库那本账**。
     *
     * 用途只有一个：用户在"登录记录"页点清空时，要把两本账都清掉。
     * 只清真库那本的话，用户会看到"清空之后列表里还留着几条假密码登录"——
     * 那一刻他会以为自己清空失败了，而其实只是另一半没被清。
     */
    fun clearDecoyLoginLog(auditKey: AuditKey) {
        requireUnlocked()
        val rowId = decoyLogRowId(auditKey)
        val plain = LoginLogCodec.encode(LoginLog.EMPTY)
        try {
            val sealed = AeadCipher.seal(
                auditKey.expose(),
                plain,
                Aad.entryField(rowId, DECOY_LOG_AAD_FIELD),
            )
            rows.upsert(CipherRow(rowId, sealed.nonce, sealed.ciphertext))
        } finally {
            SecureBytes.zeroize(plain)
        }
    }

    /**
     * 本域审计槽。真库读它，才拿得到诱饵库那本账的钥匙。
     *
     * ## 为什么是三态而不是可空的钥匙
     *
     * "没有槽"与"槽在、但解不出来"在读数上都表现为读不到那本账，
     * 而它们对用户的意义相反：前者是"这个功能还没建立"（正常状态，补一次就好），
     * 后者是"有人动过这里"。用 `AuditKey?` 表达会把第二种显示成第一种 ——
     * 而把一次可能的入侵说成"一切正常"，正是这个功能最不该犯的错。
     *
     * **只允许在真库里调用**：诱饵会话算的是另一域的行 id（由真库 DEK 派生），
     * 它算不出来，硬算也只会得到一个不存在的行 id。所以这里的域校验不是安全边界，
     * 而是把"调用方搞错了"这件事在最外层拦住。
     */
    fun auditSlot(): AuditSlot {
        if (requireUnlocked().domain != VaultDomain.REAL) return AuditSlot.Absent
        return readOwnAuditSlot()
    }

    /** 读本域审计槽。真库用它拿诱饵账本的钥匙，诱饵会话在解锁时用它找自己的钥匙。 */
    private fun readOwnAuditSlot(): AuditSlot {
        val own = requireUnlocked()
        val rowId = derivedRowIdWith(own, AUDIT_SLOT_MAC_INPUT)
        val row = rows.load(rowId) ?: return AuditSlot.Absent
        val plain = AeadCipher.open(
            own,
            row.nonce,
            row.ciphertext,
            Aad.entryField(rowId, AUDIT_SLOT_AAD_FIELD),
        ) ?: return AuditSlot.Damaged
        return try {
            // 长度也要校验：一个被写坏的槽会让审计钥匙拿着长度不对的字节去派生行 id，
            // 算出一个不存在的行，然后被显示成"假密码从没被用过"。
            if (plain.size != AeadCipher.KEY_BYTES) {
                AuditSlot.Damaged
            } else {
                AuditSlot.Present(AuditKey.of(plain))
            }
        } finally {
            SecureBytes.zeroize(plain)
        }
    }

    /**
     * 建立审计通道：把**同一把**审计钥匙，分别用真库 DEK 与诱饵 DEK 各封一份。
     *
     * ## 为什么写入顺序是契约
     *
     * 先写诱饵域那份，成功之后才写真库域那份。于是"真库域有槽"必然蕴含
     * "诱饵域也有槽" —— 真库会话看到槽时，诱饵会话那边一定写得动账本。
     *
     * 反过来先写真库域的话，会留下一个**不会报错的坏状态**：真库以为通道建好了，
     * 而诱饵库根本记不了。界面上看起来就是"假密码从来没有人用过"，
     * 一个静默的、且方向最坏的错误结论。
     *
     * [decoyDek] 只在"刚设置假密码"或"用户补一次绑定"这两种时刻拿得到 ——
     * 那也正是建立通道仅有的两个时机。
     */
    fun linkAudit(auditKey: AuditKey, decoyDek: VaultDek) {
        val own = requireUnlocked()
        require(own.domain == VaultDomain.REAL) { "只有真库可以建立审计通道" }
        require(decoyDek.domain == VaultDomain.DECOY) { "收到的不是诱饵钥匙：${decoyDek.domain}" }
        writeAuditSlot(decoyDek, auditKey)
        writeAuditSlot(own, auditKey)
    }

    /**
     * 拆除审计通道（关闭假密码时）。
     *
     * 调用方**必须先读走那本账再调这里**，否则它记录的"有人用过假密码"
     * 会永久读不出来。
     *
     * 只删真库域那一份：诱饵域那一份的行 id 由诱饵 DEK 派生，这里既算不出来，
     * 在诱饵 DEK 被销毁之后也不再需要 —— 它已经永远解不开了。
     */
    fun unlinkAudit() {
        val own = requireUnlocked()
        require(own.domain == VaultDomain.REAL) { "只有真库需要拆除审计通道" }
        rows.delete(derivedRowIdWith(own, AUDIT_SLOT_MAC_INPUT))
    }

    private fun writeAuditSlot(holder: VaultDek, auditKey: AuditKey) {
        val rowId = derivedRowIdWith(holder, AUDIT_SLOT_MAC_INPUT)
        val sealed = AeadCipher.seal(
            holder,
            auditKey.expose(),
            Aad.entryField(rowId, AUDIT_SLOT_AAD_FIELD),
        )
        rows.upsert(CipherRow(rowId, sealed.nonce, sealed.ciphertext))
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
     *
     * 登录账本、审计槽与诱饵账本用的是同一条路子（见 [derivedRowId]），
     * 加起来的行数越多，上面第二条越重要：只要有**一个**系统行长得不像笔记，
     * "哪几行是系统行"就成了一条可用的线索。
     */
    private fun manifestRowId(): String = derivedRowId(MANIFEST_MAC_INPUT)

    /**
     * 本域账本的坐标：**用哪把钥匙、行 id 由哪个标签派生、密文绑定哪个 AAD 字段**。
     *
     * 把这三样捆在一个类型里，是因为它们必须**同进同出**，而错配不会报错：
     * 只换钥匙不换标签，会算出真库那本账的行 id，然后拿审计钥匙去解真库的密文
     * （解不开，显示成"还没有记录"）；只换标签不换密钥，会对着诱饵库那本账
     * 用真库 DEK 去解（同样解不开、同样静默）。两种错法都只会让人以为
     * "没人进来过"。
     */
    private sealed interface LogKey {
        /** 真库：账本由本域 DEK 保护。 */
        class Own(val dek: VaultDek) : LogKey

        /** 诱饵库：账本由审计钥匙保护 —— **不是**诱饵 DEK。 */
        class Audit(val key: AuditKey) : LogKey
    }

    /**
     * 本域账本用的钥匙；`null` = 本域还没有可用的账本通道。
     *
     * 只有一种情况会拿到 null：诱饵会话所在的这个库还没有审计槽
     * （这台设备升级上来、或建立通道时写入失败）。那时它记不了登录 ——
     * 但**绝不能因此挡住解锁或使用**，见 [prepareLoginLog]。
     */
    private fun logKeyOrNull(): LogKey? {
        val own = dek ?: return null
        if (own.domain != VaultDomain.DECOY) return LogKey.Own(own)
        return auditKey?.let { LogKey.Audit(it) }
    }

    private fun logTag(key: LogKey): ByteArray = when (key) {
        is LogKey.Own -> LOGIN_LOG_MAC_INPUT
        is LogKey.Audit -> DECOY_LOG_MAC_INPUT
    }

    private fun logAadField(key: LogKey): String = when (key) {
        is LogKey.Own -> LOGIN_LOG_AAD_FIELD
        is LogKey.Audit -> DECOY_LOG_AAD_FIELD
    }

    private fun logRowId(key: LogKey): String = when (key) {
        is LogKey.Own -> derivedRowIdWith(key.dek, logTag(key))
        is LogKey.Audit -> derivedRowIdFrom(key.key.expose(), logTag(key))
    }

    /**
     * 诱饵账本的行 id。
     *
     * 真库会话算它时手上**只有审计钥匙**，这正是设计要的：没有诱饵 DEK，
     * 算不出诱饵库任何一条笔记的行 id。
     */
    private fun decoyLogRowId(auditKey: AuditKey): String =
        derivedRowIdFrom(auditKey.expose(), DECOY_LOG_MAC_INPUT)

    /**
     * 用 DEK 派生一个系统行的 id。
     *
     * 四个系统行（清单、真库账本、审计槽、诱饵账本）共用这一个实现：
     * 它们的 id 都必须是"32 位十六进制、形状与笔记行一致"，才有
     * "从数据库里看不出哪一行是系统行"这个性质。分成几份实现，迟早会有一份走样 ——
     * 而走样之后不会报错，只会悄悄多出一条可被识别的线索。
     */
    private fun derivedRowId(tag: ByteArray): String = derivedRowIdWith(requireUnlocked(), tag)

    /**
     * 同一套派生，但用**指定的** DEK。
     *
     * 它不能走 [derivedRowId] —— 那个用的是当前会话的 DEK，而这里要算的
     * 可能是另一域的行 id（或本域但由另一把钥匙保护的行）。
     */
    private fun derivedRowIdWith(dek: VaultDek, tag: ByteArray): String =
        derivedRowIdFrom(dek.expose(), tag)

    /**
     * 派生材料既可以是 DEK，也可以是审计钥匙 —— 两者都只是 32 字节的 HMAC 密钥。
     *
     * ## material 的生命周期（这条最容易读错）
     *
     * 传进来的是 `dek.expose()` / `auditKey.expose()` 交出来的
     * **`SecureBytes` 内部数组本身**（一路 `return bytes`，不复制），
     * 所以它是**会话级**的：归那两个对象所有，由它们的 `close()` 清零。
     *
     * 而这里构造的密钥副本是**一次调用级**的，[TransientSecretKey] 构造时的
     * `copyOf()` 已经把两者彻底解耦。职责因此是清楚的：
     * **调用方那份不归这里管，这里这份归 `finally` 管** ——
     * 不要因为"我们也会清"就省掉 `SecureBytes.close()`，那是另一件事。
     * 反过来也一样：这里清掉的不是你传进来的那个数组，
     * 本次返回之后调用方仍然可以继续用它的 material。
     *
     * ## 能清到哪、清不到哪（实测）
     *
     * 2026-09-20 在 Pixel_7 AVD（SDK 37 / provider AndroidOpenSSL 1.0）上实测：
     * `getEncoded()` 的调用次数在 `init` 之后是 1、`doFinal` 之后**仍是 1** ——
     * `Mac` 在 `init` 时就把密钥复制进了自己的上下文，此后不再回读 Java 端。
     * 于是有两条结论：
     *
     * - `init` 之后销毁自持副本**不影响** `doFinal`（派生结果与旧路径逐字节一致），
     *   这就是 `finally` 可以放在这个位置的理由；
     * - 但 native 上下文里那份**够不到**。这与 [TransientSecretKey] 的诚实边界是同一条：
     *   能清的是"我们自己持有的那份"。
     *
     * 证据落档在 `dist/HMAC密钥副本-独立评估.md` 与
     * `dist/evidence/hmac-key-lifecycle/`。
     *
     * ## 为什么这里不像 `AeadCipher.open` 那样吞异常
     *
     * `AeadCipher.open` 把 `GeneralSecurityException` 吞成 `null`，是因为调用方
     * 要区分"密码不对"和"密文被篡改"，两者会走到同一个拒绝分支。
     * 这里的失败（算法名写错、密钥长度非法）是**程序性错误**，而它会抛出的类型分散在
     * `InvalidKeyException`（`GeneralSecurityException`）与
     * `IllegalStateException`（`RuntimeException`）两处 —— 要一并吞掉只能
     * `catch (Exception)`，那会把"派生出一个错误的 id"变成更难查的静默失败。
     * 所以这里**只加 `finally`、不加 `catch`**：改的是内存，不是异常语义。
     *
     * 另外，派生结果本身**不是秘密**（它就是行 id，明文存在库里），
     * 所以只清密钥副本，不去擦那个返回值。
     */
    private fun derivedRowIdFrom(material: ByteArray, tag: ByteArray): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        // 与 AeadCipher 同一个道理：不能用 SecretKeySpec —— 它那份副本够不到，
        // Android 上也没有可用的销毁手段（见 TransientSecretKey 的 javap 实测）。
        val secret = TransientSecretKey(material, HMAC_ALGORITHM)
        return try {
            mac.init(secret)
            mac.doFinal(tag).copyOf(MANIFEST_ID_BYTES).toHex()
        } finally {
            // 唯一的清零点。放在 init 之后是安全的（Mac 在 init 就读完了密钥），
            // 而 `finally` 保证"中途抛异常也清" —— 派生失败往往正是反复试错的时刻。
            // 少了这一行不会有任何症状，所以它由 `scrubbedHmacKeyCount` 这条探针钉住。
            secret.destroy()
        }
    }

    /**
     * 解锁时定下本域的账本通道，然后把账本读进内存。
     *
     * ## 诱饵会话要先把自己的审计钥匙解出来
     *
     * 诱饵库那本账**不是**用诱饵 DEK 保护的（见 [auditKey] 的说明），
     * 所以诱饵会话必须先读本域的审计槽。槽不在时它就没有账本通道，
     * 后续的写入会**静默跳过** —— 这是刻意的：绝不能对着一个正被胁迫者
     * 使用的界面弹一句"登录记录写入失败"。真库那边会明确显示
     * "假密码那一半还没开始记录"，由用户自己补一次。
     *
     * ## 读不出来绝不能让解锁失败
     *
     * 账本不是用户的内容，为它把用户挡在门外是本末倒置。
     * 但也绝不能把"读不出来"显示成"还没有记录"—— 那会把一次可能的入侵
     * 说成"一切正常"，所以这里只落到 [loginLogUnreadable] 上，
     * 由界面如实说出来。
     */
    private fun prepareLoginLog() {
        if (requireUnlocked().domain == VaultDomain.DECOY) {
            auditKey = (readOwnAuditSlot() as? AuditSlot.Present)?.key
        }
        loadLoginLog()
    }

    private fun loadLoginLog() {
        val key = logKeyOrNull() ?: return
        val rowId = logRowId(key)
        val row = rows.load(rowId)
        if (row == null) {
            // 行不存在 = 这个库还没记过登录。**这是正常的首次状态**，不是损坏，
            // 因此不能和"解密/解码失败"共用一个分支。
            loginLog = LoginLog.EMPTY
            loginLogUnreadable = false
            return
        }
        val aad = Aad.entryField(rowId, logAadField(key))
        val plain = when (key) {
            is LogKey.Own -> AeadCipher.open(key.dek, row.nonce, row.ciphertext, aad)
            is LogKey.Audit -> AeadCipher.open(key.key.expose(), row.nonce, row.ciphertext, aad)
        }
        val decoded = plain?.let { LoginLogCodec.decode(it) }
        plain?.let { SecureBytes.zeroize(it) }
        loginLog = decoded ?: LoginLog.EMPTY
        loginLogUnreadable = decoded == null
    }

    private fun persistLoginLog(log: LoginLog) {
        val key = logKeyOrNull() ?: return
        val rowId = logRowId(key)
        val plain = LoginLogCodec.encode(log)
        try {
            val aad = Aad.entryField(rowId, logAadField(key))
            val sealed = when (key) {
                is LogKey.Own -> AeadCipher.seal(key.dek, plain, aad)
                is LogKey.Audit -> AeadCipher.seal(key.key.expose(), plain, aad)
            }
            rows.upsert(CipherRow(rowId, sealed.nonce, sealed.ciphertext))
        } finally {
            SecureBytes.zeroize(plain)
        }
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
        private const val LOGIN_LOG_AAD_FIELD = "login-log"
        private const val DECOY_LOG_AAD_FIELD = "decoy-log"
        private const val AUDIT_SLOT_AAD_FIELD = "audit-key"
        /**
         * 与 [TransientSecretKey.HMAC_SHA256] 必须是同一个值：
         * 前者喂给 `Mac.getInstance`，后者决定自检脚本把这次销毁记到哪条探针上。
         */
        private const val HMAC_ALGORITHM = TransientSecretKey.HMAC_SHA256

        /**
         * 系统行的 HMAC 标签 —— **落盘契约，只许新增、不许修改**。
         *
         * 改掉 [MANIFEST_MAC_INPUT] 的代价最重：它的行 id 会变，于是所有存量用户
         * 升级之后"清单行不见了"，被当成全新的空库 —— 唯一的补救是从备份恢复。
         *
         * 新增标签时必须确认它与已有的任何一个都**不会碰撞**：派生到同一个 id
         * 会让两个系统行互相覆盖，表现是"记一次登录，索引就没了"，
         * 而这两件事看起来毫无关系。
         *
         * 它们只给 HMAC 当输入，因此不与 [Aad] 里的字符串共用命名空间，
         * 但保持同一套 `mixia:<用途>:v1` 写法，便于一眼看出哪些是契约。
         *
         * 注意 [DECOY_LOG_MAC_INPUT] 虽然列在这一组里，它的派生材料却**不是**全库 DEK，
         * 而是审计钥匙 —— 见 [derivedRowIdFrom]。
         */
        private val MANIFEST_MAC_INPUT = "mixia:index:v1".toByteArray(StandardCharsets.US_ASCII)
        private val LOGIN_LOG_MAC_INPUT = "mixia:login-log:v1".toByteArray(StandardCharsets.US_ASCII)
        private val AUDIT_SLOT_MAC_INPUT = "mixia:audit-slot:v1".toByteArray(StandardCharsets.US_ASCII)
        private val DECOY_LOG_MAC_INPUT = "mixia:decoy-log:v1".toByteArray(StandardCharsets.US_ASCII)

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

        /**
         * KDF 预热用的固定口令。
         *
         * 它**不是用户凭据**，所以不走 `wiping`：内容是写死在代码里的常量，
         * 清不清零都不涉及任何秘密。`security-selfcheck.py` 的凭据清零检查里
         * 对它做了豁免备案 —— 那里只该漏报常量，不该漏报用户输入。
         */
        private val WARMUP_PASSWORD = "mixia-kdf-warmup".toCharArray()
    }
}
