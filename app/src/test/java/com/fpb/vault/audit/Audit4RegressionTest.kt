package com.fpb.vault.audit

import com.fpb.vault.crypto.AeadCipher
import com.fpb.vault.data.ChunkedBlobFormat
import com.fpb.vault.data.RowIds
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.session.VaultSession
import com.fpb.vault.vault.BackupException
import com.fpb.vault.vault.BackupManager
import com.fpb.vault.vault.ImagePipeline
import com.fpb.vault.vault.MediaImportRoute
import com.fpb.vault.crypto.RecoveryCode
import com.fpb.vault.vault.StorageNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 第四轮代码审核的回归测试。
 *
 * 与前几轮（[AuditRegressionTest]、[Audit2RegressionTest]、[Audit3RegressionTest]）
 * 同一套纪律：**每条测试对应一个在修复前会失败的具体缺陷**。缺陷的存在性由失败本身
 * 证明，不由阅读代码证明；修复后再跑同一套测试转绿。
 *
 * 本轮的主线是**视频功能引入的新上限**：它把"一条记录可能有多大"这个量
 * 从几十 MB 抬到了几 GB，而别处有几个上限是按老量级定的。
 * 关键缺陷（备份恢复）就出在这里 —— 两处各自都自洽，凑在一起才炸。
 */
class Audit4RegressionTest {

    // ==================== 1. 备份包的条目上限必须覆盖最大的附件 ====================

    /**
     * 一段附件按分块格式落盘后占多少字节。
     *
     * 用 [ChunkedBlobFormat.headerOf] 算，而不是在测试里手写一遍公式 ——
     * 第一版走查脚本就是因为把**末块按满块**算（`24 + N×(12+块+16)`），
     * 报出了 4 条读起来像"加密写错了"的假失败。公式只该有一处出处。
     */
    private fun storedBytesOf(plainBytes: Long): Long =
        ChunkedBlobFormat.headerOf(plainBytes).expectedStoredBytes()

    /** 整块格式（图片）落盘的体积：nonce + 明文 + 标签。 */
    private fun imageStoredBytes(plainBytes: Long): Long =
        plainBytes + AeadCipher.NONCE_BYTES + AeadCipher.TAG_BYTES

    /**
     * 缺陷：`BackupManager.MAX_ENTRY_BYTES` 是 **256 MiB**，而单段视频的明文上限是 **2 GiB**。
     *
     * 这两个数字各自都合理（一个是"解压炸弹"的闸门，一个是产品决定），
     * 但它们从来没有被放在一起比过 —— 于是**一段 400 MB 的手机 4K 视频**
     * 就足以让整份备份恢复失败。
     *
     * 后果的形状比"导不进来"更糟：**导出照常成功**。用户看到"已导出备份"、
     * 把包放进了网盘，直到某天需要它的时候才发现这份备份根本恢复不了 ——
     * 而那时原始数据大概率已经不在了。对一个"不备份就丢数据"的应用，
     * 这是最坏的一类失败：**能导出的备份、恢复不回来**。
     */
    @Test
    fun `备份恢复的单条目上限必须容得下应用能产出的最大附件`() {
        val largest = storedBytesOf(VaultSession.MAX_VIDEO_PLAINTEXT_BYTES)
        assertTrue(
            "备份恢复的单条目上限 ${BackupManager.MAX_ENTRY_BYTES} 字节" +
                "（${BackupManager.MAX_ENTRY_BYTES / (1024 * 1024)} MB）容不下应用自己允许落盘的" +
                "最大附件 $largest 字节 —— 含视频的备份可以导出、却恢复不了",
            BackupManager.MAX_ENTRY_BYTES >= largest,
        )
    }

    /**
     * 同一个根因的另一半：**总量**预算。
     *
     * `MAX_TOTAL_BYTES` 是 4 GiB。而库里出现视频之后，
     * "一个真实用户的库"很容易超过它：三段 1 GB 的视频（手机随手拍几分钟 4K 就是这个量级）
     * 加两百张 8 MB 的照片 ≈ 4.6 GiB。
     *
     * 这条与上一条必须分开断言：只修单条目上限的话，用户导第二段视频时会撞上总量那条 ——
     * 而两次失败的提示是同一句话，排查时看不出是哪个上限。
     */
    @Test
    fun `备份恢复的总量预算必须容得下几段视频加一批照片的真实库`() {
        val threeVideos = 3 * storedBytesOf(1024L * 1024 * 1024)
        val twoHundredPhotos = 200 * imageStoredBytes(8L * 1024 * 1024)
        val realistic = threeVideos + twoHundredPhotos

        assertTrue(
            "总量预算 ${BackupManager.MAX_TOTAL_BYTES} 字节" +
                "（${BackupManager.MAX_TOTAL_BYTES / (1024 * 1024 * 1024)} GB）容不下" +
                "「3 段 1 GB 视频 + 200 张 8 MB 照片」这样的真实库（约 ${realistic / (1024 * 1024 * 1024)} GB）",
            BackupManager.MAX_TOTAL_BYTES >= realistic,
        )
    }

    /** 反向约束：闸门不能被抬到形同虚设 —— 它挡的是"压缩炸弹"，必须仍然是个有限值。 */
    @Test
    fun `总量预算仍然是个有限且可用的值`() {
        assertTrue("总量预算必须有限", BackupManager.MAX_TOTAL_BYTES in 1..(1L shl 40))
        assertTrue(
            "单条目上限不该超过总量预算",
            BackupManager.MAX_ENTRY_BYTES <= BackupManager.MAX_TOTAL_BYTES,
        )
    }

    /**
     * 闸门本身仍然有效：条目字节数一旦超过上限就中止。
     *
     * 用**注入的上限**来测，而不是用真实上限：真上限是 GB 量级，
     * 为验一句比较就写几百 MB 到磁盘不值得，而这里要确认的恰恰是
     * "比较用的是不是那个上限"。
     */
    @Test
    fun `单个条目超过上限时中止并说明是体积问题`() {
        val staging = Files.createTempDirectory("mixia-audit4-over").toFile()
        try {
            val zip = zipOfAttachment(payloadBytes = 5000)
            try {
                BackupManager.extract(
                    ByteArrayInputStream(zip),
                    staging,
                    maxEntryBytes = 4096,
                    maxTotalBytes = 1L shl 30,
                )
                fail("超过单条目上限却没有中止")
            } catch (expected: BackupException) {
                assertTrue(
                    "异常信息要说清是体积的问题，实际是：${expected.message}",
                    expected.message!!.contains("体积"),
                )
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 反向约束：恰好等于上限必须照常解出（否则上限等于上限减一，正常包会被误拒）。 */
    @Test
    fun `条目恰好等于上限时照常解出`() {
        val staging = Files.createTempDirectory("mixia-audit4-exact").toFile()
        try {
            val zip = zipOfAttachment(payloadBytes = 4096)
            BackupManager.extract(
                ByteArrayInputStream(zip),
                staging,
                maxEntryBytes = 4096,
                maxTotalBytes = 1L shl 30,
            )
            // 只看"没抛异常"是不够的：那样的断言在"什么都没解出来"时也会通过。
            // 逐字节核对该落盘的东西真的落了盘。
            val extracted = File(staging, "attachments").listFiles()
            assertEquals("应当解出恰好一个附件", 1, extracted?.size ?: 0)
            assertEquals(4096L, extracted!!.single().length())
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 总量超限也要中止（两个上限各管一段，不能只留一个）。 */
    @Test
    fun `总量超过预算时中止`() {
        val staging = Files.createTempDirectory("mixia-audit4-total").toFile()
        try {
            val zip = zipOfAttachment(payloadBytes = 8000)
            try {
                BackupManager.extract(
                    ByteArrayInputStream(zip),
                    staging,
                    maxEntryBytes = 1L shl 20,
                    maxTotalBytes = 4096,
                )
                fail("超过总量预算却没有中止")
            } catch (expected: BackupException) {
                assertTrue(expected.message!!.contains("体积"))
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    // ==================== 2. 「MIME 说不清」时的回退规则 ====================

    /**
     * 缺陷：图库里那条"图片读不出来就再按视频试一次"的回退，**只在 `Unreadable` 上生效**。
     *
     * `ImagePipeline.prepare` 的 `TooLarge` 走的是另一支，直接记一句"图片超过 32 MB 上限"
     * 就结束了。而 `prepare` 是**先限量读、后解码判形状**的：一个 MIME 取不到的
     * **大于 32 MiB 的视频**会在"限量读"那一步就返回 TooLarge ——
     * 于是它连一次被当成视频试的机会都没有，用户拿到的是
     * 「图片超过单张 32 MB 的上限，请先在系统相册里裁剪或缩小后再导入」。
     *
     * 讽刺的是，这条回退当初就是为"MIME 说不清"写的（少数第三方提供方不给 type），
     * 而它恰好只在**小视频**上有效 —— 32 MiB 以下的视频小到不构成一个真实场景。
     */
    @Test
    fun `MIME 说不清且图片路径报「过大」时必须再按视频试一次`() {
        val plan = MediaImportRoute.plan(
            mime = null,
            image = ImagePipeline.Preparation.TooLarge(VaultSession.MAX_IMAGE_PLAINTEXT_BYTES),
        )
        assertEquals(
            "MIME 说不清时，图片路径报「过大」也必须再按视频试一次 —— " +
                "视频几乎必然超过图片上限，这条路正是为它准备的",
            MediaImportRoute.Plan.TryVideo,
            plan,
        )
    }

    /** 反向约束：MIME **明确说是图片**时不要再去试视频（那会白白写一遍几百 MB 的文件）。 */
    @Test
    fun `MIME 明确说是图片时报「过大」不必再试视频`() {
        val plan = MediaImportRoute.plan(
            mime = NoteType.IMAGE,
            image = ImagePipeline.Preparation.TooLarge(VaultSession.MAX_IMAGE_PLAINTEXT_BYTES),
        )
        assertTrue(
            "MIME 明确说是图片就不该再走视频那条路（要走完一次完整写盘才轮到探测）",
            plan is MediaImportRoute.Plan.ImageTooLarge,
        )
    }

    /** 既有行为必须保住：MIME 说是视频时直接走视频，不必先试一遍图片。 */
    @Test
    fun `MIME 说是视频时直接走视频路径`() {
        assertEquals(
            MediaImportRoute.Plan.TryVideo,
            MediaImportRoute.plan(NoteType.VIDEO, ImagePipeline.Preparation.Unreadable),
        )
    }

    /** 既有行为必须保住：图片已经解出来时直接用，不再试视频、也不该报错。 */
    @Test
    fun `图片已经解出来时直接用图片`() {
        val prepared = ImagePipeline.Prepared(ByteArray(16), 4, 4)
        val plan = MediaImportRoute.plan(NoteType.IMAGE, ImagePipeline.Preparation.Ready(prepared))
        assertTrue(plan is MediaImportRoute.Plan.UseImage)
        assertTrue("必须原样带出解好的那份字节", (plan as MediaImportRoute.Plan.UseImage).prepared === prepared)
    }

    /** 既有行为必须保住：图片解不出形状（不是图片、或 MIME 误标）时仍然回退去试视频。 */
    @Test
    fun `图片读不出来时仍然回退去试视频`() {
        assertEquals(
            MediaImportRoute.Plan.TryVideo,
            MediaImportRoute.plan(NoteType.IMAGE, ImagePipeline.Preparation.Unreadable),
        )
        assertEquals(
            MediaImportRoute.Plan.TryVideo,
            MediaImportRoute.plan(null, ImagePipeline.Preparation.Unreadable),
        )
    }

    // ==================== 3. 恢复码输入的展示级预校验 ====================

    /**
     * `RecoveryCode.isPlausiblePartialInput` 的注释写着「用于输入框的即时反馈」，
     * 而它在全工程**零调用**：解锁页那个恢复码输入框只做了 `error = null`，
     * 用户在按下按钮之前得不到任何反馈。
     *
     * 这一组钉住的就是那个助手的判据本身（此前它一条测试都没有）。
     * 缺陷的形态是"没有调用者"，无法用断言表达；因此这里的价值是
     * 让"接线之后它到底会拦掉什么"变成可执行的说明，而不是靠读实现。
     */
    @Test
    fun `恢复码的部分输入预校验只拦不可能合法的内容`() {
        // 合法：字母表内的大写/小写/数字，带分隔符、空格，长度在 12 组之内
        listOf(
            "A", "0", "ABCD", "ABCD-EFGH", "abcd-efgh", "ABCD EFGH",
            RecoveryCode.formatForDisplay(RecoveryCode.generate()),
        ).forEach {
            assertTrue("应当被视为可能合法：$it", RecoveryCode.isPlausiblePartialInput(it))
        }

        // 不可能合法：字母表外的字符（Crockford 剔除了 U，I/L/O 由容错映射接管）
        listOf("U", "uuu", "ABCD-UUUU", "恢复码", "ABCD#EFGH", "ABC.DEF").forEach {
            assertFalse("含不可能出现的字符，应当立刻被拦下：$it", RecoveryCode.isPlausiblePartialInput(it))
        }

        // 长度窗口是 60（48 字符 + 12 组连字符），而不是 48：
        // **显示形式本身就是 59 个字符**，所以"比 48 长"绝不能被当成不可能合法 ——
        // 否则用户把带连字符的那一份粘进来会当场被标红，而那份才是他手上真正拿着的东西。
        val code = RecoveryCode.generate()
        assertTrue(RecoveryCode.isPlausiblePartialInput(code))
        assertTrue(RecoveryCode.isPlausiblePartialInput(code.chunked(4).joinToString("-")))
        assertTrue(
            "多打一个字符仍在窗口内，预校验只该拦『不可能』，不该拦『还差几位』",
            RecoveryCode.isPlausiblePartialInput(code + "0"),
        )
        assertFalse(RecoveryCode.isPlausiblePartialInput("A".repeat(200)))
    }

    /** 恰好等于长度窗口的内容必须仍然被接受（防止把窗口写成 `<`）。 */
    @Test
    fun `长度恰好到窗口上沿时仍被接受`() {
        val maxLength = RecoveryCode.CANONICAL_LENGTH + RecoveryCode.GROUP_COUNT
        assertTrue(RecoveryCode.isPlausiblePartialInput("A".repeat(maxLength)))
        assertFalse(RecoveryCode.isPlausiblePartialInput("A".repeat(maxLength + 1)))
    }

    // ==================== 4. 记录规模的注释与事实 ====================

    /**
     * `NotePayload.MAX_VIDEOS` 的注释声称"一条记录可能占多大"的上界是
     * 别处（**存储占用展示、孤儿清扫、备份包的体量**）都默认依赖的一个量。
     *
     * 第三项在修复前是假的：备份包的总量预算是 4 GiB，而 20 条 × 2 GiB = 40 GiB。
     * 这条断言把"注释里的那句话"变成可执行的检查 —— 一条记录名下的附件总量
     * 必须真的落在备份预算之内，而不是"理论上应该"。
     */
    @Test
    fun `一条记录名下的附件总量必须落在备份的预算之内`() {
        val oneRecordWorstCase = NotePayload.MAX_VIDEOS * storedBytesOf(VaultSession.MAX_VIDEO_PLAINTEXT_BYTES)
        assertTrue(
            "一条记录最坏可占 $oneRecordWorstCase 字节，而备份总量预算是 " +
                "${BackupManager.MAX_TOTAL_BYTES} 字节 —— 这样的备份恢复不了。" +
                "要么抬高预算，要么下调 MAX_VIDEOS，不能两处各说各话",
            BackupManager.MAX_TOTAL_BYTES >= oneRecordWorstCase,
        )
    }

    // ==================== 5. 恢复时的三道闸门必须说三种不同的话 ====================

    /**
     * 缺陷：三处失败共用同一句「备份包解压后体积异常，已中止」。
     *
     * 而这三处的成因**完全不同**：单条目超限与总量超限是"包有问题"
     * （构造出来撑爆闸门的），可用空间不足是"机器有问题"（包完全正常）。
     *
     * 用户拿到后一句时，会去重新下载、换个网盘再传一遍 —— 怎么试都不会成功，
     * 因为这份包本来就没有问题，缺的是磁盘空间。**说错归因比不说更糟**：
     * 它把人送去做一件注定失败的事。
     */
    @Test
    fun `可用空间不足时的提示不能说成是包坏了`() {
        val staging = Files.createTempDirectory("mixia-audit4-space").toFile()
        try {
            val zip = zipOfAttachment(payloadBytes = 8000)
            try {
                BackupManager.extract(
                    ByteArrayInputStream(zip),
                    staging,
                    maxEntryBytes = 1L shl 20,
                    maxTotalBytes = 1L shl 30,
                    usableBytes = 4096,
                )
                fail("可用空间不足却没有中止")
            } catch (expected: BackupException) {
                val message = expected.message!!
                assertTrue("要指明是空间问题，实际是：$message", message.contains("可用空间不足"))
                // 必须点明"包没问题"：否则用户的第一反应就是换个包再试。
                assertTrue("要说明包本身没问题，实际是：$message", message.contains("没有问题"))
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 反向约束：空间够的时候不能误拒（`usableBytes` 是比较用的下限，不是"必须有余量"）。 */
    @Test
    fun `可用空间足够时照常解出`() {
        val staging = Files.createTempDirectory("mixia-audit4-space-ok").toFile()
        try {
            BackupManager.extract(
                ByteArrayInputStream(zipOfAttachment(payloadBytes = 4096)),
                staging,
                maxEntryBytes = 1L shl 20,
                maxTotalBytes = 1L shl 30,
                usableBytes = 1L shl 20,
            )
            // 只看"没抛异常"不够：什么都没解出来时它也会通过。
            assertEquals(1, File(staging, "attachments").listFiles()?.size ?: 0)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 三道闸门各自的话术必须互不相同。
     *
     * 这条单独立一个用例，是因为"能不能定位到是哪个闸门"**只由文案本身承载** ——
     * 代码里分得再清楚，只要三句话一样，用户看到的仍然是一个纯运气问题。
     */
    @Test
    fun `三道闸门各自报出不同的话`() {
        val staging = Files.createTempDirectory("mixia-audit4-words").toFile()
        try {
            fun messageOf(block: () -> Unit): String = try {
                block()
                fail("应当中止却没有中止")
                ""
            } catch (e: BackupException) {
                e.message ?: ""
            }

            val single = messageOf {
                BackupManager.extract(
                    ByteArrayInputStream(zipOfAttachment(payloadBytes = 5000)),
                    staging,
                    maxEntryBytes = 4096,
                    maxTotalBytes = 1L shl 30,
                )
            }
            val total = messageOf {
                BackupManager.extract(
                    ByteArrayInputStream(zipOfAttachment(payloadBytes = 8000)),
                    staging,
                    maxEntryBytes = 1L shl 20,
                    maxTotalBytes = 4096,
                )
            }
            val space = messageOf {
                BackupManager.extract(
                    ByteArrayInputStream(zipOfAttachment(payloadBytes = 8000)),
                    staging,
                    maxEntryBytes = 1L shl 20,
                    maxTotalBytes = 1L shl 30,
                    usableBytes = 4096,
                )
            }

            assertEquals("三条话术应当互不相同", 3, setOf(single, total, space).size)
            assertTrue("单条目那条要说清是「单个文件」：$single", single.contains("单个文件"))
            assertTrue("总量那条要说清是「总体积」：$total", total.contains("总体积"))
            assertTrue("空间那条要说清是「可用空间」：$space", space.contains("可用空间"))
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 清单条目要单独按 [BackupManager] 里那个 64 KiB 封顶，**不能跟着附件上限走**。
     *
     * 正常流程里 `inspect` 会先拦掉过大的清单，但 `restore` 里并不调用 `inspect`
     * （界面上的顺序是"选包 → inspect → 用户确认 → restore"，两步各自重新打开输入流）。
     * 所以"绕过确认页直接恢复"这条路够不到那道闸门。
     *
     * 而清单是**唯一会被整体读进内存**的条目（其余条目解压时只是流过缓冲区），
     * 不封顶就等于留了一个把堆撑爆的口子。
     */
    @Test
    fun `清单条目按 64 KiB 封顶而不是按附件上限`() {
        val staging = Files.createTempDirectory("mixia-audit4-manifest").toFile()
        try {
            try {
                // 附件上限故意给到 1 MiB：如果清单也被它管，200 KB 的清单就会一路通过。
                BackupManager.extract(
                    ByteArrayInputStream(zipOfManifest(payloadBytes = 200 * 1024)),
                    staging,
                    maxEntryBytes = 1L shl 20,
                    maxTotalBytes = 1L shl 30,
                )
                fail("超过 64 KiB 的清单没有被拦下")
            } catch (expected: BackupException) {
                assertTrue("异常信息要说清是体积问题：${expected.message}", expected.message!!.contains("体积"))
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** 反向约束：正常大小的清单（一百多字节）必须照常解出，否则上限就成了"什么都导不进来"。 */
    @Test
    fun `正常大小的清单照常解出`() {
        val staging = Files.createTempDirectory("mixia-audit4-manifest-ok").toFile()
        try {
            BackupManager.extract(
                ByteArrayInputStream(zipOfManifest(payloadBytes = 160)),
                staging,
                maxEntryBytes = 1L shl 20,
                maxTotalBytes = 1L shl 30,
            )
            assertEquals(160L, File(staging, StorageNames.BACKUP_MANIFEST).length())
        } finally {
            staging.deleteRecursively()
        }
    }

    // ==================== 内部 ====================

    /**
     * 造一个只含一个附件条目的合法备份包。
     *
     * 附件名必须是 32 位十六进制（`extract` 会拒绝别的形状），
     * 这里用 [RowIds.random] 而不是写死一个串。
     */
    private fun zipOfAttachment(payloadBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("attachments/${RowIds.random()}"))
            val buffer = ByteArray(1024)
            var written = 0
            while (written < payloadBytes) {
                val size = minOf(buffer.size, payloadBytes - written)
                zip.write(buffer, 0, size)
                written += size
            }
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * 造一个只含清单条目的备份包。
     *
     * 条目名用 [StorageNames.BACKUP_MANIFEST] 而不是写死 `"fpb-backup.txt"`：
     * 写死的话，将来这个名字改了，测试会拿着**旧名字**继续跑 —— 它不再是被
     * `extract` 认作清单的那一条，于是"清单有没有被封顶"这件事就悄悄不再被验证，
     * 而测试仍然是绿的。
     */
    private fun zipOfManifest(payloadBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(StorageNames.BACKUP_MANIFEST))
            zip.write(ByteArray(payloadBytes) { 'x'.code.toByte() })
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
