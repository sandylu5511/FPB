package com.fpb.vault.vault

/**
 * 「该导出备份了」的判定。
 *
 * ## 为什么单独成一个纯对象
 *
 * 判定本身只是几个时间戳的算术，但它挂在 [SettingsStore]（要 `Context`）上时
 * 就没法在 JVM 单测里覆盖 —— 而这段逻辑正是"提醒到底会不会出现"的唯一判据。
 * 本次审核发现的缺陷恰好就在这里：写入端（导出成功时记时间戳）一直都在，
 * **读取端从头到尾不存在**，于是四个不同文件的注释都在承诺
 * "设置页会周期性提醒导出备份"，而实际上一次都不会提醒。
 *
 * 对一个"数据只在这台设备上、没有 INTERNET 权限、也没有任何自动上传"的应用，
 * 这条承诺的落空就是数据丢失本身：用户以为应用会催自己备份，所以一直没主动导出；
 * 等手机丢了，什么都没有了。因此它必须有可被断言的测试，见 `Audit3RegressionTest`。
 */
object BackupReminder {

    /**
     * 距离上次**成功导出**超过这个时长，就提醒一次。
     *
     * 七天是个折中：更短会变成骚扰（用户会学着无视它，那比不提醒更糟），
     * 更长则一次「忘了备份就换手机」的窗口足以让数据彻底消失。
     */
    const val INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** 提醒的三种结局。用枚举而不是布尔，是因为"从未导出过"与"太久了"要说不同的话。 */
    enum class Due {
        /** 不需要提醒。 */
        NONE,

        /**
         * 库里有内容，却**一次都没导出过**。
         *
         * 这是最危险的一种状态，也是最容易被漏掉的一种：早期实现只比较
         * "现在 - 上次记录"，而"从未记录"被当成"刚备份过"处理，
         * 于是最该被催的那批用户恰好一次都不会被催。
         */
        NEVER_EXPORTED,

        /** 上次导出已经超过 [INTERVAL_MILLIS]。 */
        STALE,
    }

    /**
     * 现在是否该提醒，以及该说哪一句话。
     *
     * @param lastExportedAt 上次**成功导出**的时间戳（`<= 0` 表示从未导出过）。
     * @param hasContent 库里有没有内容。空库不提醒 —— 没东西可丢，
     *        刚建完库就被催着备份只会让这个提醒贬值。
     * @param now 当前时间。
     */
    fun eval(lastExportedAt: Long, hasContent: Boolean, now: Long): Due {
        if (!hasContent) return Due.NONE
        // 有内容却从没导出过：立刻提醒，不设任何"基线"。
        if (lastExportedAt <= 0L) return Due.NEVER_EXPORTED
        // 用户把系统时间往回拨（或时区被改）会让差值为负。此时不提醒：
        // 漏一次提醒的代价，远小于"每次打开都弹"。
        if (now < lastExportedAt) return Due.NONE
        return if (now - lastExportedAt >= INTERVAL_MILLIS) Due.STALE else Due.NONE
    }
}
