package com.fpb.vault.crypto

/**
 * 数据密钥（DEK）的包裹槽位。
 *
 * 一把 DEK 会被**多把不同的 KEK 各包裹一份**存入不同槽位，这样：
 * - 改主密码只需要把主密码槽重新包裹一遍，**不必重新加密任何内容**（秒完成）
 * - 忘记主密码可以用恢复码槽解出同一把 DEK
 * - 生物识别槽由 Android Keystore 的硬件密钥把守
 *
 * 槽位号会参与 AAD 计算，因此密文被从一个槽搬到另一个槽必然解密失败。
 */
enum class KeySlot(val id: Int) {
    /** 真库的主密码槽。 */
    PRIMARY(1),

    /** 假密码槽，解出的是**另一个完全独立的 DEK**（诱饵库）。 */
    DECOY(2),

    /** 恢复码槽，解出真库的 DEK。 */
    RECOVERY(3),

    /** 生物识别槽，解出真库的 DEK；M4 接入 Keystore 后启用。 */
    BIOMETRIC(4),

    ;

    companion object {
        fun fromId(id: Int): KeySlot? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 被 KEK 包裹后的 DEK。
 *
 * [ciphertext] 的长度恒为 `32（DEK）+ 16（GCM 标签）= 48` 字节。
 */
class WrappedKey(val slot: KeySlot, val nonce: ByteArray, val ciphertext: ByteArray) {

    fun matches(other: WrappedKey): Boolean =
        slot == other.slot &&
            SecureBytes.constantTimeEquals(nonce, other.nonce) &&
            SecureBytes.constantTimeEquals(ciphertext, other.ciphertext)

    companion object {
        /** DEK 32 字节 + GCM 认证标签 16 字节。 */
        const val EXPECTED_CIPHERTEXT_BYTES = AeadCipher.KEY_BYTES + AeadCipher.TAG_BYTES
    }
}
