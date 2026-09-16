package com.fpb.vault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.fpb.vault.ui.brand.FpbWordmark
import com.fpb.vault.ui.components.InlineNotice
import com.fpb.vault.ui.components.RestoreConfirmDialog
import com.fpb.vault.ui.components.rememberBackupPicker
import com.fpb.vault.vault.BackupInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * 解锁页。
 *
 * 三条入口并存：主密码（始终可用）、生物识别（启用后自动弹出）、恢复码（收在下面）。
 * 另外还有一条**灾难入口**：导入备份 —— 换了手机、或者本机密钥文件损坏时，
 * 用户唯一的活路是从备份包里把库整个搬回来，因此它必须能在解锁页上够到，
 * 而不是藏在"解锁之后才进得去的设置页"里。
 */
@Composable
fun UnlockScreen(state: VaultAppState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var recoveryMode by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var failures by remember { mutableIntStateOf(0) }
    var cooldown by remember { mutableIntStateOf(0) }

    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val biometricReady = remember {
        state.settings.biometricEnabled &&
            state.biometric.hasEnrollment() &&
            state.biometric.availability() == com.fpb.vault.vault.BiometricGate.Availability.READY
    }

    val pickBackup = rememberBackupPicker { pendingImport = it }

    // 进入解锁页自动拉起一次生物识别。用一次性的标记，避免每次重组都弹框。
    var autoPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(biometricReady) {
        if (biometricReady && !autoPrompted && activity != null) {
            autoPrompted = true
            state.unlockWithBiometric(activity)
        }
    }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1_000)
            cooldown -= 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        FpbWordmark(
            modifier = Modifier
                .width(200.dp)
                .height(46.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "本机加密笔记",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(36.dp))

        state.bootError?.let { boot ->
            InlineNotice(
                text = "$boot\n如果本机的密钥文件已经损坏，可以用之前导出的备份包恢复" +
                    "（下方「导入备份」）。",
                tone = com.fpb.vault.ui.components.NoticeTone.WARNING,
            )
            Spacer(Modifier.height(12.dp))
        }

        if (recoveryMode) {
            Text(
                text = "输入恢复码",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "12 组、每组 4 位。大小写与连字符都不影响，抄错的 O/I/L 会自动纠正。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = recovery,
                onValueChange = { recovery = it; error = null },
                label = { Text("恢复码") },
                placeholder = { Text("XXXX-XXXX-XXXX-…") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        when (val feedback = state.unlockWithRecoveryCode(recovery)) {
                            is VaultAppState.UnlockFeedback.Failure -> error = feedback.message
                            VaultAppState.UnlockFeedback.Success -> Unit
                        }
                    }
                },
                enabled = recovery.isNotBlank() && !state.busy && cooldown == 0,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text("用恢复码解锁")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { recoveryMode = false; recovery = ""; error = null }) {
                Text("改用主密码")
            }
        } else {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("主密码") },
                singleLine = true,
                isError = error != null,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (passwordVisible) "隐藏" else "显示",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (cooldown > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "连续输错，请等待 $cooldown 秒后再试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    scope.launch {
                        when (val feedback = state.unlockWithPassword(password.toCharArray())) {
                            is VaultAppState.UnlockFeedback.Failure -> {
                                error = feedback.message
                                failures += 1
                                cooldown = cooldownFor(failures)
                                password = ""
                            }
                            VaultAppState.UnlockFeedback.Success -> {
                                password = ""
                                failures = 0
                            }
                        }
                    }
                },
                enabled = password.isNotEmpty() && !state.busy && cooldown == 0,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("正在派生密钥…")
                } else {
                    Text("解锁")
                }
            }

            if (biometricReady) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { activity?.let { state.unlockWithBiometric(it) } },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("用指纹解锁")
                }
            }

            Spacer(Modifier.height(20.dp))
            TextButton(onClick = { recoveryMode = true; error = null }) {
                Text("忘记密码？用恢复码")
            }
        }

        Spacer(Modifier.height(28.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "换了手机，或者本机数据损坏？",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "备份包里只有密文，可以放在任何地方。导入后请用该备份对应的主密码解锁。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = pickBackup,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("导入备份包")
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }

    RestoreConfirmDialog(state, pendingImport) { pendingImport = null }
}

/**
 * UI 侧的失败节流。
 *
 * 真正拦住暴力破解的是 Argon2id（每次尝试都要现算 64 MiB 的内存硬化派生），
 * 这里只是让"有人拿着手机一个劲乱试"变得没意义。
 */
private fun cooldownFor(failures: Int): Int = when {
    failures < 5 -> 0
    else -> min(30, (failures - 4) * 5)
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "未知"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
