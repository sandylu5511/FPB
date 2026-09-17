package com.fpb.vault.data

import com.fpb.vault.crypto.AeadCipher
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 分块附件的随机访问读取器：读哪块解哪块，带一个 LRU 块缓存。
 *
 * ## 为什么这样就够了
 *
 * `MediaPlayer` 播放时的读取**大部分是顺序的**（它按需向前推进），偶发跳转。
 * 所以「按需解一块 + 缓存最近若干块」的命中率很高：一个 24 块的窗口
 * （默认 24 MiB 明文）足以覆盖顺序播放，跳转时最多多解一块。
 *
 * 缓存里存的是**明文**，因此它和 `BitmapCache` 一样受同一条纪律约束：
 * 只在内存里、随读取器关闭而释放、绝不落盘。它比图片缓存更短命 ——
 * 关掉播放页就没了。
 *
 * ## 两类失败必须分开，这是这个类最要紧的一处
 *
 * - **越界**（`position` 超出明文长度）→ 返回 **-1**，这是「读完了」的正常信号。
 * - **密文损坏 / 认证失败 / 文件被截断** → 抛 [IOException]，并且从此把整个读取器
 *   标记为坏掉。
 *
 * 把后者伪装成 -1（也就是"读到结尾了"）会造成一种非常隐蔽的伤害：
 * 用户看到一个**静默变短**的影片，没有任何提示，还会以为是当初就没录完。
 * 而 `MediaDataSource.readAt` 本来就声明了 `throws IOException`，
 * 抛出去能让播放器报错、界面如实说明「这段视频读不出来」。
 */
class ChunkedBlobReader internal constructor(
    private val file: File,
    internal val header: ChunkedBlobFormat.Header,
    private val open: ChunkOpener,
    maxCachedChunks: Int = DEFAULT_CACHE_CHUNKS,
) : BlobReader {

    /** 解密一个块的实现。由持有密钥的一方提供（见 [BlobSink.openChunked]）。 */
    fun interface ChunkOpener {
        /**
         * 解密第 [index] 块。
         *
         * [isLast] 必须原样传进 AAD（见 [com.fpb.vault.crypto.Aad.blobChunk]）——
         * 读取方比写入方多知道一件事：**哪个块是末块**，而这个信息正是"文件被截断"的判据。
         *
         * 认证失败返回 null（不抛异常）——「密码不对」与「密文被改过」在上层是同一个拒绝分支。
         */
        fun open(index: Int, nonce: ByteArray, ciphertext: ByteArray, isLast: Boolean): ByteArray?
    }

    private val raf = RandomAccessFile(file, "r")

    /**
     * 访问序 LRU：`accessOrder = true` 让 `get` 也算一次使用。
     * 用 `LinkedHashMap` 而不是自己实现，是因为"淘汰最久未用的那个"这里只需要一行。
     */
    private val cache = object : LinkedHashMap<Int, ByteArray>(CACHE_INITIAL, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>): Boolean =
            size > maxCachedChunks
    }

    /** 缓存、文件指针、`broken` 三者必须一起受保护：`readAt` 可能来自播放器线程与界面线程。 */
    private val lock = Any()

    private var broken = false

    override val size: Long get() = header.plainSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        // 越界是"读完"，不是"出错" —— 这两个必须分开，见类注释。
        if (position < 0 || position >= header.plainSize) return -1
        if (size <= 0) return 0

        val total = minOf(size.toLong(), header.plainSize - position).toInt()
        var copied = 0
        var cursor = position
        while (copied < total) {
            val index = (cursor / header.chunkSize).toInt()
            val plain = chunkAt(index)
            val within = (cursor % header.chunkSize).toInt()
            val take = minOf(plain.size - within, total - copied)
            System.arraycopy(plain, within, buffer, offset + copied, take)
            copied += take
            cursor += take
        }
        return copied
    }

    override fun close() {
        synchronized(lock) {
            cache.clear()
            runCatching { raf.close() }
        }
    }

    /** 缓存窗口：24 × 1 MiB。与图片 LRU 的 32 MiB 上限同量级，不会互相挤兑。 */
    private fun chunkAt(index: Int): ByteArray = synchronized(lock) {
        if (broken) throw IOException("附件已损坏，拒绝继续读取: ${file.name}")
        cache[index]?.let { return it }

        val plainLength = header.plainBytesOf(index)
        val stored = ByteArray(header.storedBytesOf(index))
        try {
            raf.seek(header.storedOffsetOf(index))
            raf.readFully(stored)
        } catch (e: IOException) {
            // 文件被截短（或干脆没了）时会走到这里。
            broken = true
            throw IOException("附件读取失败: ${file.name}", e)
        }

        val nonce = stored.copyOfRange(0, AeadCipher.NONCE_BYTES)
        val ciphertext = stored.copyOfRange(AeadCipher.NONCE_BYTES, stored.size)
        val plain = open.open(index, nonce, ciphertext, index == header.chunkCount - 1)
        // 明文长度与头部声明不符 = 头部被改过（AAD 本应挡住，这里再兜一层）。
        if (plain == null || plain.size != plainLength) {
            broken = true
            throw IOException("附件第 $index 块校验失败: ${file.name}")
        }

        cache[index] = plain
        return plain
    }

    companion object {
        const val DEFAULT_CACHE_CHUNKS = 24

        private const val CACHE_INITIAL = 12
    }
}
