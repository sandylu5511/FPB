package com.fpb.vault.data

import java.io.Closeable

/**
 * 对一份**已解密明文**的随机访问读取器。
 *
 * ## 为什么需要它，而不是继续用 ByteArray
 *
 * 图片那条路一直是「整份解密进 ByteArray」：图片最大 32 MiB，一次性进内存没问题，
 * 而 `BitmapFactory` 也确实需要完整字节。视频不行 —— 单条上限 2 GiB，
 * 整份进内存等于必然 OOM，而且用户点开就要等整段 AES-GCM 跑完（数秒黑屏）。
 *
 * 但 `MediaPlayer` 要的恰恰是**随机访问**：它拿任意 offset 调 `readAt` 去寻址、读 moov、
 * 跳转。所以这里把「能随机读到明文的某个区间」抽象出来，视频用按需解密的实现
 * （见 [ChunkedBlobReader]），图片与实况影片段用内存实现（见 [ByteArrayBlobReader]）。
 *
 * ## readAt 的返回约定必须严格照抄 `android.media.MediaDataSource`
 *
 * 越界（含 `position >= size`）返回 **-1**，不是 0。返回 0 会被播放器理解成
 * 「此刻暂时没数据，过会儿再来问」，于是它就一直等一段永远不会到来的数据 ——
 * 表现为打开视频后无限转圈，而不是干脆报错。这个坑在 [com.fpb.vault.vault.MotionPhoto]
 * 那条内存播放路径上已经踩过一次，注释还留在原来的实现里。
 */
interface BlobReader : Closeable {

    /** 明文字节数。 */
    val size: Long

    /**
     * 从 [position] 起最多读 [size] 字节到 [buffer] 的 [offset] 处，返回实际读到的字节数。
     *
     * 越界返回 -1。调用方（`MediaDataSource`）保证 `buffer` 足够大。
     *
     * @throws java.io.IOException 密文损坏、认证失败等**不可恢复**的故障。
     *   这类故障绝不能伪装成「读到结尾」：那会让用户拿到一个静默截断的影片，
     *   而以为是完整的那一份。抛出去让播放器报错，界面才能如实说「这段视频读不出来」。
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
}

/**
 * 明文字节已经整份在内存里的读取器。
 *
 * [offset] / [length] 让它能表示「一大段字节里的某一个区间」——
 * 实况照片正是这种情况：影片段是尾部那段 MP4，而前面的 JPEG 主体不该被递出去。
 */
class ByteArrayBlobReader(
    private val data: ByteArray,
    private val start: Int = 0,
    private val length: Int = data.size - start,
) : BlobReader {

    init {
        require(start >= 0 && length >= 0 && start + length <= data.size) {
            "区间越界: start=$start length=$length 总长=${data.size}"
        }
    }

    override val size: Long get() = length.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= length) return -1
        if (size <= 0) return 0
        val count = minOf(size.toLong(), length - position).toInt()
        System.arraycopy(data, start + position.toInt(), buffer, offset, count)
        return count
    }

    override fun close() = Unit
}
