package com.fpb.vault.vault

/**
 * 落盘名称的唯一来源 —— 同时是**升级兼容性契约**。
 *
 * ## 为什么要把这些字符串收拢到一个文件
 *
 * 这些名字看起来只是普通常量，实际是"已发出去的那个包"和"接下来要发的包"之间的接口：
 * 名字一旦改动，新版本会在设备上找不到旧版本写下的数据 ——
 * **而且失败方式大多不是崩溃，是"数据看起来凭空少了"**：
 *
 * | 改错了什么 | 用户的感受 |
 * | --- | --- |
 * | [KEY_FILE]、[BLOB_DIR] | 密文和附件都在，但没有密钥 → 只能当成空库重新引导 |
 * | [DATABASE_FILE] | 同上，记录整批消失 |
 * | [BIOMETRIC_WRAP_FILE]、[BIOMETRIC_KEY_ALIAS] | 生物识别忽然"用不了了"，要重新开一次 |
 * | [Pref] 里任意一项 | 该设置被静默重置（配色跳回跟随系统、自动锁定跳回 30 秒…） |
 * | [BACKUP_MANIFEST] 等备份包条目名 | **用户以前导出的备份包再也导不回来** |
 *
 * 最后一条最难补救：备份包是用户手里唯一的副本，而它已经躺在网盘里了。
 *
 * ## 规矩
 *
 * - 这里的值**只能新增，不能改**。真要改就必须先写"读到旧名字就迁移到新名字"的逻辑，
 *   并且为它补一条回归测试。
 * - 任何新加的落盘名称也要放进来，由 `StorageNamesTest` 钉住字面量。
 */
internal object StorageNames {

    // ==================== 应用私有目录内的文件与目录 ====================

    /**
     * 主密钥文件（`files/fpb.key`）。
     *
     * 它丢了不等于"数据丢了"——密文还在，但没有它解不开：
     * 应用只能按"全新的库"重新引导，用户看到的就是"我的东西全没了"。
     * 因此它是整个私有目录里**最该被备份**的单个文件。
     */
    const val KEY_FILE = "fpb.key"

    /** 附件（照片）目录，位于 `files/` 下。 */
    const val BLOB_DIR = "attachments"

    /** 写文件时的临时后缀：先写 `x.tmp` 再 rename，避免断电留下半个文件。 */
    const val TEMP_SUFFIX = ".tmp"

    /** SQLite 数据库文件名（由 `SQLiteOpenHelper` 放在 `databases/` 下）。 */
    const val DATABASE_FILE = "vault.db"

    /** Keystore 硬件密钥包裹后的 DEK（本项目唯一不可导出的东西）。 */
    const val BIOMETRIC_WRAP_FILE = "bio_wrap.bin"

    /**
     * Keystore 里的密钥别名。
     *
     * 带 `.v1` 是**故意**的：将来若要换一套包裹方案，别名换成 `.v2` 即可与旧的并存，
     * 老用户开一次生物识别就平滑迁移；沿用同名则会把旧密钥覆盖掉，
     * 而那意味着他们的生物识别入口失效。
     */
    const val BIOMETRIC_KEY_ALIAS = "fpb.biometric.v1"

    /** 明文偏好文件（`shared_prefs/fpb_settings.xml`）。 */
    const val PREFS_FILE = "fpb_settings"

    // ==================== 备份包内部的条目名 ====================

    /** 备份包里的清单条目（记录格式版本、条目数等，供导入前预检）。 */
    const val BACKUP_MANIFEST = "fpb-backup.txt"

    /**
     * 备份包里的密钥条目。
     *
     * 刻意与磁盘上的 [KEY_FILE] 同名：导入时是"按名字把条目写回对应的磁盘位置"，
     * 两个名字分开定义只会制造"改了其中一个忘了另一个"的机会。
     */
    const val BACKUP_KEY_ENTRY = KEY_FILE

    /** 备份包里的数据库条目，理由同上。 */
    const val BACKUP_DB_ENTRY = DATABASE_FILE

    /** 备份包里附件条目的前缀（`attachments/<blobId>`）。 */
    const val BACKUP_BLOB_PREFIX = "$BLOB_DIR/"

    /**
     * 备份包清单里的字段名（形如 `format=1`，每行一个 `key=value`）。
     *
     * 这些字符串同样是**对外契约**：清单是导入时唯一能用来判格式的东西，
     * 改掉任何一个，用户以前导出的包都会退化成"清单异常，无法确认格式"。
     * 注意 [ATTACHMENTS] 的值与 [BLOB_DIR] 相同只是巧合 —— 一个是清单字段名，
     * 一个是目录名，将来不一定会一起改，所以**不要**把它们合并成一个常量。
     */
    object BackupManifest {
        const val FORMAT = "format"
        const val APP = "app"
        const val CREATED_AT = "createdAt"
        const val NOTE_ROWS = "noteRows"
        const val ATTACHMENTS = "attachments"

        /** [APP] 字段的取值，用于识别"这是不是本应用的备份包"。 */
        const val APP_TAG = "FPB"
    }

    /**
     * 偏好键名。
     *
     * 单独一组是因为它们的"改错代价"和文件类不同：不会丢内容，
     * 但会让用户的设置莫名重置 —— 属于用户能立刻察觉、却完全无从排查的一类问题。
     */
    object Pref {
        const val AUTO_LOCK = "auto_lock_millis"

        const val BLOCK_SCREENSHOTS = "block_screenshots"

        const val BIOMETRIC = "biometric_enabled"

        const val LAUNCHER_ALIAS = "launcher_alias"

        const val ONBOARDING = "onboarding_done"

        /**
         * 是否配置了第二个槽位（诱饵密码）。
         *
         * 键名刻意含糊：`shared_prefs` 是明文 XML，写成 `decoy_configured`
         * 等于把"这台设备还藏着一个假密码"摊开给人看。
         */
        const val SECONDARY_SLOT = "slot_b_configured"

        /**
         * 上次成功导出的时间戳（0 = 从未导出过）。
         *
         * 名字里的 "reminder" 是历史遗留：它记的是**导出**时间，
         * 读取端在 [BackupReminder]。两处都保留这个略显别扭的名字，是因为
         * 改名会让所有存量用户的时间戳被静默清零 —— 无害但没必要。
         */
        const val BACKUP_REMINDER = "backup_reminder_at"

        /** 外观模式：存字符串而不是序号，序号会随枚举重排而错位。 */
        const val THEME_MODE = "theme_mode"
    }
}
