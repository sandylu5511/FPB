package com.fpb.vault.vault

import com.fpb.vault.session.VaultSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容量文案（[ImagePipeline.describeSize]）。
 *
 * 这是**唯一**一处把字节数翻译成人话的地方：设置页的占用明细、导入完成的提示、
 * 导出成功的提示都走它。所以每一档都要有一条断言 —— 判"该用哪个单位"的分支
 * 写错了，用户看到的不是报错，而是一个**看着挺合理、其实差了一千倍**的数字。
 */
class SizeDescriptionTest {

    /**
     * B / KB 两档的边界。
     *
     * **KB 是"就近取整"，不是截断**（实现用 `roundToInt()`）。所以 1536 B（= 1.5 KB）
     * 显示成「2 KB」而不是「1 KB」。这一点必须钉进断言：曾经这里写的是「1 KB」，
     * 是一条**永远红**的断言 —— 而它当时被漏跑了，所以没人发现自己写错了预期。
     *
     * 另有一条**已知的观感怪相**：每档的上界会顶到"下档整整一分"的读数 ——
     * 1048575 B 显示成「1024 KB」、1 GiB−1 显示成「1024.0 MB」。
     * 这不是 bug，是"先按区间选单位、再换算"的必然结果；改它要动用户可见文案，
     * 因此这里**把现状钉住**，而不是留一条谁也不知道是意外还是故意的空档。
     */
    @Test
    fun `四个档位各自取对的单位`() {
        // B 档
        assertEquals("0 B", ImagePipeline.describeSize(0))
        assertEquals("1023 B", ImagePipeline.describeSize(1023))

        // KB 档：就近取整
        assertEquals("1 KB", ImagePipeline.describeSize(1024))
        assertEquals("1 KB", ImagePipeline.describeSize(1535)) // 1.4990… → 1
        assertEquals("2 KB", ImagePipeline.describeSize(1536)) // 1.5    → 2
        assertEquals("1024 KB", ImagePipeline.describeSize(1024L * 1024 - 1)) // 档位上界

        // MB 档
        assertEquals("1.0 MB", ImagePipeline.describeSize(1024L * 1024))
        assertEquals("1024.0 MB", ImagePipeline.describeSize(1024L * 1024 * 1024 - 1))

        // GB 档（本次新增的第四档）
        assertEquals("1.0 GB", ImagePipeline.describeSize(1024L * 1024 * 1024))
    }

    /**
     * 视频上限**必须**落在 GB 档里。
     *
     * 只有 B/KB/MB 三档时，一段 2 GiB 的视频会显示成「2048.0 MB」——
     * 用户得自己数位数才知道那是 2 GB。而"占用明细"那一栏里，
     * 视频恰恰是最可能出现 GB 级读数的项。
     *
     * 这条断言把"上限值在那个单位下可读"钉住：以后若把视频上限抬到几 GB
     * 而这里退回三档，它会立刻变红。
     */
    @Test
    fun `视频上限在 GB 档里读得出来`() {
        val text = ImagePipeline.describeSize(VaultSession.MAX_VIDEO_PLAINTEXT_BYTES)
        assertTrue("视频上限应当以 GB 显示，实际是：$text", text.endsWith("GB"))
        assertEquals("2.0 GB", text)
    }

    /** 备份总量预算（≈80 GiB）也必须落在 GB 档 —— 否则设置页会给出五位数的 MB。 */
    @Test
    fun `备份总量预算在 GB 档里读得出来`() {
        val text = ImagePipeline.describeSize(BackupManager.MAX_TOTAL_BYTES)
        assertTrue("总量预算应当以 GB 显示，实际是：$text", text.endsWith("GB"))
    }
}
