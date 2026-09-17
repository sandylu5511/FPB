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

    /** 图片等二进制附件的密文文件（整段一个 GCM 记录）。 */
    fun blob(blobId: String): ByteArray =
        "mixia:v$SCHEMA:blob:$blobId".toByteArray(CHARSET)

    /**
     * **分块**附件（视频）的第 [index] 块。
     *
     * 与 [blob] 的字符串必须不可能碰撞：老附件（图片）整段用 [blob]，
     * 新附件（视频）逐块用这个。两者若能构造出同一个字节串，同一批密文就能在两个
     * blobId 之间搬运。多出来的 `:chunk:…` 后缀已经保证形状不同，不必再单独加版本号。
     *
     * ## 为什么带 [isLast]，而不是带"块数 / 明文总长"
     *
     * 一开始想认证的是头部里的 `chunkCount` 与 `plainSize`（它们明文写在文件头里，
     * 改掉就能让读取方只解出前几块 —— 用户看到一段**静默变短**的影片，一处校验都不会报错）。
     * 但那两个量在**流式写入时根本算不出来**：总量要读完最后一个字节才知道。
     * 要认证它们，就只能先落一份明文或先扫一遍流，前者违背"明文绝不落盘"，后者要求流可回退。
     *
     * [isLast] 是等价的替代品，而且写入时就能确定（提前读一块看一眼后面还有没有数据）。
     * 截断场景下：文件真身有 10 块，第 9 块带 `isLast=true` 封的；攻击者把头部改成 5 块
     * 并截短文件 —— 此时读取方认为第 4 块是末块，于是用 `isLast=true` 去解，而它当初是
     * 带 `isLast=false` 封的，AAD 不同 → 解密失败。**截断必然暴露。**
     *
     * [chunkSize] 一并认证：它决定块偏移（不查表，纯算术），被改掉会让读取方从错位的地方取字节，
     * 那本来也会认证失败，但把它写进 AAD 让这件事从"碰巧失败"变成"必然失败"。
     */
    fun blobChunk(blobId: String, index: Int, chunkSize: Int, isLast: Boolean): ByteArray =
        "mixia:v$SCHEMA:blob:$blobId:chunk:$index:$chunkSize:$isLast".toByteArray(CHARSET)

    /** 导出备份的清单文件。 */
    fun manifest(vaultId: String): ByteArray =
        "mixia:v$SCHEMA:manifest:$vaultId".toByteArray(CHARSET)
}
