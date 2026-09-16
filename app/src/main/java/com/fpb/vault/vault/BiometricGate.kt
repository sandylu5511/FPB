package com.fpb.vault.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 生物识别解锁的硬件侧。
 *
 * ## 它保护的到底是什么
 *
 * 不是 DEK。DEK 已经被一把 KEK 包裹着写在密钥文件里，这里做的是
 * **再给那把 KEK 加一道硬件锁**：
 *
 * ```
 * Android Keystore 里的一把 AES 密钥（每次使用都需要通过生物识别）
 *        └─ 加密 → 32 字节随机 KEK
 *                     └─ 该 KEK 就是包裹真库 DEK 的那把（BIOMETRIC 槽）
 * ```
 *
 * 因此"验指纹"这一步不是界面上的仪式，而是**硬件层面拒绝把密钥交出来**：
 * 没有通过生物识别，Keystore 就不会执行那次解密，KEK 也就拿不到。
 * 密钥本身**永远无法从 Keystore 导出**，所以把设备上的文件全部拷走也没用。
 *
 * ## 换指纹就会失效（有意为之）
 *
 * 密钥设了 `setInvalidatedByBiometricEnrollment(true)`：设备上新录入一个指纹，
 * 这把密钥立刻作废。代价是用户加指纹后生物识别解锁会失效（需要重新启用一次），
 * 收益是"知道锁屏密码的人偷偷加自己的指纹"这条路被堵死 ——
 * 对一个以"别人拿到手机也打不开"为卖点的应用，这个取舍是必须的。
 *
 * ## 只能配强生物识别
 *
 * CryptoObject 这条通道要求生物识别是 Strong 级别（绝大多数屏下/背面指纹都满足；
 * 部分机型的 2D 人脸是 Weak 级别，用不了）。这是系统限制，不是这里的选择。
 */
class BiometricGate(private val context: Context) {

    enum class Availability {
        /** 可用。 */
        READY,

        /** 设备没有可用的强生物识别硬件。 */
        NO_HARDWARE,

        /** 有硬件，但用户一个都没录。 */
        NOT_ENROLLED,

        /** 硬件暂时不可用（例如被系统锁定）。 */
        UNAVAILABLE,
    }

    private val wrapFile: File get() = File(context.filesDir, WRAP_FILE_NAME)

    fun availability(): Availability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Availability.NO_HARDWARE
        return when (
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        ) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.UNAVAILABLE
            else -> Availability.NO_HARDWARE
        }
    }

    /** 是否已经存在可用的硬件包裹。文件在但密钥被作废时返回 false。 */
    fun hasEnrollment(): Boolean {
        if (!wrapFile.isFile) return false
        return runCatching {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            store.containsAlias(KEY_ALIAS)
        }.getOrDefault(false)
    }

    /**
     * 准备一个"加密"用的 Cipher，用于把 KEK 存起来。
     * 必须在用户通过生物识别之后才真正使用（见 [storeWrapped] 的调用方）。
     */
    fun newEncryptCipher(): Cipher? = runCatching {
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
    }.getOrNull()

    /** 准备一个"解密"用的 Cipher（IV 从已存的文件里读）。密钥被作废时返回 null。 */
    fun newDecryptCipher(): Cipher? {
        val stored = readWrapped() ?: return null
        return runCatching {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(TAG_BITS, stored.iv),
                )
            }
        }.getOrElse { error ->
            if (error is KeyPermanentlyInvalidatedException ||
                error is UserNotAuthenticatedException ||
                error is UnrecoverableKeyException
            ) {
                clear()
            }
            null
        }
    }

    /** 把 KEK 用硬件密钥加密后落盘。 */
    fun storeWrapped(cipher: Cipher, kek: ByteArray): Boolean = runCatching {
        val ct = cipher.doFinal(kek)
        val iv = cipher.iv ?: return false
        val temp = File(wrapFile.parentFile, wrapFile.name + ".tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(byteArrayOf(iv.size.toByte()))
                out.write(iv)
                out.write(ct)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(wrapFile)) return false
        } finally {
            if (temp.exists()) temp.delete()
        }
        true
    }.getOrDefault(false)

    /** 取出 KEK。[cipher] 必须是生物识别通过后拿到的那个对象。 */
    fun unwrapKek(cipher: Cipher): ByteArray? = runCatching {
        val stored = readWrapped() ?: return null
        cipher.doFinal(stored.ciphertext).takeIf { it.size == KEK_BYTES }
    }.getOrNull()

    /** 关掉生物识别：删掉硬件包裹，并销毁 Keystore 里的那把密钥。 */
    fun clear() {
        runCatching { wrapFile.delete() }
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    // ==================== 内部 ====================

    private class Stored(val iv: ByteArray, val ciphertext: ByteArray)

    private fun readWrapped(): Stored? {
        if (!wrapFile.isFile) return null
        val bytes = runCatching { wrapFile.readBytes() }.getOrNull() ?: return null
        if (bytes.size <= 1) return null
        val ivLength = bytes[0].toInt() and 0xFF
        if (ivLength != IV_LENGTH || bytes.size <= 1 + ivLength) return null
        return Stored(
            iv = bytes.copyOfRange(1, 1 + ivLength),
            ciphertext = bytes.copyOfRange(1 + ivLength, bytes.size),
        )
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        internal const val WRAP_FILE_NAME = StorageNames.BIOMETRIC_WRAP_FILE

        const val KEK_BYTES = 32
        private const val KEY_ALIAS = StorageNames.BIOMETRIC_KEY_ALIAS
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val IV_LENGTH = 12

        /**
         * 拉起系统认证界面。[cipher] 由 [newEncryptCipher] / [newDecryptCipher] 提供，
         * 认证通过后带着同一个 cipher 回到 [onSuccess]。
         */
        fun prompt(
            activity: FragmentActivity,
            cipher: Cipher,
            title: String,
            subtitle: String,
            onSuccess: (Cipher) -> Unit,
            onFailure: (String) -> Unit,
        ) {
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticated = result.cryptoObject?.cipher
                    if (authenticated == null) {
                        onFailure("认证已通过，但没有拿到加密对象")
                    } else {
                        onSuccess(authenticated)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure(errString.toString())
                }

                // 单次指纹不匹配时系统会继续给机会，这里不能当成"整体失败"，
                // 否则用户手指没放正就会看到一次"解锁失败"。
                override fun onAuthenticationFailed() = Unit
            }

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("取消")
                .build()

            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
                .authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }
    }
}
