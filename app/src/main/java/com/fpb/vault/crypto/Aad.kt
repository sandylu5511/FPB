package com.fpb.vault.crypto

import java.nio.charset.StandardCharsets

/**
 * AAD（附加认证数据）构造器。
 *
 * 集中在一处生成，原因是加密方和解密方必须构造出**逐字节相同**的 AAD，
 * 分散在各处手写字符串迟早会出现一边改了另一边没改的静默故障
 * （表现为"数据突然解不开了"，且极难定位）。
 */
object Aad {

    private const val SCHEMA = 1
    private val CHARSET = StandardCharsets.US_ASCII

    /**
     * DEK 包裹用。把密文与"它该待在哪个槽"绑定：
     * 若把假密码槽的密文复制到主密码槽，解密必然失败 —— 否则一次文件复制
     * 就能把假密码变成打开真库的钥匙。
     */
    fun dekWrap(slot: KeySlot): ByteArray =
        "mixia:v$SCHEMA:dek-wrap:${slot.id}".toByteArray(CHARSET)

    /** 条目的某个字段（标题/正文/标签/时间戳…）。绑定条目 ID，防止密文在条目之间搬运。 */
    fun entryField(entryId: String, field: String): ByteArray =
        "mixia:v$SCHEMA:entry:$entryId:$field".toByteArray(CHARSET)

    /** 图片等二进制附件的密文文件。 */
    fun blob(blobId: String): ByteArray =
        "mixia:v$SCHEMA:blob:$blobId".toByteArray(CHARSET)

    /** 导出备份的清单文件。 */
    fun manifest(vaultId: String): ByteArray =
        "mixia:v$SCHEMA:manifest:$vaultId".toByteArray(CHARSET)
}
