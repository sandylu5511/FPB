package com.fpb.vault.vault

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 带上限地读一个流。
 *
 * ## 为什么必须有这个函数
 *
 * 图片是"原图直存"的，而相册交出来的字节完全由用户决定 —— 高像素 RAW、
 * 全景、扫描件都可能几十上百 MB。`VaultSession.putImage` 里确实有一条
 * 32 MiB 的校验，**但那条校验发生在字节已经全部读进内存之后**：
 * 内存已经花出去了，校验再准也挡不住 `OutOfMemoryError`
 * （它是 `Error`，`runCatching` 虽然能接住 Throwable，但堆已经榨干，
 * 后续分配照样失败，表现为应用直接崩掉而不是给出提示）。
 *
 * 因此读取这一步本身就要封顶：一旦超过 [limit] 立刻放弃，
 * 不再继续把字节累积进内存。
 *
 * 放在 `vault` 包而不是各自的实现里，是因为导出/导入备份那条链路上
 * 有同一个需求（见 `BackupManager.inspect` 读清单文件），
 * 两处各写一遍迟早会分叉 —— 而其中一处恰恰是解析不可信输入的入口。
 */
internal fun InputStream.readCapped(limit: Int): ByteArray? {
    require(limit >= 0) { "上限不能为负：$limit" }
    val out = ByteArrayOutputStream(minOf(limit, INITIAL_CAPACITY))
    val buffer = ByteArray(CHUNK)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        total += read
        // 先把"越界"判掉再写：否则最后一小段超限的字节也已经进了内存。
        if (total > limit) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val CHUNK = 1 shl 16

/** 初始容量：小上限（如清单文件）不必按上限预分配，大上限也不必从 128 字节开始反复扩容。 */
private const val INITIAL_CAPACITY = 1 shl 12
