package com.fpb.vault.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fpb.vault.model.ImageRef
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.ui.components.EmptyState
import com.fpb.vault.ui.components.FpbTopBar
import com.fpb.vault.vault.ImagePipeline
import com.fpb.vault.vault.MotionPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 照片库：聚合当前库里全部图片记录的照片网格。
 *
 * ## 设计取舍
 *
 * - **聚合视图，不是独立存储**：照片库不另起一套数据，它把所有图片类型记录里的
 *   图片摊平成一个网格。真库里存了多少张，这里就能看到多少张；
 *   锁定时它和列表页一样整体消失。
 * - **入口只在真库显示**（见 HomeScreen）：诱饵库的使命是"看起来是个正常的空库"，
 *   一个空空如也的照片库入口反而是画蛇添足的暗示。
 * - **导入即一条记录**：一次多选导入生成一条图片记录，在列表页里也能看到它、给它加标签。
 *   这条记录**不落标题** —— 列表里看到的"照片 · 9月15日 20:44"是显示时按创建时间派生的
 *   （见 [com.fpb.vault.vault.PhotoRecord]），这样"标题为空"就恒等于"用户没自己起过名字"，
 *   照片被删光时它才敢连记录一起删掉。
 * - **删除走多选**：长按任意一格进入选择模式，之后点击即勾选，
 *   顶栏给出"已选 N 张 / 全选 / 删除"。单张删除不再单独开一个确认框 ——
 *   同一个动作有两条路径，迟早会只有一条被维护到。
 */
@Composable
fun PhotoLibraryScreen(state: VaultAppState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 网格按记录更新时间降序摊平；同一条记录内的照片保持导入顺序
    val entries = remember(state.notes) {
        state.notes
            .filter { it.type == NoteType.IMAGE && it.payload.images.isNotEmpty() }
            .sortedByDescending { it.updatedAt }
            .flatMap { note -> note.payload.images.map { PhotoEntry(it.blobId, note.id) } }
    }

    var busy by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 照片被删光或换了之后，选中集合里会留下已经不存在的 blobId。
    // 每次网格内容变化就收敛一次，否则顶栏会显示"已选 3 张"而屏幕上只剩 2 张能勾。
    // effect 的 key 只挂 entries：下面这个 effect 体内会改 selected，
    // 若把它也当 key，就会像 AppRoot 那个 Snackbar 一样把自己的协程取消掉。
    LaunchedEffect(entries) {
        val alive = entries.map { it.blobId }.toSet()
        selected = selected intersect alive
        if (selected.isEmpty()) selecting = false
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_PICK),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            // 原图直存：与编辑器同一条入库管线（读字节 + 校验可解码），不压缩。
            // 实况照片的那一小段影片也在这份字节里 —— 不做任何裁剪，否则就不是原图了。
            val outcomes = uris.map { uri ->
                withContext(Dispatchers.IO) { ImagePipeline.prepare(context, uri) }
            }
            val prepared = outcomes.filterIsInstance<ImagePipeline.Preparation.Ready>()
                .map { it.prepared }
            val tooLarge = outcomes.filterIsInstance<ImagePipeline.Preparation.TooLarge>()
            if (prepared.isEmpty()) {
                busy = false
                // 全都是"过大"时必须说"过大"，否则用户会一直换图片重试同一个必然失败的操作
                state.setMessage(
                    tooLarge.firstOrNull()?.message ?: "选中的图片都读不出来，换一批试试",
                )
                return@launch
            }

            val refs = prepared.mapNotNull { item ->
                state.putImage(item.bytes)?.let { blobId ->
                    ImageRef(blobId, item.width, item.height)
                }
            }
            if (refs.isEmpty()) {
                busy = false
                return@launch
            }

            val now = System.currentTimeMillis()
            val payload = NotePayload(
                type = NoteType.IMAGE,
                // 标题刻意留空：显示时派生，见类注释
                title = "",
                createdAt = now,
                updatedAt = now,
                images = refs,
            )
            val saved = state.createNote(payload)
            busy = false
            if (saved != null) {
                // 部分过大被跳过时要说清楚，否则用户数一数"我选了 9 张，怎么只有 6 张"
                state.setMessage(
                    buildString {
                        append("已保存 ${refs.size} 张照片")
                        if (tooLarge.isNotEmpty()) append("，${tooLarge.size} 张因超过体积上限被跳过")
                    },
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                FpbTopBar(
                    title = "已选 ${selected.size} 张",
                    subtitle = "共 ${entries.size} 张",
                    onBack = {
                        selecting = false
                        selected = emptySet()
                    },
                    // "取消选择"与"离开这一页"是两件事，共用一个返回箭头会让人以为会退出去。
                    backIcon = Icons.Outlined.Close,
                    backDescription = "取消选择",
                    actions = {
                        val all = entries.map { it.blobId }.toSet()
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
                                contentDescription = "删除选中的照片",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
            } else {
                FpbTopBar(
                    title = "照片库",
                    subtitle = "原图加密存储 · ${entries.size} 张",
                    onBack = { state.pop() },
                )
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Outlined.PhotoLibrary,
                        title = "还没有照片",
                        description = "点右下角的按钮从相册导入。照片按原图加密存储，" +
                            "只有解锁后才能看到。导入后会成为库里独立的一份，" +
                            "之后从相册删除原图不影响这里。" +
                            "长按照片可以一次选多张一起删。",
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 2.dp,
                        end = 2.dp,
                        top = 2.dp,
                        bottom = 108.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(entries, key = { _, entry -> entry.blobId }) { _, entry ->
                        PhotoCell(
                            state = state,
                            entry = entry,
                            selecting = selecting,
                            picked = entry.blobId in selected,
                            onOpen = {
                                val blobIds = entries.map { it.blobId }
                                state.push(Route.Viewer(blobIds, blobIds.indexOf(entry.blobId)))
                            },
                            onToggle = {
                                selected = if (entry.blobId in selected) {
                                    selected - entry.blobId
                                } else {
                                    selected + entry.blobId
                                }
                            },
                            onLongPress = {
                                if (!selecting) selecting = true
                                selected = selected + entry.blobId
                            },
                        )
                    }
                }
            }
        }

        // 选择模式下把导入按钮撤掉：正忙着挑要删的照片，此时误触弹出的选图器最容易造成混乱
        if (!selecting) {
            ExtendedFloatingActionButton(
                onClick = {
                    if (!busy) {
                        pickImages.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }
                },
                icon = {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                    }
                },
                text = { Text(if (busy) "正在保存…" else "添加照片") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(20.dp),
            )
        }
    }

    if (confirmDelete) {
        val picked = entries.filter { it.blobId in selected }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除选中的 ${picked.size} 张照片？") },
            text = {
                Text(
                    "删除后无法恢复。如果某条记录的照片被全部删掉，" +
                        "而它上面没有你写的标题、备注或标签，这条记录也会一并删除。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        state.removePhotos(picked.map { PhotoRef(it.noteId, it.blobId) })
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

/**
 * 网格里的一格。
 *
 * 点击看图，长按进入多选；已经在多选模式时点击即勾选。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    state: VaultAppState,
    entry: PhotoEntry,
    selecting: Boolean,
    picked: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    var bitmap by remember(entry.blobId) { mutableStateOf<Bitmap?>(null) }
    var motion by remember(entry.blobId) { mutableStateOf<MotionPhoto.Motion?>(null) }
    LaunchedEffect(entry.blobId) {
        bitmap = state.thumbnail(entry.blobId)
        // 缩略图那一步已经在同一份字节上把形态算出来存进内存了，这里只是读一下 ——
        // 不会再解密一次，也不会再扫一遍字节。
        motion = state.motionOf(entry.blobId)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = { if (selecting) onToggle() else onOpen() },
                onLongClick = onLongPress,
            ),
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
                modifier = Modifier.align(Alignment.Center).size(20.dp),
            )
        }

        // 实况照片：右下角一个角标。影片段就在这份字节里，点开大图后可以放出来。
        if (motion != null) {
            MotionBadge(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
            )
        }

        if (selecting) {
            if (picked) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .border(1.5.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(10.dp)),
                )
            }
        }
    }
}

/**
 * 「实况」角标。
 *
 * 用文字而不是图标：这套图标集里没有一眼就能认出"实况"的形状，
 * 而中文用户在相册里认的就是这两个字。
 */
@Composable
fun MotionBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = "实况",
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 12.sp,
        )
    }
}

/** 网格摊平后的最小单元：这张图在哪条记录里。 */
private data class PhotoEntry(val blobId: String, val noteId: String)

/** 一次导入最多选多少张。图片记录本身的条数上限由 [NotePayload.MAX_IMAGES] 兜底。 */
private const val MAX_PICK = 30
