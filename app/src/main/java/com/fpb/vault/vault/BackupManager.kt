package com.fpb.vault.vault

import com.fpb.vault.crypto.VaultKeyFileCodec
import com.fpb.vault.crypto.VaultKeyFileException
import com.fpb.vault.data.ChunkedBlobFormat
import com.fpb.vault.data.RowIds
import com.fpb.vault.model.NotePayload
import com.fpb.vault.session.VaultSession
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 导出结果，用于给用户一个"导出成功了什么"的明确交代。 */
data class BackupSummary(val noteRows: Int, val attachments: Int, val bytes: Long)

/** 备份包的元信息，导入前先展示给用户确认。 */
data class BackupInfo(
    val formatVersion: Int,
    val createdAt: Long,
    val noteRows: Int,
    val attachments: Int,
    val bytes: Long,
)

/**
 * 密文备份包的导出与导入。
 *
 * ## 备份包里是什么
 *
 * ```
 * fpb-backup.txt      明文清单（格式版本、导出时间、条目数）
 * fpb.key             密钥文件：KDF 参数 + salt + 各槽位被包裹后的 DEK
 * vault.db            密文行（id / nonce / ciphertext）
 * attachments/<id>    图片密文
 * ```
 *
 * **里面没有一条明文**，也没有密码、恢复码、裸 DEK，更没有生物识别用的硬件密钥
 * （那把密钥锁在设备的 Keystore 里，导不出来也不该导出）。
 * 所以这个 zip 可以放在网盘、U 盘、聊天记录里 —— 拿到它的人得到的是一块石头。
 *
 * ## 为什么导出必须由用户手动触发
 *
 * 应用没有 INTERNET 权限，也没有后台任务，**没有任何自动上传的可能**。
 * 反过来，用户不导出 = 数据只有一份 = 手机丢了内容就永久消失。
 * 所以设置页会周期性提醒导出，但永远不会替用户决定。
 */
object BackupManager {

    const val FORMAT_VERSION = 1

    private const val MANIFEST_ENTRY = StorageNames.BACKUP_MANIFEST
    private const val KEY_ENTRY = StorageNames.BACKUP_KEY_ENTRY
    private const val DB_ENTRY = StorageNames.BACKUP_DB_ENTRY
    private const val BLOB_PREFIX = StorageNames.BACKUP_BLOB_PREFIX

    /**
     * 单个条目允许解压出的最大字节数。
     *
     * **由应用自己能产出的最大附件推导，而不是另写一个数字。**
     * 原来是硬编码的 256 MiB（按图片时代的量级定的：单张图片上限 32 MiB，
     * 256 MiB 已经宽松得多）。视频把它抬到了 2 GiB，而这个数没有被一起改 ——
     * 于是**一段 400 MB 的手机 4K 视频就足以让整份备份恢复失败**。
     *
     * 这类缺陷的形状很难自己暴露出来：导出照常成功、提示照常说"已导出备份"，
     * 直到某天真的需要它的时候才发现这份备份恢复不了 —— 而那时原始数据
     * 大概率已经不在了。所以这里的取值必须能从 [VaultSession.MAX_VIDEO_PLAINTEXT_BYTES]
     * **推出来**，而不是靠人记得同步改两处。
     */
    internal val MAX_ENTRY_BYTES: Long =
        ChunkedBlobFormat.headerOf(VaultSession.MAX_VIDEO_PLAINTEXT_BYTES).expectedStoredBytes()

    /**
     * 单次导入允许解压的总字节数。
     *
     * 同样是推导出来的：按"两条装满上限视频的记录"给足
     * （[com.fpb.vault.model.NotePayload.MAX_VIDEOS] 段满上限视频 × 2）。
     *
     * ## 它挡的是什么，以及不挡什么
     *
     * 它挡的是"一个声明了 1 TB 的构造包"。它**不**负责精确的容量规划 ——
     * 真实设备上先被撞到的通常是**可用空间**：解压要落进 cache，
     * 装不下时文件系统自己会失败，而 `finally` 会把暂存目录清掉。
     * 因此这里的原则是"**有限、且不小于应用自己允许存的量**"，而不是越小越好：
     * 任何比它小的取值都可能拒绝一份合法备份，而这正是本轮修的缺陷本身。
     */
    internal val MAX_TOTAL_BYTES: Long =
        MAX_ENTRY_BYTES * (NotePayload.MAX_VIDEOS.toLong() * 2)

    /** 备份包里的条目数上限。防一份声明了几十万条目的包把解压变成一次拒绝服务。 */
    private const val MAX_ENTRIES = 200_000

    /**
     * 清单文件允许的最大字节数。
     *
     * [inspect] 是**唯一一处把不可信输入整体读进内存**的地方：其余条目都只 `closeEntry()`
     * 跳过（那是流式丢弃，不占内存），而清单要解析成字符串，只能先读全。
     * 真正的清单只有一百多字节，因此这里给一个宽松的上限就足以把
     * "一个声明了几个 GB 的清单条目"这类构造挡在 `OutOfMemoryError` 之前。
     *
     * [extract] 侧另有 [MAX_ENTRY_BYTES] / [MAX_TOTAL_BYTES] 兜底 ——
     * 但那是**导入**路径，[inspect] 跑在它前面，因此必须自己封顶。
     */
    private const val MAX_MANIFEST_BYTES = 64 * 1024

    private const val SQLITE_MAGIC = "SQLite format 3\u0000"

    // ==================== 导出 ====================

    /**
     * 拼出备份包里的清单文本。
     *
     * 抽成独立函数只为了**能被单元测试钉住**：它是备份格式的写入端，
     * 而格式一旦变了，用户以前导出的包就再也导不回来 —— 那些包已经在网盘里了，
     * 我们没有任何事后补救的机会。原来这段是内联在 [export] 里的 `buildString`，
     * 而 [export] 需要一整个仓库与会话，测不了，于是格式只能靠"读一遍代码"保证。
     *
     * 格式：每行一个 `key=value`，以换行结尾。[inspect] 用 `lineValue` 逐行解析，
     * 因此**行尾那个换行和字段名的大小写都是格式的一部分**，不能随手改。
     * 字段名见 [StorageNames.BackupManifest]。
     */
    internal fun buildManifest(createdAt: Long, noteRows: Int, attachments: Int): String =
        buildString {
            appendLine("${StorageNames.BackupManifest.FORMAT}=$FORMAT_VERSION")
            appendLine("${StorageNames.BackupManifest.APP}=${StorageNames.BackupManifest.APP_TAG}")
            appendLine("${StorageNames.BackupManifest.CREATED_AT}=$createdAt")
            appendLine("${StorageNames.BackupManifest.NOTE_ROWS}=$noteRows")
            appendLine("${StorageNames.BackupManifest.ATTACHMENTS}=$attachments")
        }

    fun export(
        repository: VaultRepository,
        session: VaultSession,
        output: OutputStream,
    ): BackupSummary {
        val keyBytes = repository.keyFileBytes()
            ?: throw BackupException("找不到密钥文件，无法导出")

        val dbFile = repository.databaseFile()
        if (!dbFile.isFile) throw BackupException("找不到数据库文件，无法导出")

        val blobDir = repository.attachmentsDir()
        val blobs = blobDir.listFiles()?.filter { it.isFile && RowIds.isValid(it.name) }.orEmpty()

        var written = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            written += zip.putText(
                MANIFEST_ENTRY,
                buildManifest(
                    createdAt = System.currentTimeMillis(),
                    noteRows = session.noteCount,
                    attachments = blobs.size,
                ),
            )

            written += zip.putBytes(KEY_ENTRY, keyBytes)
            written += zip.putFile(DB_ENTRY, dbFile)
            blobs.forEach { written += zip.putFile(BLOB_PREFIX + it.name, it) }
        }

        return BackupSummary(
            noteRows = session.noteCount,
            attachments = blobs.size,
            bytes = written,
        )
    }

    // ==================== 检视（导入前预检） ====================

    /**
     * 只读取清单与关键结构，不解压全部内容。
     *
     * 用户点"导入"之后、真正覆盖数据之前必须先看到"这个包是什么、什么时候导出的"，
     * 否则一次误选就会把当前库整个替换掉，而且没有任何撤销。
     */
    fun inspect(input: InputStream, totalBytes: Long): BackupInfo {
        var version = 0
        var createdAt = 0L
        var noteRows = 0
        var attachments = 0
        var hasKey = false
        var hasDb = false

        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry ?: throw BackupException("这不是一个 zip 备份包")
            var guard = 0
            while (true) {
                if (++guard > MAX_ENTRIES) throw BackupException("备份包条目过多，已中止")
                val name = entry.name.safeEntryName()
                when {
                    name == MANIFEST_ENTRY -> {
                        val text = zip.readCapped(MAX_MANIFEST_BYTES)?.toString(Charsets.UTF_8)
                            ?: throw BackupException("备份包的清单异常，无法确认格式")
                        version = text.lineValue(StorageNames.BackupManifest.FORMAT)?.toIntOrNull() ?: 0
                        createdAt = text.lineValue(StorageNames.BackupManifest.CREATED_AT)?.toLongOrNull() ?: 0L
                        noteRows = text.lineValue(StorageNames.BackupManifest.NOTE_ROWS)?.toIntOrNull() ?: 0
                        attachments = text.lineValue(StorageNames.BackupManifest.ATTACHMENTS)?.toIntOrNull() ?: 0
                    }
                    name == KEY_ENTRY -> hasKey = true
                    name == DB_ENTRY -> hasDb = true
                }
                zip.closeEntry()
                entry = zip.nextEntry ?: break
            }
        }

        if (!hasKey) throw BackupException("备份包里没有密钥文件，无法使用")
        if (!hasDb) throw BackupException("备份包里没有数据库文件，无法使用")
        if (version <= 0) throw BackupException("备份包缺少清单，无法确认格式")
        if (version > FORMAT_VERSION) {
            throw BackupException("备份包来自更新的版本（格式 $version），当前版本读不了")
        }

        return BackupInfo(version, createdAt, noteRows, attachments, totalBytes)
    }

    // ==================== 导入 ====================

    /**
     * 覆盖式恢复。
     *
     * **调用前必须处于锁定状态**：恢复会把密钥文件整个换掉，
     * 若此时会话里还握着一把旧 DEK，那个 DEK 已经打不开任何东西了，
     * 而界面还在用它读写 —— 结果是"导入之后刚写的笔记全部消失"。
     *
     * 全程走暂存目录：先解压到 cache 里校验，**校验通过之后才动现有数据**。
     * 校验失败时现有库一个字节都没变，用户可以直接重试另一个包。
     *
     * 解压前会按暂存目录的**可用空间**先拒一次（见 [extract] 的 `usableBytes`）——
     * 固定预算（[MAX_TOTAL_BYTES] ≈ 80 GiB）在手机上永远大于真实可用空间，
     * 所以现实里第一个撞上的闸门就是它。
     */
    fun restore(repository: VaultRepository, input: InputStream): BackupInfo {
        check(!repository.session().isUnlocked) { "恢复备份必须在锁定状态下进行" }

        val staging = repository.stagingDir()
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) throw BackupException("无法创建暂存目录")

        try {
            // 按暂存目录的可用空间预检，放在解压**之前**：一旦开始写，被填满的
            // 不只是这个暂存目录，而是整个 cache 分区 —— 到那时连"空间不足"
            // 这句提示都可能因为日志与界面写不进去而落不了地。
            //
            // 只传可用空间、不动 `maxTotalBytes`：两个闸门各有各的话术，
            // 一份构造出来的 80 GiB 巨包应当被报成"包有问题"而不是"你空间不够"
            // （判定顺序在 [extract] 里保证了这一点）。
            //
            // `usableSpace` 读不到时返回 0，而 0 表示"这项不检查" ——
            // 宁可放过一次预检（文件系统自己会失败），也不要因为读不到余量
            // 就把一份正常的备份判死。
            extract(input, staging, usableBytes = staging.usableSpace)

            val stagedKey = File(staging, KEY_ENTRY)
            val stagedDb = File(staging, DB_ENTRY)
            val stagedBlobs = File(staging, StorageNames.BLOB_DIR)

            // 密钥文件必须能被解码，而且必须有主密码槽 —— 否则导入的就是一个死库
            val decoded = try {
                VaultKeyFileCodec.decode(stagedKey.readBytes())
            } catch (e: VaultKeyFileException) {
                throw BackupException("备份包里的密钥文件无法解读：${e.message}", e)
            } catch (e: IOException) {
                throw BackupException("备份包里的密钥文件读取失败", e)
            }
            if (!decoded.hasSlot(com.fpb.vault.crypto.KeySlot.PRIMARY)) {
                throw BackupException("备份包里的密钥文件缺少主密码槽")
            }

            if (!looksLikeSqlite(stagedDb)) {
                throw BackupException("备份包里的数据库文件不是有效的 SQLite 文件")
            }

            val info = BackupInfo(
                formatVersion = FORMAT_VERSION,
                createdAt = 0L,
                noteRows = 0,
                attachments = stagedBlobs.listFiles()?.size ?: 0,
                bytes = staging.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            )

            swapIntoPlace(repository, stagedKey, stagedDb, stagedBlobs)
            return info
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    /**
     * 解压到暂存目录。
     *
     * 三个上限做成参数（而非直接读常量）**只为了能被单测钉住边界**：
     * 与 [buildManifest] 抽成独立函数是同一个理由 —— 用真实上限去测，
     * 一次用例就得写几百 MB 到磁盘，而这里要验的是"比较用的是不是这个上限"。
     *
     * ## 三个闸门挡的不是同一件事，所以话术必须不一样
     *
     * | 闸门 | 挡的是什么 | 用户该做什么 |
     * |---|---|---|
     * | [maxEntryBytes] | 单个文件被撑爆（构造出来的包） | 这份包不可信 |
     * | [maxTotalBytes] | 总量被撑爆（同上） | 同上 |
     * | [usableBytes] | **本机磁盘装不下**（完全正常的包） | 清点空间再来一次 |
     *
     * 前两个是"包有问题"，第三个是"机器有问题"。这三处原先共用一句
     * 「备份包解压后体积异常，已中止」，于是**拿着一条完全正常的备份包的用户，
     * 被告知"包坏了"** —— 他会去重下、换网盘再传，怎么试都不会成功。
     *
     * ## 判定的先后顺序也是有意的
     *
     * 先判两个"包的问题"，再判"机器的问题"。反过来的话，一份被构造出来的巨包
     * 会被报成"空间不足"，而用户去清空间是永远解决不了它的。
     *
     * @param usableBytes 暂存目录所在分区的可用空间。**0 = 不做这项检查**
     *   （内存实现、以及读不到可用空间的情况）。它是**取值那一刻**的余量，
     *   解压期间别的进程也会占盘，所以这是一道"提前拒绝"，不是精确预算。
     */
    internal fun extract(
        input: InputStream,
        staging: File,
        maxEntryBytes: Long = MAX_ENTRY_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        usableBytes: Long = 0L,
    ) {
        var total = 0L
        var count = 0
        ZipInputStream(input.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (++count > MAX_ENTRIES) throw BackupException("备份包条目过多，已中止")
                val name = entry.name.safeEntryName()
                if (!entry.isDirectory) {
                    val target = when {
                        name == MANIFEST_ENTRY -> File(staging, name)
                        name == KEY_ENTRY -> File(staging, name)
                        name == DB_ENTRY -> File(staging, name)
                        name.startsWith(BLOB_PREFIX) -> {
                            val blobId = name.removePrefix(BLOB_PREFIX)
                            // 附件名必须是 32 位十六进制。不校验的话，
                            // 一个叫 "../../databases/vault.db" 的条目就能写到目录外面去。
                            if (!RowIds.isValid(blobId)) {
                                throw BackupException("备份包里有非法的附件名：$blobId")
                            }
                            File(staging, "attachments/$blobId")
                        }
                        else -> null
                    }
                    // 清单单独封一条更紧的线：正常流程里 [inspect] 会先按
                    // [MAX_MANIFEST_BYTES] 把它拦掉，但"绕过确认页直接恢复"这条路
                    // 够不到那道闸门。而清单是**唯一会被整体读进内存**的条目
                    // （其余条目解压时只是流过缓冲区），不封顶就留了一个撑爆堆的口子。
                    val entryLimit =
                        if (name == MANIFEST_ENTRY) {
                            minOf(maxEntryBytes, MAX_MANIFEST_BYTES.toLong())
                        } else {
                            maxEntryBytes
                        }
                    if (target != null) {
                        target.parentFile?.mkdirs()
                        var entryBytes = 0L
                        target.outputStream().use { out ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                entryBytes += read
                                total += read
                                if (entryBytes > entryLimit) {
                                    throw BackupException(
                                        "备份包里有单个文件解压后体积异常，已中止",
                                    )
                                }
                                if (total > maxTotalBytes) {
                                    throw BackupException("备份包解压后的总体积异常，已中止")
                                }
                                if (usableBytes > 0 && total > usableBytes) {
                                    throw BackupException(
                                        "本机可用空间不足，解不开这份备份。" +
                                            "先腾出一些空间再试 —— 这份备份本身没有问题。",
                                    )
                                }
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (count == 0) throw BackupException("备份包是空的")
    }

    /**
     * 真正的替换动作。
     *
     * 顺序：**先让现有数据失去密钥文件（即不可解读），再放新数据**。
     * 反过来的话，中途失败会留下"新密钥文件 + 旧数据库"的组合 ——
     * 那是一个永远打不开的库，而且用户以为导入成功了。
     */
    private fun swapIntoPlace(
        repository: VaultRepository,
        stagedKey: File,
        stagedDb: File,
        stagedBlobs: File,
    ) {
        repository.close()

        val dbTarget = repository.databaseFile()
        listOf(dbTarget, File(dbTarget.path + "-journal"), File(dbTarget.path + "-wal"), File(dbTarget.path + "-shm"))
            .forEach { runCatching { it.delete() } }

        val blobTarget = repository.attachmentsDir()
        blobTarget.listFiles()?.forEach { runCatching { it.delete() } }
        blobTarget.mkdirs()

        // 硬件密钥包裹与导入库不匹配（那是另一台设备的 Keystore 生成的），必须一并清掉。
        repository.biometric.clear()
        repository.settings.biometricEnabled = false

        runCatching { repository.keyFileForRestore().delete() }

        dbTarget.parentFile?.mkdirs()
        if (!stagedDb.renameTo(dbTarget)) {
            stagedDb.copyTo(dbTarget, overwrite = true)
        }
        stagedBlobs.listFiles()?.forEach { blob ->
            val target = File(blobTarget, blob.name)
            if (!blob.renameTo(target)) blob.copyTo(target, overwrite = true)
        }
        if (!stagedKey.renameTo(repository.keyFileForRestore())) {
            stagedKey.copyTo(repository.keyFileForRestore(), overwrite = true)
        }
    }

    private fun looksLikeSqlite(file: File): Boolean {
        if (!file.isFile || file.length() < 16) return false
        return runCatching {
            val header = ByteArray(16)
            file.inputStream().use { it.read(header) }
            String(header, Charsets.ISO_8859_1) == SQLITE_MAGIC
        }.getOrDefault(false)
    }

    private fun String.safeEntryName(): String {
        val trimmed = trimStart('/')
        if (trimmed.contains("..") || trimmed.contains('\\')) {
            throw BackupException("备份包里有不安全的路径：$this")
        }
        return trimmed
    }

    private fun String.lineValue(key: String): String? =
        lineSequence().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()

    // ==================== zip 写入小工具 ====================

    private fun ZipOutputStream.putText(name: String, text: String): Long {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return putBytes(name, bytes)
    }

    private fun ZipOutputStream.putBytes(name: String, bytes: ByteArray): Long {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
        return bytes.size.toLong()
    }

    private fun ZipOutputStream.putFile(name: String, file: File): Long {
        putNextEntry(ZipEntry(name))
        var written = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                write(buffer, 0, read)
                written += read
            }
        }
        closeEntry()
        return written
    }
}
