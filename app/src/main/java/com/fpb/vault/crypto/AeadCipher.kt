package com.fpb.vault.crypto

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 认证加密。
 *
 * 为什么用 GCM 而不是 CBC + HMAC：
 * GCM 自带的认证标签使得密文被改动任意一位都会解密失败，
 * 不需要额外再挂一层 HMAC，也就不存在"加密和认证顺序搞错"这类经典漏洞。
 *
 * AAD（附加认证数据）不是可选项，是这里的关键设计：
 * 它把密文和"它该出现在哪"绑定起来。例如把假密码库的密钥包裹密文
 * 复制到真密码库的位置，AAD 不匹配就会解密失败 —— 否则攻击者可以
 * 用一次复制把假密码变成打开真库的钥匙。
 */
object AeadCipher {

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8

    /** 一段密文及其随机 nonce。nonce 无需保密，但**绝不能重用**（随机 96 位已足够避免碰撞）。 */
    class Sealed(val nonce: ByteArray, val ciphertext: ByteArray) {
        val overheadBytes: Int get() = nonce.size + ciphertext.size
    }

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): Sealed {
        require(key.size == KEY_BYTES) { "AES-256 密钥必须为 $KEY_BYTES 字节" }
        val nonce = SecureBytes.random(NONCE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(aad)
        return Sealed(nonce, cipher.doFinal(plaintext))
    }

    fun seal(key: SecureBytes, plaintext: ByteArray, aad: ByteArray): Sealed =
        seal(key.expose(), plaintext, aad)

    /** 直接用 DEK 加密内容，这是最常用的形式。 */
    fun seal(key: VaultDek, plaintext: ByteArray, aad: ByteArray): Sealed =
        seal(key.expose(), plaintext, aad)

    /**
     * 解密并校验完整性。认证失败返回 null，**不抛异常**。
     *
     * 返回 null 而不是抛异常是有意的：调用方在"密码不对"和"密文被篡改"
     * 两种情况下会自然走到同一个拒绝分支，不会因为异常类型不同而泄漏信息。
     */
    fun open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray? {
        if (key.size != KEY_BYTES) return null
        if (nonce.size != NONCE_BYTES) return null
        if (ciphertext.size < TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    fun open(
        key: SecureBytes,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray? = open(key.expose(), nonce, ciphertext, aad)

    fun open(
        key: VaultDek,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray? = open(key.expose(), nonce, ciphertext, aad)

    /**
     * 用平台默认 Provider 的 AES/GCM/NoPadding。
     * Android 上是 Conscrypt，JVM 上是 SunJCE —— 两边都从 API 21 起支持，且行为一致。
     */
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
