package com.fpb.vault.model

/**
 * 条目类型。
 *
 * 序列化时**不使用 ordinal**，而是用显式代码（见 `NoteCodec`）。
 * 理由：将来若在枚举中间插入一个新类型，ordinal 会让所有旧数据的类型整体错位，
 * 表现为"密码条目突然变成待办清单"这类无法从数据本身察觉的故障。
 */
enum class NoteType {
    TEXT,
    IMAGE,
    CHECKLIST,
    CREDENTIAL,
    VIDEO,
}

/** 指向一条已加密落盘的图片。宽高只是为了列表页占位，不是解密后的尺寸来源。 */
data class ImageRef(
    val blobId: String,
    val width: Int,
    val height: Int,
) {
    init {
        require(width >= 0 && height >= 0) { "图片尺寸不能为负" }
    }
}

/**
 * 指向一段已加密落盘的视频。
 *
 * ## 为什么不复用 [ImageRef]
 *
 * 三个字段里只有 blobId 是共通的。[width] / [height] 看着也能共用，
 * 但视频的宽高**必须**是"按旋转角摆正之后"的显示尺寸 —— 手机竖拍的视频
 * 常常是 1920×1080 的帧加上 90° 旋转标记，直接拿帧宽高去占位会得到一个横躺的格子。
 * [durationMs] 更是只有视频才有，它对网格上的时长角标、详情页的展示都是必需的。
 *
 * 塞进 [ImageRef] 的后果是"图片"这个概念下的每一个消费点都要先判一下它到底是不是视频，
 * 而漏判一处就会表现为"把视频当图片解码 → 一片空白"。
 */
data class VideoRef(
    val blobId: String,
    /** 摆正后的显示宽度（已考虑旋转角）。 */
    val width: Int,
    /** 摆正后的显示高度（已考虑旋转角）。 */
    val height: Int,
    /** 时长（毫秒）。取不到时为 0，界面按"未知"显示，不假装是 0 秒。 */
    val durationMs: Long = 0L,
) {
    init {
        require(blobId.isNotEmpty()) { "视频引用的 blobId 不能为空" }
        require(width >= 0 && height >= 0) { "视频尺寸不能为负" }
        require(durationMs >= 0) { "视频时长不能为负" }
    }
}

data class TodoItem(
    val text: String,
    val done: Boolean = false,
)

/**
 * 结构化字段（账号密码条目用）。
 *
 * [sensitive] 为 true 时界面上默认以圆点显示，且复制走剪贴板自动清空通道。
 */
data class SecretField(
    val label: String,
    val value: String,
    val sensitive: Boolean = false,
)

/**
 * 一条笔记的**明文**内容。
 *
 * 这是整个应用里唯一持有明文的数据结构，它有两个必须遵守的约束：
 * 1. **永远不直接落盘** —— 必须先经 [com.fpb.vault.codec.NoteCodec] 编码、
 *    再用 DEK 做 AES-GCM 加密
 * 2. **生命周期不超出解锁会话** —— [com.fpb.vault.session.VaultSession] 锁定时
 *    整个索引会被丢弃
 */
data class NotePayload(
    val type: NoteType,
    val title: String = "",
    val body: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val favorite: Boolean = false,
    val images: List<ImageRef> = emptyList(),
    val videos: List<VideoRef> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val fields: List<SecretField> = emptyList(),
) {

    /**
     * 列表页摘要。**全部在内存中计算** —— 锁定时没有任何途径能算出这个字符串，
     * 因为摘要依赖的正文本身就是密文。
     */
    fun summary(limit: Int = SUMMARY_CHARS): String {
        val source = when (type) {
            NoteType.CHECKLIST -> buildString {
                append("已完成 ${todos.count { it.done }} / ${todos.size}")
                todos.filterNot { it.done }.take(2).forEach { append(" · ").append(it.text) }
            }

            NoteType.CREDENTIAL -> fields.joinToString(" · ") { f ->
                if (f.sensitive) "${f.label} ••••" else "${f.label} ${f.value}"
            }

            NoteType.IMAGE, NoteType.VIDEO -> mediaSummary(this)

            NoteType.TEXT -> body
        }
        return flatten(source, limit)
    }

    /**
     * 供内存搜索使用的可检索文本（已转小写）。
     *
     * 搜索**只可能**发生在解锁后：解密前拿到的是一段密文，
     * 而密文无法被模糊匹配。这是全字段加密换来的必然代价，也是它安全的来源。
     */
    fun searchableText(): String = buildString {
        append(title.lowercase()).append('\n')
        // 图库导入的图片/视频记录标题是**显示时按创建时间派生**的、不落盘
        // （原因见 com.fpb.vault.vault.PhotoRecord），所以这里单独补上那个词 ——
        // 否则"搜照片/视频"就再也搜不到那些记录了，而这正是用户会用的搜法。
        if (title.isBlank() && type == NoteType.IMAGE) {
            append(IMAGE_TITLE_WORD.lowercase()).append('\n')
        }
        if (title.isBlank() && type == NoteType.VIDEO) {
            append(VIDEO_TITLE_WORD.lowercase()).append('\n')
        }
        // 一条记录里可能既有图又有视频（编辑器里可以两样都加）。这时代表词按**内容**补，
        // 而不是按类型 —— 否则搜"视频"只能搜到视频记录，搜不到"图片记录里带着的那段视频"。
        if (videos.isNotEmpty() && type != NoteType.VIDEO) {
            append(VIDEO_TITLE_WORD.lowercase()).append('\n')
        }
        if (images.isNotEmpty() && type != NoteType.IMAGE) {
            append(IMAGE_TITLE_WORD.lowercase()).append('\n')
        }
        append(body.lowercase()).append('\n')
        tags.forEach { append(it.lowercase()).append(' ') }
        todos.forEach { append(it.text.lowercase()).append(' ') }
        fields.forEach { append(it.label.lowercase()).append(' ').append(it.value.lowercase()).append(' ') }
    }

    /**
     * 收敛到可控范围后再加密。
     *
     * 这一步不是为了"好看"，而是防御性约束：单条内容若不受限，
     * 一次误操作（例如把整个相册目录粘进正文）会生成几十 MB 的密文，
     * 而解密是**在解锁时一次性全量进行**的，届时会直接拖垮启动。
     */
    fun normalized(): NotePayload = copy(
        title = takeChars(title.trim(), MAX_TITLE_CHARS),
        body = takeChars(body, MAX_BODY_CHARS),
        // 顺序不能颠倒：必须先截断到最终落盘的样子，再判重。
        // 反过来写的话，两个仅在第 MAX_TAG_CHARS 个字符之后不同的标签会被判为"不同"
        // 而双双留下，截断之后却变得一模一样 —— 界面上于是出现两个完全相同的标签，
        // 标签计数也会被算成 2。
        tags = tags
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { takeChars(it, MAX_TAG_CHARS) }
            .distinct()
            .take(MAX_TAGS),
        todos = todos
            .take(MAX_TODOS)
            .map { it.copy(text = takeChars(it.text.trim(), MAX_TODO_CHARS)) }
            .filter { it.text.isNotEmpty() },
        fields = fields
            .take(MAX_FIELDS)
            .map {
                it.copy(
                    label = takeChars(it.label.trim(), MAX_FIELD_LABEL_CHARS),
                    value = takeChars(it.value, MAX_FIELD_VALUE_CHARS),
                )
            }
            .filter { it.label.isNotEmpty() },
        images = images.take(MAX_IMAGES),
        videos = videos.take(MAX_VIDEOS),
    )

    companion object {
        const val SUMMARY_CHARS = 90

        /**
         * 图片记录的检索词，同时是派生标题的前缀。
         *
         * 放在模型层而不是 `PhotoRecord` 里，是为了不让 `model` 反过来依赖 `vault` ——
         * 而 [searchableText] 需要它。
         */
        const val IMAGE_TITLE_WORD = "照片"

        /** 视频记录的检索词，与 [IMAGE_TITLE_WORD] 同理（见 [searchableText]）。 */
        const val VIDEO_TITLE_WORD = "视频"

        const val MAX_TITLE_CHARS = 200
        const val MAX_BODY_CHARS = 200_000
        const val MAX_TAGS = 32
        const val MAX_TAG_CHARS = 32
        const val MAX_TODOS = 500
        const val MAX_TODO_CHARS = 500
        const val MAX_FIELDS = 64
        const val MAX_FIELD_LABEL_CHARS = 64
        const val MAX_FIELD_VALUE_CHARS = 8_192
        const val MAX_IMAGES = 100

        /**
         * 单条记录里的视频条数上限。
         *
         * 比 [MAX_IMAGES] 小得多，因为两边的代价不是一个量级：图片一条几百 KB 到几 MB，
         * 视频一条上限 2 GiB。真放开到 100 条，一条记录就能塞满 200 GiB ——
         * 而"一条记录可能占多大"的上界，是别处**默认依赖**的一个量：
         * 备份恢复的总量预算就直接由它推出（见
         * [com.fpb.vault.vault.BackupManager.MAX_TOTAL_BYTES]）。
         *
         * 所以改这个数会让那个预算跟着变 —— 这正是它必须被依赖、而不是"两处
         * 各自写一个数字"的原因：各写一份时，改了这边忘那边，报出来的失败
         * 会长得像"备份包损坏"，而真正的问题是恢复预算容不下应用自己允许存的量。
         */
        const val MAX_VIDEOS = 20

        /**
         * 媒体记录的摘要（`2 张图片` / `1 段视频 · 0:42` / `2 张图片 + 1 段视频 · 1:05`）。
         *
         * 只有单一媒体且没有附加正文时，产出与旧版本**逐字符相同**（`1 张图片`）——
         * 列表页的摘要会被单测钉住，也会被用户的截图对照，改了没有收益却会制造噪音。
         */
        private fun mediaSummary(payload: NotePayload): String {
            val media = buildList {
                if (payload.images.isNotEmpty()) add("${payload.images.size} 张图片")
                if (payload.videos.isNotEmpty()) add("${payload.videos.size} 段视频")
            }.joinToString(" + ")

            // 时长只在真有视频且取得到时才显示。取不到（0）时**不假装是 0 秒** ——
            // 那会让用户以为这段视频坏了。
            val totalMs = payload.videos.sumOf { it.durationMs }
            val head = when {
                media.isEmpty() -> ""
                payload.videos.isNotEmpty() && totalMs > 0 -> "$media · ${formatDuration(totalMs)}"
                else -> media
            }

            return when {
                head.isEmpty() -> payload.body
                payload.body.isBlank() -> head
                else -> "$head · ${payload.body}"
            }
        }

        /**
         * `0:42` / `12:05` / `1:02:33`。
         *
         * 不能偷懒写成"总秒数 ÷ 60"：那对 1 小时以上的素材会给出 `62:33` 这种读不出
         * 到底是"62 分"还是"62 秒"的数字。
         */
        internal fun formatDuration(millis: Long): String {
            val totalSeconds = (millis / 1000).coerceAtLeast(0)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }

        /**
         * 按长度截断，但**不切断一个字符**。
         *
         * `take(n)` 数的是 UTF-16 code unit，不是字符：一个 emoji 占两个 code unit，
         * 截断点正好落在它中间时，留下的那个孤立高位代理会被 UTF-8 编码成 `?`
         * —— 用户粘的那串 emoji 在落盘时静默少一个、变成一个问号；
         * 而在摘要这种只显示不落盘的地方，它会显示成一个"�"方块。
         * 两种后果都不报错，所以只能在截断这一步挡掉。
         *
         * 界面侧判断"会不会被截断"也要用这个函数，别自己写 `length > MAX`：
         * 两个判据差一个 code unit 的话，会出现"提示说没超限、保存时却少了一个字"。
         */
        internal fun takeChars(value: String, limit: Int): String {
            if (value.length <= limit) return value
            val end = if (Character.isHighSurrogate(value[limit - 1])) limit - 1 else limit
            return value.substring(0, end)
        }

        /** 把多行文本压成一行，并截断到 [limit]。 */
        private fun flatten(source: String, limit: Int): String {
            val flat = source.replace(WHITESPACE, " ").trim()
            if (flat.isEmpty()) return ""
            val clipped = takeChars(flat, limit)
            return if (clipped.length == flat.length) clipped else clipped.trimEnd() + "…"
        }

        private val WHITESPACE = Regex("\\s+")
    }
}

/** 带 id 的笔记，列表与搜索的返回单元。 */
data class VaultNote(
    val id: String,
    val payload: NotePayload,
) {
    val type: NoteType get() = payload.type
    val title: String get() = payload.title
    val updatedAt: Long get() = payload.updatedAt
    val tags: List<String> get() = payload.tags
}
