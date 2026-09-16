package com.fpb.vault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Base32CrockfordTest {

    @Test
    fun `编码后再解码可以还原原始字节`() {
        val cases = listOf(
            byteArrayOf(0),
            byteArrayOf(0xFF.toByte()),
            byteArrayOf(0, 0),
            byteArrayOf(1, 2, 3, 4, 5),
            SecureBytes.random(1),
            SecureBytes.random(30),
            SecureBytes.random(32),
            SecureBytes.random(100),
        )
        for (original in cases) {
            val encoded = Base32Crockford.encode(original)
            val decoded = Base32Crockford.decodeOrNull(encoded, original.size)
            assertArrayEquals(
                "往返失败，原始长度 ${original.size}，编码为 $encoded",
                original,
                decoded,
            )
        }
    }

    @Test
    fun `空数组编码为空串`() {
        assertEquals("", Base32Crockford.encode(ByteArray(0)))
    }

    @Test
    fun `30 字节恰好编码为 48 字符`() {
        // 240 位 / 5 = 48，整除，因此没有填充位 —— 这正是恢复码选 30 字节的原因
        assertEquals(48, Base32Crockford.encode(SecureBytes.random(30)).length)
    }

    @Test
    fun `O 归一化为 0 I 与 L 归一化为 1`() {
        assertEquals("0", Base32Crockford.normalize("O"))
        assertEquals("1", Base32Crockford.normalize("I"))
        assertEquals("1", Base32Crockford.normalize("L"))
        // U 是唯一被彻底排除的字符（Crockford 规范为了减少脏话组合）
        assertNull(Base32Crockford.normalize("U"))
    }

    @Test
    fun `字母表不含易混字符`() {
        for (c in listOf('I', 'L', 'O', 'U')) {
            assertFalse("$c 不应被判为规范字符", Base32Crockford.isCanonical(c.toString()))
        }
        assertTrue(Base32Crockford.isCanonical("0123456789ABCDEFGHJKMNPQRSTVWXYZ"))
    }

    @Test
    fun `拒绝非法字符`() {
        assertNull(Base32Crockford.normalize("AB#C"))
        assertNull(Base32Crockford.normalize("AB!C"))
        assertNull(Base32Crockford.normalize("中文"))
        assertNull(Base32Crockford.decodeOrNull("!!!!!!!!"))
    }

    @Test
    fun `拒绝非规范编码_尾部填充位必须为零`() {
        // 1 字节 = 8 位，编码为 2 个符号：第 2 个符号只承载 3 位有效数据，
        // 其低 2 位必须为 0。符号 '1' 的值为 1，低 2 位是 01，不合法。
        //
        // 这条校验不能省：若允许填充位非零，同一个字节串就会有多个合法字符串表达，
        // 等于凭空多出一堆可猜测的别名。
        assertNull(Base32Crockford.decodeOrNull("01", 1))
        assertNotNull(Base32Crockford.decodeOrNull("00", 1))
    }

    @Test
    fun `期望长度不符时返回 null`() {
        val encoded = Base32Crockford.encode(SecureBytes.random(30))
        assertNull(Base32Crockford.decodeOrNull(encoded, 29))
        assertNull(Base32Crockford.decodeOrNull(encoded, 31))
        assertNotNull(Base32Crockford.decodeOrNull(encoded, 30))
    }

    @Test
    fun `分隔符与空白被忽略`() {
        val raw = SecureBytes.random(30)
        val canonical = Base32Crockford.encode(raw)
        val decorated = canonical.chunked(4).joinToString("-") + "\n"
        assertArrayEquals(raw, Base32Crockford.decodeOrNull(decorated, 30))
    }

    @Test
    fun `小写输入可被归一化`() {
        val raw = SecureBytes.random(30)
        val canonical = Base32Crockford.encode(raw)
        assertArrayEquals(raw, Base32Crockford.decodeOrNull(canonical.lowercase(), 30))
    }
}
