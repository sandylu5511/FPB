package com.fpb.vault.crypto

import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey

/**
 * 测试探针：**真正被清零**的 AES 密钥副本累计数量。
 *
 * ## 为什么计数器必须放在 [TransientSecretKey.destroy] 里，而不是调用点
 *
 * 第一版把它写在 `AeadCipher` 的 `finally` 块里（`destroy()` 旁边）。
 * 那是个假绿：对照实验——把 `destroy()` 那一行删掉、只留计数器——所有断言照样通过，
 * 因为计数器记的是"代码走到了这一行"，而不是"密钥真的被清零了"。
 *
 * 放进 [TransientSecretKey.destroy] 之后它的含义就唯一了：**只有真正执行了清零
 * 才会自增**。删掉调用点的 `destroy()`，计数就不动，用例立刻变红。
 *
 * 生产路径上它只是一次原子自增（纳秒级，相对于一次 AES-GCM 可忽略），
 * 没有任何行为依赖它。
 */
internal val scrubbedAesKeyCount = AtomicInteger()

/**
 * 测试探针：**真正被清零**的 HMAC 密钥副本累计数量。
 *
 * ## 为什么不与 AES 共用一个计数器
 *
 * 2026-09-20 把 `TransientAesKey` 泛化成 [TransientSecretKey] 时，第一反应是
 * "都是密钥副本，一个计数器就够了"。那会**直接毁掉两条判据的鉴别力**：
 *
 * - `Audit8RegressionTest` 与 `TransientSecretKeyTest` 用 `scrubbedAesKeyCount`
 *   钉住"AeadCipher 那条路清到位了"；
 * - `VaultSession` 的派生路径要有一条自己的判据。
 *
 * 混在一起数时，**AES 销毁成功、HMAC 忘了销毁，总数照样在涨** ——
 * 两条判据都能通过，而其中一条路一个字节也没清。这与探针写在调用点是同一类错误：
 * 测的不是它声称在测的东西。
 *
 * 拆开之后每个计数器只代表一条路，两条判据各自钉各自的路。
 */
internal val scrubbedHmacKeyCount = AtomicInteger()

/**
 * 一把"用完就能自己清零"的密钥 —— AES 与 HMAC 共用同一套机制。
 *
 * ## 为什么不能继续用 `SecretKeySpec`
 *
 * [AeadCipher] 原先每次加解密都 `SecretKeySpec(key, "AES")`，而
 * [com.fpb.vault.session.VaultSession] 的派生路径用
 * `SecretKeySpec(material, "HmacSHA256")`。它的构造函数会
 * **复制**一份密钥字节，而那份副本应用侧够不到：没有销毁手段，只能等 GC。
 *
 * 这里有一个非常容易想当然的地方，而且它**在 JVM 和 Android 上结论相反**：
 * `javax.crypto.SecretKey` 继承自 `java.security.Destroyable`，所以
 * "`secretKey.destroy()` 会清掉密钥"看起来是成立的。但 `Destroyable.destroy()`
 * 自 Java 8 起是**默认实现，直接抛 `DestroyFailedException`**，
 * 是否真能清掉取决于具体实现类有没有覆盖它。实测（`javap` 打在 Android SDK 的真实
 * `android.jar` 上）Android 的 `SecretKeySpec` **没有覆盖**：
 *
 * ```
 * public class javax.crypto.spec.SecretKeySpec implements java.security.spec.KeySpec, javax.crypto.SecretKey {
 *   public javax.crypto.spec.SecretKeySpec(byte[], java.lang.String);
 *   public java.lang.String getAlgorithm();
 *   public java.lang.String getFormat();
 *   public byte[] getEncoded();
 *   public int hashCode();
 *   public boolean equals(java.lang.Object);
 *   void clear();            // ← 包级私有，应用侧碰不到
 * }
 * ```
 *
 * 也就是说在 Android 上对 `SecretKeySpec` 调 `destroy()`：**一个字节也清不掉，
 * 还会抛异常**。（OpenJDK 的实现覆盖了它，所以在 JVM 单测里"能清掉"——
 * 这正是这个坑的迷惑性所在：按 JVM 的印象推断 Android，结论是错的。）
 *
 * 由此得到唯一可行的做法：**要能清零，密钥副本就必须由我们自己持有。**
 * 这就是这个类存在的全部理由。
 *
 * 上面那段 `javap` 输出**不是装饰**：它是这个类存在的唯一理由，所以它贴着类声明
 * 而不是挪进某个"通用说明"。看不到它的人会问"为什么不能用平台自带的销毁"，
 * 然后很可能把 `SecretKeySpec` 改成"加上 destroy() 调用"就用回去 ——
 * 那正是被实测否掉的路（连那个 `void clear()` 都是包级私有的）。
 *
 * ## 算法名是构造参数，不是常量
 *
 * `getAlgorithm()` 返回**构造时传入的那一个**。不写成常量、也不从 `material`
 * 推断：两类实例混在日志或测试失败信息里时，必须一眼看出是哪条路。
 *
 * `getFormat()` 两者都是 `"RAW"`（裸密钥字节），这个确实是共用的。
 *
 * ## 生命周期
 *
 * ```
 * TransientSecretKey(material, algorithm)   复制一份，与调用方的数组彻底解耦
 *   getEncoded()                            交出这份副本（Cipher / Mac 只在 init 时读一次）
 *   Cipher.init / Mac.init
 *   …运算…
 *   destroy()                               副本填零，置为已销毁
 * ```
 *
 * 因此不变式是：**只能在密码学操作全部结束之后调用 [destroy]。**
 * 两个调用方都把它放进 `finally`，异常路径同样清到位。
 *
 * 它没有让"内存里的密钥副本"消失 —— 底层实现（Conscrypt / SunJCE）在 `init` 时
 * 仍会把密钥读进自己的上下文（AES 与 HMAC 都实测过：`getEncoded()` 在 `init`
 * 之后不再被调用）。它做到的是：**堆上那份由我们持有的明文副本
 * 有了确定的、立即的终点**，而不是"也许下次 GC 时"。
 */
internal class TransientSecretKey(
    material: ByteArray,
    private val algorithm: String,
) : SecretKey {

    private var material: ByteArray? = material.copyOf()

    override fun getAlgorithm(): String = algorithm

    /** 裸密钥字节。AES 与 HMAC 的 `SecretKeySpec` 也都是 `"RAW"`，这个可以共用。 */
    override fun getFormat(): String = FORMAT

    /**
     * 交出密钥字节。
     *
     * 返回的是**内部数组本身**，不是每次新建的副本：`Cipher` / `Mac` 只在 `init`
     * 时读它，之后密钥已在底层上下文里。多复制一份只会多留一份够不到的明文，
     * 与这个类的目的正好相反。遵守 [SecureBytes.expose] 的同一条约定 ——
     * 调用方不得持有返回的引用。
     */
    override fun getEncoded(): ByteArray =
        material ?: error("密钥副本已销毁，不能再交给 Cipher / Mac")

    /**
     * 清零。**幂等**：已经销毁过就什么都不做，也不重复计数 ——
     * 计数只代表"真的清掉了一份"，重复调用让它虚增会让探针失去意义。
     *
     * 计数按 [algorithm] 分流，未知算法**一个都不计**：计数器代表"某一类密钥
     * 被清掉了几份"，把第三种算法算进某一类会让那条探针从此不可信 ——
     * 宁可它因为"数量对不上"变红，也不要它把一个陌生的东西当成已知的照样绿。
     */
    override fun destroy() {
        val current = material ?: return
        current.fill(0)
        material = null
        when (algorithm) {
            AES -> scrubbedAesKeyCount.incrementAndGet()
            HMAC_SHA256 -> scrubbedHmacKeyCount.incrementAndGet()
            else -> Unit
        }
    }

    override fun isDestroyed(): Boolean = material == null

    /** 绝不能把密钥内容打进日志 —— 这个类的实例会被 `toString()` 顺手用上。 */
    override fun toString(): String = "TransientSecretKey($algorithm, destroyed=$isDestroyed)"

    internal companion object {
        /** 只表示算法族；`AeadCipher` 用的变换串 `"AES/GCM/NoPadding"` 是另一回事。 */
        const val AES = "AES"

        /** 与 [com.fpb.vault.session.VaultSession] 派生行 id 用的算法必须是同一个值。 */
        const val HMAC_SHA256 = "HmacSHA256"

        private const val FORMAT = "RAW"
    }
}
