package com.fpb.vault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class Argon2KdfTest {

    /**
     * 测试统一用最低参数档（32 MiB / t2 / p1，实测约 44 ms）。
     * 参数档位只影响耗时，不影响派生逻辑本身 —— 同一份代码路径。
     */
    private fun fastParams() = KdfParams.fast()

    @Test
    fun `相同密码与相同参数派生出相同密钥`() {
        val params = fastParams()
        val a = Argon2Kdf.derive("correct horse battery staple".toCharArray(), params)
        val b = Argon2Kdf.derive("correct horse battery staple".toCharArray(), params)
        try {
            assertEquals(AeadCipher.KEY_BYTES, a.size)
            assertArrayEquals("KDF 不确定，换机导入备份将无法还原", a.expose(), b.expose())
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun `换个 salt 就派生出完全不同的密钥`() {
        val p1 = fastParams()
        val p2 = fastParams()
        assertFalse("两次生成的 salt 不应相同", p1.salt.contentEquals(p2.salt))

        val a = Argon2Kdf.derive("same password".toCharArray(), p1)
        val b = Argon2Kdf.derive("same password".toCharArray(), p2)
        try {
            assertFalse(
                "相同密码在不同 salt 下派生出同一密钥 —— salt 没有起作用",
                a.expose().contentEquals(b.expose()),
            )
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun `不同密码派生出不同密钥`() {
        val params = fastParams()
        val a = Argon2Kdf.derive("password-A".toCharArray(), params)
        val b = Argon2Kdf.derive("password-B".toCharArray(), params)
        try {
            assertFalse(a.expose().contentEquals(b.expose()))
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun `密码大小写与空白被视为不同`() {
        val params = fastParams()
        val a = Argon2Kdf.derive("Password".toCharArray(), params)
        val b = Argon2Kdf.derive("password".toCharArray(), params)
        val c = Argon2Kdf.derive(" password".toCharArray(), params)
        try {
            assertNotEquals(a.expose().toList(), b.expose().toList())
            assertNotEquals(b.expose().toList(), c.expose().toList())
        } finally {
            a.close()
            b.close()
            c.close()
        }
    }

    @Test
    fun `空密码被拒绝`() {
        assertRejectsIllegalArgument("空密码应被拒绝") {
            Argon2Kdf.derive(CharArray(0), fastParams())
        }
    }

    @Test
    fun `输出长度恒为 32 字节`() {
        for (password in listOf("a", "a longer password with spaces", "中文密码🔒")) {
            val dek = Argon2Kdf.derive(password.toCharArray(), fastParams())
            try {
                assertEquals(32, dek.size)
            } finally {
                dek.close()
            }
        }
    }

    @Test
    fun `标准档参数符合设计值`() {
        val params = KdfParams.standard()
        assertEquals("标准档内存开销应为 64 MiB", 64 * 1024, params.memoryKiB)
        assertEquals("标准档迭代次数应为 3", 3, params.iterations)
        assertEquals("标准档并行度应为 2", 2, params.parallelism)
        assertEquals(32, params.salt.size)
    }

    @Test
    fun `非法 KDF 参数被拒绝`() {
        assertRejectsIllegalArgument("内存过低应被拒绝") {
            KdfParams(1024, 3, 2, SecureBytes.random(32))
        }
        assertRejectsIllegalArgument("迭代次数为 0 应被拒绝") {
            KdfParams(64 * 1024, 0, 2, SecureBytes.random(32))
        }
        assertRejectsIllegalArgument("salt 长度不符应被拒绝") {
            KdfParams(64 * 1024, 3, 2, SecureBytes.random(16))
        }
    }

    @Test
    fun `参数相等性按内容比较`() {
        val salt = SecureBytes.random(32)
        val a = KdfParams(64 * 1024, 3, 2, salt)
        val b = KdfParams(64 * 1024, 3, 2, salt.copyOf())
        val c = KdfParams(64 * 1024, 3, 2, SecureBytes.random(32))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }
}
