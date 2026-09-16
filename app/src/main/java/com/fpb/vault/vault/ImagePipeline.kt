package com.fpb.vault.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.fpb.vault.session.VaultSession
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片入库前的准备与出库后的解码。
 *
 * ## 存储策略：原图直存（用户明确要求）
 *
 * 用户选择"不压缩、存原图"，于是入库的字节就是相册交出来的原始字节，
 * **不重编码、不缩放**。带来的两个后果必须在这里消化：
 *
 * 1. **EXIF 方向。** 原图（尤其相机直出的 JPEG）的方向信息在 EXIF 里，
 *    而 `BitmapFactory.decodeByteArray` 不读 EXIF —— 直接显示会躺倒。
 *    因此 [decode] 在解出像素后按原始字节的 EXIF 方向补一次旋转，
 *    [prepare] 里给出的宽高也按同一规则先换算好。
 *    （顺带的：EXIF 里的拍摄位置等元数据会随原图一起留在库里，这是
 *    "原图存储"这个选择本身的代价，界面上会如实告知。）
 *
 * 2. **解码内存。** 1200 万像素的原图解出来是 48 MB 的 bitmap。
 *    展示侧（[decode]）默认把长边压到 [DISPLAY_EDGE] 再给界面 ——
 *    存的是原图，看的是按需缩小的副本，两者并不矛盾。
 */
object ImagePipeline {

    /** 展示用的长边上限。原图解码按这个尺寸采样，避免全屏看一张 48 MP 照片直接把内存打爆。 */
    const val DISPLAY_EDGE = 4096

    class Prepared(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * [prepare] 的三种结局。
     *
     * 从前这里返回的是 `Prepared?`，于是"这张图太大"与"这张图读不出来"
     * 对调用方是同一件事 —— 用户能拿到的唯一解释是"读不出来，换一张试试"，
     * 而他手里那张 40 MB 的全景图其实**换一百张也是同一个结果**。
     * 更糟的是这条 `null` 掩盖的根因：读取时根本没有上限，
     * 大图会先把内存吃光（见 [readCapped] 的说明）。
     */
    sealed interface Preparation {
        data class Ready(val prepared: Prepared) : Preparation

        /** 取不到流、不是图片、或读取过程出错。 */
        data object Unreadable : Preparation

        /** 字节数超过 [limitBytes]，在读完之前就已放弃。 */
        data class TooLarge(val limitBytes: Int) : Preparation {
            /**
             * 给用户看的一句话。
             *
             * 放在这里而不是各调用点各写一遍：上限值只应有一处出处，
             * 否则改了上限、忘了改文案，用户看到的数字就和实际行为对不上了。
             */
            val message: String
                get() = "图片超过单张 ${limitBytes / (1024 * 1024)} MB 的上限，" +
                    "请先在系统相册里裁剪或缩小后再导入"
        }
    }

    /**
     * 把 [uri] 指向的图片**原样**读进内存。
     *
     * 只做三件事：读字节、解码边界确认"这确实是一张图"、把 EXIF 方向换算进宽高。
     * 全程在调用方提供的后台线程上执行。
     */
    fun prepare(context: Context, uri: Uri): Preparation = runCatching {
        val stream = context.contentResolver.openInputStream(uri)
            ?: return@runCatching Preparation.Unreadable
        // 带上限地读。**上限必须与写入侧的 VaultSession.MAX_IMAGE_PLAINTEXT_BYTES 一致**：
        // 那边是在字节已经全部进内存之后才校验的，挡不住 OOM；
        // 这里如果放行，那些字节连"被拒绝"的机会都没有就已经把堆吃掉了。
        val bytes = stream.use { it.readCapped(VaultSession.MAX_IMAGE_PLAINTEXT_BYTES) }
            ?: return@runCatching Preparation.TooLarge(VaultSession.MAX_IMAGE_PLAINTEXT_BYTES)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching Preparation.Unreadable

        val orientation = orientationOf(bytes)
        val swap = orientation in intArrayOf(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
        )
        Preparation.Ready(
            Prepared(
                bytes = bytes,
                width = if (swap) bounds.outHeight else bounds.outWidth,
                height = if (swap) bounds.outWidth else bounds.outHeight,
            ),
        )
    }.getOrElse { Preparation.Unreadable }

    /**
     * 从解密后的字节还原成可直接显示的 bitmap。
     *
     * [maxEdge] 控制采样率（缩略图走小值）；默认 [DISPLAY_EDGE] 兜住内存。
     * EXIF 方向在解码后补上 —— 原图存储后这是唯一能把照片"转正"的地方。
     */
    fun decode(bytes: ByteArray, maxEdge: Int = DISPLAY_EDGE): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return null
        applyOrientation(decoded, orientationOf(bytes))
    }.getOrNull()

    // ==================== 内部 ====================

    /** EXIF 方向读取失败（PNG、GIF 等无 EXIF 的格式）按"不需要转"处理。 */
    private fun orientationOf(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (max(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return source
        }
        val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (result !== source && !source.isRecycled) source.recycle()
        return result
    }

    /** 仅供界面展示"占多大"。 */
    fun describeSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes / 1024.0).roundToInt()} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /** 缩略图目标长边。列表卡片上显示约 72dp，取 3 倍密度足够。 */
    const val THUMBNAIL_EDGE = 240
}
