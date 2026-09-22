package com.fpb.vault.ui

import com.fpb.vault.model.NotePayload

/**
 * 往"会满额"的列表里追加一项之后，界面该做什么。
 *
 * 为什么要把它单独抽出来：这三件事（要不要追加 / 草稿框该剩什么 / 要不要说话）
 * 原先散在界面代码的 if 里，其中最要紧的一条 —— **满额时草稿不能被清空** ——
 * 没有任何东西盯着。抽成纯函数之后，它可以被单测钉住。
 */
internal enum class AddOutcome {
    /** 真的加进去了。 */
    Added,

    /** 草稿是空的（或只有空白）。什么都没发生，也不必说话。 */
    Empty,

    /** 这一项已经在列表里了，不重复加。 */
    Duplicate,

    /** 列表已经满了，加不进去。 */
    Full,
}

/**
 * @param value 非 null = 应该把这一项追加进列表；null = 不追加。
 * @param draftAfter 按下"添加"之后，草稿框里应该剩下什么。
 *   **满额时是原样保留** —— 用户刚打好的那行字不能被悄悄丢掉。
 * @param notice 要在输入框下方显示给用户的话；null = 不必说话。
 */
internal data class AddResult(
    val outcome: AddOutcome,
    val value: String?,
    val draftAfter: String,
    val notice: String?,
)

/**
 * 加一个标签。
 *
 * 判定顺序是刻意的：**先看重复，再看满额**。
 * 两句提示的"下一步该做什么"不一样 —— 重复是"这个词已经在里面了"，
 * 满额是"你得先腾个位置"。要是反过来，一个已经存在的标签又碰上列表满，
 * 用户会被告知"满了"，而去移除一个已有的标签 —— 其实他打的那个词本来就有。
 */
internal fun tagAddResult(
    raw: String,
    tags: List<String>,
    maxCount: Int = NotePayload.MAX_TAGS,
    maxChars: Int = NotePayload.MAX_TAG_CHARS,
): AddResult {
    // 必须用 `NotePayload.takeChars`，不能用 `take`：
    // `take(n)` 数的是 UTF-16 code unit 而不是字符，一个 emoji 占两个，
    // 截断点正好落在它中间时会留下一个**孤立高位代理** —— 存下来是个「?」。
    // 落盘那一步（`NotePayload.normalized`）用的也是 `takeChars`，
    // 两边不一致的话，界面上看着没问题、存进去的却是另一个字。
    val clean = NotePayload.takeChars(raw.trim(), maxChars)
    if (clean.isEmpty()) {
        return AddResult(AddOutcome.Empty, null, "", null)
    }
    if (clean in tags) {
        return AddResult(AddOutcome.Duplicate, null, "", "「$clean」已经在标签里了")
    }
    if (tags.size >= maxCount) {
        return AddResult(
            outcome = AddOutcome.Full,
            value = null,
            draftAfter = raw,
            notice = "标签最多 $maxCount 个，先移除一个再加",
        )
    }
    return AddResult(AddOutcome.Added, clean, "", null)
}

/**
 * 加一条待办。
 *
 * 与标签不同的是**这里不做字符数裁剪** —— 待办文本的超长由输入框下方的实时提示
 * 告知，落盘那一步统一裁（与正文、标题同款"有声裁剪"）。保持这个语义不变，
 * 这里只解决"满额时丢字且不说"这一件事。
 */
internal fun todoAddResult(
    raw: String,
    count: Int,
    maxCount: Int = NotePayload.MAX_TODOS,
): AddResult {
    val text = raw.trim()
    if (text.isEmpty()) {
        return AddResult(AddOutcome.Empty, null, "", null)
    }
    if (count >= maxCount) {
        return AddResult(
            outcome = AddOutcome.Full,
            value = null,
            draftAfter = raw,
            notice = "一条记录最多 $maxCount 条待办",
        )
    }
    return AddResult(AddOutcome.Added, text, "", null)
}
