package com.fpb.vault.vault

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import com.fpb.vault.data.BlobReader
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 视频入库前的元数据探测，以及网格要用的首帧缩略图。
 *
 * ## 为什么不落临时文件
 *
 * 两条路都从**内存里的明文**直接喂给 [MediaMetadataRetriever]（经 [BlobMediaSource]）。
 * 一个把"明文绝不落盘"当卖点的应用，不该为了取一张封面图先写一个 mp4 到磁盘上 ——
 * 那不但留下明文，还会留在应用外部可见的临时目录里。
 *
 * ## 为什么元数据在**入库之后**才探测，而不是先探再存
 *
 * 因为入参是流（视频可能上 GB，不可能先整个读进内存）。流的顺序是单向的：
 * 读完了就得重新拿一个。所以流程是**先存、再开读取器探**：
 * 存完拿到 blobId，立刻用它打开随机访问读取器取元数据；取不出来（不是视频、
 * 或者文件坏了）就把刚存的密文删掉、报"读不出来"。代价是多解一次文件头部，
 * 换来的是"探测失败时留下的垃圾能被自己清掉"。
 *
 * ## 旋转角
 *
 * 手机竖拍的视频常常是 1920×1080 的帧 + 90° 旋转标记。**必须把旋转算进去**，
 * 否则网格上的格子与播放器窗口都会按横屏的比例摆放 —— 表现为视频两边被压扁。
 * 网格与全屏播放页的宽高**都**取自这里：播放器自己那个
 * `setOnVideoSizeChangedListener` 给的是**帧**的尺寸（不保证已摆正），
 * 拿它去摆窗口只会把竖拍视频摆错，所以那条路干脆不挂这个回调。
 * 封面（[firstFrame]）另有一层同类处理，见那里的注释。
 */
object VideoPipeline {

    /** 探测出来的显示尺寸与时长。宽高**已经按旋转角摆正**。 */
    class Meta(val width: Int, val height: Int, val durationMs: Long)

    /**
     * 探测视频元数据。取不出来返回 null（不是视频、容器损坏、或者读不到）。
     */
    fun probe(reader: BlobReader): Meta? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(BlobMediaSource(reader))
            val frameWidth = retriever.intOf(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val frameHeight = retriever.intOf(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (frameWidth <= 0 || frameHeight <= 0) return null

            val duration = retriever.intOf(MediaMetadataRetriever.METADATA_KEY_DURATION).toLong()
            val rotation = retriever.intOf(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            // 90/270 才是"躺着拍的"，180 不改变宽高关系。
            val swapped = rotation == 90 || rotation == 270
            Meta(
                width = if (swapped) frameHeight else frameWidth,
                height = if (swapped) frameWidth else frameHeight,
                durationMs = if (duration > 0) duration else 0L,
            )
        } catch (e: Exception) {
            // 包含 RuntimeException（setDataSource 对非视频内容会抛）、
            // IOException（我们的读取器在密文损坏时抛）。
            // 一律当成"探测不出来"，让上层给出"这个视频读不出来"。
            null
        } finally {
            // 释放的是 retriever（以及它内部分配的解码器）。
            // **读取器不在这里关**：BlobMediaSource 不拥有它（见那里的注释），
            // 关它的是拿着 reader 的那个作用域 —— 两处调用都写成
            // `reader.use { VideoPipeline.xxx(it) }`。
            runCatching { retriever.release() }
        }
    }

    /**
     * 抽首帧当封面。
     *
     * 取的是**约 10% 处**的同步帧而不是第 0 帧：手机视频开头常常是一段黑屏或过曝的
     * 过渡帧，用它当封面会让整个网格看起来像坏掉了。时长未知时才退回第 0 帧。
     *
     * [maxEdge] 控制输出尺寸。用 `getFrameAtTime` 拿到的帧就是视频原始分辨率
     * （4K 视频即 3840×2160，一张 33 MB），必须自己缩 —— `getScaledFrameAtTime`
     * 要 API 27，而本应用 minSdk 是 26。
     */
    fun firstFrame(reader: BlobReader, maxEdge: Int, durationMs: Long = 0L): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(BlobMediaSource(reader))
            val timeUs = if (durationMs > 0) (durationMs / 10) * 1000 else 0L
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            scaleDown(upright(retriever, frame), maxEdge)
        } catch (e: Exception) {
            null
        } finally {
            // 释放的是 retriever（以及它内部分配的解码器）。
            // **读取器不在这里关**：BlobMediaSource 不拥有它（见那里的注释），
            // 关它的是拿着 reader 的那个作用域 —— 两处调用都写成
            // `reader.use { VideoPipeline.xxx(it) }`。
            runCatching { retriever.release() }
        }
    }

    /**
     * 把首帧摆正（拍的时候是竖着的，帧本身却常常是横的）。
     *
     * ## 为什么要自己判一次
     *
     * 竖拍视频的帧是 1920×1080，真正的"竖"记在容器的一个 90° 旋转标记里。
     * `getFrameAtTime` 到底有没有替我们应用这个标记，**各系统版本与各家实现并不一致** ——
     * 有的返回已经转好的竖图，有的原样返回横帧。信一句名不副实的"它会处理"，
     * 表现就是网格里所有竖拍视频都横躺着，而横躺的封面在方形格子里几乎认不出是什么。
     *
     * ## 判据不靠猜，靠**比对**
     *
     * 把返回帧的宽高与元数据里记的原始帧宽高对照：完全一致 ⇒ 没转过，自己转；
     * 已经是转过的形状 ⇒ 不用动。两者都不像 ⇒ 这批元数据本身不可信，
     * **原样交出去**（宁可横躺，也不能拿不准就旋转 —— 那会把本来正确的封面拧斜）。
     */
    private fun upright(retriever: MediaMetadataRetriever, frame: Bitmap): Bitmap {
        val rotation = retriever.intOf(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        if (rotation != 90 && rotation != 270) return frame

        val rawWidth = retriever.intOf(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val rawHeight = retriever.intOf(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        if (frame.width != rawWidth || frame.height != rawHeight) return frame

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = runCatching {
            Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
        }.getOrNull() ?: return frame
        if (rotated !== frame && !frame.isRecycled) frame.recycle()
        return rotated
    }

    /**
     * 按长边等比缩小。已经够小就原样返回（**不做无意义的复制**，
     * 每次覆盖网格都在这里多分配一份 bitmap 是看得见的卡顿来源）。
     */
    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        if (maxEdge <= 0) return source
        val longest = max(source.width, source.height)
        if (longest <= maxEdge || longest <= 0) return source

        val ratio = maxEdge.toFloat() / longest
        val targetWidth = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        if (scaled !== source && !source.isRecycled) source.recycle()
        return scaled
    }

    /** 取一个整数元数据。取不到（键不存在）返回 0 —— 调用方按"未知"处理。 */
    private fun MediaMetadataRetriever.intOf(key: Int): Int =
        extractMetadata(key)?.toIntOrNull() ?: 0
}
