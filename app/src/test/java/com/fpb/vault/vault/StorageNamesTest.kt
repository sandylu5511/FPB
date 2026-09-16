package com.fpb.vault.vault

import com.fpb.vault.data.SqliteRowStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘名称与偏好键名的**升级兼容性契约**。
 *
 * ## 为什么这类测试必须存在
 *
 * 前面三轮审核查的都是"代码写错了"，而这一份查的是**另一类事故**：
 * 代码本身完全正确，只是某个字符串改了一个字 —— 于是新版本在设备上
 * 找不到旧版本写下的东西。
 *
 * 它的破坏力之所以大，是因为**失败方式不像失败**：
 *
 * - 改了 `fpb.key` / `vault.db` / `attachments` → 密文还在，但没有密钥能解开 →
 *   应用按"全新的库"重新引导，用户看到的是"我的东西全没了"；
 * - 改了任一偏好键 → 用户的配色、自动锁定、桌面图标被**静默重置**，
 *   他能察觉不对劲，却完全无从排查；
 * - 改了备份包的条目名或清单字段名 → **用户以前导出的备份包再也导不回来**。
 *   这一条最严重：备份包是用户手里唯一的副本，而它已经躺在网盘里了，
 *   我们没有任何机会回去"再补一个兼容读取"。
 *
 * 所以这里的每条断言都刻意写死字面量。**测试失败时不要改测试** ——
 * 它意味着你正在改动一个已经发出去的契约，正确做法是补"读到旧名字就迁移"的逻辑。
 *
 * 上面这些名字已经被收拢到 [StorageNames] 一个文件里，因此这条测试同时也在保护
 * "生产代码里不再散落这些字面量"这件事（见最后两条）。
 */
class StorageNamesTest {

    // ==================== 一、磁盘上的名字 ====================

    @Test
    fun `应用私有目录里的文件名与目录名不得改动`() {
        assertEquals("fpb.key", StorageNames.KEY_FILE)
        assertEquals("attachments", StorageNames.BLOB_DIR)
        assertEquals("vault.db", StorageNames.DATABASE_FILE)
        assertEquals(".tmp", StorageNames.TEMP_SUFFIX)
        assertEquals("bio_wrap.bin", StorageNames.BIOMETRIC_WRAP_FILE)
        assertEquals("fpb.biometric.v1", StorageNames.BIOMETRIC_KEY_ALIAS)
        assertEquals("fpb_settings", StorageNames.PREFS_FILE)
    }

    /**
     * 别名里的 `.v1` 不是装饰：将来换包裹方案时要用 `.v2` 与它并存，
     * 直接改这个值会覆盖掉老用户的 Keystore 密钥、让他们的生物识别入口失效。
     */
    @Test
    fun `Keystore 别名必须带版本后缀_以便将来换方案时与旧密钥并存`() {
        assertTrue(
            "别名里应有版本后缀：${StorageNames.BIOMETRIC_KEY_ALIAS}",
            Regex("""\.v\d+$""").containsMatchIn(StorageNames.BIOMETRIC_KEY_ALIAS),
        )
    }

    /** 名字里混进路径分隔符会让 `File(filesDir, name)` 跑到别的目录去。 */
    @Test
    fun `文件名不含路径分隔符_避免意外写到私有目录之外`() {
        val plainNames = listOf(
            StorageNames.KEY_FILE,
            StorageNames.DATABASE_FILE,
            StorageNames.BIOMETRIC_WRAP_FILE,
            StorageNames.PREFS_FILE,
            StorageNames.BACKUP_MANIFEST,
        )
        for (name in plainNames) {
            assertFalse("$name 不应含 '/'", name.contains('/'))
            assertFalse("$name 不应含 '\\'", name.contains('\\'))
            assertFalse("$name 不应含 '..'", name.contains(".."))
        }
        // 目录名同理，但它允许自己就是一层目录
        assertFalse(StorageNames.BLOB_DIR.contains('/'))
        assertFalse(StorageNames.BLOB_DIR.contains(".."))
    }

    // ==================== 二、偏好键 ====================

    @Test
    fun `偏好键名不得改动_改动会让存量用户的设置被静默重置`() {
        assertEquals("auto_lock_millis", StorageNames.Pref.AUTO_LOCK)
        assertEquals("block_screenshots", StorageNames.Pref.BLOCK_SCREENSHOTS)
        assertEquals("biometric_enabled", StorageNames.Pref.BIOMETRIC)
        assertEquals("launcher_alias", StorageNames.Pref.LAUNCHER_ALIAS)
        assertEquals("onboarding_done", StorageNames.Pref.ONBOARDING)
        assertEquals("slot_b_configured", StorageNames.Pref.SECONDARY_SLOT)
        assertEquals("backup_reminder_at", StorageNames.Pref.BACKUP_REMINDER)
        assertEquals("theme_mode", StorageNames.Pref.THEME_MODE)
    }

    /**
     * `backup_reminder_at` 记的是**导出**时间而不是"提醒"时间，
     * 名字和键名都显得别扭，但两处都刻意保留 —— 见 [StorageNames.Pref.BACKUP_REMINDER]。
     * 这条测试是那段"为什么明知别扭还留着"的注释的**唯一执行者**：
     * 只要有人把键改成 `last_exported_at`，存量用户的时间戳会被清零，
     * 而他们下次打开应用就会看到一个假的"还没有导出过备份"。
     */
    @Test
    fun `导出时间戳的键名保持 backup_reminder_at_以免清零存量用户的时间戳`() {
        assertEquals("backup_reminder_at", StorageNames.Pref.BACKUP_REMINDER)
    }

    /**
     * 不只是防重名：两个键共用一个名字时，后写的那次会覆盖前一次，
     * 表现是"我开了生物识别，结果自动锁定被改回了默认值"这种莫名其妙的组合。
     */
    @Test
    fun `偏好键名两两不重复`() {
        val keys = listOf(
            StorageNames.Pref.AUTO_LOCK,
            StorageNames.Pref.BLOCK_SCREENSHOTS,
            StorageNames.Pref.BIOMETRIC,
            StorageNames.Pref.LAUNCHER_ALIAS,
            StorageNames.Pref.ONBOARDING,
            StorageNames.Pref.SECONDARY_SLOT,
            StorageNames.Pref.BACKUP_REMINDER,
            StorageNames.Pref.THEME_MODE,
        )
        assertEquals("键名有重复：$keys", keys.size, keys.toSet().size)
    }

    // ==================== 三、备份包 ====================

    @Test
    fun `备份包里的条目名不得改动_改了用户已有的备份包就导不回来`() {
        assertEquals("fpb-backup.txt", StorageNames.BACKUP_MANIFEST)
        assertEquals("fpb.key", StorageNames.BACKUP_KEY_ENTRY)
        assertEquals("vault.db", StorageNames.BACKUP_DB_ENTRY)
        assertEquals("attachments/", StorageNames.BACKUP_BLOB_PREFIX)
    }

    @Test
    fun `备份包清单的字段名不得改动_改了旧清单会解析不出格式`() {
        assertEquals("format", StorageNames.BackupManifest.FORMAT)
        assertEquals("app", StorageNames.BackupManifest.APP)
        assertEquals("createdAt", StorageNames.BackupManifest.CREATED_AT)
        assertEquals("noteRows", StorageNames.BackupManifest.NOTE_ROWS)
        assertEquals("attachments", StorageNames.BackupManifest.ATTACHMENTS)
        assertEquals("FPB", StorageNames.BackupManifest.APP_TAG)
    }

    /**
     * 清单是每行一个 `key=value`，字段名里混进 `=` 或换行会把那一行拆坏，
     * 导入时表现为"清单异常，无法确认格式"。
     */
    @Test
    fun `清单字段名不能含等号或换行_否则会写坏清单格式`() {
        val fields = listOf(
            StorageNames.BackupManifest.FORMAT,
            StorageNames.BackupManifest.APP,
            StorageNames.BackupManifest.CREATED_AT,
            StorageNames.BackupManifest.NOTE_ROWS,
            StorageNames.BackupManifest.ATTACHMENTS,
        )
        for (field in fields) {
            assertTrue("$field 不应为空", field.isNotEmpty())
            assertFalse("$field 不应含 '='", field.contains('='))
            assertFalse("$field 不应含换行", field.contains('\n'))
            assertFalse("$field 不应含回车", field.contains('\r'))
            assertEquals("$field 不应含空格（会被误当成分隔）", field, field.trim())
        }
        assertEquals("字段名有重复：$fields", fields.size, fields.toSet().size)
    }

    // ==================== 四、"契约只有一个来源" ====================

    /**
     * 生产代码里的公开常量必须就是 [StorageNames] 里那个值。
     *
     * 防的是"改了一处、忘了另一处"：例如把 [SqliteRowStore.DATABASE_NAME] 改回硬编码字面量，
     * 而 [StorageNames.DATABASE_FILE] 没跟着改 —— 两个"同一个东西"从此有两个值，
     * 上一条测试却仍然是绿的。
     */
    @Test
    fun `生产代码中的公开常量必须与 StorageNames 同值`() {
        assertEquals(StorageNames.DATABASE_FILE, SqliteRowStore.DATABASE_NAME)
        assertEquals(StorageNames.BIOMETRIC_WRAP_FILE, BiometricGate.WRAP_FILE_NAME)
    }

    /**
     * 备份包的附件前缀必须真的由目录名派生，而不是另写一遍 `"attachments/"`。
     * 两者哪天要是分开了，导入时会把附件解到别处 —— 记录恢复了，照片全没了。
     */
    @Test
    fun `备份包附件前缀必须与附件目录名保持一致`() {
        assertEquals("${StorageNames.BLOB_DIR}/", StorageNames.BACKUP_BLOB_PREFIX)
    }

    /**
     * 清单字段 `attachments` 与目录名 `attachments` 取值相同**只是巧合**，
     * 因此它们是两个独立常量。这条测试把这个"巧合"写下来，
     * 免得将来有人看到两个同值常量就顺手合并成一个。
     */
    @Test
    fun `清单字段 attachments 与附件目录名是两件事`() {
        assertEquals(StorageNames.BLOB_DIR, StorageNames.BackupManifest.ATTACHMENTS)
        // 值恰好相同，但它们服务于不同的命名空间（键 vs 路径），别合并
        assertTrue(StorageNames.BLOB_DIR.isNotEmpty())
        assertTrue(StorageNames.BackupManifest.ATTACHMENTS.isNotEmpty())
    }
}
