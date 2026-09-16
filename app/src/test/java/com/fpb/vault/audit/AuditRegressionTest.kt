package com.fpb.vault.audit

import com.fpb.vault.codec.NoteIndexCodec
import com.fpb.vault.crypto.KeySlot
import com.fpb.vault.crypto.KdfParams
import com.fpb.vault.crypto.RecoveryCode
import com.fpb.vault.crypto.SecureBytes
import com.fpb.vault.crypto.VaultKeyring
import com.fpb.vault.crypto.assertRejectsIllegalArgument
import com.fpb.vault.crypto.unlockedOrFail
import com.fpb.vault.data.FileBlobStore
import com.fpb.vault.model.ImageRef
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.session.VaultSession
import com.fpb.vault.testing.InMemoryBlobSink
import com.fpb.vault.testing.InMemoryRowStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * 代码审核发现的缺陷的回归测试。
 *
 * 每条测试对应一个**在修复前会失败**的具体缺陷。修复后它们全部转为通过，
 * 从而把"改对了没有"变成可执行的断言，而不是口头结论。
 *
 * 命名规则：每条测试的注释里写清「缺陷是什么 / 会造成什么后果 / 修法是什么」。
 */
class AuditRegressionTest {

    private val master = "correct horse battery"
    private val decoy = "another long passphrase"

    private fun keyring(): com.fpb.vault.crypto.CreationResult =
        VaultKeyring.create(master.toCharArray(), decoy.toCharArray(), KdfParams.fast())

    private fun textNote(title: String, tags: List<String> = emptyList()) = NotePayload(
        type = NoteType.TEXT,
        title = title,
        body = "正文",
        tags = tags,
        createdAt = 0L,
        updatedAt = 0L,
    )

    // ==================== 1. 恢复码的"别名输入" ====================

    /**
     * 缺陷：在 48 字符的合法恢复码后面多打一个 `0`，`canonicalize` 仍然接受。
     *
     * 原因：48 字符 = 240 位，整除；多出的那个字符贡献 5 位残留，恰好全为 0，
     * 于是"尾部填充位必须为零"这条校验被绕过；而长度校验只看**解出的字节数**
     * （仍是 30 字节），也放行了。
     *
     * 后果：同一个恢复码存在无穷多个可接受的写法（多打 0、多打 00…要看位数），
     * 违反"长度不符即拒绝"的契约，也让输入框的即时校验失去意义。
     * 对一个"用户手抄、手输"的恢复码来说，这一类宽松必须为零。
     */
    @Test
    fun `恢复码末尾多打一个 0 不应被接受`() {
        repeat(20) {
            val code = RecoveryCode.generate()
            assertNull(
                "在合法恢复码后追加一个 0 被接受了 —— 存在无限多个等价别名",
                RecoveryCode.canonicalize(code + "0"),
            )
        }
    }

    /** 同一问题的另一种表现：多打两个 `0`（此处长度校验会挡住，用于确认修法不是"只补一个特例"）。 */
    @Test
    fun `恢复码末尾多打两个 0 应被拒绝`() {
        val code = RecoveryCode.generate()
        assertNull(RecoveryCode.canonicalize(code + "00"))
    }

    /** 合法恢复码本身必须依然可用（防止上一处的修复把正常路径一起挡掉）。 */
    @Test
    fun `合法恢复码及其带连字符形式依然被接受`() {
        val code = RecoveryCode.generate()
        assertEquals(code, RecoveryCode.canonicalize(code))
        assertEquals(code, RecoveryCode.canonicalize(RecoveryCode.formatForDisplay(code)))
    }

    // ==================== 2. 外部 KEK 写入槽位时缺少域校验 ====================

    /**
     * 缺陷：`withExternalWrap` 不校验 DEK 的域，可以吧**诱饵 DEK** 写进**主密码槽**。
     *
     * 后果：这正是 `VaultDomain` 这个类型被造出来要防的事故 ——
     * 用户输入真实主密码，却被解到诱饵库，于是把真正的私密内容
     * 一路写进"准备给攻击者看"的那个库。密码路径有校验，外部 KEK 路径漏了。
     *
     * M4 的生物识别槽就走这条路（KEK 来自 Keystore），因此在 M4 之前必须补上。
     */
    @Test
    fun `诱饵 DEK 不得经由外部 KEK 写进主密码槽`() {
        val created = keyring()
        try {
            val decoyDek = created.decoyDek!!
            assertRejectsIllegalArgument("诱饵 DEK 被允许写进主密码槽") {
                created.keyring.withExternalWrap(
                    KeySlot.PRIMARY,
                    SecureBytes.random(32),
                    decoyDek,
                )
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    /** 反向也必须挡住：真库 DEK 不得写进诱饵槽，否则"假密码进真库"会暴露真库。 */
    @Test
    fun `真库 DEK 不得经由外部 KEK 写进诱饵槽`() {
        val created = keyring()
        try {
            assertRejectsIllegalArgument("真库 DEK 被允许写进诱饵槽") {
                created.keyring.withExternalWrap(
                    KeySlot.DECOY,
                    SecureBytes.random(32),
                    created.primaryDek,
                )
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    /** 正确配对的用法必须照常工作（防止修复把 M4 的正常路径一起挡掉）。 */
    @Test
    fun `真库 DEK 经由外部 KEK 写入生物识别槽仍然可用`() {
        val created = keyring()
        try {
            val hardwareKek = SecureBytes.random(32)
            val updated = created.keyring.withExternalWrap(
                KeySlot.BIOMETRIC,
                hardwareKek,
                created.primaryDek,
            )
            val dek = updated.unwrapWithExternalKek(KeySlot.BIOMETRIC, hardwareKek)
            assertNotNull("按正确域写入后应当能解出", dek)
            assertTrue(
                SecureBytes.constantTimeEquals(dek!!.expose(), created.primaryDek.expose()),
            )
            dek.close()
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 3. KDF 参数缺少上界 ====================

    /**
     * 缺陷：`KdfParams` 只校验了下界，没有上界；而这份参数是**从密钥文件里读出来的**，
     * 密钥文件将来会从备份包导入（可能来自网盘、来自别人）。
     *
     * 后果：一个伪造的密钥文件把 `memoryKiB` 写成 `Int.MAX_VALUE`（约 2 TiB），
     * 解锁时 `Argon2BytesGenerator` 会尝试按此规模分配内存 ——
     * 抛的是 `OutOfMemoryError`（属于 Error 而不是 Exception，`decode` 的
     * `catch (Exception)` 接不住），应用直接崩溃，且无法给出可理解的提示。
     * `iterations` 写成一个天文数字则表现为"解锁永久卡死"。
     *
     * 这不是理论问题：M5 的导入功能就是这条路径的入口。
     */
    @Test
    fun `KDF 参数拒绝不合理的内存上限`() {
        assertRejectsIllegalArgument("内存开销 2 TiB 被接受了") {
            KdfParams(Int.MAX_VALUE, 3, 2, ByteArray(KdfParams.SALT_BYTES))
        }
        assertRejectsIllegalArgument("内存开销 100 GiB 被接受了") {
            KdfParams(100 * 1024 * 1024, 3, 2, ByteArray(KdfParams.SALT_BYTES))
        }
    }

    @Test
    fun `KDF 参数拒绝不合理的迭代次数`() {
        assertRejectsIllegalArgument("迭代次数 Int 最大值被接受了") {
            KdfParams(64 * 1024, Int.MAX_VALUE, 2, ByteArray(KdfParams.SALT_BYTES))
        }
    }

    /** 三个预设档位必须仍然合法。 */
    @Test
    fun `三个预设档位都通过校验`() {
        listOf(KdfParams.fast(), KdfParams.standard(), KdfParams.highSecurity())
            .forEach { assertNotNull(it.toString()) }
    }

    // ==================== 4. 标签截断与去重的顺序 ====================

    /**
     * 缺陷：`NotePayload.normalized()` 先 `distinct()` 再截断到 32 字符。
     *
     * 后果：两个仅在第 32 字符之后不同的标签，去重时被判为不同、截断后却变得完全相同，
     * 于是库里出现两个一样的标签，界面上出现重复的筛选按钮、标签计数也会被算成 2。
     * 正确顺序是先截断（变成最终落盘的样子）再判重。
     */
    @Test
    fun `超长标签截断后不应留下重复项`() {
        val base = "标签".repeat(16) // 32 个字符，正好是上限
        val normalized = textNote(
            title = "标签去重顺序",
            tags = listOf(base + "甲", base + "乙", base + "丙"),
        ).normalized()

        assertEquals(
            "截断后相同的标签没有被去重：${normalized.tags}",
            1,
            normalized.tags.size,
        )
    }

    // ==================== 5. 锁定时 update 的静默失败 ====================

    /**
     * 缺陷：`VaultSession.update()` 没有调用 `requireUnlocked()`，锁定时因为索引已被清空，
     * 它走到 `index[id] ?: return null` 就返回了 null。
     *
     * 后果：调用方无法区分"这条不存在"和"保险库锁着"。界面会显示"保存失败/条目不存在"，
     * 而真实原因是会话到期 —— 用户被告知的是一个错误的事实。
     * `delete()` 在这件事上抛异常，`update()` 却不抛，同一个类里两套语义。
     */
    @Test
    fun `锁定时更新条目应抛出异常而不是静默返回 null`() {
        val rows = InMemoryRowStore()
        val blobs = InMemoryBlobSink()
        val created = keyring()
        try {
            val session = VaultSession(rows, blobs)
            session.unlock(created.keyring.unlock(master.toCharArray()).unlockedOrFail())
            val note = session.create(textNote("先写一条"))
            session.lock()

            var thrown = false
            try {
                session.update(note.id, textNote("锁定时不该能改"))
            } catch (e: IllegalStateException) {
                thrown = true
            }
            assertTrue("锁定时 update 静默返回 null，调用方会误判为『条目不存在』", thrown)
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 6. 解锁时多余的 LOCKED 回调 ====================

    /**
     * 缺陷：`VaultSession.unlock()` 第一件事是无条件调用 `lock()`，
     * 而 `lock()` 在"之前是解锁状态"时会发出一次 `LOCKED` 回调。
     *
     * 后果：已经解锁的会话再次调用 unlock（例如解锁失败后重试、或界面重建）时，
     * 回调序列是 `LOCKED → UNLOCKED`。界面若按 LOCKED 跳回解锁页，
     * 用户会看到一次莫名其妙的闪回。状态回调表达的应当是**状态迁移**，
     * 而不是"内部清理动作"。
     */
    @Test
    fun `重复解锁不应先发出一次锁定回调`() {
        val rows = InMemoryRowStore()
        val blobs = InMemoryBlobSink()
        val created = keyring()
        try {
            val session = VaultSession(rows, blobs)
            val events = mutableListOf<VaultSession.State>()
            session.onStateChanged = { events += it }

            session.unlock(created.keyring.unlock(master.toCharArray()).unlockedOrFail())
            session.unlock(created.keyring.unlock(master.toCharArray()).unlockedOrFail())

            assertEquals(
                "回调序列应只表达『进入解锁态』这一次迁移，实际为 $events",
                listOf(VaultSession.State.UNLOCKED),
                events,
            )
            assertTrue(session.isUnlocked)
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    /** 真正的锁定动作必须照常发出回调。 */
    @Test
    fun `锁定动作仍会发出锁定回调`() {
        val rows = InMemoryRowStore()
        val blobs = InMemoryBlobSink()
        val created = keyring()
        try {
            val session = VaultSession(rows, blobs)
            val events = mutableListOf<VaultSession.State>()
            session.onStateChanged = { events += it }

            session.unlock(created.keyring.unlock(master.toCharArray()).unlockedOrFail())
            session.lock()
            assertEquals(listOf(VaultSession.State.UNLOCKED, VaultSession.State.LOCKED), events)
            assertFalse(session.isUnlocked)
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 7. 写入的原子性 ====================

    /**
     * 缺陷：`create()` 先写笔记行、再写索引清单，两次写入各自独立提交。
     *
     * 后果：进程在两次写入之间被杀（低内存、用户划掉任务、系统回收）就会留下
     * **行在数据库里、清单里却没有**的半截状态。而清单是"这个库里有哪些笔记"的唯一
     * 权威来源，于是那条笔记此后永远不出现在列表里、也永远不被清理，
     * 用户只会觉得"我明明记过的内容不见了"。
     *
     * 对一个内容只存在本机的应用，"写了一半"必须要么全成、要么全无。
     */
    @Test
    fun `创建条目时清单落盘失败不得留下不可见的孤儿行`() {
        val rows = InMemoryRowStore()
        val blobs = InMemoryBlobSink()
        val created = keyring()
        try {
            val session = VaultSession(rows, blobs)
            session.unlock(created.keyring.unlock(master.toCharArray()).unlockedOrFail())

            // 放行第 1 次写入（笔记行），让第 2 次（清单）失败
            rows.failUpsertAfter = 1
            var failed = false
            try {
                session.create(textNote("必须全成或全无"))
            } catch (e: IOException) {
                failed = true
            }
            rows.failUpsertAfter = -1

            assertTrue("故障注入未生效，测试本身无效", failed)
            assertEquals("磁盘上残留了清单里没有的孤儿行", 0, rows.size)
            assertEquals("内存索引也必须一并回滚", 0, session.noteCount)
            assertEquals(emptyList<String>(), session.notes().map { it.title })
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    /** 写入失败后，会话必须仍然是可用的（回滚不是"把会话搞坏"）。 */
    @Test
    fun `写入失败回滚后仍能正常创建与读取条目`() {
        val rows = InMemoryRowStore()
        val blobs = InMemoryBlobSink()
        val created = keyring()
        try {
            val session = VaultSession(rows, blobs)
            session.unlock(created.keyring.unlock(master.toCharArray()).unlockedOrFail())

            rows.failUpsertAfter = 1
            runCatching { session.create(textNote("会失败的一次")) }
            rows.failUpsertAfter = -1

            val ok = session.create(textNote("之后应该正常"))
            assertEquals(listOf("之后应该正常"), session.notes().map { it.title })
            assertNotNull(session.note(ok.id))
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 8. 删除顺序：不可再生的图片必须最后删 ====================

    /**
     * 缺陷：`delete()` 的顺序是"先删图片密文 → 再删行 → 再删清单"。
     *
     * 后果：图片密文是整个应用里**唯一不可再生**的数据。按这个顺序，
     * 只要后面任何一步失败（清单写不进去、进程被杀），用户就得到
     * "条目还在、照片全没了" —— 而且是静默的、不可恢复的。
     * 正确的顺序是按可恢复性从低到高：先提交行与清单（可回滚），图片放最后
     * （即使失败也只留下可被孤儿清理扫掉的文件）。
     */
    @Test
    fun `删除条目时清单落盘失败不得已经删掉图片`() {
        val rows = InMemoryRowStore()
        val blobs = InMemoryBlobSink()
        val created = keyring()
        try {
            val session = VaultSession(rows, blobs)
            session.unlock(created.keyring.unlock(master.toCharArray()).unlockedOrFail())

            val blobId = session.putImage(ByteArray(64) { it.toByte() })
            val note = session.create(
                NotePayload(
                    type = NoteType.IMAGE,
                    title = "带图条目",
                    images = listOf(ImageRef(blobId, 1200, 900)),
                    createdAt = 0L,
                    updatedAt = 0L,
                ),
            )

            // 删除事务里唯一的一次写入就是清单 —— 让它失败
            rows.failUpsertAfter = 0
            var failed = false
            try {
                session.delete(note.id)
            } catch (e: IOException) {
                failed = true
            }
            rows.failUpsertAfter = -1

            assertTrue("故障注入未生效，测试本身无效", failed)
            assertTrue("删除失败却已经把图片密文删掉了 —— 不可逆的数据丢失", blobs.ids.contains(blobId))
            assertNotNull("条目本身也必须还在", session.note(note.id))
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    /** 正常删除时图片仍应被清掉（防止"放在最后"被写成"干脆不删"）。 */
    @Test
    fun `正常删除条目仍会清掉它的图片密文`() {
        val rows = InMemoryRowStore()
        val blobs = InMemoryBlobSink()
        val created = keyring()
        try {
            val session = VaultSession(rows, blobs)
            session.unlock(created.keyring.unlock(master.toCharArray()).unlockedOrFail())
            val blobId = session.putImage(ByteArray(64) { it.toByte() })
            val note = session.create(
                NotePayload(
                    type = NoteType.IMAGE,
                    title = "带图条目",
                    images = listOf(ImageRef(blobId, 1200, 900)),
                    createdAt = 0L,
                    updatedAt = 0L,
                ),
            )

            assertTrue(session.delete(note.id))
            assertFalse("图片密文应随条目一并清掉", blobs.ids.contains(blobId))
            assertNull(session.note(note.id))
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }
    }

    // ==================== 9. 清单条目数上限必须在写入侧就拦住 ====================

    /**
     * 缺陷：`NoteIndexCodec.MAX_ENTRIES`（10 万）只在**解码**时检查，编码时不检查。
     *
     * 后果：写入侧一路通畅 —— 清单顺利落盘，但从那一刻起**它再也解不回来**。
     * 每次解锁都会判定"清单不可读"并拒绝，用户全部数据就此锁死，无补救途径。
     * 上限必须在两侧同时生效：写入侧抛错是一次"有提示、可处理"的失败，
     * 而写出一份解不回来的清单是一次"沉默且不可逆"的失败。
     */
    @Test
    fun `清单编码拒绝超过上限的条目数`() {
        // 用廉价的确定性 id 填充，避免这里为了造样例而跑 10 万次安全随机数
        val ids = List(NoteIndexCodec.MAX_ENTRIES + 1) { index ->
            index.toString(16).padStart(32, '0')
        }
        assertRejectsIllegalArgument("编码侧没有拦住超限的条目数 —— 会写出解不回来的清单") {
            NoteIndexCodec.encode(ids)
        }
    }

    /** 恰好等于上限必须仍然可以编码（防止把边界写成 `>=`）。 */
    @Test
    fun `清单编码接受恰好等于上限的条目数`() {
        val ids = List(NoteIndexCodec.MAX_ENTRIES) { index ->
            index.toString(16).padStart(32, '0')
        }
        val encoded = NoteIndexCodec.encode(ids)
        assertEquals(NoteIndexCodec.MAX_ENTRIES, NoteIndexCodec.decode(encoded)?.size)
    }

    // ==================== 10. 崩溃残留的半截附件文件 ====================

    /**
     * 缺陷：`FileBlobStore` 写入时先写 `<blobId>.part` 再改名，而进程被强杀
     * （OOM、划掉任务）会让临时文件留在目录里。它不满足 `RowIds.isValid`，
     * 因此 `listIds()` 看不见它，孤儿清理也永远扫不到 —— 只能永久占着空间。
     */
    @Test
    fun `孤儿清理会顺带回收崩溃残留的半截附件文件`() {
        val root = Files.createTempDirectory("mixia-audit-blobs").toFile()
        try {
            val blobs = FileBlobStore(root)
            val rows = InMemoryRowStore()
            val created = keyring()
            try {
                val session = VaultSession(rows, blobs)
                session.unlock(created.keyring.unlock(master.toCharArray()).unlockedOrFail())

                // 模拟"写到一半被杀"：留下一个没有对应 blobId 的临时文件
                val halfWritten = File(root, "a".repeat(32) + FileBlobStore.TEMP_SUFFIX)
                halfWritten.writeBytes(ByteArray(128))

                val removed = session.purgeOrphanBlobs()
                assertFalse("崩溃残留的半截文件没有被回收", halfWritten.exists())
                assertEquals("应报告清理了 1 个残留文件", 1, removed)
            } finally {
                created.primaryDek.close()
                created.decoyDek?.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
