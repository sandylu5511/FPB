package com.fpb.vault.crypto

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 密钥字节的可清除容器。
 *
 * 为什么不直接用 ByteArray / String：
 * - String 不可变、可能被驻留、无法主动清除，且要等 GC 才可能离开内存
 * - 裸 ByteArray 容易被随手复制，副本散落在堆里无法回收
 *
 * 必须诚实说明的局限：**JVM / ART 上不可能保证内存被真正清零**。
 * GC 会搬移对象、JIT 可能把值留在寄存器或栈上、系统可能把页交换到磁盘。
 * 所以这里不做"已安全擦除"的虚假承诺，只做一件确实有效的事：
 * **缩小密钥在内存里的暴露窗口** —— 用完立刻填零，并避免无谓的复制。
 */
class SecureBytes private constructor(private var bytes: ByteArray) : AutoCloseable {

    val size: Int get() = bytes.size

    fun isEmpty(): Boolean = bytes.isEmpty()

    /**
     * 短暂暴露内部数组，用于喂给 Cipher 之类的 API。
     * 调用方**不得**持有返回的引用，更不得把它存进字段或复制进其他容器。
     */
    fun expose(): ByteArray = bytes

    /** 取一份独立副本。仅当需要不同生命周期时才用（例如把 DEK 交给会话层持有）。 */
    fun copy(): SecureBytes = SecureBytes(bytes.copyOf())

    override fun close() {
        bytes.fill(0)
        bytes = EMPTY
    }

    companion object {
        private val EMPTY = ByteArray(0)

        fun of(source: ByteArray): SecureBytes = SecureBytes(source.copyOf())

        /**
         * 接管数组所有权（不复制）。调用方交出后不得再使用该数组。
         * 用于 KDF / 随机数生成这类"刚创建就是密钥"的场景，避免多份副本。
         */
        fun takeOwnership(bytes: ByteArray): SecureBytes = SecureBytes(bytes)

        fun zeroize(target: ByteArray) {
            target.fill(0)
        }

        /**
         * 恒定时间比较。普通 `contentEquals` 会在第一个不同字节处短路返回，
         * 比较耗时与"前多少字节相同"相关，可被用于逐字节试探。
         */
        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
            MessageDigest.isEqual(a, b)

        /**
         * 取指定长度的强随机字节。
         *
         * 不调用 setSeed()：Android 与 JVM 的默认 SecureRandom 已由系统熵源播种，
         * 手动播种反而可能**削弱**熵（用可预测的数据覆盖系统种子）。
         */
        fun random(size: Int): ByteArray {
            val out = ByteArray(size)
            RANDOM.nextBytes(out)
            return out
        }

        // SecureRandom 本身线程安全（内部对 Provider 访问做了同步），不需要 ThreadLocal。
        // 用 ThreadLocal 反而会让每个线程各持一个实例，白白重复消耗熵池初始化成本。
        private val RANDOM: SecureRandom by lazy { SecureRandom() }
    }
}
