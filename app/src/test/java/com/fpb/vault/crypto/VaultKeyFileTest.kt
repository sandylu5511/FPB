package com.fpb.vault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultKeyFileTest {

    private val fast = KdfParams.fast()
    private val password = "correct horse battery".toCharArray()
    private val decoyPassword = "decoy pass 123".toCharArray()

    private fun newVault(decoy: CharArray? = null) =
        VaultKeyring.create(password, decoy, fast)

    private fun assertDecodeFails(message: String, bytes: ByteArray) {
        var thrown = false
        try {
            VaultKeyFileCodec.decode(bytes)
        } catch (e: VaultKeyFileException) {
            thrown = true
        }
        assertTrue("$message（期望抛出 VaultKeyFileException）", thrown)
    }

    @Test
    fun `编码后解码可以正常解锁`() {
        val created = newVault(decoyPassword)
        try {
            val bytes = VaultKeyFileCodec.encode(created.keyring)
            val restored = VaultKeyFileCodec.decode(bytes)

            assertEquals(created.keyring.slots, restored.slots)
            assertEquals(created.keyring.params, restored.params)

            val unlocked = restored.unlock(password).unlockedOrFail()
            try {
                assertArrayEquals(created.primaryDek.expose(), unlocked.dek.expose())
            } finally {
                unlocked.dek.close()
            }

            val decoy = restored.unlock(decoyPassword).unlockedOrFail()
            try {
                assertArrayEquals(created.decoyDek!!.expose(), decoy.dek.expose())
            } finally {
                decoy.dek.close()
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `KDF 参数被原样保留`() {
        val created = newVault()
        try {
            val restored = VaultKeyFileCodec.decode(VaultKeyFileCodec.encode(created.keyring))
            val original = created.keyring.params
            val roundTripped = restored.params

            assertEquals(original.memoryKiB, roundTripped.memoryKiB)
            assertEquals(original.iterations, roundTripped.iterations)
            assertEquals(original.parallelism, roundTripped.parallelism)
            assertArrayEquals(
                "salt 在往返中丢了，换机导入将无法派生出同一把 KEK",
                original.salt,
                roundTripped.salt,
            )
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `密钥文件中不含任何明文密钥`() {
        val created = newVault(decoyPassword)
        try {
            val bytes = VaultKeyFileCodec.encode(created.keyring)

            assertNull(
                "密钥文件里出现了真库 DEK 的明文",
                indexOfSubArray(bytes, created.primaryDek.expose()),
            )
            assertNull(
                "密钥文件里出现了诱饵库 DEK 的明文",
                indexOfSubArray(bytes, created.decoyDek!!.expose()),
            )
            // 恢复码也不该出现
            assertNull(
                "密钥文件里出现了恢复码明文",
                indexOfSubArray(bytes, created.recoveryCode.toByteArray()),
            )
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `magic 不匹配的文件被拒绝`() {
        val created = newVault()
        try {
            val bytes = VaultKeyFileCodec.encode(created.keyring)
            bytes[0] = 'X'.code.toByte()
            assertDecodeFails("magic 被改坏后应拒绝解码", bytes)
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `版本号不支持时被拒绝`() {
        val created = newVault()
        try {
            val bytes = VaultKeyFileCodec.encode(created.keyring)
            bytes[4] = 99 // schema 版本字段
            assertDecodeFails("未知版本号应被拒绝", bytes)
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `被截断的文件被拒绝`() {
        val created = newVault()
        try {
            val bytes = VaultKeyFileCodec.encode(created.keyring)
            val cuts = listOf(1, 3, 4, 6, 10, 20, bytes.size / 2, bytes.size - 1)
            for (cut in cuts) {
                assertDecodeFails("截断到 $cut 字节应被拒绝", bytes.copyOf(cut))
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `尾部被追加垃圾字节应被拒绝`() {
        val created = newVault()
        try {
            val bytes = VaultKeyFileCodec.encode(created.keyring)
            val padded = bytes + ByteArray(64) { 0x7F }
            // 尾部必须干净：密钥文件会随备份包导入，输入不可信。
            // 多余字节意味着文件被拼接/篡改过、或格式版本错位 —— 静默接受
            // 等于放弃格式漂移的最后一道检出机会。
            // （第二轮回审：本测试原先断言"仍能解码"，把缺陷固化成了预期行为。）
            try {
                VaultKeyFileCodec.decode(padded)
                fail("追加了 64 字节垃圾的密钥文件被接受了")
            } catch (e: VaultKeyFileException) {
                // 预期：明确拒绝
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `主密码槽密文被篡改后密码解锁失败但恢复码仍可用`() {
        val created = newVault()
        try {
            val bytes = VaultKeyFileCodec.encode(created.keyring)
            val target = locateFirstSlotCiphertextEnd(bytes)
            assertNotNull("未能定位主密码槽的密文", target)
            bytes[target!!] = (bytes[target].toInt() xor 0x01).toByte()

            // 解码本身会通过（长度字段都合法），但 GCM 认证应当失败
            val restored = VaultKeyFileCodec.decode(bytes)

            restored.unlock(password)
                .assertRejected("主密码槽密文被篡改，却仍然解锁成功 —— GCM 认证未生效")

            // 恢复码槽没被动过，应当仍然可用：说明篡改只影响被改动的那个槽
            val viaRecovery = restored.unlockWithRecoveryCode(created.recoveryCode)
            assertNotNull("未被篡改的恢复码槽也失效了", viaRecovery)
            viaRecovery!!.close()
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `编码结果是确定的_同样输入两次编码长度一致`() {
        val created = newVault(decoyPassword)
        try {
            val a = VaultKeyFileCodec.encode(created.keyring)
            val b = VaultKeyFileCodec.encode(created.keyring)
            assertArrayEquals("同一密钥环两次编码结果应完全一致", a, b)
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    @Test
    fun `启用与未启用假密码时密钥文件长度一致`() {
        val without = newVault(decoy = null)
        val with = newVault(decoyPassword)
        try {
            assertEquals(
                "文件长度不同，攻击者可从文件大小判断是否启用了诱饵库",
                VaultKeyFileCodec.encode(without.keyring).size,
                VaultKeyFileCodec.encode(with.keyring).size,
            )
        } finally {
            without.primaryDek.close()
            without.decoyDek?.close()
            with.primaryDek.close()
            with.decoyDek?.close()
        }
    }

    @Test
    fun `缺少主密码槽的文件被拒绝`() {
        val created = newVault()
        try {
            // 手工破坏槽位编号：把第一个槽（PRIMARY）改成未知编号 99
            val bytes = VaultKeyFileCodec.encode(created.keyring)
            val slotOffset = locateFirstSlotOffset(bytes)
            assertNotNull("未能定位第一个槽位字段", slotOffset)
            bytes[slotOffset!!] = 99
            assertDecodeFails("未知槽位编号应被拒绝", bytes)
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 辅助 ====================

    /**
     * 定位第一个槽位编号字段的偏移。
     * 固定头长度 = magic4 + schema1 + kdfId1 + flags2 + 3×int4 + saltLen1 + salt32 + wrapCount1 = 54
     */
    private fun locateFirstSlotOffset(bytes: ByteArray): Int? {
        val fixedOffset = 4 + 1 + 1 + 2 + 4 + 4 + 4 + 1 + KdfParams.SALT_BYTES + 1
        return if (bytes.size > fixedOffset) fixedOffset else null
    }

    /** 定位第一个槽（按 id 排序即 PRIMARY）密文的最后一个字节偏移。 */
    private fun locateFirstSlotCiphertextEnd(bytes: ByteArray): Int? {
        val slotOffset = locateFirstSlotOffset(bytes) ?: return null
        val nonceLength = bytes[slotOffset + 1].toInt()
        val lengthHi = bytes[slotOffset + 2 + nonceLength].toInt() and 0xFF
        val lengthLo = bytes[slotOffset + 3 + nonceLength].toInt() and 0xFF
        val ciphertextLength = (lengthHi shl 8) or lengthLo
        val end = slotOffset + 4 + nonceLength + ciphertextLength - 1
        return if (end < bytes.size) end else null
    }

    private fun indexOfSubArray(haystack: ByteArray, needle: ByteArray): Int? {
        if (needle.isEmpty() || needle.size > haystack.size) return null
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }
}
