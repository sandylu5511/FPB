package com.fpb.vault.vault

/**
 * 实况照片（Motion Photo）识别。
 *
 * ## 这是什么
 *
 * 安卓与三星相机拍出的"实况照片"，在磁盘上**就是一张普通的 JPEG** ——
 * 只是尾部多接了一小段 MP4，并在 XMP 元数据里声明"我是一张实况照片、影片有多长"。
 * 系统相册读到这段声明，查看时就会把那一小段放出来。
 *
 * ## 为什么识别放在**读取侧**，而不是入库时记一个标记
 *
 * 因为**字节本来就已经在库里了**。导入走的是原图直存，影片段和图片段一起被加密落盘，
 * 一个字节都没少（实测：源文件 31 413 B → 密文 31 441 B = 31 413 + 12 字节 nonce + 16 字节 tag）。
 * 缺的只是"有人去读那段 XMP"。所以放在读取侧有两个实打实的好处：
 *
 * 1. **已经导进去的老照片会立刻获得标识**，不必重新导入 ——
 *    这对一个已经装过、库里已经有照片的用户是决定性差别；
 * 2. 判据只有一处（就这份 XMP），不存在"标记与字节不一致"这种要额外维护的一致性。
 *
 * 代价是每次显示都要在已经解密出来的字节上扫一遍。这个开销可以忽略：
 * 扫描窗口限定在文件头部 [XMP_SCAN_BYTES] 字节，而同一张图的字节**本来就已经被解密**
 * 才能做缩略图 —— 我们没有为它多解密一次，只是在同一份内存上多走了一遍字节。
 *
 * ## 为什么写成纯字节函数
 *
 * 不碰任何 `android.*`，于是它能在普通 JVM 单测里对着**真实的实况照片字节**跑。
 * 这类解析器的分支几乎全在"元数据长什么样"上（新老两种格式、属性写法、
 * 数字对不上、根本不是实况照片），而单测正是覆盖这些分支最便宜的地方。
 *
 * ## 明确不支持的
 *
 * - **苹果的实况照片**：它是 `.HEIC` + **独立**的 `.MOV` **两个文件**，影片不在静止帧里。
 *   选图器选"图片"只会交出来那一张，所以这不是本应用丢失的数据 —— 它本来就不在同一个文件里。
 *   这里不做猜测性识别：识别不出来就返回 null，比显示一个点了没反应的"实况"角标诚实。
 * - **HEIC 容器的实况照片**：XMP 可能落在文件后段，超出扫描窗口。
 *   实测没有可用的样本，不做没有依据的支持。
 */
object MotionPhoto {

    /**
     * 影片段在整份字节里的位置。
     *
     * 刻意不提供"影片字节"字段：影片是**明文**，持有它就要负责它的生命周期。
     * 只告诉调用方"从哪到哪"，由调用方在真正要播的时候才切出来，
     * 切出来的那一份由调用方负责用完后不再持有。
     */
    data class Motion(val videoOffset: Int, val videoLength: Int) {

        init {
            require(videoOffset > 0 && videoLength > 0) { "影片段的偏移与长度必须为正" }
        }

        /** 从完整字节里切出影片段。超出范围时返回 null，绝不返回一段截断的影片。 */
        fun videoOf(bytes: ByteArray): ByteArray? {
            if (videoOffset + videoLength > bytes.size) return null
            return bytes.copyOfRange(videoOffset, videoOffset + videoLength)
        }
    }

    // ==================== 入口 ====================

    fun detect(bytes: ByteArray): Motion? {
        if (bytes.size < MIN_FILE_BYTES) return null

        val xmp = xmpRegion(bytes) ?: return null

        // 新格式优先：Container:Directory 直接列了每一段的长度，是三种判据里最精确的。
        val items = containerItems(xmp)
        if (items.isNotEmpty()) {
            fromContainer(items, bytes.size)?.let { return it }
        }

        // 没有声明自己是实况照片，到这里就结束 —— 不去猜。
        if (!DECLARES_MOTION.containsMatchIn(xmp)) return null

        fromLegacyOffset(xmp, bytes.size)?.let { return it }

        // 声明了是实况照片，但元数据里没给长度（个别厂商如此）。
        // 影片段必然是接在图片后面的一段完整 MP4，找它的 ftyp 盒即可。
        return fromFtypScan(bytes)
    }

    // ==================== 三种判据 ====================

    /**
     * 新格式：XMP 里的 `Container:Directory` 按顺序列出每一段
     * （`Item:Mime="image/jpeg" Item:Semantic="Primary"` 与
     * `Item:Mime="video/mp4" Item:Semantic="MotionPhoto"`），所以影片的起始位置
     * 就是它前面那些段的长度之和。
     */
    private fun fromContainer(items: List<XmpItem>, fileSize: Int): Motion? {
        val index = items.indexOfLast { it.mime.startsWith("video/") }
        if (index <= 0) return null
        // 影片段之后还有别的段：这不是"图片在前、影片在末尾"的常见形状，
        // 位置算不准就宁可不出手 —— 宁可不显示角标，也不要播错一段字节。
        if (items.drop(index + 1).any { it.mime.isNotEmpty() }) return null

        var offset = 0L
        for (i in 0 until index) offset += items[i].length + items[i].padding
        val video = items[index]
        if (offset <= 0 || video.length <= 0) return null
        return build(offset, video.length, fileSize)
    }

    /**
     * 老格式：`GCamera:MicroVideo="1"` + `GCamera:MicroVideoOffset="N"`，
     * N 是**尾部**那段影片的字节数（不是起始位置）。
     */
    private fun fromLegacyOffset(xmp: String, fileSize: Int): Motion? {
        val raw = LEGACY_OFFSET.find(xmp)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return build(fileSize - raw, raw, fileSize)
    }

    /**
     * 兜底：在字节里找 MP4 的 `ftyp` 盒。
     *
     * 盒头是「4 字节大端长度 + 'ftyp'」。要求长度自洽，是为了排除"压缩数据里碰巧
     * 出现了 ftyp 这四个字母"—— 那种情况下前面 4 个字节几乎不可能同时是一个合理的盒长。
     */
    private fun fromFtypScan(bytes: ByteArray): Motion? {
        var at = indexOfBytes(bytes, FTYP, MIN_FILE_BYTES)
        while (at >= 0) {
            val boxStart = at - 4
            if (boxStart > 0) {
                // ftyp 盒本身很小（常见 16~32 字节），它声明的是**自己这个盒**的长度，
                // 不是整段影片的长度 —— 一开始拿它跟影片长度下限比，永远比不过，
                // 结果就是"兜底路径一次也没生效过"。这里改成"盒长落在合理区间内"：
                // 盒长大到几百字节以上的，不是 ftyp 而是别的什么。
                val size = readInt32(bytes, boxStart)
                val sane = size >= 8 && size <= MAX_FTYP_BOX &&
                    boxStart.toLong() + size <= bytes.size.toLong()
                if (sane) return build(boxStart.toLong(), (bytes.size - boxStart).toLong(), bytes.size)
            }
            at = indexOfBytes(bytes, FTYP, at + FTYP.size)
        }
        return null
    }

    /**
     * 统一校验。
     *
     * ## 为什么不是"数字必须严丝合缝"
     *
     * 一开始这里要求 `偏移 + 长度` **恰好等于**文件大小，看着更严谨，实际是错的：
     * 逐字节对齐的写入方可能在结尾留几字节填充，那并不是元数据出错。
     * 而两种失败方向的代价完全不对称 ——
     * - 判得太严：真实的实况照片不显示角标，用户以为"还是丢了"；
     * - 判得太松：切出一段不是 MP4 的字节交给播放器，它放不出来，界面据实报一句"这段影片播不出来"。
     *
     * 所以只要"声明的影片完整落在文件里"就放行，并用 [MAX_TRAILING_SLACK]
     * 挡住"声明了一大段其实不在文件里"这种真正的自相矛盾。
     */
    private fun build(offset: Long, length: Long, fileSize: Int): Motion? {
        if (offset <= 0 || length < MIN_VIDEO_BYTES) return null
        if (offset > Int.MAX_VALUE || length > Int.MAX_VALUE) return null
        val end = offset + length
        if (end > fileSize.toLong()) return null
        if (fileSize.toLong() - end > MAX_TRAILING_SLACK) return null
        return Motion(offset.toInt(), length.toInt())
    }

    // ==================== XMP 定位与解析 ====================

    /**
     * 取出 XMP 所在的一小段文本。
     *
     * 用 ISO-8859-1 解码而不是 UTF-8：我们要找的键名与数字全是 ASCII，
     * 而 ISO-8859-1 是**字节一一对应**的，遇到非 UTF-8 的字节也绝不会解码失败或错位 ——
     * 用 UTF-8 的话，一个坏字节就可能把后面的内容整体解析成替换字符，键名再也匹配不上。
     */
    private fun xmpRegion(bytes: ByteArray): String? {
        val window = minOf(bytes.size, XMP_SCAN_BYTES)
        val header = indexOfBytes(bytes, XMP_HEADER, 0, window)
        val start = if (header >= 0) {
            header + XMP_HEADER.size
        } else {
            // 没有标准 APP1 头（某些写入方直接放 XML）。退一步找根元素。
            indexOfBytes(bytes, XMPMETA_TAG, 0, window)
        }
        if (start < 0 || start >= bytes.size) return null
        val end = minOf(bytes.size, start + MAX_XMP_BYTES)
        return String(bytes, start, end - start, Charsets.ISO_8859_1)
    }

    private data class XmpItem(val semantic: String, val mime: String, val length: Long, val padding: Long)

    /**
     * 按出现顺序取出 `Container:Directory` 里的所有 Item。
     *
     * 用正则而不是 XML 解析器：这段文本是相机固件写出来的、结构极稳定，
     * 而拉一个 XML 解析器进来要处理命名空间前缀、实体转义与流式读取，
     * 出错的方式反而更多。真正重要的是**每一处都取不到就当作没有**，
     * 绝不做"尽力猜测"——半个解析出来的 Directory 会给出一段错的字节范围。
     */
    private fun containerItems(xmp: String): List<XmpItem> =
        ITEM_ELEMENT.findAll(xmp).mapNotNull { match ->
            val element = match.value
            val length = attr(element, "Length")?.toLongOrNull() ?: return@mapNotNull null
            if (length < 0) return@mapNotNull null
            val padding = attr(element, "Padding")?.toLongOrNull() ?: 0L
            if (padding < 0) return@mapNotNull null
            XmpItem(
                semantic = attr(element, "Semantic").orEmpty(),
                mime = attr(element, "Mime").orEmpty(),
                length = length,
                padding = padding,
            )
        }.toList()

    /** 取一个属性值。属性名局部匹配（`Item:Length` / `Length` 都能取到），单双引号都认。 */
    private fun attr(element: String, name: String): String? =
        Regex("""(?:^|[\s<])[\w.:-]*$name\s*=\s*["']([^"']*)["']""")
            .find(element)?.groupValues?.get(1)

    // ==================== 字节工具 ====================

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int, to: Int = haystack.size): Int {
        if (needle.isEmpty()) return -1
        val last = minOf(to, haystack.size) - needle.size
        var i = maxOf(from, 0)
        while (i <= last) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    private fun readInt32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)

    // ==================== 常量 ====================

    /** 比这更小的文件不可能是"图片 + 影片"的组合，直接跳过。 */
    private const val MIN_FILE_BYTES = 2 * 1024

    /** 影片段的字节数下限。再短的"影片"多半是把别的东西误认成了视频。 */
    private const val MIN_VIDEO_BYTES = 1024

    /** ftyp 盒自身的长度上限。真实的 ftyp 盒只有几十字节。 */
    private const val MAX_FTYP_BOX = 512

    /** 允许文件末尾有这么多未声明的字节（对齐填充），再多就说明元数据与文件对不上。 */
    private const val MAX_TRAILING_SLACK = 4096

    /** 只在文件头部这么大范围内找 XMP。JPEG 的 APP1 段本来就在最前面。 */
    private const val XMP_SCAN_BYTES = 256 * 1024

    /** XMP 文本的解析上限，防止一段残缺的元数据把扫描拖长。 */
    private const val MAX_XMP_BYTES = 64 * 1024

    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII)
    private val XMPMETA_TAG = "<x:xmpmeta".toByteArray(Charsets.US_ASCII)
    private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)

    /**
     * 声明自己是实况照片。三种写法都认：
     * `GCamera:MotionPhoto="1"`、`GCamera:MicroVideo="1"`、`<smta:MotionPhoto>1</smta:MotionPhoto>`。
     *
     * 注意 `MotionPhotoVersion="1"` / `MicroVideoOffset="…"` **不会**被误判 ——
     * 正则要求 `MotionPhoto` 后面紧跟等号或右尖括号，中间不能夹别的字母。
     */
    private val DECLARES_MOTION = Regex("""(?:MotionPhoto|MicroVideo)(?:\s*=\s*["']1["']|>\s*1\s*<)""")

    /** `GCamera:MicroVideoOffset="3543"` —— 尾部的影片字节数。 */
    private val LEGACY_OFFSET = Regex("""(?:^|[\s<])[\w.:-]*MicroVideoOffset\s*=\s*["'](\d+)["']""")

    /** `<Container:Item Item:Mime="video/mp4" Item:Length="3543" Item:Padding="0"/>` */
    private val ITEM_ELEMENT = Regex("""<[\w.:-]*Item\b[^>]*>""")
}
