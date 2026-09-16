package com.fpb.vault

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.graphics.toArgb
import com.fpb.vault.ui.AppRoot
import com.fpb.vault.ui.VaultAppState
import com.fpb.vault.ui.theme.DarkColors
import com.fpb.vault.ui.theme.FpbTheme
import com.fpb.vault.ui.theme.LightColors
import com.fpb.vault.vault.ThemeMode

/**
 * 唯一的 Activity。
 *
 * ## 为什么继承 FragmentActivity 而不是 ComponentActivity
 *
 * `androidx.biometric` 的 BiometricPrompt 要求宿主是 FragmentActivity
 * （它靠 FragmentManager 做生命周期与旋转恢复）。这里用的是 androidx 版本
 * 而不是平台的 `android.hardware.biometrics.BiometricPrompt`：
 * 部分国产 ROM 上平台实现不弹框或直接回调错误码，androidx 里带了对这些机型的兜底。
 * FragmentActivity 本身继承自 ComponentActivity，所以 `setContent` 照常可用。
 *
 * ## 为什么带 configChanges
 *
 * 旋转、深色模式切换都不重建 Activity，于是：
 * - 用户正在写的正文不会因为转屏丢掉
 * - 解锁状态不会因为转屏被重置（否则会看到"转一下就要求重新输密码"）
 *
 * 代价是要自己处理配置变化，而 Compose 会自动重组，所以实际上是零成本。
 * 注意其中 `uiMode` 这一项带来的连带责任：系统深色模式变化时 Activity 不重建，
 * **窗口级**的东西（状态栏图标颜色、窗口底色）也得自己跟着改 ——
 * 见 [applyWindowChrome]，它由 AppRoot 在配色变化时调用。
 */
class MainActivity : FragmentActivity() {

    private lateinit var appState: VaultAppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appState = VaultAppState(applicationContext)

        // 第一帧就按设置决定是否禁止截屏，避免"先正常显示一帧、再变黑"这种能被截到的窗口
        applyScreenSecurity(appState.settings.blockScreenshots)

        // 同上：状态栏图标颜色与窗口底色也要在**第一帧之前**就正确。
        // 这里用配置里的系统深浅（非 Composable 判据）先算一次初始值，
        // 之后的每一次变化（用户改设置、系统切深浅）都由 AppRoot 负责跟进。
        applyWindowChrome(resolveDarkNow())

        setContent {
            FpbTheme(themeMode = appState.themeMode) {
                AppRoot(appState)
            }
        }

        appState.boot()
    }

    override fun onStart() {
        super.onStart()
        // 回到前台时立刻判断是否已经超过自动锁定时长
        appState.onForegrounded()
    }

    override fun onStop() {
        super.onStop()
        // 开始计时。只有解锁状态下才计时 —— 停在解锁页不需要锁。
        appState.onBackgrounded()
    }

    /** 冷启动这一刻的配色深浅：设置是显式值就用它，否则看系统配置。 */
    private fun resolveDarkNow(): Boolean =
        when (ThemeMode.normalize(appState.settings.themeMode)) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }

    /**
     * 禁止/允许截屏。
     *
     * `FLAG_SECURE` 同时挡掉三件事：系统截图、屏幕录制、以及最近任务列表里的缩略图
     * （否则划开多任务就能看到内容缩略图）。它挡不住的是用另一台手机拍屏幕 ——
     * 这也是设置里默认开启的原因。
     */
    fun applyScreenSecurity(block: Boolean) {
        if (block) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * 把"当前配色是深还是浅"应用到窗口层。
     *
     * Compose 的配色管不到这两样东西，它们属于 Window：
     *
     * 1. **状态栏/导航栏图标颜色**。XML 主题里的 `windowLightStatusBar` 只在
     *    系统深浅变化时跟着 `values-night` 走；用户把应用单独切成深色、而系统仍是浅色时，
     *    那行配置不会动 —— 结果就是浅色状态栏上画浅色图标，时间与电量直接看不见。
     * 2. **窗口底色**。`@color/vault_bg` 同理只在系统维度生效。它决定的是
     *    Activity 起来那一瞬间（Compose 还没画出第一帧）露出的颜色，
     *    选错就是"切了深色，开屏却白闪一下"。
     *
     * 由 AppRoot 在配色变化时调用，保证与 Compose 侧永远一致。
     */
    fun applyWindowChrome(dark: Boolean) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        val background = if (dark) DarkColors.background else LightColors.background
        window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
    }
}
