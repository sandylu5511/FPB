package com.fpb.vault.crypto

/**
 * 密钥派生参数。
 *
 * 这些值必须随密钥文件一起落盘 —— 换机导入备份时要用**完全相同的参数和 salt**
 * 才能派生出同一把 KEK。任何一项不同都会导致解密失败。
 */
class KdfParams(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val salt: ByteArray,
) {

    init {
        require(memoryKiB in MIN_MEMORY_KIB..MAX_MEMORY_KIB) {
            "内存开销越界：$memoryKiB KiB（允许 $MIN_MEMORY_KIB..$MAX_MEMORY_KIB）"
        }
        require(iterations in 1..MAX_ITERATIONS) {
            "迭代次数越界：$iterations（允许 1..$MAX_ITERATIONS）"
        }
        require(parallelism in 1..MAX_PARALLELISM) { "并行度越界：$parallelism" }
        require(salt.size == SALT_BYTES) { "salt 长度必须为 $SALT_BYTES 字节，实际 ${salt.size}" }
    }

    /** ByteArray 是引用类型，data class 生成的 equals 会退化成引用比较，因此手写。 */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KdfParams) return false
        return memoryKiB == other.memoryKiB &&
            iterations == other.iterations &&
            parallelism == other.parallelism &&
            SecureBytes.constantTimeEquals(salt, other.salt)
    }

    override fun hashCode(): Int {
        var result = memoryKiB
        result = 31 * result + iterations
        result = 31 * result + parallelism
        result = 31 * result + salt.contentHashCode()
        return result
    }

    override fun toString(): String =
        "KdfParams(mem=${memoryKiB}KiB, t=$iterations, p=$parallelism, salt=${salt.size}B)"

    companion object {
        const val SALT_BYTES = 32
        const val MIN_MEMORY_KIB = 8 * 1024
        const val MAX_PARALLELISM = 16

        /**
         * 内存开销的上界：1 GiB。
         *
         * 这个上界不是给"我们自己选参数"用的（预设三档最高才 256 MiB），
         * 而是给**从密钥文件里读进来的参数**用的。密钥文件将来会随备份包导入，
         * 而备份包可能来自网盘或别人发来的压缩包。
         *
         * 少了这道校验，一个把 `memoryKiB` 写成 `Int.MAX_VALUE`（约 2 TiB）的伪造文件
         * 会让 Argon2 直接尝试按此规模分配内存，抛出的是 `OutOfMemoryError` ——
         * 它属于 `Error` 而不是 `Exception`，`VaultKeyFileCodec.decode` 里的
         * `catch (Exception)` 根本接不住，应用当场崩溃且给不出可理解的提示。
         * `iterations` 同理：写成一个天文数字表现为"解锁永久卡死"。
         */
        const val MAX_MEMORY_KIB = 1024 * 1024

        /** 迭代次数上界。预设档位最高为 4，留足余量的同时杜绝"卡死型"参数。 */
        const val MAX_ITERATIONS = 64

        private fun withFreshSalt(memoryKiB: Int, iterations: Int, parallelism: Int) =
            KdfParams(memoryKiB, iterations, parallelism, SecureBytes.random(SALT_BYTES))

        /**
         * 标准档（默认）：64 MiB / t=3 / p=2。
         *
         * 这个取值来自本机实测而非拍脑袋：tools/Argon2Bench.java 在 PC 上量到
         * 平均 155 ms，按手机端 3~5 倍劣化估算约 0.5~0.8 s，正好是"用户可感知但不烦"的区间。
         * 同时也是 OWASP 对 Argon2id 的推荐下限（19 MiB）的三倍多。
         */
        fun standard() = withFreshSalt(64 * 1024, 3, 2)

        /** 高安全档：解锁约 1.5~2.5 s，低端机可能卡顿。 */
        fun highSecurity() = withFreshSalt(256 * 1024, 4, 2)

        /** 流畅档：解锁几乎无感，抗暴力破解仍远强于 PBKDF2 默认配置。 */
        fun fast() = withFreshSalt(32 * 1024, 2, 1)
    }
}
