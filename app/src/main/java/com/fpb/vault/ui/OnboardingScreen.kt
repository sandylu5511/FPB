package com.fpb.vault.ui

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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fpb.vault.crypto.CreationResult
import com.fpb.vault.crypto.RecoveryCode
import com.fpb.vault.crypto.VaultKeyring
import com.fpb.vault.ui.brand.FpbWordmark
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 首启引导。
 *
 * 顺序刻意是：欢迎 → 主密码 → （可选）假密码 → 生成 → 抄写恢复码 → 进入。
 *
 * ## 为什么恢复码的确认不能只是一句"我抄好了"
 *
 * 恢复码是这个库**唯一**的兜底：没有它，忘掉主密码 = 数据永久消失。
 * 而这个确认框是用户唯一一次被要求"证明自己真的抄下来了"的机会。
 * 因此这里要求他从 12 组里**随机抽出的 3 组**逐字打回来 ——
 * 一个只勾了"我已抄写"就过去的复选框，在真出事的那一天毫无价值。
 */
@Composable
fun OnboardingScreen(state: VaultAppState) {
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) }
    var primary by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var decoyEnabled by remember { mutableStateOf(false) }
    var decoy by remember { mutableStateOf("") }
    var created by remember { mutableStateOf<CreationResult?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        FpbWordmark(
            modifier = Modifier
                .width(190.dp)
                .height(44.dp),
        )

        Spacer(Modifier.height(28.dp))

        when (step) {
            0 -> WelcomeStep(onNext = { step = 1 })

            1 -> PasswordStep(
                primary = primary,
                confirm = confirm,
                error = error,
                onPrimaryChange = { primary = it; error = null },
                onConfirmChange = { confirm = it; error = null },
                onNext = {
                    error = validateNewPassword(primary, confirm)
                    if (error == null) step = 2
                },
            )

            2 -> DecoyStep(
                enabled = decoyEnabled,
                decoy = decoy,
                error = error,
                onEnabledChange = { decoyEnabled = it; error = null },
                onDecoyChange = { decoy = it; error = null },
                onBack = { step = 1 },
                onNext = {
                    if (decoyEnabled) {
                        error = when {
                            decoy.length < VaultKeyring.MIN_PASSWORD_LENGTH ->
                                "假密码至少 ${VaultKeyring.MIN_PASSWORD_LENGTH} 个字符"
                            decoy == primary -> "假密码不能与主密码相同"
                            else -> null
                        }
                        if (error != null) return@DecoyStep
                    }
                    working = true
                    step = 3
                    scope.launch {
                        val result = state.createVault(
                            primary.toCharArray(),
                            if (decoyEnabled) decoy.toCharArray() else null,
                        )
                        created = result
                        working = false
                        if (result == null) {
                            error = state.notice
                            state.notice = null
                            step = 1
                        } else {
                            step = 4
                        }
                    }
                },
            )

            3 -> CreatingStep()

            4 -> RecoveryStep(
                created = created,
                onDone = { confirmed ->
                    val result = created ?: return@RecoveryStep
                    state.finishOnboarding(result, confirmed)
                    if (state.phase != VaultAppState.Phase.UNLOCKED) {
                        error = state.notice
                        state.notice = null
                    }
                },
            )
        }

        // 换手机 / 重装之后的第一站就是这里：还没有库、也没有密码，
        // 唯一能把数据找回来的路是导入之前导出的备份包。
        // 因此这个入口必须在引导页上，而不是藏在"解锁之后才进得去的设置页"里。
        if (step == 0) {
            Spacer(Modifier.height(22.dp))
            var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
            val pickBackup = com.fpb.vault.ui.components.rememberBackupPicker {
                pendingRestore = it
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已经有备份包？",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = pickBackup) { Text("从备份恢复") }
            }
            com.fpb.vault.ui.components.RestoreConfirmDialog(state, pendingRestore) {
                pendingRestore = null
            }
        }

        if (working && step != 3) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

// ==================== 第 1 屏：说明 ====================

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "本机加密笔记",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        listOf(
            "内容只存在这台设备上" to "应用没有申请联网权限，系统层面就无法把数据发出去。",
            "忘记密码只能靠恢复码" to "密钥由主密码派生，我们没有、也不会有任何后门。",
            "内容全部加密后落盘" to "数据库里只有随机 id 和密文，看不出记了几条、记了什么。",
        ).forEach { (title, detail) ->
            PromoRow(title, detail)
        }

        Spacer(Modifier.height(6.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text("开始设置")
        }
    }
}

@Composable
private fun PromoRow(title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ==================== 第 2 屏：主密码 ====================

@Composable
private fun PasswordStep(
    primary: String,
    confirm: String,
    error: String?,
    onPrimaryChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "设置主密码",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "主密码是打开这个保险库的唯一钥匙，不会有第二个人能帮你重置它。" +
                "建议用一句你记得住的话，而不是生日或手机号。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = primary,
            onValueChange = onPrimaryChange,
            label = { Text("主密码（至少 ${VaultKeyring.MIN_PASSWORD_LENGTH} 位）") },
            singleLine = true,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) {
                            Icons.Outlined.VisibilityOff
                        } else {
                            Icons.Outlined.Visibility
                        },
                        contentDescription = if (visible) "隐藏" else "显示",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = confirm,
            onValueChange = onConfirmChange,
            label = { Text("再输一次") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        StrengthHint(primary)

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onNext,
            enabled = primary.isNotEmpty() && confirm.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text("下一步")
        }
    }
}

/** 只做粗略的强度提示。不拦人 —— 拦人只会让用户去用一个更短的密码。 */
@Composable
private fun StrengthHint(password: String) {
    if (password.isEmpty()) return
    val (label, tone) = when {
        password.length < 8 -> "太短，容易被穷举" to MaterialTheme.colorScheme.error
        password.length < 12 -> "长度尚可，可再长一点" to MaterialTheme.colorScheme.tertiary
        else -> "长度不错" to com.fpb.vault.ui.components.SuccessGreen
    }
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = tone)
}

// ==================== 第 3 屏：假密码 ====================

@Composable
private fun DecoyStep(
    enabled: Boolean,
    decoy: String,
    error: String?,
    onEnabledChange: (Boolean) -> Unit,
    onDecoyChange: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "要不要再设一个假密码？",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "输主密码进真库，输假密码进一个独立的空库。两个库混在同一个文件里，" +
                "从外部看不出这里存在第二个库。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onEnabledChange(!enabled) }
                .padding(vertical = 4.dp),
        ) {
            Checkbox(checked = enabled, onCheckedChange = onEnabledChange)
            Text("启用假密码", style = MaterialTheme.typography.bodyLarge)
        }

        if (enabled) {
            OutlinedTextField(
                value = decoy,
                onValueChange = onDecoyChange,
                label = { Text("假密码（至少 ${VaultKeyring.MIN_PASSWORD_LENGTH} 位）") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "提示：假密码要像真的。它是拿来在被胁迫时交出去的，" +
                    "因此日常别用、也别在里面放重要东西 —— 那个库会显得很空，" +
                    "可以放几条无关紧要的记录让它看起来正常。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.height(50.dp)) {
                Text("上一步")
            }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f).height(50.dp),
            ) {
                Text(if (enabled) "生成保险库" else "跳过，生成保险库")
            }
        }
    }
}

// ==================== 第 4 屏：生成中 ====================

@Composable
private fun CreatingStep() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(34.dp), strokeWidth = 3.dp)
        Text(
            text = "正在派生密钥…",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Argon2id 会刻意占用较大内存并反复迭代，用来拖慢暴力破解。" +
                "这一步在手机上大概需要 1~3 秒，只发生这一次。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

// ==================== 第 5 屏：恢复码 ====================

@Composable
private fun RecoveryStep(
    created: CreationResult?,
    onDone: (Boolean) -> Unit,
) {
    if (created == null) {
        CreatingStep()
        return
    }

    val clipboard = LocalClipboardManager.current
    val display = remember(created) { RecoveryCode.formatForDisplay(created.recoveryCode) }
    val groups = remember(created) { display.split(RecoveryCode.GROUP_SEPARATOR) }

    // 抽 3 组让用户回填。固定抽哪几组反而会被"只抄这 3 组"钻空子，所以每次进入都随机。
    val quizIndices = remember(created) {
        (0 until RecoveryCode.GROUP_COUNT).shuffled(Random).take(3).sorted()
    }
    var answers by remember(created) { mutableStateOf(List(quizIndices.size) { "" }) }
    var copied by remember(created) { mutableStateOf(false) }
    var acknowledged by remember(created) { mutableStateOf(false) }

    val allCorrect = quizIndices.indices.all { i ->
        normalizeRecoveryGroup(answers[i]) == groups[quizIndices[i]]
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "抄下恢复码",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "这是你忘掉主密码后唯一的出路。请抄在纸上或存进密码管理器，" +
                "不要只截图留在手机里。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { group ->
                            Text(
                                text = group,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(display))
                    copied = true
                },
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "已复制（记得清空剪贴板）" else "复制")
            }
        }

        Text(
            text = "抄完了？请把下面 3 组打回来验证一下。",
            style = MaterialTheme.typography.titleSmall,
        )

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
            Checkbox(
                checked = acknowledged,
                onCheckedChange = { acknowledged = it },
            )
            Text(
                text = "我已把恢复码保存在离线、安全的地方",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = { onDone(allCorrect && acknowledged) },
            enabled = allCorrect && acknowledged,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("进入保险库")
        }

        if (acknowledged && !allCorrect) {
            Text(
                text = "上面几组和恢复码不一致，请再核对一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ==================== 工具 ====================

private fun validateNewPassword(primary: String, confirm: String): String? = when {
    primary.length < VaultKeyring.MIN_PASSWORD_LENGTH ->
        "主密码至少 ${VaultKeyring.MIN_PASSWORD_LENGTH} 位"
    primary != confirm -> "两次输入不一致"
    else -> null
}

/**
 * Crockford Base32 的容错归一化。
 *
 * 恢复码的字符集里没有 I、L、O、U，所以用户打进来的这几个字符一定是看错了 ——
 * 把它们按字形纠正回 0/1。抄写场景下这是最常见的一种错，不纠正的话
 * 用户会以为是"恢复码失效"，而不是"自己抄错了一个字母"。
 *
 * 设置页换恢复码时的抄写校验也走这里，因此是 `internal` 而不是 `private`：
 * 两份归一化实现一旦漂移，就会出现"引导页抄得进、设置页抄不进"这种莫名其妙的差别。
 */
internal fun normalizeRecoveryGroup(input: String): String = buildString {
    input.trim().uppercase().forEach { c ->
        append(
            when (c) {
                'O' -> '0'
                'I', 'L' -> '1'
                ' ' -> ' '
                else -> c
            },
        )
    }
}.replace(" ", "")
