package com.fpb.vault.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份包清单的**写入端**格式契约。
 *
 * ## 为什么单独测这个
 *
 * [BackupManager.export] 需要一整个仓库与会话，跑不起来；于是清单的写法
 * （字段名、顺序、行尾换行）原先只能靠"读一遍代码"保证 —— 而它是备份格式的写入端：
 * 格式一变，**用户以前导出的包就再也导不回来**，而那些包已经躺在网盘里了，
 * 我们没有任何事后补救的机会。
 *
 * 把拼装抽成 [BackupManager.buildManifest] 之后，这段就能被钉住了。
 * 这里的两条主线：
 *
 * 1. **字面量**：字段名与整体形状一字不改（与 [StorageNamesTest] 互为呼应）；
 * 2. **往返**：写入端产出的清单，读取端（[BackupManager.inspect]）必须原样解析回来 ——
 *    单看字面量只能证明"没改"，证明不了"两端仍然对得上"。
 */
class BackupManifestFormatTest {

    /**
     * 整段文本逐字比对。
     *
     * 写死的是**完整字符串**而不是"包含某个字段"：因为格式的失败方式恰恰是
     * 少一个换行、多一个空格、或者把 `createdAt` 写成 `created_at` ——
     * 逐字段断言对这些都是绿的，只有整段比对才拦得住。
     */
    @Test
    fun `清单文本的格式必须一字不改`() {
        val text = BackupManager.buildManifest(
            createdAt = 1_700_000_000_000L,
            noteRows = 7,
            attachments = 3,
        )
        assertEquals(
            "format=1\n" +
                "app=FPB\n" +
                "createdAt=1700000000000\n" +
                "noteRows=7\n" +
                "attachments=3\n",
            text,
        )
    }

    /**
     * 写入端产出的清单，必须能被自己的读取端解析回来。
     *
     * 这一条防的是"两端各自改了却都自认为对"：只钉字面量的话，
     * 如果有人同时改了 [BackupManager.buildManifest] 和 [StorageNamesTest] 里的期望值，
     * 测试会全绿而**旧备份包已读不出来**。往返测试把写入端与读取端绑在一起 ——
     * 只要读取端还认旧名字，写入端改成新名字就会红。
     */
    @Test
    fun `写入端产出的清单能被读取端原样解析回来`() {
        val manifest = BackupManager.buildManifest(
            createdAt = 1_700_000_000_000L,
            noteRows = 12,
            attachments = 5,
        )
        val bytes = zipOfBackup(manifest.toByteArray(Charsets.UTF_8))
        val info = BackupManager.inspect(ByteArrayInputStream(bytes), bytes.size.toLong())

        assertEquals(BackupManager.FORMAT_VERSION, info.formatVersion)
        assertEquals(1_700_000_000_000L, info.createdAt)
        assertEquals(12, info.noteRows)
        assertEquals(5, info.attachments)
    }

    /**
     * 格式版本写进清单后必须能被读出来，且**不能超过**当前程序能处理的版本
     * （超了说明这个包来自更新的版本，应当明确拒绝而不是猜着读）。
     */
    @Test
    fun `清单里的格式版本可被读出且等于当前版本`() {
        val manifest = BackupManager.buildManifest(createdAt = 1L, noteRows = 0, attachments = 0)
        val bytes = zipOfBackup(manifest.toByteArray(Charsets.UTF_8))
        val info = BackupManager.inspect(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals(BackupManager.FORMAT_VERSION, info.formatVersion)
    }

    /** 恰好 5 行、行尾都有换行、没有空行 —— `lineValue` 是按行找 `key=value` 的。 */
    @Test
    fun `清单是五行且每行都以换行结束`() {
        val text = BackupManager.buildManifest(createdAt = 1L, noteRows = 0, attachments = 0)
        assertEquals("行尾必须是换行", "\n", text.takeLast(1))
        assertEquals("不应有多余的空行", text, text.trimEnd('\n') + "\n")
        val lines = text.removeSuffix("\n").split("\n")
        assertEquals("应恰好 5 行，实际 $lines", 5, lines.size)
        assertTrue("每行都应是 key=value 形状：$lines",
            lines.all { it.substringAfter("=").isNotEmpty() && it.count { c -> c == '=' } == 1 })
    }

    /**
     * 清单里**不能出现任何内容字段**。
     *
     * 它是整个备份包里唯一明文的东西，因此只能放"这个包有多大/什么时候导的"这类元信息。
     * 结构上已经保证了（[BackupManager.buildManifest] 只收计数与时间），
     * 这条测试把"只收计数"这件事写下来，免得将来有人图省事往里塞个标题预览。
     */
    @Test
    fun `清单只含元信息_字段名可枚举`() {
        val text = BackupManager.buildManifest(createdAt = 1L, noteRows = 0, attachments = 0)
        val keys = text.removeSuffix("\n").split("\n").map { it.substringBefore("=") }
        assertEquals(
            listOf("format", "app", "createdAt", "noteRows", "attachments"),
            keys,
        )
        assertEquals("字段名不应重复", keys.size, keys.toSet().size)
    }

    // ==================== 辅助 ====================

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * 一个结构完整的最小备份包：清单 + 密钥文件 + 数据库。
     *
     * 三者缺一 [BackupManager.inspect] 都会拒绝，所以往返测试必须把它们都放进去，
     * 否则失败会归因到"包不完整"而不是"清单解析不了"。
     */
    private fun zipOfBackup(manifest: ByteArray): ByteArray = zipOf(
        StorageNames.BACKUP_MANIFEST to manifest,
        StorageNames.BACKUP_KEY_ENTRY to ByteArray(64) { it.toByte() },
        StorageNames.BACKUP_DB_ENTRY to
            ("SQLite format 3\u0000" + ByteArray(64)).toByteArray(Charsets.ISO_8859_1),
    )
}
