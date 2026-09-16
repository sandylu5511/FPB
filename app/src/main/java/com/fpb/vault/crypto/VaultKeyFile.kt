package com.fpb.vault.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class VaultKeyFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 密钥文件的二进制编解码。
 *
 * ## 这个文件里有什么、没有什么
 *
 * 只包含：KDF 参数（公开的）、salt（公开的）、以及被 KEK 包裹后的 DEK 密文。
 * **不包含任何明文密钥、也不包含密码或恢复码本身。**
 * 所以这个文件本身是可以放进备份包的 —— 没有密码的人拿到它，等于拿到一块石头。
 *
 * ## 格式（大端）
 *
 * ```
 * magic       4 B   "MXVK"
 * schema      1 B   格式版本
 * kdfId       1 B   1 = Argon2id
 * flags       2 B   保留位
 * memoryKiB   4 B   int
 * iterations  4 B   int
 * parallelism 4 B   int
 * saltLen     1 B
 * salt        n B
 * wrapCount   1 B
 * wraps       每个：[slot 1B][nonceLen 1B][nonce][ctLen 2B][ct]
 * ```
 *
 * 解析对每个长度字段都做了范围校验。校验的必要性不只是防损坏文件：
 * 这个文件将来会从备份包导入，而备份包可能来自不可信来源
 * （用户从网盘下载、别人发过来的），长度字段被伪造会导致巨大的内存分配。
 */
object VaultKeyFileCodec {

    private val MAGIC = byteArrayOf(0x4D, 0x58, 0x56, 0x4B) // "MXVK"
    const val SCHEMA_VERSION = 1
    private const val KDF_ARGON2ID = 1

    private const val MAX_WRAPS = 16

    fun encode(keyring: VaultKeyring): ByteArray {
        val buffer = ByteArrayOutputStream(256)
        DataOutputStream(buffer).use { out ->
            out.write(MAGIC)
            out.writeByte(SCHEMA_VERSION)
            out.writeByte(KDF_ARGON2ID)
            out.writeShort(0) // flags，保留

            val params = keyring.params
            out.writeInt(params.memoryKiB)
            out.writeInt(params.iterations)
            out.writeInt(params.parallelism)
            out.writeByte(params.salt.size)
            out.write(params.salt)

            val slots = keyring.slots.sortedBy { it.id }
            out.writeByte(slots.size)
            for (slot in slots) {
                val wrapped = keyring.wrappedKey(slot)
                    ?: throw VaultKeyFileException("槽位 $slot 声明存在但读不到内容")
                if (wrapped.ciphertext.size != WrappedKey.EXPECTED_CIPHERTEXT_BYTES) {
                    throw VaultKeyFileException("槽位 $slot 的密文长度异常：${wrapped.ciphertext.size}")
                }
                out.writeByte(slot.id)
                out.writeByte(wrapped.nonce.size)
                out.write(wrapped.nonce)
                out.writeShort(wrapped.ciphertext.size)
                out.write(wrapped.ciphertext)
            }
            out.flush()
        }
        return buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): VaultKeyring {
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))

            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) {
                throw VaultKeyFileException("不是FPB密钥文件（magic 不匹配）")
            }

            val schema = input.readUnsignedByte()
            if (schema != SCHEMA_VERSION) {
                throw VaultKeyFileException("密钥文件版本不支持：$schema（本版本支持 $SCHEMA_VERSION）")
            }

            val kdfId = input.readUnsignedByte()
            if (kdfId != KDF_ARGON2ID) {
                throw VaultKeyFileException("未知的 KDF 标识：$kdfId")
            }

            input.readUnsignedShort() // flags

            val memoryKiB = input.readInt()
            val iterations = input.readInt()
            val parallelism = input.readInt()

            val saltLength = input.readUnsignedByte()
            if (saltLength != KdfParams.SALT_BYTES) {
                throw VaultKeyFileException("salt 长度异常：$saltLength")
            }
            val salt = ByteArray(saltLength)
            input.readFully(salt)

            val params = try {
                KdfParams(memoryKiB, iterations, parallelism, salt)
            } catch (e: IllegalArgumentException) {
                throw VaultKeyFileException("KDF 参数非法：${e.message}", e)
            }

            val wrapCount = input.readUnsignedByte()
            if (wrapCount !in 1..MAX_WRAPS) {
                throw VaultKeyFileException("槽位数量异常：$wrapCount")
            }

            val wraps = LinkedHashMap<KeySlot, WrappedKey>(wrapCount)
            repeat(wrapCount) { index ->
                val slotId = input.readUnsignedByte()
                val slot = KeySlot.fromId(slotId)
                    ?: throw VaultKeyFileException("第 $index 个槽位的编号未知：$slotId")

                // 重复槽位必须拒绝，不能"后一个覆盖前一个"。
                // 覆盖会让伪造文件产生一个沉默的后果：文件里明明有两份主密码包裹，
                // 用哪一份却取决于写入顺序 —— 校验通过、解锁失败，而失败原因无从判断。
                if (wraps.containsKey(slot)) {
                    throw VaultKeyFileException("槽位 $slot 在文件中重复出现")
                }

                val nonceLength = input.readUnsignedByte()
                if (nonceLength != AeadCipher.NONCE_BYTES) {
                    throw VaultKeyFileException("第 $index 个槽位的 nonce 长度异常：$nonceLength")
                }
                val nonce = ByteArray(nonceLength)
                input.readFully(nonce)

                // 槽位密文的长度是固定的（32 字节 DEK + 16 字节 GCM 标签），
                // 因此这里做**等值**校验而不是区间校验。
                // 区间校验会放过"长度合法但结构不对"的包裹：它能被解析出来，
                // 却永远解不开，把一次文件损坏伪装成一次"密码错误"。
                val ciphertextLength = input.readUnsignedShort()
                if (ciphertextLength != WrappedKey.EXPECTED_CIPHERTEXT_BYTES) {
                    throw VaultKeyFileException(
                        "第 $index 个槽位的密文长度异常：$ciphertextLength" +
                            "（应为 ${WrappedKey.EXPECTED_CIPHERTEXT_BYTES}）",
                    )
                }
                val ciphertext = ByteArray(ciphertextLength)
                input.readFully(ciphertext)

                wraps[slot] = WrappedKey(slot, nonce, ciphertext)
            }

            if (!wraps.containsKey(KeySlot.PRIMARY)) {
                throw VaultKeyFileException("密钥文件缺少主密码槽，无法解锁")
            }

            // 尾部必须干净。NoteCodec 与 NoteIndexCodec 都有这条检查，唯独这里漏了。
            // 密钥文件将来会随备份包导入，输入不可信 —— 多余字节通常意味着
            // 文件被拼接/截断后补齐过、或写入方与读取方对格式的理解已经不一致。
            // 此时静默接受，等于放弃格式漂移的最后一道检出机会。
            if (input.available() != 0) {
                throw VaultKeyFileException(
                    "密钥文件尾部存在 ${input.available()} 字节多余数据（可能被篡改或格式不匹配）",
                )
            }

            return VaultKeyring.fromPersisted(params, wraps)
        } catch (e: VaultKeyFileException) {
            throw e
        } catch (e: IOException) {
            throw VaultKeyFileException("密钥文件损坏或不完整", e)
        }
    }
}
