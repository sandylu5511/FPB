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

            NoteType.IMAGE -> if (images.isEmpty()) {
                body
            } else {
                "${images.size} 张图片" + if (body.isBlank()) "" else " · $body"
            }

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
        // 图库导入的图片记录标题是**显示时按创建时间派生**的、不落盘
        // （原因见 com.fpb.vault.vault.PhotoRecord），所以这里单独补上那个词 ——
        // 否则"搜照片"就再也搜不到那些记录了，而这正是用户会用的搜法。
        if (type == NoteType.IMAGE && title.isBlank()) {
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
        title = title.trim().take(MAX_TITLE_CHARS),
        body = body.take(MAX_BODY_CHARS),
        // 顺序不能颠倒：必须先截断到最终落盘的样子，再判重。
        // 反过来写的话，两个仅在第 MAX_TAG_CHARS 个字符之后不同的标签会被判为"不同"
        // 而双双留下，截断之后却变得一模一样 —— 界面上于是出现两个完全相同的标签，
        // 标签计数也会被算成 2。
        tags = tags
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.take(MAX_TAG_CHARS) }
            .distinct()
            .take(MAX_TAGS),
        todos = todos
            .take(MAX_TODOS)
            .map { it.copy(text = it.text.trim().take(MAX_TODO_CHARS)) }
            .filter { it.text.isNotEmpty() },
        fields = fields
            .take(MAX_FIELDS)
            .map {
                it.copy(
                    label = it.label.trim().take(MAX_FIELD_LABEL_CHARS),
                    value = it.value.take(MAX_FIELD_VALUE_CHARS),
                )
            }
            .filter { it.label.isNotEmpty() },
        images = images.take(MAX_IMAGES),
    )

    companion object {
        const val SUMMARY_CHARS = 90

        /**
         * 照片记录的检索词，同时是派生标题的前缀。
         *
         * 放在模型层而不是 `PhotoRecord` 里，是为了不让 `model` 反过来依赖 `vault` ——
         * 而 [searchableText] 需要它。
         */
        const val IMAGE_TITLE_WORD = "照片"

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

        /** 把多行文本压成一行，并截断到 [limit]。 */
        private fun flatten(source: String, limit: Int): String {
            val flat = source.replace(WHITESPACE, " ").trim()
            if (flat.isEmpty()) return ""
            return if (flat.length <= limit) flat else flat.take(limit).trimEnd() + "…"
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
