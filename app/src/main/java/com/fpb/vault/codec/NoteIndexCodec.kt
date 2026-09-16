package com.fpb.vault.codec

import com.fpb.vault.data.RowIds
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * 加密索引清单的编解码器。
 *
 * ## 它解决什么问题
 *
 * 数据库里所有的行都是密文，真库与诱饵库的行**混在同一张表里**（这是刻意的，
 * 见 [com.fpb.vault.session.VaultSession] 的说明）。那么解锁之后，我们怎么知道
 * 哪些行是自己的？
 *
 * 答案不能是"挨个试着解密"：解密失败既可能意味着"这是另一个库的行"，
 * 也可能意味着"这是我们自己的行，但数据损坏了"。两者被混为一谈的后果是
 * **一条损坏的笔记会静默消失** —— 用户不会收到任何提示，只会发现内容不见了。
 * 对一个"数据只在这里"的应用，这是最不能接受的一类故障。
 *
 * 所以每个域额外保存一份**用自己的 DEK 加密的 id 清单**：
 * - 清单里有的 id + 行存在 + 解密成功 → 正常
 * - 清单里有的 id + 行不存在     → 数据缺失，报给用户
 * - 清单里有的 id + 解密失败     → 数据损坏，报给用户
 * - 不在清单里的行               → 属于另一个库，一律不碰
 *
 * ## 格式（v1，大端序）
 *
 * ```
 * magic   4 字节  "MXI1"
 * version 1 字节  = 1
 * count   4 字节
 * ids     count × (4 字节长度 + ASCII 十六进制)
 * ```
 */
object NoteIndexCodec {

    const val SCHEMA_VERSION = 1

    private val MAGIC = byteArrayOf(0x4D, 0x58, 0x49, 0x31) // "MXI1"

    /**
     * 单个库允许的条目数上限。
     *
     * 这个上界**必须在编码和解码两侧同时生效**，而不是只在解码侧检查。
     * 只拦解码的后果是最糟的一种：写入时一路通畅，清单顺利落盘，
     * 但从那一刻起**这份清单再也解不回来** —— 解锁直接判定"清单不可读"并拒绝，
     * 用户的所有数据就此锁死，且没有任何补救途径。
     * 编码侧抛异常虽然也是一次失败，但它发生在写入那一刻、有明确提示、可被处理。
     */
    const val MAX_ENTRIES = 100_000

    fun encode(ids: List<String>): ByteArray {
        require(ids.size <= MAX_ENTRIES) {
            "清单条目数 ${ids.size} 超过上限 $MAX_ENTRIES，拒绝写出无法解读的清单"
        }
        // 与解码侧同源同律：解码会拒绝的形状，编码就不许产出。
        // 这不是对称性洁癖：清单是"哪些行属于本域"的唯一凭证，一旦写出
        // 一份含非法 id 的清单，下一次解锁就判定"清单不可读"，整个库锁死。
        ids.forEach { id ->
            require(RowIds.isValid(id)) { "清单 id 形状非法（应为 32 位十六进制）：$id" }
        }
        val out = ByteArrayOutputStream(8 + ids.size * 36)
        DataOutputStream(out).use { d ->
            d.write(MAGIC)
            d.writeByte(SCHEMA_VERSION)
            d.writeInt(ids.size)
            ids.forEach { id ->
                val bytes = id.toByteArray(StandardCharsets.US_ASCII)
                d.writeInt(bytes.size)
                d.write(bytes)
            }
        }
        return out.toByteArray()
    }

    /**
     * @return 清单中的 id 列表；格式不合规时返回 `null`（**不返回空列表** ——
     *         空列表会让"清单损坏"被当成"这个库是空的"，进而导致下一次写入
     *         用一个只有一条记录的清单覆盖掉真实清单，等于丢掉整个索引）。
     */
    fun decode(bytes: ByteArray): List<String>? = try {
        decodeOrThrow(bytes)
    } catch (e: Exception) {
        null
    }

    private fun decodeOrThrow(bytes: ByteArray): List<String>? {
        val input = DataInputStream(ByteArrayInputStream(bytes))

        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) return null

        if (input.readUnsignedByte() != SCHEMA_VERSION) return null

        val count = input.readInt()
        if (count < 0 || count > MAX_ENTRIES) return null

        val ids = ArrayList<String>(minOf(count, 256))
        repeat(count) {
            val length = input.readInt()
            if (length < 1 || length > 64) throw IllegalArgumentException("id 长度越界: $length")
            val buffer = ByteArray(length)
            input.readFully(buffer)
            val id = String(buffer, StandardCharsets.US_ASCII)
            if (!RowIds.isValid(id)) throw IllegalArgumentException("清单中出现非法 id")
            ids.add(id)
        }

        if (input.available() != 0) return null
        return ids
    }
}
