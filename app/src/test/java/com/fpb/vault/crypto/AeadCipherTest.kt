package com.fpb.vault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AeadCipherTest {

    private val key = ByteArray(32) { it.toByte() }
    private val aad = "mixia:v1:entry:test:body".toByteArray()
    private val plaintext = "绝密内容 secret payload".toByteArray()

    @Test
    fun `加密后可以解密还原`() {
        val sealed = AeadCipher.seal(key, plaintext, aad)
        val opened = AeadCipher.open(key, sealed.nonce, sealed.ciphertext, aad)
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun `空明文与长明文都能正确处理`() {
        for (payload in listOf(ByteArray(0), ByteArray(1), SecureBytes.random(200_000))) {
            val sealed = AeadCipher.seal(key, payload, aad)
            assertArrayEquals(payload, AeadCipher.open(key, sealed.nonce, sealed.ciphertext, aad))
        }
    }

    @Test
    fun `密文任意一位被改动都无法解密`() {
        val sealed = AeadCipher.seal(key, plaintext, aad)
        for (index in sealed.ciphertext.indices) {
            val tampered = sealed.ciphertext.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertNull(
                "第 $index 字节被篡改，却仍然解密成功 —— GCM 认证未生效",
                AeadCipher.open(key, sealed.nonce, tampered, aad),
            )
        }
    }

    @Test
    fun `nonce 被改动就无法解密`() {
        val sealed = AeadCipher.seal(key, plaintext, aad)
        val tampered = sealed.nonce.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNull(AeadCipher.open(key, tampered, sealed.ciphertext, aad))
    }

    @Test
    fun `AAD 不匹配时解密失败`() {
        val sealed = AeadCipher.seal(key, plaintext, "mixia:v1:entry:A:body".toByteArray())
        assertNull(
            "AAD 不同却解密成功 —— 密文将可以在不同条目之间搬运",
            AeadCipher.open(key, sealed.nonce, sealed.ciphertext, "mixia:v1:entry:B:body".toByteArray()),
        )
    }

    @Test
    fun `换一把密钥无法解密`() {
        val sealed = AeadCipher.seal(key, plaintext, aad)
        val otherKey = ByteArray(32) { (it + 1).toByte() }
        assertNull(AeadCipher.open(otherKey, sealed.nonce, sealed.ciphertext, aad))
    }

    @Test
    fun `密文被截断无法解密`() {
        val sealed = AeadCipher.seal(key, plaintext, aad)
        assertNull(
            AeadCipher.open(
                key,
                sealed.nonce,
                sealed.ciphertext.copyOf(sealed.ciphertext.size - 1),
                aad,
            ),
        )
        assertNull(AeadCipher.open(key, sealed.nonce, ByteArray(4), aad))
        assertNull(AeadCipher.open(key, sealed.nonce, ByteArray(0), aad))
    }

    @Test
    fun `相同明文两次加密产生不同密文与 nonce`() {
        val a = AeadCipher.seal(key, plaintext, aad)
        val b = AeadCipher.seal(key, plaintext, aad)
        assertFalse("nonce 复用了", a.nonce.contentEquals(b.nonce))
        assertFalse("密文相同，说明 nonce 没有真正随机化", a.ciphertext.contentEquals(b.ciphertext))
    }

    @Test
    fun `非法密钥长度被拒绝`() {
        assertRejectsIllegalArgument("16 字节密钥应被拒绝") {
            AeadCipher.seal(ByteArray(16), plaintext, aad)
        }
        assertNull(AeadCipher.open(ByteArray(16), ByteArray(12), ByteArray(48), aad))
    }

    @Test
    fun `密文长度包含 16 字节认证标签`() {
        val sealed = AeadCipher.seal(key, plaintext, aad)
        org.junit.Assert.assertEquals(
            plaintext.size + AeadCipher.TAG_BYTES,
            sealed.ciphertext.size,
        )
    }
}
