package com.fpb.vault.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * 硬件密钥的加固规格与换代号迁移 —— 必须问真实平台才能得到答案的两件事。
 *
 * ## 为什么这一份不能是 JVM 单测
 *
 * 这一整份测试的主题是"**平台到底照做了没有**"：
 * Keystore 有没有接受 `setIsStrongBoxBacked` / `setUnlockedDeviceRequired` /
 * `setUserAuthenticationRequired`，以及换代号之后上一代的别名还在不在。
 *
 * JVM 单测里 `android.*` 全是会抛 `RuntimeException("not mocked")` 的 stub，
 * 断言它们等于断言自己的假设 —— 正是安全加固里最容易自欺的一类验证。
 * 所以这一份跑在设备（`connectedDebugAndroidTest`）上：它读回来的是
 * `KeyInfo`，即 Keymaster 的真实回答。
 *
 * ## 能证明什么、不能证明什么（必须写清楚）
 *
 * **能证明**：从 Keystore 读回的 `KeyInfo` 里，`isUserAuthenticationRequired`、
 * `isInvalidatedByBiometricEnrollment`、`getUserAuthenticationValidityDurationSeconds`、
 * 以及保护等级（`getSecurityLevel`，其中 StrongBox 与 TEE 可区分）都是加固后的取值。
 *
 * **不能证明**：`setUnlockedDeviceRequired(true)`。
 * `KeyInfo` 里**没有**对应的读取接口（成员只有上面那几个加上
 * `isInsideSecureHardware` / `getKeystoreAlias` 等），
 * `KeyGenParameterSpec` 的 getter 也只能读我们自己刚构造的那个对象。
 * 因此那一项的证据上限是"我们确实请求了 ＋ 平台接受了这份规格并生成了密钥"，
 * [BiometricGate.EnrollmentSpec.unlockedDeviceRequired] 的 KDoc 里也这么写。
 *
 * **不能证明（第二件）**：本机有没有 StrongBox。模拟器一定没有，
 * 所以"最严那一档真的可用"只能在有该硬件的设备上验证。
 * 这里能做的是验证**降级路径**：不支持时不能谎报支持（见断言）。
 *
 * ## 方法名不能带空格（踩过一次）
 *
 * 下面这些用反引号包起来的中文方法名**必须没有空格**。带空格的名字在 JVM 单测里
 * 完全合法（`Audit8RegressionTest` 就是这么过的），但编进 androidTest 的 DEX 时
 * 会被 D8/R8 直接拒绝：
 *
 * ```
 * com.android.tools.r8.internal.jf:
 *   Space characters in SimpleName '尚未生成密钥时规格为 null_而不是编一个默认值'
 *   are not allowed prior to DEX version 040
 * ```
 *
 * 原因是本工程 `minSdk = 26`（DEX 038），而带空格的简单名要到 DEX 040 才允许。
 * 症状很容易看错方向：报错来自 `dexBuilderDebugAndroidTest` 这个构建任务，
 * 看起来像依赖或配置问题，实际是**测试方法名**的问题。
 */
@RunWith(AndroidJUnit4::class)
class BiometricGateInstrumentedTest {

    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val gate get() = BiometricGate(target)
    private val wrapFile get() = File(target.filesDir, WRAP_FILE)

    @Before
    fun cleanSlate() = clearEverything()

    @After
    fun tidyUp() = clearEverything()

    // ==================== ③④：读回来的规格 ====================

    /**
     * ③④ 的核心断言：读回来的规格确实是加固后的那一档。
     *
     * ## 这条断言证明的是什么（1.1.5 里被重新界定过一次）
     *
     * 它**不是**"从密钥上读回来确认了属性"—— 那条路对 AES 密钥不存在
     * （`AndroidKeyStore` 未注册 `KeyFactory/AES`，实测见下面的逐档探针用例）。
     * 它证明的是：**平台接受了这份加固规格**，证据是 `generateKey()` 没有抛异常。
     * 这个证据成立的前提是"平台对不支持的要求抛异常、而不是静默忽略"——
     * 本机实测支持这条前提（未录入生物识别时，`setUserAuthenticationRequired(true)`
     * 直接抛 `InvalidAlgorithmParameterException`，没有默默降级）。
     *
     * **它不能证明**"平台声称支持 StrongBox 却静默忽略"这一种情形。
     *
     * 若本机连最低档都生成不出来（例如既没录生物识别、也没有锁屏凭据），
     * 那是环境限制而非缺陷，因此用 `assumeTrue` 记为跳过并留下原因 ——
     * 一条会静默变成"通过"的环境限制，比一条跳过危险得多。
     * 但**跳过不等于查明了原因**：同一现象至少有两个完全不同的成因
     * （本该抛异常的平台限制 / 我们自己写错了规格），所以下面同时打印
     * 逐档失败原文，并要求"跳过时能说出是哪一档、因为什么"。本条在 1.1.5
     * 里就是被这个区分救回来的。
     */
    @Test
    fun `新生成的密钥确实带上了加固规格`() {
        val spec = gate.ensureEnrollmentKey()
        val ladder = gate.probeKeySpecLadder()
        assumeTrue(
            "本机拒绝了全部档位，无法验证规格（环境限制，非缺陷）。逐档原文：" +
                ladder.joinToString("；") { (level, answer) -> "$level=$answer" },
            spec != null,
        )
        val s = spec!!

        val summary = "[规格实测] guard=${s.guard}" +
            " authRequired=${s.userAuthenticationRequired}" +
            " authPerUse=${s.authPerUse}" +
            " invalidatedByEnrollment=${s.invalidatedByBiometricEnrollment}" +
            " unlockedDeviceRequiredRequested=${s.unlockedDeviceRequired}" +
            " strongBoxSupported=${gate.strongBoxSupported()}" +
            " sdk=${android.os.Build.VERSION.SDK_INT}"
        println(summary)

        assertTrue("④ 密钥没有要求用户认证：$summary", s.userAuthenticationRequired)
        assertTrue(
            "④ 认证带免验窗口（不是 auth-per-use）—— 解锁后一段时间内无需再验，$summary",
            s.authPerUse,
        )
        assertTrue("④ 新增生物识别录入不会让密钥失效：$summary", s.invalidatedByBiometricEnrollment)

        // ③ 保护等级必须**有结论**。以前这里靠读回 KeyInfo，而那条路对 AES 不存在，
        // 于是 guard 永远是 null、整条断言永远跳过 —— 表现和"环境限制"一模一样。
        // 现在 guard 来自落盘记录，取不到就说明"生成时漏了记档位"，必须报出来。
        assertNotNull(
            "③ 拿不到密钥的保护等级 —— 生成时没有记下档位（界面会永远不说明保护等级）：$summary",
            s.guard,
        )

        // ③ 最硬的一条：**落盘记录必须等于平台真正接受的那一档**。
        // 这台设备上哪些档能被接受是刚刚实测出来的（探针逐档问过），
        // 而 guard 是我们据此记下的结论 —— 两者不一致，界面就会向用户
        // 说明一个错误的保护等级（说高了是虚假安全感，说低了是虚惊）。
        // 这条断言与设备无关：模拟器、有 StrongBox 的旗舰、拒绝解锁要求的定制 ROM 上
        // 都成立，因为它比的是"平台回答"与"我们的记录"，而不是某个固定档位。
        val accepted = ladder.firstOrNull { it.second == "OK" }?.first
        val expected = when (accepted) {
            "STRONGBOX" -> BiometricGate.KeyGuard.STRONGBOX
            "TEE_HARDENED" -> BiometricGate.KeyGuard.TEE
            "SOFT_REQUIREMENTS" -> BiometricGate.KeyGuard.SOFTWARE
            else -> null
        }
        assertEquals(
            "③ 落盘档位（$accepted）与读回来的保护等级（${s.guard}）对不上 —— " +
                "界面会说明错误的保护等级：$summary",
            expected,
            s.guard,
        )

        if (!gate.strongBoxSupported()) {
            assertNotEquals(
                "③ 本机不声称支持 StrongBox，却报告落到了 StrongBox —— 能力探测或档位映射有问题：$summary",
                BiometricGate.KeyGuard.STRONGBOX,
                s.guard,
            )
        }
    }

    @Test
    fun 逐档探针_打印平台对每一档的回答() {
        val report = gate.probeKeySpecLadder()
        println(
            "[逐档探针] sdk=${android.os.Build.VERSION.SDK_INT}" +
                " strongBoxSupported=${gate.strongBoxSupported()}",
        )
        report.forEach { (level, answer) -> println("  [逐档探针] $level -> $answer") }

        // 再来一层：走一遍生产路径，把"停在哪一步"打出来。
        // 只报"返回了 null"是不够的 —— 那正是这条用例第一次跑成 skipped 时
        // 说不清的地方（"没录指纹"和"KeyInfo 读不回来"都会表现成 null）。
        gate.probeEnrollment().forEach { (step, answer) ->
            println("  [生产路径] $step -> $answer")
        }
        println("  [生产路径] ensureEnrollmentKey -> ${gate.ensureEnrollmentKey()}")

        // AndroidKeyStore 这个 provider 到底注册了哪些 KeyFactory —— 直接问它。
        // 这条是"读回规格"能不能成立的全部前提：如果它没有注册 AES，
        // 那么用 KeyFactory 读 `KeyInfo` 这条路对 AES 密钥就是**不存在**的，
        // 而不是"我们写错了算法名"。
        val provider = java.security.Security.getProvider("AndroidKeyStore")
        val factories = provider?.services
            ?.filter { it.type == "KeyFactory" }
            ?.joinToString { it.algorithm }
        println("  [provider] KeyFactory 已注册算法：$factories")
        listOf("AES", "EC", "RSA").forEach { alg ->
            val r = runCatching { java.security.KeyFactory.getInstance(alg, "AndroidKeyStore") }
            println("  [provider] KeyFactory/$alg -> ${r.fold({ "OK" }, { it.javaClass.simpleName })}")
        }

        assertTrue("探针至少要报告一档，否则阶梯是空的：$report", report.isNotEmpty())
    }

    @Test
    fun `尚未生成密钥时规格为空_而不是编一个默认值`() {
        assertEquals(null, gate.enrollmentSpec())
    }

    @Test
    fun `能力探测与设备实际特性一致`() {
        val supports = gate.strongBoxSupported()
        val feature = target.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            assertEquals("strongBoxSupported 与 PackageManager 的特性开关不一致", feature, supports)
        } else {
            assertFalse("API 28 以下不该报告支持 StrongBox", supports)
        }
        println("[能力探测] strongbox_feature=$feature, strongBoxSupported=$supports")
    }

    // ==================== 换代号迁移 ====================

    /**
     * 迁移路径的核心：有 v1 痕迹时必须**真的清掉**，并如实回答"退役了"。
     *
     * 清两样东西，缺一不可：
     * - Keystore 里的 v1 别名 —— 否则旧密钥一直在，等于加固没生效；
     * - `bio_wrap.bin` —— 它的 KEK 由 v1 密钥加密，删掉别名后它就是一段
     *   永远解不开的密文；留着会让 `hasEnrollment()` 继续报"已启用"。
     */
    @Test
    fun `有上一代痕迹时_退役会删掉别名与包裹文件`() {
        createAlias(LEGACY_ALIAS)
        wrapFile.writeBytes(byteArrayOf(12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13))
        assertTrue("前置条件：v1 别名应当在", aliasExists(LEGACY_ALIAS))
        assertTrue("前置条件：包裹文件应当在", wrapFile.isFile)
        assertFalse("v1 不该被当成可用绑定（当前代是 v2）", gate.hasEnrollment())

        val retired = gate.retireLegacyEnrollment()

        assertTrue("有 v1 痕迹却没报告退役", retired)
        assertFalse("v1 别名还在 —— 旧规格的密钥没有被清掉", aliasExists(LEGACY_ALIAS))
        assertFalse("bio_wrap.bin 还在 —— 它会是一段永远解不开的密文", wrapFile.isFile)
    }

    /**
     * 没有 v1 痕迹时**什么都不做**，并且如实回答"没有退役"。
     *
     * 这条看着平淡，它挡的是最坏的一种误伤：对一个已经在用 v2 的用户
     * 回报"退役了"，界面就会让他重新启用一次，而他本来好好的。
     */
    @Test
    fun `没有上一代痕迹时_退役什么都不做`() {
        assertFalse("干净的设备上不该报告退役", gate.retireLegacyEnrollment())
    }

    /**
     * 当前这一代的绑定绝不能被迁移逻辑碰掉。
     *
     * 判据必须是"**v1 别名是否存在**"，而不是"包裹文件是否存在" ——
     * 两代用的是同一个文件名 `bio_wrap.bin`，只看文件会把正在用的绑定删掉，
     * 而后果正是这次加固想要避免的那件事：用户的指纹解锁凭空失效。
     */
    @Test
    fun `有上一代痕迹时也不碰当前这一代的绑定`() {
        gate.ensureEnrollmentKey()
        val hasCurrent = aliasExists(CURRENT_ALIAS)

        // 造出 v1 痕迹，然后跑退役
        createAlias(LEGACY_ALIAS)
        gate.retireLegacyEnrollment()

        assertFalse("v1 该被清掉", aliasExists(LEGACY_ALIAS))
        assertEquals("当前这一代的存在状态被改变了", hasCurrent, aliasExists(CURRENT_ALIAS))
    }

    @Test
    fun `退役之后hasEnrollment转为false_用户会看到需要重新启用`() {
        // 造一个"完整"的 v1 状态：v1 别名 + 包裹文件
        createAlias(LEGACY_ALIAS)
        wrapFile.writeBytes(byteArrayOf(12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13))
        assertTrue(gate.retireLegacyEnrollment())

        assertFalse("退役之后不该还自称已启用生物识别", gate.hasEnrollment())
        assertFalse(gate.retireLegacyEnrollment()) // 幂等
    }

    // ==================== 工具 ====================

    private fun clearEverything() {
        runCatching { gate.clear() }
        runCatching { wrapFile.delete() }
        runCatching { File(target.filesDir, WRAP_FILE + ".tmp").delete() }
        deleteAlias(LEGACY_ALIAS)
        deleteAlias(CURRENT_ALIAS)
    }

    /**
     * 造一份"上一代"的痕迹。
     *
     * 刻意用**不带认证要求**的规格：退役逻辑只关心"这个别名在不在"，
     * 而带认证要求的密钥在没有锁屏密码的设备上根本生成不出来 ——
     * 那会让这条用例在干净的模拟器上莫名其妙地失败，进而掩盖真正的回归。
     */
    private fun createAlias(alias: String) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    private fun deleteAlias(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun aliasExists(alias: String): Boolean =
        runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val WRAP_FILE = "bio_wrap.bin"

        // 两个别名在这里写成字面量，与 StorageNamesTest 的做法一致：
        // 它们是对外契约，测试里写死才能在有人改动时立刻失败。
        const val LEGACY_ALIAS = "fpb.biometric.v1"
        const val CURRENT_ALIAS = "fpb.biometric.v2"
    }
}
