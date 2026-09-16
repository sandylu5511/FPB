package com.fpb.vault.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * [MotionPhoto] 的守护测试。
 *
 * 这个类的分支几乎全在"元数据长什么样"上 —— 新老两种格式、属性写法、
 * 数字对不上、根本不是实况照片、以及一堆不该被认成实况照片的近似输入。
 * 这些分支在真机上很难逐一造出来，而构造字节是最便宜的事，所以在这里全部覆盖。
 *
 * 所有输入都是**手工拼出来的字节**，不依赖任何真机素材。
 */
class MotionPhotoTest {

    // ==================== 正常路径 ====================

    @Test
    fun `新格式 Container-Directory 能算出影片段的位置`() {
        val bytes = containerFormat(imageLen = 4_000, videoLen = 2_000)

        val motion = MotionPhoto.detect(bytes)

        assertNotNull("Container:Directory 里明明白白列了 video/mp4，必须认得出来", motion)
        assertEquals(4_000, motion!!.videoOffset)
        assertEquals(2_000, motion.videoLength)
    }

    @Test
    fun `切出来的影片段就是尾部那一段原样字节`() {
        val bytes = containerFormat(imageLen = 4_000, videoLen = 2_000)

        val video = MotionPhoto.detect(bytes)!!.videoOf(bytes)

        assertNotNull(video)
        assertArrayEquals(bytes.copyOfRange(4_000, bytes.size), video)
    }

    @Test
    fun `老格式 MicroVideoOffset 按尾部字节数算`() {
        // MicroVideoOffset 是**尾部**那段影片的字节数，不是起始位置 ——
        // 这里刻意让 imageLen 与 videoLen 相差很大，位置算反了就会被看出来。
        val bytes = assemble(
            declaration = """GCamera:MotionPhoto="1" GCamera:MicroVideo="1" """ +
                """GCamera:MicroVideoOffset="2000"""",
            imageLen = 4_000,
            videoLen = 2_000,
        )

        val motion = MotionPhoto.detect(bytes)

        assertEquals(4_000, motion?.videoOffset)
        assertEquals(2_000, motion?.videoLength)
    }

    @Test
    fun `只声明是实况但没给长度时靠 ftyp 盒兜底`() {
        val bytes = assemble(
            declaration = """GCamera:MotionPhoto="1"""",
            imageLen = 4_000,
            videoLen = 2_000,
        )

        val motion = MotionPhoto.detect(bytes)

        assertEquals(4_000, motion?.videoOffset)
        assertEquals(2_000, motion?.videoLength)
    }

    @Test
    fun `单引号属性与别的命名空间前缀都认`() {
        val bytes = assemble(
            declaration = "GCamera:MotionPhoto='1' GCamera:MicroVideoOffset='2000'",
            imageLen = 4_000,
            videoLen = 2_000,
        )

        assertEquals(4_000, MotionPhoto.detect(bytes)?.videoOffset)
    }

    @Test
    fun `三星那种元素写法也认`() {
        val bytes = assemble(
            declaration = """<smta:MotionPhoto>1</smta:MotionPhoto> """ +
                """<smta:MotionPhotoVersion>1</smta:MotionPhotoVersion> """ +
                """GCamera:MicroVideoOffset="2000"""",
            imageLen = 4_000,
            videoLen = 2_000,
        )

        assertEquals(4_000, MotionPhoto.detect(bytes)?.videoOffset)
    }

    // ==================== 必须判 null 的输入 ====================

    @Test
    fun `不是实况照片的普通图片返回 null`() {
        val bytes = assemble(
            declaration = """GCamera:HdrMode="1"""",
            imageLen = 4_000,
            videoLen = 2_000,
        )

        assertNull("没声明是实况就不该去猜有影片段", MotionPhoto.detect(bytes))
    }

    @Test
    fun `完全不带 XMP 的字节返回 null`() {
        val bytes = ByteArray(8_000) { (it % 251).toByte() }

        assertNull(MotionPhoto.detect(bytes))
    }

    @Test
    fun `文件小到不可能装下图片加影片时直接跳过`() {
        val bytes = containerFormat(imageLen = 800, videoLen = 600)

        assertNull(MotionPhoto.detect(bytes))
    }

    @Test
    fun `影片段短得不像影片时拒绝`() {
        // 图片段合法，影片段只有 100 字节 —— 多半是把别的东西误认成了视频
        val bytes = assemble(
            declaration = """GCamera:MotionPhoto="1" GCamera:MicroVideoOffset="100"""",
            imageLen = 4_000,
            videoLen = 100,
        )

        assertNull(MotionPhoto.detect(bytes))
    }

    @Test
    fun `声明的长度超出文件时拒绝`() {
        // 声明尾部有 9000 字节影片，实际文件只有 6000 —— 自相矛盾，
        // 而且视频段刻意不以 ftyp 开头，堵死兜底路径，确认它真的不出手。
        val bytes = assemble(
            declaration = """GCamera:MotionPhoto="1" GCamera:MicroVideoOffset="9000"""",
            imageLen = 4_000,
            videoLen = 2_000,
            videoLooksLikeMp4 = false,
        )

        assertNull(MotionPhoto.detect(bytes))
    }

    @Test
    fun `Container 里影片段之后还有别的段时不出手`() {
        // 这不是"图片在前、影片在末尾"的常见形状，位置算不准就宁可不出手，
        // 也不要按公式算出一个错的起点去播一段垃圾。
        val bytes = assemble(
            declaration =
                """<Container:Directory><rdf:Seq>""" +
                    """<rdf:li><Container:Item Item:Mime="image/jpeg" """ +
                    """Item:Semantic="Primary" Item:Length="4000" Item:Padding="0"/></rdf:li>""" +
                    """<rdf:li><Container:Item Item:Mime="video/mp4" """ +
                    """Item:Semantic="MotionPhoto" Item:Length="2000" Item:Padding="0"/></rdf:li>""" +
                    """<rdf:li><Container:Item Item:Mime="image/jpeg" """ +
                    """Item:Semantic="Secondary" Item:Length="0" Item:Padding="0"/></rdf:li>""" +
                    """</rdf:Seq></Container:Directory>""",
            imageLen = 4_000,
            videoLen = 2_000,
            videoLooksLikeMp4 = false,
        )

        assertNull(MotionPhoto.detect(bytes))
    }

    @Test
    fun `压缩数据里出现 ftyp 字样但盒长不合理时不出手`() {
        // 声明是实况照片、没有给长度，于是走到 ftyp 兜底；
        // 但"ftyp"前面那 4 个字节不是一个合理的盒长，说明那只是碰巧撞上的四个字母。
        val bytes = assemble(
            declaration = """GCamera:MotionPhoto="1"""",
            imageLen = 4_000,
            videoLen = 2_000,
            videoLooksLikeMp4 = false,
        )

        assertNull(MotionPhoto.detect(bytes))
    }

    // ==================== 不越界 ====================

    @Test
    fun `影片段的位置不会超出字节范围`() {
        val bytes = containerFormat(imageLen = 4_000, videoLen = 2_000)
        val motion = MotionPhoto.detect(bytes)!!

        assertEquals(bytes.size, motion.videoOffset + motion.videoLength)
        // 传一份更短的字节进去时必须拒绝，而不是返回一段被截断的影片
        assertNull(motion.videoOf(bytes.copyOf(3_000)))
    }

    // ==================== 构造测试数据 ====================

    /** 新格式：XMP 里带 Container:Directory，逐段声明长度。 */
    private fun containerFormat(imageLen: Int, videoLen: Int): ByteArray = assemble(
        declaration =
            """<Container:Directory><rdf:Seq>""" +
                """<rdf:li><Container:Item Item:Mime="image/jpeg" """ +
                """Item:Semantic="Primary" Item:Length="$imageLen" Item:Padding="0"/></rdf:li>""" +
                """<rdf:li><Container:Item Item:Mime="video/mp4" """ +
                """Item:Semantic="MotionPhoto" Item:Length="$videoLen" Item:Padding="0"/></rdf:li>""" +
                """</rdf:Seq></Container:Directory>""",
        imageLen = imageLen,
        videoLen = videoLen,
    )

    /**
     * 拼一份"图片段 + 影片段"的字节。
     *
     * 图片段 = 标准 XMP APP1 头 + XML + 空白填充到 [imageLen]；
     * 影片段默认以一个合法的 ftyp 盒开头（[videoLooksLikeMp4] 关掉后就是普通字节，
     * 用来确认兜底路径不会凭空成功）。
     */
    private fun assemble(
        declaration: String,
        imageLen: Int,
        videoLen: Int,
        videoLooksLikeMp4: Boolean = true,
    ): ByteArray {
        val xml = buildString {
            append("""<?xpacket begin="﻿"?>""")
            append("""<x:xmpmeta xmlns:x="adobe:ns:meta/">""")
            append("""<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""")
            append("""<rdf:Description rdf:about="" """)
            append("""xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" """)
            append("""xmlns:Container="http://ns.google.com/photos/1.0/container/" """)
            append("""xmlns:Item="http://ns.google.com/photos/1.0/container/item/" """)
            append("""xmlns:smta="http://ns.samsung.com/motionphoto/1.0/" """)
            append(declaration)
            append("></rdf:Description></rdf:RDF></x:xmpmeta>")
        }

        val xmp = XMP_HEADER + xml.toByteArray(StandardCharsets.ISO_8859_1)
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) +
            byteArrayOf(((xmp.size + 2) shr 8).toByte(), ((xmp.size + 2) and 0xFF).toByte()) +
            xmp
        require(header.size < imageLen) { "图片段太小，装不下 XMP：$imageLen < ${header.size}" }

        val image = header + ByteArray(imageLen - header.size) { ' '.code.toByte() }
        val video = ByteArray(videoLen) { 0x11 }
        if (videoLooksLikeMp4 && videoLen >= FTYP_BOX_BYTES) {
            // ftyp 盒：4 字节大端盒长 + 'ftyp' + 主品牌 + 版本。
            // 盒长必须把**四个字节都写全** —— 只写最低位那一个的话，
            // 高三位还是填充用的 0x11，读出来是个天文数字，盒长校验必然不通过。
            writeBigEndianInt(video, 0, FTYP_BOX_BYTES)
            "ftyp".toByteArray(StandardCharsets.US_ASCII).copyInto(video, 4)
            "mp42".toByteArray(StandardCharsets.US_ASCII).copyInto(video, 8)
        }
        return image + video
    }

    /** 大端写入一个 32 位整数。 */
    private fun writeBigEndianInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private companion object {
        val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.ISO_8859_1)

        /** 与 `MotionPhoto.MAX_FTYP_BOX` 内的合理区间同量级的一个盒长。 */
        const val FTYP_BOX_BYTES = 24
    }
}
