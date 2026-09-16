package com.fpb.vault.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.fpb.vault.model.NoteType
import com.fpb.vault.model.VaultNote
import com.fpb.vault.ui.components.EmptyState
import com.fpb.vault.ui.components.FpbTopBar
import com.fpb.vault.ui.components.InlineNotice
import com.fpb.vault.ui.components.NoticeTone
import com.fpb.vault.ui.theme.BrandGradient
import com.fpb.vault.vault.PhotoRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 列表页。
 *
 * 排序、筛选、搜索**全部在内存里做** —— 这是全字段加密的必然结果：
 * 数据库里只有随机 id 与密文，没有任何可用于排序或筛选的明文列。
 * 代价是解锁时要一次性解密全部条目，收益是这些操作全部即时完成，
 * 并且**锁定时它们会整体消失**（不存在留在磁盘上的明文索引）。
 */
@Composable
fun HomeScreen(state: VaultAppState) {
    var searching by remember { mutableStateOf(false) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val visible = remember(state.notes, state.tagFilter, state.query) { state.visibleNotes() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                val all = visible.map { it.id }.toSet()
                FpbTopBar(
                    title = "已选 ${selected.size} 条",
                    subtitle = "共 ${visible.size} 条",
                    onBack = {
                        selecting = false
                        selected = emptySet()
                    },
                    // "取消选择"与"离开这一页"是两件事，共用一个返回箭头会让人以为会退出去。
                    backIcon = Icons.Outlined.Close,
                    backDescription = "取消选择",
                    actions = {
                        TextButton(
                            onClick = { selected = if (selected.size == all.size) emptySet() else all },
                        ) {
                            Text(if (selected.size == all.size) "取消全选" else "全选")
                        }
                        IconButton(
                            onClick = { confirmDelete = true },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "删除选中的记录",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
            } else {
                FpbTopBar(
                    title = "FPB",
                    subtitle = "本机加密 · ${state.notes.size} 条",
                    actions = {
                        // 诱饵库（假密码进入）不提供照片库入口：诱饵库要看起来像
                        // 一个正常的空应用，一个空的"照片库"反而是此地无银的暗示。
                        if (!state.isDecoy) {
                            IconButton(onClick = { state.push(Route.Photos) }) {
                                Icon(Icons.Outlined.PhotoLibrary, contentDescription = "照片库")
                            }
                        }
                        IconButton(onClick = { searching = !searching }) {
                            Icon(
                                imageVector = if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                                contentDescription = if (searching) "关闭搜索" else "搜索",
                            )
                        }
                        IconButton(onClick = { state.push(Route.Settings) }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "设置")
                        }
                    },
                )
            }

            // 搜索框与标签筛选条在多选模式下整体收起：它们会改变 visible，
            // 而"已选 N 条"是相对 visible 说的 —— 中途换一次筛选，选中集合与屏幕上
            // 能看到的条目就对不上了，用户没有任何办法看出这件事。收起入口最省事也最安全。
            if (searching && !selecting) {
                TextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    placeholder = {
                        Text(
                            "搜索标题、正文、标签…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { state.query = "" }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "清空",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            state.loadWarning?.let { warning ->
                InlineNotice(text = "$warning 建议尽快导出备份并检查存储。")
            }

            // 「该导出备份了」的提醒。与上面的告警条互斥：那条本身就写着
            // "建议尽快导出备份"，两条叠着出现只会让人不知道该先处理哪个。
            if (state.loadWarning == null) {
                state.backupNotice?.let { text ->
                    InlineNotice(
                        text = text,
                        onClick = { state.push(Route.Settings) },
                    )
                }
            }

            if (state.tagCounts.isNotEmpty() && !selecting) {
                TagFilterRow(
                    state = state,
                    visibleCount = visible.size,
                )
            }

            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = if (state.query.isBlank() && state.tagFilter == null) {
                            Icons.Outlined.NoteAlt
                        } else {
                            Icons.Outlined.Search
                        },
                        title = if (state.query.isBlank() && state.tagFilter == null) {
                            "还没有任何记录"
                        } else {
                            "没有匹配的记录"
                        },
                        description = if (state.query.isBlank() && state.tagFilter == null) {
                            "点右下角的按钮记第一条。文字、图片、待办、账号密码都可以。"
                        } else {
                            "换个关键词，或清掉标签筛选试试。"
                        },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 6.dp,
                        bottom = 108.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visible, key = { it.id }) { note ->
                        NoteCard(
                            state = state,
                            note = note,
                            selecting = selecting,
                            picked = note.id in selected,
                            onClick = {
                                if (selecting) {
                                    selected = if (note.id in selected) {
                                        selected - note.id
                                    } else {
                                        selected + note.id
                                    }
                                } else {
                                    state.push(Route.View(note.id))
                                }
                            },
                            onLongPress = {
                                if (!selecting) selecting = true
                                selected = selected + note.id
                            },
                        )
                    }
                }
            }
        }

        // 多选时收起"新建"：正忙着挑要删的记录，此时误触弹出的类型对话框最容易造成混乱
        if (!selecting) {
            ExtendedFloatingActionButton(
                onClick = { showCreateSheet = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("新建") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(20.dp),
            )
        }
    }

    if (showCreateSheet) {
        CreateTypeDialog(
            onDismiss = { showCreateSheet = false },
            onPick = { type ->
                showCreateSheet = false
                state.push(Route.Editor(type, noteId = null))
            },
        )
    }

    if (confirmDelete) {
        val count = selected.size
        val withImages = visible.count { it.id in selected && it.payload.images.isNotEmpty() }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除选中的 $count 条记录？") },
            text = {
                Text(
                    if (withImages > 0) {
                        "其中 $withImages 条带图片，删除后图片也会一并销毁，无法恢复。"
                    } else {
                        "删除后无法恢复。建议定期导出备份。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        state.deleteNotes(selected)
                        selecting = false
                        selected = emptySet()
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

@Composable
private fun TagFilterRow(state: VaultAppState, visibleCount: Int) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = state.tagFilter == null,
                onClick = { state.tagFilter = null },
                label = { Text("全部") },
            )
        }
        items(state.tagCounts, key = { it.name }) { tag ->
            FilterChip(
                selected = state.tagFilter == tag.name,
                onClick = {
                    state.tagFilter = if (state.tagFilter == tag.name) null else tag.name
                },
                label = { Text("${tag.name} ${tag.count}") },
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Outlined.Label,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                },
            )
        }
        if (state.tagFilter != null) {
            item {
                Text(
                    text = "筛选中 $visibleCount 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp, start = 4.dp),
                )
            }
        }
    }
}

/**
 * 列表里的一条。点击进详情（多选模式下改为勾选），长按进入多选。
 *
 * 用的是不带 `onClick` 的 [Card] 重载 + 自己挂 [combinedClickable]：
 * `Card(onClick = …)` 那个重载没有长按，而"长按进多选"正是这里要的入口。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    state: VaultAppState,
    note: VaultNote,
    selecting: Boolean,
    picked: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val payload = note.payload
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            // 先 clip 再 clickable：水波纹由 clickable 生成，排在 clip 之前的话
            // 点一下看到的是一个方角的水波纹，比卡片本身大一圈。
            .clip(CARD_SHAPE)
            .then(
                if (picked) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CARD_SHAPE)
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (selecting) {
                SelectionDot(picked)
                Spacer(Modifier.width(10.dp))
            }

            TypeBadge(note.type)

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = note.displayTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (payload.favorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Outlined.Star,
                            contentDescription = "已收藏",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }

                val summary = payload.summary()
                if (summary.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = relativeTime(payload.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (payload.tags.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = payload.tags.joinToString(" ") { "#$it" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (note.type == NoteType.IMAGE && payload.images.size > 1) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${payload.images.size} 张",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 图片条目在卡片右侧挂一张缩略图。它是解密后按需生成的，
            // 只进内存缓存，锁定时随之消失。
            val firstImage = payload.images.firstOrNull()
            if (firstImage != null) {
                Spacer(Modifier.width(12.dp))
                BlobThumbnail(
                    state = state,
                    blobId = firstImage.blobId,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

/**
 * 多选模式下卡片最左边那个勾选圆点。
 *
 * `contentDescription` 无论选没选中都给一个固定值：它是**给自动化验证用的锚点** ——
 * 否则"进入多选后卡片左边会多出一个圆点"这件事就没有任何可断言的痕迹。
 */
@Composable
private fun SelectionDot(picked: Boolean) {
    val tint = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            // 与 TypeBadge（38dp 高的方块）视觉居中对齐：20dp 的圆点顶边下移 9dp
            .padding(top = 9.dp)
            .size(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (picked) tint else Color.Transparent)
            .border(1.5.dp, tint, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // 勾始终在（未选中时画成全透明），否则节点在 a11y 树里时有时无，
        // 自动化验证只能靠"数节点个数"这种脆判据。
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = if (picked) "已选中" else "未选中",
            tint = if (picked) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun BlobThumbnail(
    state: VaultAppState,
    blobId: String,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(blobId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(blobId) {
        bitmap = state.thumbnail(blobId)
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
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
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TypeBadge(type: NoteType) {
    val (icon, tint) = when (type) {
        NoteType.TEXT -> Icons.Outlined.NoteAlt to MaterialTheme.colorScheme.primary
        NoteType.IMAGE -> Icons.Outlined.Image to MaterialTheme.colorScheme.secondary
        NoteType.CHECKLIST -> Icons.Outlined.Checklist to MaterialTheme.colorScheme.tertiary
        NoteType.CREDENTIAL -> Icons.Outlined.Password to MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = type.label(),
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** 新建时选类型。四类编辑器差异很大，所以先问一句再进去。 */
@Composable
private fun CreateTypeDialog(
    onDismiss: () -> Unit,
    onPick: (NoteType) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "新建",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                NoteType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(type) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val (icon, hint) = type.iconAndHint()
                        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(text = type.label(), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 展示辅助 ====================

fun NoteType.label(): String = when (this) {
    NoteType.TEXT -> "文字"
    NoteType.IMAGE -> "图片"
    NoteType.CHECKLIST -> "待办"
    NoteType.CREDENTIAL -> "账号密码"
}

private fun NoteType.iconAndHint(): Pair<ImageVector, String> = when (this) {
    NoteType.TEXT -> Icons.Outlined.NoteAlt to "纯文本，适合记想法、地址、证件号"
    NoteType.IMAGE -> Icons.Outlined.Image to "原图直接加密存储，不压缩"
    NoteType.CHECKLIST -> Icons.Outlined.Checklist to "清单，可勾选"
    NoteType.CREDENTIAL -> Icons.Outlined.Password to "账号、密码等结构化字段"
}

/**
 * 标题为空时用摘要首行顶上，避免卡片看起来是空白。
 *
 * 图片记录是唯一的例外：它显示的是**按创建时间派生**的 `照片 · 9月15日 20:44`，
 * 而不是落盘的字。图库导入时不再往记录里写这个标题，于是"标题为空"永远等于
 * "用户没自己起过名字" —— 那条记录才敢在照片被删光时一起删掉（见 [PhotoRecord]）。
 * 派生出来的样子与旧版本写进记录里的那串完全一致，老用户看不出任何差别。
 */
fun VaultNote.displayTitle(): String = when {
    title.isNotBlank() -> title
    type == NoteType.IMAGE && payload.createdAt > 0L -> PhotoRecord.autoTitle(payload.createdAt)
    else -> payload.summary(limit = 24).ifBlank { type.label() + "记录" }
}

/** 列表底部的时间。今天的只显示时分，避免每条都是完整日期占满一行。 */
private fun relativeTime(millis: Long): String {
    if (millis <= 0) return ""
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochMilli(millis).atZone(zone)
    val today = LocalDate.now(zone)
    return when (moment.toLocalDate()) {
        today -> "今天 " + moment.format(TIME_FORMAT)
        today.minusDays(1) -> "昨天 " + moment.format(TIME_FORMAT)
        else -> if (moment.year == today.year) {
            moment.format(MONTH_DAY_FORMAT)
        } else {
            moment.format(FULL_DATE_FORMAT)
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MONTH_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
private val FULL_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")

/** 列表卡片的圆角。多选时的那道描边必须与卡片本身同形，否则会露出四个角。 */
private val CARD_SHAPE = RoundedCornerShape(12.dp)
