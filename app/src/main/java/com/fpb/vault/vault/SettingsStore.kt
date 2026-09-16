package com.fpb.vault.vault

import android.content.Context
import android.content.SharedPreferences
import com.fpb.vault.BuildConfig
import com.fpb.vault.session.VaultSession

/**
 * 非敏感的本机设置。
 *
 * ## 为什么这些可以放明文 SharedPreferences
 *
 * 保险库里的**内容**一律加密，但"自动锁定时长""是否禁止截屏"这类偏好不是内容：
 * 它们既不能用来解密任何东西，也不是用户记下来的信息。
 * 把它们也加密会引入一个先有鸡还是先有蛋的问题 —— 自动锁定时长要在**解锁之前**
 * 就生效（解锁页放着不动同样要计时），那时还没有 DEK 可用。
 *
 * 代价要说清楚：`shared_prefs/fpb_settings.xml` 的**文件名与键名**是明文的，
 * 拿到设备读写权限的人能看出"这台设备装了 FPB、开了生物识别"。
 * 这属于"应用存在"这个层面的事实（安装列表里本来就有），
 * 但**不会**泄漏任何一条备忘录的内容。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(StorageNames.PREFS_FILE, Context.MODE_PRIVATE)

    /** 切后台后多久自动锁定。解锁页也会用它计时，因此必须在解锁前可读。 */
    var autoLockMillis: Long
        get() = prefs.getLong(StorageNames.Pref.AUTO_LOCK, VaultSession.DEFAULT_AUTO_LOCK_MILLIS)
        set(value) = prefs.edit().putLong(StorageNames.Pref.AUTO_LOCK, value).apply()

    /**
     * 是否禁止截屏（FLAG_SECURE）。
     *
     * 默认值在调试包与发布包上不同：调试包默认**允许**截屏，
     * 因为在真机上做界面验收必须能截图（FLAG_SECURE 会让截图全黑，
     * 连 uiautomator 的界面树都拿不到）；发布包默认禁止。
     * 用户随时可以在设置里改，改过之后就以用户的选择为准。
     */
    var blockScreenshots: Boolean
        get() = prefs.getBoolean(StorageNames.Pref.BLOCK_SCREENSHOTS, !BuildConfig.DEBUG)
        set(value) = prefs.edit().putBoolean(StorageNames.Pref.BLOCK_SCREENSHOTS, value).apply()

    /** 用户是否主动改过截屏开关（用于区分"默认值"与"用户选择"）。 */
    val screenshotPreferenceTouched: Boolean
        get() = prefs.contains(StorageNames.Pref.BLOCK_SCREENSHOTS)

    /** 是否已把真库 DEK 用 Keystore 硬件密钥包裹好、可以走生物识别解锁。 */
    var biometricEnabled: Boolean
        get() = prefs.getBoolean(StorageNames.Pref.BIOMETRIC, false)
        set(value) = prefs.edit().putBoolean(StorageNames.Pref.BIOMETRIC, value).apply()

    /**
     * 本库是否配置了第二个槽位（诱饵密码）。
     *
     * 键名刻意含糊：`slot_b_configured` 而不是 `decoy_configured`。
     * `shared_prefs` 是明文 XML，键名里直接写 "decoy" 等于把
     * "这台设备还藏着一个假密码" 这个事实摊开给人看。
     *
     * 注意它只是一个**界面提示用的**标志，不是安全边界：
     * 真正的判据永远是密钥文件里 DECOY 槽能否被解开。
     * 因此即便有人改了这个布尔值，也解不出那个库。
     */
    var secondarySlotConfigured: Boolean
        get() = prefs.getBoolean(StorageNames.Pref.SECONDARY_SLOT, false)
        set(value) = prefs.edit().putBoolean(StorageNames.Pref.SECONDARY_SLOT, value).apply()

    /** 当前启用的桌面别名（[LauncherIcon] 的三个候选之一）。 */
    var launcherAlias: String
        get() = prefs.getString(StorageNames.Pref.LAUNCHER_ALIAS, LauncherIcon.ALIAS_FPB) ?: LauncherIcon.ALIAS_FPB
        set(value) = prefs.edit().putString(StorageNames.Pref.LAUNCHER_ALIAS, value).apply()

    /**
     * 外观模式：跟随系统 / 浅色 / 深色（[ThemeMode.OPTIONS] 之一）。
     *
     * 默认 [ThemeMode.SYSTEM]，与"加这个开关之前"的行为完全一致 ——
     * 升级上来的老用户不会因为这次改动被突然换掉配色。
     * 它同样属于"解锁之前就要生效"的偏好（解锁页、引导页都得先知道用什么配色），
     * 所以和自动锁定时长一样留在明文偏好里。
     */
    var themeMode: String
        get() = ThemeMode.normalize(prefs.getString(StorageNames.Pref.THEME_MODE, ThemeMode.SYSTEM))
        set(value) = prefs.edit().putString(StorageNames.Pref.THEME_MODE, ThemeMode.normalize(value)).apply()

    /** 首次启动引导是否已完成。 */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(StorageNames.Pref.ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(StorageNames.Pref.ONBOARDING, value).apply()

    /**
     * 上次**成功导出备份**的时间戳（0 = 从未导出过）。
     *
     * 名字与键名都保留了 "reminder"（写的是"提醒"，实际记的是"导出"）：
     * 变更键名会让所有存量用户的时间戳被静默清零，而那是无害但没必要的一次重置。
     * 键名本身也不值得改 —— `shared_prefs` 是明文 XML，
     * "backup_reminder" 比 "last_exported" 少透露一点"这个库里有东西要备份"。
     *
     * **它的读取端在 [BackupReminder]**：只写不读曾是本次审核发现的一个真缺陷
     * （注释承诺"周期性提醒"，实际一次都不提醒）。
     */
    var lastExportedAt: Long
        get() = prefs.getLong(StorageNames.Pref.BACKUP_REMINDER, 0L)
        set(value) = prefs.edit().putLong(StorageNames.Pref.BACKUP_REMINDER, value).apply()

    fun clearAll() = prefs.edit().clear().commit()

    companion object {
        // 偏好文件名与键名统一放在 [StorageNames]：它们是升级兼容性契约，
        // 改动会让存量用户的设置被静默重置，因此必须由测试钉住字面量。

        /** 设置页展示的自动锁定时长选项。 */
        val AUTO_LOCK_OPTIONS: List<Pair<Long, String>> = listOf(
            15_000L to "15 秒",
            30_000L to "30 秒",
            60_000L to "1 分钟",
            300_000L to "5 分钟",
            1_800_000L to "30 分钟",
        )

        fun labelForAutoLock(millis: Long): String =
            AUTO_LOCK_OPTIONS.firstOrNull { it.first == millis }?.second
                ?: "${millis / 1000} 秒"
    }
}

/**
 * 外观模式的三档取值。
 *
 * 为什么是"三档"而不是一个 `darkMode: Boolean` 开关：
 * 布尔开关必须自己挑一个默认值，而两边的默认值都是错的 ——
 * 默认浅色，夜里打开会被白屏闪一下；默认深色，白天使用像是个坏掉的应用。
 * 真正正确的默认是"跟随系统"，那就得有一个能表达"跟随系统"的第三档。
 *
 * 存的是字符串而不是序号：序号会随枚举重排而错位，
 * 而那意味着用户升级之后配色被莫名其妙换掉。
 */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    /** 设置页里的三档选项（值 → 展示名）。 */
    val OPTIONS: List<Pair<String, String>> = listOf(
        SYSTEM to "跟随系统",
        LIGHT to "浅色",
        DARK to "深色",
    )

    fun labelOf(mode: String): String = OPTIONS.firstOrNull { it.first == mode }?.second ?: "跟随系统"

    /** 把存量/异常值收敛到三档之一，避免脏数据把配色搞成未定义。 */
    fun normalize(mode: String?): String =
        if (mode != null && OPTIONS.any { it.first == mode }) mode else SYSTEM
}
