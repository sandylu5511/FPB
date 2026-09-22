package com.fpb.vault.session

import com.fpb.vault.crypto.AeadCipher
import com.fpb.vault.crypto.SecureBytes

/**
 * 审计钥匙 —— 专门用来保护「假密码那本登录账」的第三把钥匙。
 *
 * ## 为什么必须有这样一把钥匙
 *
 * 需求是「在真密码的设置页里看到假密码的登录情况」，也就是**真库会话要能读到
 * 诱饵库那本账**。而真库会话手上只有真库 DEK，诱饵库的账本是诱饵 DEK 加密的，
 * 于是必须有一条跨域的通道。通道只有两种做法：
 *
 * 1. 真库侧存一份**诱饵 DEK 的副本**（"借出诱饵库的钥匙"）
 * 2. 真库侧存一份**只够读账本的第三把钥匙**（就是本类）
 *
 * 第一种做法看起来更省事，但它有一个不可接受的后果：**拿到真库 DEK 的人
 * 也就拿到了诱饵库的钥匙**，能把诱饵库里的东西原样端走 —— 而诱饵库正是
 * 被胁迫时用来交差的那个库，它的钥匙绝不该挂在真库门后。
 *
 * 换成审计钥匙之后，一次真库 DEK 泄露暴露的只有"假密码在什么时候被用过"
 * 这条线索，加一把**打不开任何一条笔记**的钥匙。
 *
 * ## 它为什么不能由某一方的 DEK 派生
 *
 * 两个方向都走不通：真库会话没有诱饵 DEK（这正是要守住的东西），
 * 而诱饵会话没有真库 DEK（这是另一条边界）。所以它只能是一把**独立随机**
 * 的钥匙，两个域各用本域的 DEK 封装一份。
 *
 * ## 顺带得到的好处：与密码的生命周期解耦
 *
 * 诱饵库每次重设假密码都会换一把全新的诱饵 DEK（否则上一个假密码保护过的内容
 * 会原样留在新的诱饵库里）。账本若由诱饵 DEK 保护，换密码就得先把账迁走，
 * 那种迁移一旦失败就是**静默丢掉"有人用过假密码"这条记录**。
 * 由审计钥匙保护之后，换假密码完全不碰账本。
 *
 * ## 它有多敏感
 *
 * 比两把 DEK 都低：拿着它只能读写一本登录时间线（谁在什么时候进来过），
 * **读不到任何一条笔记、任何一个字段**。但它仍然等同于明文级敏感 ——
 * 它是"这个库被人用过"的完整线索，所以和 DEK 一样用完即清。
 */
class AuditKey private constructor(private val bytes: SecureBytes) : AutoCloseable {

    /** 短暂暴露内部字节，用于喂给 HMAC 与 Cipher。调用方不得持有引用。 */
    fun expose(): ByteArray = bytes.expose()

    val isValid: Boolean get() = !bytes.isEmpty()

    override fun close() = bytes.close()

    /**
     * **故意不打印任何字节**，只说明它能不能用。
     * 日志、崩溃报告、单元测试的失败信息都可能把它带出去。
     */
    override fun toString(): String = "AuditKey(usable=$isValid)"

    companion object {

        /** 新生成一把。只在「建立审计通道」时调用一次。 */
        internal fun random(): AuditKey =
            AuditKey(SecureBytes.takeOwnership(SecureBytes.random(AeadCipher.KEY_BYTES)))

        /** 从解出来的明文字节复制一份（**复制**，调用方随后可以放心销毁自己的那份）。 */
        internal fun of(source: ByteArray): AuditKey = AuditKey(SecureBytes.of(source))
    }
}
