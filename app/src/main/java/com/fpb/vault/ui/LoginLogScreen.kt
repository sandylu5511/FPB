package com.fpb.vault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fpb.vault.crypto.wiping
import com.fpb.vault.session.LoginEvent
import com.fpb.vault.session.LoginKind
import com.fpb.vault.session.LoginLog
import com.fpb.vault.session.describe
import com.fpb.vault.session.loginTimeLabel
import com.fpb.vault.ui.components.EmptyState
import com.fpb.vault.ui.components.FpbTopBar
import com.fpb.vault.ui.components.GroupDivider
import com.fpb.vault.ui.components.InlineNotice
import com.fpb.vault.ui.components.KeyValueRow
import com.fpb.vault.ui.components.NoticeTone
import com.fpb.vault.ui.components.SectionHeader
import com.fpb.vault.ui.components.SettingsGroupCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 登录记录页 —— 谁在什么时候打开过这个保险库。
 *
 * ## 为什么它是一条独立的路由
 *
 * 它是一张会长的列表（最多 200 条），塞进设置页的一个对话框里既装不下也点不准。
 *
 * ## 这个页面只有真库进得来
 *
 * 诱饵库的设置页不显示入口，[VaultAppState.clearLoginLog] 等方法里也有硬校验。
 * 原因不是"怕它看"，而是**它不该知道自己被记录着**：一个正常的库不会有
 * "登录记录"这种只有诱饵才需要的说法，而一旦被胁迫者看到，他会追问
 * "为什么要专门记这个"。
 *
 * ## 页面上四种"空"必须分开说
 *
 * 一本空账（确实没人进来过）、一本读不出来的账（有人动过手）、
 * 还没有建立审计通道（存量设备，补一次就好）、通道本身读不出来。
 * 它们在读数上一模一样，而含义相反 —— 把第二种显示成第一种，
 * 正是这个功能最不该犯的错。
 */
@Composable
fun LoginLogScreen(state: VaultAppState) {
    var dialog by remember { mutableStateOf<LoginLogDialog?>(null) }
    val scope = rememberCoroutineScope()
    val events = state.loginEvents

    // "今天 / 昨天"要按本地日历算，所以每次重组重新取一次当前时刻。
    val now = System.currentTimeMillis()

    val lastReal = events.firstOrNull { it.kind == LoginKind.REAL_PASSWORD }
    val decoyEvents = events.filter { it.kind == LoginKind.DECOY_PASSWORD }
    val failedTotal = events.filter { it.kind == LoginKind.FAILED_ATTEMPTS }.sumOf { it.detail }

    Column(Modifier.fillMaxSize()) {
        FpbTopBar(
            title = "登录记录",
            subtitle = if (events.isEmpty()) null else "最近 ${events.size} 条",
            onBack = { state.pop() },
            actions = {
                // 没有记录时不显示清空：一个点了没反应的按钮，比没有这个按钮更让人困惑。
                if (events.isNotEmpty()) {
                    IconButton(onClick = { dialog = LoginLogDialog.Clear }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "清空登录记录")
                    }
                }
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 32.dp),
        ) {
            if (state.loginLogUnreadable) {
                InlineNotice(
                    text = "有一部分登录记录读不出来。那一段密文没有被正确解读 ——" +
                        "可能是存储损坏，也可能是有人改动过。下面列出的是还能读出来的部分。",
                    tone = NoticeTone.WARNING,
                )
            }

            if (!state.decoyLogBound) {
                // 这一条**必须**存在：没有它，用户会看到"假密码从没被用过"，
                // 而那是个彻底的错误结论 —— 真相是这一半根本还没在记录。
                InlineNotice(
                    text = "假密码那一半还没有开始记录。这台设备是在这个功能之前就设好假密码的，" +
                        "所以在那之后用假密码进来过几次，这里看不到。",
                    tone = NoticeTone.INFO,
                )
                SettingsGroupCard(Modifier.padding(horizontal = 16.dp)) {
                    TapRow(
                        icon = Icons.Outlined.Shield,
                        title = "现在补上",
                        subtitle = "输一次假密码，此后它的登录会出现在这张列表里",
                        onClick = { dialog = LoginLogDialog.Bind },
                    )
                }
            }

            // ==================== 概况 ====================

            SectionHeader("概况")

            SettingsGroupCard(Modifier.padding(horizontal = 16.dp)) {
                KeyValueRow(
                    label = "真密码打开",
                    value = lastReal?.let { loginTimeLabel(it.at, now) } ?: "还没有过",
                )
                GroupDivider()
                KeyValueRow(
                    label = "假密码打开",
                    value = when {
                        !state.decoyLogBound -> "未在记录"
                        decoyEvents.isEmpty() -> "还没有过"
                        else -> "共 ${decoyEvents.size} 次 · 最近 ${loginTimeLabel(decoyEvents.first().at, now)}"
                    },
                )
                GroupDivider()
                KeyValueRow(
                    label = "密码输错",
                    value = if (failedTotal == 0) "没有" else "共 $failedTotal 次",
                )
            }

            // ==================== 明细 ====================

            SectionHeader("明细")

            if (events.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.History,
                    title = if (state.loginLogUnreadable) "读不出记录" else "还没有登录记录",
                    description = if (state.loginLogUnreadable) {
                        "这台设备上的记录无法解读。它可能是存储损坏，也可能是有人改动过 ——" +
                            "在没有查清之前，不要把它当成「没人进来过」。"
                    } else {
                        "下一次解锁之后，这里会记下它发生在什么时候、用的是哪一把钥匙。"
                    },
                )
            } else {
                SettingsGroupCard(Modifier.padding(horizontal = 16.dp)) {
                    events.forEachIndexed { index, event ->
                        if (index > 0) GroupDivider()
                        LoginEventRow(event, now)
                    }
                }
            }

            if (state.loginLogTruncated) {
                Text(
                    text = "只保留最近 ${LoginLog.MAX_ENTRIES} 条，更早的已经看不到了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                )
            }

            Text(
                text = "假密码那一栏来自诱饵库自己的记录。真库只拿得到一把「只够读这条记录」的钥匙 ——" +
                    "它打不开诱饵库里的任何一条笔记。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }

    when (dialog) {
        LoginLogDialog.Clear -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("清空登录记录？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "· 真密码与假密码两本记录会一起清掉；\n" +
                            "· 清空之后列表会变成空的，而这个动作本身不会留下痕迹；\n" +
                            "· 此操作无法撤销。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.clearLoginLog()
                        dialog = null
                    },
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )

        LoginLogDialog.Bind -> BindDecoyLogDialog(
            onDismiss = { dialog = null },
            onSubmit = { password -> state.bindDecoyLog(password) },
            onBound = {
                dialog = null
                state.setMessage("已开始记录假密码的登录")
            },
            scope = scope,
        )

        null -> Unit
    }
}

/** 页面上的两个对话框。用密封接口而不是两个布尔值，避免出现"两个都开着"的非法状态。 */
private sealed interface LoginLogDialog {
    data object Clear : LoginLogDialog
    data object Bind : LoginLogDialog
}

/**
 * 一行登录记录。
 *
 * 输错那一行单独用警示色：它是这个列表里唯一一条"有人试过但没进来"的信息，
 * 与"某把钥匙打开过"在含义上完全不同，不该长得一样。
 */
@Composable
private fun LoginEventRow(event: LoginEvent, now: Long) {
    val accent = when (event.kind) {
        LoginKind.FAILED_ATTEMPTS -> MaterialTheme.colorScheme.error
        LoginKind.DECOY_PASSWORD -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconFor(event.kind),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = describe(event),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = loginTimeLabel(event.at, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun iconFor(kind: LoginKind): ImageVector = when (kind) {
    LoginKind.REAL_PASSWORD -> Icons.Outlined.Key
    LoginKind.DECOY_PASSWORD -> Icons.Outlined.Shield
    LoginKind.RECOVERY_CODE -> Icons.Outlined.ContentCopy
    LoginKind.BIOMETRIC -> Icons.Outlined.Fingerprint
    LoginKind.FAILED_ATTEMPTS -> Icons.Outlined.Warning
}

/**
 * 「点一下去做这件事」的行，样式与设置页里的行一致。
 *
 * 补绑定的入口刻意做成一行按钮，而不是让用户去点那条告警条：
 * 告警条长得像提示、不像按钮，而这件事**是必须点一下才会发生的** ——
 * 用户不会去点一条看起来只是说明文字的东西。
 */
@Composable
private fun TapRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 一次性绑定：让"假密码的登录"从此也能被记录。
 *
 * ## 为什么要用户输一次假密码
 *
 * 审计通道只能在同时拿到真库 DEK 与诱饵 DEK 的那一刻建立，而那正是"设置假密码"
 * 的时候。在这台设备上假密码早就设好了，App 手上从来没有过那把诱饵钥匙，
 * 所以只能请用户当场交一次。
 *
 * **只用来建立那条通路，密码本身不会被保存**这句话必须写在界面上：
 * 用户听到"要输一次假密码"时，最自然的担心就是"它是不是要把我的密码存下来"。
 */
@Composable
private fun BindDecoyLogDialog(
    onDismiss: () -> Unit,
    onSubmit: suspend (CharArray) -> String?,
    onBound: () -> Unit,
    scope: CoroutineScope,
) {
    var decoy by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("让假密码的登录也能被记录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "输一次假密码即可开始记录。它只用来建立一条「只够读写这条记录」的通路，" +
                        "密码本身不会被保存，也不会因此多出任何打开诱饵库的钥匙。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = decoy,
                    onValueChange = { decoy = it; error = null },
                    label = { Text("假密码") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = visible, onCheckedChange = { visible = it })
                    Text("显示密码", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (decoy.isEmpty()) {
                        error = "请输入这台设备上的假密码"
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        val failure = decoy.toCharArray().wiping { chars -> onSubmit(chars) }
                        busy = false
                        if (failure == null) onBound() else error = failure
                    }
                },
            ) { Text(if (busy) "正在确认…" else "开始记录") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}
