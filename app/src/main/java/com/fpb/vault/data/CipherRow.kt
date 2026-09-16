package com.fpb.vault.data

/**
 * 落盘的一行**密文**。
 *
 * 字段只有三样，这是刻意的设计：
 * - [id] 随机的 32 位十六进制串（16 字节熵），**不含任何语义**。
 *   不用"标题"当初级键、不用时间戳排序、不加类型列 —— 任何明文字段都会
 *   在数据库文件里留下可被直接读取的形状。
 * - [nonce] GCM 的 96 位随机数，公开无妨，但不可重用
 * - [ciphertext] 认证加密后的内容（含 16 字节认证标签）
 *
 * 因此，"这个库里有多少条笔记"是明文可数的，"里面写了什么"不是。
 * 前者无法隐藏（行数本身就在文件里），后者是这个设计的全部目的。
 */
class CipherRow(
    val id: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    init {
        require(id.isNotEmpty()) { "行 id 不能为空" }
    }

    val storedBytes: Long get() = (nonce.size + ciphertext.size).toLong()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CipherRow) return false
        return id == other.id &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    /** 不要打印完整 id 或密文长度以外的信息，避免日志成为侧信道。 */
    override fun toString(): String = "CipherRow(id=${id.take(8)}…, ct=${ciphertext.size}B)"
}

/**
 * 密文行的存储接口。
 *
 * 抽成接口的唯一目的是**让会话层可以被单元测试覆盖**：真实实现依赖 SQLite，
 * 在 JVM 上跑不起来；而域隔离、篡改检测、清单一致性这些逻辑恰恰
 * 值得我们逐条断言。测试里换成内存实现即可，被测代码一行不改。
 */
interface CipherRowStore {

    fun load(id: String): CipherRow?

    /**
     * 全量读取。**不用于常规业务路径**（常规路径靠加密索引清单精确定位），
     * 只用于两件事：清理孤儿 blob、以及诊断页面统计真实占用。
     */
    fun loadAll(): List<CipherRow>

    fun upsert(row: CipherRow)

    fun delete(id: String)

    /** 密文总字节数（不含 SQLite 页开销），用于设置页显示"本地占用"。 */
    fun totalBytes(): Long

    /**
     * 把一组写入合成**一次原子提交**：块内抛异常则全部回滚。
     *
     * 这个能力是必需的，不是"顺手加的便利方法"。会话层有一次写入必须同时落到两处：
     * 笔记行本身（`cipher_rows` 里的一行）和加密索引清单（另一行）。
     * 两者若各自独立提交，进程在两次写入之间被杀（低内存、用户划掉、系统回收）就会留下
     * **行存在、清单里没有**的半截状态。而清单是"这个库里有哪些笔记"的唯一权威来源，
     * 于是那条笔记此后永远不可见、永远不被清理，**用户也不会收到任何提示**。
     *
     * 对一个"数据只在这里"的应用，"写了一半"必须要么全成、要么全无。
     *
     * 默认实现直接执行块（内存实现若不做真实回滚会失去测试意义，
     * 因此 [com.fpb.vault.testing.InMemoryRowStore] 也实现了快照回滚）。
     */
    fun <T> inTransaction(block: () -> T): T = block()
}
