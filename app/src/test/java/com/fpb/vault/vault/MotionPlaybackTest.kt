package com.fpb.vault.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MotionPlayback] 的守护测试。
 *
 * 这两组判据各自守着一次真机验收里被发现的问题：
 *
 * - **打开大图不自动播**，得用户先点一下"实况"胶囊才动 —— 对用过系统相册的人来说，
 *   打开大图就是在等它动起来，不动的第一反应是"实况丢了"。
 * - **播放时画面被拉伸变形** —— 1080×2400 的整屏 Surface 放 1080×1920 的实况影片，
 *   纵向拉长 25%。根因是 Surface 的尺寸就是画面的目标尺寸，与缩放模式无关。
 *
 * 两个问题都发生在真机播放路径上，难以在 JVM 里重现**画面**；
 * 但"要不要播""现在能不能播"这两个判断是纯逻辑，正好可以在这里钉死 ——
 * 它们是上面两个现象的**唯一判据**，判据错了，画面就一定错。
 */
class MotionPlaybackTest {

    // ==================== 自动播放：打开即播 ====================

    @Test
    fun `实况照片打开就自动播放_不需要用户再点一次`() {
        assertTrue(
            "认得出是实况就该直接播，这正是用户要的『打开就能看到它动』",
            MotionPlayback.shouldAutoPlay(motion(), blobId = "blob-1", stoppedByUser = null),
        )
    }

    @Test
    fun `普通照片不自动播放`() {
        assertFalse(
            MotionPlayback.shouldAutoPlay(null, blobId = "blob-1", stoppedByUser = null),
        )
    }

    @Test
    fun `没有当前页时不自动播放`() {
        assertFalse(
            "空列表/越界时没有当前 blob，不能去取影片",
            MotionPlayback.shouldAutoPlay(motion(), blobId = null, stoppedByUser = null),
        )
    }

    @Test
    fun `用户按过停止的那一张不再自动播放`() {
        assertFalse(
            "用户已经明确表示这张别放，界面不能立刻又给他放一遍",
            MotionPlayback.shouldAutoPlay(motion(), blobId = "blob-1", stoppedByUser = "blob-1"),
        )
    }

    @Test
    fun `停止过的是另一张时_当前这张照常自动播放`() {
        assertTrue(
            "『停止』只对按下的那一张有效，翻页后新的一张仍然要自动播",
            MotionPlayback.shouldAutoPlay(motion(), blobId = "blob-2", stoppedByUser = "blob-1"),
        )
    }

    // ==================== 不拉伸：Surface 与影片同比例才开播 ====================

    @Test
    fun `Surface 与影片同比例时算匹配`() {
        assertTrue(
            MotionPlayback.surfaceMatchesVideo(1080, 1920, MotionPlayback.aspectOf(1080, 1920)),
        )
    }

    @Test
    fun `Surface 被铺满整屏时不算匹配_这正是拉伸的现场`() {
        val video = MotionPlayback.aspectOf(1080, 1920)
        assertFalse(
            "1080×2400 的整屏放 1080×1920 的影片，纵向被拉长 1-1080x1920/2400 ≈ 25%",
            MotionPlayback.surfaceMatchesVideo(1080, 2400, video),
        )
    }

    @Test
    fun `横屏影片同样按这一条判据`() {
        val video = MotionPlayback.aspectOf(1920, 1080)
        assertTrue(MotionPlayback.surfaceMatchesVideo(1920, 1080, video))
        assertFalse(
            "同一段横屏影片放进 4:3 的 Surface 一样会变形",
            MotionPlayback.surfaceMatchesVideo(1920, 1440, video),
        )
    }

    @Test
    fun `整数取整带来的误差仍然算匹配`() {
        // 16:9 铺到 1080 宽：高 607.5 → 取整 607，相对误差约 0.08%
        assertTrue(
            "尺寸只能是整数像素，取整误差不能把正常情况判成不匹配（否则影片永远不播）",
            MotionPlayback.surfaceMatchesVideo(1080, 607, 16f / 9f),
        )
    }

    @Test
    fun `尺寸还没量出来时不算匹配`() {
        assertFalse(
            "surfaceChanged 还没来过，不知道 Surface 多大，不能开播",
            MotionPlayback.surfaceMatchesVideo(0, 0, 16f / 9f),
        )
        assertFalse(MotionPlayback.surfaceMatchesVideo(1920, 0, 16f / 9f))
        assertFalse(MotionPlayback.surfaceMatchesVideo(0, 1080, 16f / 9f))
    }

    @Test
    fun `影片比例未知时不算匹配`() {
        assertFalse(
            "尺寸回调还没给比例，等它给 —— 兜底由 startIfReady(force) 负责",
            MotionPlayback.surfaceMatchesVideo(1920, 1080, videoAspect = 0f),
        )
    }

    // ==================== 比例换算 ====================

    @Test
    fun `尺寸回调给出的比例就是宽除以高`() {
        assertEquals(1080f / 1920f, MotionPlayback.aspectOf(1080, 1920), 1e-6f)
        assertEquals(1920f / 1080f, MotionPlayback.aspectOf(1920, 1080), 1e-6f)
    }

    @Test
    fun `尺寸无效时比例为 0_表示还不知道`() {
        assertEquals(0f, MotionPlayback.aspectOf(0, 1920), 1e-6f)
        assertEquals(0f, MotionPlayback.aspectOf(1080, 0), 1e-6f)
        assertEquals(0f, MotionPlayback.aspectOf(0, 0), 1e-6f)
    }

    /** 一段最小可用的影片段（这里只用来代表"有影片"这件事，字节内容与播放无关）。 */
    private fun motion() = MotionPhoto.Motion(videoOffset = 4_000, videoLength = 2_000)
}
