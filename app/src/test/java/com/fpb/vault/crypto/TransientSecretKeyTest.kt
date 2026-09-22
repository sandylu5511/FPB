package com.fpb.vault.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TransientSecretKey] 本身 —— AES 与 HMAC 共用的那一套清零机制。
 *
 * ## 与 `Audit8RegressionTest` 的分工
 *
 * - **这个文件测封装本身**：副本归谁所有、销毁清到什么、计数怎么分流、算法名从哪来。
 * - `Audit8RegressionTest` 测**生产路径有没有用它**：`AeadCipher.seal / open`
 *   走完之后探针有没有动 —— 那是"接线对不对"，不是"零件对不对"。
 *
 * 分开的理由很实际：混在一个文件里，将来一条红了得先判断是零件坏还是接线断。
 *
 * ## 为什么公共行为可以共用、差异行为绝不能合并成参数化用例
 *
 * 泛化时最容易图省事的一步，是把它写成 `@RunWith(Parameterized::class)` 的两组参数。
 * **不要那样做。** 参数化之后失败信息只会说"某一组挂了"，
 * 而这两组的失败含义完全不同：AES 那条挂了要去查 `AeadCipher`，
 * HMAC 那条挂了要去查 `VaultSession` 的派生路径。
 * 所以公共行为（自持副本、填零、幂等…）共用一份代码没问题，
 * **差异行为（算法名、探针计数）必须各写各的**，让红的用例自己说出是哪条路。
 *
 * ## 对照实验
 *
 * | 把生产代码改成 | 应当红的用例 |
 * |---|---|
 * | `destroy()` 里去掉 `material.fill(0)` | `销毁之后副本是零_复用是显式失败` |
 * | `destroy()` 里去掉 `material = null` | 同上（`isDestroyed` 那半条） |
 * | `destroy()` 里去掉计数自增 | 两条 `…销毁只让…探针加一…` |
 * | 未知算法分支改成 `else -> scrubbedHmacKeyCount.…` | `未知算法不计入任何一条探针` |
 * | `getEncoded()` 改成返回 `material.copyOf()` | `getEncoded 交出的是内部数组本身…` |
 * | `getAlgorithm()` 改成写死 `"AES"` | `HMAC 实例的 getAlgorithm 与 AES 实例不同…` |
 */
class TransientSecretKeyTest {

    // ==================== 一、两种算法共用的行为 ====================

    /**
     * 从 `Audit8RegressionTest` 移来（原名「密钥交出的是独立副本_改动原数组不会影响它」），
     * 断言一字未改 —— 只是把构造方式换成泛化之后的签名。
     */
    @Test
    fun `构造时复制入参_改动原数组不影响这把密钥`() {
        val original = SAMPLE_KEY.copyOf()
        val expected = original.copyOf()
        val key = TransientSecretKey(original, TransientSecretKey.AES)

        val handed = key.getEncoded()

        assertNotSame("交出的不能就是调用方那个数组", original, handed)
        assertTrue("副本内容应与原数组一致", expected.contentEquals(handed))
        original[0] = 99
        assertEquals("改动原数组影响了密钥副本 —— 说明没有真正复制", expected[0].toInt(), handed[0].toInt())
    }

    /** 从 `Audit8RegressionTest` 移来（原名「销毁之后那份副本是零_且不可再用」），断言一字未改。 */
    @Test
    fun `销毁之后副本是零_复用是显式失败`() {
        val key = TransientSecretKey(ByteArray(KEY_BYTES) { 7 }, TransientSecretKey.AES)
        val handed = key.getEncoded()
        assertFalse(key.isDestroyed)

        key.destroy()

        assertTrue(key.isDestroyed)
        assertTrue("销毁后交给 Cipher 的那份字节应全为零，实际：${handed.toList()}", isAllZero(handed))
        // 复用它应当是**显式失败**，而不是悄悄拿一串零去运算（那会表现为"密码不对"）
        assertTrue(runCatching { key.getEncoded() }.isFailure)
    }

    /**
     * 幂等：重复销毁不抛异常、状态不变。
     *
     * 「不重复计数」那半条**不在**这里，因为它是差异行为（涉及具体哪个探针）——
     * 见下面两组。
     */
    @Test
    fun `销毁是幂等的`() {
        val key = TransientSecretKey(ByteArray(KEY_BYTES) { 3 }, TransientSecretKey.AES)

        key.destroy()
        key.destroy()

        assertTrue("重复销毁之后状态应仍是已销毁", key.isDestroyed)
    }

    /** 从 `Audit8RegressionTest` 移来（原名「密钥的 toString 不泄漏内容」），并补一条"要带算法名"。 */
    @Test
    fun `toString 不泄漏内容_且带上算法名`() {
        val text = TransientSecretKey(ByteArray(KEY_BYTES) { 42 }, TransientSecretKey.AES).toString()

        assertTrue("toString 应当能自证身份：$text", text.contains("TransientSecretKey"))
        assertFalse("toString 里不该出现密钥字节：$text", text.contains("42"))
        assertTrue(
            "toString 没带算法名 —— 两类实例在日志与失败信息里长得一样：$text",
            text.contains(TransientSecretKey.AES),
        )
    }

    /**
     * `getEncoded()` 交的是**内部数组本身**，不是每次新建的副本。
     *
     * 这是这个类的核心取舍：`Cipher` / `Mac` 只在 `init` 时读一次，
     * 而每多复制一份就多留一份够不到的明文。所以"返回内部数组"不是省事，
     * 是目的本身。这条用 `assertSame`（引用相等）而不是内容相等来钉，
     * 因为内容相等在两种实现下都成立 —— 那样它就没有鉴别力。
     */
    @Test
    fun `getEncoded 交出的是内部数组本身_不是每次新建的副本`() {
        val key = TransientSecretKey(SAMPLE_KEY.copyOf(), TransientSecretKey.AES)

        val first = key.getEncoded()
        val second = key.getEncoded()

        assertSame(
            "getEncoded() 每次都新建一份副本 —— 那等于每调一次就多留一份够不到的明文，" +
                "与这个类存在的理由正好相反",
            first,
            second,
        )
    }

    /** `getFormat()` 对 AES 与 HMAC 都是裸密钥字节，这个确实可以共用。 */
    @Test
    fun `getFormat 对两种算法都是 RAW`() {
        assertEquals("RAW", TransientSecretKey(SAMPLE_KEY.copyOf(), TransientSecretKey.AES).getFormat())
        assertEquals("RAW", TransientSecretKey(SAMPLE_KEY.copyOf(), TransientSecretKey.HMAC_SHA256).getFormat())
    }

    // ==================== 二、AES 差异行为 ====================

    @Test
    fun `AES 实例的 getAlgorithm 是构造时传入的那个`() {
        val key = TransientSecretKey(SAMPLE_KEY.copyOf(), TransientSecretKey.AES)

        assertEquals(TransientSecretKey.AES, key.getAlgorithm())
    }

    /**
     * AES 那条路**只动 AES 探针**，且重复销毁不重复计数。
     *
     * 后一个断言是 2026-09-20 那次教训的固化：探针代表"真的清掉了几份"，
     * 不是"`destroy()` 被调用了几次"。虚增会让它在缺陷存在时照样绿。
     */
    @Test
    fun `AES 销毁只让 AES 探针加一_且重复销毁不重复计数`() {
        val aesBefore = scrubbedAesKeyCount.get()
        val hmacBefore = scrubbedHmacKeyCount.get()

        val key = TransientSecretKey(ByteArray(KEY_BYTES) { 3 }, TransientSecretKey.AES)
        key.destroy()
        key.destroy()

        assertEquals("AES 副本的清零没有被正确计数", aesBefore + 1, scrubbedAesKeyCount.get())
        assertEquals(
            "AES 的销毁动到了 HMAC 探针 —— 两条判据从此互相污染，各自都失去鉴别力",
            hmacBefore,
            scrubbedHmacKeyCount.get(),
        )
    }

    // ==================== 三、HMAC 差异行为 ====================

    /**
     * 算法名必须**从构造参数来**，不能写死。
     *
     * 写死 `"AES"` 的后果不是"报错"，而是两类实例在日志、崩溃报告、测试失败信息里
     * 长得一模一样 —— 排查时会把 `VaultSession` 的派生路径看成本地加解密。
     */
    @Test
    fun `HMAC 实例的 getAlgorithm 与 AES 实例不同`() {
        val aes = TransientSecretKey(SAMPLE_KEY.copyOf(), TransientSecretKey.AES)
        val hmac = TransientSecretKey(SAMPLE_KEY.copyOf(), TransientSecretKey.HMAC_SHA256)

        assertEquals(TransientSecretKey.HMAC_SHA256, hmac.getAlgorithm())
        assertNotEquals(
            "两类实例的算法名相同 —— 日志与失败信息里分不出是哪条路",
            aes.getAlgorithm(),
            hmac.getAlgorithm(),
        )
    }

    @Test
    fun `HMAC 销毁只让 HMAC 探针加一_且重复销毁不重复计数`() {
        val aesBefore = scrubbedAesKeyCount.get()
        val hmacBefore = scrubbedHmacKeyCount.get()

        val key = TransientSecretKey(ByteArray(KEY_BYTES) { 9 }, TransientSecretKey.HMAC_SHA256)
        key.destroy()
        key.destroy()

        assertEquals("HMAC 副本的清零没有被正确计数", hmacBefore + 1, scrubbedHmacKeyCount.get())
        assertEquals(
            "HMAC 的销毁动到了 AES 探针 —— 那 `Audit8RegressionTest` 那几条" +
                "「seal/open 之后副本被清零」会在缺陷存在时照样绿",
            aesBefore,
            scrubbedAesKeyCount.get(),
        )
    }

    /**
     * 未知算法**一个探针都不动**。
     *
     * 这是"宁可变红也不要假绿"的落地：如果 `else` 分支把陌生算法算进 HMAC，
     * 那么将来有人把派生路径换成 `HmacSHA512` 时，HMAC 那条判据会**继续显示正常** ——
     * 而它证明的已经不是它声称的事了。
     */
    @Test
    fun `未知算法不计入任何一条探针`() {
        val aesBefore = scrubbedAesKeyCount.get()
        val hmacBefore = scrubbedHmacKeyCount.get()

        TransientSecretKey(ByteArray(KEY_BYTES) { 5 }, "HmacSHA512").destroy()

        assertEquals("未知算法被算成了 AES", aesBefore, scrubbedAesKeyCount.get())
        assertEquals(
            "未知算法被算成了 HMAC —— 那 HMAC 那条判据以后再也不能证明它声明的事",
            hmacBefore,
            scrubbedHmacKeyCount.get(),
        )
    }

    private companion object {
        const val KEY_BYTES = 32

        val SAMPLE_KEY = ByteArray(KEY_BYTES) { (it + 1).toByte() }

        fun isAllZero(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }
    }
}
