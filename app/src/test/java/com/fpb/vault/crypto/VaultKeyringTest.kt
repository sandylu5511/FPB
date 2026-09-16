package com.fpb.vault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VaultKeyring] 的安全属性测试。
 *
 * 这里验证的不是"功能能不能跑"，而是**几件必须成立的安全事实**：
 * 错误密码打不开、诱饵库与真库物理隔离、改密码不需要重加密内容、
 * 恢复码能兜底、以及攻击者无法从文件结构反推用户配置。
 */
class VaultKeyringTest {

    private val fast = KdfParams.fast()
    private val password = "correct horse battery".toCharArray()
    private val decoyPassword = "decoy pass 123".toCharArray()

    private fun newVault(decoy: CharArray? = null): CreationResult =
        VaultKeyring.create(primaryPassword = password, decoyPassword = decoy, params = fast)

    // ==================== 基本解锁 ====================

    @Test
    fun `主密码解锁得到真库 DEK`() {
        val created = newVault()
        val first = created.keyring.unlock(password).unlockedOrFail()
        val second = created.keyring.unlock(password).unlockedOrFail()
        try {
            assertEquals(KeySlot.PRIMARY, first.slot)
            assertEquals(VaultDomain.REAL, first.domain)
            assertFalse(first.isDecoy)
            // 两次独立解锁必须得到同一把 DEK，否则已加密的内容第二次就打不开了
            assertArrayEquals(first.dek.expose(), second.dek.expose())
            // 且与创建时返回的那把一致
            assertArrayEquals(created.primaryDek.expose(), first.dek.expose())
        } finally {
            first.dek.close()
            second.dek.close()
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `错误密码被拒绝`() {
        val created = newVault()
        try {
            created.keyring.unlock("wrong password".toCharArray())
                .assertRejected("错误密码竟然解锁成功了")
            created.keyring.unlock(CharArray(0))
                .assertRejected("空密码应被拒绝")
            // 差一个字符也不行
            created.keyring.unlock("correct horse batter".toCharArray())
                .assertRejected("差一个字符的密码竟然通过了")
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 诱饵库隔离 ====================

    @Test
    fun `假密码进入诱饵库且 DEK 与真库不同`() {
        val created = newVault(decoyPassword)
        val real = created.keyring.unlock(password).unlockedOrFail()
        val decoy = created.keyring.unlock(decoyPassword).unlockedOrFail()
        try {
            assertFalse("主密码不应进入诱饵库", real.isDecoy)
            assertTrue("假密码应进入诱饵库", decoy.isDecoy)
            assertEquals(KeySlot.DECOY, decoy.slot)
            assertEquals(VaultDomain.DECOY, decoy.domain)
            assertFalse(
                "诱饵库 DEK 与真库相同 —— 两个库没有真正隔离",
                real.dek.expose().contentEquals(decoy.dek.expose()),
            )
        } finally {
            real.dek.close()
            decoy.dek.close()
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `用假密码解锁后无法解开真库内容`() {
        val created = newVault(decoyPassword)
        try {
            // 用真库 DEK 加密一条内容
            val aad = Aad.entryField("entry-1", "body")
            val sealed = AeadCipher.seal(created.primaryDek, "机密内容".toByteArray(), aad)

            val decoy = created.keyring.unlock(decoyPassword).unlockedOrFail()
            try {
                assertNull(
                    "诱饵库的 DEK 竟然解开了真库的内容 —— 两个库没有真正隔离",
                    AeadCipher.open(decoy.dek, sealed.nonce, sealed.ciphertext, aad),
                )
            } finally {
                decoy.dek.close()
            }

            // 真库 DEK 则能正常解开
            val real = created.keyring.unlock(password).unlockedOrFail()
            try {
                assertArrayEquals(
                    "机密内容".toByteArray(),
                    AeadCipher.open(real.dek, sealed.nonce, sealed.ciphertext, aad),
                )
            } finally {
                real.dek.close()
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `拒绝把诱饵库 DEK 写进主密码槽`() {
        val created = newVault(decoyPassword)
        try {
            // 这是最危险的一类误用：若真的写入成功，用户之后"输主密码会进入诱饵库"，
            // 以为在记私密内容，实际全写进了准备给攻击者看的库。
            assertRejectsIllegalArgument("诱饵库 DEK 必须被拒绝写入主密码槽") {
                created.keyring.rewrapPrimary("new password".toCharArray(), created.decoyDek!!)
            }
            assertRejectsIllegalArgument("真库 DEK 必须被拒绝写入诱饵槽") {
                created.keyring.withDecoy("new decoy pw".toCharArray(), created.primaryDek)
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `未启用假密码时诱饵槽依然存在但无法打开`() {
        val created = newVault(decoy = null)
        try {
            assertTrue(
                "诱饵槽应始终存在，使密钥文件结构与启用假密码时一致",
                created.keyring.hasSlot(KeySlot.DECOY),
            )
            created.keyring.unlock(decoyPassword).assertRejected("占位诱饵槽不应被打开")
            created.keyring.unlock("anything else here".toCharArray()).assertRejected("占位诱饵槽不应被打开")
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `启用与未启用假密码的密钥文件结构完全一致`() {
        val without = newVault(decoy = null)
        val with = newVault(decoyPassword)
        try {
            assertEquals(
                "槽位集合不同，攻击者可从文件结构判断是否启用了诱饵库",
                without.keyring.slots,
                with.keyring.slots,
            )
            for (slot in without.keyring.slots) {
                assertEquals(
                    "槽 $slot 的密文长度不同，攻击者可从长度差异判断配置",
                    without.keyring.wrappedKey(slot)!!.ciphertext.size,
                    with.keyring.wrappedKey(slot)!!.ciphertext.size,
                )
            }
        } finally {
            without.primaryDek.close()
            without.decoyDek?.close()
            with.primaryDek.close()
            with.decoyDek?.close()
        }
    }

    // ==================== 恢复码 ====================

    @Test
    fun `恢复码解出与主密码相同的 DEK`() {
        val created = newVault()
        try {
            val viaPassword = created.keyring.unlock(password).unlockedOrFail()
            val viaRecovery = created.keyring.unlockWithRecoveryCode(created.recoveryCode)
            assertNotNull("恢复码无法解锁", viaRecovery)
            try {
                assertEquals(VaultDomain.REAL, viaRecovery!!.domain)
                assertArrayEquals(
                    "恢复码解出的 DEK 与主密码解出的不一致 —— 恢复码是无效兜底",
                    viaPassword.dek.expose(),
                    viaRecovery.expose(),
                )
            } finally {
                viaPassword.dek.close()
                viaRecovery!!.close()
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `恢复码容错输入仍能解锁`() {
        val created = newVault()
        try {
            val display = RecoveryCode.formatForDisplay(created.recoveryCode)
            val variants = listOf(
                display,
                display.lowercase(),
                "  $display  ",
                display.replace("-", ""),
            )
            for (variant in variants) {
                val dek = created.keyring.unlockWithRecoveryCode(variant)
                assertNotNull("恢复码变体无法解锁：$variant", dek)
                dek!!.close()
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `恢复码不能解锁诱饵库`() {
        val created = newVault(decoyPassword)
        try {
            val dek = created.keyring.unlockWithRecoveryCode(created.recoveryCode)
            assertNotNull(dek)
            try {
                assertFalse(
                    "恢复码解出的应是真库 DEK，不能是诱饵库的",
                    dek!!.expose().contentEquals(created.decoyDek!!.expose()),
                )
                assertArrayEquals(created.primaryDek.expose(), dek.expose())
            } finally {
                dek!!.close()
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `错误的恢复码返回 null`() {
        val created = newVault()
        try {
            assertNull(created.keyring.unlockWithRecoveryCode(RecoveryCode.generate()))
            assertNull(created.keyring.unlockWithRecoveryCode("not a recovery code"))
            assertNull(created.keyring.unlockWithRecoveryCode(""))
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 改主密码 ====================

    @Test
    fun `改主密码后旧密码失效新密码可用且 DEK 不变`() {
        val created = newVault()
        val dek = created.primaryDek
        try {
            // 改密码前先用 DEK 加密一段内容，改完后必须仍能解开
            val aad = Aad.entryField("entry-9", "body")
            val payload = "改密码前写入的内容".toByteArray()
            val sealed = AeadCipher.seal(dek, payload, aad)

            val updated = created.keyring.rewrapPrimary("brand new password".toCharArray(), dek)

            // 必须用 updated（新密钥环）验证旧密码失效。
            // rewrapPrimary 返回的是新实例，旧实例的包裹并没有被改动，
            // 拿旧实例去验证会得到"旧密码仍然可用"的假结论。
            updated.unlock(password)
                .assertRejected("旧密码在改密码后仍然可用")

            val viaNew = updated.unlock("brand new password".toCharArray()).unlockedOrFail()
            try {
                assertArrayEquals("新密码解出的 DEK 变了", dek.expose(), viaNew.dek.expose())
                assertArrayEquals(
                    "改密码后原有内容解不开了 —— 说明 DEK 被换掉了，这不该发生",
                    payload,
                    AeadCipher.open(viaNew.dek, sealed.nonce, sealed.ciphertext, aad),
                )
            } finally {
                viaNew.dek.close()
            }
        } finally {
            dek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `改主密码后恢复码依然可用`() {
        val created = newVault()
        try {
            val updated = created.keyring.rewrapPrimary(
                "another password".toCharArray(),
                created.primaryDek,
            )
            assertTrue("改密码不应移除恢复码槽", updated.hasSlot(KeySlot.RECOVERY))

            val viaRecovery = updated.unlockWithRecoveryCode(created.recoveryCode)
            assertNotNull("改密码让恢复码失效了 —— salt 处理有问题", viaRecovery)
            try {
                assertArrayEquals(created.primaryDek.expose(), viaRecovery!!.expose())
            } finally {
                viaRecovery!!.close()
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `改主密码后假密码不受影响`() {
        val created = newVault(decoyPassword)
        try {
            val updated = created.keyring.rewrapPrimary("yet another pw".toCharArray(), created.primaryDek)
            val decoy = updated.unlock(decoyPassword).unlockedOrFail()
            try {
                assertTrue(decoy.isDecoy)
            } finally {
                decoy.dek.close()
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 恢复码轮换与槽位管理 ====================

    @Test
    fun `换恢复码后旧恢复码失效新恢复码可用`() {
        val created = newVault()
        try {
            val oldCode = created.recoveryCode
            val newCode = RecoveryCode.generate()
            val updated = created.keyring.rewrapRecovery(newCode, created.primaryDek)

            assertNull("旧恢复码在轮换后仍可用", updated.unlockWithRecoveryCode(oldCode))

            val dek = updated.unlockWithRecoveryCode(newCode)
            assertNotNull("新恢复码不可用", dek)
            try {
                assertArrayEquals(created.primaryDek.expose(), dek!!.expose())
            } finally {
                dek!!.close()
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `主密码槽不可移除`() {
        val created = newVault()
        try {
            assertRejectsIllegalArgument("主密码槽被移除了，用户将永久无法入库") {
                created.keyring.withoutSlot(KeySlot.PRIMARY)
            }
            // 其他槽可以移除：这是"关闭生物识别"这类操作的实现方式
            assertEquals(
                setOf(KeySlot.PRIMARY, KeySlot.RECOVERY, KeySlot.DECOY),
                created.keyring.withoutSlot(KeySlot.BIOMETRIC).slots,
            )
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 外部 KEK（M4 生物识别接口） ====================

    @Test
    fun `外部 KEK 可以包裹并解出 DEK`() {
        val created = newVault()
        try {
            val hardwareKek = SecureBytes.random(AeadCipher.KEY_BYTES)
            val withBiometric = created.keyring.withExternalWrap(
                KeySlot.BIOMETRIC,
                hardwareKek,
                created.primaryDek,
            )

            val dek = withBiometric.unwrapWithExternalKek(KeySlot.BIOMETRIC, hardwareKek)
            assertNotNull("外部 KEK 无法解出 DEK", dek)
            try {
                assertArrayEquals(created.primaryDek.expose(), dek!!.expose())
            } finally {
                dek!!.close()
            }

            // 错的硬件密钥解不开
            assertNull(
                withBiometric.unwrapWithExternalKek(KeySlot.BIOMETRIC, SecureBytes.random(32)),
            )
            // 长度不对的 KEK 直接拒绝
            assertNull(withBiometric.unwrapWithExternalKek(KeySlot.BIOMETRIC, ByteArray(16)))
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 密码策略 ====================

    @Test
    fun `过短的主密码被拒绝`() {
        assertRejectsIllegalArgument("7 字符的密码应被拒绝") {
            VaultKeyring.create("1234567".toCharArray(), params = fast)
        }
    }

    @Test
    fun `假密码不能与主密码相同`() {
        assertRejectsIllegalArgument("假密码与主密码相同时应被拒绝") {
            VaultKeyring.create(password, password.copyOf(), params = fast)
        }
    }

    // ==================== 恒定时间 ====================

    @Test
    fun `真密码 假密码 错误密码的解锁耗时处于同一量级`() {
        val created = newVault(decoyPassword)
        try {
            // 预热，避免把 JIT 编译时间算进来
            repeat(3) {
                created.keyring.unlock(password).discard()
                created.keyring.unlock("wrong password".toCharArray()).discard()
                created.keyring.unlock(decoyPassword).discard()
            }

            val real = measureUnlock(created.keyring, password)
            val wrong = measureUnlock(created.keyring, "wrong password".toCharArray())
            val decoy = measureUnlock(created.keyring, decoyPassword)

            val times = listOf(real, wrong, decoy)
            val min = times.min()
            val max = times.max()

            // 阈值放到 2 倍：这个测试只能粗筛掉"明显的短路分支"
            // （例如输入真密码只解 1 次 AES、输入假密码解 2 次），
            // 精确的时序分析不属于单元测试的职责范畴。
            assertTrue(
                "解锁耗时差异过大：真=${real / 1_000_000}ms 错=${wrong / 1_000_000}ms 假=${decoy / 1_000_000}ms。" +
                    "可能存在按密码类型提前返回的分支",
                max < min * 2,
            )
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 生产参数端到端 ====================

    @Test
    fun `标准档参数下完整流程可用`() {
        // 这个用例故意不传参，走生产的默认档位（64 MiB / t3 / p2），
        // 确认发布用的参数组合本身是能跑通的。
        val created = VaultKeyring.create(password)
        try {
            val unlocked = created.keyring.unlock(password).unlockedOrFail()
            try {
                assertArrayEquals(created.primaryDek.expose(), unlocked.dek.expose())
            } finally {
                unlocked.dek.close()
            }

            val viaRecovery = created.keyring.unlockWithRecoveryCode(created.recoveryCode)
            assertNotNull(viaRecovery)
            viaRecovery!!.close()

            val afterChange = created.keyring.rewrapPrimary("brand new password".toCharArray(), created.primaryDek)
            afterChange.unlock(password).assertRejected("旧密码应已失效")
            afterChange.unlock("brand new password".toCharArray()).unlockedOrFail().dek.close()
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 关闭假密码：这是"看起来像没设过"的关键 ====================

    /**
     * 关闭假密码必须做到两件事：旧假密码彻底失效，且**密钥文件的结构与"从未设过假密码"完全一致**。
     *
     * 后者不是洁癖：如果关掉之后 DECOY 槽整个消失，那么拿到 `fpb.key` 的人
     * 量一下文件长度（或者数一下槽位数）就能判断"这台设备设过/没设过第二个密码"，
     * 而"要不要逼问第二个密码"这个决定立刻就有了答案。
     */
    @Test
    fun `关闭假密码后旧假密码解不开、但文件结构不变`() {
        val withDecoy = newVault(decoyPassword)
        val never = newVault()
        try {
            val closed = withDecoy.keyring.withoutDecoy()

            // 1) 旧假密码不再能进任何库
            closed.unlock(decoyPassword).assertRejected("关闭假密码后，旧假密码不应再能进库")
            // 2) 主密码不受影响
            closed.unlock(password).unlockedOrFail().dek.close()
            // 3) 槽位集合不变 —— 文件里看起来仍然有两把钥匙
            assertEquals(withDecoy.keyring.slots, closed.slots)
            assertEquals(setOf(KeySlot.PRIMARY, KeySlot.RECOVERY, KeySlot.DECOY), closed.slots)
            // 4) 与"从未设过假密码"的库长度完全一致
            assertEquals(
                "关闭假密码后的密钥文件长度必须与从未设过假密码的一致",
                VaultKeyFileCodec.encode(never.keyring).size,
                VaultKeyFileCodec.encode(closed).size,
            )
            // 5) 诱饵槽本身的密文长度也要一致
            assertEquals(
                never.keyring.wrappedKey(KeySlot.DECOY)!!.ciphertext.size,
                closed.wrappedKey(KeySlot.DECOY)!!.ciphertext.size,
            )
            // 6) 关掉之后仍然可以重新开启一个假密码
            val freshDecoyDek = VaultDek.random(VaultDomain.DECOY)
            try {
                val reopened = closed.withDecoy(decoyPassword, freshDecoyDek)
                reopened.unlock(decoyPassword).unlockedOrFail().dek.close()
            } finally {
                freshDecoyDek.close()
            }
        } finally {
            withDecoy.primaryDek.close()
            withDecoy.decoyDek?.close()
            never.primaryDek.close()
            never.decoyDek?.close()
        }
    }

    /**
     * 在诱饵库里改密码走的必须是 [VaultKeyring.rewrapDecoy]，它只能动诱饵槽。
     *
     * 若错走 `rewrapPrimary`，用户看到的是"改成功了"，实际发生的是
     * 真库的钥匙被换成了一把新密码，而他下次输真密码会掉进诱饵库。
     */
    @Test
    fun `重设假密码只动诱饵槽，真库槽位字节不变`() {
        val created = newVault(decoyPassword)
        try {
            val newDecoy = "another decoy 456".toCharArray()
            val rotated = created.keyring.rewrapDecoy(newDecoy, created.decoyDek!!)

            assertTrue(
                "改假密码不应触碰真库槽位",
                created.keyring.wrappedKey(KeySlot.PRIMARY)!!
                    .matches(rotated.wrappedKey(KeySlot.PRIMARY)!!),
            )
            assertTrue(
                "改假密码不应触碰恢复码槽位",
                created.keyring.wrappedKey(KeySlot.RECOVERY)!!
                    .matches(rotated.wrappedKey(KeySlot.RECOVERY)!!),
            )
            rotated.unlock(decoyPassword).assertRejected("旧假密码应失效")
            rotated.unlock(newDecoy).unlockedOrFail().dek.close()
            rotated.unlock(password).unlockedOrFail().dek.close()
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 辅助 ====================

    private fun UnlockOutcome.discard() {
        (this as? UnlockOutcome.Unlocked)?.dek?.close()
    }

    private fun measureUnlock(keyring: VaultKeyring, input: CharArray): Long {
        val start = System.nanoTime()
        val outcome = keyring.unlock(input)
        val elapsed = System.nanoTime() - start
        outcome.discard()
        return elapsed
    }
}
