package com.fpb.vault.data

import com.fpb.vault.crypto.Aad
import com.fpb.vault.crypto.AeadCipher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Random

/**
 * [ChunkedBlobReader] 自己的行为 —— 主要是**缓存那一层**。
 *
 * ## 为什么单开一个测试类

 * [FileBlobStoreChunkedTest] 是从"写进去、读出来"这个角度验分块格式的，
 * 它每次都把整段读完，而且块数（3 块）远小于缓存窗口（
 * [ChunkedBlobReader.DEFAULT_CACHE_CHUNKS] = 24 块）——
 * 也就是说**淘汰这件事从来没发生过**。
 *
 * 而淘汰恰恰是这个类里最容易写错、后果又最难查的一处：读出来的字节串了块、
 * 或者被淘汰的块再也读不出来，表现都是"视频播到某个位置开始花屏/卡死"。
 * 这类 bug 不会在"顺序读完一个小文件"里露面，必须把缓存**故意调小**去逼它。
 *
 * 所以这里把缓存压到 1~2 块，用 7 块的附件去撞：
 *
 *  · 顺序读完仍然逐字节正确（淘汰之后必须能重新解密，而不是吐空数据）；
 *  · 打乱顺序反复读任意窗口也不串块（块偏移是纯算术，不是查表）；
 *  · **访问序 LRU**：被反复读的块不会被新块挤掉（FIFO 会挤掉它 —— 这条专门分辨两者）；
 *  · 缓存命中不重复解密（用计数验证，不靠"应该很快"这种说法）；
 *  · 文件被截短（头部说 N 块、磁盘上没那么多）→ 抛 `IOException`，
 *    并且**坏掉之后继续抛**，绝不退化成"读到结尾了"（-1）。
 *
 * 密钥与 AAD 都是真的，所以"篡改必然暴露"在这里是密码学结论，不是替身的行为约定。
 */
class ChunkedBlobReaderTest {

    private lateinit var dir: File

    /** 块大小取合法下界 64 KiB：造得出 7 块，又不必让单测写几 MB。 */
    private val cs = ChunkedBlobFormat.MIN_CHUNK_BYTES

    private val key = ByteArray(AeadCipher.KEY_BYTES) { ((it * 53 + 7) and 0xFF).toByte() }

    /** 附件块数。取 7 是为了远大于下面用到的缓存窗口（1~2 块）。 */
    private val chunks = 7

    private val plainSize = cs * (chunks - 1) + 12_345

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("fpb-chunk-reader").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun bytes(size: Int, seed: Long = 7L) =
        ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun seal(blobId: String) =
        { index: Int, plain: ByteArray, length: Int, isLast: Boolean ->
            AeadCipher.seal(key, plain.copyOf(length), Aad.blobChunk(blobId, index, cs, isLast))
        }

    /** 写一个附件；返回它的明文，方便逐字节比对。 */
    private fun write(blobId: String): ByteArray {
        val plain = bytes(plainSize)
        FileBlobStore(dir).writeChunked(
            blobId, ByteArrayInputStream(plain), cs, Long.MAX_VALUE, seal(blobId),
        )
        return plain
    }

    /**
     * 打开一个读取器，并附带"每块被解密了几次"的计数器。
     *
     * 计数器是这个类里大半判据的**唯一**客观依据：缓存到底有没有生效、
     * 淘汰之后有没有重新解密，从读出来的字节上是看不出来的（两种情况都对）。
     */
    private fun readerOf(
        blobId: String,
        cacheChunks: Int,
        header: ChunkedBlobFormat.Header? = null,
    ): Pair<ChunkedBlobReader, MutableMap<Int, Int>> {
        val hits = mutableMapOf<Int, Int>()
        val opener = ChunkedBlobReader.ChunkOpener { index, nonce, cipher, isLast ->
            hits[index] = (hits[index] ?: 0) + 1
            AeadCipher.open(key, nonce, cipher, Aad.blobChunk(blobId, index, cs, isLast))
        }
        val hd = header ?: FileBlobStore(dir).chunkHeader(blobId)
        requireNotNull(hd) { "附件不是分块格式，测试前置条件不成立" }
        return ChunkedBlobReader(File(dir, blobId), hd, opener, cacheChunks) to hits
    }

    private fun readWindow(reader: BlobReader, offset: Long, length: Int): ByteArray {
        val buf = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = reader.readAt(offset + at, buf, at, length - at)
            if (n <= 0) fail("读到偏移 ${offset + at} 时提前结束（应为 ${offset + length}）")
            at += n
        }
        return buf
    }

    private fun readAll(reader: BlobReader) = readWindow(reader, 0L, reader.size.toInt())

    // ==================== 缓存装不下时，正确性不能有例外 ====================

    @Test
    fun `缓存只有 1 块时，顺序读完仍然逐字节正确`() {
        val blobId = "a".repeat(32)
        val plain = write(blobId)
        val (reader, hits) = readerOf(blobId, cacheChunks = 1)

        reader.use {
            assertArrayEquals(plain, readAll(it))
        }
        // 缓存只装得下 1 块，所以每一块都必须至少解密一次 —— 这条是"淘汰真的发生了"的证据。
        assertEquals("7 块各解密一次", (0 until chunks).toSet(), hits.keys)
        assertTrue("每块解密次数应恰为 1，实测 $hits", hits.values.all { it == 1 })
    }

    @Test
    fun `缓存装不下时，按打乱的顺序反复读任意窗口也不串块`() {
        val blobId = "b".repeat(32)
        val plain = write(blobId)
        val (reader, _) = readerOf(blobId, cacheChunks = 2)

        val rnd = Random(20260917L)
        reader.use {
            repeat(120) {
                val length = 1 + rnd.nextInt(9_000)
                val offset = rnd.nextInt(plain.size - length)
                assertArrayEquals(
                    "偏移 $offset 起 $length 字节",
                    plain.copyOfRange(offset, offset + length),
                    readWindow(reader, offset.toLong(), length),
                )
            }
        }
    }

    @Test
    fun `跨块边界的读取在缓存装不下时也正确`() {
        val blobId = "c".repeat(32)
        val plain = write(blobId)
        val (reader, _) = readerOf(blobId, cacheChunks = 1)

        // 每次从"块边界前若干字节"起读，正好横跨两块 —— 一趟下来把所有边界都过一遍。
        reader.use {
            for (index in 1 until chunks) {
                val offset = index.toLong() * cs - 40
                assertArrayEquals(
                    "跨第 $index 块边界",
                    plain.copyOfRange(offset.toInt(), offset.toInt() + 80),
                    readWindow(it, offset, 80),
                )
            }
        }
    }

    // ==================== 淘汰的语义：LRU，不是 FIFO ====================

    @Test
    fun `被淘汰的块再次读取会重新解密，而不是吐出空数据或坏数据`() {
        val blobId = "d".repeat(32)
        val plain = write(blobId)
        val (reader, hits) = readerOf(blobId, cacheChunks = 1)

        reader.use {
            assertArrayEquals(plain.copyOfRange(0, 16), readWindow(it, 0L, 16))
            assertEquals("第一次读第 0 块应当解密一次", 1, hits[0])

            // 读第 3 块 —— 缓存只装 1 块，第 0 块必被淘汰。
            assertArrayEquals(
                plain.copyOfRange(3 * cs, 3 * cs + 16),
                readWindow(it, 3L * cs, 16),
            )

            // 再读第 0 块：必须**重新解密**并给出正确字节，不能是空数组、也不能是第 3 块的内容。
            assertArrayEquals(plain.copyOfRange(0, 16), readWindow(it, 0L, 16))
            assertEquals("被淘汰后重读必须重新解密", 2, hits[0])
        }
    }

    @Test
    fun `是访问序 LRU —— 被反复读的块不会被新块挤掉`() {
        val blobId = "e".repeat(32)
        val plain = write(blobId)
        val (reader, hits) = readerOf(blobId, cacheChunks = 2)

        reader.use {
            readWindow(it, 0L, 8)           // 装 0
            readWindow(it, 1L * cs, 8)      // 装 1
            readWindow(it, 0L, 8)           // 命中 0，并把 0 变成"最近用过"
            readWindow(it, 2L * cs, 8)      // 装 2 → 要淘汰一个

            // LRU 淘汰的是"最久未用"的 1；FIFO 会淘汰最早插入的 0。
            assertEquals("0 被刚刚读过，不该被淘汰", 1, hits[0])
            readWindow(it, 1L * cs, 8)
            assertEquals("1 才是最久未用的那个，应当被淘汰后重解", 2, hits[1])
        }
        // 上面这一对断言合起来就唯一确定了淘汰策略是访问序，而不是插入序。
    }

    @Test
    fun `缓存命中不会重复解密`() {
        val blobId = "f".repeat(32)
        write(blobId)
        val (reader, hits) = readerOf(blobId, cacheChunks = 4)

        reader.use {
            // 注意：这里**不能**写 `it` —— `repeat` 的 lambda 自己也有一个 `it`
            // （重复次数，Int），内层的会遮蔽外层的，编译器只会报一句
            // "Int 不是 BlobReader"，看不出是被谁遮住了。
            repeat(50) { readWindow(reader, 0L, 4) }
            assertEquals("50 次读同一块只该解密一次", 1, hits[0])
        }
    }

    // ==================== 两类失败必须分开 ====================

    @Test
    fun `越界读取返回 -1 —— 这是「读完了」，不是「出错了」`() {
        val blobId = "1".repeat(32)
        write(blobId)
        val (reader, _) = readerOf(blobId, cacheChunks = 1)

        reader.use {
            assertEquals(-1, it.readAt(it.size, ByteArray(8), 0, 8))
            assertEquals(-1, it.readAt(it.size + 1_000, ByteArray(8), 0, 8))
            assertEquals(-1, it.readAt(-1, ByteArray(8), 0, 8))
            // 贴着末尾起读：只给得出剩余那几个字节，而不是 -1。
            val n = it.readAt(it.size - 3, ByteArray(8), 0, 8)
            assertEquals("末尾起读只该拿到剩下的 3 字节", 3, n)
        }
    }

    @Test
    fun `文件被截短而头部没改时抛 IOException，并且坏掉之后就一直是坏的`() {
        // 造一个合法的 3 块文件，然后用一个**声明 7 块**的头部去打开它。
        // 这正是攻击者能做的那件事：改头部字段、再把文件截短。
        // （`FileBlobStore.chunkHeader` 的"长度必须与块布局相符"校验会挡掉这种文件，
        //   所以这里直接构造读取器 —— 验的是读取器自己那一道防线。）
        val blobId = "2".repeat(32)
        val plain = bytes(cs * 2 + 100)
        FileBlobStore(dir).writeChunked(
            blobId, ByteArrayInputStream(plain), cs, Long.MAX_VALUE, seal(blobId),
        )
        val forged = ChunkedBlobFormat.Header(cs, chunks, cs * (chunks - 1L) + 100)
        val (reader, _) = readerOf(blobId, cacheChunks = 1, header = forged)

        reader.use {
            // 块偏移只由块大小决定，与块数无关 —— 所以前两块在伪造的布局下**位置也对**，
            // 第 0 块应当读得出正确内容。先确认这一点，"后面抛"才说明是截断而不是从头就坏。
            assertArrayEquals(plain.copyOfRange(0, 16), readWindow(it, 0L, 16))

            // 第 4 块早已不在磁盘上。位置本身是合法的（< 声明的明文总长），
            // 所以这里**绝不允许**返回 -1：那等于把截断说成"读到结尾"，
            // 用户会拿到一段静默变短的影片。
            val at = 4L * cs
            assertThrows(IOException::class.java) {
                it.readAt(at, ByteArray(32), 0, 32)
            }
            // 坏掉之后必须继续抛，不能悄悄降级。
            assertThrows(IOException::class.java) {
                it.readAt(0L, ByteArray(32), 0, 32)
            }
        }
    }

    @Test
    fun `关闭之后缓存被释放，且重复关闭不抛`() {
        val blobId = "3".repeat(32)
        write(blobId)
        val (reader, _) = readerOf(blobId, cacheChunks = 2)
        reader.use { readWindow(it, 0L, 8) }
        reader.close() // 第二次关闭：用户退出播放页与播放器释放可能各调一次
    }
}
