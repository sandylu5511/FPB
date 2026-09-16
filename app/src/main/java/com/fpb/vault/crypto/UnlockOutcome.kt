package com.fpb.vault.crypto

/**
 * 解锁结果。
 *
 * 三种情况（真密码 / 假密码 / 密码错误）在 [VaultKeyring.unlock] 内部
 * 走的是同一条计算路径，耗时一致，调用方不需要（也不应该）为此做额外处理。
 */
sealed class UnlockOutcome {

    /** 解锁成功。[slot] 指明是哪把钥匙打开的，[dek] 是对应的数据密钥。 */
    class Unlocked internal constructor(
        val slot: KeySlot,
        val dek: VaultDek,
    ) : UnlockOutcome() {

        val domain: VaultDomain get() = dek.domain

        /** 是否进入了诱饵库。为 true 时上层必须禁用"改密码""换恢复码"等会污染真库密钥的操作。 */
        val isDecoy: Boolean get() = dek.domain == VaultDomain.DECOY

        /** 取回 DEK 所有权；调用方负责 close。 */
        fun takeDek(): VaultDek = dek
    }

    /** 密码错误。注意：也包含"密文被篡改导致认证失败"的情况，两者对外不可区分是有意为之。 */
    data object Rejected : UnlockOutcome()

    val isUnlocked: Boolean get() = this is Unlocked
}
