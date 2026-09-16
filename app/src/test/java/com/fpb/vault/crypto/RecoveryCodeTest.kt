package com.fpb.vault.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCodeTest {

    @Test
    fun `生成的恢复码为 48 字符且全部规范`() {
        repeat(50) {
            val code = RecoveryCode.generate()
            assertEquals(RecoveryCode.CANONICAL_LENGTH, code.length)
            assertTrue("生成了非规范字符：$code", Base32Crockford.isCanonical(code))
        }
    }

    @Test
    fun `展示格式为 12 组每组 4 字符`() {
        val display = RecoveryCode.formatForDisplay(RecoveryCode.generate())
        val groups = display.split(RecoveryCode.GROUP_SEPARATOR)
        assertEquals("应为 12 组", 12, groups.size)
        assertTrue("每组应为 4 字符", groups.all { it.length == 4 })
    }

    @Test
    fun `熵为 240 位`() {
        assertEquals(30, RecoveryCode.ENTROPY_BYTES)
        assertEquals(240, RecoveryCode.ENTROPY_BYTES * 8)
        assertEquals(RecoveryCode.CANONICAL_LENGTH, RecoveryCode.ENTROPY_BYTES * 8 / 5)
    }

    @Test
    fun `每次生成的恢复码都不同`() {
        val codes = (1..200).map { RecoveryCode.generate() }.toSet()
        assertEquals("出现重复的恢复码，随机源有问题", 200, codes.size)
    }

    @Test
    fun `归一化可容忍连字符 空格 换行与小写`() {
        val code = RecoveryCode.generate()
        val display = RecoveryCode.formatForDisplay(code)

        assertEquals(code, RecoveryCode.canonicalize(display))
        assertEquals(code, RecoveryCode.canonicalize(display.lowercase()))
        assertEquals(code, RecoveryCode.canonicalize("  $display  "))
        assertEquals(code, RecoveryCode.canonicalize(display.replace("-", "")))
        assertEquals(code, RecoveryCode.canonicalize(display.replace("-", "\n")))
    }

    @Test
    fun `抄写错误 O 与 I 与 L 能被自动纠正`() {
        // 构造一个必然含 0 和 1 的码，再模拟用户把它们抄成 O / l / I 的情况
        val base = RecoveryCode.generate()
        val seeded = ('0' + base.drop(1)).take(RecoveryCode.CANONICAL_LENGTH)
        val asTyped = seeded
            .map { c -> when (c) { '0' -> 'O'; '1' -> 'l'; else -> c } }
            .joinToString("")
        val decorated = asTyped.chunked(4).joinToString("-")

        assertEquals(
            "抄错的 O / l 应被纠正回 0 / 1",
            RecoveryCode.canonicalize(seeded),
            RecoveryCode.canonicalize(decorated),
        )
    }

    @Test
    fun `长度不符的输入被拒绝`() {
        val code = RecoveryCode.generate()
        assertNull(RecoveryCode.canonicalize(code.dropLast(1)))
        assertNull(RecoveryCode.canonicalize(code + "A"))
        assertNull(RecoveryCode.canonicalize(""))
        assertNull(RecoveryCode.canonicalize("   "))
        assertNull(RecoveryCode.canonicalize("ABCD-EFGH"))
    }

    @Test
    fun `含非法字符的输入被拒绝`() {
        val code = RecoveryCode.generate()
        val withIllegal = "U" + code.drop(1) // U 是唯一被彻底排除、不做容错的字母
        assertNull(RecoveryCode.canonicalize(withIllegal))
        assertNull(RecoveryCode.canonicalize("!" + code.drop(1)))
        assertNull(RecoveryCode.canonicalize(code.dropLast(1) + "中文!"))
    }

    @Test
    fun `isValid 与 canonicalize 判断一致`() {
        val code = RecoveryCode.generate()
        assertTrue(RecoveryCode.isValid(code))
        assertTrue(RecoveryCode.isValid(RecoveryCode.formatForDisplay(code)))
        assertFalse(RecoveryCode.isValid(code + "ZZZZ"))
        assertFalse(RecoveryCode.isValid(""))
    }

    @Test
    fun `规范化后长度不变且可再次规范化`() {
        val code = RecoveryCode.generate()
        val once = RecoveryCode.canonicalize(code)!!
        val twice = RecoveryCode.canonicalize(once)!!
        assertEquals(once, twice)
        assertEquals(code, once)
    }

    @Test
    fun `不同恢复码之间不会碰撞`() {
        val a = RecoveryCode.generate()
        val b = RecoveryCode.generate()
        assertNotEquals(a, b)
        assertNotNull(RecoveryCode.canonicalize(a))
        assertNotNull(RecoveryCode.canonicalize(b))
    }
}
