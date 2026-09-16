package com.fpb.vault.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.fpb.vault.BuildConfig
import com.fpb.vault.crypto.RecoveryCode
import com.fpb.vault.crypto.VaultKeyring
import com.fpb.vault.ui.components.FpbTopBar
import com.fpb.vault.ui.components.GroupDivider
import com.fpb.vault.ui.components.NoticeTone
import com.fpb.vault.ui.components.InlineNotice
import com.fpb.vault.ui.components.KeyValueRow
import com.fpb.vault.ui.components.RestoreConfirmDialog
import com.fpb.vault.ui.components.SectionHeader
import com.fpb.vault.ui.components.SettingsGroupCard
import com.fpb.vault.ui.components.rememberBackupPicker
import com.fpb.vault.vault.BiometricGate
import com.fpb.vault.vault.ImagePipeline
import com.fpb.vault.vault.LauncherIcon
import com.fpb.vault.vault.SettingsStore
import com.fpb.vault.vault.ThemeMode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 设置页。
 *
 * ## 为什么每一项都要配一句"代价说明"
 *
 * 这个页面上的每一个开关都牵涉到"数据能不能找回来"：
 * 关掉生物识别、换掉恢复码、清空数据 —— 用户看不到这些操作的后果，
 * 只会看到一次"设置成功"。因此副标题写的不是功能，而是**后果**
 * （"旧恢复码会立即失效""导入会替换本机全部数据"）。
 *
 * ## 诱饵库里的设置页是"缩水"的
 *
 * 在诱饵库里，生物识别、假密码、恢复码这三项一律不显示：
 * 它们都属于真库。假密码这一项更是必须挡掉 —— 在诱饵库里"关闭假密码"
 * 会把用户自己正待着的这个库一起废掉。
 * 界面不显示只是第一道；[VaultAppState.setDecoyPassword] 等方法里还有硬校验。
 */
@Composable
fun SettingsScreen(state: VaultAppState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var working by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        scope.launch {
            val result = state.exportBackup(uri)
            working = false
            if (result != null && !result.startsWith("导出失败")) {
                // 导出成功才重置"该备份了"的计时，否则提醒会在下次冷启动立刻再弹。
                // 走 markBackupReminder() 而不是直接写 settings：写入端与读取端
                // （VaultAppState 里的提醒状态）必须一起更新，否则这次导出之后
                // 首页的催促条会一直挂到下一次解锁。
                state.markBackupReminder()
            }
            state.setMessage(result ?: "导出未完成")
        }
    }
    val importPicker = rememberBackupPicker { pendingRestore = it }

    val isDecoy = state.session.isDecoy
    val availability = remember { state.biometric.availability() }
    val storageBytes = remember(state.notes) { state.storageBytes() }

    Column(Modifier.fillMaxSize()) {
        FpbTopBar(
            title = "设置",
            subtitle = if (isDecoy) "当前在诱饵库" else null,
            onBack = { state.pop() },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 32.dp),
        ) {
            if (isDecoy) {
                InlineNotice(
                    text = "这是一个诱饵库。与真库相关的设置（生物识别、恢复码、假密码）在这里不显示，" +
                        "以免这个库看起来「不像一个正常的库」。",
                    tone = NoticeTone.INFO,
                )
            }

            if (working) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在处理…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ==================== 安全 ====================

            SectionHeader("安全")

            SettingsGroupCard(Modifier.padding(horizontal = 16.dp)) {
                ActionRow(
                    icon = Icons.Outlined.Key,
                    title = if (isDecoy) "修改这个库的密码" else "修改主密码",
                    subtitle = "已有内容不会重新加密，改完立刻生效",
                    onClick = { dialog = SettingsDialog.ChangePassword },
                )

                if (!isDecoy) {
                    GroupDivider()
                    ActionRow(
                        icon = Icons.Outlined.Shield,
                        title = "假密码",
                        subtitle = if (state.secondarySlotConfigured) {
                            "已开启 · 输入另一个密码会进入一个独立的空库"
                        } else {
                            "开启后，被胁迫时可以交出一个看起来正常的库"
                        },
                        value = if (state.secondarySlotConfigured) "已开启" else "未开启",
                        onClick = {
                            dialog = if (state.secondarySlotConfigured) {
                                SettingsDialog.DisableDecoy
                            } else {
                                SettingsDialog.EnableDecoy
                            }
                        },
                    )
                    GroupDivider()
                    ActionRow(
                        icon = Icons.Outlined.ContentCopy,
                        title = "更换恢复码",
                        subtitle = "换完必须重新抄写一遍，旧恢复码立即失效",
                        onClick = {
                            working = true
                            scope.launch {
                                val code = state.regenerateRecoveryCode()
                                working = false
                                if (code != null) dialog = SettingsDialog.NewRecoveryCode(code)
                            }
                        },
                    )
                    GroupDivider()
                    SwitchRow(
                        icon = Icons.Outlined.Fingerprint,
                        title = "生物识别解锁",
                        subtitle = when {
                            activity == null -> "当前环境不支持生物识别弹窗"
                            availability == BiometricGate.Availability.READY ->
                                "用指纹代替输入主密码（新增指纹后会自动失效，需要重新开启）"
                            availability == BiometricGate.Availability.NOT_ENROLLED ->
                                "本机还没有录入指纹或人脸"
                            else -> "本机没有可用的强生物识别硬件"
                        },
                        checked = state.biometricEnabled,
                        enabled = activity != null && availability == BiometricGate.Availability.READY,
                        onCheckedChange = { want ->
                            if (!want) {
                                state.disableBiometric("已关闭生物识别解锁")
                            } else if (activity != null) {
                                state.enableBiometric(activity)
                            }
                        },
                    )
                }

                GroupDivider()
                SwitchRow(
                    icon = Icons.Outlined.Screenshot,
                    title = "禁止截屏",
                    subtitle = "开启后截图、录屏与最近任务缩略图都会是黑屏",
                    checked = state.blockScreenshots,
                    onCheckedChange = { state.applyBlockScreenshots(it) },
                )
                GroupDivider()
                ActionRow(
                    icon = Icons.Outlined.Timer,
                    title = "自动锁定",
                    subtitle = "切到后台超过这个时长就要重新解锁",
                    value = SettingsStore.labelForAutoLock(state.autoLockMillis),
                    onClick = { dialog = SettingsDialog.AutoLock },
                )
            }

            // ==================== 外观与伪装 ====================

            SectionHeader("外观与伪装")

            SettingsGroupCard(Modifier.padding(horizontal = 16.dp)) {
                ActionRow(
                    icon = themeModeIcon(state.themeMode),
                    title = "外观模式",
                    subtitle = when (state.themeMode) {
                        ThemeMode.LIGHT -> "始终用浅色，不看系统的深色设置"
                        ThemeMode.DARK -> "始终用深色，不看系统的深色设置"
                        else -> "跟系统设置走：系统切深色，应用一起切"
                    },
                    value = ThemeMode.labelOf(state.themeMode),
                    onClick = { dialog = SettingsDialog.ThemeMode },
                )

                GroupDivider()

                LauncherIcon.OPTIONS.forEachIndexed { index, option ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(
                        title = option.label,
                        subtitle = option.description,
                        selected = state.launcherAlias == option.alias,
                        onClick = {
                            if (state.launcherAlias != option.alias) {
                                state.applyLauncherAlias(option.alias)
                                state.setMessage(
                                    "桌面图标已切换为「${option.label}」。部分桌面需要几秒到几十秒才刷新，" +
                                        "期间可能同时看到新旧两个图标。",
                                )
                            }
                        },
                    )
                }
            }
            Text(
                text = "提醒：换成「备忘录」或「计算器」之后，请记住这个应用是哪一个 —— " +
                    "找不到它的时候，只能去系统设置的「应用」列表里找。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
            )

            // ==================== 存储 ====================

            SectionHeader("存储")

            SettingsGroupCard(Modifier.padding(horizontal = 16.dp)) {
                KeyValueRow("占用空间", ImagePipeline.describeSize(storageBytes))
                GroupDivider()
                KeyValueRow("记录条数", "${state.notes.size} 条")
                GroupDivider()
                ActionRow(
                    icon = Icons.Outlined.CleaningServices,
                    title = "清理无用图片",
                    subtitle = "删除条目时没删干净的图片密文会一直占着空间",
                    onClick = {
                        val removed = state.purgeOrphanBlobs()
                        state.setMessage(
                            if (removed == 0) "没有发现无用图片" else "已清理 $removed 个无用的图片文件",
                        )
                    },
                )
            }

            // ==================== 备份 ====================

            SectionHeader("备份")

            SettingsGroupCard(Modifier.padding(horizontal = 16.dp)) {
                ActionRow(
                    icon = Icons.Outlined.FileUpload,
                    title = "导出备份",
                    subtitle = "导出的是密文包，仍然需要主密码或恢复码才能打开",
                    onClick = { exportLauncher.launch(suggestBackupFileName()) },
                )
                GroupDivider()
                ActionRow(
                    icon = Icons.Outlined.FileDownload,
                    title = "导入备份",
                    subtitle = "会用备份包里的内容替换本机全部数据，无法撤销",
                    onClick = importPicker,
                )
            }

            // ==================== 危险操作 ====================

            SectionHeader("危险操作")

            SettingsGroupCard(Modifier.padding(horizontal = 16.dp)) {
                ActionRow(
                    icon = Icons.Outlined.DeleteForever,
                    title = "清空本机数据",
                    subtitle = "删除密钥文件、数据库与全部图片，之后无法恢复（除非有备份）",
                    danger = true,
                    onClick = { dialog = SettingsDialog.Wipe },
                )
            }

            // ==================== 关于 ====================

            SectionHeader("关于")
            SettingsGroupCard(Modifier.padding(horizontal = 16.dp)) {
                KeyValueRow("版本", BuildConfig.VERSION_NAME)
                GroupDivider()
                KeyValueRow(
                    "数据存放",
                    "仅本机 · 无联网权限",
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "应用没有申请联网权限，因此内容在系统层面就无法被发出去。" +
                        "数据仅保存在本机（可手动导出密文备份包）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ==================== 对话框 ====================

    when (val current = dialog) {
        null -> Unit

        SettingsDialog.ChangePassword -> ChangePasswordDialog(
            state = state,
            isDecoy = isDecoy,
            onDismiss = { dialog = null },
        )

        SettingsDialog.EnableDecoy -> DecoyPasswordDialog(
            onDismiss = { dialog = null },
            onConfirm = { password ->
                dialog = null
                working = true
                scope.launch {
                    val error = state.setDecoyPassword(password)
                    working = false
                    state.setMessage(error ?: "假密码已开启。请务必记住：它进入的是一个独立的空库。")
                }
            },
        )

        SettingsDialog.DisableDecoy -> DisableDecoyDialog(
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                working = true
                scope.launch {
                    val error = state.setDecoyPassword(null)
                    working = false
                    state.setMessage(error ?: "已关闭假密码。原来那个假密码现在解不开任何东西了。")
                }
            },
        )

        SettingsDialog.AutoLock -> AutoLockDialog(
            selected = state.autoLockMillis,
            onDismiss = { dialog = null },
            onPick = { millis ->
                state.setAutoLock(millis)
                dialog = null
                state.setMessage("自动锁定：${SettingsStore.labelForAutoLock(millis)}")
            },
        )

        SettingsDialog.ThemeMode -> ThemeModeDialog(
            selected = state.themeMode,
            onDismiss = { dialog = null },
            onPick = { mode ->
                // applyThemeMode 会同时落盘与更新镜像状态 —— 镜像状态一变，
                // setContent 根上的 FpbTheme 立刻重组，整屏配色当场切换，不需要重启。
                state.applyThemeMode(mode)
                dialog = null
                state.setMessage("外观模式已切换为「${ThemeMode.labelOf(mode)}」")
            },
        )

        SettingsDialog.Wipe -> WipeDialog(
            state = state,
            onDismiss = { dialog = null },
            onWiped = { dialog = null },
        )

        is SettingsDialog.NewRecoveryCode -> NewRecoveryCodeDialog(
            display = current.display,
            onDismiss = { dialog = null },
            onDone = { confirmed ->
                dialog = null
                state.setMessage(
                    if (confirmed) "恢复码已更换，请把新的那份收好" else "恢复码已经换了，但你还没确认抄写 —— 请尽快在纸上记下来",
                )
            },
        )
    }

    RestoreConfirmDialog(state, pendingRestore) { pendingRestore = null }
}

// ==================== 对话框类型 ====================

private sealed interface SettingsDialog {
    data object ChangePassword : SettingsDialog
    data object EnableDecoy : SettingsDialog
    data object DisableDecoy : SettingsDialog
    data object AutoLock : SettingsDialog
    data object ThemeMode : SettingsDialog
    data object Wipe : SettingsDialog
    data class NewRecoveryCode(val display: String) : SettingsDialog
}

// ==================== 行样式 ====================

/** 可点击的一行：图标 + 标题 + 一句后果说明 + 右侧当前值与 iOS 风格的进入箭头。 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    value: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, danger)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 带开关的一行。[enabled] 为 false 时整行置灰且不可点。 */
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, danger = false, alpha = alpha)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** 单选行（伪装图标三选一）。 */
@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RowIcon(icon: ImageVector, danger: Boolean, alpha: Float = 1f) {
    val tint = if (danger) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tint.copy(alpha = 0.12f * alpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = alpha),
            modifier = Modifier.size(18.dp),
        )
    }
}

// ==================== 改密码 ====================

@Composable
private fun ChangePasswordDialog(
    state: VaultAppState,
    isDecoy: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (isDecoy) "修改这个库的密码" else "修改主密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "已有内容一行都不会重新加密 —— 改的只是「解锁用的那把钥匙」。" +
                        "换个新密码不会动到恢复码，恢复码依然有效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecretField(
                    value = current,
                    onValueChange = { current = it; error = null },
                    label = "当前密码",
                    visible = visible,
                    imeAction = ImeAction.Next,
                )
                SecretField(
                    value = next,
                    onValueChange = { next = it; error = null },
                    label = "新密码（至少 ${VaultKeyring.MIN_PASSWORD_LENGTH} 位）",
                    visible = visible,
                    imeAction = ImeAction.Next,
                )
                SecretField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = "再输一次新密码",
                    visible = visible,
                    imeAction = ImeAction.Done,
                    isError = error != null,
                    supporting = error,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = visible, onCheckedChange = { visible = it })
                    Text("显示密码", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && current.isNotEmpty() && next.isNotEmpty() && confirm.isNotEmpty(),
                onClick = {
                    error = when {
                        next.length < VaultKeyring.MIN_PASSWORD_LENGTH ->
                            "新密码至少 ${VaultKeyring.MIN_PASSWORD_LENGTH} 位"
                        next != confirm -> "两次输入的新密码不一致"
                        next == current -> "新密码与当前密码相同"
                        else -> null
                    }
                    if (error != null) return@TextButton
                    submitting = true
                    scope.launch {
                        val result = state.changeMasterPassword(
                            current.toCharArray(),
                            next.toCharArray(),
                        )
                        submitting = false
                        if (result == null) {
                            onDismiss()
                            state.setMessage("密码已更新")
                        } else {
                            error = result
                        }
                    }
                },
            ) { Text(if (submitting) "处理中…" else "确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") } },
    )
}

// ==================== 假密码 ====================

@Composable
private fun DecoyPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var decoy by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开启假密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "输主密码进真库，输这个假密码进一个独立的空库。两个库混在同一个文件里，" +
                        "从外部（甚至从密钥文件的结构上）都看不出这里有两个库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecretField(
                    value = decoy,
                    onValueChange = { decoy = it; error = null },
                    label = "假密码（至少 ${VaultKeyring.MIN_PASSWORD_LENGTH} 位）",
                    visible = visible,
                    imeAction = ImeAction.Done,
                    isError = error != null,
                    supporting = error,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = visible, onCheckedChange = { visible = it })
                    Text("显示密码", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "假密码要像真的：它是准备在被胁迫时交出去的。" +
                        "因此日常别用它、也别在里面放重要东西 —— 那个库空着反而更可疑，" +
                        "可以放几条无关紧要的记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (decoy.length < VaultKeyring.MIN_PASSWORD_LENGTH) {
                        error = "假密码至少 ${VaultKeyring.MIN_PASSWORD_LENGTH} 位"
                        return@TextButton
                    }
                    onConfirm(decoy.toCharArray())
                },
            ) { Text("开启") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DisableDecoyDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关闭假密码？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("关闭之后：")
                Text(
                    text = "· 原来那个假密码将解不开任何东西；\n" +
                        "· 假密码库里存过的东西，从界面上再也看不到（密钥没了，密文还在文件里但无法解读）；\n" +
                        "· 密钥文件在外观上仍然「看起来有两把钥匙」，不会暴露你曾经设过假密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("关闭假密码", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ==================== 自动锁定 ====================

@Composable
private fun AutoLockDialog(
    selected: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动锁定") },
        text = {
            Column {
                Text(
                    text = "切到后台超过这个时长，下次回到应用就要重新解锁。" +
                        "选得越短越安全，但也越频繁地要重新输密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SettingsStore.AUTO_LOCK_OPTIONS.forEach { (millis, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(millis) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == millis, onClick = { onPick(millis) })
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

// ==================== 外观模式 ====================

/**
 * 浅色 / 深色 / 跟随系统。
 *
 * 为什么给三档、而不是一个"深色模式"开关：开关必须自己挑一个默认值，
 * 而两边都错 —— 默认浅色，夜里打开会白屏闪一下；默认深色，白天用像是应用坏了。
 * 正确的默认是"跟随系统"，而"跟随系统"是一个只有三档才能表达的状态。
 */
@Composable
private fun ThemeModeDialog(
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("外观模式") },
        text = {
            Column {
                ThemeMode.OPTIONS.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(mode) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == mode, onClick = { onPick(mode) })
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = when (mode) {
                                    ThemeMode.LIGHT -> "白底深字"
                                    ThemeMode.DARK -> "深底浅字"
                                    else -> "系统切深色时应用跟着切（默认）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/** 这一行的图标跟着当前档位走，一眼能看出现在是什么模式。 */
private fun themeModeIcon(mode: String): ImageVector = when (mode) {
    ThemeMode.LIGHT -> Icons.Outlined.LightMode
    ThemeMode.DARK -> Icons.Outlined.DarkMode
    else -> Icons.Outlined.Brightness6
}

// ==================== 新恢复码 ====================

/**
 * 换完恢复码之后的抄写校验。
 *
 * 逻辑与首启引导里的那一屏一致（同样是随机抽 3 组回填），但**不能复用同一个 Composable**：
 * 引导页那版是接在建库结果上的整屏流程，这里是一个对话框。
 * 真正需要共用的只有 [normalizeRecoveryGroup] —— 它已经提出来了。
 */
@Composable
private fun NewRecoveryCodeDialog(
    display: String,
    onDismiss: () -> Unit,
    onDone: (Boolean) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val groups = remember(display) { display.split(RecoveryCode.GROUP_SEPARATOR) }
    val quizIndices = remember(display) {
        (0 until RecoveryCode.GROUP_COUNT).shuffled(Random).take(3).sorted()
    }
    var answers by remember(display) { mutableStateOf(List(quizIndices.size) { "" }) }
    var copied by remember(display) { mutableStateOf(false) }
    var acknowledged by remember(display) { mutableStateOf(false) }

    val allCorrect = quizIndices.indices.all { i ->
        normalizeRecoveryGroup(answers[i]) == groups[quizIndices[i]]
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新的恢复码") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "旧恢复码已经失效了。请立刻把这一份抄到纸上或存进密码管理器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        groups.chunked(3).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                row.forEach { group ->
                                    Text(
                                        text = group,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }

                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(display))
                        copied = true
                    },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (copied) "已复制（记得清空剪贴板）" else "复制")
                }

                Text("把下面 3 组打回来验证一下：", style = MaterialTheme.typography.titleSmall)

                quizIndices.forEachIndexed { slot, groupIndex ->
                    OutlinedTextField(
                        value = answers[slot],
                        onValueChange = { value ->
                            answers = answers.toMutableList().also { it[slot] = value }
                        },
                        label = { Text("第 ${groupIndex + 1} 组") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text("我已把新的恢复码存在离线、安全的地方", style = MaterialTheme.typography.bodySmall)
                }

                if (acknowledged && !allCorrect) {
                    Text(
                        text = "上面几组和恢复码不一致，请再核对一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(allCorrect && acknowledged) }) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (allCorrect && acknowledged) "抄好了" else "先跳过")
            }
        },
    )
}

// ==================== 清空数据 ====================

/**
 * 清空本机数据。
 *
 * 二次确认 + 输入主密码。**为什么非要输密码**：
 * 这个操作不可撤销，而"手机上正在运行的这个应用"经常处于
 * "已经解锁、交到别人手里"的状态 —— 光点两下"确定"就抹掉全部数据，
 * 等于给了一个一键自毁按钮。要求输密码把这件事拉回到"必须知道密码的人才能做"。
 */
@Composable
private fun WipeDialog(
    state: VaultAppState,
    onDismiss: () -> Unit,
    onWiped: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var understood by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("清空本机数据？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InlineNotice(
                    text = "这一步会删除密钥文件、数据库和全部图片。做完之后，" +
                        "如果手上没有导出的备份包，这些内容就**永久**找不回来了。",
                    tone = NoticeTone.WARNING,
                )
                Text(
                    text = "只删除 FPB 自己保存的内容（记录、图片与密钥）。" +
                        "本机相册、下载与其他应用的数据都不会被碰。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (state.session.isDecoy) {
                        "请输入这个库的密码确认。"
                    } else {
                        "请输入主密码确认。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecretField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = "当前密码",
                    visible = showPassword,
                    imeAction = ImeAction.Done,
                    isError = error != null,
                    supporting = error,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showPassword, onCheckedChange = { showPassword = it })
                    Text("显示密码", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text(
                        text = "我已经确认不需要保留本机的任何内容",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && understood && password.isNotEmpty(),
                onClick = {
                    submitting = true
                    scope.launch {
                        val ok = state.verifyCurrentPassword(password.toCharArray())
                        submitting = false
                        if (!ok) {
                            error = "密码不正确"
                            return@launch
                        }
                        state.wipeEverything()
                        onWiped()
                    }
                },
            ) {
                Text(if (submitting) "处理中…" else "永久清除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") } },
    )
}

// ==================== 小工具 ====================

@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    imeAction: ImeAction,
    isError: Boolean = false,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 备份包的建议文件名。
 *
 * 带时间戳是为了让用户能在文件管理器里分清哪一份是新的 ——
 * 备份最糟糕的失败方式是"导出成功，但导入时选了三年前那一份"。
 */
private fun suggestBackupFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "FPB-backup-$stamp.zip"
}
