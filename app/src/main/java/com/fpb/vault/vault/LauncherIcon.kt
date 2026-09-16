package com.fpb.vault.vault

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标/名称的伪装。
 *
 * 三个桌面入口在 `AndroidManifest.xml` 里各自是一个 `activity-alias`，
 * 都指向同一个 `MainActivity`，区别只在图标与名称。任何时刻**有且只有一个**处于启用状态。
 *
 * 这不是"换个图标好看"：在被迫交出手机的场景里，
 * 对方看到的是"备忘录"这个名字和一张纸的图标，翻开来也只是个普通备注应用；
 * 而真正的入口藏在——原本那个 FPB 图标已经被关掉了，从桌面上找不到。
 *
 * 用户随时可以在设置里切回来（前提是他还记得这个应用是哪个）。
 */
object LauncherIcon {

    const val ALIAS_FPB = "com.fpb.vault.alias.Fpb"
    const val ALIAS_NOTES = "com.fpb.vault.alias.Notes"
    const val ALIAS_CALC = "com.fpb.vault.alias.Calc"

    val ALL = listOf(ALIAS_FPB, ALIAS_NOTES, ALIAS_CALC)

    data class Option(val alias: String, val label: String, val description: String)

    val OPTIONS = listOf(
        Option(ALIAS_FPB, "FPB", "正常图标"),
        Option(ALIAS_NOTES, "备忘录", "看起来像一个普通记事本"),
        Option(ALIAS_CALC, "计算器", "看起来像系统计算器"),
    )

    fun current(context: Context): String =
        ALL.firstOrNull { isEnabled(context, it) } ?: ALIAS_FPB

    /**
     * 某个别名当前是否处于启用状态。
     *
     * ## 这里必须区分「DEFAULT」与「DISABLED」
     *
     * `getComponentEnabledSetting` 在**从未被显式设置过**时返回
     * `COMPONENT_ENABLED_STATE_DEFAULT`（0），而不是 `ENABLED`（1）。
     * 只跟 `ENABLED` 比较的话，全新安装（清单里 `.alias.Fpb` 写着
     * `android:enabled="true"`、其余两个写着 `false`，但系统里都还是 DEFAULT）
     * 会被判成"三个别名全部未启用"：
     * - [current] 只是**碰巧**蒙对 —— 它兜底返回 [ALIAS_FPB]；
     * - [verify] 则会直接给出假警报，于是它作为"自检判据"是不可信的。
     *
     * 本次审核修正的正是这一点：DEFAULT 时以清单里的 `android:enabled` 为准。
     */
    fun isEnabled(context: Context, alias: String): Boolean {
        val pm = context.packageManager
        val component = ComponentName(context, alias)
        return when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            // DISABLED_USER（用户在系统设置里关的）与 DISABLED_UNTIL_USED 同样是"不启用"。
            // 少列一个就会把"被用户手动停用"当成 DEFAULT 从而误判为启用。
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> false
            // DEFAULT：读清单里那个值。取不到（理论上只在清单被改坏的版本上发生）
            // 就按"只有 FPB 这个明写 enabled=true 的别名是启用的"处理。
            else -> runCatching { pm.getActivityInfo(component, 0).enabled }
                .getOrDefault(alias == ALIAS_FPB)
        }
    }

    /**
     * 切换到 [target]。
     *
     * ## 顺序不能反
     *
     * **先启用目标，再禁用其它**。反过来写的话，两步之间会存在一个
     * "三个别名全部处于禁用状态"的瞬间 —— 那一刻桌面上这个应用彻底消失。
     * 若进程恰好在那一瞬间被系统杀掉（低内存、用户划掉任务），
     * 用户就再也无法从桌面打开它了，只能去系统设置的安装列表里找。
     *
     * 代价是中间有极短的一瞬桌面上可能出现两个图标，但那比"应用消失"轻得多。
     * 用 `DONT_KILL_APP` 是为了不让系统借机重启进程，否则切换图标会把当前界面一起带走。
     */
    fun apply(context: Context, target: String) {
        if (target !in ALL) return
        setState(context, target, enabled = true)
        ALL.filter { it != target }.forEach { setState(context, it, enabled = false) }
    }

    /**
     * 三个别名的启用状态是否符合预期：**恰好** [expected] 一个处于启用。
     *
     * 判据本身早就在，但在本次审核之前**没有任何调用者** —— 于是"别名状态坏了"
     * 这件事只能靠用户发现"桌面上的图标没了"。现在它由
     * `VaultAppState.boot()` 在每次冷启动时跑一次，见那里的 [apply] 修复分支。
     */
    fun verify(context: Context, expected: String): Boolean =
        isEnabled(context, expected) && ALL.filter { it != expected }.none { isEnabled(context, it) }

    private fun setState(context: Context, alias: String, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, alias),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }
}
