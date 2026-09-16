package com.fpb.vault.diagnostics

import android.content.Context
import android.util.Log
import com.fpb.vault.crypto.Aad
import com.fpb.vault.crypto.AeadCipher
import com.fpb.vault.crypto.Argon2Kdf
import com.fpb.vault.crypto.CreationResult
import com.fpb.vault.crypto.KdfParams
import com.fpb.vault.crypto.RecoveryCode
import com.fpb.vault.crypto.SecureBytes
import com.fpb.vault.crypto.UnlockOutcome
import com.fpb.vault.crypto.VaultDomain
import com.fpb.vault.crypto.VaultKeyFileCodec
import com.fpb.vault.crypto.VaultKeyring
import com.fpb.vault.data.FileBlobStore
import com.fpb.vault.data.SqliteRowStore
import com.fpb.vault.model.ImageRef
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.model.SecretField
import com.fpb.vault.model.TodoItem
import com.fpb.vault.session.VaultSession
import java.io.ByteArrayOutputStream
import java.io.File

data class SelfTestStep(
    val name: String,
    val passed: Boolean,
    val detail: String,
    val millis: Long,
)

/**
 * 在真机上把加密内核的关键路径真实跑一遍。
 *
 * ## 为什么需要它（两个理由，都不是"顺手做的"）
 *
 * 1. **单元测试测不到真机**。单元测试跑在电脑的 JVM 上，AES 走 SunJCE、随机数走桌面版
 *    SecureRandom；真机上是 Android ART + Conscrypt + 内核熵源，是另一套实现。
 *    同一份代码在两套实现上表现不同是常见事故。
 *
 * 2. **要拿到真机上的 Argon2id 实测耗时**。PC 上量到标准档 155 ms，手机上是多少
 *    只有这里能回答。如果低端机上要 3 秒，参数就得重新定。
 *
 * 另外它还有个副作用是必要的：**让加密内核真正被生产代码引用**。
 * 否则 R8 会把整个加密包当作死代码剥掉，release 包里根本没有这段代码，
 * 于是"R8 会不会破坏加密内核"这个问题会一直藏到打包发布才暴露。
 *
 * M3 接入真实界面后，这个包可以整体删除。
 */
object CryptoSelfTest {

    private const val TAG = "MixiaSelfTest"

    private const val MASTER = "self-test master password"
    private const val DECOY = "self-test decoy password"
    private const val NEW_MASTER = "self-test rotated password"

    /**
     * @param context 用于创建真实的 SQLite 数据库与附件目录。M2 引入 ——
     *                数据层的验收标准是"磁盘上的文件里搜不到明文"，
     *                这条只能在真实文件系统上验证。
     */
    fun run(context: Context): List<SelfTestStep> {
        val steps = mutableListOf<SelfTestStep>()

        // ---- 第一项：量生产参数档在真机上的耗时 ----
        //
        // 必须跑两次、只采纳第二次的数字。
        // 第一次包含 BouncyCastle 类首次加载、Argon2 热循环的 JIT 解释执行、
        // 以及 64 MiB 大数组的首次分配 —— 实测在模拟器上这个冷启动值高达 29 秒，
        // 与用户日常解锁的体验毫无关系。第二次才是稳定值。
        steps += step("Argon2id 标准档派生（64 MiB / t=3 / p=2）") {
            val params = KdfParams.standard()
            Argon2Kdf.derive(MASTER.toCharArray(), params).close() // 预热，结果丢弃

            val started = System.currentTimeMillis()
            val kek = Argon2Kdf.derive(MASTER.toCharArray(), params)
            val elapsed = System.currentTimeMillis() - started
            try {
                true to "预热后单次派生 ${kek.size} 字节 KEK 耗时 ${elapsed} ms"
            } finally {
                kek.close()
            }
        }

        // 功能自检统一用最低参数档：KDF 参数只影响耗时、不影响正确性，
        // 用 32 MiB 档能把整个自检压到几秒内跑完。
        val fast = KdfParams.fast()

        var createdRef: CreationResult? = null
        steps += step("创建密钥环（主密码 + 假密码 + 恢复码）") {
            val result = VaultKeyring.create(
                primaryPassword = MASTER.toCharArray(),
                decoyPassword = DECOY.toCharArray(),
                params = fast,
            )
            createdRef = result
            true to buildString {
                append("槽位 ")
                append(result.keyring.slots.sortedBy { it.id }.joinToString { it.name })
                append("，恢复码 ")
                append(result.recoveryCode.length)
                append(" 字符（")
                append(RecoveryCode.GROUP_COUNT)
                append(" 组）")
            }
        }

        val vault = createdRef ?: return steps

        try {
            steps += step("主密码解锁并取回同一把 DEK") {
                val unlocked = vault.keyring.unlock(MASTER.toCharArray())
                    as? UnlockOutcome.Unlocked ?: return@step false to "解锁被拒绝"
                try {
                    val same = SecureBytes.constantTimeEquals(
                        unlocked.dek.expose(),
                        vault.primaryDek.expose(),
                    )
                    same to if (same) "解出的 DEK 与创建时一致" else "DEK 不一致"
                } finally {
                    unlocked.dek.close()
                }
            }

            steps += step("错误密码被拒绝") {
                val outcome = vault.keyring.unlock("definitely wrong password".toCharArray())
                val rejected = outcome is UnlockOutcome.Rejected
                rejected to if (rejected) "已拒绝，且未泄漏任何信息" else "竟然通过了"
            }

            steps += step("假密码进入诱饵库且 DEK 与真库不同") {
                val unlocked = vault.keyring.unlock(DECOY.toCharArray())
                    as? UnlockOutcome.Unlocked ?: return@step false to "假密码被拒绝"
                try {
                    val rightDomain = unlocked.domain == VaultDomain.DECOY
                    val differs = !SecureBytes.constantTimeEquals(
                        unlocked.dek.expose(),
                        vault.primaryDek.expose(),
                    )
                    (rightDomain && differs) to "域=${unlocked.domain}，DEK 与真库不同=$differs"
                } finally {
                    unlocked.dek.close()
                }
            }

            steps += step("诱饵库 DEK 无法解开真库内容") {
                val aad = Aad.entryField("self-test", "body")
                val sealed = AeadCipher.seal(vault.primaryDek, "真库机密内容".toByteArray(), aad)
                val decoy = vault.keyring.unlock(DECOY.toCharArray())
                    as? UnlockOutcome.Unlocked ?: return@step false to "假密码被拒绝"
                try {
                    val leaked = AeadCipher.open(decoy.dek, sealed.nonce, sealed.ciphertext, aad)
                    (leaked == null) to if (leaked == null) "两个库已物理隔离" else "诱饵库解出了真库内容"
                } finally {
                    decoy.dek.close()
                }
            }

            steps += step("恢复码还原真库 DEK") {
                val dek = vault.keyring.unlockWithRecoveryCode(vault.recoveryCode)
                    ?: return@step false to "恢复码无效"
                try {
                    val same = SecureBytes.constantTimeEquals(dek.expose(), vault.primaryDek.expose())
                    same to if (same) "与主密码解出的 DEK 一致" else "DEK 不一致"
                } finally {
                    dek.close()
                }
            }

            steps += step("恢复码容错（小写 + 连字符 + 把 0 抄成 o）") {
                val messy = RecoveryCode.formatForDisplay(vault.recoveryCode)
                    .lowercase()
                    .replace('0', 'o')
                val dek = vault.keyring.unlockWithRecoveryCode(messy)
                    ?: return@step false to "容错输入无法解锁"
                try {
                    val same = SecureBytes.constantTimeEquals(dek.expose(), vault.primaryDek.expose())
                    same to "抄错的字符已被自动纠正"
                } finally {
                    dek.close()
                }
            }

            steps += step("改主密码：新密码可用 / 旧密码失效 / DEK 不变") {
                val updated = vault.keyring.rewrapPrimary(NEW_MASTER.toCharArray(), vault.primaryDek)
                val oldRejected = updated.unlock(MASTER.toCharArray()) is UnlockOutcome.Rejected
                val viaNew = updated.unlock(NEW_MASTER.toCharArray())
                    as? UnlockOutcome.Unlocked ?: return@step false to "新密码无法解锁"
                try {
                    val same = SecureBytes.constantTimeEquals(
                        viaNew.dek.expose(),
                        vault.primaryDek.expose(),
                    )
                    (oldRejected && same) to "旧密码已失效=$oldRejected，DEK 未变=$same（已有内容无需重新加密）"
                } finally {
                    viaNew.dek.close()
                }
            }

            steps += step("改密码后恢复码依然可用") {
                val updated = vault.keyring.rewrapPrimary(NEW_MASTER.toCharArray(), vault.primaryDek)
                val dek = updated.unlockWithRecoveryCode(vault.recoveryCode)
                    ?: return@step false to "改密码让恢复码失效了"
                dek.close()
                true to "恢复码未受影响"
            }

            steps += step("密钥文件编解码往返（落盘格式）") {
                // 必须用"改过密码后"的密钥环：rewrapPrimary 返回的是新实例，
                // 原始 vault.keyring 里存的仍是旧密码的包裹，拿新密码去解必然失败。
                val rotated = vault.keyring.rewrapPrimary(NEW_MASTER.toCharArray(), vault.primaryDek)
                val bytes = VaultKeyFileCodec.encode(rotated)
                val restored = VaultKeyFileCodec.decode(bytes)
                val unlocked = restored.unlock(NEW_MASTER.toCharArray())
                    as? UnlockOutcome.Unlocked ?: return@step false to "往返后无法解锁"
                try {
                    val same = SecureBytes.constantTimeEquals(
                        unlocked.dek.expose(),
                        vault.primaryDek.expose(),
                    )
                    same to "${bytes.size} 字节，往返后可解锁=$same"
                } finally {
                    unlocked.dek.close()
                }
            }

            steps += step("密钥文件中不存在 DEK 明文") {
                val bytes = VaultKeyFileCodec.encode(vault.keyring)
                val found = contains(bytes, vault.primaryDek.expose())
                (!found) to if (found) "发现 DEK 明文！" else "${bytes.size} 字节全部为密文与公开参数"
            }

            steps += step("AES-GCM 篡改检测") {
                val aad = Aad.entryField("self-test", "tamper")
                val sealed = AeadCipher.seal(vault.primaryDek, "payload".toByteArray(), aad)
                val tampered = sealed.ciphertext.copyOf().also {
                    it[0] = (it[0].toInt() xor 0x01).toByte()
                }
                val result = AeadCipher.open(vault.primaryDek, sealed.nonce, tampered, aad)
                (result == null) to if (result == null) "篡改一位即被检出" else "篡改未被检出！"
            }
        } finally {
            vault.primaryDek.close()
            vault.decoyDek?.close()
        }

        // ---- M2：数据层：真实 SQLite + 真实文件 ----
        steps += runDataLayer(context)

        // 结果同时写入 logcat。
        //
        // 为什么需要这条通道：应用开着 FLAG_SECURE，uiautomator 抓不到界面树
        // （返回 "null root node"），所以在模拟器/真机上做自动验证时只能从日志读结果。
        // 只输出"通过/失败 + 检查项名称 + 耗时"，不含任何密码、密钥或用户数据。
        steps.forEach { step ->
            Log.i(
                TAG,
                "SELFTEST ${if (step.passed) "PASS" else "FAIL"} | ${step.name} | " +
                    "${step.detail} | ${step.millis}ms",
            )
        }
        Log.i(TAG, "SELFTEST SUMMARY ${steps.count { it.passed }}/${steps.size} passed")

        return steps
    }

    // ==================== 内部 ====================

    private fun step(name: String, block: () -> Pair<Boolean, String>): SelfTestStep {
        val started = System.currentTimeMillis()
        return try {
            val (passed, detail) = block()
            SelfTestStep(name, passed, detail, System.currentTimeMillis() - started)
        } catch (t: Throwable) {
            SelfTestStep(
                name = name,
                passed = false,
                detail = "抛出 ${t::class.java.simpleName}：${t.message ?: "(无消息)"}",
                millis = System.currentTimeMillis() - started,
            )
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    // ==================== M2：数据层（真实 SQLite + 真实文件）====================

    /**
     * 在设备的真实文件系统上跑一遍数据层。
     *
     * 与单元测试的分工是明确的：单元测试用内存实现验证**逻辑**
     * （域隔离、篡改检测、清单一致性），这里验证**落盘之后磁盘上到底有什么**。
     *
     * 差别在于证据的层级：单元测试证明的是"我们的代码按设计写出了密文"，
     * 而"全库无明文字段"这条验收标准的对象是 SQLite 文件本身。
     *
     * **落盘产物刻意保留不删**（运行前会清掉上一轮的）。这样任何人都可以
     * `adb exec-out run-as com.fpb.vault cat databases/selftest_vault.db > vault.db`
     * 把文件拉下来自己 grep —— 复核不必依赖本程序的结论。
     */
    private fun runDataLayer(context: Context): List<SelfTestStep> {
        val steps = mutableListOf<SelfTestStep>()
        val blobDir = File(context.cacheDir, SELF_TEST_BLOB_DIR)

        try {
            context.deleteDatabase(SELF_TEST_DB)
            blobDir.deleteRecursively()
        } catch (t: Throwable) {
            steps += SelfTestStep("清理上一轮自检产物", false, t.message ?: "失败", 0L)
            return steps
        }

        val created = VaultKeyring.create(
            primaryPassword = MASTER.toCharArray(),
            decoyPassword = DECOY.toCharArray(),
            params = KdfParams.fast(),
        )
        val blobs = try {
            FileBlobStore(blobDir)
        } catch (t: Throwable) {
            steps += SelfTestStep("创建附件目录", false, t.message ?: "失败", 0L)
            created.primaryDek.close()
            created.decoyDek?.close()
            return steps
        }

        var realImageBlob: String? = null
        var credentialId: String? = null
        var realNoteCount = 0

        try {
            // ---------- 阶段 1：写入并真实落盘 ----------
            var rows = SqliteRowStore(context, SELF_TEST_DB)
            steps += step("真实 SQLite：写入四类条目 + 一张图片并落盘") {
                val session = VaultSession(rows, blobs)
                val unlocked = created.keyring.unlock(MASTER.toCharArray())
                    as? UnlockOutcome.Unlocked ?: return@step false to "主密码无法解锁"
                session.unlock(unlocked)

                session.create(
                    NotePayload(
                        type = NoteType.TEXT,
                        title = MARKER_TEXT,
                        body = MARKER_BODY,
                        tags = listOf(MARKER_TAG),
                        createdAt = 0L,
                        updatedAt = 0L,
                    ),
                )

                val blobId = session.putImage(MARKER_IMAGE)
                realImageBlob = blobId
                session.create(
                    NotePayload(
                        type = NoteType.IMAGE,
                        title = "自检图片条目",
                        images = listOf(ImageRef(blobId, 1200, 900)),
                        createdAt = 0L,
                        updatedAt = 0L,
                    ),
                )
                session.create(
                    NotePayload(
                        type = NoteType.CHECKLIST,
                        title = "自检待办条目",
                        todos = listOf(TodoItem(MARKER_TODO, false)),
                        createdAt = 0L,
                        updatedAt = 0L,
                    ),
                )
                credentialId = session.create(
                    NotePayload(
                        type = NoteType.CREDENTIAL,
                        title = "自检密码条目",
                        fields = listOf(SecretField("密码", MARKER_SECRET, sensitive = true)),
                        createdAt = 0L,
                        updatedAt = 0L,
                    ),
                ).id

                realNoteCount = session.noteCount
                session.lock()
                // 关闭连接，确保数据真的落到文件里而不是停在页缓存中
                rows.close()
                true to "写入 $realNoteCount 条，图片 ${MARKER_IMAGE.size} 字节，连接已关闭"
            }

            // ---------- 阶段 2：独立扫描磁盘字节 ----------
            steps += step("磁盘上的 db / journal / 附件文件里搜不到任何明文") {
                val raw = readAllVaultBytes(context, blobDir)
                val textHits = listOf(
                    MARKER_TEXT, MARKER_BODY, MARKER_TAG, MARKER_TODO, MARKER_SECRET, "自检图片条目",
                ).filter { contains(raw, it.toByteArray(Charsets.UTF_8)) }
                val imageLeaked = contains(raw, MARKER_IMAGE)

                when {
                    textHits.isNotEmpty() -> false to "发现明文泄漏：${textHits.joinToString("、")}"
                    imageLeaked -> false to "图片原始字节出现在磁盘上"
                    raw.isEmpty() -> false to "没有扫描到任何落盘字节，验证不成立"
                    else -> true to "扫描 ${raw.size} 字节，6 个文本标记与图片字节全部 0 命中"
                }
            }

            // ---------- 阶段 3：重新打开 ----------
            rows = SqliteRowStore(context, SELF_TEST_DB)
            var realSession: VaultSession? = null
            steps += step("重新打开后条目与图片完整恢复") {
                val session = VaultSession(rows, blobs)
                val unlocked = created.keyring.unlock(MASTER.toCharArray())
                    as? UnlockOutcome.Unlocked ?: return@step false to "重新解锁失败"
                val report = session.unlock(unlocked)
                realSession = session

                val restored = realImageBlob?.let { session.image(it) }
                val imageOk = restored != null && restored.contentEquals(MARKER_IMAGE)
                val countOk = report.noteCount == realNoteCount
                (report.isClean && countOk && imageOk) to
                    "${report.noteCount} 条恢复（预期 $realNoteCount），问题项 ${report.problemCount}，图片还原=$imageOk"
            }

            // ---------- 阶段 4：真库 / 诱饵库共用一张表 ----------
            steps += step("真库与诱饵库混在同一张表里但互相看不见") {
                val decoySession = VaultSession(rows, blobs)
                val decoyUnlocked = created.keyring.unlock(DECOY.toCharArray())
                    as? UnlockOutcome.Unlocked ?: return@step false to "假密码无法解锁"
                val decoyReport = decoySession.unlock(decoyUnlocked)
                if (decoyReport.noteCount != 0) {
                    return@step false to "诱饵库竟然看到了 ${decoyReport.noteCount} 条真库内容"
                }
                decoySession.create(
                    NotePayload(
                        type = NoteType.TEXT,
                        title = "诱饵自检条目",
                        createdAt = 0L,
                        updatedAt = 0L,
                    ),
                )
                val decoySees = decoySession.noteCount
                decoySession.lock()

                val realAgain = VaultSession(rows, blobs)
                val realUnlocked = created.keyring.unlock(MASTER.toCharArray())
                    as? UnlockOutcome.Unlocked ?: return@step false to "真库重新解锁失败"
                realAgain.unlock(realUnlocked)
                val realSees = realAgain.noteCount
                realAgain.lock()

                val totalRows = rows.loadAll().size
                (decoySees == 1 && realSees == realNoteCount) to
                    "诱饵库看到 $decoySees 条，真库看到 $realSees 条，" +
                    "而表里实际混存 $totalRows 行（2 份清单 + 5 条笔记）"
            }

            // ---------- 阶段 5：自动锁定 ----------
            steps += step("自动锁定：超时后密钥清零且读取被拒") {
                val session = realSession ?: return@step false to "缺少已解锁的会话"
                val t0 = 1_000L
                session.onBackgrounded(t0)
                session.tick(t0 + 59_999L)
                val stillOpen = session.isUnlocked
                session.tick(t0 + 60_000L)
                val locked = !session.isUnlocked
                val readBlocked = try {
                    session.notes()
                    false
                } catch (e: IllegalStateException) {
                    true
                }
                (stillOpen && locked && readBlocked) to
                    "未超时保持解锁=$stillOpen，超时后已锁定=$locked，锁定后读取被拒=$readBlocked"
            }

            // ---------- 阶段 6：篡改检测 ----------
            steps += step("篡改数据库中一条密文后，它被标记为不可读而非静默消失") {
                val id = credentialId ?: return@step false to "缺少条目 id"
                val row = rows.load(id) ?: return@step false to "数据库中找不到该行"
                row.ciphertext[0] = (row.ciphertext[0].toInt() xor 0x01).toByte()
                rows.upsert(row)
                rows.close()

                val reopened = SqliteRowStore(context, SELF_TEST_DB)
                try {
                    val session = VaultSession(reopened, blobs)
                    val unlocked = created.keyring.unlock(MASTER.toCharArray())
                        as? UnlockOutcome.Unlocked ?: return@step false to "解锁失败"
                    val report = session.unlock(unlocked)
                    session.lock()
                    (report.unreadableRows == 1 && report.noteCount == realNoteCount - 1) to
                        "不可读 ${report.unreadableRows} 条，其余 ${report.noteCount} 条正常" +
                        "（预期 ${realNoteCount - 1}）"
                } finally {
                    reopened.close()
                }
            }
        } finally {
            created.primaryDek.close()
            created.decoyDek?.close()
        }

        // 保留产物，供独立复核
        val dbFile = context.getDatabasePath(SELF_TEST_DB)
        Log.i(TAG, "SELFTEST ARTIFACT db=${dbFile.absolutePath} bytes=${dbFile.length()}")
        Log.i(
            TAG,
            "SELFTEST ARTIFACT blobs=${blobDir.absolutePath} files=${blobDir.listFiles()?.size ?: 0}",
        )

        return steps
    }

    /** 把数据库文件、它的 journal / wal、以及全部附件文件读成一段连续字节。 */
    private fun readAllVaultBytes(context: Context, blobDir: File): ByteArray {
        val out = ByteArrayOutputStream()
        val dbPath = context.getDatabasePath(SELF_TEST_DB).path
        listOf(dbPath, "$dbPath-journal", "$dbPath-wal").forEach { path ->
            val file = File(path)
            if (file.isFile) out.write(file.readBytes())
        }
        blobDir.listFiles()?.forEach { if (it.isFile) out.write(it.readBytes()) }
        return out.toByteArray()
    }

    // 自检用的明文标记。取值刻意怪异，避免与任何正常运行产生的字节偶然相同。
    private const val MARKER_TEXT = "自检明文标记-ALPHA-741852"
    private const val MARKER_BODY = "自检正文标记-BRAVO-963741"
    private const val MARKER_TAG = "自检标签-CHARLIE"
    private const val MARKER_TODO = "自检待办-DELTA"
    private const val MARKER_SECRET = "自检密码-ECHO-13579"
    private val MARKER_IMAGE = "SELFTEST-IMAGE-MARKER-FOXTROT-528491".toByteArray()

    private const val SELF_TEST_DB = "selftest_vault.db"
    private const val SELF_TEST_BLOB_DIR = "selftest-vault"
}
