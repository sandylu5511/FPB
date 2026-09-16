package com.fpb.vault.audit

import com.fpb.vault.codec.NoteCodec
import com.fpb.vault.codec.NoteIndexCodec
import com.fpb.vault.crypto.KdfParams
import com.fpb.vault.crypto.VaultKeyFileCodec
import com.fpb.vault.crypto.VaultKeyring
import com.fpb.vault.crypto.assertRejectsIllegalArgument
import com.fpb.vault.crypto.unlockedOrFail
import com.fpb.vault.data.RowIds
import com.fpb.vault.model.ImageRef
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.session.VaultSession
import com.fpb.vault.testing.InMemoryBlobSink
import com.fpb.vault.testing.InMemoryRowStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * 第二轮代码审核的探测测试。
 *
 * 与第一轮 [AuditRegressionTest] 相同的纪律：每条测试对应一个**在修复前会失败**的具体缺陷。
 * 先让它们红，再修，然后转绿 —— 缺陷的存在性由失败本身证明，不由阅读代码证明。
 */
class Audit2RegressionTest {

    private val master = "correct horse battery"
    private val decoy = "another long passphrase"

    private fun keyring(): VaultKeyring {
        val created = VaultKeyring.create(master.toCharArray(), decoy.toCharArray(), KdfParams.fast())
        created.primaryDek.close()
        created.decoyDek?.close()
        return created.keyring
    }

    private fun textNote(title: String) = NotePayload(
        type = NoteType.TEXT,
        title = title,
        body = "正文",
        createdAt = 0L,
        updatedAt = 0L,
    )

    private class Fixture {
        val rows = InMemoryRowStore()
        val blobs = InMemoryBlobSink()
        val keyring: VaultKeyring
        val session: VaultSession

        init {
            val created = VaultKeyring.create(
                "correct horse battery".toCharArray(),
                "another long passphrase".toCharArray(),
                KdfParams.fast(),
            )
            created.primaryDek.close()
            created.decoyDek?.close()
            keyring = created.keyring
            session = VaultSession(rows, blobs)
        }

        fun unlockReal() {
            session.unlock(keyring.unlock("correct horse battery".toCharArray()).unlockedOrFail())
        }

        fun reopenUnlock(): VaultSession.LoadReport {
            val fresh = VaultSession(rows, blobs)
            return fresh.unlock(keyring.unlock("correct horse battery".toCharArray()).unlockedOrFail())
        }
    }

    // ==================== 1. 清单编码不校验 id 形状 ====================

    /**
     * 缺陷：[NoteIndexCodec.encode] 对 id 不做任何形状校验，能编码出
     * **解码必拒**（`RowIds.isValid` 失败）的清单。
     *
     * 与第一轮修掉的“MAX_ENTRIES 只在解码侧检查”是同一类病：编码能产出的东西、
     * 解码会拒绝的东西，一旦落盘就是一份**自己永远解不开的清单** ——
     * 下一次解锁直接判定“清单不可读”，整个库永久锁死。
     *
     * 当前生产代码里所有 id 来源（RowIds.random / HMAC 派生 / 解码验证过的清单）
     * 都是合法形状，因此这条是**潜在缺陷**；但防线必须建在“能写坏数据的那一侧”，
     * 而不是指望所有调用方永远不犯错。
     */
    @Test
    fun `清单编码拒绝形状非法的 id`() {
        val valid = RowIds.random()
        assertRejectsIllegalArgument("非十六进制 id 被允许写进清单") {
            NoteIndexCodec.encode(listOf(valid, "not-a-hex-id"))
        }
        assertRejectsIllegalArgument("空 id 被允许写进清单") {
            NoteIndexCodec.encode(listOf(""))
        }
    }

    /** 合法 id 不受影响（防止修复误伤正常路径）。 */
    @Test
    fun `清单编码接受合法 id 且往返一致`() {
        val ids = listOf(RowIds.random(), RowIds.random())
        val bytes = NoteIndexCodec.encode(ids)
        assertEquals(ids, NoteIndexCodec.decode(bytes))
    }

    // ==================== 2. 笔记编解码不校验 blobId 形状 ====================

    /**
     * 缺陷：[NoteCodec] 编码时接受任意字符串当 blobId，解码时不校验。
     *
     * 使用点各自有 RowIds.isValid 防线（不会路径穿越），但防线分散意味着：
     * - 一条被污染的 payload 会在内存索引里带着非法 blobId 存活，
     *   每个 UI 层的使用点都得记得防一遍；
     * - 编码/解码不对称 —— 解码器声称“不合规一律 null”，却放行形状非法的 blobId。
     *
     * 修法：编码侧 require、解码侧判 null，非法形状的条目按“不可读”报告，
     * 而不是把脏数据继续往内存里放。
     */
    @Test
    fun `笔记编码拒绝形状非法的 blobId`() {
        val payload = textNote("带非法图片引用").copy(
            images = listOf(ImageRef("../../databases/vault", 1200, 900)),
        )
        assertRejectsIllegalArgument("非法 blobId 被允许编码进笔记") {
            NoteCodec.encode(payload)
        }
    }

    /** 解码侧同样拒绝（用手工构造的字节流，因为编码侧已拒绝、正常途径产不出这种数据）。 */
    @Test
    fun `笔记解码拒绝形状非法的 blobId`() {
        val bytes = handCraftedPayloadBytes(blobId = "../evil")
        assertEquals(null, NoteCodec.decode(bytes))
    }

    /** 合法 blobId 的笔记编解码往返不受影响。 */
    @Test
    fun `合法 blobId 的笔记编解码往返一致`() {
        val payload = textNote("带合法图片引用").copy(
            images = listOf(ImageRef(RowIds.random(), 1200, 900)),
        )
        assertEquals(payload, NoteCodec.decode(NoteCodec.encode(payload)))
    }

    // ==================== 3. 密钥文件解码不检查尾部多余字节 ====================

    /**
     * 缺陷：[VaultKeyFileCodec.decode] 读完全部槽位就返回，
     * **不检查后面还有没有多余字节** —— NoteCodec 与 NoteIndexCodec 都做了
     * `available() != 0` 的尾部检查，唯独密钥文件漏了。
     *
     * 后果：一个被追加过内容的文件（拼接、截断后补齐、或格式版本错位）
     * 会被静默接受为合法密钥文件，格式漂移失去了最后一道检出机会。
     * 密钥文件将来会随备份包导入，输入不可信。
     */
    @Test
    fun `密钥文件解码拒绝尾部多余字节`() {
        val keyring = keyring()
        val bytes = VaultKeyFileCodec.encode(keyring)
        val withTrailing = bytes + 0x00

        var thrown = false
        try {
            VaultKeyFileCodec.decode(withTrailing)
        } catch (e: com.fpb.vault.crypto.VaultKeyFileException) {
            thrown = true
        }
        assertTrue("追加了 1 个字节的密钥文件被接受了", thrown)
    }

    // ==================== 4. 不可读条目在下一次写入时被静默从清单抹掉 ====================

    /**
     * 缺陷：清单里“行缺失/解不开”的 id 只进 [VaultSession.LoadReport]，
     * **不进下一次 persistIndex 写出的清单** —— 任何一次 create/update/delete
     * 之后，这些 id 就从清单里消失了。
     *
     * 两个后果：
     * 1. 问题报告消失。解锁时用户被告知“1 条不可读”，随手新建一条笔记后
     *    问题就再也不会被报告 —— “笔记消失变成一件无声的事”恰恰是本设计
     *    最想避免的故障模式（见 NoteIndexCodec 的设计说明）。
     * 2. 行成为永久无主孤儿。清单是“这行属于本域”的**唯一凭证** ——
     *    id 一旦从清单掉队，这行密文既不能被归属、不能被清理，
     *    也不能被另一个库认领，只能永远躺在数据库里占空间。
     */
    @Test
    fun `不可读条目在后续写入后仍保留在清单中`() {
        val f = Fixture()
        f.unlockReal()
        val a = f.session.create(textNote("会损坏的条目"))
        f.session.lock()

        f.rows.flipByte(a.id) // 模拟存储介质损坏
        val report1 = f.reopenUnlock()
        assertEquals("损坏应当被报告", 1, report1.unreadableRows)

        // 修复前：这次 create 会把损坏条目的 id 从清单里抹掉
        val reopened = VaultSession(f.rows, f.blobs)
        reopened.unlock(f.keyring.unlock("correct horse battery".toCharArray()).unlockedOrFail())
        reopened.create(textNote("新条目"))
        reopened.lock()

        val report2 = f.reopenUnlock()
        assertEquals(
            "一次普通写入之后，损坏条目的问题报告消失了（id 已被从清单抹掉）",
            1,
            report2.unreadableRows,
        )
    }

    /** 缺失行同理：清单里记录的 id 找不到对应行时，也不应因后续写入而遗忘。 */
    @Test
    fun `缺失条目在后续写入后仍保留在清单中`() {
        val f = Fixture()
        f.unlockReal()
        val a = f.session.create(textNote("会被删掉行尸的条目"))
        f.session.lock()

        f.rows.delete(a.id) // 直接从存储层抽走这一行（模拟行丢失）
        val report1 = f.reopenUnlock()
        assertEquals(1, report1.missingRows)

        val reopened = VaultSession(f.rows, f.blobs)
        reopened.unlock(f.keyring.unlock("correct horse battery".toCharArray()).unlockedOrFail())
        reopened.create(textNote("新条目"))
        reopened.lock()

        val report2 = f.reopenUnlock()
        assertEquals("缺失条目被后续写入静默遗忘", 1, report2.missingRows)
    }

    // ==================== 5. putImage 无明文体积上限 ====================

    /**
     * 缺陷（第一轮审核的“未修改但需知晓”项，本轮补上）：
     * [VaultSession.putImage] 对明文体积没有上限。
     *
     * 解密是**解锁时一次性全量进行**的，一张超大图片会直接拖垮启动；
     * 更糟的是 GCM 解密需要把整个密文读进内存，低端机上先 OOM 的就是它。
     * M3 会做“长边 2048 / JPEG q85”重编码，但防线不能只建在
     * “上游一般会压缩”上 —— 上游忘了压缩，下游就当场死。
     */
    @Test
    fun `图片明文超过上限被拒绝`() {
        val f = Fixture()
        f.unlockReal()
        val oversized = ByteArray(VaultSession.MAX_IMAGE_PLAINTEXT_BYTES + 1)
        assertRejectsIllegalArgument("超大图片明文被接受") {
            f.session.putImage(oversized)
        }
    }

    /** 恰好等于上限的图片仍然可用（防止修复误伤正常路径）。 */
    @Test
    fun `恰好等于上限的图片仍可写入读回`() {
        val f = Fixture()
        f.unlockReal()
        val atLimit = ByteArray(VaultSession.MAX_IMAGE_PLAINTEXT_BYTES) { (it and 0xFF).toByte() }
        val blobId = f.session.putImage(atLimit)
        assertNotNull(f.session.image(blobId))
    }

    // ==================== 6. create() 的 id 冲突会静默覆盖已有条目 ====================

    /**
     * 缺陷：[VaultSession.create] 不检查新 id 是否与已有条目冲突。
     *
     * 生产环境的 idFactory 是 128 位随机数，碰撞概率可以忽略；
     * 但 check 的成本是一次 HashMap 查找 —— 一旦自定义 idFactory
     * （测试、或将来引入可导入 id 的备份恢复）产生碰撞，
     * 当前行为是**静默覆盖**：旧条目的内存索引与磁盘密文被同时替换，
     * 原内容不可逆丢失，且不抛任何异常。
     * 不可逆数据丢失必须发生在“大声失败”里，不能发生在“继续运行”里。
     */
    @Test
    fun `条目 id 冲突时拒绝覆盖已有条目`() {
        val fixed = RowIds.random()
        val f = object {
            val rows = InMemoryRowStore()
            val blobs = InMemoryBlobSink()
        }
        val keyring = keyring()
        val session = VaultSession(f.rows, f.blobs, idFactory = { fixed })
        session.unlock(keyring.unlock(master.toCharArray()).unlockedOrFail())

        session.create(textNote("第一条"))
        var thrown = false
        try {
            session.create(textNote("第二条（同 id）"))
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue("同 id 的 create 静默覆盖了已有条目", thrown)
        // 原条目必须还在
        assertEquals(1, session.noteCount)
        assertEquals("第一条", session.notes().single().title)
    }

    // ==================== 内部 ====================

    /**
     * 按 NoteCodec v1 格式手工构造一段“blobId 为指定值”的合法字节流。
     * 编码侧加校验后，正常途径产不出非法 blobId 的数据，只能手工构造来测解码侧。
     */
    private fun handCraftedPayloadBytes(blobId: String): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.write(byteArrayOf(0x4D, 0x58, 0x4E, 0x31)) // "MXN1"
            d.writeByte(1)                                  // version
            d.writeByte(2)                                  // type = IMAGE
            d.writeByte(0)                                  // flags
            d.writeLong(0L)                                 // createdAt
            d.writeLong(0L)                                 // updatedAt
            writeZ(d, "".toByteArray())                     // title
            writeZ(d, "".toByteArray())                     // body
            d.writeInt(0)                                   // tags
            d.writeInt(1)                                   // images: 1 个
            writeZ(d, blobId.toByteArray(Charsets.US_ASCII))
            d.writeInt(1200)
            d.writeInt(900)
            d.writeInt(0)                                   // todos
            d.writeInt(0)                                   // fields
        }
        return out.toByteArray()
    }

    private fun writeZ(d: DataOutputStream, bytes: ByteArray) {
        d.writeInt(bytes.size)
        d.write(bytes)
    }
}
