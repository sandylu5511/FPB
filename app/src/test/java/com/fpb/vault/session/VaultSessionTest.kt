package com.fpb.vault.session

import com.fpb.vault.crypto.KdfParams
import com.fpb.vault.crypto.VaultKeyring
import com.fpb.vault.crypto.unlockedOrFail
import com.fpb.vault.model.ImageRef
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.model.SecretField
import com.fpb.vault.model.TodoItem
import com.fpb.vault.testing.InMemoryBlobSink
import com.fpb.vault.testing.InMemoryRowStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MASTER = "correct horse battery"
private const val DECOY = "another long passphrase"
private const val AUTO_LOCK = 60_000L
private const val T_START = 1_770_000_000_000L
private const val T_FAKE = 1_000L

private fun newKeyring(): VaultKeyring {
    val created = VaultKeyring.create(MASTER.toCharArray(), DECOY.toCharArray(), KdfParams.fast())
    // 测试只用到密钥环本身；create() 顺带产出的两把 DEK 在这里没有用途，立即销毁。
    created.primaryDek.close()
    created.decoyDek?.close()
    return created.keyring
}

/**
 * 一套共享的存储 + 一个会话。
 *
 * [reopen] 是关键：它用**全新的 VaultSession 实例**接上同一份存储，
 * 模拟"关掉 App 再打开"。很多缺陷只在重新加载时才暴露 ——
 * 比如索引清单没写对、域隔离只做在内存里而没落到数据上。
 */
private class Fixture {
    val rows = InMemoryRowStore()
    val blobs = InMemoryBlobSink()
    val keyring = newKeyring()
    var now = T_START
    val session = VaultSession(rows, blobs, autoLockMillis = AUTO_LOCK, clock = { now })

    fun unlockReal(): VaultSession.LoadReport =
        session.unlock(keyring.unlock(MASTER.toCharArray()).unlockedOrFail())

    fun reopen(): VaultSession =
        VaultSession(rows, blobs, autoLockMillis = AUTO_LOCK, clock = { now })
}

private fun textNote(
    title: String,
    body: String = "正文内容",
    tags: List<String> = emptyList(),
) = NotePayload(
    type = NoteType.TEXT,
    title = title,
    body = body,
    tags = tags,
    createdAt = 0L,
    updatedAt = 0L,
)

private fun imageNote(blobId: String, title: String = "照片") = NotePayload(
    type = NoteType.IMAGE,
    title = title,
    images = listOf(ImageRef(blobId, 1200, 900)),
    createdAt = 0L,
    updatedAt = 0L,
)

private fun bytesOf(seed: Int, size: Int = 64): ByteArray =
    ByteArray(size) { ((it * 7 + seed * 31) and 0xFF).toByte() }

private fun assertThrowsIllegalState(
    message: String = "应抛出 IllegalStateException",
    block: () -> Unit,
) {
    var thrown = false
    try {
        block()
    } catch (e: IllegalStateException) {
        thrown = true
    }
    assertTrue(message, thrown)
}

class VaultSessionTest {

    // ==================== 锁定状态 ====================

    @Test
    fun `锁定时任何读取或写入都抛出异常而不是返回空数据`() {
        val f = Fixture()
        assertFalse(f.session.isUnlocked)
        assertEquals(VaultSession.State.LOCKED, f.session.state)

        assertThrowsIllegalState("锁定时列出笔记应抛出") { f.session.notes() }
        assertThrowsIllegalState("锁定时搜索应抛出") { f.session.search("任意") }
        assertThrowsIllegalState("锁定时创建条目应抛出") { f.session.create(textNote("x")) }
        assertThrowsIllegalState("锁定时保存图片应抛出") { f.session.putImage(bytesOf(1)) }
        assertThrowsIllegalState("锁定时统计标签应抛出") { f.session.tagCounts() }
    }

    @Test
    fun `超时后自动锁定并清零密钥`() {
        val f = Fixture()
        f.unlockReal()
        assertTrue(f.session.isUnlocked)

        f.session.onBackgrounded(T_FAKE)
        f.session.tick(T_FAKE + 59_999L)
        assertTrue("未到超时不应锁定", f.session.isUnlocked)

        f.session.tick(T_FAKE + AUTO_LOCK)
        assertFalse("到点应自动锁定", f.session.isUnlocked)
        assertEquals(0, f.session.noteCount)
        assertThrowsIllegalState("锁定后不应还能读到笔记") { f.session.notes() }
    }

    @Test
    fun `回到前台未超时则保持解锁`() {
        val f = Fixture()
        f.unlockReal()
        f.session.onBackgrounded(T_FAKE)

        assertFalse("30 秒后回来不应触发锁定", f.session.onForegrounded(T_FAKE + 30_000L))
        assertTrue(f.session.isUnlocked)
    }

    @Test
    fun `回到前台已超时则锁定`() {
        val f = Fixture()
        f.unlockReal()
        f.session.onBackgrounded(T_FAKE)

        assertTrue("70 秒后回来应自动锁定", f.session.onForegrounded(T_FAKE + 70_000L))
        assertFalse(f.session.isUnlocked)
    }

    // ==================== 落盘无明文 ====================

    @Test
    fun `创建后可检索且落盘内容搜不到任何明文片段`() {
        val f = Fixture()
        f.unlockReal()

        f.session.create(
            textNote(
                title = "护照与签证",
                body = "护照号 E12345678，2028-03 到期",
                tags = listOf("证件"),
            ),
        )

        // 直接扫描会被写进 SQLite 的那些字节
        assertFalse("标题出现在密文里", f.rows.containsPlaintext("护照与签证"))
        assertFalse("正文出现在密文里", f.rows.containsPlaintext("E12345678"))
        assertFalse("标签出现在密文里", f.rows.containsPlaintext("证件"))
        assertFalse("类型名出现在密文里", f.rows.containsPlaintext("TEXT"))

        // 但解锁状态下一切照常可用
        assertEquals(1, f.session.notes().size)
        assertEquals(1, f.session.search("E12345678").size)
        assertEquals(1, f.session.search("签证").size)
        assertEquals(1, f.session.search("证件").size)
    }

    @Test
    fun `图片不以明文落盘且能完整取回`() {
        val f = Fixture()
        f.unlockReal()

        val payload = "PLAINTEXT-IMAGE-MARKER-0123456789".toByteArray()
        val blobId = f.session.putImage(payload)

        assertFalse("图片原始字节出现在密文里", f.blobs.containsPlaintext("PLAINTEXT-IMAGE-MARKER"))
        val restored = f.session.image(blobId)
        assertNotNull("图片应能取回", restored)
        assertTrue("取回的字节与写入一致", restored!!.contentEquals(payload))
    }

    // ==================== 索引清单与完整性 ====================

    @Test
    fun `清单随写入更新且重新打开后条目仍在`() {
        val f = Fixture()
        f.unlockReal()
        f.session.create(textNote("甲"))
        f.session.create(textNote("乙"))
        f.session.lock()

        val reopened = f.reopen()
        val report = reopened.unlock(f.keyring.unlock(MASTER.toCharArray()).unlockedOrFail())

        assertEquals(2, report.noteCount)
        assertTrue("一次干净的重新加载不应报告任何问题：$report", report.isClean)
        assertFalse(report.isFresh)
        assertEquals(setOf("甲", "乙"), reopened.notes().map { it.title }.toSet())
    }

    @Test
    fun `密文被篡改一位后报为不可读而不是静默消失`() {
        val f = Fixture()
        f.unlockReal()
        f.session.create(textNote("笔记甲"))
        val broken = f.session.create(textNote("笔记乙"))

        assertTrue(f.rows.flipByte(broken.id, 0))
        f.session.lock()

        val reopened = f.reopen()
        val report = reopened.unlock(f.keyring.unlock(MASTER.toCharArray()).unlockedOrFail())

        assertEquals("被篡改的那条不应出现在列表里", 1, report.noteCount)
        assertEquals("但必须被计入不可读，而不是当作不存在", 1, report.unreadableRows)
        assertEquals(0, report.missingRows)
        assertFalse(report.isClean)
        assertEquals(1, report.problemCount)
        assertEquals(listOf("笔记甲"), reopened.notes().map { it.title })
    }

    @Test
    fun `清单损坏时拒绝解锁并保持锁定`() {
        val f = Fixture()
        f.unlockReal()
        f.session.create(textNote("会连同清单一起被破坏"))

        // 破坏全部行，清单自然也在其中
        f.rows.flipAll()
        f.session.lock()

        assertThrowsIllegalState("清单读不出来时必须拒绝解锁，否则下一次写入会覆盖掉整个索引") {
            f.unlockReal()
        }
        assertFalse("拒绝解锁后必须保持锁定状态", f.session.isUnlocked)
        assertEquals(0, f.session.noteCount)
    }

    @Test
    fun `清单损坏后重新写入不会覆盖原索引`() {
        val f = Fixture()
        f.unlockReal()
        f.session.create(textNote("先写一条"))
        f.rows.flipAll()
        f.session.lock()

        assertThrowsIllegalState("清单损坏时不得解锁") { f.unlockReal() }

        // 解锁被拒绝，因此没有任何写入发生；原始行数不变（1 份清单 + 1 条笔记）
        assertEquals(2, f.rows.size)
    }

    // ==================== 真库与诱饵库 ====================

    @Test
    fun `真库与诱饵库共用一张表但互相看不见`() {
        val f = Fixture()

        // 真库：两条
        f.unlockReal()
        f.session.create(textNote("真库笔记 A"))
        f.session.create(textNote("真库笔记 B"))
        assertEquals("2 条笔记 + 1 份清单", 3, f.rows.size)
        f.session.lock()

        // 诱饵库：一条，且看不到真库的任何东西
        val decoySession = f.reopen()
        decoySession.unlock(f.keyring.unlock(DECOY.toCharArray()).unlockedOrFail())
        assertEquals("诱饵库必须是空的", 0, decoySession.noteCount)
        decoySession.create(textNote("诱饵笔记 X"))
        assertEquals(listOf("诱饵笔记 X"), decoySession.notes().map { it.title })
        decoySession.lock()

        assertEquals("全部行混在同一张表里：2+1 条笔记 + 2 份清单", 5, f.rows.size)

        // 真库重新打开，依然只看到自己那两条
        val realAgain = f.reopen()
        val report = realAgain.unlock(f.keyring.unlock(MASTER.toCharArray()).unlockedOrFail())
        assertTrue("真库不应报告任何异常：$report", report.isClean)
        assertEquals(setOf("真库笔记 A", "真库笔记 B"), realAgain.notes().map { it.title }.toSet())
    }

    @Test
    fun `真库与诱饵库的图片互相取不到`() {
        val f = Fixture()
        f.unlockReal()
        val realBlob = f.session.putImage(bytesOf(1))
        f.session.lock()

        val decoySession = f.reopen()
        decoySession.unlock(f.keyring.unlock(DECOY.toCharArray()).unlockedOrFail())

        assertNull("用诱饵库的密钥不应能解出真库的图片", decoySession.image(realBlob))
    }

    @Test
    fun `诱饵库清理孤儿不会误删真库的图片`() {
        val f = Fixture()

        // 真库：一张被引用 + 一张孤儿
        f.unlockReal()
        val referenced = f.session.putImage(bytesOf(1))
        val realOrphan = f.session.putImage(bytesOf(2))
        f.session.create(imageNote(referenced))
        f.session.lock()

        // 诱饵库：只有一张属于自己的孤儿
        val decoySession = f.reopen()
        decoySession.unlock(f.keyring.unlock(DECOY.toCharArray()).unlockedOrFail())
        val decoyOrphan = decoySession.putImage(bytesOf(3))
        assertEquals(3, f.blobs.size)

        val removed = decoySession.purgeOrphanBlobs()

        // 若按"磁盘上有、我引用的里没有"来判定，真库的两张图都会被删掉 ——
        // 那是一次不可逆的、静默的数据损毁。
        assertEquals("只应清掉诱饵库自己的那一张", 1, removed)
        assertTrue("真库被引用的图片必须保留", f.blobs.ids.contains(referenced))
        assertTrue("真库孤儿图归属无法确认，必须保留", f.blobs.ids.contains(realOrphan))
        assertFalse("诱饵库自己的孤儿应被清掉", f.blobs.ids.contains(decoyOrphan))
    }

    // ==================== 增删改 ====================

    @Test
    fun `删除条目会一并删掉它的图片密文`() {
        val f = Fixture()
        f.unlockReal()
        val blobId = f.session.putImage(bytesOf(9))
        val note = f.session.create(imageNote(blobId))

        assertEquals(1, f.blobs.size)
        assertTrue(f.session.delete(note.id))
        assertEquals("图片密文应随条目一起清掉", 0, f.blobs.size)
        assertEquals(0, f.session.noteCount)
    }

    @Test
    fun `更新保留创建时间并刷新修改时间`() {
        val f = Fixture()
        f.unlockReal()

        f.now = T_FAKE
        val note = f.session.create(textNote("原标题"))

        f.now = 5_000L
        val updated = f.session.update(note.id, textNote("新标题"))
        assertNotNull(updated)
        assertEquals("新标题", updated!!.title)
        assertEquals("创建时间必须保持首次写入的值", T_FAKE, updated.payload.createdAt)
        assertEquals("修改时间应更新", 5_000L, updated.payload.updatedAt)
        assertEquals(1, f.session.noteCount)
    }

    @Test
    fun `对不存在的条目更新或删除返回失败`() {
        val f = Fixture()
        f.unlockReal()
        assertNull(f.session.update("f".repeat(32), textNote("不存在")))
        assertFalse(f.session.delete("f".repeat(32)))
    }

    @Test
    fun `四类条目都能存取且重新打开后类型不变`() {
        val f = Fixture()
        f.unlockReal()

        f.session.create(textNote("文字条目", "正文"))

        val blobId = f.session.putImage(bytesOf(4))
        f.session.create(imageNote(blobId, "图片条目"))

        f.session.create(
            NotePayload(
                type = NoteType.CHECKLIST,
                title = "待办条目",
                todos = listOf(TodoItem("第一项", true), TodoItem("第二项", false)),
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )

        f.session.create(
            NotePayload(
                type = NoteType.CREDENTIAL,
                title = "密码条目",
                fields = listOf(
                    SecretField("用户名", "admin"),
                    SecretField("密码", "Tp8\$kQ2mZx9v", sensitive = true),
                ),
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )

        f.session.lock()
        val reopened = f.reopen()
        val report = reopened.unlock(f.keyring.unlock(MASTER.toCharArray()).unlockedOrFail())

        assertEquals(4, report.noteCount)
        assertEquals(
            setOf(NoteType.TEXT, NoteType.IMAGE, NoteType.CHECKLIST, NoteType.CREDENTIAL),
            reopened.notes().map { it.type }.toSet(),
        )
        assertFalse("密码字段值不应落盘为明文", f.rows.containsPlaintext("Tp8\$kQ2mZx9v"))
    }

    // ==================== 标签与输入校验 ====================

    @Test
    fun `标签统计按出现次数排序`() {
        val f = Fixture()
        f.unlockReal()
        f.session.create(textNote("甲", tags = listOf("证件", "重要")))
        f.session.create(textNote("乙", tags = listOf("证件")))
        f.session.create(textNote("丙", tags = listOf("账号")))

        val tags = f.session.tagCounts()
        assertEquals(TagCount("证件", 2), tags.first())
        assertEquals(3, tags.size)
    }

    @Test
    fun `非法 blobId 被拒绝且不会拼出目录外的路径`() {
        val f = Fixture()
        f.unlockReal()

        assertNull(f.session.image("../databases/vault"))
        assertNull(f.session.image("../../files/secret"))
        assertNull(f.session.image(""))
        assertFalse(f.session.deleteImage("not-a-valid-id"))
    }
}
