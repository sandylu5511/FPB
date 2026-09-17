package com.fpb.vault.data

import com.fpb.vault.crypto.AeadCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Random

/**
 * 分块格式（视频落盘格式）的纯算术部分。
 *
 * 这一层刻意不碰 IO，就是为了让它能被这样钉死 —— 因为它错的方式是**越界读**：
 * 块偏移是纯算术算出来的（不查表），一个取整方向的错误会让"第 100 块"的偏移
 * 偏出文件之外，而那种错误在真机上表现为"视频播到某个位置突然报错"，极难复现。
 */
class ChunkedBlobFormatTest {

    private val cs = ChunkedBlobFormat.DEFAULT_CHUNK_BYTES

    private fun header(plainSize: Long, chunkSize: Int = cs) =
        ChunkedBlobFormat.headerOf(plainSize, chunkSize)

    @Test
    fun `头部往返编码解析一致`() {
        val original = header(plainSize = 3L * cs + 777)
        val parsed = ChunkedBlobFormat.parseHeader(ChunkedBlobFormat.encodeHeader(original))

        assertNotNull("自家编码的头必须能被自家解析", parsed)
        assertEquals(original.chunkSize, parsed!!.chunkSize)
        assertEquals(original.chunkCount, parsed.chunkCount)
        assertEquals(original.plainSize, parsed.plainSize)
    }

    @Test
    fun `块数按向上取整且整数倍时不多出一块`() {
        assertEquals(1L, ChunkedBlobFormat.chunkCountOf(1, cs))
        assertEquals(1L, ChunkedBlobFormat.chunkCountOf(cs.toLong(), cs))
        assertEquals(2L, ChunkedBlobFormat.chunkCountOf(cs.toLong() + 1, cs))
        assertEquals(4L, ChunkedBlobFormat.chunkCountOf(4L * cs, cs))
    }

    @Test
    fun `块偏移是纯算术：头部 + 序号乘步长`() {
        val h = header(plainSize = 5L * cs + 1)
        val stride = (AeadCipher.NONCE_BYTES + cs + AeadCipher.TAG_BYTES).toLong()

        assertEquals(ChunkedBlobFormat.HEADER_BYTES.toLong(), h.storedOffsetOf(0))
        assertEquals(ChunkedBlobFormat.HEADER_BYTES + 3 * stride, h.storedOffsetOf(3))
    }

    @Test
    fun `每块的存储长度恒定，末块按余数变短`() {
        val h = header(plainSize = 2L * cs + 100)
        val full = AeadCipher.NONCE_BYTES + cs + AeadCipher.TAG_BYTES

        assertEquals(3, h.chunkCount)
        assertEquals(full, h.storedBytesOf(0))
        assertEquals(full, h.storedBytesOf(1))
        assertEquals(AeadCipher.NONCE_BYTES + 100 + AeadCipher.TAG_BYTES, h.storedBytesOf(2))
        assertEquals(cs, h.plainBytesOf(0))
        assertEquals(100, h.plainBytesOf(2))
    }

    @Test
    fun `expectedStoredBytes 与逐块长度求和完全一致`() {
        // 这条是"文件长度校验能不能挡住魔数误判"的地基：两处算出来的必须逐字节相同，
        // 差一个字节就会让所有正常视频被判成"不是分块格式"（打不开）。
        for (plainSize in listOf(1L, 100L, cs.toLong(), cs + 1L, 3L * cs, 3L * cs + 999)) {
            val h = header(plainSize)
            val sum = ChunkedBlobFormat.HEADER_BYTES.toLong() +
                (0 until h.chunkCount).sumOf { h.storedBytesOf(it).toLong() }
            assertEquals("明文 $plainSize 时两种算法必须一致", sum, h.expectedStoredBytes())
        }
    }

    @Test
    fun `块数与明文长度不自洽时拒绝构造`() {
        // 头部是可被改动的明文，所以"自洽性"必须在构造时就检查掉 ——
        // 否则每个使用点都要自己防一遍，漏一处就是一个越界读。
        assertThrows(IllegalArgumentException::class.java) {
            ChunkedBlobFormat.Header(cs, chunkCount = 3, plainSize = 2L * cs)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChunkedBlobFormat.Header(cs, chunkCount = 1, plainSize = 2L * cs)
        }
    }

    @Test
    fun `块大小越界被拒`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChunkedBlobFormat.Header(ChunkedBlobFormat.MIN_CHUNK_BYTES - 1, 1, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChunkedBlobFormat.Header(ChunkedBlobFormat.MAX_CHUNK_BYTES + 1, 1, 1L)
        }
    }

    @Test
    fun `明文长度为 0 或超出格式上界被拒`() {
        // 0 字节的附件没有任何意义，而且会让"末块"这个概念失去定义。
        assertThrows(IllegalArgumentException::class.java) { ChunkedBlobFormat.Header(cs, 1, 0L) }
        assertThrows(IllegalArgumentException::class.java) {
            ChunkedBlobFormat.Header(cs, 1, ChunkedBlobFormat.MAX_PLAIN_BYTES + 1)
        }
    }

    @Test
    fun `越界的块序号取长度时报错而不是静默给 0`() {
        val h = header(plainSize = cs.toLong())
        assertThrows(IllegalArgumentException::class.java) { h.plainBytesOf(1) }
        assertThrows(IllegalArgumentException::class.java) { h.storedOffsetOf(-1) }
    }

    // ==================== 解析侧：一切不合规都返回 null ====================

    @Test
    fun `随机字节不会被当成头部`() {
        // 老附件（整块格式）的头 12 字节是随机 nonce。它必须走到 null 这一支，
        // 否则一个图片会被当成视频去解析出天文数字的块数。
        repeat(200) {
            val rnd = Random(20260917L + it)
            val junk = ByteArray(ChunkedBlobFormat.HEADER_BYTES).also { rnd.nextBytes(it) }
            assertNull("随机字节不该被解析成头部", ChunkedBlobFormat.parseHeader(junk))
        }
    }

    @Test
    fun `魔数被改一位即返回 null`() {
        val encoded = ChunkedBlobFormat.encodeHeader(header(cs.toLong()))
        for (i in ChunkedBlobFormat.MAGIC.indices) {
            val corrupted = encoded.copyOf()
            corrupted[i] = (corrupted[i].toInt() xor 0x01).toByte()
            assertNull("第 $i 字节被改后不该通过", ChunkedBlobFormat.parseHeader(corrupted))
        }
    }

    @Test
    fun `长度不足一个头部时返回 null`() {
        val short = ChunkedBlobFormat.encodeHeader(header(cs.toLong()))
            .copyOfRange(0, ChunkedBlobFormat.HEADER_BYTES - 1)
        assertNull(ChunkedBlobFormat.parseHeader(short))
        assertNull(ChunkedBlobFormat.parseHeader(ByteArray(0)))
    }

    @Test
    fun `带 offset 解析时只看那一段`() {
        val encoded = ChunkedBlobFormat.encodeHeader(header(2L * cs + 5))
        val padded = ByteArray(7) + encoded + ByteArray(3)

        val parsed = ChunkedBlobFormat.parseHeader(padded, offset = 7, length = encoded.size)
        assertNotNull(parsed)
        assertEquals(2L * cs + 5, parsed!!.plainSize)

        // 长度声明得比实际短时也不能越界去读后面的字节
        assertNull(ChunkedBlobFormat.parseHeader(padded, offset = 7, length = 10))
    }

    @Test
    fun `改了块数但没同步改明文长度会被拒`() {
        // 这是"把 chunkCount 改小再截短文件"那类篡改的第一步。
        // 头部这一关挡不住它（攻击者可以把两个字段一起改成自洽的），
        // 真正的防线是块级 AAD 的末块标记 —— 见 FileBlobStoreChunkedTest 里那条用例。
        val encoded = ChunkedBlobFormat.encodeHeader(header(3L * cs))
        // plainSize 落在偏移 16（8 魔数 + 4 块大小 + 4 块数）
        encoded[19] = (encoded[19].toInt() xor 0x01).toByte()

        assertNull(ChunkedBlobFormat.parseHeader(encoded))
    }
}
