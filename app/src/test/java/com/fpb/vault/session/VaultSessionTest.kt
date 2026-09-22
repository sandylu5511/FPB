package com.fpb.vault.session

import com.fpb.vault.crypto.KdfParams
import com.fpb.vault.crypto.VaultKeyring
import com.fpb.vault.crypto.unlockedOrFail
import com.fpb.vault.data.BlobReader
import com.fpb.vault.data.ChunkedBlobFormat
import com.fpb.vault.model.ImageRef
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.model.SecretField
import com.fpb.vault.model.TodoItem
import com.fpb.vault.model.VideoRef
import com.fpb.vault.testing.InMemoryBlobSink
import com.fpb.vault.testing.InMemoryRowStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

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

/**
 * 一段"视频"的字节。
 *
 * 长度刻意跨过多个块（默认块大小 1 MiB），否则随机访问、跨块边界、
 * "只解第 0 块"这些行为一条都验不到 —— 单块的输入会让它们全部退化成"整段读一遍"。
 */
private fun videoBytes(seed: Int = 11): ByteArray = bytesOf(seed, size = 2 * (1 shl 20) + 7_777)

private fun videoNote(blobId: String, title: String = "视频") = NotePayload(
    type = NoteType.VIDEO,
    title = title,
    videos = listOf(VideoRef(blobId, 1920, 1080, 3_600L)),
    createdAt = 0L,
    updatedAt = 0L,
)

/** 把一个读取器从头读到尾。 */
private fun readAll(reader: BlobReader): ByteArray {
    val out = ByteArray(reader.size.toInt())
    var at = 0
    while (at < out.size) {
        val n = reader.readAt(at.toLong(), out, at, out.size - at)
        if (n <= 0) throw AssertionError("读到 $at 字节时提前结束（应为 ${out.size}）")
        at += n
    }
    return out
}

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

    // ==================== 视频（分块格式） ====================

    @Test
    fun `视频入库后能按随机访问逐字节读回`() {
        val f = Fixture()
        f.unlockReal()
        val plain = videoBytes()

        val stored = f.session.putVideo(ByteArrayInputStream(plain))

        assertEquals(plain.size.toLong(), stored.plainBytes)
        val reader = f.session.openBlob(stored.blobId)
        assertNotNull("视频必须能打开成随机访问读取器", reader)
        reader!!.use {
            assertEquals(plain.size.toLong(), it.size)
            assertArrayEquals("整段读回必须与原文逐字节一致", plain, readAll(it))
        }
    }

    @Test
    fun `视频跨块边界的读取与任意位置读取都正确`() {
        val f = Fixture()
        f.unlockReal()
        val plain = videoBytes()
        val stored = f.session.putVideo(ByteArrayInputStream(plain))

        f.session.openBlob(stored.blobId)!!.use { reader ->
            val boundary = ChunkedBlobFormat.DEFAULT_CHUNK_BYTES.toLong()
            val across = ByteArray(4)
            assertEquals(4, reader.readAt(boundary - 2, across, 0, 4))
            assertEquals(plain[(boundary - 2).toInt()], across[0])
            assertEquals(plain[boundary.toInt()], across[2])

            val rnd = java.util.Random(2026L)
            repeat(200) {
                val at = rnd.nextInt(plain.size)
                val one = ByteArray(1)
                assertEquals(1, reader.readAt(at.toLong(), one, 0, 1))
                assertEquals("位置 $at 的字节不对", plain[at], one[0])
            }

            // 越界是"读完了"（-1），不是"损坏"。
            assertEquals(-1, reader.readAt(reader.size, ByteArray(1), 0, 1))
        }
    }

    // ==================== 占用明细（设置页的"存储"那一栏） ====================

    /**
     * 明细的第一条不变量：**各项之和 == 总占用**。
     *
     * 没有这条，"明细"就只是一堆看着漂亮的数字：用户会去加，加起来对不上，
     * 然后开始怀疑应用在偷偷占他的空间。而"总数比各项之和大一截"恰恰是最容易
     * 出现的情况 —— 半截文件与没人引用的附件，都曾经不属于任何一项。
     */
    @Test
    fun `占用明细的各项之和等于总占用`() {
        val f = Fixture()
        f.unlockReal()

        val photo = f.session.putImage(bytesOf(1, size = 5000))
        val video = f.session.putVideo(ByteArrayInputStream(videoBytes()))
        f.session.create(
            NotePayload(
                type = NoteType.IMAGE,
                title = "带照片和视频的一条",
                images = listOf(ImageRef(photo, 100, 100)),
                videos = listOf(VideoRef(video.blobId, 1920, 1080, 3_600L)),
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )

        val usage = f.session.storageUsage()!!
        assertEquals(
            "明细各项相加必须等于总占用 —— 对不上就说明有一类附件没被归到任何一项里",
            f.session.storageBytes(),
            usage.totalBytes,
        )
        assertEquals(1, usage.photoCount)
        assertEquals(1, usage.videoCount)
        assertEquals(0, usage.orphanCount)
        assertEquals(0L, usage.tempBytes)
    }

    /**
     * 没人引用的附件要单独归一类，**不能混进照片/视频的账里**。
     *
     * 混进去的后果不是"数字不准"，而是**清理功能看起来没用**：用户点"清理无用图片"，
     * 数字本该跟着降下来；如果那些附件被算在"照片"里，清理前后照片那一项纹丝不动，
     * 看起来就像清理失败了 —— 而它其实成功了。
     */
    @Test
    fun `未被引用的附件单独归一类`() {
        val f = Fixture()
        f.unlockReal()

        val live = f.session.putImage(bytesOf(1, size = 4000))
        val orphan = f.session.putImage(bytesOf(2, size = 6000))
        f.session.create(
            NotePayload(
                type = NoteType.IMAGE,
                title = "只引用其中一张",
                images = listOf(ImageRef(live, 10, 10)),
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )

        val usage = f.session.storageUsage()!!
        assertEquals(1, usage.photoCount)
        assertEquals(1, usage.orphanCount)
        assertEquals("被引用那张占的字节", f.blobs.sizeOf(live), usage.photoBytes)
        assertEquals(f.blobs.sizeOf(orphan), usage.orphanBytes)

        // 清理之后那一类归零，而"照片"这一项一个字节都不能少。
        assertEquals(1, f.session.purgeOrphanBlobs())
        val after = f.session.storageUsage()!!
        assertEquals(0, after.orphanCount)
        assertEquals(0L, after.orphanBytes)
        assertEquals(usage.photoBytes, after.photoBytes)
    }

    /**
     * 附件文件不见时，"张数"必须跟着字节一起不算它，并**单独报出缺失**。
     *
     * 张数若取"记录里引用了几个"，这一行会显示"照片 5 张 · 3 MB" ——
     * 用户看不出少了一张，也就不会想到那条记录里已经有一张永远打不开了。
     * 张数与字节必须同源（都只在文件真的在磁盘上时才计数），缺失则另立一项，
     * 这样用户至少有一条能追的线索。
     */
    @Test
    fun `附件文件丢失时张数不算它并单独报出缺失`() {
        val f = Fixture()
        f.unlockReal()

        val kept = f.session.putImage(bytesOf(1, size = 4000))
        val lost = f.session.putImage(bytesOf(2, size = 6000))
        f.session.create(
            NotePayload(
                type = NoteType.IMAGE,
                title = "两张图",
                images = listOf(ImageRef(kept, 10, 10), ImageRef(lost, 10, 10)),
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        // 模拟"文件在磁盘上不见了"：那条记录还引着它，但文件已经不在了。
        assertTrue(f.blobs.delete(lost))

        val usage = f.session.storageUsage()!!
        assertEquals("磁盘上只剩一张", 1, usage.photoCount)
        assertEquals("少掉的那张要单独报出来", 1, usage.missingPhotoCount)
        assertEquals("字节只算真实存在的那张", f.blobs.sizeOf(kept), usage.photoBytes)
        assertEquals(
            "缺失说的是少了什么、不是占了什么，不能并进总占用",
            f.session.storageBytes(),
            usage.totalBytes,
        )
    }

    /**
     * 视频的**明文**大小必须能从落盘的头部读回来，而不是靠另存一个字段。
     *
     * 界面要告诉用户"这段视频多大"，而用户心里的数是**原始文件的字节数**；
     * 磁盘上那个数比它多出固定开销。两个数都给出来，才不会出现
     * "应用说我占了 2.1 GB、我明明只放了 2.1 GB"这种对不上的疑惑。
     * 头部本来就记着明文长度（否则读不出块边界），所以这里不必新增落盘字段。
     */
    @Test
    fun `视频的明文大小能从落盘头部读回来`() {
        val f = Fixture()
        f.unlockReal()
        val plain = videoBytes()
        val stored = f.session.putVideo(ByteArrayInputStream(plain))
        f.session.create(videoNote(stored.blobId))

        val usage = f.session.storageUsage()!!
        assertEquals("明文大小取自分块头部", plain.size.toLong(), usage.videoPlainBytes)
        assertEquals("密文占盘", f.blobs.sizeOf(stored.blobId), usage.videoBytes)
        assertTrue("密文不可能小于明文", usage.videoBytes >= usage.videoPlainBytes)
    }

    /**
     * 整块格式（图片）的明文大小靠"总长减固定开销"反推 —— 不必把内容解密出来。
     *
     * 设置页统计几百张图时，"读几个文件头"与"把每张图都解密一遍"是完全不同的代价，
     * 而这里要的只是一个数字。
     */
    @Test
    fun `图片的明文大小靠总长反推而不必解密`() {
        val f = Fixture()
        f.unlockReal()
        val plain = bytesOf(3, size = 12_345)
        val blobId = f.session.putImage(plain)
        f.session.create(
            NotePayload(
                type = NoteType.IMAGE,
                title = "一张",
                images = listOf(ImageRef(blobId, 10, 10)),
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )

        assertEquals(plain.size.toLong(), f.session.storageUsage()!!.photoPlainBytes)
    }

    /**
     * 锁定时返回 null，而**不是**一份"全部都是孤儿"的表。
     *
     * 分类靠的是内存索引里"哪些 blobId 被引用"，而索引在锁定时是空的 ——
     * 那种回答不是"不精确"，是**反的**：它会把库里全部照片和视频都报成垃圾，
     * 于是设置页会建议用户去清理他没删过的东西。
     */
    @Test
    fun `锁定时占用明细返回空而不是把全部附件算成孤儿`() {
        val f = Fixture()
        f.unlockReal()
        val photo = f.session.putImage(bytesOf(1, size = 3000))
        f.session.create(
            NotePayload(
                type = NoteType.IMAGE,
                title = "一张",
                images = listOf(ImageRef(photo, 10, 10)),
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        assertNotNull("解锁态下应当能给出明细", f.session.storageUsage())

        f.session.lock()
        assertNull("锁定时无从判断谁被引用，不能拿空索引去猜", f.session.storageUsage())
    }

    @Test
    fun `图片仍能通过同一个入口打开`() {
        // 回归：openBlob 在分流处新增了 isChunked 判定，整块格式那条路不能被带坏。
        val f = Fixture()
        f.unlockReal()
        val blobId = f.session.putImage(bytesOf(21))

        f.session.openBlob(blobId)!!.use {
            assertArrayEquals(bytesOf(21), readAll(it))
        }
    }

    @Test
    fun `视频不能走图片读取路径`() {
        // 这条挡的是一次 OOM。`image()` 的实现是"整份密文读进堆再解密"，
        // 而视频上限 2 GiB —— 缩略图、大图、实况探测这几个入口一旦被视频 blob 撞上，
        // 下场不是"报错说这不是图片"，而是整个进程被系统杀掉。
        val f = Fixture()
        f.unlockReal()
        val stored = f.session.putVideo(ByteArrayInputStream(videoBytes()))

        assertNull("视频不该出现在图片读取的结果里", f.session.image(stored.blobId))
        assertTrue("视频密文本身还在", f.blobs.ids.contains(stored.blobId))
    }

    @Test
    fun `视频重新打开库后仍能读出来`() {
        val f = Fixture()
        f.unlockReal()
        val plain = videoBytes()
        val stored = f.session.putVideo(ByteArrayInputStream(plain))
        f.session.create(videoNote(stored.blobId))
        f.session.lock()

        val reopened = f.reopen()
        reopened.unlock(f.keyring.unlock(MASTER.toCharArray()).unlockedOrFail())

        val note = reopened.notes().single()
        assertEquals(NoteType.VIDEO, note.type)
        assertEquals(stored.blobId, note.payload.videos.single().blobId)
        assertArrayEquals(plain, readAll(reopened.openBlob(stored.blobId)!!))
    }

    @Test
    fun `视频不以明文落盘`() {
        val f = Fixture()
        f.unlockReal()
        val plain = ByteArray(ChunkedBlobFormat.DEFAULT_CHUNK_BYTES * 2)
        val marker = "视频明文标记".toByteArray(Charsets.UTF_8)
        // 故意让标记跨块边界：任何一块单独泄漏都不该出现它
        System.arraycopy(marker, 0, plain, ChunkedBlobFormat.DEFAULT_CHUNK_BYTES - 3, marker.size)

        f.session.putVideo(ByteArrayInputStream(plain))

        assertFalse("分块密文里不该找得到明文片段", f.blobs.containsPlaintext("视频明文标记"))
    }

    @Test
    fun `锁定时保存或打开视频都抛出异常`() {
        val f = Fixture()
        assertThrowsIllegalState("锁定时保存视频应抛出") {
            f.session.putVideo(ByteArrayInputStream(videoBytes()))
        }
        assertThrowsIllegalState("锁定时打开附件应抛出") {
            f.session.openBlob("a".repeat(32))
        }
    }

    @Test
    fun `诱饵库既读不出也清不掉真库的视频`() {
        val f = Fixture()
        f.unlockReal()
        val stored = f.session.putVideo(ByteArrayInputStream(videoBytes()))
        f.session.create(videoNote(stored.blobId))
        f.session.lock()

        val decoy = f.reopen()
        decoy.unlock(f.keyring.unlock(DECOY.toCharArray()).unlockedOrFail())

        // 1) 读不出来。
        //
        // 注意：分块格式的**打开**不需要密钥 —— 判据是文件头的魔数与长度自洽，
        // 所以这里可能拿到一个读取器，真正的拒绝发生在第一次解密。
        // 这个"懒"是有意的：读哪块解哪块，打开一个 2 GiB 的视频不该先把整份文件解一遍。
        decoy.openBlob(stored.blobId)?.use { reader ->
            assertThrows(IOException::class.java) {
                reader.readAt(0, ByteArray(1), 0, 1)
            }
        }

        // 2) 也清不掉。这条更要紧：孤儿清扫若按"磁盘上有、我引用的里没有"判定，
        //    真库的视频会被当成诱饵库的垃圾删掉 —— 一次不可逆的静默损毁。
        assertEquals("诱饵库里没有孤儿，不该清掉任何东西", 0, decoy.purgeOrphanBlobs())
        assertTrue("真库的视频密文必须原封不动", f.blobs.ids.contains(stored.blobId))
    }

    // ==================== 增删改 ====================

    @Test
    fun `删除条目会一并删掉它的视频密文`() {
        // 漏了这一步的后果很具体：GB 级的密文永远留在库里，
        // 而界面上已经看不到这条记录了 —— 空间只能靠"清空本机数据"才能收回。
        val f = Fixture()
        f.unlockReal()
        val stored = f.session.putVideo(ByteArrayInputStream(videoBytes()))
        val note = f.session.create(videoNote(stored.blobId))

        assertEquals(1, f.blobs.size)
        assertTrue(f.session.delete(note.id))
        assertEquals("视频密文应随条目一起清掉", 0, f.blobs.size)
    }

    @Test
    fun `孤儿清扫不会把被引用的视频当垃圾删掉`() {
        // 这条挡的是一个会静默毁掉全部视频的错误：purgeOrphanBlobs 的 live 集合
        // 若只收 images 不收 videos，每一次"清理无用内容"都会把库里**全部**视频
        // 当成孤儿删掉 —— 记录还留着，表现为"视频全变成打不开的黑块"。
        val f = Fixture()
        f.unlockReal()
        val kept = f.session.putVideo(ByteArrayInputStream(videoBytes(seed = 31)))
        val note = f.session.create(videoNote(kept.blobId))
        val orphan = f.session.putVideo(ByteArrayInputStream(videoBytes(seed = 32)))
        assertEquals(2, f.blobs.size)

        val removed = f.session.purgeOrphanBlobs()

        assertEquals("只应清掉那个没被任何记录引用的", 1, removed)
        assertTrue("被引用的视频必须保留", f.blobs.ids.contains(kept.blobId))
        assertFalse("没被引用的那个才该清掉", f.blobs.ids.contains(orphan.blobId))
        assertNotNull("记录还在，视频还必须能打开", f.session.openBlob(kept.blobId))
        assertEquals(note.id, f.session.notes().single().id)
    }

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
