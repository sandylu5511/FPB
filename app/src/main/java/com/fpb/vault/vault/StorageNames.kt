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
 *
 * 上面第一条**已经被真实执行过一次**：`BIOMETRIC_KEY_ALIAS` 从 `fpb.biometric.v1`
 * 换成 `.v2`。配套做齐了三件事 —— 旧值原样保留在 [BIOMETRIC_KEY_ALIAS_RETIRED_V1]、
 * 迁移逻辑在 `BiometricGate.retireLegacyEnrollment()`、回归测试在 `StorageNamesTest`
 * 与 `BiometricGateInstrumentedTest`。这是一条可以照抄的模板，
 * 也是"改名字"与"直接删掉旧名字"之间的分界线。
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
     * Keystore 里的密钥别名（**当前一代**）。
     *
     * `v1 → v2` 换代的理由：密钥规格一旦生成就改不了（`BiometricGate.secretKey`
     * 命中已有别名就直接复用旧密钥），所以"要求 StrongBox、要求设备未锁定"
     * 这些加固只对新生成的密钥有效。要让存量用户也拿到，只能换一代别名。
     *
     * **代价是用户必须重新启用一次生物识别**，由
     * `BiometricGate.retireLegacyEnrollment()` 在启动时执行，并如实告知用户。
     * 不会造成数据损失：DEK 同时还被主密码槽与恢复码槽包裹着。
     *
     * `.v1` 那条"值只能新增不能改"的纪律针对的是**改掉一个还在用的名字**；
     * 这里改的是"当前代是谁"，而旧名字被完整保留在
     * [BIOMETRIC_KEY_ALIAS_RETIRED_V1] 里并由上面那段迁移逻辑读取 ——
     * 这正是纪律里要求的做法。
     */
    const val BIOMETRIC_KEY_ALIAS = "fpb.biometric.v2"

    /**
     * **已退役**的上一代别名。只为作废而保留，绝不能再拿它生成密钥。
     *
     * 它对应的密钥是用"没有 StrongBox、也没有设备未锁定要求"的规格生成的
     * （1.1.4 及以前）。删掉这个常量会让 `retireLegacyEnrollment()` 失去判断依据，
     * 存量用户就会被永久留在旧规格上 —— 那是这次加固最不该出现的结果。
     */
    const val BIOMETRIC_KEY_ALIAS_RETIRED_V1 = "fpb.biometric.v1"

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

        /**
         * "生物识别绑定被作废了，需要你重新启用一次"。
         *
         * 换代号（v1 → v2，见 [BIOMETRIC_KEY_ALIAS]）时置位，用户重新开启成功即清除。
         *
         * 这个标志不是装饰：没有它，用户看到的现象是"指纹解锁的按钮凭空消失了"，
         * 而**没有任何地方解释它为什么消失** —— 对一个人来说，
         * 设置自己变掉了、又查不出原因，比"提示我重新开一次"糟糕得多。
         */
        const val BIOMETRIC_REENROLL = "bio_reenroll_needed"

        /**
         * 当前这把生物识别密钥**生成时被平台接受的那一档加固规格**（枚举名）。
         *
         * ## 为什么必须把它落盘，而不是"用的时候去读回来"
         *
         * 因为**读不回来**。原设计想用 `KeyFactory.getKeySpec(secretKey, KeyInfo::class.java)`
         * 把密钥的属性读回来（`isUserAuthenticationRequired`、`getSecurityLevel()` 等），
         * 这条路的实现是 `readKeyInfo()`。实测（API 37 模拟器）：
         *
         * ```
         * [provider] AndroidKeyStore 的 KeyFactory 已注册算法：EC, RSA, XDH, ED25519, ML-DSA…
         * [provider] KeyFactory/AES -> NoSuchAlgorithmException
         * ```
         *
         * **这个 provider 不注册 AES。** 所以对一把 AES 密钥，`KeyInfo` 这条路
         * 不是"写错了算法名"，是**根本不存在**。真实后果不是崩溃，而是
         * [BiometricGate.enrollmentSpec] 永远返回 null ——
         * 于是设置页里"密钥在独立安全芯片内"与"本机密钥未被安全硬件保护"
         * **两句话都不会出现**，而后者正是需要被看见的那一句。
         *
         * 换成落盘之后，这一项的来源要说清楚：它是
         * **"生成时平台接受了这份规格"**，不是"从密钥上读回来确认生效"。
         * 这个区分成立的前提是**平台对不支持的要求是抛异常、而不是静默忽略**
         * （实测：没有录入生物识别时 `setUserAuthenticationRequired(true)`
         * 直接抛 `InvalidAlgorithmParameterException`，而不是默默降级）。
         * 唯一能绕过这个前提的情形是"平台声称支持 StrongBox 但静默忽略"，
         * 那一项本项无法证明，已在 [BiometricGate.KeyGuard] 的 KDoc 里写明。
         */
        const val BIOMETRIC_KEY_TIER = "bio_key_tier"

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

        /**
         * 密码/恢复码输错的次数，与最后一次输错的时间。
         *
         * 这两个值**只能是明文**，而且这条路没有别的走法：解锁失败的那一刻，
         * 手上还没有任何密钥（密码错的，所以派不出 KEK），密文无从谈起。
         *
         * 代价要说清楚：`shared_prefs` 是明文 XML，拿到设备文件的人能看出
         * "这台设备在某个时刻被人反复试过"。泄漏的是**这件事本身** ——
         * 不是密码，也不是任何一条内容。
         *
         * 键名刻意含糊（`attempt_*` 而不是 `wrong_password_*`）：键名越直白，
         * 这句话越像"这里有个值得撬的东西"。这与 [SECONDARY_SLOT] 是同一条考虑。
         *
         * 它们只是**尚未记入账本的那几次**：下次成功解锁时会折成一条登录记录并清零，
         * 于是明文里长期留着的只有空值。
         */
        const val ATTEMPT_COUNT = "attempt_count"
        const val ATTEMPT_LAST_AT = "attempt_last_at"
    }
}
