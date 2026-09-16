package com.fpb.vault.data

import com.fpb.vault.crypto.SecureBytes

/**
 * 随机标识符。
 *
 * 全库的 id 都必须从这里产生，原因是它们会直接出现在：
 * - SQLite 的主键列（明文）
 * - 磁盘上的文件名（明文）
 *
 * 因此它们必须**完全不含信息**：不用时间戳（可排序 → 泄漏创建顺序）、
 * 不用递增序号（泄漏数量增长）、不用标题哈希（可字典比对）。
 * 只用 16 字节系统随机数。
 */
object RowIds {

    private const val BYTES = 16
    private const val HEX_LENGTH = BYTES * 2

    private val SHAPE = Regex("^[0-9a-f]{$HEX_LENGTH}$")

    fun random(): String = SecureBytes.random(BYTES).toHex()

    /**
     * 校验形状。
     *
     * 这不只是格式检查：条目内容经过解密后可能包含指向图片的 blobId，
     * 而那个字符串最终会参与拼接**文件名**。若不校验，一段构造出来的
     * 内容（例如 `../../databases/vault`）就能让写入跑到别的目录去。
     * 加密保证了"内容不可被外部篡改"，但不保证"内容一定是我们写进去的样子"。
     */
    fun isValid(id: String): Boolean = SHAPE.matches(id)
}

/** 字节数组转小写十六进制字符串。 */
internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    val digits = "0123456789abcdef"
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = digits[v ushr 4]
        out[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(out)
}
