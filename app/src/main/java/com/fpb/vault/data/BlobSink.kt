package com.fpb.vault.data

import java.io.IOException
import java.io.InputStream

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
 * 明文超过上限。**这不是程序错误，是用户的素材太大**，所以要能被单独接住，
 * 好让界面说「这个视频有 2.4 GB，超过 2 GB 的上限」而不是「保存失败」。
 *
 * 继承 [IOException] 而不是 `IllegalArgumentException`：它与「文件写不进去」
 * 属于同一类（外部条件不满足，重试同一个素材也不会成功），
 * 而 `require(...)` 那一类表达的是调用方写错了代码。
 */
class BlobTooLargeException(val limitBytes: Long) : IOException("明文超过上限 $limitBytes 字节")

/** 空附件。0 字节的"视频"没有任何意义，且分块格式本身就不允许 [ChunkedBlobFormat.Header] 为 0。 */
class BlobEmptyException : IOException("附件内容为空")

/**
 * 分块写入的结果。
 *
 * [storedBytes] 与 [plainBytes] 分开给：界面上要说「2.1 GB 的视频占了 2.1 GB」
 * （密文只比明文多出头部与每块的 28 字节开销），而排查问题时又要能对得上文件大小。
 */
class ChunkedWrite(val plainBytes: Long, val storedBytes: Long)

/**
 * 附件密文的存储接口。
 *
 * 注意接口里**没有任何"列出全部文件名"之外的能力**，也没有"按名字查找"的便捷方法：
 * 上层永远通过笔记内容里记录的 blobId 去取，不做目录扫描。
 * 这既是性能考虑，也让"存在哪些文件"这件事不参与业务逻辑 ——
 * 落在磁盘上的文件名全是随机串，扫描不出任何结构。
 *
 * ## 两种格式并存
 *
 * 图片沿用整块格式（[write] / [read]）：最大 32 MiB，一次性进内存没问题，
 * 而 `BitmapFactory` 也确实要完整字节。视频用分块格式（[writeChunked] / [openChunked]）：
 * 上限 2 GiB，必须能流式写、随机读。
 *
 * 两种格式的文件**放在同一个目录、用同一套随机 blobId 命名**，靠文件内部的魔数区分。
 * 这一点是被备份逼出来的：`BackupManager.export` 是按"扫 [listIds] 把文件逐个打进 zip"
 * 实现的，换个目录就意味着导出备份**静默漏掉全部视频** —— 用户以为备份好了，其实没有。
 * 同一个目录 + 同一套命名，备份与恢复那一整条链路一行都不用改。
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

    // ==================== 分块格式（视频） ====================

    /**
     * 流式写入一个分块附件。
     *
     * **明文的任何一个瞬间都只有有限块在内存里**（实现上最多两块，见下方 [seal] 的说明）——
     * 这正是引入分块格式的目的：2 GiB 的视频不可能先整个读进堆再加密。
     *
     * [seal] 由持有密钥的一方提供（存储层不认识密钥，也不该认识）。
     * 它接收块序号、**恰好这么长**的明文，以及**这一块是不是末块**。
     *
     * 末块标记必须由**写入方**确定，因为"是不是最后一块"只有读完流才知道 ——
     * 而它要进 AAD（见 [com.fpb.vault.crypto.Aad.blobChunk]），所以不能事后补。
     * 实现方为此要**超前读一块**：手上留着一块，同时去读下一块，
     * 下一块读空了，手上这块就是末块。代价是峰值两块明文（默认 2 MiB）。
     *
     * 超过 [limitBytes] 时抛 [BlobTooLargeException]，且**不留下任何文件**：
     * 临时文件在 finally 里删掉，改名那一步根本没走到。
     *
     * @return 写入的明文与密文字节数。
     * @throws BlobTooLargeException 明文超过 [limitBytes]。
     * @throws BlobEmptyException 源里一个字节都没有。
     */
    fun writeChunked(
        blobId: String,
        source: InputStream,
        chunkSize: Int,
        limitBytes: Long,
        seal: (index: Int, plain: ByteArray, length: Int, isLast: Boolean) -> com.fpb.vault.crypto.AeadCipher.Sealed,
    ): ChunkedWrite

    /**
     * 读头部，判断这个附件是不是分块格式。
     *
     * 返回 null 有两种情况，而调用方要做的处理是同一个（按整块格式读）：
     * 不是分块格式，或者头部不合规。
     * 实现方**必须**同时校验"文件长度与块布局相符"，否则随机 nonce 碰巧以魔数开头的
     * 老附件会被误判成视频（概率极低但代价很高：2 GiB 的越界读），
     * 而且那道校验还顺带挡掉了"改了 chunkCount 但没同步截断文件"这一整类篡改。
     */
    fun chunkHeader(blobId: String): ChunkedBlobFormat.Header?

    /**
     * 打开分块附件的随机访问读取器。不是分块格式（或头部不合规）返回 null。
     *
     * [open] 里的解密失败返回 null 而非抛异常 —— 与 [com.fpb.vault.crypto.AeadCipher.open]
     * 的约定一致；读取器会把它转成 [IOException]（见 [ChunkedBlobReader]）。
     */
    fun openChunked(blobId: String, open: ChunkedBlobReader.ChunkOpener): BlobReader?
}

