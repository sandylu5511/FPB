package com.fpb.vault.codec

import com.fpb.vault.model.ImageRef
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.model.SecretField
import com.fpb.vault.model.TodoItem
import com.fpb.vault.model.VideoRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Random

class NoteCodecTest {

    private val t0 = 1_770_000_000_000L

    private fun note(
        type: NoteType = NoteType.TEXT,
        title: String = "标题",
        body: String = "正文",
        tags: List<String> = emptyList(),
        images: List<ImageRef> = emptyList(),
        videos: List<VideoRef> = emptyList(),
        todos: List<TodoItem> = emptyList(),
        fields: List<SecretField> = emptyList(),
        favorite: Boolean = false,
    ) = NotePayload(
        type = type,
        title = title,
        body = body,
        tags = tags,
        createdAt = t0,
        updatedAt = t0 + 1234,
        favorite = favorite,
        images = images,
        videos = videos,
        todos = todos,
        fields = fields,
    )

    private fun roundTrip(original: NotePayload): NotePayload {
        val decoded = NoteCodec.decode(NoteCodec.encode(original))
        assertNotNull("解码失败：${original.type}", decoded)
        return decoded!!
    }

    @Test
    fun `文字笔记往返一致`() {
        val original = note(title = "护照与签证", body = "护照号 E12345678\n2028-03 到期")
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `图片笔记往返一致`() {
        val original = note(
            type = NoteType.IMAGE,
            images = listOf(
                ImageRef("a".repeat(32), 2048, 1536),
                ImageRef("b".repeat(32), 640, 480),
            ),
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `待办清单往返一致`() {
        val original = note(
            type = NoteType.CHECKLIST,
            todos = listOf(TodoItem("宽带迁移", true), TodoItem("水电过户", false)),
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `账号密码往返一致`() {
        val original = note(
            type = NoteType.CREDENTIAL,
            fields = listOf(
                SecretField("用户名", "admin", sensitive = false),
                SecretField("密码", "Tp8\$kQ2mZx9v", sensitive = true),
            ),
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `收藏标记往返一致`() {
        assertTrue(roundTrip(note(favorite = true)).favorite)
        assertFalse(roundTrip(note(favorite = false)).favorite)
    }

    @Test
    fun `空内容与空列表往返一致`() {
        val original = note(title = "", body = "")
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `中文表情与换行往返一致`() {
        val original = note(
            title = "🔐 私密 · 备忘",
            body = "第一行\r\n第二行\u2028第三行\ttab",
            tags = listOf("证件", "账号", "备注"),
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `同一内容编码两次字节完全相同`() {
        // 确定性是后续做"内容没变就不必重写密文"的前提，也方便用哈希比对做变更检测。
        val a = note(title = "确定性", body = "同样的输入必须得到同样的字节")
        assertTrue(
            "编码结果不确定 —— 同一个 payload 两次编码不一致",
            NoteCodec.encode(a).contentEquals(NoteCodec.encode(a)),
        )
    }

    @Test
    fun `magic 被破坏后解码失败`() {
        val bytes = NoteCodec.encode(note())
        bytes[0] = 'X'.code.toByte()
        assertNull(NoteCodec.decode(bytes))
    }

    @Test
    fun `尾部多余字节导致解码失败`() {
        // 多出的尾巴说明写入方与读取方对格式的理解已经不一致。
        // 若这里"宽容地忽略掉"，格式错位就会被伪装成一次正常读取。
        val bytes = NoteCodec.encode(note()) + byteArrayOf(0, 0, 0, 0)
        assertNull(NoteCodec.decode(bytes))
    }

    @Test
    fun `未知版本号返回 null`() {
        val bytes = NoteCodec.encode(note())
        bytes[4] = 99
        assertNull(NoteCodec.decode(bytes))
    }

    @Test
    fun `非法类型码返回 null`() {
        val bytes = NoteCodec.encode(note())
        bytes[5] = 99
        assertNull(NoteCodec.decode(bytes))
    }

    @Test
    fun `截断的字节返回 null`() {
        val bytes = NoteCodec.encode(note(body = "一段足够长的正文，用来确保截断点落在数据中间"))
        assertNull(NoteCodec.decode(bytes.copyOf(bytes.size - 5)))
    }

    @Test
    fun `超大长度声明不会分配内存而是直接拒绝`() {
        val bytes = NoteCodec.encode(note())
        // 标题长度字段位于 4+1+1+1+8+8 = 23
        bytes[23] = 0x7F
        bytes[24] = 0xFF.toByte()
        bytes[25] = 0xFF.toByte()
        bytes[26] = 0xFF.toByte()
        assertNull(NoteCodec.decode(bytes))
    }

    @Test
    fun `随机字节不会让解码器抛异常`() {
        val random = Random(20260914)
        repeat(300) {
            val length = random.nextInt(120) + 8
            val bytes = ByteArray(length).also { random.nextBytes(it) }
            // 一半用合法 magic 开头，逼解码器真正往深处走
            if (it % 2 == 0) {
                bytes[0] = 0x4D
                bytes[1] = 0x58
                bytes[2] = 0x4E
                bytes[3] = 0x31
            }
            // 不抛异常即通过；返回 payload 或 null 都可以接受
            NoteCodec.decode(bytes)
        }
    }

    @Test
    fun `解码器不接受清单格式的字节`() {
        // 两个格式的 magic 不同，互不认账。防止将来某处传错对象却静默成功。
        val indexBytes = NoteIndexCodec.encode(listOf("a".repeat(32)))
        assertNull(NoteCodec.decode(indexBytes))
    }

    // ==================== 视频字段与版本兼容 ====================

    @Test
    fun `视频笔记往返一致`() {
        val original = note(
            type = NoteType.VIDEO,
            videos = listOf(
                VideoRef("a".repeat(32), 1080, 1920, 12_345L),
                // 时长取 0 = "容器里没写时长"，必须能原样往返（界面据此不显示 0:00）
                VideoRef("b".repeat(32), 3840, 2160, 0L),
            ),
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `图片与视频可以共存于同一条记录`() {
        val original = note(
            type = NoteType.IMAGE,
            images = listOf(ImageRef("a".repeat(32), 2048, 1536)),
            videos = listOf(VideoRef("b".repeat(32), 1080, 1920, 5_000L)),
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `v2 只是在 v1 之后追加，老字段的位置一个都没动`() {
        // 这条验的是"追加在流末尾"这个前提本身。新字段若插进了任何老字段**之间**，
        // 老版本读到的就是错位的数据 —— 而它读出来的东西看起来仍然"像"一条正常记录。
        //
        // 两条记录**除 videos 之外每一个字段都相同**（类型、标题、标签都要一样），
        // 否则字节差异会来自别处，这条断言就什么都没验到。
        val withoutVideo = note(
            type = NoteType.IMAGE,
            title = "同一份内容",
            tags = listOf("甲", "乙"),
            images = listOf(ImageRef("a".repeat(32), 2048, 1536)),
        )
        val withVideo = note(
            type = NoteType.IMAGE,
            title = "同一份内容",
            tags = listOf("甲", "乙"),
            images = listOf(ImageRef("a".repeat(32), 2048, 1536)),
            videos = listOf(VideoRef("c".repeat(32), 1080, 1920, 1L)),
        )
        val shorter = NoteCodec.encode(withoutVideo)
        val longer = NoteCodec.encode(withVideo)

        // 视频那一段的**元素个数**（4 字节）之前，两条编码必须逐字节相同。
        // 注意不能直接比 "shorter 是 longer 的前缀"：那 4 个字节在两边分别是 0 和 1，
        // 所以严格前缀关系不成立 —— 这种断言写得太顺口，很容易变成一个恒假的判据。
        val common = shorter.size - 4
        assertArrayEquals(
            "视频之前的全部老字段必须逐字节相同",
            shorter.copyOf(common),
            longer.copyOf(common),
        )
        // 而"空的视频表"与"一个视频引用"只差那一个引用本身：
        // 4（blobId 长度）+ 32（blobId）+ 4（宽）+ 4（高）+ 8（时长）
        assertEquals(
            "尾部新增的只能是那一个视频引用",
            VIDEO_ENTRY_BYTES,
            longer.size - shorter.size,
        )
    }

    @Test
    fun `仍能读出 v1 记录且视频字段为空表`() {
        // **少了这条就是"升级应用之后所有历史记录消失"**：库里已存在的老记录全是 v1，
        // 它们没有 videos 那一段，而末尾仍然要求"干净"。
        val legacy = encodeV1(
            typeCode = 2, // IMAGE
            title = "老照片",
            body = "v1 时代写下的正文",
            imageBlobIds = listOf("d".repeat(32)),
        )

        val decoded = NoteCodec.decode(legacy)

        assertNotNull("v1 记录必须还能读", decoded)
        assertEquals(NoteType.IMAGE, decoded!!.type)
        assertEquals("老照片", decoded.title)
        assertEquals("v1 时代写下的正文", decoded.body)
        assertEquals(listOf("d".repeat(32)), decoded.images.map { it.blobId })
        assertEquals(V1_TIME, decoded.createdAt)
        assertTrue("v1 没有视频，必须按空表处理", decoded.videos.isEmpty())
    }

    @Test
    fun `v1 记录尾部多变出字节仍然被拒`() {
        // 版本分流**不能**放松"尾部必须干净"：它挡的是"写入方与读取方对格式的理解
        // 已经不一致"这种更隐蔽的情况，而 v1 同样会遇上。
        val legacy = encodeV1(typeCode = 1, title = "文字", body = "", imageBlobIds = emptyList())
        assertNull(NoteCodec.decode(legacy + byteArrayOf(0)))
    }

    @Test
    fun `未知版本号被拒而不是尽力解析`() {
        val bytes = NoteCodec.encode(note())
        bytes[4] = 9
        assertNull(NoteCodec.decode(bytes))
    }

    @Test
    fun `v2 里把版本号改回 v1 会让尾部校验失败`() {
        // 反方向的一致性：声称自己是 v1 却带着 v2 的尾巴，必须失败。
        // 否则"版本号"这个字段就成了一个可以随便写的东西。
        val bytes = NoteCodec.encode(
            note(type = NoteType.VIDEO, videos = listOf(VideoRef("e".repeat(32), 1080, 1920, 1L))),
        )
        bytes[4] = 1
        assertNull(NoteCodec.decode(bytes))
    }

    @Test
    fun `视频引用的 blobId 形状非法时编码直接失败`() {
        val bad = note(type = NoteType.VIDEO, videos = listOf(VideoRef("../databases/vault", 1, 1, 1L)))
        assertThrows(IllegalArgumentException::class.java) { NoteCodec.encode(bad) }
    }

    @Test
    fun `视频尺寸为负时解码失败`() {
        val bytes = NoteCodec.encode(
            note(type = NoteType.VIDEO, videos = listOf(VideoRef("f".repeat(32), 1080, 1920, 1L))),
        )
        // 视频块在整条记录的最末尾：[blobId: 4+32][width: 4][height: 4][durationMs: 8]。
        // 先确认"这里确实是 height 的最高字节"（正数必为 0）再动手改 ——
        // 否则将来格式一变，这条用例会因为"改到了别的字节"而失败，报出一个误导人的原因。
        val heightHigh = bytes.size - 12
        assertEquals("定位到的高度最高字节应为 0", 0, bytes[heightHigh].toInt())
        bytes[heightHigh] = 0xFF.toByte()
        assertNull("负数尺寸必须被拒", NoteCodec.decode(bytes))
    }

    @Test
    fun `视频时长为负时解码失败`() {
        val bytes = NoteCodec.encode(
            note(type = NoteType.VIDEO, videos = listOf(VideoRef("f".repeat(32), 1080, 1920, 1L))),
        )
        bytes[bytes.size - 8] = 0xFF.toByte() // durationMs 的最高字节 → 负数
        assertNull("负数时长必须被拒", NoteCodec.decode(bytes))
    }
}

/** v1 时代的固定时间戳。断言里连同它一起验 —— 老记录的这几个字段也不能被读错。 */
private const val V1_TIME = 1_770_000_000_000L

/** 一个视频引用在编码流里占的字节数（不含列表自己的"元素个数"4 字节）：blobId + 宽高 + 时长。 */
private const val VIDEO_ENTRY_BYTES = 4 + 32 + 4 + 4 + 8

/**
 * 手写一份 **v1** 编码。
 *
 * 刻意**不复用**生产编码器再"删掉 videos 那一段"：那样只是把自己的假设抄了两遍 ——
 * 一旦 `encode` 本身写错（比如把 videos 插到了 fields 之前），两边会一起错，测试照样绿。
 * 这里是照 v1 时代的格式文档独立写一遍，也只有这样，"新版本能读老数据"才算被证明过。
 */
private fun encodeV1(
    typeCode: Int,
    title: String,
    body: String,
    imageBlobIds: List<String>,
): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { d ->
        d.write(byteArrayOf(0x4D, 0x58, 0x4E, 0x31)) // "MXN1"
        d.writeByte(1) // version
        d.writeByte(typeCode)
        d.writeByte(0) // flags：未收藏
        d.writeLong(V1_TIME)
        d.writeLong(V1_TIME)
        writeV1String(d, title)
        writeV1String(d, body)
        d.writeInt(0) // tags
        d.writeInt(imageBlobIds.size)
        imageBlobIds.forEach {
            writeV1String(d, it)
            d.writeInt(1200)
            d.writeInt(900)
        }
        d.writeInt(0) // todos
        d.writeInt(0) // fields
        // v1 到此为止：没有 videos 这一段。
    }
    return out.toByteArray()
}

private fun writeV1String(out: DataOutputStream, value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    out.writeInt(bytes.size)
    out.write(bytes)
}

class NoteIndexCodecTest {

    @Test
    fun `id 列表往返一致`() {
        val ids = listOf("0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210")
        assertEquals(ids, NoteIndexCodec.decode(NoteIndexCodec.encode(ids)))
    }

    @Test
    fun `空清单往返一致`() {
        assertEquals(emptyList<String>(), NoteIndexCodec.decode(NoteIndexCodec.encode(emptyList())))
    }

    @Test
    fun `清单损坏时返回 null 而不是空列表`() {
        // 这是最关键的一条：若损坏被当成"空清单"，下一次写入会用一个
        // 只含一条记录的清单覆盖掉整个索引，等于丢掉全部笔记。
        val bytes = NoteIndexCodec.encode(listOf("a".repeat(32)))
        bytes[0] = 'Z'.code.toByte()
        assertNull(NoteIndexCodec.decode(bytes))
    }

    @Test
    fun `清单中出现非法 id 时返回 null`() {
        // 手工构造一份含路径穿越串的清单
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.write(byteArrayOf(0x4D, 0x58, 0x49, 0x31))
            d.writeByte(1)
            d.writeInt(1)
            val bad = "../databases/vault".toByteArray(Charsets.US_ASCII)
            d.writeInt(bad.size)
            d.write(bad)
        }
        assertNull(NoteIndexCodec.decode(out.toByteArray()))
    }

    @Test
    fun `尾部多余字节导致失败`() {
        val bytes = NoteIndexCodec.encode(listOf("a".repeat(32))) + byteArrayOf(1)
        assertNull(NoteIndexCodec.decode(bytes))
    }
}
