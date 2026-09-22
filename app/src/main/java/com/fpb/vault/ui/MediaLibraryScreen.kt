package com.fpb.vault.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.Videocam
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
import com.fpb.vault.model.ImageRef
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.model.VideoRef
import com.fpb.vault.ui.components.EmptyState
import com.fpb.vault.ui.components.FpbTopBar
import com.fpb.vault.ui.components.MotionBadge
import com.fpb.vault.ui.components.VideoBadge
import com.fpb.vault.ui.components.VideoPlayGlyph
import com.fpb.vault.vault.ImagePipeline
import com.fpb.vault.vault.MediaImportRoute
import com.fpb.vault.vault.MotionPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 媒体库：聚合当前库里全部照片与视频的网格。
 *
 * ## 设计取舍
 *
 * - **聚合视图，不是独立存储**：媒体库不另起一套数据，它把记录里的 `images`
 *   与 `videos` 摊平成同一个网格。真库里存了多少份，这里就能看到多少份；
 *   锁定时它和列表页一样整体消失。
 * - **入口只在真库显示**（见 HomeScreen）：诱饵库的使命是"看起来是个正常的空库"，
 *   一个空空如也的媒体库入口反而是画蛇添足的暗示。
 * - **导入即一条记录**：一次多选导入生成一条记录，在列表页里也能看到它、给它加标签。
 *   这条记录**不落标题** —— 列表里看到的"照片 · 9月15日 20:44"是显示时按创建时间派生的
 *   （见 [com.fpb.vault.vault.PhotoRecord]），这样"标题为空"就恒等于"用户没自己起过名字"，
 *   媒体被删光时它才敢连记录一起删掉。
 * - **删除走多选**：长按任意一格进入选择模式，之后点击即勾选，
 *   顶栏给出"已选 N 项 / 全选 / 删除"。单份删除不再单独开一个确认框 ——
 *   同一个动作有两条路径，迟早会只有一条被维护到。
 * - **照片与视频混排在同一张网格里**，按记录更新时间降序、记录内保持导入顺序。
 *   分成两个网格或两个入口看起来更"整齐"，但相册的心智模型本来就是按时间看全部，
 *   而"这一格能不能播"由角标回答就够了。
 */
@Composable
fun MediaLibraryScreen(state: VaultAppState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 网格按记录更新时间降序摊平；同一条记录内先照片后视频，与详情页的顺序一致
    val entries = remember(state.notes) {
        state.notes
            .filter {
                (it.type == NoteType.IMAGE || it.type == NoteType.VIDEO) &&
                    (it.payload.images.isNotEmpty() || it.payload.videos.isNotEmpty())
            }
            .sortedByDescending { it.updatedAt }
            .flatMap { note ->
                note.payload.images.map { MediaEntry(it.blobId, note.id, video = null) } +
                    note.payload.videos.map { MediaEntry(it.blobId, note.id, video = it) }
            }
    }

    var busy by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 媒体被删光或换了之后，选中集合里会留下已经不存在的 blobId。
    // 每次网格内容变化就收敛一次，否则顶栏会显示"已选 3 项"而屏幕上只剩 2 格能勾。
    // effect 的 key 只挂 entries：下面这个 effect 体内会改 selected，
    // 若把它也当 key，就会像 AppRoot 那个 Snackbar 一样把自己的协程取消掉。
    LaunchedEffect(entries) {
        val alive = entries.map { it.blobId }.toSet()
        selected = selected intersect alive
        if (selected.isEmpty()) selecting = false
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_PICK),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val preparedImages = mutableListOf<ImagePipeline.Prepared>()
            val videoRefs = mutableListOf<VideoRef>()
            // 这批视频的**明文**字节数。磁盘上存的是密文，而用户问的是
            // "我放进来多大一段"，两个数只差固定开销，报明文才和他的认知对得上。
            // 这也是 `VideoImport.Ready.plainBytes` 一直存在的理由（见 BlobSink 的注释）。
            var videoPlainBytes = 0L
            // 图片超限的文案由 ImagePipeline 给出（上限值只应有唯一出处），
            // 这里只留最后一次的那句 —— 一批里超限的原因必然相同。
            var tooLargeMessage: String? = null
            // 逐个失败而不是整批失败：一次选了 9 个文件，其中一个不认识，
            // 不该让另外 8 个也一起丢掉。
            val failures = mutableListOf<String>()

            uris.forEach { uri ->
                val kind = classify(context, uri)
                if (kind == NoteType.VIDEO) {
                    when (val outcome = state.importVideo { context.contentResolver.openInputStream(uri) }) {
                        is VideoImport.Ready -> {
                            videoRefs += outcome.video
                            videoPlainBytes += outcome.plainBytes
                        }

                        is VideoImport.Rejected -> failures += outcome.message
                    }
                    return@forEach
                }

                // 图片，以及"MIME 说不清"的那些（少数第三方提供方不给 type）。
                // **先真的试一次图片**：下面那个判定全部基于这次尝试的结果
                // （规则本身在 MediaImportRoute 里，可被单测逐条钉住）。
                val image = withContext(Dispatchers.IO) { ImagePipeline.prepare(context, uri) }
                when (val plan = MediaImportRoute.plan(kind, image)) {
                    is MediaImportRoute.Plan.UseImage -> preparedImages += plan.prepared
                    is MediaImportRoute.Plan.ImageTooLarge -> tooLargeMessage = plan.message

                    // 图片这条路走不通就再按视频试一次 —— 否则一个正常的视频会被报成
                    // "图片读不出来"，而用户拿它毫无办法。
                    MediaImportRoute.Plan.TryVideo -> {
                        val retry = state.importVideo {
                            context.contentResolver.openInputStream(uri)
                        }
                        when (retry) {
                            is VideoImport.Ready -> {
                                videoRefs += retry.video
                                videoPlainBytes += retry.plainBytes
                            }

                            is VideoImport.Rejected -> failures += retry.message
                        }
                    }
                }
            }

            // 真正落盘在解码与探测都通过之后集中做，避免"先写一半再发现这批全坏"。
            val writtenImages = preparedImages.mapNotNull { prepared ->
                state.putImage(prepared.bytes)?.let { blobId ->
                    ImageRef(blobId, prepared.width, prepared.height)
                }
            }

            if (writtenImages.isEmpty() && videoRefs.isEmpty()) {
                busy = false
                // 全都是"过大"时必须说"过大"，否则用户会一直换文件重试同一个必然失败的操作
                state.setMessage(
                    failures.firstOrNull() ?: tooLargeMessage ?: "选中的内容都读不出来，换一批试试",
                )
                return@launch
            }

            val now = System.currentTimeMillis()
            // 主类型按"这批里有没有照片"决定：混选时记为图片记录（列表页的图标、
            // 摘要与空壳判定都按主类型走），但它名下的两种媒体都会如实存下来。
            val payload = NotePayload(
                type = if (writtenImages.isEmpty()) NoteType.VIDEO else NoteType.IMAGE,
                // 标题刻意留空：显示时派生，见类注释
                title = "",
                createdAt = now,
                updatedAt = now,
                images = writtenImages,
                videos = videoRefs,
            )
            val saved = state.createNote(payload)
            busy = false
            if (saved == null) {
                // 记录没建成，那刚刚写进去的附件就成了没人引用的孤儿，
                // 当场回收掉 —— 否则它们会一直占着空间，而界面上什么都看不到。
                writtenImages.forEach { runCatching { state.session.deleteImage(it.blobId) } }
                videoRefs.forEach { runCatching { state.session.deleteImage(it.blobId) } }
                return@launch
            }

            state.setMessage(
                importSummary(
                    images = writtenImages.size,
                    videos = videoRefs.size,
                    videoPlainBytes = videoPlainBytes,
                    tooLarge = tooLargeMessage != null,
                    failures = failures,
                ),
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                FpbTopBar(
                    title = "已选 ${selected.size} 项",
                    subtitle = "共 ${entries.size} 项",
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
                                contentDescription = "删除选中的内容",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
            } else {
                FpbTopBar(
                    title = "照片与视频",
                    subtitle = "原图原片加密存储 · ${entries.size} 项",
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
                        title = "还没有照片或视频",
                        description = "点右下角的按钮从相册导入。内容按原图、原片加密存储，" +
                            "只有解锁后才能看到。导入后会成为库里独立的一份，" +
                            "之后从相册删除原文件不影响这里。" +
                            "长按可以一次选多项一起删。",
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
                        MediaCell(
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

        // 选择模式下把导入按钮撤掉：正忙着挑要删的内容，此时误触弹出的选择器最容易造成混乱
        if (!selecting) {
            ExtendedFloatingActionButton(
                onClick = {
                    if (!busy) {
                        pickMedia.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                            ),
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
                text = { Text(if (busy) "正在保存…" else "添加照片或视频") },
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
            title = { Text("删除选中的 ${picked.size} 项？") },
            text = {
                Text(
                    "删除后无法恢复。如果某条记录的照片与视频被全部删掉，" +
                        "而它上面没有你写的标题、备注或标签，这条记录也会一并删除。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        state.removeMedia(picked.map { MediaRef(it.noteId, it.blobId) })
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
 * 网格里的一格。点击看图/看视频，长按进入多选；已经在多选模式时点击即勾选。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    state: VaultAppState,
    entry: MediaEntry,
    selecting: Boolean,
    picked: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    var bitmap by remember(entry.blobId) { mutableStateOf<Bitmap?>(null) }
    var motion by remember(entry.blobId) { mutableStateOf<MotionPhoto.Motion?>(null) }
    LaunchedEffect(entry.blobId, entry.video) {
        val video = entry.video
        if (video != null) {
            // 视频封面走 MediaMetadataRetriever，与图片缩略图完全是两条数据路径
            bitmap = state.videoThumbnail(entry.blobId, video.durationMs)
        } else {
            bitmap = state.thumbnail(entry.blobId)
            // 缩略图那一步已经在同一份字节上把形态算出来存进内存了，这里只是读一下 ——
            // 不会再解密一次，也不会再扫一遍字节。
            motion = state.motionOf(entry.blobId)
        }
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
                imageVector = if (entry.video != null) Icons.Outlined.Videocam else Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(20.dp),
            )
        }

        // 视频格：中央一个播放三角（"这一格能播"要在快速滚动里一眼看得出来）。
        // 封面还没解出来时不画 —— 那时候中央已经有 Videocam 图标在占位了，
        // 两个叠在一起只会互相盖住。
        if (entry.video != null && current != null) {
            VideoPlayGlyph(modifier = Modifier.align(Alignment.Center))
        }

        // 实况照片：左下角一个角标。影片段就在这份字节里，点开大图后可以放出来。
        if (motion != null) {
            MotionBadge(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
            )
        }

        // 视频：时长角标。取不到时长时只画播放三角，不显示 0:00。
        entry.video?.let { video ->
            VideoBadge(
                durationMs = video.durationMs,
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

/** 网格摊平后的最小单元：这份媒体在哪条记录里，以及它是不是视频。 */
private data class MediaEntry(val blobId: String, val noteId: String, val video: VideoRef?)

/**
 * 判断用户选中的这一项该按图片还是按视频处理。
 *
 * 依据是内容提供方给的 MIME。**取不到 MIME 时返回 null**（而不是猜一个），
 * 让调用方有机会"先按图片试、不行再按视频试" —— 见 [MediaLibraryScreen] 里那处分支。
 */
private fun classify(context: Context, uri: Uri): NoteType? =
    when (val mime = context.contentResolver.getType(uri)) {
        null -> null
        else -> when {
            mime.startsWith("video/") -> NoteType.VIDEO
            mime.startsWith("image/") -> NoteType.IMAGE
            else -> null
        }
    }

/**
 * 导入结果的一句话交代。
 *
 * 每一类被跳过的原因都要说出来：用户数一数"我选了 9 个，怎么只有 6 个"时，
 * 唯一能把这件事解释清楚的就是这句话。
 */
private fun importSummary(
    images: Int,
    videos: Int,
    /** 这批视频的明文字节数。0 或没有视频时这一项只是一句"已保存 N 段视频"。 */
    videoPlainBytes: Long,
    tooLarge: Boolean,
    failures: List<String>,
): String = buildString {
    val saved = buildList {
        if (images > 0) add("$images 张照片")
        // 视频带上体量：一段 2 GiB 的视频在"已保存 1 段视频"里完全看不出轻重，
        // 而用户紧接着最可能问的就是"它多大、我的空间还够不够"。
        if (videos > 0) {
            val size = ImagePipeline.describeSize(videoPlainBytes)
            add("$videos 段视频（$size）")
        }
    }.joinToString("、")
    append(if (saved.isEmpty()) "没有导入任何内容" else "已保存 $saved")
    if (tooLarge) append("，有照片因超过体积上限被跳过")
    if (failures.isNotEmpty()) {
        // 失败原因只报第一条：连着念多条提示等于一条都看不清，
        // 而失败的原因多半是同一个（体积超限、格式不认识）。
        append("，${failures.size} 个被跳过（${failures.first()}）")
    }
}

/** 一次导入最多选多少项。图片记录本身的条数上限由 [NotePayload.MAX_IMAGES] 兜底。 */
private const val MAX_PICK = 30
