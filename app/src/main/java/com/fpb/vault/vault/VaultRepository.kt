package com.fpb.vault.vault

import android.content.Context
import com.fpb.vault.crypto.KdfParams
import com.fpb.vault.crypto.VaultKeyFileCodec
import com.fpb.vault.crypto.VaultKeyFileException
import com.fpb.vault.crypto.VaultKeyring
import com.fpb.vault.data.FileBlobStore
import com.fpb.vault.data.SqliteRowStore
import com.fpb.vault.session.VaultSession
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 密钥文件读不出来。这是一个必须让用户看见的错误 —— 不能退化成"当作新库"。 */
class VaultStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 把加密内核、数据层与 Android 的文件系统装配在一起。
 *
 * 它是**唯一**知道"东西放在哪"的地方：内核与数据层都只接受注入的路径，
 * 自己不知道 `filesDir` 是什么。这样加密逻辑可以在普通 JVM 单测里跑，
 * 而"密钥文件叫什么名字"这类部署细节只在这一个文件里出现。
 *
 * ## 落盘位置
 *
 * ```
 * files/fpb.key         密钥文件（KDF 参数 + salt + 各槽位包裹后的 DEK，不含任何明文密钥）
 * files/bio_wrap.bin    Keystore 硬件密钥包裹的 KEK（仅启用生物识别时存在）
 * files/attachments/    图片密文，文件名是 32 位随机十六进制
 * databases/vault.db    密文行（id / nonce / ciphertext 三列）
 * shared_prefs/...      非敏感偏好（自动锁定时长等）
 * ```
 */
class VaultRepository(private val context: Context) {

    val settings = SettingsStore(context)
    val biometric = BiometricGate(context)

    private val keyFile: File get() = File(context.filesDir, KEY_FILE_NAME)
    private val blobDir: File get() = File(context.filesDir, BLOB_DIR_NAME)

    private var rowStore: SqliteRowStore? = null
    private var blobStore: FileBlobStore? = null
    private var sessionInstance: VaultSession? = null

    /**
     * 已解码的密钥环。
     *
     * 缓存在内存里是安全的：它内部只有**被 KEK 包裹后的** DEK 密文，
     * 没有密码、没有恢复码、也没有裸的 DEK。真正的秘密是解锁时算出来的 KEK。
     * 缓存它换来的是"每次解锁不必重新读盘并重新校验文件格式"。
     */
    private var keyringCache: VaultKeyring? = null

    // ==================== 库是否已存在 ====================

    /** 密钥文件不存在 = 还没建过库。这是判断"走引导流程还是走解锁流程"的唯一依据。 */
    fun hasVault(): Boolean = keyFile.isFile

    /**
     * 读取密钥环。
     *
     * @return null 表示还没有库。
     * @throws VaultStoreException 文件在，但读不出来（损坏、被截断、来自更高版本）。
     *         **绝不能把它当成"没有库"** —— 那会让界面走引导流程，
     *         用户顺手新建一个库，就把原来的密钥文件覆盖掉了。
     */
    fun keyring(): VaultKeyring? {
        keyringCache?.let { return it }
        if (!keyFile.isFile) return null
        val bytes = try {
            keyFile.readBytes()
        } catch (e: IOException) {
            throw VaultStoreException("密钥文件无法读取：${e.message}", e)
        }
        val keyring = try {
            VaultKeyFileCodec.decode(bytes)
        } catch (e: VaultKeyFileException) {
            throw VaultStoreException(e.message ?: "密钥文件损坏", e)
        }
        keyringCache = keyring
        return keyring
    }

    // ==================== 建库与维护 ====================

    /**
     * 新建保险库。
     *
     * 顺序刻意是"先落盘、后交给调用方"：密钥文件写入成功之后，
     * 这个库才算存在。若反过来（先把 DEK 交给界面用），
     * 一次写盘失败就会留下一个"能记东西但重启后打不开"的库。
     */
    fun createVault(
        primaryPassword: CharArray,
        decoyPassword: CharArray?,
        params: KdfParams = KdfParams.standard(),
    ): com.fpb.vault.crypto.CreationResult {
        check(!hasVault()) { "已经存在保险库，拒绝覆盖" }
        val created = VaultKeyring.create(primaryPassword, decoyPassword, params)
        try {
            persist(created.keyring)
        } catch (t: Throwable) {
            // 落盘失败：把刚生成的 DEK 就地销毁，绝不交给界面
            created.primaryDek.close()
            created.decoyDek?.close()
            throw t
        }
        keyringCache = created.keyring
        return created
    }

    /** 密钥环发生变化（改主密码、换假密码、启用生物识别）后落盘并更新缓存。 */
    fun replaceKeyring(updated: VaultKeyring) {
        persist(updated)
        keyringCache = updated
    }

    /**
     * 用"先写临时文件再改名"的方式落盘。
     *
     * 密钥文件是整个库的总开关：写到一半断电，留下的是一个半截文件，
     * 解码时会在某个长度字段上失败 —— 表现为"密码没错但就是打不开"。
     * 改名是文件系统上的原子操作，因此要么是旧文件，要么是新文件。
     */
    private fun persist(keyring: VaultKeyring) {
        val bytes = VaultKeyFileCodec.encode(keyring)
        val target = keyFile
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(target)) {
                throw IOException("密钥文件改名失败：${target.name}")
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** 导出备份时用：当前密钥环的规范字节（与磁盘上的内容等价）。 */
    fun keyFileBytes(): ByteArray? = keyring()?.let { VaultKeyFileCodec.encode(it) }

    // ==================== 路径（只给备份模块用） ====================

    /**
     * 这些"文件在哪"的知识**只留在本文件里**（见类注释）。
     * 备份模块需要直接搬运文件，因此这里开三个只读入口，
     * 而不是让 BackupManager 自己去拼 `filesDir` 路径 ——
     * 那样一旦目录改名，两处就会不一致，而症状是"导出成功但内容不全"。
     */
    fun databaseFile(): File = context.getDatabasePath(SqliteRowStore.DATABASE_NAME)

    fun attachmentsDir(): File = blobDir

    fun keyFileForRestore(): File = keyFile

    /** 恢复备份的暂存目录（放 cache 里，系统可以随时回收）。 */
    fun stagingDir(): File = File(context.cacheDir, "restore-staging")

    // ==================== 会话 ====================

    fun session(): VaultSession {
        sessionInstance?.let { return it }
        val rows = rowStore ?: SqliteRowStore(context).also { rowStore = it }
        val blobs = blobStore ?: FileBlobStore(blobDir).also { blobStore = it }
        return VaultSession(rows, blobs).also {
            it.autoLockMillis = settings.autoLockMillis
            sessionInstance = it
        }
    }

    /** 设置页改了自动锁定时长后调用。 */
    fun applyAutoLock() {
        sessionInstance?.autoLockMillis = settings.autoLockMillis
    }

    // ==================== 核弹按钮 ====================

    /**
     * 抹掉本机的一切数据。
     *
     * 顺序与删除单条笔记一致：**先删可再生的，最后删密钥文件**。
     * 密钥文件一删，剩下的密文就永远不可解读了；反过来先删密钥文件
     * 而中途失败，会留下一个"解密不了但还在占空间"的残骸。
     *
     * 注意它连 SharedPreferences 一起清掉 —— 否则改过截屏开关的用户
     * 在新库里会看到上一轮的设置，那属于信息泄漏。
     */
    fun wipeEverything() {
        sessionInstance?.let { runCatching { it.lock() } }
        sessionInstance = null
        runCatching { rowStore?.close() }
        rowStore = null
        blobStore = null
        keyringCache = null

        val db = context.getDatabasePath(SqliteRowStore.DATABASE_NAME)
        listOf(db, File(db.path + "-journal"), File(db.path + "-wal"), File(db.path + "-shm"))
            .forEach { runCatching { it.delete() } }

        blobDir.listFiles()?.forEach { runCatching { it.delete() } }
        runCatching { blobDir.delete() }

        File(context.filesDir, BiometricGate.WRAP_FILE_NAME).let { runCatching { it.delete() } }
        runCatching { keyFile.delete() }
        runCatching { File(keyFile.parentFile, keyFile.name + TEMP_SUFFIX).delete() }

        settings.clearAll()
    }

    fun close() {
        sessionInstance?.let { runCatching { it.lock() } }
        runCatching { rowStore?.close() }
        sessionInstance = null
        rowStore = null
        blobStore = null
        keyringCache = null
    }

    companion object {
        private const val KEY_FILE_NAME = StorageNames.KEY_FILE
        private const val BLOB_DIR_NAME = StorageNames.BLOB_DIR
        private const val TEMP_SUFFIX = StorageNames.TEMP_SUFFIX
    }
}
