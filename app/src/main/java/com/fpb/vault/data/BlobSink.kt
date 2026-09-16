package com.fpb.vault.data

/**
 * 一段加密后的二进制附件（图片、缩略图）。nonce 与密文分开保存，
 * 因为落盘格式是 `[nonce][ciphertext]`，读回来时要能分别取出。
 */
class EncryptedBlob(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    val storedBytes: Long get() = (nonce.size + ciphertext.size).toLong()
}

/**
 * 附件密文的存储接口。
 *
 * 注意接口里**没有任何"列出全部文件名"之外的能力**，也没有"按名字查找"的便捷方法：
 * 上层永远通过笔记内容里记录的 blobId 去取，不做目录扫描。
 * 这既是性能考虑，也让"存在哪些文件"这件事不参与业务逻辑 ——
 * 落在磁盘上的文件名全是随机串，扫描不出任何结构。
 */
interface BlobSink {

    fun write(blobId: String, nonce: ByteArray, ciphertext: ByteArray)

    fun read(blobId: String): EncryptedBlob?

    /** @return 是否确实删掉了东西。 */
    fun delete(blobId: String): Boolean

    /** 仅用于孤儿清理与占用统计。 */
    fun listIds(): List<String>

    fun totalBytes(): Long

    /**
     * 清理"写入中断"留下的半截文件，返回清理数量。
     *
     * [FileBlobStore] 写入时先写 `<blobId>.part` 再改名。进程在改名之前被杀，
     * 那个临时文件就留在目录里了 —— 它不满足 [com.fpb.vault.data.RowIds.isValid]，
     * 因此 [listIds] 看不见它，孤儿清理也就永远扫不到它，只能靠这里回收。
     *
     * 内存实现没有"半截文件"这个概念，默认返回 0。
     */
    fun purgeStaleTempFiles(): Int = 0
}
