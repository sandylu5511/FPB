package com.fpb.vault.codec

import com.fpb.vault.model.ImageRef
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.model.SecretField
import com.fpb.vault.model.TodoItem
import com.fpb.vault.model.VideoRef
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

/**
 * 笔记明文的二进制编解码器。
 *
 * ## 为什么不用 JSON
 *
 * 1. **体积**：这段字节会被整段加密后落盘，也决定了每一条记录在列表加载时要解密的数据量。
 *    二进制编码比 JSON 小 30%~50%。
 * 2. **确定性**：同一个 [NotePayload] 必须每次编码出**逐字节相同**的结果，
 *    否则"内容没改但密文变了"会让后续做增量同步变得无从判断。
 * 3. **零依赖**：不引入 kotlinx.serialization，也就没有反射与 R8 保留规则的问题 ——
 *    对一个靠"代码不被误裁剪"活着的加密应用来说，少一个变量就是少一个风险点。
 *
 * ## 格式（全部大端序）
 *
 * ```
 * magic        4 字节  "MXN1"
 * version      1 字节  1 或 2（见下）
 * type         1 字节  1=TEXT 2=IMAGE 3=CHECKLIST 4=CREDENTIAL 5=VIDEO
 * flags        1 字节  bit0 = 收藏
 * createdAt    8 字节  毫秒时间戳
 * updatedAt    8 字节  毫秒时间戳
 * title        string
 * body         string
 * tags         list<string>
 * images       list<string blobId, int32 w, int32 h>
 * todos        list<string text, byte done>
 * fields       list<string label, string value, byte sensitive>
 * videos       list<string blobId, int32 w, int32 h, int64 durationMs>   ← 仅 v2
 * ```
 *
 * `string` = `int32 字节长度` + `UTF-8 字节`；`list` = `int32 元素个数` + 元素。
 *
 * ## 为什么是 v2 而不是"v1 再加一个字段"
 *
 * [videos] 是**追加在流末尾**的。如果版本号不变，老版本读到它不认识的那一段时，
 * 只会在末尾那道"尾部必须干净"的校验上失败 —— 于是**已经装机的老版本**打开自己的库
 * 会发现记录全变成"待修复"。升版本号让老版本能明确说出"这条记录的格式版本比应用新"，
 * 而不是把它当成损坏。
 *
 * 反过来，**新版本必须能读 v1**：库里已经存在的老记录全是 v1，
 * 读到它们时 [videos] 一律按空表处理。这条兼容不是可选项 ——
 * 少了它就是"升级应用之后所有历史记录消失"。
 *
 * 注意"尾部必须干净"这条校验对 v1 与 v2 **都保留**：它挡的是"写入方与读取方
 * 对格式的理解已经不一致"这种更隐蔽的情况，不能因为引入了版本分支就把它放松成
 * "多余的字节就算了"。
 *
 * ## 解码器的立场
 *
 * 解码的输入是"刚刚通过 AES-GCM 认证的明文"，但它**仍然可能不是合法编码**：
 * 密钥正确但数据是用旧版本格式写的、或者是我们自己的编码器有 bug。
 * 因此解码器对每一处长度都做上界检查，任何异常一律返回 `null`，
 * 绝不抛异常给上层，也绝不做"尽力恢复"——半个解析出来的笔记比没有更危险。
 */
object NoteCodec {

    /** 当前写入的版本。 */
    const val SCHEMA_VERSION = 2

    /** 还能读的最老版本。 */
    private const val LEGACY_VERSION = 1

    private val MAGIC = byteArrayOf(0x4D, 0x58, 0x4E, 0x31) // "MXN1"

    private const val CODE_TEXT = 1
    private const val CODE_IMAGE = 2
    private const val CODE_CHECKLIST = 3
    private const val CODE_CREDENTIAL = 4
    private const val CODE_VIDEO = 5

    private const val FLAG_FAVORITE = 0x01

    /** 单个字符串（标题/正文/字段值）允许的最大 UTF-8 字节数。与模型层的字符上限留足余量。 */
    private const val MAX_STRING_BYTES = 4 * 1024 * 1024

    private const val MAX_LIST_ELEMENTS = 4096

    fun encode(payload: NotePayload): ByteArray {
        val out = ByteArrayOutputStream(ESTIMATED_BYTES)
        DataOutputStream(out).use { d ->
            d.write(MAGIC)
            d.writeByte(SCHEMA_VERSION)
            d.writeByte(typeCode(payload.type))
            d.writeByte(if (payload.favorite) FLAG_FAVORITE else 0)
            d.writeLong(payload.createdAt)
            d.writeLong(payload.updatedAt)

            writeString(d, payload.title)
            writeString(d, payload.body)

            d.writeInt(payload.tags.size)
            payload.tags.forEach { writeString(d, it) }

            d.writeInt(payload.images.size)
            payload.images.forEach {
                // blobId 最终会参与拼接文件路径。校验前移到编码这一侧，
                // 让"非法 blobId 入库"在任何路径上都到不了磁盘。
                require(com.fpb.vault.data.RowIds.isValid(it.blobId)) {
                    "图片引用的 blobId 形状非法（应为 32 位十六进制）：${it.blobId}"
                }
                writeString(d, it.blobId)
                d.writeInt(it.width)
                d.writeInt(it.height)
            }

            d.writeInt(payload.todos.size)
            payload.todos.forEach {
                writeString(d, it.text)
                d.writeBoolean(it.done)
            }

            d.writeInt(payload.fields.size)
            payload.fields.forEach {
                writeString(d, it.label)
                writeString(d, it.value)
                d.writeBoolean(it.sensitive)
            }

            // videos 追加在**所有老字段之后**。这是它能在 v1 基础上安全扩展的前提：
            // 老版本的读取顺序与偏移完全不受影响，只是它会在末尾的"尾部必须干净"上失败。
            d.writeInt(payload.videos.size)
            payload.videos.forEach {
                require(com.fpb.vault.data.RowIds.isValid(it.blobId)) {
                    "视频引用的 blobId 形状非法（应为 32 位十六进制）：${it.blobId}"
                }
                writeString(d, it.blobId)
                d.writeInt(it.width)
                d.writeInt(it.height)
                d.writeLong(it.durationMs)
            }
        }
        return out.toByteArray()
    }

    /**
     * 解码。任何不合规之处都返回 `null`（含"尾部有多余字节"，那通常意味着格式版本不匹配）。
     */
    fun decode(bytes: ByteArray): NotePayload? = try {
        decodeOrThrow(bytes)
    } catch (e: Exception) {
        // 包含 EOFException / 越界 / 数值超限。故意吞掉具体类型：
        // 上层只需要知道"这条记录读不出来"，异常细节反而可能被写进日志导致明文泄漏。
        null
    }

    private fun decodeOrThrow(bytes: ByteArray): NotePayload? {
        val input = DataInputStream(ByteArrayInputStream(bytes))

        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) return null

        val version = input.readUnsignedByte()
        // 只接受"能读的版本区间"。上界不是 [SCHEMA_VERSION] 而是个明确的白名单：
        // 将来写到 v3 时，这个判断要显式改一次，而不是靠"版本号比我大所以拒绝"这种
        // 猜出来的规则 —— 那种写法在版本号回退时会把可读的数据判成不可读。
        if (version != LEGACY_VERSION && version != SCHEMA_VERSION) return null

        val type = typeOf(input.readUnsignedByte()) ?: return null
        val flags = input.readUnsignedByte()
        val createdAt = input.readLong()
        val updatedAt = input.readLong()

        val title = readString(input)
        val body = readString(input)
        val tags = readList(input) { readString(input) }

        val images = readList(input) {
            val blobId = readString(input)
            val width = input.readInt()
            val height = input.readInt()
            if (width < 0 || height < 0) throw IllegalArgumentException("图片尺寸为负")
            // 与编码侧对称：blobId 会参与拼接文件路径，形状不对的引用
            // 应当让整条笔记被标记为"不可读"（报告给用户），
            // 而不是把脏引用放进内存索引、指望每个使用点各自记得防御。
            if (!com.fpb.vault.data.RowIds.isValid(blobId)) {
                throw IllegalArgumentException("blobId 形状非法")
            }
            ImageRef(blobId, width, height)
        }

        val todos = readList(input) { TodoItem(readString(input), input.readBoolean()) }

        val fields = readList(input) {
            SecretField(
                label = readString(input),
                value = readString(input),
                sensitive = input.readBoolean(),
            )
        }

        // v1 里没有这一段，所以**必须按版本分流**而不是"试着读一读、失败就当空"：
        // 后者会把"v1 记录恰好以合法块开头的尾部字节"读成视频引用，
        // 也会把真正的损坏悄悄咽掉。
        val videos = if (version >= 2) {
            readList(input) {
                val blobId = readString(input)
                val width = input.readInt()
                val height = input.readInt()
                val durationMs = input.readLong()
                if (width < 0 || height < 0) throw IllegalArgumentException("视频尺寸为负")
                if (durationMs < 0) throw IllegalArgumentException("视频时长为负")
                if (!com.fpb.vault.data.RowIds.isValid(blobId)) {
                    throw IllegalArgumentException("blobId 形状非法")
                }
                VideoRef(blobId, width, height, durationMs)
            }
        } else {
            emptyList()
        }

        // 尾部必须干净。多出来的字节说明写入方与读取方对格式的理解已经不一致，
        // 此时"忽略多余部分"会把格式错位伪装成正常读取。
        if (input.available() != 0) return null

        return NotePayload(
            type = type,
            title = title,
            body = body,
            tags = tags,
            createdAt = createdAt,
            updatedAt = updatedAt,
            favorite = flags and FLAG_FAVORITE != 0,
            images = images,
            videos = videos,
            todos = todos,
            fields = fields,
        )
    }

    // ==================== 内部 ====================

    private inline fun <T> readList(input: DataInputStream, readElement: () -> T): List<T> {
        val count = input.readInt()
        if (count < 0 || count > MAX_LIST_ELEMENTS) {
            throw IllegalArgumentException("列表元素个数越界: $count")
        }
        // 每个元素至少要占 1 字节，因此"元素个数 > 剩余字节数"必然不成立，可以提前拒绝。
        // 这条校验不替代上界检查，而是让"剩余数据很少却声明了很多元素"这种输入
        // 在分配任何容器之前就被挡住。
        if (count > input.available()) {
            throw IllegalArgumentException("列表元素个数超出剩余数据: $count")
        }
        return ArrayList<T>(minOf(count, 64)).apply {
            repeat(count) { add(readElement()) }
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_STRING_BYTES) { "单个字段超过 $MAX_STRING_BYTES 字节" }
        out.writeInt(encoded.size)
        out.write(encoded)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw IllegalArgumentException("字符串长度越界: $length")
        }
        // 长度字段声称的字节数不能超过流里剩下的字节数。
        //
        // 少了这一步，一段几十字节的输入就能让我们先按长度字段分配最多 4 MB 的数组，
        // 再在 readFully 里失败。数据本身经过 GCM 认证，外部改不了，
        // 但这道校验的代价是零，而它挡住的是"自己的编码 bug 或一次不当的数据迁移"
        // 变成一次内存放大甚至 OOM 的可能。
        if (length > input.available()) {
            throw IllegalArgumentException("字符串长度超出剩余数据: $length（剩余 ${input.available()}）")
        }
        val buffer = ByteArray(length)
        input.readFully(buffer)
        return String(buffer, StandardCharsets.UTF_8)
    }

    private fun typeCode(type: NoteType): Int = when (type) {
        NoteType.TEXT -> CODE_TEXT
        NoteType.IMAGE -> CODE_IMAGE
        NoteType.CHECKLIST -> CODE_CHECKLIST
        NoteType.CREDENTIAL -> CODE_CREDENTIAL
        NoteType.VIDEO -> CODE_VIDEO
    }

    private fun typeOf(code: Int): NoteType? = when (code) {
        CODE_TEXT -> NoteType.TEXT
        CODE_IMAGE -> NoteType.IMAGE
        CODE_CHECKLIST -> NoteType.CHECKLIST
        CODE_CREDENTIAL -> NoteType.CREDENTIAL
        CODE_VIDEO -> NoteType.VIDEO
        else -> null
    }

    private const val ESTIMATED_BYTES = 512
}
