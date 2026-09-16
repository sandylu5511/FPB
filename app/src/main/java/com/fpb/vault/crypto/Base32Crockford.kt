package com.fpb.vault.crypto

/**
 * Crockford Base32 编解码。
 *
 * 为什么不用标准 Base32 或 Base64 来做恢复码：
 * - 字母表剔除了 I / L / O / U —— 手抄到纸上、或电话口述时最容易认错的四个字符
 * - 解码时把 O → 0、I / L → 1，用户抄错也能还原出正确的码
 * - 只有大写字母和数字，没有大小写敏感问题
 *
 * 这些特性直接决定恢复码能不能被真的抄对 —— 一个抄不对的恢复码等于没有兜底。
 */
object Base32Crockford {

    /** Crockford 字母表：去掉了 I、L、O、U。 */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c ->
            table[c.code] = index
            // 大小写不敏感：用户把恢复码抄在纸上再敲进来时，
            // 手机键盘很容易打出小写。只认大写会让恢复码在最需要它的时候失效。
            table[c.lowercaseChar().code] = index
        }
        // Crockford 规范定义的容错映射：视觉上易混的字符归一到合法值
        table['o'.code] = 0
        table['O'.code] = 0
        table['i'.code] = 1
        table['I'.code] = 1
        table['l'.code] = 1
        table['L'.code] = 1
    }

    /** 编码为规范大写形式，不含分隔符。 */
    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                sb.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
            // 防止 buffer 随循环无限增长
            buffer = buffer and ((1 shl bitsLeft) - 1)
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }

    /**
     * 解码。非法字符、长度不符、或非规范编码都返回 null。
     *
     * 为什么必须拒绝"非规范编码"：若允许末尾填充位非零，
     * 同一个字节串就能对应多个不同的合法字符串，等于凭空多出可猜测的别名。
     */
    fun decodeOrNull(text: String, expectedBytes: Int? = null): ByteArray? {
        val cleaned = normalize(text) ?: return null
        if (cleaned.isEmpty()) return null

        val out = ByteArray(cleaned.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var written = 0

        for (c in cleaned) {
            val value = DECODE_TABLE[c.code]
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out[written++] = ((buffer shr (bitsLeft - 8)) and 0xFF).toByte()
                bitsLeft -= 8
                buffer = buffer and ((1 shl bitsLeft) - 1)
            }
        }

        // 尾部残留位必须全为 0，否则是非规范编码
        if (bitsLeft > 0 && buffer != 0) return null

        if (expectedBytes != null) {
            if (written != expectedBytes) return null

            // 反查规范化，挡掉"字节数对得上、字符数却多出来"的别名输入。
            //
            // 具体场景：在 48 字符的合法恢复码后面多打一个 `0`。
            // 多出的字符贡献 5 位残留，恰好看上去全为 0，于是上一行的填充位校验放行；
            // 而 30 字节的长度校验也照样满足 —— 两道校验都过，多打的字符被静默吞掉。
            // 结果是同一个恢复码有了无穷多个"能解开"的写法，与"长度不符即拒绝"的契约相矛盾。
            //
            // 判据用"重新编码后是否与原输入逐字符相同"，因为 normalize 已经把输入
            // 归一到规范大写形式，所以这条比较同时也覆盖了字符数是否正确。
            if (!encode(out).contentEquals(cleaned)) return null
        }
        return out
    }

    /**
     * 归一化：去掉分隔符、统一大写、修正易混字符。
     * 含非法字符时返回 null。
     */
    fun normalize(text: String): String? {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '-', ' ', '\t', '\r', '\n', '_' -> continue
            }
            if (c.code >= 128) return null
            val value = DECODE_TABLE[c.code]
            if (value < 0) return null
            sb.append(ALPHABET[value])
        }
        return sb.toString()
    }

    fun isValid(text: String): Boolean = decodeOrNull(text) != null

    /** 校验字符是否在字母表内（不含容错映射）。 */
    fun isCanonical(text: String): Boolean {
        if (text.isEmpty()) return false
        return text.all { c -> c.code < 128 && ALPHABET.indexOf(c) >= 0 }
    }
}
