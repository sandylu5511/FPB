package com.fpb.vault.data

import com.fpb.vault.crypto.AeadCipher
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * 文件系统实现的附件密文存储。
 *
 * ## 落盘形状
 *
 * ```
 * <rootDir>/a3f9c1e77b204d8f6a15c3ee90b4d271     ← 文件名是 32 位随机十六进制
 * ```
 *
 * 文件内容为 `[12 字节 nonce][密文 + 16 字节认证标签]`，没有扩展名、
 * 没有目录层级、没有可读的命名规律。**从文件系统层面看，这里是一堆没有名字的二进制块。**
 *
 * 视频走另一套内部布局（分块，见 [ChunkedBlobFormat]），但**放在同一个目录、用同一种文件名**。
 * 这不是偷懒 —— 备份与恢复是"扫这个目录、把每个文件打进 zip"，换目录就会让导出备份
 * 静默漏掉全部视频。两种格式靠文件内部的魔数区分。
 *
 * ## 写入用"先写临时文件再改名"
 *
 * 图片可能有几 MB，视频可能上 GB，写入过程中若进程被杀，会留下半截文件。半截文件不是"部分内容"，
 * 而是一段永远无法通过认证的密文 —— 用户会看到一个打不开的附件，且无从判断原因。
 * 改名是文件系统上的原子操作，因此要么看到完整文件，要么什么都看不到。
 */
class FileBlobStore(private val rootDir: File) : BlobSink {

    init {
        if (!rootDir.isDirectory && !rootDir.mkdirs()) {
            throw IOException("无法创建附件目录: ${rootDir.absolutePath}")
        }
    }

    override fun write(blobId: String, nonce: ByteArray, ciphertext: ByteArray) {
        val target = fileFor(blobId)
        val temp = File(rootDir, blobId + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                out.write(nonce)
                out.write(ciphertext)
                out.flush()
                // 落盘后再改名。少了这一步，断电时可能改名成功但数据还在页缓存里。
                out.fd.sync()
            }
            if (!temp.renameTo(target)) {
                throw IOException("附件改名失败: ${target.name}")
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    override fun read(blobId: String): EncryptedBlob? {
        if (!RowIds.isValid(blobId)) return null
        val file = File(rootDir, blobId)
        if (!file.isFile) return null

        val bytes = file.readBytes()
        if (bytes.size <= AeadCipher.NONCE_BYTES) return null

        return EncryptedBlob(
            nonce = bytes.copyOfRange(0, AeadCipher.NONCE_BYTES),
            ciphertext = bytes.copyOfRange(AeadCipher.NONCE_BYTES, bytes.size),
        )
    }

    override fun delete(blobId: String): Boolean {
        if (!RowIds.isValid(blobId)) return false
        return File(rootDir, blobId).delete()
    }

    override fun listIds(): List<String> =
        rootDir.listFiles()
            ?.filter { it.isFile && RowIds.isValid(it.name) }
            ?.map { it.name }
            ?: emptyList()

    /**
     * 清掉写入中断留下的 `<blobId>.part`。
     *
     * 正常情况下 [write] 与 [writeChunked] 的 finally 会删掉临时文件；
     * 只有进程被强杀（OOM、划掉任务、系统回收）才会漏下来。这些文件不满足
     * [RowIds.isValid]，因此 [listIds] 看不见它们，也就永远不会被孤儿清理扫到 ——
     * 只能在这里回收。
     *
     * 注意一个 GB 级视频的 `.part` 可能是 2 GB：它不只是"碍眼"，
     * 而是能实打实地把用户的存储吃掉，所以启动时的这次清理不能省。
     */
    override fun purgeStaleTempFiles(): Int {
        val stale = rootDir.listFiles()?.filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }
            ?: return 0
        var removed = 0
        stale.forEach { if (it.delete()) removed++ }
        return removed
    }

    override fun totalBytes(): Long =
        rootDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    // ==================== 分块格式（视频） ====================

    /**
     * 流式写入分块附件。
     *
     * 先写 24 字节的头占位，边读边加密边写，最后**回头把真实头部补上** ——
     * 块的个数与明文总长要读完了才知道，而它们必须在文件开头（读取方要先知道块多大才能定位）。
     * 用 [RandomAccessFile] 而不是 `FileOutputStream` 就是为了这次 seek 回填。
     *
     * ## 超前一块，是为了确定"末块标记"
     *
     * 末块标记要进 AAD，所以封块的时候就必须知道这一块是不是最后一块。
     * 于是循环里始终**手上留一块、同时去读下一块**：下一块读空了，手上这块就是末块。
     * 峰值明文因此是两块（默认 2 MiB），换取"截断文件必被发现"。
     *
     * 超限的处理是这里最要紧的另一处：一旦发现超出 [limitBytes]，立刻抛异常，
     * 而 `finally` 会把临时文件删掉 —— **用户不会在磁盘上留下一个 2 GB 的垃圾**，
     * 也不会发生"改名成功但内容被截断"这种事（改名那一步压根没执行）。
     */
    override fun writeChunked(
        blobId: String,
        source: InputStream,
        chunkSize: Int,
        limitBytes: Long,
        seal: (index: Int, plain: ByteArray, length: Int, isLast: Boolean) -> AeadCipher.Sealed,
    ): ChunkedWrite {
        require(RowIds.isValid(blobId)) { "非法 blobId，拒绝拼接路径" }
        require(chunkSize in ChunkedBlobFormat.MIN_CHUNK_BYTES..ChunkedBlobFormat.MAX_CHUNK_BYTES) {
            "块大小越界: $chunkSize"
        }

        val target = fileFor(blobId)
        val temp = File(rootDir, blobId + TEMP_SUFFIX)
        try {
            var plainSize = 0L
            var chunkCount = 0
            var storedSize = ChunkedBlobFormat.HEADER_BYTES.toLong()

            RandomAccessFile(temp, "rw").use { raf ->
                raf.write(ByteArray(ChunkedBlobFormat.HEADER_BYTES))

                val pending = ByteArray(chunkSize)
                var pendingLength = readUpTo(source, pending, chunkSize)

                while (pendingLength > 0) {
                    // 先判上限再加密：超限的字节不该被加密、更不该落盘。
                    if (plainSize + pendingLength > limitBytes) throw BlobTooLargeException(limitBytes)

                    val next = ByteArray(chunkSize)
                    val nextLength = readUpTo(source, next, chunkSize)
                    val isLast = nextLength <= 0

                    val sealed = seal(chunkCount, pending, pendingLength, isLast)
                    raf.write(sealed.nonce)
                    raf.write(sealed.ciphertext)
                    plainSize += pendingLength
                    storedSize += sealed.nonce.size + sealed.ciphertext.size
                    chunkCount++

                    // 这一轮用过的 pending 变成下一轮的 next。交换缓冲而不新建，
                    // 是为了让峰值稳定在两块 —— 每次迭代都 new 一个的话，
                    // GC 会跟着一个几百 MB 的视频一路抖动。
                    System.arraycopy(next, 0, pending, 0, nextLength)
                    pendingLength = nextLength
                }

                if (plainSize <= 0) throw BlobEmptyException()

                raf.seek(0)
                raf.write(ChunkedBlobFormat.encodeHeader(ChunkedBlobFormat.headerOf(plainSize, chunkSize)))
                raf.fd.sync()
            }

            if (!temp.renameTo(target)) {
                throw IOException("附件改名失败: ${target.name}")
            }
            return ChunkedWrite(plainBytes = plainSize, storedBytes = storedSize)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * 是不是分块格式。
     *
     * 两道判据缺一不可：魔数，**以及**"文件长度与头部算出来的长度逐字节相符"。
     * 只有魔数的话，一个整块附件的随机 nonce 恰好以 `"FPBCHK"` 开头的概率虽然极低，
     * 但一旦撞上，我们就会拿一个图片的字节当头部去解析出天文数字的块数。
     * 加上长度校验之后，那种情况会在这一步被挡掉，代价是零。
     */
    override fun chunkHeader(blobId: String): ChunkedBlobFormat.Header? {
        if (!RowIds.isValid(blobId)) return null
        val file = File(rootDir, blobId)
        if (!file.isFile) return null
        val length = file.length()
        // 头部本身都装不下就不可能是分块格式；长度先做一次廉价的粗筛，避免白读文件。
        if (length < ChunkedBlobFormat.HEADER_BYTES) return null

        val prefix = readPrefix(file, ChunkedBlobFormat.HEADER_BYTES) ?: return null
        val header = ChunkedBlobFormat.parseHeader(prefix) ?: return null
        return if (header.expectedStoredBytes() == length) header else null
    }

    override fun openChunked(blobId: String, open: ChunkedBlobReader.ChunkOpener): BlobReader? {
        val header = chunkHeader(blobId) ?: return null
        val file = File(rootDir, blobId)
        return runCatching { ChunkedBlobReader(file, header, open) }.getOrNull()
    }

    // ==================== 内部 ====================

    /**
     * 把一个流填满 [max] 字节（除非先读到结尾）。
     *
     * 必须有这个循环：`InputStream.read` 允许**只返回一部分**，直接拿它当"一块"会让块大小
     * 随机变化，而分块格式的块偏移是**按固定步长算出来的**（不查表），
     * 块大小一变，后面所有块的偏移全错。表现为视频从某个点开始花屏或直接报错。
     */
    private fun readUpTo(source: InputStream, buffer: ByteArray, max: Int): Int {
        var total = 0
        while (total < max) {
            val read = source.read(buffer, total, max - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun readPrefix(file: File, max: Int): ByteArray? {
        val count = minOf(max.toLong(), file.length()).toInt()
        if (count <= 0) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                ByteArray(count).also { raf.readFully(it) }
            }
        }.getOrNull()
    }

    /**
     * 校验通过后才拼接路径。
     *
     * blobId 的来源是**解密后的笔记内容**。加密保证了它没被外部篡改，
     * 但不保证它一定是当初写进去的形状 —— 一个自己造成的编码 bug、
     * 或一次不当的数据迁移，都可能让里面出现 `../../databases/vault`。
     * 在拼路径之前挡住，比事后审计要便宜得多。
     */
    private fun fileFor(blobId: String): File {
        require(RowIds.isValid(blobId)) { "非法 blobId，拒绝拼接路径" }
        return File(rootDir, blobId)
    }

    companion object {
        /** 临时文件后缀。写入过程中断时靠它定位残留。 */
        const val TEMP_SUFFIX = ".part"
    }
}
