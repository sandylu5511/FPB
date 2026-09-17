package com.fpb.vault.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fpb.vault.model.NoteType
import com.fpb.vault.model.VaultNote
import com.fpb.vault.model.VideoRef
import com.fpb.vault.ui.components.FpbTopBar
import com.fpb.vault.ui.components.MotionBadge
import com.fpb.vault.ui.components.SuccessGreen
import com.fpb.vault.ui.components.VideoBadge
import com.fpb.vault.ui.components.VideoPlayGlyph
import com.fpb.vault.vault.MotionPhoto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 只读查看页。
 *
 * ## 为什么要有这一页
 *
 * 之前的交互是"点卡片直接进编辑器"，看一眼内容也得面对一整页输入框和保存按钮 ——
 * 大多数时候用户只是想看看记了什么。因此点卡片进**这里**（纯阅读），
 * 想改再点右上角的编辑按钮进 [NoteEditorScreen]。
 *
 * 这一页**没有任何输入控件**：正文不可点入（但可长按选择复制），
 * 待办不能勾、字段不能改 —— 防止"只是想看看结果误触改了数据"。
 */
@Composable
fun NoteViewScreen(state: VaultAppState, route: Route.View) {
    // 依赖 state.notes：从编辑页保存/删除返回这里时，notes 会变，
    // remember 会重新取到最新内容，页面随之刷新。
    val note = remember(state.notes, route.noteId) { state.note(route.noteId) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 条目被（编辑页里）删掉了：这里没东西可看，退回列表。
    LaunchedEffect(note) {
        if (note == null && state.routes.lastOrNull() == route) state.pop()
    }

    if (note == null) return

    Column(Modifier.fillMaxSize()) {
        FpbTopBar(
            title = note.type.label(),
            onBack = { state.pop() },
            actions = {
                IconButton(onClick = { state.push(Route.Editor(note.type, note.id)) }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除")
                }
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ==================== 标题与元信息 ====================
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.displayTitle(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (note.payload.favorite) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = "已收藏",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Text(
                text = "更新于 ${formatDateTime(note.payload.updatedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ==================== 正文（按类型） ====================
            ViewBody(state = state, note = note)

            // ==================== 标签 ====================
            if (note.payload.tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    note.payload.tags.take(4).forEach { tag ->
                        AssistChip(onClick = { }, label = { Text("#$tag") })
                    }
                }
                if (note.payload.tags.size > 4) {
                    Text(
                        text = note.payload.tags.drop(4).joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = "创建于 ${formatDateTime(note.payload.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记录？") },
            text = {
                Text(
                    if (note.payload.images.isNotEmpty()) {
                        "删除后它的图片也会一并销毁，无法恢复。"
                    } else {
                        "删除后无法恢复。建议定期导出备份。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        state.deleteNote(note.id)
                        state.pop()
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

// ==================== 分类型只读正文 ====================

@Composable
private fun ViewBody(state: VaultAppState, note: VaultNote) {
    when (note.type) {
        NoteType.TEXT -> TextBody(note.payload.body)
        NoteType.IMAGE, NoteType.VIDEO -> MediaBody(state = state, note = note)
        NoteType.CHECKLIST -> ChecklistBody(note)
        NoteType.CREDENTIAL -> CredentialBody(note)
    }
}

@Composable
private fun TextBody(body: String) {
    if (body.isBlank()) return
    SelectionContainer {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 媒体正文：图片与视频各来一组三列网格，点任意一格进全屏查看器。
 *
 * ## 为什么图与视频合成一个正文
 *
 * 因为**浏览器的语义是"一次看完整条记录里的所有东西"**，而全屏查看器那条序列
 * 本来就是跨类型的（左右滑动能在照片和视频之间翻）。若分成两个正文，
 * 点一张图进去只会看到图、点一个视频进去只能看到视频，
 * "翻到下一格"的行为就与网格上看到的顺序对不上了。
 *
 * 两类媒体共用一条 blobId 序列（先图后视频，与网格的阅读顺序一致），
 * 于是查看器里的页码与网格上数出来的位置一致。
 */
@Composable
private fun MediaBody(state: VaultAppState, note: VaultNote) {
    val images = note.payload.images
    val videos = note.payload.videos
    if (images.isEmpty() && videos.isEmpty()) {
        Text(
            text = "（没有照片或视频${if (note.payload.body.isBlank()) "，也没有备注" else ""}）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val blobIds = images.map { it.blobId } + videos.map { it.blobId }
        MediaGrid(
            state = state,
            blobIds = blobIds,
            imageCount = images.size,
            videos = videos,
        )
        TextBody(note.payload.body)
    }
}

/**
 * 三列网格。[blobIds] 的前 [imageCount] 个是图片，其后依次对应 [videos]。
 *
 * 这样切分而不是传两个格子列表，是因为"点开之后翻页的顺序"必须与网格上的顺序
 * 逐格对齐 —— 分成两组各渲染一次的话，两处各自算一遍序号，迟早会有一处算错。
 */
@Composable
private fun MediaGrid(
    state: VaultAppState,
    blobIds: List<String>,
    imageCount: Int,
    videos: List<VideoRef>,
) {
    blobIds.chunked(3).forEachIndexed { rowIndex, rowIds ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowIds.forEachIndexed { columnIndex, blobId ->
                val position = rowIndex * 3 + columnIndex
                val video = if (position >= imageCount) videos[position - imageCount] else null
                ViewThumbnail(
                    state = state,
                    blobId = blobId,
                    video = video,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                    onClick = {
                        state.push(Route.Viewer(blobIds, position.coerceAtLeast(0)))
                    },
                )
            }
            // 不足三张时补齐占位，避免最后一张被拉伸
            repeat(3 - rowIds.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** [video] 非空表示这一格是视频，封面与角标要走另一条路。 */
@Composable
private fun ViewThumbnail(
    state: VaultAppState,
    blobId: String,
    modifier: Modifier = Modifier,
    video: VideoRef? = null,
    onClick: () -> Unit,
) {
    var bitmap by remember(blobId) { mutableStateOf<Bitmap?>(null) }
    var motion by remember(blobId) { mutableStateOf<MotionPhoto.Motion?>(null) }
    LaunchedEffect(blobId, video) {
        if (video != null) {
            bitmap = state.videoThumbnail(blobId, video.durationMs)
        } else {
            bitmap = state.thumbnail(blobId)
            // 缩略图那一步已经在同一份字节上顺手算过了，这里是纯内存命中
            motion = state.motionOf(blobId)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (video != null) Icons.Outlined.Videocam else Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        if (video != null) {
            // 封面还没解出来时先给个三角，否则那几秒里这一格看起来就是一张空白图
            if (current == null) VideoPlayGlyph()
            VideoBadge(
                durationMs = video.durationMs,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
            )
        } else if (motion != null) {
            MotionBadge(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
            )
        }
    }
}

/** 待办正文：只读。已完成的灰显 + 划线。 */
@Composable
private fun ChecklistBody(note: VaultNote) {
    val todos = note.payload.todos
    if (todos.isEmpty()) return
    val done = todos.count { it.done }
    Text(
        text = "已完成 $done / ${todos.size}",
        style = MaterialTheme.typography.titleSmall,
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            todos.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (item.done) {
                            Icons.Outlined.CheckCircle
                        } else {
                            Icons.Outlined.RadioButtonUnchecked
                        },
                        contentDescription = if (item.done) "已完成" else "未完成",
                        tint = if (item.done) {
                            SuccessGreen
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (item.done) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (index != todos.lastIndex) {
                    Box(
                        Modifier
                            .padding(start = 50.dp)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
        }
    }
}

/** 账号密码正文：分组卡片，敏感字段默认遮蔽，可单个显示/复制。 */
@Composable
private fun CredentialBody(note: VaultNote) {
    val clipboard = LocalClipboardManager.current
    val fields = note.payload.fields
    if (fields.isEmpty()) return
    var copyMessage by remember { mutableStateOf<String?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            fields.forEachIndexed { index, field ->
                var revealed by remember(note.id, index) { mutableStateOf(!field.sensitive) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = field.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (revealed) field.value else "••••••••",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = if (revealed && field.sensitive) {
                                    FontFamily.Monospace
                                } else {
                                    FontFamily.Default
                                },
                            ),
                            maxLines = if (revealed) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (field.sensitive) {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                imageVector = if (revealed) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = if (revealed) "隐藏" else "显示",
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(field.value))
                            copyMessage = "已复制「${field.label}」（记得用完清空剪贴板）"
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "复制",
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                if (index != fields.lastIndex) {
                    Box(
                        Modifier
                            .padding(start = 16.dp)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
        }
    }

    copyMessage?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelMedium,
            color = SuccessGreen,
        )
    }
}

private fun formatDateTime(millis: Long): String {
    if (millis <= 0) return "未知"
    return SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(millis))
}
