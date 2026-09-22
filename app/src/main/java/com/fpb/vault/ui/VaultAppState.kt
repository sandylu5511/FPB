package com.fpb.vault.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.fpb.vault.crypto.CreationResult
import com.fpb.vault.crypto.KdfParams
import com.fpb.vault.crypto.KeySlot
import com.fpb.vault.crypto.RecoveryCode
import com.fpb.vault.crypto.UnlockOutcome
import com.fpb.vault.crypto.VaultDek
import com.fpb.vault.crypto.VaultDomain
import com.fpb.vault.crypto.VaultKeyring
import com.fpb.vault.data.BlobEmptyException
import com.fpb.vault.data.BlobReader
import com.fpb.vault.data.BlobTooLargeException
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.model.VaultNote
import com.fpb.vault.model.VideoRef
import com.fpb.vault.session.AuditKey
import com.fpb.vault.session.LoginEvent
import com.fpb.vault.session.LoginKind
import com.fpb.vault.session.TagCount
import com.fpb.vault.session.VaultSession
import com.fpb.vault.vault.BackupInfo
import com.fpb.vault.vault.BackupManager
import com.fpb.vault.vault.BackupReminder
import com.fpb.vault.vault.BitmapCache
import com.fpb.vault.vault.BiometricGate
import com.fpb.vault.vault.ImagePipeline
import com.fpb.vault.vault.LauncherIcon
import com.fpb.vault.vault.MotionPhoto
import com.fpb.vault.vault.PhotoRecord
import com.fpb.vault.vault.SettingsStore
import com.fpb.vault.vault.ThemeMode
import com.fpb.vault.vault.VaultRepository
import com.fpb.vault.vault.VaultStoreException
import com.fpb.vault.vault.VideoPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 解锁之后可导航到的位置。 */
sealed interface Route {
    data object Home : Route
    data class Editor(val type: NoteType, val noteId: String?) : Route

    /** 只读详情页。点列表卡片先进这里，想改再点"编辑"进 [Editor]。 */
    data class View(val noteId: String) : Route
    data class Viewer(val blobIds: List<String>, val startIndex: Int) : Route

    /** 媒体库：聚合全部照片与视频的网格视图。诱饵库里不提供入口。 */
    data object Photos : Route
    data object Settings : Route

    /**
     * 登录记录。
     *
     * 走独立路由而不是设置页里的一个对话框：它是一张会长的列表（最多 200 条），
     * 对话框里滚动列表既装不下也点不准。
     */
    data object LoginLog : Route
}

/**
 * 一份媒体文件（照片或视频）在库里的坐标：属于哪条记录、是哪个 blob。
 *
 * 媒体库是一张**跨记录摊平**的网格，所以"删掉这一格"必须同时说清楚它在哪条记录里 ——
 * 只说 blobId 的话，删完之后不知道该更新哪条记录。
 *
 * 照片与视频共用这一个类型：删除的那套顺序纪律（先改可回滚的记录行与清单、
 * 最后销毁不可再生的密文）对两者完全一样，分成两个类型只会变成两份迟早走样的实现。
 */
data class MediaRef(val noteId: String, val blobId: String)

/**
 * 一份附件在全屏查看器里该被当成什么。
 *
 * 全屏查看器只拿到一串 blobId（它是一条跨记录的媒体序列），必须能判断
 * "这一页该当图看还是当视频放"。类型来自**记录内容**而不是文件本身 ——
 * 这也正是它不该被猜的原因：一个整块格式的附件可能是图片，也可能是实况照片
 * （尾部带 MP4），而分块格式的一定是视频。
 *
 * [video] 只对视频有值，并且它带的是**已经按旋转角摆正**的宽高（见 [VideoPipeline.probe]）。
 * 播放窗口的比例必须用这里这份，不能用播放器回调给的原始帧尺寸：
 * 手机竖拍的视频常常是 1920×1080 的帧 + 90° 旋转标记，拿原始帧尺寸去摆窗口，
 * 画面就会横躺着被压扁。
 */
data class BlobKind(val type: NoteType, val video: VideoRef? = null)

/**
 * 一次视频导入的结局。
 *
 * 用密封类而不是 `VideoRef?`，是为了让**失败的原因能被如实说出来**：
 * "这个视频有 2.4 GB，超过上限"和"这个文件不是视频"对用户是完全不同的两件事，
 * 而它们都只表现为"没导进去"。
 */
sealed interface VideoImport {
    class Ready(val video: VideoRef, val plainBytes: Long) : VideoImport

    /** [message] 是可以直接显示给用户的一句话。 */
    class Rejected(val message: String) : VideoImport
}

/**
 * 界面侧的唯一状态持有者。
 *
 * ## 为什么不是 ViewModel
 *
 * 整个应用只有一个 Activity、一个会话、一份内存索引，它们必须共享同一个生命周期；
 * 拆成多个 ViewModel 会让"锁定时把明文全部丢掉"这件事变成跨对象的协作问题 ——
 * 而现在它只是一个方法（[lock]）。
 *
 * 实例由 Activity 持有（配合 `android:configChanges`，旋转不会重建），
 * 因此屏幕旋转不会丢掉解锁状态，也不会让 Argon2 重新跑一遍。
 *
 * ## 线程约定
 *
 * 所有会触碰 Compose 状态的赋值都发生在主线程。加密与磁盘操作一律
 * `withContext(Dispatchers.Default / IO)` 之后再把结果带回来 ——
 * 解锁要跑 Argon2id（标准档几百毫秒到数秒），放在主线程会直接 ANR。
 */
@Stable
class VaultAppState(private val context: Context) {

    enum class Phase {
        /** 正在读密钥文件、预热 KDF。 */
        LOADING,

        /** 还没有库，走引导流程。 */
        ONBOARDING,

        /** 有库，等待解锁。 */
        LOCKED,

        /** 已解锁。 */
        UNLOCKED,
    }

    val repository = VaultRepository(context)

    val settings: SettingsStore get() = repository.settings
    val biometric: BiometricGate get() = repository.biometric
    val session: VaultSession get() = repository.session()

    val bitmapCache = BitmapCache()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ==================== 可观察状态 ====================

    var phase by mutableStateOf(Phase.LOADING)
        private set

    /** 导航栈。栈底永远是 [Route.Home]。 */
    var routes by mutableStateOf<List<Route>>(listOf(Route.Home))
        private set

    var notes by mutableStateOf<List<VaultNote>>(emptyList())
        private set

    var tagCounts by mutableStateOf<List<TagCount>>(emptyList())
        private set

    var tagFilter by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    /** 正在跑耗时操作（解锁、导出…），界面据此禁用按钮。 */
    var busy by mutableStateOf(false)
        private set

    /**
     * 一次性提示（错误或成功），展示后由界面清空。
     *
     * 名字叫 notice 而不是 message，是为了给 [setMessage] 让路：
     * `var message` 会自动生成一个 `setMessage(String)`，与同名函数在 JVM 层撞签名。
     * 读它的地方很少（只有承载 Snackbar 的那一处），写它一律走 [setMessage]。
     */
    var notice by mutableStateOf<String?>(null)

    /** 冷启动时密钥文件读不出来 —— 这不是"没有库"，必须让用户看见。 */
    var bootError by mutableStateOf<String?>(null)
        private set

    /** 本次解锁发现的问题条目数（缺失/损坏）。非 null 时列表页顶部显示告警条。 */
    var loadWarning by mutableStateOf<String?>(null)
        private set

    /**
     * 本次解锁发现的问题条目数（缺失 + 损坏）。0 = 库干净。
     *
     * 与 [loadWarning] 并存而不是从它反推：告警条是一句话，而设置页要的是
     * 一个可以判零的数（"库状态：正常 / 有 N 条异常"）。从文案里抠数字出来
     * 是把展示格式当数据用，改一次文案就会把那个判断一起改坏。
     */
    var loadProblemCount by mutableStateOf(0)
        private set

    /**
     * 「该导出备份了」的文案。null = 不需要提醒。
     *
     * 每次 [enterUnlocked] 用 [BackupReminder.eval] 重新判定，而不是常驻一个布尔值：
     * 判定依赖"当前库有没有内容"与"现在几点"，这两样在会话期间都会变。
     *
     * 存的是**文案**而不是枚举，是因为"从未导出过"与"超过 7 天没导出"要说不同的话，
     * 而这两句话属于界面语言，跟本类里其余提示放在一起比散到屏幕文件里更好维护。
     */
    var backupNotice by mutableStateOf<String?>(null)
        private set

    /**
     * 当前会话是否落在诱饵库（假密码进入）。
     *
     * 照片库这类"真库专属"的入口靠它隐藏：诱饵库的使命是看起来正常，
     * 任何"这里应该有东西却空着"的暗示都是减分项。
     */
    val isDecoy: Boolean get() = session.isDecoy

    // ==================== 登录记录 ====================

    /**
     * 登录记录，最新在前。
     *
     * 真库会话拿到的是**两本账合起来**的：真库自己那本 + 用审计钥匙读出来的
     * 诱饵库那本（见 [VaultSession] 里 [AuditKey] 一段的说明）。
     * 诱饵会话只有自己那本 —— 它读不到真库那本，连那一行叫什么名字都算不出来。
     */
    var loginEvents by mutableStateOf<List<LoginEvent>>(emptyList())
        private set

    /** 账本读不出来（**不是**"没有记录"）。界面必须把这两件事分开说。 */
    var loginLogUnreadable by mutableStateOf(false)
        private set

    /** 已经到上限、更早的记录被挤掉了。界面据此说明"更早的看不到了"。 */
    var loginLogTruncated by mutableStateOf(false)
        private set

    /**
     * 真库侧是否已经建立了审计通道。
     *
     * 只有它为 true 时，"假密码的登录"才看得到。存量用户升上来时它是 false
     * （这台设备从没建立过通道），需要在记录页里用假密码补一次绑定。
     */
    var decoyLogBound by mutableStateOf(false)
        private set

    // ==================== 设置的镜像状态 ====================
    //
    // SharedPreferences 的写入**不会**触发 Compose 重组 —— 它只是个文件。
    // 因此凡是"用户改完界面要立刻跟着变"的偏好，都在这里留一份可观察副本，
    // 并且只允许通过下面这几个方法写入，保证两边永远同步。
    // 直接读 settings.xxx 的地方（例如解锁流程）不受影响，它们只关心当前值。

    /** 是否禁止截屏。AppRoot 监听它来开关 FLAG_SECURE。 */
    var blockScreenshots by mutableStateOf(settings.blockScreenshots)
        private set

    var autoLockMillis by mutableStateOf(settings.autoLockMillis)
        private set

    var biometricEnabled by mutableStateOf(settings.biometricEnabled)
        private set

    /**
     * 生物识别绑定被安全加固换代作废，需要用户重新启用一次。
     *
     * 界面必须拿它说一句话。**没有它就会变成一次静默失效**：用户的感受是
     * "指纹解锁的按钮不见了"，而设置页上看不出任何异常 —— 设置自己变掉、
     * 又查不出原因，比被要求重新点一次开关糟糕得多。
     */
    var biometricReenrollNeeded by mutableStateOf(settings.biometricReenrollNeeded)
        private set


    var launcherAlias by mutableStateOf(settings.launcherAlias)
        private set

    var secondarySlotConfigured by mutableStateOf(settings.secondarySlotConfigured)
        private set

    /**
     * 外观模式的三档之一（跟随系统/浅色/深色）。
     *
     * 这份镜像状态是**必须的**：`FpbTheme` 就挂在 setContent 的根上，
     * 它只认 Compose 状态 —— 光把值写进 SharedPreferences 不会触发任何重组，
     * 用户点完"深色"会看不到任何变化。
     */
    var themeMode by mutableStateOf(settings.themeMode)
        private set

    // ==================== 启动 ====================

    private var booted = false

    fun boot() {
        if (booted) return
        booted = true
        repairLauncherAlias()
        scope.launch {
            retireLegacyBiometricBinding()

            val loaded = withContext(Dispatchers.IO) { runCatching { repository.keyring() } }
            val keyring = loaded.getOrNull()
            val failure = loaded.exceptionOrNull()

            // 先把 Argon2 的代码路径焐热：首次派生在模拟器上要 29 秒，
            // 全部来自 ART 解释执行与 64 MiB 大数组的首次分配。
            // 放在启动时后台跑，用户输密码那一刻就只剩正常耗时了。
            withContext(Dispatchers.Default) {
                runCatching { VaultSession.warmUpKdf(keyring?.params ?: KdfParams.standard()) }
            }

            phase = when {
                failure is VaultStoreException -> {
                    bootError = failure.message
                    Phase.LOCKED
                }
                keyring == null -> Phase.ONBOARDING
                else -> Phase.LOCKED
            }
        }
    }

    /**
     * 作废上一代（v1）的硬件绑定，并在确实作废了东西时告诉用户重新启用一次。
     *
     * 为什么必须在**启动时**做，而不是等用户自己去看设置：做之前的盘面是
     * "设置里显示已启用，解锁页却不给指纹按钮"（`hasEnrollment()` 那时已经是 false）。
     * 那是一个自相矛盾的状态，而且用户完全无从理解它 —— 他只会觉得指纹功能坏了。
     * 先把状态摆正、再给一句解释，才是"可接受的重新启用一次"。
     *
     * 判据交给 [BiometricGate.retireLegacyEnrollment]，这里不做任何猜测：
     * 只有"Keystore 里真的躺着 v1 那个别名"时才动东西。已经用上 v2 的设备
     * （包裹文件同名，只看文件会误伤）在这一步什么都不发生。
     */
    private suspend fun retireLegacyBiometricBinding() {
        val retired = withContext(Dispatchers.IO) {
            runCatching { biometric.retireLegacyEnrollment() }.getOrDefault(false)
        }
        if (!retired) return

        val wasEnabled = settings.biometricEnabled
        settings.biometricEnabled = false
        biometricEnabled = false
        // 只在"它本来是个能用的设置"时提示。本来就关着指纹的人不该收到这条通知 ——
        // 对他来说没有发生任何变化，多一句话只会让人以为哪里出错了。
        if (wasEnabled) {
            settings.biometricReenrollNeeded = true
            biometricReenrollNeeded = true
        }
    }

    // ==================== 建库 ====================

    /** @return 成功时返回建库结果（含恢复码与尚未交出的 DEK）；失败返回 null 并设置 [message]。 */
    suspend fun createVault(primary: CharArray, decoy: CharArray?): CreationResult? =
        withContext(Dispatchers.Default) {
            runCatching {
                repository.createVault(primary, decoy, KdfParams.standard())
            }.getOrElse { error ->
                setMessage(if (error is IllegalArgumentException) error.message ?: "参数不合法" else "创建失败：${error.message}")
                null
            }
        }

    /** 引导流程走完，把 DEK 交给会话。 */
    fun finishOnboarding(created: CreationResult, recoveryConfirmed: Boolean) {
        if (!recoveryConfirmed) {
            // 恢复码没抄就进来的话，忘密码就真的没救了。这里不放行。
            setMessage("请先确认已经抄下恢复码")
            return
        }
        settings.onboardingDone = true
        // 引导时勾了假密码的话，必须同步把"第二槽已配置"的标志写上。
        // 漏掉这一步的症状正是用户报过的 bug：引导页明明设了假密码，
        // 进设置一看却显示"未开启"，只能再设一遍 —— 密钥文件里 DECOY 槽其实一直是好的，
        // 坏的只是这个给界面读的镜像标志。
        if (created.hasDecoy) {
            settings.secondarySlotConfigured = true
            secondarySlotConfigured = true
        }
        val outcome = UnlockOutcome.Unlocked(KeySlot.PRIMARY, created.primaryDek)
        val report = runCatching { session.unlock(outcome) }.getOrElse { error ->
            setMessage("初始化失败：${error.message}")
            return
        }
        applyLoadReport(report)
        enterUnlocked()
    }

    // ==================== 解锁 ====================

    sealed interface UnlockFeedback {
        data object Success : UnlockFeedback
        data class Failure(val message: String) : UnlockFeedback
    }

    suspend fun unlockWithPassword(password: CharArray): UnlockFeedback {
        if (busy) return UnlockFeedback.Failure("正在处理中")
        busy = true
        val result = withContext(Dispatchers.Default) { runPasswordUnlock(password) }
        busy = false
        if (result is UnlockFeedback.Success) enterUnlocked()
        return result
    }

    private fun runPasswordUnlock(password: CharArray): UnlockFeedback {
        val keyring = try {
            repository.keyring() ?: return UnlockFeedback.Failure("本机没有找到密钥文件")
        } catch (e: VaultStoreException) {
            return UnlockFeedback.Failure(e.message ?: "密钥文件损坏")
        }
        return when (val outcome = keyring.unlock(password)) {
            is UnlockOutcome.Unlocked -> openSession(outcome)
            UnlockOutcome.Rejected -> {
                noteFailedUnlock(password.isNotEmpty())
                UnlockFeedback.Failure("密码不正确")
            }
        }
    }

    suspend fun unlockWithRecoveryCode(input: String): UnlockFeedback {
        if (busy) return UnlockFeedback.Failure("正在处理中")
        busy = true
        val result = withContext(Dispatchers.Default) {
            val keyring = try {
                repository.keyring() ?: return@withContext UnlockFeedback.Failure("本机没有找到密钥文件")
            } catch (e: VaultStoreException) {
                return@withContext UnlockFeedback.Failure(e.message ?: "密钥文件损坏")
            }
            val dek = keyring.unlockWithRecoveryCode(input)
            if (dek == null) {
                // 抄错恢复码也算输错。不记的话，"有人拿着恢复码在试"这件事
                // 会完全不留痕 —— 而恢复码是唯一能绕过主密码的东西。
                noteFailedUnlock(input.isNotBlank())
                return@withContext UnlockFeedback.Failure("恢复码不正确")
            }
            openSession(UnlockOutcome.Unlocked(KeySlot.RECOVERY, dek))
        }
        busy = false
        if (result is UnlockFeedback.Success) enterUnlocked()
        return result
    }

    /**
     * 生物识别解锁。
     *
     * 全程不跑 Argon2：KEK 是硬件解出来的，密码学强度来自 Keystore，
     * 因此这里没有需要挪到后台的耗时步骤。
     */
    fun unlockWithBiometric(activity: FragmentActivity) {
        if (!settings.biometricEnabled) {
            setMessage("尚未启用生物识别")
            return
        }
        val keyring = try {
            repository.keyring()
        } catch (e: VaultStoreException) {
            setMessage(e.message ?: "密钥文件损坏")
            return
        } ?: run {
            setMessage("本机没有找到密钥文件")
            return
        }

        val cipher = biometric.newDecryptCipher()
        if (cipher == null) {
            disableBiometric("生物识别密钥已失效（可能因为新增了指纹），请用主密码解锁后重新启用")
            return
        }

        BiometricGate.prompt(
            activity = activity,
            cipher = cipher,
            title = "解锁 FPB",
            subtitle = "验证指纹以打开保险库",
            onSuccess = { authenticated ->
                val kek = biometric.unwrapKek(authenticated)
                if (kek == null) {
                    setMessage("生物识别数据不可用，请用主密码解锁")
                    return@prompt
                }
                val dek = keyring.unwrapWithExternalKek(KeySlot.BIOMETRIC, kek)
                if (dek == null) {
                    setMessage("生物识别凭据与当前保险库不匹配，请用主密码解锁")
                    return@prompt
                }
                when (val feedback = openSession(UnlockOutcome.Unlocked(KeySlot.BIOMETRIC, dek))) {
                    is UnlockFeedback.Success -> enterUnlocked()
                    is UnlockFeedback.Failure -> setMessage(feedback.message)
                }
            },
            onFailure = { reason -> setMessage(reason) },
        )
    }

    /**
     * 真正打开会话，并记一笔登录。
     *
     * 记录的类型**由槽推出**（[kindOf]），不由调用点传进来：同一个"输密码"入口
     * 既可能开真库也可能开假库，在调用点硬写一个类型，会在假密码进来时
     * 记成"用真密码打开保险库" —— 那是一条彻底错误的记录，而且看起来完全正常。
     */
    private fun openSession(outcome: UnlockOutcome.Unlocked): UnlockFeedback = try {
        val report = session.unlock(outcome)
        applyLoadReport(report)
        logSuccessfulUnlock(kindOf(outcome.slot))
        UnlockFeedback.Success
    } catch (t: Throwable) {
        UnlockFeedback.Failure(t.message ?: "保险库打不开")
    }

    /**
     * 槽 → 记录类型。
     *
     * 返回可空：将来若新增槽位而忘了在这里映射，结果是**宁可不记，也不记错**。
     * 猜一个类型（比如默认成真密码）会凭空造出一条假记录，
     * 而漏记一条至少不会骗人。
     *
     * 注意建库那一步不走 [openSession]（它直接开会话），因此新建的库
     * 不会留下一条"刚建好就登录一次"的记录。
     */
    private fun kindOf(slot: KeySlot): LoginKind? = when (slot) {
        KeySlot.PRIMARY -> LoginKind.REAL_PASSWORD
        KeySlot.DECOY -> LoginKind.DECOY_PASSWORD
        KeySlot.RECOVERY -> LoginKind.RECOVERY_CODE
        KeySlot.BIOMETRIC -> LoginKind.BIOMETRIC
    }

    /**
     * 这一次成功解锁：记一笔，并把"在此之前输错过几次"折成一条记录，然后把计数器清零。
     *
     * ## 三条记录合成一次写入
     *
     * 分两次写（先记失败、再记登录）的话，中间那次失败会留下一个坏状态：
     * 失败计数已经清零、而记录没落地 —— 那批失败**凭空消失**。
     * 见 [VaultSession.recordLogins]。
     *
     * ## 清零只发生在写入成功之后
     *
     * 写失败了就把计数留着，下一次解锁再记。宁可同一批失败被记两遍
     * （用户看得见，也解释得通），也不能让它无声消失。
     *
     * ## 记账失败为什么不阻断解锁
     *
     * 记录不是用户的内容，为一条日志把用户挡在自己的库外面是本末倒置 ——
     * 那正好帮了想进来的人。但也不能装作没发生：所以这里只把失败变成一句提示。
     * 静默的后果是"记录里没有"被读成"没人进来过"，那正是这个功能最不该犯的错。
     */
    private fun logSuccessfulUnlock(logAs: LoginKind?) {
        val now = System.currentTimeMillis()
        if (logAs != null) {
            val pending = settings.failedAttempts
            val events = buildList {
                if (pending > 0) {
                    // 时刻取"最后一次输错"的时间，而不是现在 —— 否则一批凌晨的尝试
                    // 会被画成"刚刚发生"，用户会以为此刻正有人在门口。
                    add(
                        LoginEvent(
                            LoginKind.FAILED_ATTEMPTS,
                            settings.lastFailedAt.takeIf { it > 0 } ?: now,
                            pending,
                        ),
                    )
                }
                add(LoginEvent(logAs, now))
            }
            runCatching { session.recordLogins(events) }
                .onSuccess { if (pending > 0) settings.clearFailedAttempts() }
                .onFailure { setMessage("登录记录没能写入：${it.message ?: "未知原因"}") }
        }
        refreshLoginLog()
    }

    /**
     * 把账本读进界面状态。真库会话会把**诱饵库那本账也读出来**并进来 ——
     * 这正是"有人用了假密码"能被真库发现的地方。
     *
     * 四种情况必须分开表达，它们看起来都像"空"但意思相反：
     * 一本空账 = 确实还没人进来过；一本读不出来的账 = 有人动过手；
     * "真库没有审计槽" = 这个功能还没建立（正常，补一次绑定就好）；
     * "审计槽在但解不开" = 通道本身被人动过。
     */
    private fun refreshLoginLog() {
        if (!session.isUnlocked) return
        var merged = session.loginLog()
        var unreadable = session.loginLogUnreadable()

        // 诱饵会话自己没有登录记录页，但状态必须摆正：
        // 否则锁上再用真密码进来时，界面会先显示上一个会话留下的列表。
        if (session.isDecoy) {
            loginEvents = merged.newestFirst()
            loginLogTruncated = merged.isTruncated
            loginLogUnreadable = unreadable
            decoyLogBound = false
            return
        }

        var bound = false
        when (val slot = session.auditSlot()) {
            // 没有槽 = 这台设备还没建立审计通道（存量用户升上来就是这个状态）。
            // 这是**正常状态**：界面给一个"用假密码确认一次"的入口。
            is VaultSession.AuditSlot.Absent -> Unit

            // 槽在、但解不开。**绝不能当成"没有槽"**：那会把一次可能的篡改
            // 显示成"这个功能还没开"，而这是这个页面最不该犯的错。
            is VaultSession.AuditSlot.Damaged -> {
                bound = true
                unreadable = true
            }

            is VaultSession.AuditSlot.Present -> {
                bound = true
                try {
                    when (val foreign = session.decoyLoginLog(slot.key)) {
                        // null = 那一行在、但解不出来。同样不能当成"空的"：
                        // 否则一段被破坏的记录会显示成"假密码从没被用过"。
                        null -> unreadable = true
                        else -> merged = merged.merged(foreign)
                    }
                } finally {
                    slot.key.close()
                }
            }
        }
        loginEvents = merged.newestFirst()
        loginLogTruncated = merged.isTruncated
        loginLogUnreadable = unreadable
        decoyLogBound = bound
    }

    /**
     * 记一次解锁失败（密码或恢复码被拒）。
     *
     * 只累加一个明文计数，**不写账本**：此刻手上没有任何密钥，写不了密文
     * （见 [SettingsStore.failedAttempts]）。真正的记录发生在下一次成功解锁时。
     *
     * 空输入不算：那是"什么都没输就按了解锁"，把它算成"输错一次"会让记录里
     * 凭空多出一批根本没人尝试过的失败 —— 一条不值得信的记录比没有记录更糟。
     *
     * **生物识别的失败与取消不记**（见 [unlockWithBiometric] 的分支）：
     * 那不是"输错密码"，把它算进来会让记录里多出用户自己按了取消的那些次，
     * 次数一多，"有人在试你的密码"这句提示就彻底失去意义了。
     */
    private fun noteFailedUnlock(submitted: Boolean) {
        if (!submitted) return
        settings.noteFailedAttempt(System.currentTimeMillis())
    }

    /**
     * 把解锁时那份加载报告摊成界面上要用的两个东西：一句告警、一个可以判零的数。
     *
     * [loadProblemCount] **必须在这里赋值** —— 它是设置页「库状态」那一行的唯一来源。
     * 只声明不赋值的话属性恒为初始值 0，界面会一直说"正常"：
     * 而"永远说正常"比"不显示这一行"更糟，它把一件用户能感知的异常
     * （打不开的条目、找不到的记录）盖成了"没问题"。
     *
     * （文案本身由 [VaultSession.LoadReport.warning] 负责，见那里的说明。）
     */
    private fun applyLoadReport(report: VaultSession.LoadReport) {
        loadProblemCount = report.problemCount
        loadWarning = report.warning()
    }

    private fun enterUnlocked() {
        session.autoLockMillis = settings.autoLockMillis
        reload()
        popToHome()
        query = ""
        tagFilter = null
        refreshBackupReminder()
        phase = Phase.UNLOCKED
    }

    /**
     * 重新判定"该不该催用户导出备份"。
     *
     * 这是 [BackupReminder] 的**唯一调用点** —— 在此之前，
     * [SettingsStore.lastExportedAt] 只有写入端没有读取端，
     * 四个文件的注释承诺的"周期性提醒"一次也没有真的出现过。
     *
     * 只在解锁时判定（而不是像自动锁那样每 3 秒轮询）：
     * 判定结果的粒度是天，会话期间重新算没有意义，反而会让提示忽隐忽现。
     */
    private fun refreshBackupReminder() {
        val due = BackupReminder.eval(
            lastExportedAt = settings.lastExportedAt,
            hasContent = session.noteCount > 0,
            now = System.currentTimeMillis(),
        )
        backupNotice = when (due) {
            BackupReminder.Due.NONE -> null
            BackupReminder.Due.NEVER_EXPORTED ->
                "还没有导出过备份。内容只在这台设备上，手机丢失、损坏或应用被卸载后都无法找回。点这里去导出"
            BackupReminder.Due.STALE ->
                "上次导出备份已经超过 7 天。点这里再导出一份，存到电脑或网盘上"
        }
    }

    /**
     * 导出成功后调用：重置计时并撤下提醒。
     *
     * 由 [exportBackup] 的调用方（设置页）在**确认导出成功之后**触发，
     * 不能在 [exportBackup] 内部无条件记：导出失败也记时间戳的话，
     * 用户会被"我以为备份过了"骗上 7 天。
     */
    fun markBackupReminder() {
        settings.lastExportedAt = System.currentTimeMillis()
        backupNotice = null
    }

    // ==================== 锁定 ====================

    fun lock() {
        session.lock()
        // 明文图像只活在内存里，锁定时必须一起丢掉
        bitmapCache.clear()
        notes = emptyList()
        tagCounts = emptyList()
        popToHome()
        query = ""
        tagFilter = null
        phase = Phase.LOCKED
    }

    fun onBackgrounded() {
        if (phase == Phase.UNLOCKED) session.onBackgrounded()
    }

    fun onForegrounded() {
        if (session.onForegrounded()) lock()
    }

    fun tick() {
        session.tick()
        if (phase == Phase.UNLOCKED && !session.isUnlocked) lock()
    }

    // ==================== 读 ====================

    fun reload() {
        if (!session.isUnlocked) return
        notes = session.notes()
        tagCounts = session.tagCounts()
    }

    fun visibleNotes(): List<VaultNote> {
        if (!session.isUnlocked) return emptyList()
        val byTag = tagFilter?.let { tag -> notes.filter { tag in it.tags } } ?: notes
        val keyword = query.trim()
        if (keyword.isEmpty()) return byTag
        // 搜索走会话里那份小写检索缓存：这里按 id 取出对应文本再匹配，
        // 避免对每条笔记重新拼一遍可搜索文本。
        return byTag.filter { session.search(keyword).any { hit -> hit.id == it.id } }
    }

    fun note(id: String): VaultNote? = runCatching { session.note(id) }.getOrNull()

    // ==================== 写 ====================

    fun createNote(payload: NotePayload): VaultNote? = guarded("保存失败") {
        session.create(payload)
    }?.also { reload() }

    fun updateNote(id: String, payload: NotePayload): VaultNote? {
        val saved = guarded("保存失败") { session.update(id, payload) } ?: return null
        // 保存之后可能变成"只剩照片的空壳"（在编辑器里把图片全移除了）。
        // 该不该消失是**一条不变量**，与图库里删光照片是同一条，所以处置也必须一致 ——
        // 只在一个路径上做，就成了"成对操作只做了一半"那类缺陷。
        if (PhotoRecord.isShell(saved.payload)) {
            deleteNote(id)
            setMessage("照片已全部移除，这条只剩照片的记录也一并删掉了")
        } else {
            reload()
        }
        return saved
    }

    fun deleteNote(id: String): Boolean {
        if (!session.isUnlocked) return false
        val removed = runCatching { session.delete(id) }.getOrElse { error ->
            setMessage("删除失败：${error.message}")
            return false
        }
        reload()
        return removed
    }

    /**
     * 媒体库：把一张照片从它所属的记录里摘掉。
     *
     * 只是 [removeMedia] 的单张形式 —— 判定与顺序都在那里，避免两处各自演化。
     */
    fun removePhoto(noteId: String, blobId: String): Boolean =
        removeMedia(listOf(MediaRef(noteId, blobId))) > 0

    /**
     * 媒体库：批量摘掉若干份媒体（可能跨多条记录，照片与视频混在一起）。
     *
     * ## 顺序
     *
     * 与 [VaultSession.delete] 同一条纪律：**先做可回滚的，最后做不可再生的**。
     * 记录行与清单在同一个事务里改（失败则整体回滚，什么都没变），
     * 密文留到事务成功之后再单独销毁 —— 万一那一步失败，留下的也只是
     * 能被 [com.fpb.vault.session.VaultSession.purgeOrphanBlobs] 扫掉的孤儿文件，
     * 而不是"记录还在、媒体打不开"。
     *
     * ## 只剩媒体的记录会被一并删掉
     *
     * 这是用户要的那条规则：媒体全部删光的记录不该继续留在列表里，
     * 否则列表里会多出一张"点开什么都没有"的空卡片。
     * 判据收在 [PhotoRecord.isShell]：用户自己写过标题、备注、加过标签或收藏过的记录
     * **不算空壳**，会被保留（媒体删了、备注还在，它就不再只是装着媒体的容器）。
     *
     * 记录被判为空壳时直接走 [VaultSession.delete]：它会把这条记录名下的密文一并销毁，
     * 而此刻它名下正好只剩下我们要删的那些（已无剩余媒体），所以不必再单独删一遍。
     *
     * ## 照片与视频必须一起摘
     *
     * 早先只摘 `images`。库里出现视频之后，如果这里不一起处理 `videos`，
     * 删除会变成一件很诡异的事：网格上空了一格、记录还在列表里、
     * 而那段视频的密文永远没人回收。
     *
     * @return 实际删掉的媒体份数（记录不存在、或这些 blob 不属于该记录时不计）。
     */
    fun removeMedia(refs: List<MediaRef>): Int {
        if (!session.isUnlocked || refs.isEmpty()) return 0
        var removedMedia = 0
        var deletedNotes = 0

        refs.groupBy { it.noteId }.forEach { (noteId, group) ->
            val note = runCatching { session.note(noteId) }.getOrNull() ?: return@forEach
            val payload = note.payload
            val drop = group.map { it.blobId }.toSet()
            val remainingImages = payload.images.filterNot { it.blobId in drop }
            val remainingVideos = payload.videos.filterNot { it.blobId in drop }
            val gone = (payload.images.size - remainingImages.size) +
                (payload.videos.size - remainingVideos.size)
            if (gone == 0) return@forEach

            val shelled = PhotoRecord.isShell(
                payload.copy(images = remainingImages, videos = remainingVideos),
            )
            val ok = runCatching {
                if (shelled) {
                    session.delete(noteId)
                } else {
                    session.update(
                        noteId,
                        payload.copy(images = remainingImages, videos = remainingVideos),
                    ) != null
                }
            }.getOrElse { error ->
                setMessage("删除失败：${error.message}")
                false
            }
            if (!ok) return@forEach
            removedMedia += gone
            if (shelled) {
                deletedNotes++
            } else {
                // 记录还在时才需要单独销毁密文；记录被删掉的话，
                // session.delete 已经把它的附件一并销毁了。
                drop.forEach { runCatching { session.deleteImage(it) } }
            }
        }

        reload()
        if (removedMedia > 0) {
            setMessage(
                buildString {
                    append("已删除 $removedMedia 项")
                    if (deletedNotes > 0) append("，$deletedNotes 条只剩媒体的记录已一并删除")
                },
            )
        }
        return removedMedia
    }

    /** 列表页：一次删掉多条记录。逐条删、只刷新一次列表。 */
    fun deleteNotes(ids: Collection<String>): Int {
        if (!session.isUnlocked) return 0
        var removed = 0
        var firstError: String? = null
        ids.forEach { id ->
            val ok = runCatching { session.delete(id) }.getOrElse { error ->
                // 只记住第一条：连着弹多条提示等于一条都看不清，
                // 而这里失败的原因多半是同一个（存储出错），报一次就够定位。
                if (firstError == null) firstError = error.message
                false
            }
            if (ok) removed++
        }
        reload()
        if (removed > 0) setMessage("已删除 $removed 条记录")
        firstError?.let { setMessage("有记录没能删除：$it") }
        return removed
    }

    suspend fun putImage(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        runCatching { session.putImage(bytes) }.getOrElse { error ->
            setMessage("图片保存失败：${error.message}")
            null
        }
    }

    /**
     * 取一张缩略图。
     *
     * 解密出来的 bitmap 只进内存缓存，**绝不落盘** —— 一旦写进磁盘缓存，
     * 加密存储就被这一步架空了。锁定与退出进程都会让它消失。
     *
     * 顺手把这张图的**形态**（是不是实况照片）也算出来存进同一个缓存：
     * 字节已经因为解码缩略图而全部在内存里了，此时多走一遍字节几乎不花钱，
     * 而放到别处去算就得再解密一次（一张实况照片十几 MB）。
     */
    suspend fun thumbnail(blobId: String): Bitmap? {
        bitmapCache.get(BitmapCache.thumbKey(blobId))?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = runCatching { session.image(blobId) }.getOrNull() ?: return@withContext null
            // 必须在 put(bitmap) 之前记形态：这样"缩略图命中缓存"就必然蕴含"形态已看过"，
            // 上层不需要再区分"不是实况照片"和"还没看过"。
            rememberMotion(blobId, bytes)
            val bitmap = ImagePipeline.decode(bytes, ImagePipeline.THUMBNAIL_EDGE)
                ?: return@withContext null
            bitmapCache.put(BitmapCache.thumbKey(blobId), bitmap)
            bitmap
        }
    }

    suspend fun fullImage(blobId: String): Bitmap? {
        bitmapCache.get(BitmapCache.fullKey(blobId))?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = runCatching { session.image(blobId) }.getOrNull() ?: return@withContext null
            rememberMotion(blobId, bytes)
            val bitmap = ImagePipeline.decode(bytes) ?: return@withContext null
            bitmapCache.put(BitmapCache.fullKey(blobId), bitmap)
            bitmap
        }
    }

    // ==================== 实况照片 ====================

    /**
     * 这张图是不是实况照片。**只看内存缓存，不做任何 IO**，可以直接在渲染时调用。
     *
     * 返回 null 有两种可能：不是实况照片，或者还没看过（缩略图/大图都没加载过）。
     * 这里刻意不为它们做区分 —— 两种情况下界面要做的事完全一样（不显示角标），
     * 而多一个"未知"状态只会让每个调用点都写一遍无意义的分支。
     * 需要确定的答案时用 [ensureMotion]。
     */
    fun motionOf(blobId: String): MotionPhoto.Motion? = bitmapCache.motion(blobId)

    /** 确知这张图的形态：没看过就现在读一次（会解密整份字节）。 */
    suspend fun ensureMotion(blobId: String): MotionPhoto.Motion? = withContext(Dispatchers.IO) {
        if (bitmapCache.motionKnown(blobId)) return@withContext bitmapCache.motion(blobId)
        val bytes = runCatching { session.image(blobId) }.getOrNull() ?: return@withContext null
        rememberMotion(blobId, bytes)
        bitmapCache.motion(blobId)
    }

    /**
     * 切出一张实况照片里的影片段交给播放器。
     *
     * 返回的是**明文**，调用方负责用完后不再持有它。刻意不落任何临时文件：
     * 一个把"明文绝不落盘"当卖点的应用，不该为了让系统播放器方便而先写一个 mp4 到磁盘上。
     */
    suspend fun motionVideo(blobId: String): ByteArray? = withContext(Dispatchers.IO) {
        // 只读一次字节。先 ensureMotion 再读一遍的话，一张实况照片要解密两回
        // （十几 MB 的 AES-GCM + 两次全量读盘），而这里本来就需要那份字节本身。
        val bytes = runCatching { session.image(blobId) }.getOrNull() ?: return@withContext null
        rememberMotion(blobId, bytes)
        bitmapCache.motion(blobId)?.videoOf(bytes)
    }

    /** 记下形态。已经看过就不重复解析 —— 同一个 blob 的缩略图与大图会各走一次这里。 */
    private fun rememberMotion(blobId: String, bytes: ByteArray) {
        if (bitmapCache.motionKnown(blobId)) return
        bitmapCache.putMotion(blobId, runCatching { MotionPhoto.detect(bytes) }.getOrNull())
    }

    // ==================== 视频 ====================

    /**
     * 把一段视频从相册导进库里：**流式加密落盘 → 探测元数据 → 成功则返回引用**。
     *
     * ## 为什么探测放在写入之后
     *
     * 入参是流（视频可能上 GB），流的顺序是单向的 —— 探测要用到文件里的多处内容
     * （moov、关键帧），读完了没法回头。所以顺序是：先流式存下来拿到 blobId，
     * 再用**随机访问读取器**去探。探测失败（不是视频、容器损坏）时把刚存的那份删掉，
     * 于是"失败"不会在磁盘上留下任何痕迹 —— 否则反复试错会把用户的存储悄悄吃光。
     *
     * ## 为什么不用 try 捕获所有异常并统一报错
     *
     * "这个视频有 2.4 GB，超过上限"和"这个文件不是视频"对用户是完全不同的两件事，
     * 而它们都表现为"没导进去"。所以这里把可预期的失败**翻译成人话**，
     * 而不是让 `error.message` 里的英文异常名跑到界面上。
     *
     * [open] 由调用方提供（它才知道怎么从 content:// 拿流）。返回 null 表示取不到流。
     */
    suspend fun importVideo(open: () -> java.io.InputStream?): VideoImport = withContext(Dispatchers.IO) {
        val stream = runCatching { open() }.getOrNull()
            ?: return@withContext VideoImport.Rejected("这个视频取不到，换一个再试")

        val stored = runCatching { session.putVideo(stream) }.getOrElse { error ->
            return@withContext when (error) {
                is BlobTooLargeException -> VideoImport.Rejected(
                    "这个视频超过 ${error.limitBytes / (1024 * 1024 * 1024)} GB 的单条上限，" +
                        "请先在系统相册里剪短一些再导入",
                )
                is BlobEmptyException -> VideoImport.Rejected("这个文件是空的，读不出内容")
                else -> VideoImport.Rejected("视频保存失败：${error.message}")
            }
        }

        // 探测必须能独立失败并触发回滚，所以它不套在上面的 runCatching 里。
        val meta = runCatching { session.openBlob(stored.blobId)?.use(VideoPipeline::probe) }
            .getOrNull()
        if (meta == null) {
            // 回滚：探测不出来的东西没有任何价值，留着只会变成孤儿。
            runCatching { session.deleteImage(stored.blobId) }
            return@withContext VideoImport.Rejected("这个文件不是能播放的视频，已跳过")
        }

        VideoImport.Ready(
            video = VideoRef(
                blobId = stored.blobId,
                width = meta.width,
                height = meta.height,
                durationMs = meta.durationMs,
            ),
            plainBytes = stored.plainBytes,
        )
    }

    /**
     * 打开一份附件的随机访问读取器，交给播放器。
     *
     * 调用方**必须**负责 [BlobReader.close]。之所以不在这里用 `use { }` 包起来，
     * 是因为读取器的生命周期与播放器一致（可能几分钟），而不是与这一次调用一致。
     */
    suspend fun openReader(blobId: String): BlobReader? = withContext(Dispatchers.IO) {
        runCatching { session.openBlob(blobId) }.getOrNull()
    }

    /**
     * 视频封面（首帧）。
     *
     * 与 [thumbnail] 分成两个方法，而不是在里面判一次类型：它们的**数据来源完全不同**
     * （一个走 `BitmapFactory`，一个走 `MediaMetadataRetriever`），
     * 而混在一起的那个分支会让"给视频调了图片缩略图"这种错误静默地返回 null。
     *
     * 封面解出来只进内存缓存，随 [lock] 一起消失 —— 与图片缩略图同一条纪律。
     */
    suspend fun videoThumbnail(blobId: String, durationMs: Long = 0L): Bitmap? {
        bitmapCache.get(BitmapCache.videoThumbKey(blobId))?.let { return it }
        return withContext(Dispatchers.IO) {
            val reader = runCatching { session.openBlob(blobId) }.getOrNull()
                ?: return@withContext null
            val frame = reader.use { VideoPipeline.firstFrame(it, ImagePipeline.THUMBNAIL_EDGE, durationMs) }
                ?: return@withContext null
            bitmapCache.put(BitmapCache.videoThumbKey(blobId), frame)
            frame
        }
    }

    /**
     * 当前库里每个附件的类型（视频另带摆正后的宽高）。
     *
     * 做成"一次遍历出一张表"而不是 [blobKinds] + 一张视频表：全屏查看器翻页时
     * 每一页都要问一次，两张表就得查两次，而它们的数据源是同一份 [notes]。
     */
    fun blobKinds(): Map<String, BlobKind> = notes.asSequence()
        .flatMap { note ->
            sequence {
                note.payload.images.forEach { yield(it.blobId to BlobKind(NoteType.IMAGE)) }
                note.payload.videos.forEach { yield(it.blobId to BlobKind(NoteType.VIDEO, it)) }
            }
        }
        .toMap()

    // ==================== 维护操作 ====================

    /**
     * 修改**当前所在这个库**的密码。
     *
     * 一定要按会话所在域分派：在诱饵库里点"修改密码"时，改的必须是 DECOY 槽。
     * 若无条件走 [VaultKeyring.rewrapPrimary]，用户改完会发现
     * "新密码进不去了" —— 因为新密码被写进了他从没打开过的真库槽位，
     * 而诱饵槽还挂着旧密码。
     */
    suspend fun changeMasterPassword(current: CharArray, next: CharArray): String? {
        if (!session.isUnlocked) return "保险库已锁定"
        return withContext(Dispatchers.Default) {
            runCatching {
                val keyring = repository.keyring() ?: throw IllegalStateException("密钥文件不见了")
                val dek = requireCurrentDek(keyring, current) ?: throw IllegalArgumentException("当前密码不正确")
                try {
                    val updated = if (session.isDecoy) {
                        keyring.rewrapDecoy(next, dek)
                    } else {
                        keyring.rewrapPrimary(next, dek)
                    }
                    repository.replaceKeyring(updated)
                } finally {
                    dek.close()
                }
            }.exceptionOrNull()?.let { error ->
                when (error) {
                    is IllegalArgumentException -> error.message ?: "参数不合法"
                    else -> "修改失败：${error.message}"
                }
            }
        }
    }

    /** 只校验密码对不对，不改任何东西。用于清空数据前的最终确认。 */
    suspend fun verifyCurrentPassword(password: CharArray): Boolean = withContext(Dispatchers.Default) {
        runCatching {
            val keyring = repository.keyring() ?: return@runCatching false
            val dek = requireCurrentDek(keyring, password) ?: return@runCatching false
            dek.close()
            true
        }.getOrDefault(false)
    }

    /**
     * 设置或关闭假密码。
     *
     * ## 为什么只能在真库里改
     *
     * 在诱饵库里执行"关闭假密码"会把自己所在的这个库一起废掉 ——
     * 关掉 DECOY 槽的瞬间，当前会话拿着的那把诱饵 DEK 就再也解不开了。
     * 用户不会把这件事理解成"我删了自己的库"，只会觉得"应用坏了"。
     * 所以这里直接拒绝，而不是靠界面不显示这个按钮。
     */
    suspend fun setDecoyPassword(decoy: CharArray?): String? {
        if (!session.isUnlocked) return "保险库已锁定"
        if (session.isDecoy) return "当前在诱饵库里，无法修改这项设置"
        // 这里装的不是"失败"，而是"需要让用户知道的一句话"：关闭假密码时那本账
        // 可能读不出来，那件事必须说出来 —— 但也不能因此把这次关闭拦下来。
        var warning: String? = null
        val error = withContext(Dispatchers.Default) {
            runCatching {
                val keyring = repository.keyring() ?: throw IllegalStateException("密钥文件不见了")

                val updated = if (decoy == null) {
                    // 关掉假密码：诱饵域那本账马上会连同诱饵 DEK 一起变得解不开，
                    // 所以**先**把它并进真库 —— 它记的正是"有人用过假密码"，
                    // 是这次改动里最该留下的东西。
                    if (!mergeDecoyLogBeforeUnlink()) {
                        warning = "假库那本登录记录读不出来（可能已损坏），没能并进真库"
                    }
                    session.unlinkAudit()
                    // 不删槽，换回一个解不开的占位包裹，保持密钥文件结构对称。
                    keyring.withoutDecoy()
                } else {
                    // 诱饵库每次重设都要换一把全新的 DEK：沿用旧 DEK 的话，
                    // 上一个假密码保护过的那批内容会原样留在"新诱饵库"里。
                    val decoyDek = VaultDek.random(VaultDomain.DECOY)
                    try {
                        // 但**审计钥匙要沿用**：它跟密码无关，换掉它会让此前记下的
                        // "有人用过假密码"再也读不出来。没有才新建一把。
                        val slot = session.auditSlot()
                        val auditKey = (slot as? VaultSession.AuditSlot.Present)?.key
                            ?: AuditKey.random()
                        try {
                            // **先建审计通道，再落密钥文件。** 反过来的顺序若失败
                            // （密钥已换、通道没建），结果是从此看不到假密码的登录，
                            // 而用户察觉不到、也无从修。这个顺序的失败最多在真库
                            // 留下一份指向旧诱饵库的槽，下一次重设就覆盖了。
                            session.linkAudit(auditKey, decoyDek)
                            keyring.withDecoy(decoy, decoyDek)
                        } finally {
                            auditKey.close()
                        }
                    } finally {
                        decoyDek.close()
                    }
                }
                repository.replaceKeyring(updated)
            }.exceptionOrNull()?.let { error ->
                when (error) {
                    is IllegalArgumentException -> error.message ?: "参数不合法"
                    else -> "设置失败：${error.message}"
                }
            }
        }
        // 落盘成功之后才改标志位；Compose 状态一律在主线程写。
        if (error == null) {
            settings.secondarySlotConfigured = decoy != null
            secondarySlotConfigured = decoy != null
            refreshLoginLog()
        }
        return error ?: warning?.let {
            if (decoy == null) "已关闭假密码。但$it" else "假密码已更新。但$it"
        }
    }

    /**
     * 把诱饵库那本登录账并进真库（在审计通道即将被拆掉之前）。
     *
     * ## 为什么只有"关假密码"需要它
     *
     * 诱饵那本账由审计钥匙保护，不随诱饵 DEK 更换而变化。所以**换成另一把假密码
     * 时完全不用碰它** —— 早先"账本由诱饵 DEK 加密"的做法必须在这里抢先把账迁走，
     * 而那种迁移一旦失败就是静默丢掉"有人用过假密码"。现在这条路径不存在了。
     *
     * @return true = 没有"读不出来的遗留账"（根本还没建立通道，也是 true）；
     *         false = 槽在、但那本账解不出来，于是这次合并没能完成。
     *
     * 读不出来时**不阻断**调用方：用户关的是他自己的一个设置，为一条内部记录
     * 把他卡住、而且他没有任何办法解决，比丢掉这条记录更糟。
     * 但结果必须说出来（由调用方负责），否则"记录里没有"会被读成"没人用过假密码"。
     */
    private fun mergeDecoyLogBeforeUnlink(): Boolean {
        val slot = session.auditSlot()
        return when (slot) {
            is VaultSession.AuditSlot.Absent -> true
            is VaultSession.AuditSlot.Damaged -> false
            is VaultSession.AuditSlot.Present -> try {
                val foreign = session.decoyLoginLog(slot.key)
                if (foreign == null) {
                    false
                } else {
                    if (!foreign.isEmpty) {
                        session.replaceLoginLog(session.loginLog().merged(foreign))
                    }
                    true
                }
            } finally {
                slot.key.close()
            }
        }
    }

    /**
     * 清空登录记录（**两本账都清**）。
     *
     * 只清真库那本的话，用户会看到"清空之后列表里还留着几条假密码登录" ——
     * 那一刻他以为自己清空失败了，而其实只是另一半没被清。
     *
     * **诱饵会话一律拒绝**：这张页面在诱饵库里不显示（它会让那个库看起来
     * "知道自己被监视"），但只靠界面不显示是不够的 —— 一旦哪天走错一步，
     * 被胁迫者就能从假密码那边把真库的账清掉。
     */
    fun clearLoginLog() {
        if (!session.isUnlocked) return
        if (session.isDecoy) return
        runCatching {
            session.clearLoginLog()
            val slot = session.auditSlot()
            if (slot is VaultSession.AuditSlot.Present) {
                try {
                    session.clearDecoyLoginLog(slot.key)
                } finally {
                    slot.key.close()
                }
            }
        }.onFailure { setMessage("清空登录记录失败：${it.message ?: "未知原因"}") }
        refreshLoginLog()
    }

    /**
     * 一次性绑定：把手上这把假密码交给真库一次，让"假密码的登录"从此能被看到。
     *
     * ## 为什么需要这个动作
     *
     * 审计通道只能在**同时拿到两把钥匙的那一刻**建立（真库 DEK 与诱饵 DEK 都要
     * 在场，才能把同一把审计钥匙分别封进两个域）—— 那正是"设置假密码"的时候。
     * 存量用户升上来时假密码早就设好了，App 手上从来没有过那把诱饵 DEK，
     * 于是这一半记录看不见；要在记录页里输一次假密码补上。
     *
     * ## 为什么必须真的解开诱饵槽才算数
     *
     * 校验的不是"密码对不对"，而是"这把钥匙是不是诱饵库那把"。拿主密码来绑定，
     * 写进诱饵域的就是真库钥匙，之后诱饵会话会拿它去算一个不存在的行 ——
     * 界面上只会显示"假密码从没被用过"。**一个静默错误的安全结论。**
     *
     * @return 出错时返回一句给用户看的话；成功返回 null。
     */
    suspend fun bindDecoyLog(decoy: CharArray): String? {
        if (!session.isUnlocked) return "保险库已锁定"
        if (session.isDecoy) return "当前在诱饵库里，看不到这项"
        val keyring = repository.keyring() ?: return "密钥文件不见了"

        val error = withContext(Dispatchers.Default) {
            runCatching {
                when (val outcome = keyring.unlock(decoy)) {
                    is UnlockOutcome.Unlocked -> {
                        val dek = outcome.dek
                        try {
                            if (!outcome.isDecoy) throw IllegalArgumentException("这是主密码，不是假密码")
                            // 已经有通道就沿用同一把审计钥匙（这里通常是"没有"）。
                            val slot = session.auditSlot()
                            val auditKey = (slot as? VaultSession.AuditSlot.Present)?.key
                                ?: AuditKey.random()
                            try {
                                session.linkAudit(auditKey, dek)
                            } finally {
                                auditKey.close()
                            }
                        } finally {
                            dek.close()
                        }
                    }
                    // 解不开诱饵槽有两种可能：密码不对，或这台设备根本没开假密码
                    // （槽里躺的是那个永远解不开的占位包裹）。两者对用户是同一件事：
                    // "这个密码不是这台设备的假密码"。不区分，也不去读那个界面标志位 ——
                    // 真正的判据永远是"这一槽能不能解开"，标志位只是个提示。
                    UnlockOutcome.Rejected ->
                        throw IllegalArgumentException("假密码不对。如果这台设备没有开假密码，就不用做这一步")
                }
            }.exceptionOrNull()?.let { e ->
                when (e) {
                    is IllegalArgumentException -> e.message ?: "假密码不正确"
                    else -> "绑定失败：${e.message}"
                }
            }
        }
        if (error == null) refreshLoginLog()
        return error
    }

    /** 换新恢复码并返回给界面展示。 */
    suspend fun regenerateRecoveryCode(): String? {
        if (!session.isUnlocked) return null
        if (session.isDecoy) {
            // 恢复码槽只属于真库。在诱饵库里换恢复码会改到真库的槽位，
            // 等于用户以为自己"换了个假库的恢复码"，实际毁掉了真库的救命绳。
            setMessage("当前在诱饵库里，无法更换恢复码")
            return null
        }
        return withContext(Dispatchers.Default) {
            runCatching {
                val keyring = repository.keyring() ?: throw IllegalStateException("密钥文件不见了")
                val dek = session.currentDek() ?: throw IllegalStateException("保险库已锁定")
                val code = RecoveryCode.generate()
                repository.replaceKeyring(keyring.rewrapRecovery(code, dek))
                RecoveryCode.formatForDisplay(code)
            }.getOrElse { error ->
                setMessage("更换恢复码失败：${error.message}")
                null
            }
        }
    }

    /** 启用生物识别：把当前域的 DEK 用 Keystore 硬件密钥再包裹一份。 */
    fun enableBiometric(activity: FragmentActivity) {
        if (session.isDecoy) {
            setMessage("诱饵库不支持生物识别")
            return
        }
        val keyring = repository.keyring() ?: return
        val dek = session.currentDek() ?: return
        val cipher = biometric.newEncryptCipher()
        if (cipher == null) {
            setMessage("无法创建硬件密钥，请检查系统是否支持强生物识别")
            return
        }

        BiometricGate.prompt(
            activity = activity,
            cipher = cipher,
            title = "启用生物识别解锁",
            subtitle = "验证指纹以绑定本机",
            onSuccess = { authenticated ->
                // KEK 是随机生成的，与主密码无关：它唯一的作用就是让硬件守着 DEK。
                val kek = com.fpb.vault.crypto.SecureBytes.random(BiometricGate.KEK_BYTES)
                val stored = biometric.storeWrapped(authenticated, kek)
                if (!stored) {
                    setMessage("写入硬件凭据失败")
                    return@prompt
                }
                val updated = runCatching {
                    keyring.withExternalWrap(KeySlot.BIOMETRIC, kek, dek)
                }.getOrElse { error ->
                    biometric.clear()
                    setMessage("绑定失败：${error.message}")
                    return@prompt
                }
                repository.replaceKeyring(updated)
                settings.biometricEnabled = true
                biometricEnabled = true
                // 重新启用成功 = "需要你重新开一次"这件事已经了结，提示必须立刻撤掉。
                // 撤晚了的后果是用户刚按提示做完，提示还挂在那里，看起来像没生效。
                settings.biometricReenrollNeeded = false
                biometricReenrollNeeded = false
                setMessage("已启用生物识别解锁")
            },
            onFailure = { reason -> setMessage(reason) },
        )
    }

    /**
     * 关闭生物识别。
     *
     * 除了删硬件包裹（[BiometricGate.clear] 会连 Keystore 里的那把密钥一起销毁），
     * 还要**把密钥文件里的 BIOMETRIC 槽一并去掉**。
     *
     * 只删文件不删槽，会留下一份永远解不开的密文：KEK 已随硬件密钥一起没了，
     * 就算 `bio_wrap.bin` 从某处被恢复出来也没有第二把钥匙。它构不成漏洞，
     * 但会让"这个库配过生物识别"在密钥文件里持续留痕 —— 而用户刚刚明确关掉了它。
     * 另一头，`VaultKeyring.withoutSlot` 的注释一直写着"例如关闭生物识别"，
     * 而在这次改动之前**没有任何生产代码调用它** —— 那句话描述的是一个不存在的调用方。
     *
     * 失败不阻断：改密钥文件是"顺手收拾干净"，而用户的意图是"关掉"。
     * 两者冲突时以用户意图为准，写盘失败只会让那个死槽多留一轮。
     */
    fun disableBiometric(reason: String? = null) {
        biometric.clear()
        runCatching {
            repository.keyring()
                ?.takeIf { it.hasSlot(KeySlot.BIOMETRIC) }
                ?.let { repository.replaceKeyring(it.withoutSlot(KeySlot.BIOMETRIC)) }
        }
        settings.biometricEnabled = false
        biometricEnabled = false
        // 用户自己关掉了，就不该再挂着"请重新启用一次"的提示：
        // 那是**换代**造成的通知，而他刚刚明确表示不要这个功能。
        settings.biometricReenrollNeeded = false
        biometricReenrollNeeded = false
        reason?.let { setMessage(it) }
    }

    /** 改自动锁定时长。必须同时通知会话，否则要等下次解锁才生效。 */
    fun setAutoLock(millis: Long) {
        settings.autoLockMillis = millis
        autoLockMillis = millis
        repository.applyAutoLock()
    }

    /** 开关键：是否禁止截屏。AppRoot 会立刻把结果应用到窗口上。 */
    fun applyBlockScreenshots(block: Boolean) {
        settings.blockScreenshots = block
        blockScreenshots = block
    }

    /**
     * 切外观模式（跟随系统/浅色/深色）。落盘 + 改镜像，配色立刻全局生效。
     *
     * 名字是 `apply*` 而不是 `setThemeMode`：`var themeMode` 自己会生成一个
     * `setThemeMode(String)`，同名函数在 JVM 层直接撞签名（`notice` / `setMessage`
     * 那对名字就是这么来的）。`applyBlockScreenshots` / `applyLauncherAlias` 同例。
     */
    fun applyThemeMode(mode: String) {
        val normalized = ThemeMode.normalize(mode)
        settings.themeMode = normalized
        themeMode = normalized
    }

    fun purgeOrphanBlobs(): Int {
        if (!session.isUnlocked) return 0
        val removed = runCatching { session.purgeOrphanBlobs() }.getOrElse { error ->
            setMessage("清理失败：${error.message}")
            return 0
        }
        reload()
        return removed
    }

    fun storageBytes(): Long = runCatching { session.storageBytes() }.getOrDefault(0L)

    /**
     * 占用空间**明细**。锁定时为 null —— 分类依赖内存索引里"哪些 blobId 被引用"，
     * 而索引在锁定时是空的（见 [VaultSession.storageUsage]）。
     *
     * 每次调用都要扫一遍附件目录并为每个文件读一次块头，所以界面侧应当
     * 只在进入设置页时算一次，而不是放在每次组合都会跑的位置。
     */
    fun storageUsage(): VaultSession.StorageUsage? =
        runCatching { session.storageUsage() }.getOrNull()

    /**
     * 解密后图片的内存缓存占用。**这不是磁盘占用** ——
     * 那个缓存从不落盘（原因见 [BitmapCache]），锁定即清空。
     * 单独报出来是因为它会占着堆，而"应用占了多少内存"是用户会问的另一个问题。
     */
    fun bitmapCacheBytes(): Int = bitmapCache.sizeBytes()

    /** 切换桌面伪装图标。别名会同步应用到 PackageManager。 */
    fun applyLauncherAlias(alias: String) {
        settings.launcherAlias = alias
        launcherAlias = alias
        runCatching { LauncherIcon.apply(context, alias) }
            .onFailure { setMessage("切换桌面图标失败：${it.message}") }
    }

    /**
     * 冷启动时自检桌面别名，异常就修回来。
     *
     * ## 为什么必须在每次启动都做
     *
     * [LauncherIcon.apply] 是两步：先启用目标、再禁用其它。这个顺序是刻意的
     * （反过来会短暂出现"三个全禁用"），但代价是两步之间进程被杀就会留下脏状态：
     * - 停在第一步之后：桌面上出现**两个**图标 —— 伪装当场失效，而用户毫不知情；
     * - 更极端的情况（例如别名被第三方工具整组禁用）：桌面上**一个都不剩**，
     *   用户再也无法从桌面打开这个应用，只能去系统设置的安装列表里捞。
     *
     * 这两种状态都不会自愈，而且都不会有任何提示。因此用现成的
     * [LauncherIcon.verify] 做判据、启动即修。
     *
     * 正常启动时 [LauncherIcon.verify] 为 true，这里一个 PackageManager 写操作都不发，
     * 不会引起桌面刷新。
     */
    private fun repairLauncherAlias() {
        val expected = settings.launcherAlias
        // 取不到状态时按"没问题"处理：宁可漏修，也不要在 PM 异常时反复写别名
        // （反复写会让桌面图标不停闪，而且此时修复本身大概也不会成功）。
        if (runCatching { LauncherIcon.verify(context, expected) }.getOrDefault(true)) return

        val repaired = runCatching {
            LauncherIcon.apply(context, expected)
            if (!LauncherIcon.verify(context, expected)) {
                // 记录里那个别名怎么都启用不了（例如这个版本已经把它删了），
                // 退回 FPB：保证桌面上有一个能打开这个应用的入口，比"伪装不一致"重要得多。
                LauncherIcon.apply(context, LauncherIcon.ALIAS_FPB)
            }
        }
        if (repaired.isSuccess) {
            launcherAlias = LauncherIcon.current(context)
            settings.launcherAlias = launcherAlias
        }
    }

    fun wipeEverything() {
        repository.wipeEverything()
        bitmapCache.clear()
        notes = emptyList()
        tagCounts = emptyList()
        routes = listOf(Route.Home)
        bootError = null
        loadWarning = null
        loadProblemCount = 0
        // 库已经不存在了，"该备份"的提醒必须跟着消失：否则重建库之后
        // 会看到一条指向空库的催促，而它引用的还是上一个库的导出时间。
        backupNotice = null
        phase = Phase.ONBOARDING

        // wipeEverything 会清掉 SharedPreferences，但**清不掉系统里的桌面别名状态** ——
        // 那是 PackageManager 的事。这里反过来以系统实际状态为准回写，避免
        // "设置页显示 FPB、桌面上却是计算器"这种对不上的情况。
        launcherAlias = LauncherIcon.current(context)
        settings.launcherAlias = launcherAlias
        blockScreenshots = settings.blockScreenshots
        autoLockMillis = settings.autoLockMillis
        biometricEnabled = settings.biometricEnabled
        biometricReenrollNeeded = settings.biometricReenrollNeeded
        secondarySlotConfigured = settings.secondarySlotConfigured
        themeMode = settings.themeMode

        setMessage("本机数据已全部清除")
    }

    // ==================== 备份 ====================

    suspend fun exportBackup(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("无法写入所选位置")
            stream.use { BackupManager.export(repository, session, it) }.let { summary ->
                // 口径是"附件"不是"图片"：库里可能存的是视频，
                // 说成"0 张图片"会让人以为备份没把视频带上（这正是要防的那个误解）。
                "已导出 ${summary.noteRows} 条记录、${summary.attachments} 个附件" +
                    "（${ImagePipeline.describeSize(summary.bytes)}）"
            }
        }.getOrElse { error -> "导出失败：${error.message}" }
    }

    /** 只做预检，不解压内容；导入前的确认页用它。 */
    suspend fun inspectBackup(uri: Uri): Pair<BackupInfo?, String?> = withContext(Dispatchers.IO) {
        runCatching {
            val size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: 0L
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            stream.use { BackupManager.inspect(it, size) } to null
        }.getOrElse { error -> null to "这不是可用的备份包：${error.message}" }
    }

    /**
     * 覆盖式恢复。**必须先锁定** —— 否则会话里那把旧 DEK 已经打不开新库了，
     * 而界面还以为自己在正常读写。
     */
    suspend fun restoreBackup(uri: Uri): String? {
        lock()
        return withContext(Dispatchers.IO) {
            runCatching {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取所选文件")
                val info = stream.use { BackupManager.restore(repository, it) }
                "恢复完成（${info.attachments} 个附件）。请用该备份对应的主密码解锁。"
            }.getOrElse { error -> "恢复失败：${error.message}" }
        }
    }

    /**
     * 从界面触发恢复，走状态自己的协程域。
     *
     * 恢复的第一步就是 [restoreBackup] 里的 `lock()`，界面会随即卸载。
     * 若调用方用的是 Composable 的 `rememberCoroutineScope`，协程会在卸载那一瞬间被取消，
     * 留下一个"恢复了一半"的库 —— 那是所有可能结果里最坏的一种（数据既不是旧的也不是新的）。
     * 因此入口收在这里，用与 Activity 同生命周期的 [scope]。
     *
     * 无论成功失败都停在锁定态：库内容已经（或可能已经）被换掉，
     * 当前会话手上那把旧 DEK 不能再继续用。
     */
    fun startRestore(uri: Uri) {
        if (busy) return
        busy = true
        scope.launch {
            val result = restoreBackup(uri)
            busy = false
            if (phase == Phase.UNLOCKED) lock()
            result?.let { setMessage(it) }
        }
    }

    // ==================== 内部 ====================

    /**
     * 解出**当前会话所在域**的那把 DEK。
     *
     * 必须校验槽位，而不是"解开了就行"：在真库里输入假密码同样能解开（解开的是 DECOY 槽）。
     * 少了这道校验，用户就能在真库里用假密码通过"当前密码"这一关，
     * 然后把**真库的槽位**改写 —— 结果是两个库的密码被悄悄调换，
     * 而他下次输真密码会直接掉进诱饵库。
     */
    private fun requireCurrentDek(keyring: VaultKeyring, password: CharArray): VaultDek? {
        val expected = if (session.isDecoy) KeySlot.DECOY else KeySlot.PRIMARY
        return when (val outcome = keyring.unlock(password)) {
            is UnlockOutcome.Unlocked -> {
                val dek = outcome.takeDek()
                if (outcome.slot == expected) dek else { dek.close(); null }
            }
            UnlockOutcome.Rejected -> null
        }
    }

    private inline fun <T> guarded(prefix: String, block: () -> T): T? =
        runCatching(block).getOrElse { error ->
            setMessage("$prefix：${error.message}")
            null
        }

    fun setMessage(text: String) {
        notice = text
    }

    fun push(route: Route) {
        routes = routes + route
    }

    fun pop() {
        if (routes.size > 1) routes = routes.dropLast(1)
    }

    /**
     * 回到主页（把路由栈重置成只剩一条 Home）。
     *
     * 解锁、锁定、清库三条路都要做这件事，而且它们**必须做同一件事** ——
     * 原先三处各写一遍 `routes = listOf(Route.Home)`，将来若有第四种入口，
     * 很难保证不会有人写成 `routes.dropLast(...)` 之类的近似物。
     */
    fun popToHome() {
        routes = listOf(Route.Home)
    }
}
