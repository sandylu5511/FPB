# -*- coding: utf-8 -*-
"""对照实验：在基线副本中逐条退回第二轮审核的 6 处修复。

每处替换都必须命中，否则脚本报错退出 —— 防止"以为退回了实际没退"，
让对照实验失去意义。模式均取自真实源文件逐字内容。
"""
import io, sys

BASE = r"D:\MixiaVault-baseline2"

REVERTS = {
    r"\app\src\main\java\com\mixia\app\codec\NoteIndexCodec.kt": [(
        """        // 与解码侧同源同律：解码会拒绝的形状，编码就不许产出。
        // 这不是对称性洁癖：清单是"哪些行属于本域"的唯一凭证，一旦写出
        // 一份含非法 id 的清单，下一次解锁就判定"清单不可读"，整个库锁死。
        ids.forEach { id ->
            require(RowIds.isValid(id)) { "清单 id 形状非法（应为 32 位十六进制）：$id" }
        }
""",
        "",
    )],
    r"\app\src\main\java\com\mixia\app\codec\NoteCodec.kt": [(
        """                // blobId 最终会参与拼接文件路径。校验前移到编码这一侧，
                // 让"非法 blobId 入库"在任何路径上都到不了磁盘。
                require(com.mixia.app.data.RowIds.isValid(it.blobId)) {
                    "图片引用的 blobId 形状非法（应为 32 位十六进制）：${it.blobId}"
                }
""",
        "",
    ), (
        """            // 与编码侧对称：blobId 会参与拼接文件路径，形状不对的引用
            // 应当让整条笔记被标记为"不可读"（报告给用户），
            // 而不是把脏引用放进内存索引、指望每个使用点各自记得防御。
            if (!com.mixia.app.data.RowIds.isValid(blobId)) {
                throw IllegalArgumentException("blobId 形状非法")
            }
""",
        "",
    )],
    r"\app\src\main\java\com\mixia\app\crypto\VaultKeyFile.kt": [(
        """
            // 尾部必须干净。NoteCodec 与 NoteIndexCodec 都有这条检查，唯独这里漏了。
            // 密钥文件将来会随备份包导入，输入不可信 —— 多余字节通常意味着
            // 文件被拼接/截断后补齐过、或写入方与读取方对格式的理解已经不一致。
            // 此时静默接受，等于放弃格式漂移的最后一道检出机会。
            if (input.available() != 0) {
                throw VaultKeyFileException(
                    "密钥文件尾部存在 ${input.available()} 字节多余数据（可能被篡改或格式不匹配）",
                )
            }
""",
        "",
    )],
    r"\app\src\main\java\com\mixia\app\session\VaultSession.kt": [(
        """    /**
     * 清单里存在、但行缺失或解不开的条目 id。
     *
     * 这些 id **必须跟着清单一起落盘**，否则任何一次写入都会把它们从清单里抹掉：
     * 问题报告从此消失（"笔记消失变成无声的事"），而这些行也就永久失去了
     * "属于本域"的唯一凭证 —— 既不能归属、不能清理、也不能被另一个库认领。
     * 保留它们，让每次解锁都继续把问题报出来，直到存储层真正修复。
     */
    private val brokenIds = LinkedHashSet<String>()

""",
        "",
    ), (
        """        index.clear()
        searchCache.clear()
        brokenIds.clear()
        backgroundedAt = null
        loadReport = LoadReport.fresh()
""",
        """        index.clear()
        searchCache.clear()
        backgroundedAt = null
        loadReport = LoadReport.fresh()
""",
    ), (
        """    private fun loadIndex(): LoadReport {
        index.clear()
        searchCache.clear()
        brokenIds.clear()
""",
        """    private fun loadIndex(): LoadReport {
        index.clear()
        searchCache.clear()
""",
    ), (
        """            if (row == null) {
                missing++
                // 记入 brokenIds，随下一次写入一起保留在清单里。
                // 若不保留，这次解锁报告的"缺失/不可读"会在任何一次写入后
                // 被静默遗忘，且这些行从此失去域归属凭证（见字段说明）。
                brokenIds.add(id)
                continue
            }
            val payload = readPayload(id, row)
            if (payload == null) {
                unreadable++
                brokenIds.add(id)
                continue
            }
""",
        """            if (row == null) {
                missing++
                continue
            }
            val payload = readPayload(id, row)
            if (payload == null) {
                unreadable++
                continue
            }
""",
    ), (
        """        val manifestId = manifestRowId()
        // 清单 = 可读条目 + 仍然缺失/不可读的条目。后者必须保留：
        // 它们是"这行属于本域"的唯一记录，也是每次解锁持续报告问题的依据。
        val listBytes = NoteIndexCodec.encode(index.keys.toList() + brokenIds)
""",
        """        val manifestId = manifestRowId()
        val listBytes = NoteIndexCodec.encode(index.keys.toList())
""",
    ), (
        """    private fun requireManifestCapacity(adding: Int) {
        // 清单长度既含可读条目也含 brokenIds（它们随清单一起落盘），
        // 超限必须发生在动任何数据之前，而不是清单写入的事务里。
        val next = index.size + brokenIds.size + adding
        if (next > NoteIndexCodec.MAX_ENTRIES) {
            throw IllegalStateException(
                "条目数量已达上限 ${NoteIndexCodec.MAX_ENTRIES}，无法继续新增" +
                    "（当前 ${index.size} 条，另有 ${brokenIds.size} 条待修复记录）",
            )
        }
    }
""",
        """    private fun requireManifestCapacity(adding: Int) {
        val next = index.size + adding
        if (next > NoteIndexCodec.MAX_ENTRIES) {
            throw IllegalStateException(
                "条目数量已达上限 ${NoteIndexCodec.MAX_ENTRIES}，无法继续新增（当前 ${index.size} 条）",
            )
        }
    }
""",
    ), (
        """        // 不可逆数据丢失必须"大声失败"。128 位随机 id 碰撞概率约等于零，
        // 但自定义 idFactory（测试、将来的备份导入）一旦碰撞，静默覆盖的
        // 是旧条目的内存索引与磁盘密文 —— 原内容再也找不回来。
        // 同理也挡住与清单行的碰撞：writeNote 一旦写到清单行 id 上，
        // 被覆盖的就是"哪些笔记存在"这份唯一凭证。
        check(id !in index) { "生成的条目 id 与现有条目冲突，拒绝覆盖：$id" }
        check(id != manifestRowId()) { "生成的条目 id 与清单行冲突，拒绝覆盖：$id" }

""",
        "",
    ), (
        """        // 上限兜底：M3 的图片管线（长边 2048 / JPEG q85）正常产出约 1~3 MB，
        // 这里防的是"上游忘了压缩"—— 解密是解锁时一次性全量进行的，
        // 一张不受限的巨图会拖垮启动，甚至先让低端机 OOM。
        require(plaintext.size <= MAX_IMAGE_PLAINTEXT_BYTES) {
            "图片明文 ${plaintext.size} 字节超过上限 $MAX_IMAGE_PLAINTEXT_BYTES 字节，请先压缩后入库"
        }
""",
        "",
    )],
}

for rel, pairs in REVERTS.items():
    path = BASE + rel
    with io.open(path, "r", encoding="utf-8") as f:
        content = f.read()
    for old, new in pairs:
        if old not in content:
            print("REVERT MISS: %s <<< %r ...>>>" % (path, old[:60]))
            sys.exit(1)
        content = content.replace(old, new, 1)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(content)
    print("reverted:", rel)

print("ALL REVERTS APPLIED")
