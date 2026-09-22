package com.fpb.vault.audit

import com.fpb.vault.crypto.Aad
import com.fpb.vault.crypto.AeadCipher
import com.fpb.vault.crypto.KdfParams
import com.fpb.vault.crypto.VaultDek
import com.fpb.vault.crypto.VaultDomain
import com.fpb.vault.crypto.VaultKeyring
import com.fpb.vault.crypto.unlockedOrFail
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.session.AuditKey
import com.fpb.vault.session.LoginEvent
import com.fpb.vault.session.LoginKind
import com.fpb.vault.session.LoginLog
import com.fpb.vault.session.VaultSession
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
private const val T0 = 1_770_000_000_000L

private class Fixture {
    val rows = InMemoryRowStore()
    val blobs = InMemoryBlobSink()

    private val created = VaultKeyring.create(MASTER.toCharArray(), DECOY.toCharArray(), KdfParams.fast())
    val keyring: VaultKeyring = created.keyring

    var now = T0

    val session = VaultSession(rows, blobs, autoLockMillis = 60_000L, clock = { now })

    init {
        created.primaryDek.close()
        created.decoyDek?.close()
    }

    fun unlockReal(): VaultSession.LoadReport =
        session.unlock(keyring.unlock(MASTER.toCharArray()).unlockedOrFail())

    fun unlockDecoy(): VaultSession.LoadReport =
        session.unlock(keyring.unlock(DECOY.toCharArray()).unlockedOrFail())

    /**
     * 建立审计通道 —— 等价于"在真库里设置假密码"那一刻。
     *
     * 只在真库会话里调用得通：它需要同时拿着真库 DEK（会话里）与诱饵 DEK（参数）。
     */
    fun linkAudit() {
        val decoyDek = keyring.unlock(DECOY.toCharArray()).unlockedOrFail().dek
        try {
            val key = AuditKey.random()
            try {
                session.linkAudit(key, decoyDek)
            } finally {
                key.close()
            }
        } finally {
            decoyDek.close()
        }
    }

    fun addNote(title: String): String = session.create(textNote(title)).id

    /** 打开本域审计槽，用完即关。 */
    fun <T> withSlot(block: (VaultSession.AuditSlot.Present) -> T): T {
        val slot = session.auditSlot()
        assertTrue("期望审计槽可用，实际是 $slot", slot is VaultSession.AuditSlot.Present)
        val present = slot as VaultSession.AuditSlot.Present
        return try {
            block(present)
        } finally {
            present.key.close()
        }
    }
}

private fun textNote(title: String) = NotePayload(
    type = NoteType.TEXT,
    title = title,
    body = "正文",
    tags = emptyList(),
    createdAt = 0L,
    updatedAt = 0L,
)

private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return true
    }
    return false
}

/**
 * 第十二轮审核的回归用例（2026-09-20，登录记录）。
 *
 * ## 这一轮改的是钥匙的形状
 *
 * "在真密码的设置页里看到假密码的登录情况"这句话，落到密码学上必须先回答一个问题：
 * **真库会话凭什么读得到诱饵库那本账？**
 *
 * 早先的答案是"真库侧存一份诱饵 DEK 的副本"。它跑得通，但有一个不可接受的后果：
 * 拿到真库 DEK 的人也就拿到了诱饵库的钥匙 —— 能把诱饵库里的东西原样端走，
 * 而诱饵库正是被胁迫时用来交差的那个库，它的钥匙不该挂在真库门后。
 *
 * 现在的答案是一把**独立随机的审计钥匙**：它只够读写那本登录时间线，
 * 打不开任何一条笔记。真库 DEK 泄露暴露的只剩"假密码在什么时候被用过"。
 *
 * 所以下面每一条断言都在守一个具体的安全性质，而不是在测"功能能不能用"。
 */
class Audit7RegressionTest {

    // ==================== 1. 核心：假密码的登录必须能被真库看到 ====================

    @Test
    fun `假密码的登录能被真库看到`() {
        val f = Fixture()
        f.unlockReal()
        f.linkAudit()

        // 用假密码进来一次，记一笔。
        f.unlockDecoy()
        f.session.recordLogin(LoginKind.DECOY_PASSWORD, T0 + 60_000)

        // 回到真库：那一笔必须在。
        f.unlockReal()
        val log = f.withSlot { f.session.decoyLoginLog(it.key) }

        assertNotNull("诱饵账本应当读得出来", log)
        assertEquals("真库必须能看到假密码的这一次登录", 1, log!!.size)
        assertEquals(LoginKind.DECOY_PASSWORD, log.toList()[0].kind)
    }

    @Test
    fun `真库自己的登录与假密码的登录能合成一条时间线`() {
        val f = Fixture()
        f.unlockReal()
        f.linkAudit()
        f.session.recordLogin(LoginKind.REAL_PASSWORD, T0 + 1_000)

        f.unlockDecoy()
        f.session.recordLogin(LoginKind.DECOY_PASSWORD, T0 + 2_000)

        f.unlockReal()
        val decoyLog = f.withSlot { f.session.decoyLoginLog(it.key) }!!
        val merged = f.session.loginLog().merged(decoyLog)

        assertEquals(2, merged.size)
        assertEquals(
            "合并之后必须按时间从旧到新排列，否则截断时丢掉的就不是最旧的那条",
            listOf(T0 + 1_000, T0 + 2_000),
            merged.toList().map { it.at },
        )
    }

    // ==================== 2. 安全边界：真库拿不到诱饵库的钥匙 ====================

    /**
     * 这是本轮**最重要**的一条断言。
     *
     * 它模拟的是：一个人拿到了真库 DEK（旧手机没清干净、备份文件流到别处、
     * 被逼着解锁过一次之后设备被扣），然后在数据库文件上做离线尝试。
     *
     * 他必须**解不开诱饵库里的任何一条笔记**。改前那版（真库侧存诱饵 DEK 副本）
     * 在这里会红：他会拿副本直接解开诱饵库的一切。
     */
    @Test
    fun `拿到真库 DEK 也解不开诱饵库的笔记`() {
        val f = Fixture()
        f.unlockDecoy()
        val secret = "诱饵库里的秘密"
        f.addNote(secret)

        f.unlockReal()
        // 真库里也要有内容，否则下面的对照条件会因为没有可解的行而失败。
        f.addNote("真库里的记录")
        f.linkAudit()

        val dek = f.session.currentDek()!!
        val readable = f.rows.loadAll().mapNotNull { row ->
            AeadCipher.open(dek, row.nonce, row.ciphertext, Aad.entryField(row.id, "payload"))
                ?: AeadCipher.open(dek, row.nonce, row.ciphertext, Aad.entryField(row.id, "index"))
        }

        assertTrue(
            "真库 DEK 不该解出诱饵库的笔记内容 —— 这正是本轮换掉「诱饵钥匙副本」的理由",
            readable.none { it.containsBytes(secret.toByteArray()) },
        )
        // 对照条件：真库 DEK 至少要能解开它自己的行。
        // 少了这一条，上面那句可能因为"什么都解不开"而假通过。
        assertTrue("对照：真库 DEK 应当能解开真库自己的行", readable.isNotEmpty())
    }

    @Test
    fun `诱饵库的会话看不到真库的登录账本`() {
        val f = Fixture()
        f.unlockReal()
        f.linkAudit()
        f.session.recordLogin(LoginKind.REAL_PASSWORD, T0 + 1_000)

        f.unlockDecoy()

        assertTrue("诱饵会话不该看到真库那一笔", f.session.loginLog().isEmpty)
        assertFalse(
            "空账不等于读不出来，这里必须是「确实没有」而不是「坏掉了」",
            f.session.loginLogUnreadable(),
        )
    }

    /**
     * 两本账必须是**物理分开**的两行。
     *
     * 如果实现里图省事把两类事件写进同一行（例如都写在本域账本里），
     * 那么胁迫者用假密码登录后打开那个库的数据，就能看到"这个库被主密码打开过"——
     * 而"假密码被胁迫时看不到真库痕迹"正是整个诱饵机制存在的理由。
     */
    @Test
    fun `真库的登录绝不会落进诱饵那本账`() {
        val f = Fixture()
        f.unlockReal()
        f.linkAudit()
        f.session.recordLogin(LoginKind.REAL_PASSWORD, T0 + 1_000)

        val decoyLog = f.withSlot { f.session.decoyLoginLog(it.key) }

        assertNotNull(decoyLog)
        assertTrue(
            "真库侧的事件不该出现在诱饵账本里",
            decoyLog!!.isEmpty,
        )
    }

    // ==================== 3. 三态：没有槽 / 槽坏了 / 槽可用 ====================

    @Test
    fun `诱饵库没有审计槽时仍然能解锁，只是记不了登录`() {
        val f = Fixture()
        f.unlockDecoy()

        assertTrue("没有审计通道也必须能正常解锁", f.session.isUnlocked)
        assertFalse("「没有通道」不是「账本读不出来」", f.session.loginLogUnreadable())
        assertTrue(f.session.loginLog().isEmpty)

        // 写入静默跳过。**绝不能抛异常**：唯一会走到这条路的正是用假密码进来的人，
        // 对着他报错等于当场告诉他这是个被监视的诱饵库。
        f.session.recordLogin(LoginKind.DECOY_PASSWORD, T0)
        assertTrue(f.session.loginLog().isEmpty)
        assertTrue(f.session.isUnlocked)
    }

    @Test
    fun `真库还没有建立通道时审计槽是「没有」，不是「坏了」`() {
        val f = Fixture()
        f.unlockReal()

        assertTrue(
            "全新建立的库应当报告「没有槽」（界面据此给一个补建入口）",
            f.session.auditSlot() is VaultSession.AuditSlot.Absent,
        )
    }

    @Test
    fun `审计槽被改过要判「损坏」，不能当成「没有槽」`() {
        val f = Fixture()
        f.unlockReal()
        val before = f.rows.ids
        f.linkAudit()

        val added = f.rows.ids - before
        assertEquals("建立通道应当新增两行（诱饵域一份、真库域一份）", 2, added.size)
        added.forEach { f.rows.flipByte(it, 0) }

        assertTrue(
            "槽在但解不出来时必须是 Damaged —— 显示成「没建过」会把一次可能的篡改说成一切正常",
            f.session.auditSlot() is VaultSession.AuditSlot.Damaged,
        )
    }

    @Test
    fun `诱饵账本损坏时必须判「读不出来」而不是「没有记录」`() {
        val f = Fixture()
        f.unlockReal()
        f.linkAudit()

        f.unlockDecoy()
        val before = f.rows.ids
        f.session.recordLogin(LoginKind.DECOY_PASSWORD, T0 + 1_000)
        val added = f.rows.ids - before
        assertEquals("记账应当只新增一行", 1, added.size)

        // 翻转那一段密文里的一个字节：模拟存储损坏或有人改动。
        f.rows.flipByte(added.first(), 0)

        f.unlockReal()
        val log = f.withSlot { f.session.decoyLoginLog(it.key) }

        assertNull(
            "解不出来必须是 null（界面据此说「读不出来」），绝不能是空账本 —— " +
                "后者会被显示成「假密码从没被用过」",
            log,
        )
    }

    // ==================== 4. 上限与写入原子性 ====================

    @Test
    fun `诱饵账本最多保留 200 条，挤掉的是最旧的一条`() {
        val f = Fixture()
        f.unlockReal()
        f.linkAudit()
        f.unlockDecoy()

        repeat(LoginLog.MAX_ENTRIES + 5) { i ->
            f.session.recordLogin(LoginKind.DECOY_PASSWORD, T0 + i * 1_000L)
        }

        val log = f.session.loginLog()
        assertEquals(LoginLog.MAX_ENTRIES, log.size)
        assertEquals(
            "被挤掉的必须是最旧的那 5 条",
            T0 + 5_000L,
            log.toList().first().at,
        )
        assertTrue("界面要据此说明「更早的看不到了」", log.isTruncated)
    }

    @Test
    fun `写入失败时内存账本必须原样不动`() {
        val f = Fixture()
        f.unlockReal()
        f.linkAudit()

        f.rows.failUpsertAfter = 0
        runCatching {
            f.session.recordLogins(
                listOf(
                    LoginEvent(LoginKind.FAILED_ATTEMPTS, T0, 3),
                    LoginEvent(LoginKind.REAL_PASSWORD, T0 + 1_000),
                ),
            )
        }

        assertTrue(
            "落盘失败时不能只在内存里留下记录 —— 那会造成「界面上有、重启就没了」",
            f.session.loginLog().isEmpty,
        )
    }

    // ==================== 5. 改密码 / 关密码 ====================

    /**
     * 换假密码会换掉整把诱饵 DEK，而登录记录**不该跟着消失**。
     *
     * 改前那版（账本由诱饵 DEK 保护）必须在这里抢先把账迁走，而那种迁移一旦失败
     * 就是静默丢掉"有人用过假密码"。现在账本由审计钥匙保护、行 id 也由它派生，
     * 所以换密码完全不碰它。
     */
    @Test
    fun `换假密码不会让此前的登录记录消失`() {
        val f = Fixture()
        f.unlockReal()
        f.linkAudit()

        f.unlockDecoy()
        f.session.recordLogin(LoginKind.DECOY_PASSWORD, T0 + 1_000)

        // 换成一把全新的诱饵 DEK，审计钥匙沿用 —— 与 setDecoyPassword 的做法一致。
        //
        // 这里不用 withSlot：它在 finally 里就把钥匙关掉了，而我们要用这把钥匙
        // 再建一次通道（关掉之后字节会被填零，派生出的行 id 就完全是另一行了）。
        f.unlockReal()
        val slot = f.session.auditSlot() as VaultSession.AuditSlot.Present
        val newDecoyDek = VaultDek.random(VaultDomain.DECOY)
        try {
            f.session.linkAudit(slot.key, newDecoyDek)
        } finally {
            slot.key.close()
            newDecoyDek.close()
        }

        val log = f.withSlot { f.session.decoyLoginLog(it.key) }
        assertNotNull(log)
        assertEquals("换假密码之后那一笔必须还在", 1, log!!.size)
        assertEquals(T0 + 1_000, log.toList()[0].at)
    }

    @Test
    fun `关掉假密码之后通道消失，那本账再也读不出来`() {
        val f = Fixture()
        f.unlockReal()
        f.linkAudit()

        f.unlockDecoy()
        f.session.recordLogin(LoginKind.DECOY_PASSWORD, T0 + 1_000)

        f.unlockReal()
        // 正确顺序：先把那本账并进真库，再拆通道。
        val decoyLog = f.withSlot { f.session.decoyLoginLog(it.key) }!!
        f.session.replaceLoginLog(f.session.loginLog().merged(decoyLog))
        f.session.unlinkAudit()

        assertEquals("并进来的那一笔必须留在真库的账本里", 1, f.session.loginLog().size)
        assertTrue(
            "拆掉之后真库不再持有任何指向诱饵账本的钥匙",
            f.session.auditSlot() is VaultSession.AuditSlot.Absent,
        )

        // 再开一次假密码：用**全新**的审计钥匙，于是不会读回上一次的旧账。
        f.linkAudit()
        val fresh = f.withSlot { f.session.decoyLoginLog(it.key) }
        assertNotNull(fresh)
        assertTrue("重开假密码必须从一本空账开始", fresh!!.isEmpty)
    }

    // ==================== 6. 域隔离：两把钥匙不能互相顶替 ====================

    @Test
    fun `把诱饵密钥当成真库密钥去建通道必须被拒绝`() {
        val f = Fixture()
        f.unlockReal()

        // 手上拿到的是**真库** DEK（域标签 REAL），却试图把它当成诱饵钥匙写进通道。
        // 真让它写进去的后果是：诱饵会话拿着真库 DEK 去算诱饵账本的行 id，
        // 算出一个不存在的行 —— 界面上显示"假密码从没被用过"，
        // 一个静默的、方向最坏的错误结论。
        val wrong = VaultDek.random(VaultDomain.REAL)
        val key = AuditKey.random()
        try {
            val failed = runCatching { f.session.linkAudit(key, wrong) }.isFailure
            assertTrue("域标签不对时必须拒绝建立通道", failed)
        } finally {
            wrong.close()
            key.close()
        }
    }

    @Test
    fun `诱饵会话不能建立或拆除审计通道`() {
        val f = Fixture()
        f.unlockDecoy()

        val decoyDek = f.keyring.unlock(DECOY.toCharArray()).unlockedOrFail().dek
        val key = AuditKey.random()
        try {
            assertTrue(
                "诱饵会话不该能建立通道",
                runCatching { f.session.linkAudit(key, decoyDek) }.isFailure,
            )
            assertTrue(
                "诱饵会话不该能拆除真库的通道",
                runCatching { f.session.unlinkAudit() }.isFailure,
            )
        } finally {
            decoyDek.close()
            key.close()
        }
    }

    @Test
    fun `锁定时审计钥匙必须随明文一起消失`() {
        val f = Fixture()
        f.unlockReal()
        f.linkAudit()
        f.unlockDecoy()

        // 解锁态下拿得到账本；锁定之后连账本本身都不该可读。
        assertTrue(f.session.isUnlocked)
        f.session.lock()

        assertFalse(f.session.isUnlocked)
        val locked = runCatching { f.session.loginLog() }.isFailure
        assertTrue("锁定后读取账本必须被拒绝，而不是返回空账本", locked)
    }
}
