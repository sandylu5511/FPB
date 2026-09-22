package com.fpb.vault.audit

import com.fpb.vault.crypto.AeadCipher
import com.fpb.vault.crypto.KdfParams
import com.fpb.vault.crypto.RecoveryCode
import com.fpb.vault.crypto.scrubbedAesKeyCount
import com.fpb.vault.crypto.UnlockOutcome
import com.fpb.vault.crypto.VaultKeyring
import com.fpb.vault.crypto.unlockedOrFail
import com.fpb.vault.crypto.wipe
import com.fpb.vault.crypto.wiping
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存里的明文凭据 —— 第八轮审核（安全加固四条建议的落地）。
 *
 * 这一份查的不是"密码学对不对"，而是**密钥与凭据在内存里活多久**。
 * 这类缺陷有个共同点：**全都不影响任何功能**。加解密结果照样正确、
 * 解锁照样成功，所以任何"跑一遍看看"的验证都会漏掉它们 ——
 * 只能靠对"用完必须清掉"这条契约的显式断言。
 *
 * 覆盖两组：
 * 1. 用户凭据（主密码 / 假密码 / 恢复码）转成 `CharArray` 之后是否被清零；
 * 2. 喂给 `Cipher` 的那份 DEK 副本在用完之后是否被清零。
 *
 * ## 对照实验（怎么确认这些断言不是在自说自话）
 *
 * 把生产代码里对应的一行去掉，本文件应当有**具体某条**变红：
 *
 * | 去掉什么 | 会红的用例 |
 * |---|---|
 * | `AeadCipher.seal` 的 `finally { secret.destroy() }` | `seal 之后交给 Cipher 的密钥副本被清零` |
 * | `AeadCipher.open` 的 `finally { secret.destroy() }` | `open 之后……` 与 `open 解密失败时……` |
 * | `SecretChars.wiping` 的 `finally { wipe() }` | `block 抛异常时同样清零`（等 5 条） |
 * | `TransientSecretKey.destroy` 里的 `material.fill(0)` | `销毁之后副本是零_复用是显式失败`（在 `TransientSecretKeyTest`） |
 *
 * 都只断言"结果正确"的话，上表四行全部不会变红 —— 那正是这个文件存在的理由。
 *
 * **这张表是跑出来的，不是推出来的**（`tools/_audit8_control_experiment.py`）。
 * 第一版探针把计数写在 `AeadCipher` 的 `finally` 里（`destroy()` 旁边），
 * 跑对照实验时第一行**一条都没红** —— 因为计数记的是"代码走到了这一行"，
 * 而不是"密钥真的被清零了"。把计数挪进 `TransientSecretKey.destroy` 之后才真正生效。
 * 这也是"必须做对照实验"这件事本身的价值：不做，就会把一条假绿当证据交付。
 */
class Audit8RegressionTest {

    // ==================== 一、用户凭据字符数组的清零 ====================

    @Test
    fun `字符数组在用完之后被清零_且返回值照常带出来`() {
        val chars = "correct horse battery".toCharArray()

        val result = chars.wiping { it.concatToString() }

        assertEquals("wiping 必须把 block 的返回值原样交回", "correct horse battery", result)
        assertTrue("退出 wiping 之后数组应已清零，实际：${chars.toList()}", isAllZero(chars))
    }

    /**
     * 异常路径才是最容易漏的那条。
     *
     * 只在成功路径上清零，等于**在最需要清零的那条路径上不清零**：
     * 抛异常往往正是"密码派生失败、用户连续尝试"的时刻。
     */
    @Test
    fun `block 抛异常时同样清零`() {
        val chars = "hunter2 hunter2".toCharArray()

        val thrown = runCatching { chars.wiping { error("派生失败") } }

        assertTrue("异常应当继续向外传播，不能被 wiping 吞掉", thrown.isFailure)
        assertTrue("异常路径上数组也必须清零，实际：${chars.toList()}", isAllZero(chars))
    }

    @Test
    fun `清零是幂等的_空数组不会抛异常`() {
        val chars = "x".toCharArray()
        chars.wipe()
        chars.wipe()
        assertTrue(isAllZero(chars))

        CharArray(0).wipe()
        CharArray(0).wiping { it.size }
    }

    /**
     * 清零之后那份数组**不能再解锁**。
     *
     * 这条看着像废话，但它钉住的是"清零确实作用在真正用于派生的那份数据上"：
     * 如果 `unlock` 拿到的其实是另一个副本（例如实现里先做了 `toCharArray()` 拷贝），
     * 那么上面几条测试全绿、而调用方那份明文清不清都无所谓 —— 缺陷照旧。
     */
    @Test
    fun `清零之后的字符数组不能再解锁`() {
        val created = VaultKeyring.create(PASSWORD.toCharArray(), null, KdfParams.fast())
        try {
            val chars = PASSWORD.toCharArray()
            created.keyring.unlock(chars).let { outcome ->
                assertTrue("先用正确密码解锁应成功", outcome is UnlockOutcome.Unlocked)
                (outcome as UnlockOutcome.Unlocked).dek.close()
            }

            chars.wipe()
            val afterWipe = created.keyring.unlock(chars)
            assertTrue("清零之后不该还能解锁，实际：$afterWipe", afterWipe is UnlockOutcome.Rejected)
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    /**
     * 解锁结果不能与密码数组共享存储。
     *
     * 反过来验一次上面那条：解锁拿到 DEK 之后立刻把密码清零，
     * 解出来的 DEK 必须仍然可用。若实现上"DEK 是密码数组的某种视图"，
     * 这条会失败 —— 而现实中它会表现为"偶尔解锁后打不开内容"。
     */
    @Test
    fun `密码清零之后解出来的 DEK 仍然可用`() {
        val created = VaultKeyring.create(PASSWORD.toCharArray(), null, KdfParams.fast())
        try {
            val sealed = created.primaryDek.let {
                AeadCipher.seal(it, PLAINTEXT, AAD)
            }

            val chars = PASSWORD.toCharArray()
            val dek = chars.wiping { created.keyring.unlock(it).unlockedOrFail().dek }
            assertTrue("密码副本此刻应已清零", isAllZero(chars))

            try {
                assertArrayEquals(
                    "密码被清零之后，解出来的 DEK 解不开内容 —— 两者共享了存储",
                    PLAINTEXT,
                    AeadCipher.open(dek, sealed.nonce, sealed.ciphertext, AAD),
                )
            } finally {
                dek.close()
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    /**
     * 恢复码路径里的清理不能把功能一起清掉。
     *
     * `VaultKeyring` 内部对恢复码做了一次 `toCharArray().wiping { … }`
     * （canonical 那个 String 清不掉，清的是派生用的副本）。若哪天把它改成
     * "先清零再派生"，恢复码就会**静默失效** —— 用户唯一的兜底手段没了，
     * 而错误现象只是"恢复码不对"。
     */
    @Test
    fun `恢复码路径的清理不影响功能_两次解锁结果一致`() {
        val created = VaultKeyring.create(PASSWORD.toCharArray(), null, KdfParams.fast())
        try {
            val first = created.keyring.unlockWithRecoveryCode(created.recoveryCode)
            val second = created.keyring.unlockWithRecoveryCode(created.recoveryCode)
            assertTrue("恢复码解锁失败", first != null && second != null)
            try {
                assertArrayEquals(
                    "同一个恢复码两次解出的 DEK 不同 —— 内部清理破坏了派生",
                    first!!.expose(),
                    second!!.expose(),
                )
                assertArrayEquals("恢复码解出的 DEK 与主密码解出的不是同一把", created.primaryDek.expose(), first.expose())
            } finally {
                first?.close()
                second?.close()
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `带连字符与小写的恢复码照常可用_清理没有改变容错规则`() {
        val created = VaultKeyring.create(PASSWORD.toCharArray(), null, KdfParams.fast())
        try {
            val formatted = RecoveryCode.formatForDisplay(created.recoveryCode)
            val dek = created.keyring.unlockWithRecoveryCode(formatted.lowercase())
            assertTrue("容错归一化被内部清理破坏了", dek != null)
            dek?.close()
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 二、喂给 Cipher 的密钥副本 ====================

    // 「封装本身对不对」的用例（自持副本 / 销毁填零 / 幂等 / toString /
    // 计数器分流）已移到 `com.fpb.vault.crypto.TransientSecretKeyTest` ——
    // 那是 AES 与 HMAC 共用的一层，放在本文件里会让"零件坏"与"接线断"混在一起。
    // 下面这几条只问一件事：**生产路径（AeadCipher）有没有真的用上它。**

    /**
     * `seal` 走完之后，那份交给 `Cipher` 的副本必须已经被清零。
     *
     * 这条**没有它就没有任何东西能发现 `finally` 被删掉**：
     * 密文照样正确、用例照样通过。见本文件开头的对照实验表。
     */
    @Test
    fun `seal 之后交给 Cipher 的密钥副本被清零`() {
        val key = ByteArray(AeadCipher.KEY_BYTES) { it.toByte() }
        val before = scrubbedAesKeyCount.get()

        AeadCipher.seal(key, PLAINTEXT, AAD)

        assertEquals("seal 之后没有清零密钥副本", before + 1, scrubbedAesKeyCount.get())
        assertFalse("调用方自己那个数组不该被动过", isAllZero(key))
    }

    @Test
    fun `open 之后交给 Cipher 的密钥副本被清零`() {
        val key = ByteArray(AeadCipher.KEY_BYTES) { it.toByte() }
        val sealed = AeadCipher.seal(key, PLAINTEXT, AAD)
        val before = scrubbedAesKeyCount.get()

        AeadCipher.open(key, sealed.nonce, sealed.ciphertext, AAD)

        assertEquals("open 之后没有清零密钥副本", before + 1, scrubbedAesKeyCount.get())
    }

    /**
     * 解密**失败**（认证标签不匹配 / 密钥不对）时同样要清零。
     *
     * 这条比成功路径重要：失败路径上抛的是 `GeneralSecurityException`，
     * 而 `open` 用 `try/catch` 把它吞成了 `null`。如果 `destroy()` 写在
     * `catch` 之后而不是 `finally` 里，失败路径就会漏掉 —— 而"密钥不对"
     * 恰恰是攻击者/误操作时反复触发的那条路。
     */
    @Test
    fun `open 解密失败时同样清零`() {
        val key = ByteArray(AeadCipher.KEY_BYTES) { it.toByte() }
        val sealed = AeadCipher.seal(key, PLAINTEXT, AAD)
        val wrong = ByteArray(AeadCipher.KEY_BYTES) { (it + 1).toByte() }
        val before = scrubbedAesKeyCount.get()

        assertNull(AeadCipher.open(wrong, sealed.nonce, sealed.ciphertext, AAD))

        assertEquals("解密失败路径漏掉了清零", before + 1, scrubbedAesKeyCount.get())
    }

    @Test
    fun `参数不合法时不会假装清零过一次`() {
        val before = scrubbedAesKeyCount.get()

        // 密钥长度不对：连 Cipher 都不该碰，因此也不该计一次"清理过"
        assertTrue(
            runCatching { AeadCipher.seal(ByteArray(16), PLAINTEXT, AAD) }.isFailure,
        )
        assertNull(AeadCipher.open(ByteArray(16), ByteArray(AeadCipher.NONCE_BYTES), ByteArray(32), AAD))

        assertEquals("提前返回的路径不该被算作清过一次", before, scrubbedAesKeyCount.get())
    }

    // ==================== 三、换实现之后的回归 ====================

    /**
     * 密钥从 `SecretKeySpec` 换成自持副本之后，加解密行为必须一字不变。
     *
     * 这是本次改动的**底线**：内存里的东西变了，但落到磁盘上的格式不能变 ——
     * 否则存量数据会打不开，而那是最严重的一类事故。
     */
    @Test
    fun `换密钥实现之后封解往返与 AAD 校验行为不变`() {
        val key = ByteArray(AeadCipher.KEY_BYTES) { (it * 3).toByte() }
        val sealed = AeadCipher.seal(key, PLAINTEXT, AAD)

        assertEquals("密文长度应是明文 + nonce + 标签", PLAINTEXT.size + AeadCipher.OVERHEAD_BYTES, sealed.nonce.size + sealed.ciphertext.size)
        assertEquals(AeadCipher.NONCE_BYTES, sealed.nonce.size)
        assertArrayEquals(PLAINTEXT, AeadCipher.open(key, sealed.nonce, sealed.ciphertext, AAD))

        // AAD 不匹配必须拒绝 —— 这是"把假密码包裹挪到真密码位置"那条攻击的拦阻点
        assertNull(
            "AAD 不匹配竟然解开了",
            AeadCipher.open(key, sealed.nonce, sealed.ciphertext, "别的上下文".toByteArray()),
        )
        // 密文改一位必须拒绝
        val tampered = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(AeadCipher.open(key, sealed.nonce, tampered, AAD))
        // nonce 长度不对必须拒绝
        assertNull(AeadCipher.open(key, ByteArray(8), sealed.ciphertext, AAD))
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
        val PLAINTEXT = "一段要被加密的内容".toByteArray()
        val AAD = "mixia:audit8:v1".toByteArray()

        fun isAllZero(bytes: CharArray): Boolean = bytes.all { it == '\u0000' }

        fun isAllZero(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }
    }
}
