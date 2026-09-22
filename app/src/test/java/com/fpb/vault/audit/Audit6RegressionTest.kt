package com.fpb.vault.audit

import com.fpb.vault.model.NotePayload
import com.fpb.vault.ui.AddOutcome
import com.fpb.vault.ui.tagAddResult
import com.fpb.vault.ui.todoAddResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第九轮审核的回归用例（2026-09-18，第 14 条）。
 *
 * 这一轮修的是一件很小、但性质很重的事：**列表满了之后，用户刚打的那行字被清空，
 * 界面一句话不说。**
 *
 * 它为什么值得单独立一个文件：它不属于"算错了"，而属于"什么都没发生，
 * 但用户以为发生了"。用户打了标签、按了加号、草稿框空了 ——
 * 唯一的解释只能是"加进去了"，于是他不会再打第二遍。等到下次打开这条记录，
 * 才发现那个标签从来就不在。**没有任何报错、没有任何异常、日志干净** ——
 * 这类缺陷不会被任何"跑一遍看看有没有崩"的测试发现。
 *
 * 所以这个文件的断言全部盯着**用户能看见的那三件事**：
 * 草稿留没留、有没有话、加进去的是不是他打的那个词。
 */
class Audit6RegressionTest {

    // ==================== 1. 标签：满额时不能丢字 ====================

    /**
     * 缺陷：`onAdd` 的最后一行是无条件的 `tagDraft = ""`，
     * 而"加进去"这个动作被包在 `if (… && tags.size < MAX_TAGS)` 里。
     *
     * 后果：标签已经 32 个时，用户打一个字、按加号，**字没了、标签没多、没有提示**。
     * 他只能猜是不是自己没按到；再按一次，还是这样。
     * 与"字符数超限"不同 —— 那一档至少输入框下方会说"保存时只保留前 32 字"。
     */
    @Test
    fun `标签满额时草稿必须原样留住并给出一句话`() {
        val tags = List(NotePayload.MAX_TAGS) { "t$it" }

        val r = tagAddResult("新标签", tags)

        assertEquals("满额时这一项不该被加进去", AddOutcome.Full, r.outcome)
        assertNull("满额时不该产生要追加的值", r.value)
        assertEquals(
            "满额时草稿必须原样留住 —— 这一条是本次修复的核心",
            "新标签",
            r.draftAfter,
        )
        assertTrue("满额必须说一句话，不能静默", r.notice != null)
        assertTrue(
            "那句话里要出现上限数字，用户才知道该做什么：${r.notice}",
            r.notice!!.contains(NotePayload.MAX_TAGS.toString()),
        )
    }

    /** 差一个满、正好满、满过一 —— 三种边界的判定必须一致。 */
    @Test
    fun `标签数量的边界：少一个能加、正好满不能加`() {
        val almost = List(NotePayload.MAX_TAGS - 1) { "t$it" }
        val r1 = tagAddResult("最后一个位置", almost)
        assertEquals(AddOutcome.Added, r1.outcome)
        assertEquals("最后一个位置", r1.value)

        val exact = List(NotePayload.MAX_TAGS) { "t$it" }
        assertEquals(AddOutcome.Full, tagAddResult("再来一个", exact).outcome)

        val over = List(NotePayload.MAX_TAGS + 5) { "t$it" }
        assertEquals(
            "已经超过上限时（比如从备份导入的旧数据），同样是加不进去",
            AddOutcome.Full,
            tagAddResult("再来一个", over).outcome,
        )
    }

    // ==================== 2. 标签：重复也不能白丢 ====================

    /**
     * 缺陷：同一个 if 里还有第二个静默分支 —— `clean !in tags`。
     *
     * 后果：打一个已经存在的标签，字同样被清空、界面同样不说。
     * 用户的困惑与满额那次一模一样，但**两句提示要告诉他的下一步完全不同**：
     * 满额要去移除一个，重复是"这个词本来就有"。
     * 若把两者混成一句，用户会去删别的标签，然后发现还是加不上。
     */
    @Test
    fun `重复的标签不追加但要说清是重复而不是满额`() {
        val tags = listOf("工作", "生活")

        val r = tagAddResult("工作", tags)

        assertEquals(AddOutcome.Duplicate, r.outcome)
        assertNull(r.value)
        assertEquals("确认它已在列表里，草稿清空是对的", "", r.draftAfter)
        assertTrue("必须说一句话", r.notice != null)
        assertTrue(
            "要能看出是「已经在了」，不是「满了」：${r.notice}",
            r.notice!!.contains("工作"),
        )
        assertFalse(
            "重复时不能提上限 —— 那会把用户引去删别的标签：${r.notice}",
            r.notice!!.contains(NotePayload.MAX_TAGS.toString()),
        )
    }

    /**
     * 判定顺序：一个**既重复、列表又满**的标签，应当报"重复"。
     *
     * 后果（若顺序反了）：用户被告知"满了"，于是去移除一个标签腾位置，
     * 再试一次 —— 还是加不上，因为那个词本来就在里面。
     * 他会以为是移除没生效，然后继续删。
     */
    @Test
    fun `既重复又满额时报重复而不是满额`() {
        val tags = List(NotePayload.MAX_TAGS) { if (it == 0) "工作" else "t$it" }

        val r = tagAddResult("工作", tags)

        assertEquals(AddOutcome.Duplicate, r.outcome)
        assertTrue("要指出是重复：${r.notice}", r.notice!!.contains("工作"))
    }

    // ==================== 3. 标签：成功那一路不能被改坏 ====================

    /** 成功路径：值要追加、草稿要清空、**不该多出一句提示**（不打扰）。 */
    @Test
    fun `标签正常添加：追加、清空草稿、不说话`() {
        val r = tagAddResult("  工作  ", listOf("生活"))

        assertEquals(AddOutcome.Added, r.outcome)
        assertEquals("前后空白要被去掉", "工作", r.value)
        assertEquals("加进去了才清空草稿", "", r.draftAfter)
        assertNull("正常情况下不该出现任何提示", r.notice)
    }

    /** 空白输入：清空草稿、不说话 —— 这与修复前的行为一致，不是回归。 */
    @Test
    fun `标签空白输入：什么都不发生也不说话`() {
        for (raw in listOf("", "   ", "\t\n")) {
            val r = tagAddResult(raw, listOf("工作"))
            assertEquals(AddOutcome.Empty, r.outcome)
            assertNull(r.value)
            assertEquals("", r.draftAfter)
            assertNull("空白输入不该弹提示", r.notice)
        }
    }

    /** 超长但没满：裁到上限后再加，且裁完不能有孤立代理（emoji 被劈开）。 */
    @Test
    fun `标签超长但未满：裁到上限再加，且不留孤立代理`() {
        val emoji = "\uD83D\uDE00" // 占两个 UTF-16 code unit
        val raw = "a".repeat(NotePayload.MAX_TAG_CHARS - 1) + emoji + "尾巴"

        val r = tagAddResult(raw, emptyList())

        assertEquals(AddOutcome.Added, r.outcome)
        assertEquals(
            "超长部分被裁掉之后应当只剩前面那些字符",
            NotePayload.MAX_TAG_CHARS - 1,
            r.value!!.length,
        )
        assertFalse(
            "截断点落在 emoji 中间时要整个去掉，不能留下半个",
            r.value!!.any { Character.isSurrogate(it) },
        )
    }

    // ==================== 4. 待办：同一个病 ====================

    /**
     * 缺陷：待办有**两个**入口（回车键与加号按钮），两处各写了一遍
     * `if (… && todos.size < MAX_TODOS) { … }; draft = ""` ——
     * 于是"满额丢字"这件事在同一个界面里存在两份。
     *
     * 后果：一条记录攒到 500 条待办之后，再输入任何一条都是白打。
     * 500 条不容易攒到，但**攒到了就是彻底用不了**，而且界面不会给出任何迹象。
     */
    @Test
    fun `待办满额时草稿必须原样留住并给出一句话`() {
        val r = todoAddResult("第 501 条", NotePayload.MAX_TODOS)

        assertEquals(AddOutcome.Full, r.outcome)
        assertNull(r.value)
        assertEquals("满额时草稿必须原样留住", "第 501 条", r.draftAfter)
        assertTrue("满额必须说一句话", r.notice != null)
        assertTrue(
            "提示里要有上限数字：${r.notice}",
            r.notice!!.contains(NotePayload.MAX_TODOS.toString()),
        )
    }

    /** 待办边界：差一条能加、正好满不能加。 */
    @Test
    fun `待办数量的边界：少一条能加、正好满不能加`() {
        assertEquals(
            AddOutcome.Added,
            todoAddResult("最后一条", NotePayload.MAX_TODOS - 1).outcome,
        )
        assertEquals(
            AddOutcome.Full,
            todoAddResult("再来一条", NotePayload.MAX_TODOS).outcome,
        )
    }

    /** 待办正常路径与空白路径。 */
    @Test
    fun `待办正常添加与空白输入`() {
        val ok = todoAddResult("  买菜  ", 3)
        assertEquals(AddOutcome.Added, ok.outcome)
        assertEquals("买菜", ok.value)
        assertEquals("", ok.draftAfter)
        assertNull(ok.notice)

        val blank = todoAddResult("   ", 3)
        assertEquals(AddOutcome.Empty, blank.outcome)
        assertEquals("", blank.draftAfter)
        assertNull(blank.notice)
    }

    /** 待办不做字符裁剪（与修复前一致）：超长由输入框下方的实时提示负责。 */
    @Test
    fun `待办超长仍然原样加入，字符数交给实时提示与落盘那一步`() {
        val raw = "x".repeat(NotePayload.MAX_TODO_CHARS + 50)
        val r = todoAddResult(raw, 0)

        assertEquals(AddOutcome.Added, r.outcome)
        assertEquals(
            "这一版不改变字符数那一侧的行为",
            raw.length,
            r.value!!.length,
        )
    }

    // ==================== 5. 两类列表都不能再"白丢" ====================

    /**
     * 这是整个文件里最该留下的一条：**"满额"这个状态下，两个列表都不能丢字。**
     *
     * 写成一条断言而不是两条，是因为它们将来会各自演化 ——
     * 有人加了"星标标签"或者"待办分组"，满额判定可能被改到别处。
     * 只要这一条还在，谁改坏都会当场变红。
     */
    @Test
    fun `满额时两个列表都不许把用户打好的字丢掉`() {
        val typed = "用户刚打的这几个字"

        val tag = tagAddResult(typed, List(NotePayload.MAX_TAGS) { "t$it" })
        val todo = todoAddResult(typed, NotePayload.MAX_TODOS)

        assertEquals(
            "标签满额时丢字了",
            typed,
            tag.draftAfter,
        )
        assertEquals(
            "待办满额时丢字了",
            typed,
            todo.draftAfter,
        )
        assertTrue("标签满额时没说话", tag.notice != null)
        assertTrue("待办满额时没说话", todo.notice != null)
    }
}
