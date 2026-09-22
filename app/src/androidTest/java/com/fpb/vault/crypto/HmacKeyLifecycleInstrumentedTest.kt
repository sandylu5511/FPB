package com.fpb.vault.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC 密钥副本 —— **设备侧回归判据**。
 *
 * ## 这份文件的性质（1.1.6 改写，改写前后不是同一种东西）
 *
 * 它原来是**评估用例**：一堆带 `[HMAC实测]` 前缀的 `println`，去问设备
 * "平台在这一步到底做了什么"。那次评估的结论已经落档：
 *
 * - 结论与修法：`dist/HMAC密钥副本-独立评估.md`
 * - 原始实测输出：`dist/evidence/hmac-key-lifecycle/logcat-System.out.txt`
 * - 用例结果 XML：`dist/evidence/hmac-key-lifecycle/androidTest-results.xml`
 *
 * 评估做完之后这个文件的价值就变了：**从"问平台"变成"钉住我们依赖的平台行为"**。
 * 所以 1.1.6 把全部 `println` 去掉（诊断输出已经在上面那份 logcat 里，
 * 而且去没去掉是可核对的：`adb logcat -d -s System.out | grep HMAC实测` 现在应当为空），
 * 断言**一条都没删**。
 *
 * ## 为什么它必须在设备上跑（JVM 单测替代不了）
 *
 * 这个文件里几乎每一条的结论**在 JVM 与 Android 上都相反**：
 * `SecretKeySpec.destroy()` 在 OpenJDK 上真的会清零，在 Android 上抛
 * `DestroyFailedException` 且一个字节都不清；`Mac` 的实现一边是 SunJCE、
 * 一边是 Conscrypt / AndroidOpenSSL。所以"JVM 上绿了"推不出"设备上也是"。
 * （同一条坑写在 [TransientSecretKey] 的 KDoc 里。）
 *
 * ## 第一组：平台的事实（**哨兵**，不是我们的要求）
 *
 * | 事实 | 用例 | 它一旦变红意味着 |
 * |---|---|---|
 * | `SecretKeySpec` 构造时复制入参 | [构造时复制_清调用方数组不影响密钥] | 清调用方数组成了有效手段，修法要重估 |
 * | `getEncoded()` 每次新建副本 | [读取编码每次返回新副本_清返回值清不掉密钥] | "清返回值"这条路变得可行 |
 * | `destroy()` 一个字节也清不掉 | [destroy在Android上到底做了什么] | `SecretKeySpec` 可能已经可用 |
 * | `Mac` 在 `init` 时取走密钥、之后不再回读 | [init后清零Java端数组_Mac是否照常工作]、[init后立刻销毁副本_是否仍能算出正确结果] | `finally` 的位置要重估 |
 * | 反射调不到那个包级私有的 `clear()` | [反射探测_clear是否可调] | 多了一条（但脆弱、不推荐）的捷径 |
 *
 * 这些断言**变红不等于代码有缺陷** —— 它等于"我们赖以设计的那条平台行为变了"。
 * 这类哨兵必须存在：没有它，平台一升级，旧修法就从"合适"悄悄变成"不合适"，
 * 而所有测试照样全绿。
 *
 * ## 第二组：我们的不变式（这些才是"必须一直成立"的）
 *
 * - [自持副本与SecretKeySpec算出的派生结果必须完全一致] —— 派生 id 是**落盘契约**，
 *   换实现不能改结果。改了不会报错，只会让升级后的库**找不到自己的行**。
 * - [生产形状的销毁会计入HMAC探针] —— 探针机制在 ART 上同样工作
 *   （JVM 上能工作不代表这里能），且两条探针互不串台。
 *
 * ## 方法名不能带空格
 *
 * `minSdk = 26`（DEX 038）不允许带空格的简单名，D8 会拒编。这里一律用 `_` 分隔。
 * 这个坑踩过：D8 一次只报一个名字，改完第一处要再跑一轮才暴露第二处 ——
 * 所以改完应当用脚本扫一遍**全部**方法名，而不是靠一轮轮编译去试。
 */
@RunWith(AndroidJUnit4::class)
class HmacKeyLifecycleInstrumentedTest {

    // ==================== 一、平台的事实（哨兵） ====================

    /**
     * `SecretKeySpec` 构造时是否复制入参 —— 决定"清调用方的数组"有没有用。
     *
     * 生产代码里 `material` 就是 `SecureBytes.expose()` 交出来的**内部数组本身**
     * （`VaultDek.expose()` / `AuditKey.expose()` 一路 `return bytes`，不复制），
     * 所以它确实会被 `close()` 清零。问题在于：**清它，跟 `SecretKeySpec` 里
     * 那份副本没有关系**。这条用例把那个"没有关系"量出来。
     */
    @Test
    fun 构造时复制_清调用方数组不影响密钥() {
        val original = SAMPLE_KEY.copyOf()
        val material = original.copyOf()
        val key = SecretKeySpec(material, HMAC)

        material.fill(0) // 调用方清掉自己那份（模拟 SecureBytes.close() 的时机）
        val seen = key.encoded

        assertNotNull("getEncoded() 返回 null —— 密钥内部引用已被清空（平台行为变了）", seen)
        assertArrayEquals(
            "清调用方数组竟然影响到了密钥 —— 说明 SecretKeySpec 没有复制入参，" +
                "那么清 material 就已经是全部手段了（与预期相反，修法要重估）。",
            original,
            seen,
        )
    }

    /**
     * `getEncoded()` 每次返回的是新副本还是内部数组 —— 决定"清返回值"有没有用。
     *
     * 这是一种非常容易想到、也非常容易被当成修法的做法：`key.getEncoded().fill(0)`。
     * 如果 `getEncoded()` 返回的是**内部**数组，它确实有用；如果每次都新建一份副本，
     * 那就是在清一份马上会被丢掉的垃圾，而真正的副本一个字节都没动 ——
     * 一个不会报错、也不会生效的"修复"。
     */
    @Test
    fun 读取编码每次返回新副本_清返回值清不掉密钥() {
        val original = SAMPLE_KEY.copyOf()
        val key = SecretKeySpec(original.copyOf(), HMAC)

        val first = key.encoded
        first.fill(0) // "清返回值"这种做法
        val after = key.encoded
        assertNotNull("getEncoded() 返回 null —— 密钥内部引用已被清空", after)

        assertArrayEquals(
            "清掉 getEncoded() 的返回值之后密钥竟然被清掉了 —— 那这条路是可行的" +
                "（与预期相反，值得直接采用）。",
            original,
            after,
        )
        // 顺带确认这条用例真的取到了密钥内容，否则上面的比较是在比两份空数组
        assertTrue("自证：这条用例确实取到了非空的密钥内容", after.isNotEmpty())
    }

    /**
     * `SecretKeySpec.destroy()` 在 Android 上实际的行为。
     *
     * 已知（`javap` 打在真实 `android.jar` 上，见 [TransientSecretKey] 的 KDoc）：
     * `SecretKeySpec` **没有覆盖** `destroy()`，因此走的是 `Destroyable` 的
     * 默认实现（抛 `DestroyFailedException`）。
     *
     * 但那是**静态**证据。这条用例把它变成**运行时**证据：不问"代码长什么样"，
     * 只问"这台设备上调用它会发生什么、之后密钥还在不在"。
     */
    @Test
    fun destroy在Android上到底做了什么() {
        val original = SAMPLE_KEY.copyOf()
        val key = SecretKeySpec(original.copyOf(), HMAC)
        val before = key.encoded.copyOf()

        val outcome = runCatching { key.destroy() }
        val destroyedFlag = runCatching { key.isDestroyed }
        val outcomeText = outcome.fold(
            { "正常返回（没有抛异常）" },
            { "${it.javaClass.name}: ${it.message}" },
        )

        val after = runCatching { key.encoded }
        assertTrue(
            "destroy() 之后 getEncoded() 不可用了：${after.exceptionOrNull()}",
            after.isSuccess,
        )
        val afterBytes = after.getOrThrow()
        // AOSP 的实现里，内部引用被清空后 getEncoded() 返回 **null**。
        // 显式断言一下，好让失败信息是"读回 null"而不是一段 NPE 栈。
        assertNotNull(
            "destroy() 之后 getEncoded() 返回 null —— 密钥内部引用被清空了" +
                "（那 destroy() 就不是完全无效的，修法要重估）。destroy() 实际：$outcomeText",
            afterBytes,
        )

        // 这就是结论本身：**一个字节也没清掉**，而 Destroyable 承诺的"销毁"
        // 也没有发生（isDestroyed 仍为 false）。两者一起才是完整的坏消息。
        assertArrayEquals(
            "destroy() 之后密钥内容变了 —— 那 SecretKeySpec 这条路可能已经可用" +
                "（与预期相反，修法要重估）。实际 destroy() 的返回：$outcomeText",
            before,
            afterBytes,
        )
        assertEquals(
            "isDestroyed() 报告密钥已销毁，但内容明明还在 —— 接口说谎，" +
                "依赖它做判断的地方都不可信。destroy() 实际：$outcomeText",
            false,
            destroyedFlag.getOrThrow(),
        )
    }

    /**
     * `Mac.init()` 之后再 `destroy()` 密钥对象，是否影响 `doFinal()`。
     *
     * 这一条直接决定"能不能照抄 `AeadCipher` 的 `finally` 位置"：
     * 如果 `init` 之后销毁密钥对 `doFinal` 毫无影响，说明 `Mac` 已经
     * 不再需要那个 Java 端对象，销毁是安全的。
     */
    @Test
    fun init之后destroy是否影响doFinal() {
        val original = SAMPLE_KEY.copyOf()
        val key = SecretKeySpec(original.copyOf(), HMAC)

        val mac = Mac.getInstance(HMAC)
        mac.init(key)
        val first = mac.doFinal(MSG)

        runCatching { key.destroy() }

        val second = runCatching { mac.doFinal(MSG) }
        assertTrue(
            "init 之后 destroy() 密钥，Mac 就算不动了：${second.exceptionOrNull()}",
            second.isSuccess,
        )
        assertArrayEquals(
            "init 之后 destroy() 密钥改变了 Mac 的输出 —— 说明 Mac 还在依赖那个密钥对象，" +
                "销毁的时机必须排在本次运算之后。",
            first,
            second.getOrThrow(),
        )
    }

    /**
     * **这一条是 `finally { destroy() }` 能放在 `init` 之后、`doFinal` 之前的全部依据**：
     * 把 Java 端密钥清零之后，`Mac` 还照常工作吗？
     *
     * 用生产代码真实的 [TransientSecretKey]（`getEncoded()` 交内部数组、不额外复制），
     * 这样**应用侧唯一的那份数组**就在我们手里，想清随时能清。
     *
     * 两种结果的含义完全不同：
     *
     * - **输出不变** → `Mac` 在 `init` 时就把密钥复制进了自己的上下文
     *   （走 native）。Java 端清零不影响运算，于是"init 之后立刻清掉我们的副本"
     *   是**功能上安全**的。代价是：那份副本仍在 native 里，**我们清不到它** ——
     *   这一点必须如实写进结论，不能让"我们清了"被读成"内存里没有了"。
     * - **输出变了** → `Mac` 每次运算都回来读 Java 端，那么清零必须排在
     *   `doFinal` 之后，`finally` 的位置要跟着改。
     */
    @Test
    fun init后清零Java端数组_Mac是否照常工作() {
        val original = SAMPLE_KEY.copyOf()

        // 参考值：用一把正常的、不受干扰的密钥算一遍
        val reference = Mac.getInstance(HMAC).apply {
            init(SecretKeySpec(original.copyOf(), HMAC))
        }.doFinal(MSG)

        val key = TransientSecretKey(original.copyOf(), HMAC)
        val mac = Mac.getInstance(HMAC)
        mac.init(key)
        val first = mac.doFinal(MSG)

        key.destroy() // 清掉应用侧唯一那份数组
        assertTrue("自持副本没有进入已销毁状态，这条用例失去前提", key.isDestroyed)

        val second = runCatching { mac.doFinal(MSG) }
        assertArrayEquals("init 时算出的结果与参考值不符，说明构造方式有问题", reference, first)
        assertTrue("清零后 Mac 直接算不动了：${second.exceptionOrNull()}", second.isSuccess)
        assertArrayEquals(
            "把 Java 端数组清零之后 Mac 的输出变了 —— 那么密钥副本不是" +
                "（只）在 init 时被取走的，销毁时机必须排在 doFinal 之后，" +
                "不能照抄 AeadCipher 的 finally 位置。",
            first,
            second.getOrThrow(),
        )
    }

    /**
     * `init` 之后**立刻**销毁自持副本（紧贴 `init`，中间不隔运算），
     * 还能不能算出与正常路径完全一致的结果。
     *
     * 与上一条的区别是"清零点"：上一条在 `doFinal` 前清、中间隔了一次运算；
     * 这一条紧贴 `init` 清，正是封装里 `finally { destroy() }` 的落点。
     */
    @Test
    fun init后立刻销毁副本_是否仍能算出正确结果() {
        val original = SAMPLE_KEY.copyOf()
        val reference = Mac.getInstance(HMAC).apply {
            init(SecretKeySpec(original.copyOf(), HMAC))
        }.doFinal(MSG)

        val key = TransientSecretKey(original.copyOf(), HMAC)
        val mac = Mac.getInstance(HMAC)
        mac.init(key)
        key.destroy() // ← 就发生在这里：init 与 doFinal 之间

        val result = runCatching { mac.doFinal(MSG) }
        assertTrue("init 后立刻销毁副本就算不动了：${result.exceptionOrNull()}", result.isSuccess)
        assertArrayEquals(
            "init 后立刻销毁自持副本，算出来的 HMAC 与正常路径不一致 —— " +
                "这条路走不通，销毁要排到 doFinal 之后。",
            reference,
            result.getOrThrow(),
        )
    }

    /**
     * 同一个 `Mac` 实例能否换密钥复用 —— 以及 `init` 会不会把状态彻底重置。
     *
     * 生产代码是**每次派生都新建一个 `Mac`**，所以这条不是"当前有缺陷"，
     * 而是把"能不能改成共享实例"这个问题先答掉：共享时若 `init` 不重置状态，
     * 跨请求就会串味，而且串出来的还是**看起来正常的十六进制 id**。
     */
    @Test
    fun Mac实例可复用_且init会重置状态() {
        val keyA = SAMPLE_KEY.copyOf()
        val keyB = SAMPLE_KEY.map { (it + 1).toByte() }.toByteArray()

        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(keyA, HMAC))
        val a1 = mac.doFinal(MSG)

        mac.init(SecretKeySpec(keyB, HMAC))
        val b = mac.doFinal(MSG)

        mac.init(SecretKeySpec(keyA, HMAC))
        val a2 = mac.doFinal(MSG)

        assertArrayEquals("同一把密钥两次 init 之后的结果不一致，实例状态没被重置", a1, a2)
        assertFalse(
            "换了密钥但输出没变 —— init 根本没起作用，共享实例会串味",
            a1.contentEquals(b),
        )
    }

    /**
     * `doFinal` 之后是否需要手动 `reset`（HMAC 的语义：算完自动回到初始态）。
     *
     * 影响的是封装形状：如果 `doFinal` 会自动 reset，那么一次 `init` + 多次
     * `doFinal` 就是合法的；否则封装里必须跟一次 `reset()`，漏了会算出错误结果。
     */
    @Test
    fun doFinal之后可重复运算() {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(SAMPLE_KEY.copyOf(), HMAC))
        val first = mac.doFinal(MSG)
        val second = mac.doFinal(MSG)
        assertArrayEquals(
            "doFinal 之后没有自动回到初始态，封装里必须显式 reset()",
            first,
            second,
        )
    }

    /**
     * 能不能用反射调到那个包级私有的 `clear()`。
     *
     * `javap` 显示 `SecretKeySpec` 有一个 `void clear()`（包级私有）。
     * 如果它能被反射调到，理论上存在一条"原地清掉 `SecretKeySpec` 内部副本"的捷径。
     * 记录它是为了**避免以后有人再想一次**，不是把它当修法推荐 ——
     * 反射调私有 API 既脆弱又容易被系统更新打断。
     *
     * 这条断言的方向值得注意：它钉的是"**这条捷径当前不存在**"。
     * 它变红（能调通了）**不代表我们应该用它**，只代表这条记录过期了、值得重估。
     */
    @Test
    fun 反射探测_clear是否可调() {
        val key = SecretKeySpec(SAMPLE_KEY.copyOf(), HMAC)
        val lookup = runCatching { SecretKeySpec::class.java.getDeclaredMethod("clear") }
        val invoke = lookup.mapCatching { method ->
            method.isAccessible = true
            method.invoke(key)
        }
        val report = buildString {
            append("查找 clear() -> ")
            append(lookup.fold({ "找到" }, { "${it.javaClass.simpleName}: ${it.message}" }))
            append("；调用 -> ")
            append(invoke.fold({ "成功" }, { "${it.javaClass.simpleName}: ${it.message}" }))
        }

        assertFalse(
            "反射竟然能调到 SecretKeySpec.clear() 并且调用成功了 —— 这条路以前不存在、" +
                "现在存在了。注意这**不代表我们应该用它**（反射调私有 API 脆弱、" +
                "会被平台更新打断），只代表这条记录过期了，值得重估。实际：$report",
            lookup.isSuccess && invoke.isSuccess,
        )
    }

    // ==================== 二、我们的不变式 ====================

    /**
     * 用自持副本算出来的派生结果，必须与生产代码**以前**的形状逐字节一致。
     *
     * 这是替换密钥实现时唯一真正重要的判据：派生出来的 id 是**落盘契约**
     * （数据库里的行 id 就是它），算错不会报错，只会"找不到那一行"。
     *
     * 所以这条不是在验证"新写法跑得通"，而是在验证"写进去的 id 和以前一样"。
     * 两者的差别在于失败时机：前者当场就能发现，后者要等到用户升级完、
     * 发现库里空了才知道。
     */
    @Test
    fun 自持副本与SecretKeySpec算出的派生结果必须完全一致() {
        val material = SAMPLE_KEY.copyOf()

        // 旧路径：1.1.5 及以前生产代码的形状
        val oldMac = Mac.getInstance(HMAC)
        oldMac.init(SecretKeySpec(material.copyOf(), HMAC))
        val old = oldMac.doFinal(MSG).copyOf(ID_BYTES).toHex()

        // 新路径：生产代码现在的形状 —— 自持副本，且在 init 之后立刻销毁，
        // 这正是 derivedRowIdFrom 里 finally { destroy() } 的落点
        val key = TransientSecretKey(material.copyOf(), HMAC)
        val newMac = Mac.getInstance(HMAC)
        newMac.init(key)
        key.destroy()
        val new = newMac.doFinal(MSG).copyOf(ID_BYTES).toHex()

        assertEquals(
            "换成自持副本之后派生出来的 id 变了 —— 这是落盘契约，" +
                "变了会让升级后的库找不到自己的行，而且不会报错。",
            old,
            new,
        )
        assertEquals("id 应当是 $ID_BYTES 字节 → ${ID_BYTES * 2} 个十六进制字符", ID_BYTES * 2, old.length)
    }

    /**
     * 生产代码形状的销毁，在 **ART** 上同样会计入 HMAC 探针 —— 而且**不碰 AES 那条**。
     *
     * 为什么这条要放在设备上：探针机制本身在 JVM 上工作，不代表在 ART 上也工作
     * （`AtomicInteger`、内联、调用点裁剪都可能不一样）。而"探针动了没有"
     * 正是 `tools/security-selfcheck.py` 那两条判据在源码上认定的东西 ——
     * 这里把它在真实运行时确认一遍。
     *
     * "不碰 AES 那条"是 1.1.6 泛化时差点丢掉的性质：两个计数器混用会让
     * **AES 销毁成功、HMAC 忘了销毁，总数照样在涨**，两条判据同时通过，
     * 而其中一条路一个字节也没清。
     */
    @Test
    fun 生产形状的销毁会计入HMAC探针() {
        val aesBefore = scrubbedAesKeyCount.get()
        val hmacBefore = scrubbedHmacKeyCount.get()

        val mac = Mac.getInstance(HMAC)
        val secret = TransientSecretKey(SAMPLE_KEY.copyOf(), HMAC)
        try {
            mac.init(secret)
            mac.doFinal(MSG)
        } finally {
            secret.destroy()
        }

        assertEquals(
            "HMAC 副本的清零在 ART 上没有计入探针 —— 那么源码级那两条判据" +
                "在真机上就是空转的",
            hmacBefore + 1,
            scrubbedHmacKeyCount.get(),
        )
        assertEquals(
            "HMAC 的销毁动到了 AES 探针 —— 两条判据互相污染，各自都失去鉴别力",
            aesBefore,
            scrubbedAesKeyCount.get(),
        )
    }

    // ==================== 工具 ====================

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private companion object {
        const val HMAC = "HmacSHA256"

        /** 生产代码里 `MANIFEST_ID_BYTES` 的长度：取前 8 字节做行 id。 */
        const val ID_BYTES = 8

        /** 32 字节，与生产代码里 DEK / 审计钥匙的长度一致。 */
        val SAMPLE_KEY = ByteArray(32) { (it + 1).toByte() }

        val MSG = "fpb.hmac.probe".toByteArray()
    }
}
