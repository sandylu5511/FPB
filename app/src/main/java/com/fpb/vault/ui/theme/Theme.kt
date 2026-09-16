package com.fpb.vault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import com.fpb.vault.vault.ThemeMode

/**
 * 形状体系整体向 iOS 靠拢：小控件（按钮、输入框）10dp、
 * 卡片 16dp、对话框 24dp —— 全圆角、无描边、靠灰阶分层。
 */
private val FpbShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * 当前实际生效的配色是不是深色。
 *
 * ## 为什么不直接用 `isSystemInDarkTheme()`
 *
 * 有了"浅色/深色"这个设置之后，**系统**的深浅与**应用**的深浅就不再是一回事了：
 * 用户在系统是浅色时把应用切成深色，`isSystemInDarkTheme()` 仍然返回 false。
 * 于是任何靠它分支的颜色（典型是 [com.fpb.vault.ui.components.SuccessGreen] 这类语义色）
 * 都会按另一个主题取色 —— 深色底上放一档"浅色主题专用"的深绿，就又是"能看见但读不清"。
 *
 * 所以真正的判据只在这里算一次，其余地方一律读这个局部值。
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * 应用主题。
 *
 * [themeMode] 取 [ThemeMode] 的三档之一：
 * - `SYSTEM`（默认）—— 跟随系统，与加这个开关之前的行为完全一致；
 * - `LIGHT` / `DARK` —— 用户显式指定，**不再看系统**。
 *
 * 配色本身没变，两套仍是同一个品牌渐变派生出来的（见 [LightColors] / [DarkColors]）。
 * 这里多做的只有一件事：把"这次到底是深还是浅"算清楚并往下传。
 */
@Composable
fun FpbTheme(
    themeMode: String = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (ThemeMode.normalize(themeMode)) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }

    CompositionLocalProvider(LocalIsDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            shapes = FpbShapes,
            content = content,
        )
    }
}
