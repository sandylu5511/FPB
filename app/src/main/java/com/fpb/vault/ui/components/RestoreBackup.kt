package com.fpb.vault.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fpb.vault.ui.VaultAppState
import com.fpb.vault.vault.BackupInfo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份包选择器。
 *
 * 解锁页与首启引导都要用到它 —— 两者都是"还没有库或还没进库"的状态，
 * 而导入备份恰好是这两种状态下唯一能救回数据的手段，因此不能只放在设置页里。
 */
@Composable
fun rememberBackupPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onPicked) }
    return remember(launcher) {
        // 不限定 MIME：各家的文件提供方对 zip 的声明五花八门
        // （application/zip、application/x-zip-compressed、octet-stream…），
        // 限制类型只会把用户挡在门外。真正的校验在 BackupManager 里做。
        { launcher.launch(arrayOf("*/*")) }
    }
}

/**
 * 导入确认对话框。
 *
 * 这是整个应用里**唯一一个会毁掉现有数据**的操作，所以它不能被简化成
 * "确定吗？"—— 用户必须看到这个包是什么时候导出的、里面有多少内容，
 * 才能判断自己有没有选错文件，以及本机还没备份的东西要不要先导出来。
 *
 * 预检（[VaultAppState.inspectBackup]）也在这里做：读不到清单、格式版本过新、
 * 缺少密钥文件等问题，必须在**动任何现有数据之前**报出来。
 */
@Composable
fun RestoreConfirmDialog(
    state: VaultAppState,
    uri: Uri?,
    onDismiss: () -> Unit,
) {
    if (uri == null) return
    val scope = rememberCoroutineScope()
    var info by remember(uri) { mutableStateOf<BackupInfo?>(null) }
    var failure by remember(uri) { mutableStateOf<String?>(null) }
    var inspecting by remember(uri) { mutableStateOf(true) }

    LaunchedEffect(uri) {
        inspecting = true
        val (parsed, error) = state.inspectBackup(uri)
        info = parsed
        failure = error
        inspecting = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (failure != null) "这个备份包不能用" else "确认导入这个备份？")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    inspecting -> Text("正在检查备份包…")

                    failure != null -> Text(
                        text = failure.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> {
                        Text("导出时间：${formatBackupTime(info?.createdAt ?: 0L)}")
                        Text("内容：约 ${info?.noteRows ?: 0} 条记录、${info?.attachments ?: 0} 个附件")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "导入会用这个包里的内容替换本机全部数据，" +
                                "并且无法撤销。如果本机还有没导出过的记录，请先取消。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (failure == null) {
                TextButton(
                    enabled = !inspecting,
                    onClick = {
                        // 交给状态自己的协程域去跑，而不是这个 Composable 的
                        // rememberCoroutineScope：恢复会先锁定保险库，本界面随即卸载，
                        // 用界面作用域的话协程会在卸载瞬间被取消，
                        // 留下一个"恢复了一半"的库 —— 那是最坏的结果。
                        state.startRestore(uri)
                        onDismiss()
                    },
                ) {
                    Text("覆盖导入", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatBackupTime(millis: Long): String {
    if (millis <= 0) return "未知（清单里没有记录）"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
