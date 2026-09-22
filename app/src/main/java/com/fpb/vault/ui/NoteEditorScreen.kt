package com.fpb.vault.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fpb.vault.model.ImageRef
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.model.SecretField
import com.fpb.vault.model.TodoItem
import com.fpb.vault.model.VideoRef
import com.fpb.vault.ui.components.FpbTopBar
import com.fpb.vault.ui.components.SectionHeader
import com.fpb.vault.ui.components.SuccessGreen
import com.fpb.vault.ui.components.VideoPlayGlyph
import com.fpb.vault.vault.ImagePipeline
import kotlinx.coroutines.launch

/**
 * 五类条目共用的编辑器。
 *
 * 差异只在中间的正文区（见 [bodyFor]），上半段（标题、标签、收藏）与
 * 下半段（保存、删除、未保存提示）完全一致 —— 否则几个页面会各自演化，
 * 最后出现"只有待办能收藏""密码条目忘了做脏数据提示"这类不一致。
 *
 * ## 图片为什么等到保存时才入库，视频却必须选完立刻入库
 *
 * 图片选了之后先只放内存（[pending]），按下保存才真正加密落盘。
 * 反过来写（选一张就立刻 putImage）会在用户放弃编辑时留下孤儿密文 ——
 * 存储占用一直涨，而界面上什么都看不到。
 *
 * **视频没有这个选择**：单条上限 2 GiB。"先拿在手里、点保存时才写"意味着这 2 GiB
 * 要么整份留在内存里（必然 OOM），要么先在磁盘上落一份明文（直接违背"明文绝不落盘"）。
 * 所以视频是**选完立刻流式入库**，这是被规模逼出来的，不是偷懒。
 *
 * 代价是"用户随后放弃编辑（或把刚选的那段又移掉）"会留下孤儿密文，
 * 所以本地会记下[本次导入过的视频][importedVideoIds]，在保存与放弃两条路上各自回收一次；
 * 更极端的残留（进程被杀）由设置页的手动清理兜底。
 */
@Composable
fun NoteEditorScreen(state: VaultAppState, route: Route.Editor) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val existing = remember(route.noteId) { route.noteId?.let { state.note(it) } }
    val type = existing?.payload?.type ?: route.type

    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember { mutableStateOf(existing?.payload?.body.orEmpty()) }
    var tags by remember { mutableStateOf(existing?.tags.orEmpty()) }
    var tagDraft by remember { mutableStateOf("") }
    var tagNotice by remember { mutableStateOf<String?>(null) }
    var favorite by remember { mutableStateOf(existing?.payload?.favorite ?: false) }
    var todos by remember { mutableStateOf(existing?.payload?.todos.orEmpty()) }
    var fields by remember { mutableStateOf(existing?.payload?.fields.orEmpty()) }
    var existingImages by remember { mutableStateOf(existing?.payload?.images.orEmpty()) }
    var existingVideos by remember { mutableStateOf(existing?.payload?.videos.orEmpty()) }
    var pending by remember { mutableStateOf<List<ImagePipeline.Prepared>>(emptyList()) }

    /**
     * 这次编辑里已经落盘的视频 blobId。
     *
     * 单独记一份，是因为它们**不在** `existing.payload.videos` 里，
     * 于是"保存时清理被移除的附件"那条逻辑扫不到它们：
     * 用户选了一段 800 MB 的视频、看了看又移掉、然后保存 —— 密文会永远留在库里。
     */
    var importedVideoIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var busy by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            // 只读原始字节（不做压缩），确认可解码后按原样交给保存流程
            val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ImagePipeline.prepare(context, uri)
            }
            busy = false
            // "太大"与"读不出来"必须分开说：前者换一张也没用，得先裁剪或缩小，
            // 而用户手里那张 40 MB 的全景图，提示"换一张试试"等于没说。
            when (outcome) {
                is ImagePipeline.Preparation.Ready -> {
                    pending = pending + outcome.prepared
                    touched = true
                }
                is ImagePipeline.Preparation.TooLarge -> state.setMessage(outcome.message)
                ImagePipeline.Preparation.Unreadable -> state.setMessage("这张图片读不出来，换一张试试")
            }
        }
    }

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            // 选完立刻流式入库（原因见类注释）。界面上表现为"选完之后这一步会转一会儿"，
            // 这是大文件必然的代价，所以按钮上要显示进度而不是让它看起来点不动。
            val outcome = state.importVideo { context.contentResolver.openInputStream(uri) }
            busy = false
            when (outcome) {
                is VideoImport.Ready -> {
                    existingVideos = existingVideos + outcome.video
                    importedVideoIds = importedVideoIds + outcome.video.blobId
                    touched = true
                    // 大文件是这条路上最需要反馈的地方：选定之后要转上一会儿，
                    // 转完必须有个"它进去了、有多大"的交代，否则用户不知道刚才那几秒
                    // 到底成功了没有（视频是先落盘、保存时才有记录的）。
                    state.setMessage(
                        "已添加 1 段视频 · ${ImagePipeline.describeSize(outcome.plainBytes)}",
                    )
                }
                is VideoImport.Rejected -> state.setMessage(outcome.message)
            }
        }
    }

    fun saveAndExit() {
        if (busy) return
        busy = true
        scope.launch {
            val refs = existingImages.toMutableList()
            for (image in pending) {
                val blobId = state.putImage(image.bytes)
                if (blobId == null) {
                    busy = false
                    return@launch
                }
                refs += ImageRef(blobId, image.width, image.height)
            }

            val payload = NotePayload(
                type = type,
                title = title,
                body = body,
                tags = tags,
                favorite = favorite,
                images = refs.take(NotePayload.MAX_IMAGES),
                videos = existingVideos.take(NotePayload.MAX_VIDEOS),
                todos = todos,
                fields = fields,
                createdAt = existing?.payload?.createdAt ?: 0L,
                updatedAt = 0L,
            )

            val saved = if (existing == null) {
                state.createNote(payload)
            } else {
                state.updateNote(existing.id, payload)
            }

            if (saved != null) {
                // 编辑过程中被移除的附件要在更新成功之后再删：
                // 反过来的话，更新失败就等于"文件没了、条目还在"。
                val kept = refs.map { it.blobId }.toSet()
                existing?.payload?.images
                    ?.map { it.blobId }
                    ?.filterNot { it in kept }
                    ?.forEach { stale -> state.session.deleteImage(stale) }

                // 视频多一条来源：本次编辑里刚导入、又被移掉的那些。
                // 它们不在 existing 里，所以上面那条清理扫不到（见 [importedVideoIds]）。
                //
                // 留存集合取的是**真正写进 payload 的那一份**（`take(MAX_VIDEOS)`），
                // 而不是 `existingVideos` 全体。差别在"一次选了 25 段视频"这种情形上：
                // 选图器一次最多 30 项，而单条记录只留 20 段，于是超出上限的那 5 段
                // 既没进记录、也不会被这条清理扫到 —— 它们会一直是孤儿，
                // 要等下一次解锁时的孤儿清扫才被收走。这里顺手就收干净。
                val keptVideos = existingVideos.take(NotePayload.MAX_VIDEOS)
                    .map { it.blobId }
                    .toSet()
                (existing?.payload?.videos?.map { it.blobId }.orEmpty() + importedVideoIds)
                    .filterNot { it in keptVideos }
                    .forEach { stale -> state.session.deleteImage(stale) }

                busy = false
                state.pop()
            } else {
                busy = false
            }
        }
    }

    fun leave() {
        if (touched) confirmDiscard = true else state.pop()
    }

    Column(Modifier.fillMaxSize()) {
        FpbTopBar(
            title = if (existing == null) "新建${type.label()}" else "编辑${type.label()}",
            onBack = { leave() },
            actions = {
                IconButton(onClick = { favorite = !favorite; touched = true }) {
                    Icon(
                        imageVector = if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (favorite) "取消收藏" else "收藏",
                        tint = if (favorite) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (existing != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除")
                    }
                }
                TextButton(onClick = { saveAndExit() }, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("保存")
                    }
                }
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; touched = true },
                label = { Text("标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = overLimitHint(title, NotePayload.MAX_TITLE_CHARS, "标题"),
            )

            bodyFor(
                type = type,
                body = body,
                onBodyChange = { body = it; touched = true },
                todos = todos,
                onTodosChange = { todos = it; touched = true },
                fields = fields,
                onFieldsChange = { fields = it; touched = true },
                state = state,
                existingImages = existingImages,
                pending = pending,
                onPickImage = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onRemoveExistingImage = { ref ->
                    existingImages = existingImages - ref
                    touched = true
                },
                onRemovePendingImage = { item ->
                    pending = pending - item
                    touched = true
                },
                onOpenImage = { blobId, all ->
                    state.push(Route.Viewer(all, all.indexOf(blobId).coerceAtLeast(0)))
                },
                videos = existingVideos,
                onPickVideo = {
                    pickVideo.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                },
                onRemoveVideo = { ref ->
                    existingVideos = existingVideos - ref
                    touched = true
                },
            )

            SectionHeader("标签")
            TagEditor(
                tags = tags,
                draft = tagDraft,
                onDraftChange = { tagDraft = it },
                // 判定与"要不要说话"都交给 `tagAddResult`（见 `ListAdd.kt`）。
                //
                // 原先这里是三个条件串成一个 if，紧跟一行**无条件**的 `tagDraft = ""`。
                // 后果：标签已经满 32 个时、或者打的这个词已经存在时，
                // 用户刚打好的字会被清掉、标签没多、界面**一句话不说** ——
                // 他只能猜是不是自己没按到，然后再按一次，还是这样。
                onAdd = {
                    val r = tagAddResult(tagDraft, tags)
                    r.value?.let {
                        tags = tags + it
                        touched = true
                    }
                    tagDraft = r.draftAfter
                    tagNotice = r.notice
                },
                // 移除一个之后，"满了"这句话就不再成立 —— 顺手清掉，
                // 免得用户删完一个还看着提示，以为得继续删。
                onRemove = { tags = tags - it; touched = true; tagNotice = null },
                notice = tagNotice,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { saveAndExit() },
                enabled = !busy,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("保存")
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃这次修改？") },
            text = { Text("离开后本次输入的内容不会保存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        // 本次导入的视频已经在库里了（见类注释），放弃编辑之后
                        // 没有任何记录指向它们，**必须在这里回收** —— 否则用户
                        // "选了一段 800 MB 的视频，看了看又退出"就会永久占着这份空间，
                        // 而界面上什么都看不到。
                        // 图片不需要这一步：它们在保存之前根本没落盘。
                        importedVideoIds.forEach { stale ->
                            runCatching { state.session.deleteImage(stale) }
                        }
                        state.pop()
                    },
                ) {
                    Text("放弃", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记录？") },
            text = {
                val hasImages = existing?.payload?.images?.isNotEmpty() == true
                val hasVideos = existing?.payload?.videos?.isNotEmpty() == true
                val attachments = when {
                    hasImages && hasVideos -> "照片与视频"
                    hasVideos -> "视频"
                    hasImages -> "图片"
                    else -> ""
                }
                Text(
                    if (attachments.isEmpty()) {
                        "删除后无法恢复。建议定期导出备份。"
                    } else {
                        "删除后它的${attachments}也会一并销毁，无法恢复。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        val id = existing?.id ?: return@TextButton
                        touched = false
                        state.deleteNote(id)
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

// ==================== 分类型正文区 ====================

/**
 * 「这个字段保存时会被截断」的提示。
 *
 * 落盘那一步（`NotePayload.normalized()` 里的截断）**必须**无条件裁剪：
 * 它挡的是从备份导入的旧数据、以及程序自己的 bug，不能指望界面把关。
 * 但界面必须让用户**先知道** —— 否则一个粘进去的长标题会在保存时被无声砍掉尾部，
 * 而用户看到的是"保存成功"。一句话的提示，换掉的是
 * "我明明写了那么多，怎么只剩下半截"。
 *
 * 判据用的是 `NotePayload.takeChars`，与真正落盘裁剪的是**同一个函数**。
 * 自己写 `value.length > limit` 的话，两者会在"截断点正好落在一个 emoji 中间"
 * 这一格上差一个 code unit，表现为"提示说没超限、保存时却少了一个字"。
 *
 * 不超限时返回 null，而不是一个渲染空内容的 lambda：后者会让
 * `OutlinedTextField` 为一行空白留出高度。
 */
private fun overLimitHint(value: String, limit: Int, what: String): (@Composable () -> Unit)? =
    if (NotePayload.takeChars(value, limit).length == value.length) {
        null
    } else {
        {
            Text(
                text = "${what}已超过 $limit 字上限，保存时只保留前 $limit 字",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

/**
 * 输入框下方的提示，最多并列两条：
 * 先是「这一次为什么没加进去」（满额 / 重复），再是「字符数超了多少」。
 *
 * 两条互不替代 —— 满额时用户要腾个位置出来，超长时用户要删字，是两件事。
 * 同时出现的情形很少（先攒满 32 个标签、再打一个超 32 字的），
 * 但真出现时两句话都得在，否则用户会以为提示在说他刚做的那件事。
 */
private fun addHint(
    notice: String?,
    value: String,
    limit: Int,
    what: String,
): (@Composable () -> Unit)? {
    val over = overLimitHint(value, limit, what)
    if (notice == null && over == null) return null
    return {
        Column {
            if (notice != null) {
                Text(
                    text = notice,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (over != null) {
                over()
            }
        }
    }
}

@Composable
private fun bodyFor(
    type: NoteType,
    body: String,
    onBodyChange: (String) -> Unit,
    todos: List<TodoItem>,
    onTodosChange: (List<TodoItem>) -> Unit,
    fields: List<SecretField>,
    onFieldsChange: (List<SecretField>) -> Unit,
    state: VaultAppState,
    existingImages: List<ImageRef>,
    pending: List<ImagePipeline.Prepared>,
    onPickImage: () -> Unit,
    onRemoveExistingImage: (ImageRef) -> Unit,
    onRemovePendingImage: (ImagePipeline.Prepared) -> Unit,
    onOpenImage: (String, List<String>) -> Unit,
    videos: List<VideoRef>,
    onPickVideo: () -> Unit,
    onRemoveVideo: (VideoRef) -> Unit,
) {
    when (type) {
        NoteType.TEXT -> OutlinedTextField(
            value = body,
            onValueChange = onBodyChange,
            label = { Text("正文") },
            minLines = 10,
            modifier = Modifier.fillMaxWidth(),
            supportingText = overLimitHint(body, NotePayload.MAX_BODY_CHARS, "正文"),
        )

        NoteType.IMAGE, NoteType.VIDEO -> {
            // 图片功能区与视频功能区**按类型只显示一个**：VIDEO 记录也能装图
            // （旧数据的 IMAGE 记录也可能带视频），但让两栏同时出现只会让人以为
            // "这条记录什么都能装"，而实际上网格、列表摘要、空壳判定都是按主类型走的。
            if (type == NoteType.IMAGE) {
                ImageStrip(
                    state = state,
                    existing = existingImages,
                    pending = pending,
                    onPick = onPickImage,
                    onRemoveExisting = onRemoveExistingImage,
                    onRemovePending = onRemovePendingImage,
                    onOpen = onOpenImage,
                )
            } else {
                VideoStrip(
                    state = state,
                    videos = videos,
                    onPick = onPickVideo,
                    onRemove = onRemoveVideo,
                    onOpen = { ref ->
                        val all = videos.map { it.blobId }
                        state.push(Route.Viewer(all, all.indexOf(ref.blobId).coerceAtLeast(0)))
                    },
                )
            }
            OutlinedTextField(
                value = body,
                onValueChange = onBodyChange,
                label = { Text("备注（可留空）") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                supportingText = overLimitHint(body, NotePayload.MAX_BODY_CHARS, "备注"),
            )
        }

        NoteType.CHECKLIST -> ChecklistEditor(todos = todos, onChange = onTodosChange)

        NoteType.CREDENTIAL -> CredentialEditor(fields = fields, onChange = onFieldsChange)
    }
}

/**
 * 视频功能区：已有的视频（可播放、可移除）＋ 一个添加入口。
 *
 * 与 [ImageStrip] 的差别不只是"多了个时长"：这里**没有 pending 区**。
 * 图片选完先在内存里等着保存，视频选完就立刻落盘了（原因见 [NoteEditorScreen] 的类注释），
 * 所以列表里出现的每一条都已经是库里的东西。
 */
@Composable
private fun VideoStrip(
    state: VaultAppState,
    videos: List<VideoRef>,
    onPick: () -> Unit,
    onRemove: (VideoRef) -> Unit,
    onOpen: (VideoRef) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        videos.forEach { ref ->
            VideoRow(
                state = state,
                video = ref,
                onOpen = { onOpen(ref) },
                onRemove = { onRemove(ref) },
            )
        }
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (videos.isEmpty()) "添加视频" else "再添加一个")
        }
        if (videos.size >= NotePayload.MAX_VIDEOS) {
            Text(
                text = "一条记录最多 ${NotePayload.MAX_VIDEOS} 段视频",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一行视频：左边封面、中间时长、右边移除。整行可点进全屏播放。 */
@Composable
private fun VideoRow(
    state: VaultAppState,
    video: VideoRef,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    var cover by remember(video.blobId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(video.blobId) {
        cover = state.videoThumbnail(video.blobId, video.durationMs)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val current = cover
            if (current != null) {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            VideoPlayGlyph(modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (video.durationMs > 0) {
                "时长 ${NotePayload.formatDuration(video.durationMs)}"
            } else {
                "视频"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "移除这段视频",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImageStrip(
    state: VaultAppState,
    existing: List<ImageRef>,
    pending: List<ImagePipeline.Prepared>,
    onPick: () -> Unit,
    onRemoveExisting: (ImageRef) -> Unit,
    onRemovePending: (ImagePipeline.Prepared) -> Unit,
    onOpen: (String, List<String>) -> Unit,
) {
    val existingIds = existing.map { it.blobId }
    val slots = existing.size + pending.size

    SectionHeader("图片（$slots / ${NotePayload.MAX_IMAGES}）")

    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(existing, key = { it.blobId }) { ref ->
            Box {
                var bitmap by remember(ref.blobId) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(ref.blobId) { bitmap = state.thumbnail(ref.blobId) }
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onOpen(ref.blobId, existingIds) },
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                IconButton(
                    onClick = { onRemoveExisting(ref) },
                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "移除这张图",
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        }

        // 尚未入库的图片：直接显示刚压缩好的内存字节，还没写盘
        items(pending.size) { index ->
            val item = pending[index]
            var bitmap by remember(item) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(item) {
                bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ImagePipeline.decode(item.bytes, ImagePipeline.THUMBNAIL_EDGE)
                }
            }
            Box {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                IconButton(
                    onClick = { onRemovePending(item) },
                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "移除这张图",
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onPick,
                enabled = slots < NotePayload.MAX_IMAGES,
                modifier = Modifier.size(104.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(4.dp))
                    Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    Text(
        text = "图片按原图直接加密存储，不压缩、不裁剪，占用与相册里的原图相当。" +
            "入库后它和相册里那张就没有关联了：之后在相册里删除或修改，都不影响库里的这份" +
            "（反过来也一样）。注意：原图自带的拍摄信息（如拍摄地点）也会一并保留在库里。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChecklistEditor(
    todos: List<TodoItem>,
    onChange: (List<TodoItem>) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val done = todos.count { it.done }

    // 回车键与"加号"这两个入口共用这一份。原先两处各抄了一遍同样三行 ——
    // 于是"待办满 500 条时刚打的字被丢掉"这件事在同一个界面里存在两份。
    val submit: () -> Unit = {
        val r = todoAddResult(draft, todos.size)
        r.value?.let { onChange(todos + TodoItem(it)) }
        draft = r.draftAfter
        notice = r.notice
    }

    SectionHeader("待办（$done / ${todos.size}）")

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("新增一项") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            supportingText = addHint(notice, draft, NotePayload.MAX_TODO_CHARS, "待办"),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = submit) {
            Icon(Icons.Outlined.Add, contentDescription = "添加")
        }
    }

    todos.forEachIndexed { index, item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = item.done,
                onCheckedChange = { checked ->
                    onChange(todos.toMutableList().also { it[index] = item.copy(done = checked) })
                },
            )
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.done) {
                    androidx.compose.ui.text.style.TextDecoration.LineThrough
                } else {
                    null
                },
                color = if (item.done) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange(todos - item) }) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "删除这一项",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun CredentialEditor(
    fields: List<SecretField>,
    onChange: (List<SecretField>) -> Unit,
) {
    var revealAll by remember { mutableStateOf(false) }

    SectionHeader("字段")

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "显示所有敏感字段",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = revealAll, onCheckedChange = { revealAll = it })
    }

    fields.forEachIndexed { index, field ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = field.label,
                    onValueChange = { label ->
                        onChange(
                            fields.toMutableList().also { it[index] = field.copy(label = label) },
                        )
                    },
                    label = { Text("字段名") },
                    singleLine = true,
                    supportingText = overLimitHint(field.label, NotePayload.MAX_FIELD_LABEL_CHARS, "字段名"),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { onChange(fields - field) }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除字段",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            OutlinedTextField(
                value = field.value,
                onValueChange = { value ->
                    onChange(
                        fields.toMutableList().also { it[index] = field.copy(value = value) },
                    )
                },
                label = { Text("内容") },
                singleLine = !field.sensitive,
                textStyle = if (field.sensitive) {
                    MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                visualTransformation = if (field.sensitive && !revealAll) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    // 敏感字段关掉输入法的联想与自动纠正：密码被输入法记住就白加密了
                    keyboardType = if (field.sensitive) {
                        KeyboardType.Password
                    } else {
                        KeyboardType.Text
                    },
                    autoCorrectEnabled = !field.sensitive,
                ),
                supportingText = overLimitHint(field.value, NotePayload.MAX_FIELD_VALUE_CHARS, "内容"),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "敏感（默认遮蔽显示）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = field.sensitive,
                    onCheckedChange = { sensitive ->
                        onChange(
                            fields.toMutableList().also { it[index] = field.copy(sensitive = sensitive) },
                        )
                    },
                )
            }
        }
    }

    OutlinedButton(
        onClick = {
            if (fields.size < NotePayload.MAX_FIELDS) onChange(fields + SecretField(label = "", value = ""))
        },
        enabled = fields.size < NotePayload.MAX_FIELDS,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("添加字段")
    }

    if (fields.none { it.sensitive }) {
        Text(
            text = "提示：密码类的值请打开「敏感」，这样它在列表与详情里都不会被直接显示。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TagEditor(
    tags: List<String>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    notice: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                label = { Text("加标签（最多 ${NotePayload.MAX_TAGS} 个）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                supportingText = addHint(notice, draft, NotePayload.MAX_TAG_CHARS, "标签"),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onAdd) {
                Icon(Icons.Outlined.Add, contentDescription = "添加标签")
            }
        }

        if (tags.isNotEmpty()) {
            val rows = remember(tags) { tags.chunked(4) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { tag ->
                            AssistChip(
                                onClick = { onRemove(tag) },
                                label = { Text("#$tag") },
                                trailingIcon = {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "移除标签",
                                        modifier = Modifier.size(14.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "标签会显示在列表卡片上，也是列表页的筛选维度。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
