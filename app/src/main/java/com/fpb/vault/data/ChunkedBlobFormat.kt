package com.fpb.vault.data

import com.fpb.vault.crypto.AeadCipher
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * 分块附件（视频）的落盘格式。
 *
 * ## 为什么要有第二个格式
 *
 * 老格式是 `[12 字节 nonce][密文 + 16 字节认证标签]`，整个附件是**一个** GCM 记录。
 * 它有两个性质，对图片无所谓，对视频是致命的：
 *
 * 1. **解密必须整段做**：GCM 是认证加密，不验完最后一个字节就不敢交出任何明文。
 *    所以想读第 1 个字节，也得先把 2 GiB 全解出来。
 * 2. **无法随机访问**：`MediaPlayer` 要按任意 offset 寻址（读 moov、跳转），
 *    而「整段解密」这条路意味着它每次寻址都要重跑一遍全量解密。
 *
 * 于是视频改成分块：每块独立一个 GCM 记录，读哪块解哪块。
 *
 * ## 格式
 *
 * ```
 * magic        8 字节  "FPBCHK" 0x01 0x00（格式版本 1.0）
 * chunkSize    4 字节  int32 大端，每块的**明文**字节数（除最后一块）
 * chunkCount   4 字节  int32 大端
 * plainSize    8 字节  int64 大端，明文总字节数
 * 然后 chunkCount 组：
 *   nonce     12 字节
 *   密文       (该块明文长度 + 16) 字节
 * ```
 *
 * 头部共 [HEADER_BYTES] 字节，**全部是明文**（读取方要先知道块多大才能定位，
 * 没法先解密）。明文头意味着它可以被改：把 `chunkCount` 改小再截短文件，
 * 读取方就会平静地只解出前几块，用户看到一段**静默变短**的影片。
 * 挡住这一手的是**块级 AAD 里的末块标记**（见
 * [Aad.blobChunk][com.fpb.vault.crypto.Aad.blobChunk]），而不是把头部字段塞进 AAD ——
 * 后者的困难在于"明文总长"写入时算不出来。
 *
 * ## 与老格式的区分：靠魔数 + 长度自洽，双重校验
 *
 * 老附件的头 12 字节是随机 nonce，理论上可能碰巧以 [MAGIC] 开头。
 * 所以 [parseHeader] 不只认魔数，还要求**整个文件长度与块布局算出来的长度逐字节相符**
 * （由 [expectedStoredBytes] 与真实文件大小比对，在 [FileBlobStore.chunkHeader] 里做）。
 * 随机 nonce 同时满足这两条的概率是 2⁻⁶⁴ 量级；而且即使真撞上，GCM 认证也会失败，
 * 结果是「读不出来」而不是「读出垃圾」—— 失败方向是安全的。
 */
object ChunkedBlobFormat {

    /** `"FPBCHK"` + 格式版本 0x01 0x00。 */
    val MAGIC = byteArrayOf(0x46, 0x50, 0x42, 0x43, 0x48, 0x4B, 0x01, 0x00)

    /** 头部固定长度：8 魔数 + 4 块大小 + 4 块数 + 8 明文总长。 */
    const val HEADER_BYTES = 24

    /**
     * 默认块大小 1 MiB。
     *
     * 取舍：块越大，顺序播放时每 MiB 的解密次数越少（缓存命中率高）；
     * 块越小，随机跳转的延迟越低（跳一次只需解一小块），且缓存能覆盖更长的时间窗。
     * 1 MiB 下单个 AES-GCM 块解密在手机上约 1 毫秒，而 24 块的缓存窗口
     * （[ChunkedBlobReader.DEFAULT_CACHE_CHUNKS]）正好是 24 MiB 明文常驻 ——
     * 与图片 LRU 缓存的上限同量级，不会互相挤兑。
     */
    const val DEFAULT_CHUNK_BYTES = 1 shl 20

    /** 块大小的合法区间。下界防「块数爆炸」（4 GiB / 64 KiB = 65536 块，头都装不下）； */
    const val MIN_CHUNK_BYTES = 64 shl 10

    /** 上界防「一块就是整个文件」（等于退回整段解密）。 */
    const val MAX_CHUNK_BYTES = 8 shl 20

    /**
     * 明文总长的硬上限（4 GiB）。
     *
     * 与业务上限 [com.fpb.vault.session.VaultSession.MAX_VIDEO_PLAINTEXT_BYTES] 分开写：
     * 这个是**格式层面**的合理性边界（防一个被改过的头让我们去分配天文数字），
     * 那个是产品决定。两者都不是同一件事，不该合并。
     */
    const val MAX_PLAIN_BYTES = 4L shl 30

    /**
     * 一处分块附件的头部。
     *
     * 构造时就把自洽性校验做掉（块数与明文长度必须互相推得出来），
     * 于是拿到 [Header] 的人不必再怀疑它内部是否矛盾 —— 这比在每个使用点
     * 各写一遍防御要可靠：漏写一处就是一个越界读。
     */
    class Header(
        val chunkSize: Int,
        val chunkCount: Int,
        val plainSize: Long,
    ) {
        init {
            require(chunkSize in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES) { "块大小越界: $chunkSize" }
            require(plainSize in 1..MAX_PLAIN_BYTES) { "明文长度越界: $plainSize" }
            require(chunkCount >= 1) { "块数至少为 1: $chunkCount" }
            require(chunkCount.toLong() == chunkCountOf(plainSize, chunkSize)) {
                "块数与明文长度不自洽: count=$chunkCount size=$plainSize 块大小=$chunkSize"
            }
        }

        /**
         * 除最后一块外，每块在文件里占的字节数 = 明文块 + 固定开销。
         *
         * 开销走 [AeadCipher.OVERHEAD_BYTES] 一处，不在这里把 nonce 与 tag 再加一遍 ——
         * 下面三处偏移算术原先各写了一份 `nonce + 明文 + tag`，改 nonce 或 tag 长度时
         * 漏掉任何一处都是**静默的偏移错位**，要等画面读花了才会发现。
         */
        val storedStride: Long get() = (chunkSize + AeadCipher.OVERHEAD_BYTES).toLong()

        /** 第 [index] 块的明文长度（最后一块可能不满）。 */
        fun plainBytesOf(index: Int): Int {
            require(index in 0 until chunkCount) { "块序号越界: $index/$chunkCount" }
            if (index < chunkCount - 1) return chunkSize
            return (plainSize - plainBytesBeforeLast()).toInt()
        }

        /** 第 [index] 块在文件里的起始偏移。所有非末块等长，所以是纯算术，不必查表。 */
        fun storedOffsetOf(index: Int): Long {
            require(index in 0 until chunkCount) { "块序号越界: $index/$chunkCount" }
            return HEADER_BYTES + index * storedStride
        }

        /** 整个文件应有的字节数。用来与真实文件大小比对，挡住魔数误判。 */
        fun expectedStoredBytes(): Long {
            val full = (chunkCount - 1).toLong()
            val lastPlain = plainBytesOf(chunkCount - 1).toLong()
            return HEADER_BYTES + full * storedStride + (lastPlain + AeadCipher.OVERHEAD_BYTES)
        }

        /** 解码第 [index] 块时需要从文件里读多少字节。 */
        fun storedBytesOf(index: Int): Int =
            AeadCipher.OVERHEAD_BYTES + plainBytesOf(index)

        /** 末块之前的明文总量。块数与明文长度的自洽性已在 init 里保证，所以它恒 < plainSize。 */
        private fun plainBytesBeforeLast(): Long = (chunkCount - 1).toLong() * chunkSize
    }

    fun chunkCountOf(plainSize: Long, chunkSize: Int): Long =
        (plainSize + chunkSize - 1) / chunkSize

    fun headerOf(plainSize: Long, chunkSize: Int = DEFAULT_CHUNK_BYTES): Header =
        Header(chunkSize, chunkCountOf(plainSize, chunkSize).toInt(), plainSize)

    fun encodeHeader(header: Header): ByteArray {
        val out = ByteArrayOutputStream(HEADER_BYTES)
        DataOutputStream(out).use { d ->
            d.write(MAGIC)
            d.writeInt(header.chunkSize)
            d.writeInt(header.chunkCount)
            d.writeLong(header.plainSize)
        }
        return out.toByteArray()
    }

    /**
     * 解析头部。**任何不合规都返回 null，绝不抛异常** —— 调用方拿它当"是不是分块格式"的判据，
     * 而正常的老附件（整块格式）本来就该走到 null 这一支。
     *
     * 只校验头部自身的自洽性；"文件长度是否与块布局相符"由调用方补上（那需要 filesystem 访问，
     * 而这里刻意保持不碰 IO，好让整个格式逻辑能在 JVM 单测里跑）。
     */
    fun parseHeader(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Header? {
        if (length < HEADER_BYTES || offset < 0 || offset + HEADER_BYTES > bytes.size) return null
        for (i in MAGIC.indices) {
            if (bytes[offset + i] != MAGIC[i]) return null
        }
        var p = offset + MAGIC.size
        val chunkSize = readInt(bytes, p); p += 4
        val chunkCount = readInt(bytes, p); p += 4
        val plainSize = readLong(bytes, p)
        // 块数与明文长度的自洽校验在 Header 的 init 里，这里只是把异常换成 null。
        return runCatching { Header(chunkSize, chunkCount, plainSize) }.getOrNull()
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    private fun readLong(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }
}
