package com.fpb.vault.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ==================== 品牌 ====================

/** FPB 字标 LOGO 的渐变三停色。 */
val BrandLime = Color(0xFFE6FF4F)
val BrandMint = Color(0xFF7FE3C2)
val BrandBlue = Color(0xFF559CF0)

/** LOGO 中三个停色的位置（与 SVG 的 offset 一致：0 / 0.48 / 1）。 */
val BrandGradientStops = arrayOf(0f, 0.48f, 1f)

/** 字标用的水平渐变。见 [com.fpb.vault.ui.brand.FpbWordmark]。 */
val BrandGradient = Brush.horizontalGradient(
    colorStops = arrayOf(0f to BrandLime, 0.48f to BrandMint, 1f to BrandBlue),
)

/**
 * 白底上使用的"加深版"品牌色。
 *
 * 原始的柠檬绿与天蓝是给**深色背景上的大字号字标**配的：
 * `#559CF0` 对白底的对比度只有 2.8:1，`#E6FF4F` 更是低到 1.2:1。
 * 直接拿它们做正文或按钮色，白底主题会完全读不清 —— 所以这里按同一色相往下压明度，
 * 让文字对比度回到 WCAG AA 要求的 4.5:1 以上，同时保留渐变配色本身作为品牌元素。
 */
private val BlueInk = Color(0xFF1B6BE0)   // 4.97:1 on white
private val BlueInkDark = Color(0xFF0A2E63)
private val BlueWash = Color(0xFFE3EFFF)
private val MintInk = Color(0xFF0F9C80)
private val MintWash = Color(0xFFCDF5EB)
private val LimeInk = Color(0xFF6E8C00)
private val LimeWash = Color(0xFFEAF7B8)

val LightColors = lightColorScheme(
    primary = BlueInk,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = BlueWash,
    onPrimaryContainer = BlueInkDark,

    secondary = MintInk,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = MintWash,
    onSecondaryContainer = Color(0xFF053A31),

    tertiary = LimeInk,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = LimeWash,
    onTertiaryContainer = Color(0xFF2D3800),

    error = Color(0xFFC0392B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE2DE),
    onErrorContainer = Color(0xFF5C140C),

    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF10151C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10151C),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF5A6574),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFCFE),
    surfaceContainer = Color(0xFFF3F6FA),
    surfaceContainerHigh = Color(0xFFEDF1F7),
    surfaceContainerHighest = Color(0xFFE6EBF2),

    outline = Color(0xFFD6DDE8),
    outlineVariant = Color(0xFFE6EBF2),

    inverseSurface = Color(0xFF232A34),
    inverseOnSurface = Color(0xFFF2F5F9),
    inversePrimary = Color(0xFF9CC6FF),
    scrim = Color(0xFF000000),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF6DB6FF),
    onPrimary = Color(0xFF06203F),
    primaryContainer = Color(0xFF17406E),
    onPrimaryContainer = Color(0xFFD6E9FF),

    secondary = BrandMint,
    onSecondary = Color(0xFF00382C),
    secondaryContainer = Color(0xFF0C4E40),
    onSecondaryContainer = Color(0xFFB9F2E1),

    tertiary = Color(0xFFD6EC5A),
    onTertiary = Color(0xFF2A3300),
    tertiaryContainer = Color(0xFF414D00),
    onTertiaryContainer = Color(0xFFEFFBB8),

    error = Color(0xFFFF8A80),
    onError = Color(0xFF5C140C),
    errorContainer = Color(0xFF7A2018),
    onErrorContainer = Color(0xFFFFDAD4),

    background = Color(0xFF0A0D12),
    onBackground = Color(0xFFE9EEF5),
    surface = Color(0xFF12161C),
    onSurface = Color(0xFFE9EEF5),
    surfaceVariant = Color(0xFF1C222B),
    onSurfaceVariant = Color(0xFF9AA5B4),

    surfaceContainerLowest = Color(0xFF070A0E),
    surfaceContainerLow = Color(0xFF0F1319),
    surfaceContainer = Color(0xFF12161C),
    surfaceContainerHigh = Color(0xFF191F27),
    surfaceContainerHighest = Color(0xFF202832),

    outline = Color(0xFF2C343F),
    outlineVariant = Color(0xFF232A33),

    inverseSurface = Color(0xFFE9EEF5),
    inverseOnSurface = Color(0xFF1A1F26),
    inversePrimary = BlueInk,
    scrim = Color(0xFF000000),
)
