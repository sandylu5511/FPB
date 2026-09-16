package com.fpb.vault.crypto

/**
 * 数据密钥（DEK）所属的域。
 *
 * 这个类型存在的理由是一个具体的事故场景：
 * 假密码解出的 DEK 若被误当作真库 DEK 写进主密码槽，后果是
 * **"输入主密码却进入诱饵库"** —— 用户以为自己在记私密内容，
 * 实际全写进了准备给攻击者看的库。
 *
 * 两个 DEK 都是 32 字节随机值，在密码学上完全不可区分，
 * 只能靠类型系统在内存中标记，让编译器帮忙挡住这类误用。
 */
enum class VaultDomain {
    /** 真库。主密码、恢复码、生物识别三个槽都属于这个域。 */
    REAL,

    /** 诱饵库。假密码槽专属，与真库完全隔离。 */
    DECOY,
}

/**
 * 一把数据密钥。
 *
 * 与 [SecureBytes] 的区别：DEK 额外携带 [domain] 标签，
 * 使得 [VaultKeyring.rewrapPrimary] 这类操作能在运行时拒绝写错域的密钥。
 *
 * 生命周期由调用方负责：用完必须 [close]。
 */
class VaultDek internal constructor(
    private val bytes: SecureBytes,
    val domain: VaultDomain,
) : AutoCloseable {

    /** 短暂暴露内部字节，用于喂给 Cipher。调用方不得持有引用。 */
    fun expose(): ByteArray = bytes.expose()

    val isValid: Boolean get() = !bytes.isEmpty()

    override fun close() = bytes.close()

    override fun toString(): String = "VaultDek(domain=$domain, usable=$isValid)"

    companion object {
        internal fun random(domain: VaultDomain): VaultDek =
            VaultDek(
                SecureBytes.takeOwnership(SecureBytes.random(AeadCipher.KEY_BYTES)),
                domain,
            )
    }
}
