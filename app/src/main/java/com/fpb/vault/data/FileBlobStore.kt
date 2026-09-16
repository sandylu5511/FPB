package com.fpb.vault.data

import com.fpb.vault.crypto.AeadCipher
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
 * ## 写入用"先写临时文件再改名"
 *
 * 图片可能有几 MB，写入过程中若进程被杀，会留下半截文件。半截文件不是"部分图片"，
 * 而是一段永远无法通过认证的密文 —— 用户会看到一张打不开的图，且无从判断原因。
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
     * 正常情况下 [write] 的 finally 会删掉临时文件；只有进程被强杀（OOM、划掉任务、
     * 系统回收）才会漏下来。这些文件不满足 [RowIds.isValid]，因此 [listIds] 看不见它们，
     * 也就永远不会被孤儿清理扫到 —— 只能在这里回收。
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
