package com.fpb.vault.vault

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 生物识别解锁的硬件侧。
 *
 * ## 它保护的到底是什么
 *
 * 不是 DEK。DEK 已经被一把 KEK 包裹着写在密钥文件里，这里做的是
 * **再给那把 KEK 加一道硬件锁**：
 *
 * ```
 * Android Keystore 里的一把 AES 密钥（每次使用都需要通过生物识别）
 *        └─ 加密 → 32 字节随机 KEK
 *                     └─ 该 KEK 就是包裹真库 DEK 的那把（BIOMETRIC 槽）
 * ```
 *
 * 因此"验指纹"这一步不是界面上的仪式，而是**硬件层面拒绝把密钥交出来**：
 * 没有通过生物识别，Keystore 就不会执行那次解密，KEK 也就拿不到。
 * 密钥本身**永远无法从 Keystore 导出**，所以把设备上的文件全部拷走也没用。
 *
 * ## 密钥规格：能多严就多严，严不了就逐级退
 *
 * [buildKeySpec] 造的是**最严的一档**，[generateKey] 按 StrictBox → TEE → 旧规格
 * 逐级降级。三档分别是：
 *
 * | 档 | StrongBox | 设备未锁定要求 | 什么时候用得上 |
 * |---|---|---|---|
 * | 最严 | 要 | 要 | 有独立安全芯片的机型（Pixel 3+、部分旗舰） |
 * | 中间 | 不要 | 要 | 绝大多数有 TEE 的机型（API 28+） |
 * | 兜底 | 不要 | 不要 | API 26/27，或前两档被平台拒绝时 |
 *
 * 逐级降级不是偷懒：`setIsStrongBoxBacked(true)` 在**没有该芯片**的设备上会抛
 * `StrongBoxUnavailableException`，`setUnlockedDeviceRequired(true)` 在一些
 * 厂商实现上会被 Keystore 直接拒掉。写死最严的那一档，结果是
 * **生物识别在这些设备上直接不可用** —— 那比"用低一档的硬件保护"更糟。
 * 因此这里的顺序是"先要最好的，要不到退一档，而不是整件事失败"。
 *
 * ## 换指纹就会失效（有意为之）
 *
 * 密钥设了 `setInvalidatedByBiometricEnrollment(true)`：设备上新录入一个指纹，
 * 这把密钥立刻作废。代价是用户加指纹后生物识别解锁会失效（需要重新启用一次），
 * 收益是"知道锁屏密码的人偷偷加自己的指纹"这条路被堵死 ——
 * 对一个以"别人拿到手机也打不开"为卖点的应用，这个取舍是必须的。
 *
 * ## 换代：为什么 v1 的绑定会被主动作废
 *
 * 密钥规格**一旦生成就改不了**：本类的 [secretKey] 命中已有别名就直接复用旧密钥，
 * 新写的 spec 根本不会被执行。所以把上面的加固真正落到**存量用户**身上，
 * 唯一办法是换一代别名（v1 → v2）并把旧的那份作废 —— 代价是用户要重新启用一次
 * 生物识别。这件事由 [retireLegacyEnrollment] 在启动时执行，
 * 不会造成任何数据损失：DEK 同时还被主密码槽与恢复码槽包裹着。
 *
 * ## 只能配强生物识别
 *
 * CryptoObject 这条通道要求生物识别是 Strong 级别（绝大多数屏下/背面指纹都满足；
 * 部分机型的 2D 人脸是 Weak 级别，用不了）。这是系统限制，不是这里的选择。
 */
class BiometricGate(private val context: Context) {

    enum class Availability {
        /** 可用。 */
        READY,

        /** 设备没有可用的强生物识别硬件。 */
        NO_HARDWARE,

        /** 有硬件，但用户一个都没录。 */
        NOT_ENROLLED,

        /** 硬件暂时不可用（例如被系统锁定）。 */
        UNAVAILABLE,
    }

    /**
     * 硬件密钥**实际**落在哪一级保护里。
     *
     * 这一枚举存在的理由不只是界面文案：它同时是"加固到底生效了没有"的**读回手段**。
     * 注意它的精度受平台限制 —— 详见 [enrollmentSpec]。
     */
    enum class KeyGuard {
        /** 独立安全芯片（StrongBox）。密钥在本应用与主处理器都无法直接访问的芯片内。 */
        STRONGBOX,

        /** 主处理器内的可信执行环境（TEE）。 */
        TEE,

        /**
         * **没有拿到硬件保护的保证**。
         *
         * 两种情况都会落到这里，而它们对用户的含义是同一个：**说不准**。
         * 1. 兜底档：我们请求的规格里**不含任何硬件要求**（API 26/27，
         *    或平台拒绝了更严的两档）；
         * 2. 平台报告保护等级为纯软件。
         *
         * **它不是"一定没有硬件保护"** —— API 26+ 的设备基本都有 TEE，
         * 兜底档生成的密钥大概率也在里面。我们只是**没有要求到、也读不回**，
         * 所以不能说"有"。界面文案必须按"无法确认"来写，
         * 写成"你的密钥没有硬件保护"是拿一句无法证实的话去吓用户。
         */
        SOFTWARE,

        /**
         * 在安全硬件内，但**分不出是 StrongBox 还是 TEE**。
         *
         * API 31 之前没有 `KeyInfo.getSecurityLevel()`，只有
         * `isInsideSecureHardware()`（是/否）。把"分不出"如实说出来，
         * 而不是默认它是 TEE —— 后者会在有 StrongBox 的旧机型上低估自己。
         *
         * **当前没有代码会产生这个取值**（读 `KeyInfo` 的路对 AES 密钥不存在，
         * 见 [guardOf]）。留着它是因为"分不出"在概念上仍然存在，
         * 而枚举里少了它，将来就会有人用 TEE 去顶替这个位置。
         */
        UNSPECIFIED_HARDWARE,

        /** 尚未生成。 */
        NONE,
    }

    /**
     * 一份密钥的规格快照。
     *
     * ## 每一项分别是怎么来的（这决定了它们各自能证明什么）
     *
     * ## ⚠️ 这里曾经写错，值得逐字读一遍
     *
     * 原设计写的是"前四项都是从 Keystore **读回来**的事实
     * （`KeyFactory.getKeySpec` → `KeyInfo`）"。
     * **这条路对 AES 密钥不存在。** 实测（API 37 模拟器）问 provider 自己：
     *
     * ```
     * [provider] AndroidKeyStore 的 KeyFactory 已注册算法：EC, RSA, XDH, ED25519, ML-DSA…
     * [provider] KeyFactory/AES -> NoSuchAlgorithmException
     * ```
     *
     * 它不注册 AES。所以 `readKeyInfo()` 从来**只可能返回 null** ——
     * 而它的失败方式不是报错，是"读不回来"：`enrollmentSpec()` 永远返回 null，
     * 于是界面里 [KeyGuard.STRONGBOX] 与 [KeyGuard.SOFTWARE] 两条文案
     * **都不会出现**（后者恰恰是应该被看见的那一条），
     * 而仪器测试里那条核心断言会**永远以"环境限制"为由跳过**。
     * 一句话：这段代码看起来在认真读回规格，实际什么都没读到，且不吭声。
     *
     * 现在的来源是明确的：**生成时平台接受了哪一档**（落盘记录，见
     * [StorageNames.Pref.BIOMETRIC_KEY_TIER]）＋**我们自己写进 spec 的值**。
     * 其"生效"的证据是"平台没有拒绝生成" —— 平台对不支持的要求**抛异常**
     * 而不是静默忽略（实测：未录入生物识别时 `setUserAuthenticationRequired(true)`
     * 抛 `InvalidAlgorithmParameterException`，而不是默默降级）。
     *
     * [unlockedDeviceRequired] 仍然是这一族里最弱的一项（平台读不回，
     * 且部分厂商可能不实现），所以它单独标注。
     */
    data class EnrollmentSpec(
        val guard: KeyGuard,
        val userAuthenticationRequired: Boolean,
        val invalidatedByBiometricEnrollment: Boolean,
        /** 认证有效期是否为 -1（auth-per-use：每次使用都要验，没有免验窗口）。 */
        val authPerUse: Boolean,
        /** 生成时请求了"设备未锁定"要求。**没有平台读回接口**，见本类的 KDoc。 */
        val unlockedDeviceRequired: Boolean,
    )

    private val wrapFile: File get() = File(context.filesDir, WRAP_FILE_NAME)

    /**
     * 只用来记"这一代密钥是在哪一档规格下生成的"（见
     * [StorageNames.Pref.BIOMETRIC_KEY_TIER]）。它读写的是普通 SharedPreferences，
     * 而 `getSharedPreferences` 本身就有进程级缓存，因此这里不必自己缓存实例。
     */
    private val settings: SettingsStore get() = SettingsStore(context)

    fun availability(): Availability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Availability.NO_HARDWARE
        return when (
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        ) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.UNAVAILABLE
            else -> Availability.NO_HARDWARE
        }
    }

    /** 是否已经存在可用的硬件包裹。文件在但密钥被作废时返回 false。 */
    fun hasEnrollment(): Boolean {
        if (!wrapFile.isFile) return false
        return runCatching {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            store.containsAlias(KEY_ALIAS)
        }.getOrDefault(false)
    }

    /** 本机是否声称有独立安全芯片。API 28 起才有这个 feature 标志。 */
    fun strongBoxSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        }.getOrDefault(false)
    }

    /**
     * 取回当前这一代密钥的规格；还没有密钥时返回 null。**不会生成密钥。**
     *
     * 界面用它如实说明"这把钥匙由哪一级硬件守着"。
     * 各项的来源与证据上限见 [EnrollmentSpec] 的 KDoc —— 简单说：
     * **不是从密钥上读回来的**（AES 读不回来），是"生成时平台接受了这一档"。
     */
    fun enrollmentSpec(): EnrollmentSpec? {
        if (!hasKey()) return null
        val tier = settings.biometricKeyTier
            ?.let { name -> runCatching { Hardening.valueOf(name) }.getOrNull() }
        // 落盘记录没有（例如从没有这个键的旧版本升上来）：不知道就别编一个。
        ?: return null
        return EnrollmentSpec(
            guard = guardOf(tier),
            // 下面四项都是我们自己写进 spec 的值。规格被平台接受了（生成没抛异常），
            // 这几项就是生效的 —— "被接受但没生效"在这套 API 上不是一种可能。
            userAuthenticationRequired = true,
            invalidatedByBiometricEnrollment = true,
            authPerUse = true,
            unlockedDeviceRequired = tier.unlockedDevice,
        )
    }

    /** 当前这一代密钥是否已在 Keystore 里。**不碰**包裹文件。 */
    fun hasKey(): Boolean = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(KEY_ALIAS)
    }.getOrDefault(false)

    /**
     * 诊断：走一遍"生成密钥 → 取规格"，回传**每一步**的结果。
     *
     * 为什么要单独有这一个：`ensureEnrollmentKey()` 对失败只回 `null` ——
     * 界面要的就是"行/不行"，但**排查要的是"停在哪一步"**。
     * 把这两件事挤进同一个返回值，会让"指纹解锁不可用"变成一句无法追问的话。
     *
     * 本工程 1.1.5 开发中就是靠这一段问出了真因：原设计的"读回 `KeyInfo`"
     * 对 AES 密钥**这条路不存在**（provider 不注册 `KeyFactory/AES`），
     * 而它伪装成"环境限制"（模拟器没录指纹）骗过了一轮 ——
     * 所以下面刻意保留了那一步的位置并写明它为何不适用，
     * 免得后来的人再沿原设计把 `readKeyInfo()` 加回来。
     */
    fun probeEnrollment(): List<Pair<String, String>> {
        val steps = mutableListOf<Pair<String, String>>()

        try {
            secretKey()
            steps += "生成或取回密钥" to "OK"
        } catch (e: Exception) {
            // 这里的分档失败原文会带上逐档原因（generateKey 会把每一档都记下来）。
            steps += "生成或取回密钥" to "${e.javaClass.simpleName}: ${e.message}"
            return steps
        }

        steps += "KeyStore.containsAlias(当前代号)" to hasKey().toString()
        steps += "落盘的档位记录" to (settings.biometricKeyTier ?: "（空 —— 界面将无法说明保护等级）")
        steps += "enrollmentSpec()" to (enrollmentSpec()?.toString() ?: "null")
        steps += "KeyFactory 读回 AES 的 KeyInfo（原设计）" to
            "不适用：AndroidKeyStore 未注册 KeyFactory/AES（EC/RSA/XDH/ED25519/ML-DSA 才有）"

        return steps
    }

    /**
     * 生成（若尚未生成）本机的硬件密钥，并返回它的规格。
     *
     * 只做"生成 + 读回"，**不做任何加解密**，因此不需要用户先通过生物识别
     * （需要认证的是使用密钥那一步）。`app/src/androidTest` 用它断言
     * 加固规格真的被平台接受了，而不是"我们请求了、就当作生效了"。
     */
    fun ensureEnrollmentKey(): EnrollmentSpec? = runCatching { secretKey() }
        .map { enrollmentSpec() }
        .getOrNull()

    /**
     * 作废上一代（v1）的硬件绑定。
     *
     * 判断依据是 **v1 别名是否存在于 Keystore**，而不是只看包裹文件：
     * 只看文件会误伤已经用上 v2 的用户（他们的包裹文件就叫同一个名字）。
     *
     * 具体动作：删掉 v1 别名、删掉 `bio_wrap.bin`。
     * 后者必须删 —— 它的 KEK 是用 v1 那把密钥加密的，删掉别名之后它就是一段
     * 永远解不开也永远没用的密文；留着只会让 [hasEnrollment] 继续报"已启用"。
     *
     * @return true = 这台设备上确实有 v1 的绑定，用户需要重新启用一次生物识别。
     */
    fun retireLegacyEnrollment(): Boolean {
        val hasLegacy = runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .containsAlias(LEGACY_KEY_ALIAS)
        }.getOrDefault(false)
        if (!hasLegacy) return false

        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(LEGACY_KEY_ALIAS)
        }
        runCatching { wrapFile.delete() }
        // 顺手把档位记录清掉：它记的是**上一代**那把密钥的规格，
        // 留着会让下一次 [enrollmentSpec] 拿旧档位去描述一把还不存在的新密钥。
        settings.biometricKeyTier = null
        return true
    }

    /**
     * 准备一个"加密"用的 Cipher，用于把 KEK 存起来。
     * 必须在用户通过生物识别之后才真正使用（见 [storeWrapped] 的调用方）。
     */
    fun newEncryptCipher(): Cipher? = runCatching {
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
    }.getOrNull()

    /** 准备一个"解密"用的 Cipher（IV 从已存的文件里读）。密钥被作废时返回 null。 */
    fun newDecryptCipher(): Cipher? {
        val stored = readWrapped() ?: return null
        return runCatching {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(TAG_BITS, stored.iv),
                )
            }
        }.getOrElse { error ->
            if (error is KeyPermanentlyInvalidatedException ||
                error is UserNotAuthenticatedException ||
                error is UnrecoverableKeyException
            ) {
                clear()
            }
            null
        }
    }

    /** 把 KEK 用硬件密钥加密后落盘。 */
    fun storeWrapped(cipher: Cipher, kek: ByteArray): Boolean = runCatching {
        val ct = cipher.doFinal(kek)
        val iv = cipher.iv ?: return false
        val temp = File(wrapFile.parentFile, wrapFile.name + ".tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(byteArrayOf(iv.size.toByte()))
                out.write(iv)
                out.write(ct)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(wrapFile)) return false
        } finally {
            if (temp.exists()) temp.delete()
        }
        true
    }.getOrDefault(false)

    /** 取出 KEK。[cipher] 必须是生物识别通过后拿到的那个对象。 */
    fun unwrapKek(cipher: Cipher): ByteArray? = runCatching {
        val stored = readWrapped() ?: return null
        cipher.doFinal(stored.ciphertext).takeIf { it.size == KEK_BYTES }
    }.getOrNull()

    /** 关掉生物识别：删掉硬件包裹，并销毁 Keystore 里的那把密钥。 */
    fun clear() {
        runCatching { wrapFile.delete() }
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
        // 密钥没了，描述它的那份档位记录也必须一起没。
        // 留着会制造一个具体的错：用户关掉生物识别后，[enrollmentSpec] 仍会
        // 报出"密钥在独立安全芯片内"—— 描述一把已经不存在的钥匙。
        settings.biometricKeyTier = null
    }

    // ==================== 内部 ====================

    private class Stored(val iv: ByteArray, val ciphertext: ByteArray)

    private fun readWrapped(): Stored? {
        if (!wrapFile.isFile) return null
        val bytes = runCatching { wrapFile.readBytes() }.getOrNull() ?: return null
        if (bytes.size <= 1) return null
        val ivLength = bytes[0].toInt() and 0xFF
        if (ivLength != IV_LENGTH || bytes.size <= 1 + ivLength) return null
        return Stored(
            iv = bytes.copyOfRange(1, 1 + ivLength),
            ciphertext = bytes.copyOfRange(1 + ivLength, bytes.size),
        )
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generateKey()
    }

    /**
     * 按"最严 → 最松"逐级尝试生成密钥，返回第一份被平台接受的。
     *
     * 三档见类 KDoc 的表格。每一档失败都只记下原因、继续下一档 —— 因为
     * "要不到最好的"和"完全不可用"是两件严重程度差很远的事，
     * 而后者会让用户直接失去指纹解锁这个入口。
     */
    private fun generateKey(): SecretKey {
        val ladder = keySpecLadder()
        val reasons = mutableListOf<String>()
        var last: Exception? = null
        for (level in ladder) {
            try {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                generator.init(buildKeySpec(level))
                val key = generator.generateKey()
                // 生成成功 = 平台接受了这一档规格（不支持的要求会抛异常，不会静默忽略）。
                // 顺手记下是哪一档 —— 这是"密钥由哪一级硬件守着"**唯一**的来源，
                // 因为 AES 密钥的属性读不回来（见 StorageNames.Pref.BIOMETRIC_KEY_TIER）。
                // 忘记写这一行的后果很隐蔽：界面上的保护等级说明会永远不出现。
                settings.biometricKeyTier = level.name
                return key
            } catch (e: Exception) {
                // StrongBoxUnavailableException / InvalidAlgorithmParameterException /
                // ProviderException 等都在这里被吸收，退到下一档。
                //
                // **每一档的原因都要留**，不能只留最后一档：全部被拒时，
                // "最后一档的原因"往往是最普通的那条，而真正要知道的是
                // "最严那两档为什么也不行"。只留最后一条，会把一个本来明确的问题
                // 变成一次猜测（本工程 1.1.5 的开发中确实靠这段差一点猜错方向）。
                reasons += "${level.name} → ${e.javaClass.simpleName}: ${e.message}"
                last = e
            }
        }
        throw IllegalStateException(
            "Keystore 拒绝了全部 ${ladder.size} 档密钥规格：\n" + reasons.joinToString("\n"),
            last,
        )
    }

    /**
     * 诊断：逐档尝试生成，回传"这一档在本机上能不能用、被拒的原因是什么"。
     *
     * **为什么值得有这么一个 API**：加固规格是一条[阶梯][keySpecLadder]，
     * 同一份代码在不同设备上会落到不同档位 —— 这正是 ⑤ 的前提。用户在弱设备上
     * 反馈"指纹解锁不可用"时，唯一能问清的办法是**让设备自己回答**哪一档被拒了，
     * 而不是靠"模拟器上跑通了"推断。
     *
     * 两条纪律：
     * - 用**临时别名**生成、生成完立刻删掉 —— 它绝不能碰到用户正在用的那把密钥；
     * - 只做"生成 + 删除"，不做任何加解密，因此不需要用户先通过生物识别。
     */
    fun probeKeySpecLadder(): List<Pair<String, String>> {
        val store = runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }
            .getOrNull()
        return keySpecLadder().map { level ->
            val probeAlias = "$KEY_ALIAS.probe"
            runCatching { store?.deleteEntry(probeAlias) }
            val answer = try {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                generator.init(buildKeySpec(level, probeAlias))
                generator.generateKey()
                "OK"
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            runCatching { store?.deleteEntry(probeAlias) }
            level.name to answer
        }
    }

    /** 本机可用的规格档位，从最严到最松。 */
    private fun keySpecLadder(): List<Hardening> {
        // API 28 以下两档都用不了：setIsStrongBoxBacked 与 setUnlockedDeviceRequired 都是 API 28 的。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return listOf(Hardening.SOFT_REQUIREMENTS)
        return if (strongBoxSupported()) {
            listOf(Hardening.STRONGBOX, Hardening.TEE_HARDENED, Hardening.SOFT_REQUIREMENTS)
        } else {
            listOf(Hardening.TEE_HARDENED, Hardening.SOFT_REQUIREMENTS)
        }
    }

    /** 一档加固要求。 */
    private enum class Hardening(val strongBox: Boolean, val unlockedDevice: Boolean) {
        STRONGBOX(strongBox = true, unlockedDevice = true),
        TEE_HARDENED(strongBox = false, unlockedDevice = true),

        /**
         * 兜底档，也是 1.1.4 及以前一直在用的规格。
         *
         * 留着它是因为"降级但可用"必须优于"不可用"；但它不该是常态 ——
         * API 28 以上走到这一档，说明这台设备的 Keystore 拒绝了
         * `setUnlockedDeviceRequired(true)`。
         */
        SOFT_REQUIREMENTS(strongBox = false, unlockedDevice = false),
    }

    /**
     * @param alias 生成到哪个别名下。默认就是正在用的那个 ——
     *   `probeKeySpecLadder()` 会传一个**临时别名**进来，为的是让诊断
     *   绝不碰用户正在用的那把密钥。
     */
    private fun buildKeySpec(level: Hardening, alias: String = KEY_ALIAS): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        // 刻意**不设** setUserAuthenticationValidityDurationSeconds：默认 -1 表示
        // auth-per-use —— 每一次使用都要重新通过生物识别。设成正数会造出一个
        // "验完 N 秒内免验"的窗口，那正是应用切到后台再切回来时最不该有的东西。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (level.unlockedDevice) builder.setUnlockedDeviceRequired(true)
            if (level.strongBox) builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    /**
     * 把"生成时被平台接受的那一档"折成对外的保护等级。
     *
     * ## 这里以前是读 `KeyInfo` 的，而那条路不存在
     *
     * 旧实现分两条路读 `KeyInfo`：API 31 起用 `getSecurityLevel()`，
     * API 28–30 用 `isInsideSecureHardware()`。写得很周全，**问题是读不到** ——
     * 两者都要先经过 `readKeyInfo()`，而 `KeyFactory.getInstance(key.algorithm,
     * "AndroidKeyStore")` 对 AES 抛 `NoSuchAlgorithmException`：这个 provider
     * 不注册 AES。换句话说，**那两个分支一次都没被执行过**，`guard` 永远是 null。
     *
     * 现在的映射是"我们拿到了哪一档"。[KeyGuard.SOFTWARE] 的含义
     * 也已经改成"没有拿到硬件保护的保证"，而不是"一定是纯软件"。
     */
    private fun guardOf(tier: Hardening): KeyGuard = when (tier) {
        Hardening.STRONGBOX -> KeyGuard.STRONGBOX
        Hardening.TEE_HARDENED -> KeyGuard.TEE
        Hardening.SOFT_REQUIREMENTS -> KeyGuard.SOFTWARE
    }

    companion object {
        internal const val WRAP_FILE_NAME = StorageNames.BIOMETRIC_WRAP_FILE

        const val KEK_BYTES = 32
        private const val KEY_ALIAS = StorageNames.BIOMETRIC_KEY_ALIAS

        /**
         * 上一代的别名，**只用于作废**，绝不能再拿它生成密钥。
         *
         * 它生成时用的是没有 StrongBox、也没有设备未锁定要求的规格（1.1.4 及以前）。
         */
        private const val LEGACY_KEY_ALIAS = StorageNames.BIOMETRIC_KEY_ALIAS_RETIRED_V1

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val IV_LENGTH = 12

        /**
         * 拉起系统认证界面。[cipher] 由 [newEncryptCipher] / [newDecryptCipher] 提供，
         * 认证通过后带着同一个 cipher 回到 [onSuccess]。
         */
        fun prompt(
            activity: FragmentActivity,
            cipher: Cipher,
            title: String,
            subtitle: String,
            onSuccess: (Cipher) -> Unit,
            onFailure: (String) -> Unit,
        ) {
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticated = result.cryptoObject?.cipher
                    if (authenticated == null) {
                        onFailure("认证已通过，但没有拿到加密对象")
                    } else {
                        onSuccess(authenticated)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure(errString.toString())
                }

                // 单次指纹不匹配时系统会继续给机会，这里不能当成"整体失败"，
                // 否则用户手指没放正就会看到一次"解锁失败"。
                override fun onAuthenticationFailed() = Unit
            }

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("取消")
                .build()

            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
                .authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }
    }
}
