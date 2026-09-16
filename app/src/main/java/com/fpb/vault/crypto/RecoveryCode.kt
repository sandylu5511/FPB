package com.fpb.vault.crypto

/**
 * 一次性恢复码。
 *
 * 设计要点（任何一条不满足，恢复码就等于没有兜底）：
 *
 * 1. **熵要够**：30 字节 = 240 位随机。即使不做任何 KDF 拉伸，
 *    纯暴破 2^240 也是不可行的量级；做成 6 位数字那种"恢复码"等于给攻击者开后门。
 *
 * 2. **要能被抄对**：用 Crockford Base32（剔除 I/L/O/U），
 *    且解码时把 O→0、I/L→1 自动纠正。恢复码最常见的失效原因不是被破解，
 *    而是用户抄错、或者抄对了但输入时打错。
 *
 * 3. **不能是密钥本身**：恢复码经 Argon2id 派生后才成为 KEK。
 *    这样即使恢复码被看到，攻击者仍拿不到 DEK 本身。
 *
 * 30 字节恰好编码为 48 字符（240 / 5 = 48，整除，无填充位），
 * 分组为 12 组 × 4 字符，正好对上"12 组恢复码"的形式。
 */
object RecoveryCode {

    const val GROUP_COUNT = 12
    const val GROUP_SIZE = 4

    /** 规范形式长度：48 字符，无分隔符。 */
    const val CANONICAL_LENGTH = GROUP_COUNT * GROUP_SIZE

    /** 底层随机字节数：30 字节 = 240 位熵。 */
    const val ENTROPY_BYTES = CANONICAL_LENGTH * 5 / 8

    const val GROUP_SEPARATOR = '-'

    /**
     * 生成一个新的恢复码，返回**规范形式**（48 字符，全大写，无分隔符）。
     *
     * 展示给用户时请用 [formatForDisplay]，
     * 送入 KDF 时请用 [canonicalize] 的返回值，不要直接用用户输入的原始字符串。
     */
    fun generate(): String = Base32Crockford.encode(SecureBytes.random(ENTROPY_BYTES))

    /** 加上连字符便于抄写：`ABCD-EFGH-...`（12 组）。 */
    fun formatForDisplay(canonical: String): String =
        canonical.chunked(GROUP_SIZE).joinToString(GROUP_SEPARATOR.toString())

    /**
     * 把用户输入（可能带连字符、空格、小写、抄错的 O/I/L）归一化为规范形式。
     * 长度不符或含非法字符时返回 null。
     */
    fun canonicalize(input: String): String? {
        val decoded = Base32Crockford.decodeOrNull(input, ENTROPY_BYTES) ?: return null
        return Base32Crockford.encode(decoded)
    }

    fun isValid(input: String): Boolean = canonicalize(input) != null

    /** 用户输入的展示级预校验：只判断字符是否可能合法，不判断长度。用于输入框的即时反馈。 */
    fun isPlausiblePartialInput(input: String): Boolean {
        if (input.length > CANONICAL_LENGTH + GROUP_COUNT) return false
        return input.all { c ->
            c == GROUP_SEPARATOR || c == ' ' || Base32Crockford.normalize(c.toString()) != null
        }
    }
}
