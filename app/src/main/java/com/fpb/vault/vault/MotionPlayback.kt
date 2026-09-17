package com.fpb.vault.vault

import kotlin.math.abs

/**
 * 实况照片**播放**的判据。
 *
 * 这里只放"决定要不要播、能不能播"的判断，不放任何 `android.*` ——
 * 于是这两个判据能在普通 JVM 单测里被钉死。
 *
 * 为什么值得单独抽出来：这两条判据各自对应一个**真机上很难复现、但用户一眼就能看出**的体验问题。
 *
 * 1. [shouldAutoPlay]：实况照片以前要用户**先点一下**"实况"胶囊才会播。
 *    对用过系统相册的人来说这是"没反应"——他打开大图就是在等它动起来。
 * 2. [surfaceMatchesVideo]：播放器把解码帧交给自定义 `Surface` 时，
 *    Surface 的尺寸就是画面的目标尺寸。Surface 与影片比例不一致，画面就被**拉伸变形**。
 *    实测现场：1080×2400 的整屏 Surface 放 1080×1920 的影片，纵向被拉长 25%。
 */
object MotionPlayback {

    /**
     * 打开一张图时，要不要**自动**播放它的实况影片。
     *
     * [stoppedByUser] 是被用户按过"停止"的那个 blobId —— 用户已经明确表示"这张别放"，
     * 那么在同一张图上就不再自动开播（否则界面在跟用户的手较劲），
     * 但翻到别的照片时照常自动播放。
     */
    fun shouldAutoPlay(motion: MotionPhoto.Motion?, blobId: String?, stoppedByUser: String?): Boolean {
        if (motion == null) return false
        val id = blobId ?: return false
        return stoppedByUser != id
    }

    /**
     * 影片的显示比例（宽 / 高）。尺寸无效时返回 0，表示"还不知道"。
     *
     * 用宽高回调给的值，**不要**拿 SurfaceView 的尺寸代替：实况影片常常是 16:9，
     * 而同一张照片可能是 4:3，两者本来就不一样 —— 恰恰是这个差值造成了变形。
     */
    fun aspectOf(width: Int, height: Int): Float =
        if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0f

    /**
     * Surface 的尺寸是否已经与影片比例一致（达到 [ASPECT_TOLERANCE] 以内）。
     *
     * 只有一致时才允许 `start()`：播放器一旦开播就会立刻往 Surface 上画帧，
     * 而 Surface 的调整要等下一帧布局 —— 不等的话开头那几帧仍然是变形的。
     */
    fun surfaceMatchesVideo(surfaceWidth: Int, surfaceHeight: Int, videoAspect: Float): Boolean {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return false
        if (!videoAspect.isFinite() || videoAspect <= 0f) return false
        val actual = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        return abs(actual - videoAspect) / videoAspect <= ASPECT_TOLERANCE
    }

    /**
     * 相对容差。
     *
     * Surface 的尺寸只能是整数像素，取整必然带来误差：16:9 放在 1080 宽上得到 607.5 → 607，
     * 相对误差约 0.08%。而真正需要拦下的"整屏铺满"是 25% 量级的。2% 把两者分得很开。
     */
    const val ASPECT_TOLERANCE = 0.02f
}
