package com.fpb.vault.audit

import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.session.VaultSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第六轮审核的回归用例（2026-09-18）。
 *
 * 这一轮抓到的问题有个共同点：**它们全都没有任何测试盯着**。
 * 其中两条（设置页恒显示"正常"、占用明细的张数与字节不同源）就写在
 * 上一轮刚刚加过的代码里，而上一轮报的"313 项全绿"是真的 ——
 * 绿只说明"已有断言都成立"，不说明"该有的断言都在"。
 *
 * 所以这个文件的每一条都对应一个**曾经会错的结果**，而不是对应一个函数。
 */
class Audit5RegressionTest {

    // ==================== 1. 加载报告：两类问题必须并列 ====================

    /**
     * 缺陷：把两类问题塞进一个 `when`，只报前一类。
     *
     * 后果：库里同时有"解密失败"和"记录丢失"时，界面只说前一句。
     * 用户照着"存储可能已损坏"去查，而真正少掉的那几条还是不见踪影；
     * 而这两件事的修法完全不同（一个要修存储、一个要看清单与库是否同步）。
     * 更糟的是设置页那行"库状态"会显示"正常" —— 把一件用户能感知的异常
     * 盖成了"没问题"。
     */
    @Test
    fun `两类加载问题必须同时出现在告警里`() {
        val report = VaultSession.LoadReport(
            noteCount = 8,
            missingRows = 3,
            unreadableRows = 5,
            manifestUnreadable = false,
            isFresh = false,
        )

        val text = report.warning() ?: error("有问题时不该返回 null")
        assertTrue("解密失败那一类必须在里面，实际：$text", "5 条内容解密失败" in text)
        assertTrue("记录丢失那一类也必须在里面，实际：$text", "3 条记录在数据库里找不到了" in text)
        assertEquals("设置页那行判零用的是它", 8, report.problemCount)
    }

    /** 干净的库不该冒出告警，判零的数也必须是 0（否则"正常"永远显示不出来）。 */
    @Test
    fun `干净的库不产生告警且问题数为零`() {
        val report = VaultSession.LoadReport(
            noteCount = 2,
            missingRows = 0,
            unreadableRows = 0,
            manifestUnreadable = false,
            isFresh = false,
        )
        assertNull(report.warning())
        assertEquals(0, report.problemCount)
        assertTrue(report.isClean)
    }

    /** 只有一类问题时，那一类要照常出现（不是"两类都有才报"）。 */
    @Test
    fun `只有一类问题时也要报出来`() {
        val onlyMissing = VaultSession.LoadReport(3, missingRows = 1, unreadableRows = 0,
            manifestUnreadable = false, isFresh = false)
        assertTrue(onlyMissing.warning()!!.contains("1 条记录在数据库里找不到了"))
        assertEquals(1, onlyMissing.problemCount)
    }

    // ==================== 2. 截断不能把一个字符切成两半 ====================

    /** 😀，占两个 UTF-16 code unit。用它构造"截断点落在字符中间"的输入。 */
    private val emoji = "\uD83D\uDE00"

    /**
     * 缺陷：用 `take(n)` 截断。它数的是 code unit，不是字符。
     *
     * 后果：截断点正好落在一个 emoji 中间时，会留下一个**孤立的高位代理**。
     * 它被 UTF-8 编码时写成 `?` —— 用户粘的那串 emoji 在落盘时静默少一个、
     * 多一个问号；而在摘要那种只显示不落盘的地方会显示成"�"方块。
     * 两种后果都不报错，所以只能在截断这一步挡掉。
     */
    @Test
    fun `截断不会把一个字符切成两半`() {
        val s = "ab$emoji" + "cd"       // 6 个 code unit：a b 高 低 c d
        // 第 3 个位置正好是 emoji 的高位：从这里截断就会把它劈开。
        assertEquals("应当把被劈开的那个字符整个去掉", "ab", NotePayload.takeChars(s, 3))
        assertFalse(
            "结果里不能留下孤立代理",
            NotePayload.takeChars(s, 3).any { Character.isSurrogate(it) },
        )

        // 对照：朴素的 take 确实会劈开它 —— 这条钉住的是"为什么需要那个函数"。
        assertTrue(
            "朴素 take 的结果里应当存在孤立代理（这正是本函数要解决的问题）",
            s.take(3).any { Character.isSurrogate(it) },
        )

        // 没超限时原样返回，不做任何加工。
        assertEquals(s, NotePayload.takeChars(s, 6))
        assertEquals(s, NotePayload.takeChars(s, 99))
    }

    /**
     * 同一条约束在真正落盘的那一步也要成立：标题被截断之后，
     * 落进密文里的字符串**不能含孤立代理**。
     *
     * 这条比上一条更接近用户：它走的是 `normalized()` —— 用户粘一个长标题，
     * 保存，然后重新打开看到的就是这里的结果。
     */
    @Test
    fun `标题超限截断后落盘的内容里没有孤立代理`() {
        val title = "a".repeat(NotePayload.MAX_TITLE_CHARS - 1) + emoji + "尾巴"
        val normalized = NotePayload(
            type = NoteType.TEXT,
            title = title,
            createdAt = 0L,
            updatedAt = 0L,
        ).normalized()

        assertEquals(
            "被劈开的 emoji 应当整个去掉，于是只剩前面那些字符",
            NotePayload.MAX_TITLE_CHARS - 1,
            normalized.title.length,
        )
        assertFalse(
            "落盘的内容里不能有孤立代理",
            normalized.title.any { Character.isSurrogate(it) },
        )
    }

    /** 正文与标签走的是同一个截断函数，这里只在正文上再确认一次。 */
    @Test
    fun `正文超限截断后落盘的内容里没有孤立代理`() {
        val body = "b".repeat(NotePayload.MAX_BODY_CHARS - 1) + emoji
        val normalized = NotePayload(
            type = NoteType.TEXT,
            body = body,
            createdAt = 0L,
            updatedAt = 0L,
        ).normalized()

        assertEquals(NotePayload.MAX_BODY_CHARS - 1, normalized.body.length)
        assertFalse(normalized.body.any { Character.isSurrogate(it) })
    }
}
