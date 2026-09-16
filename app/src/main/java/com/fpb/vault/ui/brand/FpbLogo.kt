package com.fpb.vault.ui.brand

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.graphics.PathParser
import kotlin.math.min

/**
 * FPB 字标（矢量描边版）。
 *
 * ## 为什么直接搬 SVG 的 path 数据
 *
 * 字标是品牌资产，重画一遍必然走形。这里把设计稿里三个字母的 `d` 属性**原样**拿来，
 * 用 [PathParser] 解析成 `android.graphics.Path` 后按 LOGO 的渐变填充。
 * 换 LOGO 时只需要改下面三个常量，不需要重新描一遍字形。
 *
 * ## 渐变为什么用 android.graphics 的 Shader
 *
 * Compose 的 `Brush` 在画布被缩放时，着色器是**按未变换的画布尺寸**生成的：
 * 把字标缩小到标题栏大小时，渐变会跟着一起缩，结果是整块字标只吃到渐变的前一小段
 * （蓝色那一端永远不出现）。这里改用原生的 `LinearGradient`，它定义在
 * **字形自身的坐标空间**里，再由 canvas 变换带着一起缩放，因此无论显示多大，
 * 三个字母上的渐变都完整、且与设计稿一致。
 */
private const val F_PATH = "M80 83h310v72H170v28h180v69H170v65H80Z"

private const val P_PATH =
    "M445 83h184c91 0 141 41 141 111 0 72-50 112-141 112h-94v11h-90Z" +
        "m90 71v82h88c39 0 58-13 58-42 0-27-19-40-58-40Z"

private const val B_PATH =
    "M790.1 325H970.5C1016.7 325 1047.5 320.04 1071.7 308.57C1095.9 297.1 1111.3 278.19 " +
        "1111.3 259.9C1111.3 237.58 1090.4 219.91 1044.75 205.03C1084.35 190.77 1099.75 177.75 " +
        "1099.75 158.53C1099.75 142.72 1086 126.6 1063.45 115.44C1039.8 103.97 1011.75 99.01 " +
        "968.85 99.01H790.1Z" +
        "M872.6 137.76H962.25C1000.75 137.76 1020.55 146.44 1020.55 163.18C1020.55 180.23 " +
        "1000.75 188.91 962.25 188.91H872.6Z" +
        "M872.6 227.66H971.05C1011.2 227.66 1032.1 237.58 1032.1 257.11C1032.1 276.33 " +
        "1011.2 286.25 971.05 286.25H872.6Z"

/** B 在原设计稿里带 `transform="translate(41 0)"`。 */
private const val B_OFFSET_X = 41f

/** B 在原设计稿里带 `paint-order="stroke fill" stroke-width="12"`（等价于向外加粗 6）。 */
private const val B_STROKE_WIDTH = 12f

// 三个字母的墨迹范围（已把 B 的描边外扩算进去），用来裁掉 viewBox 四周的空白。
private const val INK_LEFT = 80f
private const val INK_TOP = 83f
private const val INK_WIDTH = 1078.3f
private const val INK_HEIGHT = 248f

private class Glyph(val path: android.graphics.Path, val stroked: Boolean)

private fun buildGlyphs(): List<Glyph> = listOf(
    Glyph(parsePath(F_PATH, 0f), stroked = false),
    Glyph(parsePath(P_PATH, 0f), stroked = false),
    Glyph(parsePath(B_PATH, B_OFFSET_X), stroked = true),
)

/** 解析失败不抛异常 —— 字标画不出来也不该让整个界面崩掉。 */
private fun parsePath(data: String, extraDx: Float): android.graphics.Path {
    val empty = android.graphics.Path()
    return runCatching {
        val path = PathParser.createPathFromPathData(data) ?: return empty
        val shift = android.graphics.Matrix()
        shift.setTranslate(-INK_LEFT + extraDx, -INK_TOP)
        path.transform(shift)
        path
    }.getOrDefault(empty)
}

/**
 * 画 FPB 字标。[modifier] 决定尺寸，字标按自身比例居中放进去（不会拉伸）。
 *
 * @param colors 渐变停色，默认就是 LOGO 的柠檬绿 → 薄荷青 → 天蓝。
 */
@Composable
fun FpbWordmark(
    modifier: Modifier = Modifier,
    colors: IntArray = DEFAULT_COLORS,
) {
    val glyphs = remember { buildGlyphs() }

    val fillPaint = remember(colors) {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            shader = gradientOf(colors)
        }
    }
    val strokePaint = remember(colors) {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = B_STROKE_WIDTH
            strokeJoin = Paint.Join.ROUND
            shader = gradientOf(colors)
        }
    }

    Canvas(modifier) {
        val scale = min(size.width / INK_WIDTH, size.height / INK_HEIGHT)
        if (scale <= 0f) return@Canvas
        val dx = (size.width - INK_WIDTH * scale) / 2f
        val dy = (size.height - INK_HEIGHT * scale) / 2f

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(dx, dy)
            native.scale(scale, scale)
            glyphs.forEach { glyph ->
                // B 是先描边再填充（SVG 的 paint-order），换个顺序会让它比 F/P 细一圈
                if (glyph.stroked) native.drawPath(glyph.path, strokePaint)
                native.drawPath(glyph.path, fillPaint)
            }
            native.restore()
        }
    }
}

/** 渐变定义在字形自身的坐标空间里 —— 这样画布缩放时渐变会跟着一起缩放。 */
private fun gradientOf(colors: IntArray) = LinearGradient(
    0f,
    0f,
    INK_WIDTH,
    0f,
    colors,
    floatArrayOf(0f, 0.48f, 1f),
    Shader.TileMode.CLAMP,
)

private val DEFAULT_COLORS = intArrayOf(0xFFE6FF4F.toInt(), 0xFF7FE3C2.toInt(), 0xFF559CF0.toInt())
