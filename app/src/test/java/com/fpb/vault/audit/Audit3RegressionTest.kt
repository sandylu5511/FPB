package com.fpb.vault.audit

import com.fpb.vault.vault.BackupException
import com.fpb.vault.vault.BackupManager
import com.fpb.vault.vault.BackupReminder
import com.fpb.vault.vault.ImagePipeline
import com.fpb.vault.vault.readCapped
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 第三轮代码审核的探测测试。
 *
 * 与前两轮（[AuditRegressionTest]、[Audit2RegressionTest]）同一套纪律：
 * **每条测试对应一个在修复前会失败的具体缺陷**。缺陷的存在性由失败本身证明，
 * 不由阅读代码证明；修复后再跑同一套测试转绿。
 *
 * 本轮三处缺陷的共同点，是它们都属于"**写在注释里的承诺，代码里没有对应实现**"：
 *
 * | 承诺出处 | 承诺内容 | 实际 |
 * |---|---|---|
 * | `BackupManager` / `SettingsStore` / `Common` 共四处 | 设置页会周期性提醒导出备份 | 时间戳只写不读，一次都不提醒 |
 * | `ImagePipeline.prepare` 的调用点 | 图片读不出来会告知用户 | 读取无上限，大图先 OOM；且"太大"与"读不出来"同一条提示 |
 * | `LauncherIcon.verify` | 供自检别名状态是否正常 | 没有任何调用者，且判据本身把 DEFAULT 当成 DISABLED |
 *
 * 前两处都能在 JVM 上断言，因此放在这里；第三处需要 `PackageManager`，
 * 只能在真机/模拟器上验证（见 `dist/evidence/audit3-20260916/`）。
 */
class Audit3RegressionTest {

    private val day = 24L * 60 * 60 * 1000

    /**
     * 记录"从底层流里读走了多少字节"。
     *
     * 这是本轮最关键的一个观测手段：**超限之后有没有继续读**是肉眼看不见的，
     * 而它正是"读取无上限"与"读取即封顶"的全部区别。
     */
    private class CountingStream(private val source: InputStream) : InputStream() {
        var read: Long = 0L
            private set

        override fun read(): Int = source.read().also { if (it >= 0) read++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            source.read(b, off, len).also { if (it > 0) read += it }

        override fun available(): Int = source.available()

        override fun close() = source.close()
    }

    // ==================== 1. 「该导出备份了」的判定 ====================

    /**
     * 缺陷：`SettingsStore.lastBackupReminderAt` **只有写入端没有读取端** ——
     * 导出成功时记时间戳，但全应用没有任何一处在读它来触发提醒。
     * 于是 `BackupManager`、`SettingsStore`、`Common` 里四处注释都在承诺
     * "设置页会周期性提醒导出备份"，而实际上一次都不会提醒。
     *
     * 对一个没有 INTERNET 权限、没有任何自动上传、也没有云端的应用，
     * 这条承诺的落空**就是数据丢失本身**：用户以为应用会催他备份。
     */
    @Test
    fun `有内容却从未导出过时必须立刻提醒`() {
        assertEquals(
            BackupReminder.Due.NEVER_EXPORTED,
            BackupReminder.eval(
                lastExportedAt = 0L,
                hasContent = true,
                now = 1_700_000_000_000L,
            ),
        )
    }

    /**
     * 空库不提醒。
     *
     * 反向约束：如果"从未导出过"被无条件当成需要提醒，那么刚建完库、
     * 还没记任何东西的用户一进来就被催备份 —— 提醒会立刻贬值，
     * 用户学会无视它之后，真正需要它的那一刻就没人看了。
     */
    @Test
    fun `空库即便从未导出过也不提醒`() {
        val now = 1_700_000_000_000L
        assertEquals(BackupReminder.Due.NONE, BackupReminder.eval(0L, hasContent = false, now = now))
        assertEquals(
            BackupReminder.Due.NONE,
            BackupReminder.eval(now - 300 * day, hasContent = false, now = now),
        )
    }

    /**
     * 七天整算到期，差一毫秒不算。
     *
     * 边界必须被钉住：这里写 `>` 还是 `>=` 只差一毫秒，但它是"提醒会不会
     * 在恰好第七天出现"的唯一判据，而这类边界在重构中最容易被无声改掉。
     */
    @Test
    fun `恰好七天算过期_差一毫秒不算`() {
        val base = 1_700_000_000_000L
        assertEquals(
            BackupReminder.Due.NONE,
            BackupReminder.eval(base, hasContent = true, now = base + BackupReminder.INTERVAL_MILLIS - 1),
        )
        assertEquals(
            BackupReminder.Due.STALE,
            BackupReminder.eval(base, hasContent = true, now = base + BackupReminder.INTERVAL_MILLIS),
        )
    }

    /**
     * 系统时间被往回拨（或时区被改）时差值为负 —— 不能提醒。
     *
     * 漏一次提醒的代价，远小于"每次打开都弹一条"：后者会让用户直接无视整个提醒机制。
     */
    @Test
    fun `系统时间回拨或相同时不提醒`() {
        val base = 1_700_000_000_000L
        assertEquals(BackupReminder.Due.NONE, BackupReminder.eval(base, true, base - day))
        assertEquals(BackupReminder.Due.NONE, BackupReminder.eval(base, true, base))
        assertEquals(BackupReminder.Due.NONE, BackupReminder.eval(base, true, 0L))
    }

    // ==================== 2. 读取封顶 ====================

    /**
     * 缺陷：`ImagePipeline.prepare` 用 `stream.copyTo(out)` 无上限地把整张图读进内存，
     * 而 `VaultSession.putImage` 那条 32 MiB 的校验在**这之后**才执行。
     *
     * 也就是说：内存已经花出去了，校验再准也拦不住 `OutOfMemoryError`。
     * 45 MB 的全景图、上百 MB 的扫描件都足以把低端机的堆榨干。
     *
     * 这条测试钉的是"读取这一步本身就封顶"：超限之后**不能再继续读**。
     * 用 `CountingStream` 量的是"从来源里读走了多少" —— 这正是内存开销本身。
     */
    @Test
    fun `超过上限时立刻放弃_不再把剩余字节读进内存`() {
        val source = CountingStream(ByteArrayInputStream(ByteArray(8 * 1024 * 1024)))
        assertNull("超出上限必须返回 null，而不是先读完再判断", source.readCapped(1024))

        // 读走的不超过"一个读取块 + 上限"的量级（实现按 64 KiB 一块读）。
        // 修复前这里是 8 MiB —— 整份数据都进了内存。
        assertTrue("超限后仍读走了 ${source.read} 字节", source.read <= 128 * 1024L)
        assertTrue("必须明显小于总长度，才说明它真的停下来了", source.read < 8 * 1024 * 1024L / 8)
    }

    /**
     * 恰好等于上限必须能读完。
     *
     * 反向约束，防"过度收紧"：如果实现在读满上限后就报错，
     * 那么一张**正好** 32 MiB 的合法图片会被永久拒之门外，
     * 而它的体积完全正常。
     */
    @Test
    fun `恰好等于上限时可以完整读出`() {
        val data = ByteArray(4096) { (it % 251).toByte() }
        val out = ByteArrayInputStream(data).readCapped(4096)
        assertNotNull("恰好等于上限的完整数据不该被判定为超限", out)
        assertArrayEquals(data, out)
    }

    /** 反向约束之二：多一个字节就必须放弃，否则上限等于上限 + 1。 */
    @Test
    fun `多一个字节就放弃`() {
        assertNull(ByteArrayInputStream(ByteArray(4097)).readCapped(4096))
    }

    /**
     * 空流返回空数组，不是 null。
     *
     * 这两者被混为一谈的话，"读到一个空文件"会被当成"读失败" ——
     * 而 `null` 在本项目里是"超限"的专用信号（见 [readCapped] 的返回值约定）。
     */
    @Test
    fun `空流返回空数组而不是 null`() {
        val empty = ByteArrayInputStream(ByteArray(0)).readCapped(1024)
        assertNotNull(empty)
        assertEquals(0, empty!!.size)

        val zeroLimit = ByteArrayInputStream(ByteArray(0)).readCapped(0)
        assertNotNull(zeroLimit)
        assertEquals(0, zeroLimit!!.size)
    }

    /** 上限为零时任何非空输入都被拒；负数上限是调用方的错，必须立刻炸掉而不是静默当成 0。 */
    @Test
    fun `上限为零拒绝非空输入_负数上限抛出异常`() {
        assertNull(ByteArrayInputStream(ByteArray(1)).readCapped(0))
        try {
            ByteArrayInputStream(ByteArray(1)).readCapped(-1)
            fail("负数上限应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("-1"))
        }
    }

    /**
     * "太大"与"读不出来"必须是两句不同的话，而且要把上限数字带出来。
     *
     * 反向约束：如果超限复用"读不出来，换一张试试"，用户手里那张 40 MB 的全景图
     * 会让他一遍遍换图片重试一个**必然失败**的操作 —— 真正该做的是先裁剪或缩小。
     * 数字必须来自上限本身，不能各处手写：改了上限却忘了改文案，
     * 用户看到的数字就和实际行为对不上了。
     *
     * （`ImagePipeline.Preparation` 是嵌套在 object 里的普通类，构造它不触碰任何
     * Android API，因此在纯 JVM 单测里可以直接断言文案。）
     */
    @Test
    fun `过大与读不出来是两句不同的话_并带出上限数字`() {
        val tooLarge = ImagePipeline.Preparation.TooLarge(32 * 1024 * 1024)
        assertTrue("文案里必须出现实际上限：${tooLarge.message}", tooLarge.message.contains("32 MB"))
        assertTrue("不能与「读不出来」混为一谈：${tooLarge.message}", !tooLarge.message.contains("读不出来"))
        assertTrue("要给出可执行的下一步：${tooLarge.message}", tooLarge.message.contains("裁剪"))

        val other = ImagePipeline.Preparation.TooLarge(8 * 1024 * 1024)
        assertTrue("上限变了文案要跟着变：${other.message}", other.message.contains("8 MB"))
    }

    // ==================== 3. 备份包清单的读取封顶 ====================

    private fun zipOf(entries: List<Pair<String, ByteArray>>, stored: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (stored) {
                    // 不压缩：让"底层流被读走多少"直接等于"清单被解出多少"，
                    // 从而能用 CountingStream 精确观测读取量。
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun validManifest(noteRows: Int = 3, attachments: Int = 1): String =
        buildString {
            appendLine("format=1")
            appendLine("app=FPB")
            appendLine("createdAt=1700000000000")
            appendLine("noteRows=$noteRows")
            appendLine("attachments=$attachments")
        }

    private fun backupEntries(manifest: ByteArray) = listOf(
        "fpb-backup.txt" to manifest,
        "fpb.key" to ByteArray(64) { it.toByte() },
        "vault.db" to ("SQLite format 3\u0000" + ByteArray(64)).toByteArray(Charsets.ISO_8859_1),
    )

    /** 基线：一个正常的备份包必须能被正确预检 —— 否则下面的失败就无法归因。 */
    @Test
    fun `正常备份包可以预检出条目数`() {
        val bytes = zipOf(backupEntries(validManifest(noteRows = 7, attachments = 2).toByteArray()))
        val info = BackupManager.inspect(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals(1, info.formatVersion)
        assertEquals(7, info.noteRows)
        assertEquals(2, info.attachments)
        assertEquals(bytes.size.toLong(), info.bytes)
    }

    /**
     * 缺陷：`BackupManager.inspect` 用 `zip.readBytes()` 读清单 —— **没有任何上限**。
     *
     * `inspect` 是"用户选中一个文件之后、真正导入之前"的第一步，
     * 也就是说它跑在 `extract` 那套 `MAX_ENTRY_BYTES` / `MAX_TOTAL_BYTES`
     * 防护**之前**。一个把 `fpb-backup.txt` 声明成几 GB 的构造包，
     * 会在这里被整体读进内存。
     *
     * 修复后：超过 64 KiB 的清单直接判为异常，并且**没有读到底**。
     */
    @Test
    fun `清单条目过大时拒绝_且不把整条读进内存`() {
        val hugeManifest = ByteArray(4 * 1024 * 1024)
        val zip = zipOf(backupEntries(hugeManifest), stored = true)
        val counter = CountingStream(ByteArrayInputStream(zip))

        try {
            BackupManager.inspect(counter, zip.size.toLong())
            fail("体积异常的清单应当被拒绝")
        } catch (expected: BackupException) {
            assertTrue(
                "异常信息要说清是清单的问题，实际是：${expected.message}",
                expected.message!!.contains("清单"),
            )
        }

        // 修复前这里是 4 MiB（readBytes 一路读到底）；修复后停在 64 KiB 上限附近。
        assertTrue("清单被读走了 ${counter.read} 字节，没有在上限处停下", counter.read < 512 * 1024L)
    }

    /**
     * 封顶不能误伤：一份尺寸正常、但比典型值大一些的清单仍要能读。
     *
     * 这条防的是"上限给小了"——那会让合法的备份包在预检阶段就被拒，
     * 而用户手上的包其实是好的、也**没有别的办法**证明它好。
     */
    @Test
    fun `清单略大但仍正常时照常可用`() {
        val padded = validManifest(noteRows = 12) + "# ".repeat(2000) + "\n"
        val bytes = zipOf(backupEntries(padded.toByteArray()))
        val info = BackupManager.inspect(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals(12, info.noteRows)
    }
}
