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
import com.fpb.vault.ui.components.FpbTopBar
import com.fpb.vault.ui.components.SectionHeader
import com.fpb.vault.ui.components.SuccessGreen
import com.fpb.vault.vault.ImagePipeline
import kotlinx.coroutines.launch

/**
 * 四类条目共用的编辑器。
 *
 * 差异只在中间的正文区（见 [bodyFor]），上半段（标题、标签、收藏）与
 * 下半段（保存、删除、未保存提示）完全一致 —— 否则四个页面会各自演化，
 * 最后出现"只有待办能收藏""密码条目忘了做脏数据提示"这类不一致。
 *
 * ## 图片为什么等到保存时才入库
 *
 * 用户选了图之后先只放内存（[pending]），按下保存才真正加密落盘。
 * 反过来写（选一张就立刻 putImage）会在用户放弃编辑时留下孤儿密文 ——
 * 存储占用一直涨，而界面上什么都看不到。真正剩下的孤儿由设置页的手动清理兜底。
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
    var favorite by remember { mutableStateOf(existing?.payload?.favorite ?: false) }
    var todos by remember { mutableStateOf(existing?.payload?.todos.orEmpty()) }
    var fields by remember { mutableStateOf(existing?.payload?.fields.orEmpty()) }
    var existingImages by remember { mutableStateOf(existing?.payload?.images.orEmpty()) }
    var pending by remember { mutableStateOf<List<ImagePipeline.Prepared>>(emptyList()) }
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
                // 编辑过程中被移除的图片要在更新成功之后再删：
                // 反过来的话，更新失败就等于"照片没了、条目还在"。
                val kept = refs.map { it.blobId }.toSet()
                existing?.payload?.images
                    ?.map { it.blobId }
                    ?.filterNot { it in kept }
                    ?.forEach { stale -> state.session.deleteImage(stale) }
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
            )

            SectionHeader("标签")
            TagEditor(
                tags = tags,
                draft = tagDraft,
                onDraftChange = { tagDraft = it },
                onAdd = {
                    val clean = tagDraft.trim().take(NotePayload.MAX_TAG_CHARS)
                    if (clean.isNotEmpty() && clean !in tags && tags.size < NotePayload.MAX_TAGS) {
                        tags = tags + clean
                        touched = true
                    }
                    tagDraft = ""
                },
                onRemove = { tags = tags - it; touched = true },
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
                TextButton(onClick = { confirmDiscard = false; state.pop() }) {
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
                Text(
                    if (existing?.payload?.images?.isNotEmpty() == true) {
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
) {
    when (type) {
        NoteType.TEXT -> OutlinedTextField(
            value = body,
            onValueChange = onBodyChange,
            label = { Text("正文") },
            minLines = 10,
            modifier = Modifier.fillMaxWidth(),
        )

        NoteType.IMAGE -> {
            ImageStrip(
                state = state,
                existing = existingImages,
                pending = pending,
                onPick = onPickImage,
                onRemoveExisting = onRemoveExistingImage,
                onRemovePending = onRemovePendingImage,
                onOpen = onOpenImage,
            )
            OutlinedTextField(
                value = body,
                onValueChange = onBodyChange,
                label = { Text("备注（可留空）") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        NoteType.CHECKLIST -> ChecklistEditor(todos = todos, onChange = onTodosChange)

        NoteType.CREDENTIAL -> CredentialEditor(fields = fields, onChange = onFieldsChange)
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
    val done = todos.count { it.done }

    SectionHeader("待办（$done / ${todos.size}）")

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("新增一项") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    val text = draft.trim()
                    if (text.isNotEmpty() && todos.size < NotePayload.MAX_TODOS) {
                        onChange(todos + TodoItem(text))
                    }
                    draft = ""
                },
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = {
                val text = draft.trim()
                if (text.isNotEmpty() && todos.size < NotePayload.MAX_TODOS) {
                    onChange(todos + TodoItem(text))
                }
                draft = ""
            },
        ) {
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
