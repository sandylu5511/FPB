package com.fpb.vault.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id 密钥派生。
 *
 * 选 Argon2id 而不是 PBKDF2 / bcrypt / scrypt：
 * - 击败 PBKDF2 —— PBKDF2 只烧 CPU，显卡和 ASIC 能把它压到极低成本；Argon2 强制占用内存
 * - 击败 bcrypt —— bcrypt 有 72 字节密码截断问题，且内存开销固定不可调
 * - 击败 scrypt —— Argon2id 是密码哈希竞赛（PHC）胜出者，抗侧信道（id = 先 data-independent 再 data-dependent）
 *
 * 关于实现来源：用 BouncyCastle 的纯 Java 实现，**不注册 BouncyCastle 为全局 Provider**。
 * 原因是 Android 上注册 BC Provider 会与系统自带的 Conscrypt 打架，
 * 历史上导致过 TLS 和 Cipher 行为异常。我们只直接调用它的类，AES 仍走平台实现。
 *
 * 纯 Java 实现的额外好处：JVM 单元测试能直接跑同一份生产代码（native 库做不到这点，
 * .so 在单元测试环境加载不了，只能靠仪器测试覆盖）。
 */
object Argon2Kdf {

    /** 输出 32 字节，直接用作 AES-256 的密钥。 */
    const val OUTPUT_BYTES = 32

    /**
     * 从密码派生密钥。
     *
     * 注意：单次调用会占用 [KdfParams.memoryKiB] 的内存，标准档即 64 MiB，
     * 属于耗时且耗内存的操作 —— **必须放到后台线程执行**，否则会 ANR。
     *
     * @param password 调用方输入的密码。用 CharArray 而非 String，便于用后清零。
     */
    fun derive(password: CharArray, params: KdfParams): SecureBytes {
        require(password.isNotEmpty()) { "密码不得为空" }

        val argon2Params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(params.salt)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()

        // 每次都新建实例：Argon2BytesGenerator 内部持有状态，复用有隐患。
        val generator = Argon2BytesGenerator()
        generator.init(argon2Params)

        val out = ByteArray(OUTPUT_BYTES)
        generator.generateBytes(password, out)
        return SecureBytes.takeOwnership(out)
    }

    /** 密码规范化：不做 trim、不做大小写折叠 —— 密码就是原样比较，避免意外降低熵。 */
    fun toChars(password: String): CharArray = password.toCharArray()
}
