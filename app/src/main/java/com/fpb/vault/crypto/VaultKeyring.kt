package com.fpb.vault.crypto

/**
 * 密钥环：一把 DEK，被多把 KEK 各包裹一份，分槽存放。
 *
 * ## 为什么是"包裹"而不是"派生"
 *
 * 内容加密只认一把 DEK。所有解锁方式（主密码 / 恢复码 / 假密码 / 生物识别）
 * 都不直接当密钥用，而是先经 Argon2id 派生出一把 KEK，再用这把 KEK
 * 把 DEK 加密（"包裹"）后存进对应的槽。
 *
 * 这样带来两个关键收益：
 * - **改主密码是瞬时操作**：只需重新包裹主密码槽那 48 字节，不必解密再重加密任何内容
 * - **忘记密码不是死路**：恢复码槽里躺着同一把 DEK 的另一个副本
 *
 * ## 危险用法的防护
 *
 * 一把"真库 DEK"和一把"诱饵 DEK"是两串 32 字节随机数，密码学上无法区分。
 * 因此写入槽位时用 [VaultDomain] 做运行时校验：
 * 诱饵 DEK 永远写不进主密码槽 —— 否则用户会"输着真密码进假库"。
 */
class VaultKeyring private constructor(
    val params: KdfParams,
    private val wraps: Map<KeySlot, WrappedKey>,
) {

    val slots: Set<KeySlot> get() = wraps.keys

    fun wrappedKey(slot: KeySlot): WrappedKey? = wraps[slot]

    fun hasSlot(slot: KeySlot): Boolean = wraps.containsKey(slot)

    /** 该槽对应的 DEK 属于哪个域。 */
    fun domainOf(slot: KeySlot): VaultDomain = domainOfSlot(slot)

    // ==================== 解锁 ====================

    /**
     * 用密码解锁。**耗时与密码对错无关**。
     *
     * 实现上刻意避免短路：先把主密码槽和诱饵槽**都**尝试解包，再回头判断结果。
     * 若写成 `primary ?: decoy` 这种短路形式，输入真密码时只解一次 AES、
     * 输入假密码时解两次，耗时差异虽小却是可测的 —— 攻击者能据此判断
     * "这台设备设了假密码"，进而决定要不要费劲逼问第二个密码。
     *
     * 代价是每次解锁多一次 32 字节的 AES-GCM 解密（微秒级，可忽略）。
     */
    fun unlock(password: CharArray): UnlockOutcome {
        if (password.isEmpty()) return UnlockOutcome.Rejected

        val kek = Argon2Kdf.derive(password, params)
        try {
            // 两次解包都会执行，不做短路 —— 见上面的说明
            val primary = unwrap(KeySlot.PRIMARY, kek, VaultDomain.REAL)
            val decoy = unwrap(KeySlot.DECOY, kek, VaultDomain.DECOY)

            return when {
                primary != null -> {
                    decoy?.close()
                    UnlockOutcome.Unlocked(KeySlot.PRIMARY, primary)
                }

                decoy != null -> UnlockOutcome.Unlocked(KeySlot.DECOY, decoy)

                else -> UnlockOutcome.Rejected
            }
        } finally {
            kek.close()
        }
    }

    /**
     * 用恢复码解锁真库。
     *
     * @param code 用户输入，可带连字符/空格/小写，也可含抄错的 O、I、L —— 会自动纠正。
     * @return 真库 DEK；恢复码不合法或槽已失效时返回 null。
     */
    fun unlockWithRecoveryCode(code: String): VaultDek? {
        val canonical = RecoveryCode.canonicalize(code) ?: return null
        val kek = Argon2Kdf.derive(canonical.toCharArray(), params)
        try {
            return unwrap(KeySlot.RECOVERY, kek, VaultDomain.REAL)
        } finally {
            kek.close()
        }
    }

    /**
     * 用一把外部 KEK 解包（M4 生物识别：KEK 来自 Android Keystore 的硬件密钥，
     * 不经过 Argon2，因为硬件本身已经保证了"必须验指纹才能取出"）。
     */
    fun unwrapWithExternalKek(slot: KeySlot, rawKek: ByteArray): VaultDek? {
        if (rawKek.size != AeadCipher.KEY_BYTES) return null
        val kek = SecureBytes.of(rawKek)
        try {
            return unwrap(slot, kek, domainOf(slot))
        } finally {
            kek.close()
        }
    }

    // ==================== 维护 ====================

    /**
     * 改主密码。**只重新包裹 PRIMARY 槽，DEK 本身不变**，因此已有内容一行都不用重新加密。
     * 调用方必须已用旧密码解出 [dek]。
     *
     * 关于 salt：这里**刻意不换 salt**。换 salt 会让所有从同一 salt 派生的槽
     * （恢复码槽等）同时失效，而改密码时我们手上并没有恢复码明文，无法重新包裹它们。
     * 不换 salt 也没有安全损失 —— salt 只需唯一、不需保密，
     * 旧密码照样会被新包裹挡在门外。
     */
    fun rewrapPrimary(newPassword: CharArray, dek: VaultDek): VaultKeyring {
        require(newPassword.size >= MIN_PASSWORD_LENGTH) { "新密码至少 $MIN_PASSWORD_LENGTH 个字符" }
        val wrapped = wrapWithPassword(dek, newPassword, params, KeySlot.PRIMARY)
        return VaultKeyring(params, wraps + (KeySlot.PRIMARY to wrapped))
    }

    /** 启用或更换假密码。 */
    fun withDecoy(decoyPassword: CharArray, decoyDek: VaultDek): VaultKeyring {
        require(decoyPassword.size >= MIN_PASSWORD_LENGTH) { "假密码至少 $MIN_PASSWORD_LENGTH 个字符" }
        val wrapped = wrapWithPassword(decoyDek, decoyPassword, params, KeySlot.DECOY)
        return VaultKeyring(params, wraps + (KeySlot.DECOY to wrapped))
    }

    /**
     * 修改诱饵库自身的密码（在诱饵库里"修改密码"时走这条路）。
     *
     * 真库与诱饵库的密码各自独立：改一个绝不影响另一个，
     * 因为两边改的都只是自己那个槽里的 48 字节包裹。
     */
    fun rewrapDecoy(newPassword: CharArray, dek: VaultDek): VaultKeyring {
        require(newPassword.size >= MIN_PASSWORD_LENGTH) { "新密码至少 $MIN_PASSWORD_LENGTH 个字符" }
        val wrapped = wrapWithPassword(dek, newPassword, params, KeySlot.DECOY)
        return VaultKeyring(params, wraps + (KeySlot.DECOY to wrapped))
    }

    /**
     * 关闭诱饵库。
     *
     * **不是删掉 DECOY 槽，而是换回一个解不开的占位包裹。**
     * 删槽会让密钥文件在"有假密码 / 没假密码"两种情况下结构不同 ——
     * 拿到文件的人一眼就能看出这台设备有没有设第二个密码，
     * 于是"要不要逼问第二个密码"这件事立刻有了答案。
     * 占位包裹让两种情况在字节层面完全对称（见 [placeholderDecoyWrap]）。
     */
    fun withoutDecoy(): VaultKeyring =
        VaultKeyring(params, wraps + (KeySlot.DECOY to placeholderDecoyWrap()))

    /** 用新恢复码替换恢复码槽，旧恢复码随之失效。 */
    fun rewrapRecovery(newCode: String, dek: VaultDek): VaultKeyring {
        requireDomainMatches(KeySlot.RECOVERY, dek)
        val canonical = RecoveryCode.canonicalize(newCode)
            ?: throw IllegalArgumentException("恢复码格式不合法")
        return VaultKeyring(params, wraps + (KeySlot.RECOVERY to wrapWithRecoveryCode(dek, canonical, params)))
    }

    /**
     * 用外部 KEK 写入一个槽（M4 生物识别用）。
     *
     * 域校验在这里和在密码路径上一样不可省。外部 KEK 走的是 Keystore 里的硬件密钥，
     * 但**密钥来自哪里与"它包裹的是哪个库的 DEK"是两件独立的事**：
     * 少了这道校验，一次传参错误就能把诱饵 DEK 写进主密码槽，
     * 于是用户"输入真实主密码却进了诱饵库"，把该藏的内容写进准备给人看的库。
     */
    fun withExternalWrap(slot: KeySlot, rawKek: ByteArray, dek: VaultDek): VaultKeyring {
        require(rawKek.size == AeadCipher.KEY_BYTES) { "外部 KEK 必须为 ${AeadCipher.KEY_BYTES} 字节" }
        requireDomainMatches(slot, dek)
        val kek = SecureBytes.of(rawKek)
        try {
            return VaultKeyring(params, wraps + (slot to wrapDek(dek, kek, slot)))
        } finally {
            kek.close()
        }
    }

    /** 移除某个槽（例如关闭生物识别）。主密码槽不可移除，否则将永久失去入库能力。 */
    fun withoutSlot(slot: KeySlot): VaultKeyring {
        require(slot != KeySlot.PRIMARY) { "主密码槽不可移除" }
        return VaultKeyring(params, wraps - slot)
    }

    // ==================== 内部 ====================

    private fun unwrap(slot: KeySlot, kek: SecureBytes, expected: VaultDomain): VaultDek? {
        val wrapped = wraps[slot] ?: return null
        val plain = AeadCipher.open(kek, wrapped.nonce, wrapped.ciphertext, Aad.dekWrap(slot))
            ?: return null
        return VaultDek(SecureBytes.takeOwnership(plain), expected)
    }

    companion object {

        /** 主密码最短长度。太短的密码会让 Argon2 的保护形同虚设。 */
        const val MIN_PASSWORD_LENGTH = 8

        /**
         * 从已落盘的槽位还原密钥环（未解锁状态）。
         *
         * 仅供持久化层 [VaultKeyFileCodec] 调用 —— 它已经做过完整性校验，
         * 绕过了 [create] 的密码强度检查，因为此时根本没有密码可检查。
         */
        internal fun fromPersisted(
            params: KdfParams,
            wraps: Map<KeySlot, WrappedKey>,
        ): VaultKeyring = VaultKeyring(params, wraps)

        /**
         * 创建全新的密钥环。
         *
         * @param primaryPassword 主密码
         * @param decoyPassword 假密码；传 null 表示不启用诱饵库。启用后输它进入一个独立的空库。
         * @param params KDF 参数，默认标准档。测试传 [KdfParams.fast] 以缩短用例耗时。
         */
        fun create(
            primaryPassword: CharArray,
            decoyPassword: CharArray? = null,
            params: KdfParams = KdfParams.standard(),
        ): CreationResult {
            require(primaryPassword.size >= MIN_PASSWORD_LENGTH) {
                "主密码至少 $MIN_PASSWORD_LENGTH 个字符"
            }
            if (decoyPassword != null) {
                require(decoyPassword.size >= MIN_PASSWORD_LENGTH) {
                    "假密码至少 $MIN_PASSWORD_LENGTH 个字符"
                }
                require(!primaryPassword.contentEquals(decoyPassword)) {
                    "假密码不能与主密码相同，否则将无法区分进入哪个库"
                }
            }

            val recoveryCode = RecoveryCode.generate()
            val wraps = LinkedHashMap<KeySlot, WrappedKey>()

            val primaryDek = VaultDek.random(VaultDomain.REAL)
            var decoyDek: VaultDek? = null

            try {
                wraps[KeySlot.PRIMARY] =
                    wrapWithPassword(primaryDek, primaryPassword, params, KeySlot.PRIMARY)
                wraps[KeySlot.RECOVERY] =
                    wrapWithRecoveryCode(primaryDek, recoveryCode, params)

                if (decoyPassword != null) {
                    val decoy = VaultDek.random(VaultDomain.DECOY)
                    decoyDek = decoy
                    wraps[KeySlot.DECOY] =
                        wrapWithPassword(decoy, decoyPassword, params, KeySlot.DECOY)
                } else {
                    wraps[KeySlot.DECOY] = placeholderDecoyWrap()
                }

                return CreationResult(
                    keyring = VaultKeyring(params, wraps),
                    primaryDek = primaryDek,
                    decoyDek = decoyDek,
                    recoveryCode = recoveryCode,
                )
            } catch (t: Throwable) {
                // 失败路径上不能把 DEK 留在堆里
                primaryDek.close()
                decoyDek?.close()
                throw t
            }
        }

        private fun wrapWithPassword(
            dek: VaultDek,
            password: CharArray,
            params: KdfParams,
            slot: KeySlot,
        ): WrappedKey {
            // 与外部 KEK 路径共用同一份域校验，避免两条路径的规则漂移
            requireDomainMatches(slot, dek)
            val kek = Argon2Kdf.derive(password, params)
            try {
                return wrapDek(dek, kek, slot)
            } finally {
                kek.close()
            }
        }        private fun wrapWithRecoveryCode(
            dek: VaultDek,
            canonicalCode: String,
            params: KdfParams,
        ): WrappedKey {
            val kek = Argon2Kdf.derive(canonicalCode.toCharArray(), params)
            try {
                return wrapDek(dek, kek, KeySlot.RECOVERY)
            } finally {
                kek.close()
            }
        }

        private fun wrapDek(dek: VaultDek, kek: SecureBytes, slot: KeySlot): WrappedKey {
            val sealed = AeadCipher.seal(kek, dek.expose(), Aad.dekWrap(slot))
            return WrappedKey(slot, sealed.nonce, sealed.ciphertext)
        }

        /**
         * 未启用假密码时，也往 DECOY 槽里放一个"死"包裹。
         *
         * 目的不是加密任何东西，而是让密钥文件在"启用/未启用诱饵库"两种情况下
         * **结构完全一致**（槽位数量、每个包裹的密文长度都一样）。
         * 否则攻击者只看文件结构就能判断这台设备有没有设假密码，
         * 进而知道逼问第二个密码是白费力气还是值得一试。
         *
         * 槽内用一把**随即丢弃**的随机 KEK 包裹一段随机数据，因此永远解不开。
         */
        private fun placeholderDecoyWrap(): WrappedKey {
            val discardedKek = SecureBytes.takeOwnership(SecureBytes.random(AeadCipher.KEY_BYTES))
            val filler = SecureBytes.takeOwnership(SecureBytes.random(AeadCipher.KEY_BYTES))
            try {
                return wrapDek(VaultDek(filler, VaultDomain.DECOY), discardedKek, KeySlot.DECOY)
            } finally {
                discardedKek.close()
                filler.close()
            }
        }
    }
}

/**
 * 槽位与它应当承载的 DEK 域之间的对应关系。
 *
 * 放在文件级而不是类里，是为了让**实例方法与伴生对象里的写入路径共用同一份定义**。
 * 这不是形式上的洁癖：域校验最初只写在密码路径上，外部 KEK 那条路径（M4 生物识别）
 * 就漏掉了同一个校验，于是"诱饵 DEK 被写进主密码槽"变成一件可能发生的事。
 * 一处定义、两处调用，才不会第二次漏。
 */
private fun domainOfSlot(slot: KeySlot): VaultDomain =
    if (slot == KeySlot.DECOY) VaultDomain.DECOY else VaultDomain.REAL

/** 校验 DEK 的域与目标槽位匹配，不匹配即拒绝写入。 */
private fun requireDomainMatches(slot: KeySlot, dek: VaultDek) {
    val expected = domainOfSlot(slot)
    require(dek.domain == expected) {
        "DEK 所属域（${dek.domain}）与槽位 $slot 不匹配（应为 $expected），拒绝写入"
    }
}

/**
 * 新建保险库的结果。
 *
 * [primaryDek] / [decoyDek] 的所有权已转移给调用方，用完必须 close。
 */class CreationResult internal constructor(
    val keyring: VaultKeyring,
    val primaryDek: VaultDek,
    val decoyDek: VaultDek?,
    /**
     * 恢复码，**规范形式**（48 字符，无分隔符）。
     * 展示给用户时用 [RecoveryCode.formatForDisplay] 加上连字符。
     */
    val recoveryCode: String,
) {
    val hasDecoy: Boolean get() = decoyDek != null
}
