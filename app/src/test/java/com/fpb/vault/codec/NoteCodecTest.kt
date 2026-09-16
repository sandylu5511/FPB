package com.fpb.vault.codec

import com.fpb.vault.model.ImageRef
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.model.SecretField
import com.fpb.vault.model.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
