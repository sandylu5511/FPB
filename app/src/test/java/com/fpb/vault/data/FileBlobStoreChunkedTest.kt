package com.fpb.vault.data

import com.fpb.vault.crypto.Aad
import com.fpb.vault.crypto.AeadCipher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Random

/**
 * 分块附件在**真实文件**上的行为。
 *
 * 用真实 `java.io.File` 而不是内存替身，是因为这一层要验的东西全是文件系统性质：
 * 偏移算术对不对、`readFully` 在文件被截短后会不会抛、失败时有没有留下半截文件。
 * 内存替身天生没有这些，用它测等于没测。
 *
 * 密钥与 AAD 都是真的（`javax.crypto` 在 JVM 上同样可用），
 * 于是这里验的"篡改必然暴露"是**真的密码学结论**，不是替身的行为约定。
 */
class FileBlobStoreChunkedTest {

    private lateinit var dir: File
    private lateinit var store: FileBlobStore

    /** 块大小取合法区间的下界：64 KiB 就够造出多块，又不必让单测写几十 MB。 */
    private val cs = ChunkedBlobFormat.MIN_CHUNK_BYTES

    private val key = ByteArray(AeadCipher.KEY_BYTES) { ((it * 37 + 11) and 0xFF).toByte() }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("fpb-blob-test").toFile()
        store = FileBlobStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun seal(blobId: String) =
        { index: Int, plain: ByteArray, length: Int, isLast: Boolean ->
            AeadCipher.seal(key, plain.copyOf(length), Aad.blobChunk(blobId, index, cs, isLast))
        }

    private fun opener(blobId: String) =
        { index: Int, nonce: ByteArray, ciphertext: ByteArray, isLast: Boolean ->
            AeadCipher.open(key, nonce, ciphertext, Aad.blobChunk(blobId, index, cs, isLast))
        }

    private fun bytes(size: Int, seed: Long = 7L): ByteArray =
        ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun write(blobId: String, plain: ByteArray, limit: Long = Long.MAX_VALUE) =
        store.writeChunked(blobId, ByteArrayInputStream(plain), cs, limit, seal(blobId))

    private fun readAll(reader: BlobReader): ByteArray {
        val out = ByteArray(reader.size.toInt())
        var at = 0
        while (at < out.size) {
            val n = reader.readAt(at.toLong(), out, at, out.size - at)
            if (n <= 0) fail("读到 $at 字节时提前结束（应为 ${out.size}）")
            at += n
        }
        return out
    }

    private fun tempFiles(): List<String> =
        dir.listFiles()?.filter { it.name.endsWith(FileBlobStore.TEMP_SUFFIX) }?.map { it.name }
            ?: emptyList()

    // ==================== 写入前的截断 ====================

    /**
     * 写入前必须把临时文件截断到零。
     *
     * 缺陷：`RandomAccessFile(temp, "rw")` **不会**清掉一个已存在的同名文件。
     * 上一次写入被强杀（OOM、划掉任务、系统回收）会漏下一个 `<blobId>.part`，
     * 这次的新内容若比它短，旧字节就残留在尾部 —— 文件比头部声称的长度长，
     * [FileBlobStore.chunkHeader] 的长度校验不通过，于是这个附件**永远读不出来**：
     * 加密从头开始覆盖，内容其实是对的，只是尾巴多了一截旧数据。
     *
     * 而写入那一步报的是**成功**。用户会看到"已添加 1 段视频"，
     * 点开却是一片黑 —— 且怎么重试都一样。
     *
     * 启动时的 `purgeStaleTempFiles()` 通常会删掉那种残留，但"通常"不是"一定"。
     */
    @Test
    fun `残留的半截临时文件不会让新写入的附件变成读不出来`() {
        val blobId = "e".repeat(32)
        // 造一个比这次要写的内容**长得多**的残留，模拟上次被强杀时留下的尾巴。
        val stale = File(dir, blobId + FileBlobStore.TEMP_SUFFIX)
        RandomAccessFile(stale, "rw").use { it.write(ByteArray(cs * 4 + 777)) }

        val plain = bytes(cs + 33, seed = 5L)
        write(blobId, plain)

        assertEquals("残留的临时文件写完之后不该还在", emptyList<String>(), tempFiles())
        val header = store.chunkHeader(blobId)
        assertNotNull(
            "长度对不上时这里会是 null —— 那正是缺陷的表现：文件比头部声称的长，" +
                "于是这个附件永远读不出来",
            header,
        )
        assertEquals(
            "落盘长度必须正好是头部 + 各块",
            header!!.expectedStoredBytes(),
            File(dir, blobId).length(),
        )
        store.openChunked(blobId, opener(blobId))!!.use {
            assertArrayEquals("内容也要完整读回", plain, readAll(it))
        }
    }

    // ==================== 正常路径 ====================

    @Test
    fun `分块写入后能逐字节读回全部明文`() {
        val blobId = "a".repeat(32)
        val plain = bytes(cs * 2 + 12_345) // 两个整块 + 一个残块

        val written = write(blobId, plain)

        assertEquals(plain.size.toLong(), written.plainBytes)
        // 密文只多出头部与每块的 nonce + tag
        val expectedStored = ChunkedBlobFormat.HEADER_BYTES +
            (2 * (AeadCipher.NONCE_BYTES + cs + AeadCipher.TAG_BYTES)) +
            (AeadCipher.NONCE_BYTES + 12_345 + AeadCipher.TAG_BYTES)
        assertEquals(expectedStored.toLong(), written.storedBytes)
        assertEquals(expectedStored.toLong(), File(dir, blobId).length())

        val header = store.chunkHeader(blobId)
        assertNotNull("自家写出来的必须认得出是分块格式", header)
        assertEquals(3, header!!.chunkCount)
        assertEquals(plain.size.toLong(), header.plainSize)

        store.openChunked(blobId, opener(blobId))!!.use { reader ->
            assertEquals(plain.size.toLong(), reader.size)
            assertArrayEquals(plain, readAll(reader))
        }
    }

    @Test
    fun `恰好一个整块时也只有一个块`() {
        val blobId = "b".repeat(32)
        val plain = bytes(cs, seed = 3L)
        write(blobId, plain)

        val header = store.chunkHeader(blobId)!!
        assertEquals(1, header.chunkCount)
        store.openChunked(blobId, opener(blobId))!!.use {
            assertArrayEquals(plain, readAll(it))
        }
    }

    @Test
    fun `跨块边界的读取与任意位置的单字节读取都正确`() {
        val blobId = "c".repeat(32)
        val plain = bytes(cs * 2 + 100, seed = 11L)
        write(blobId, plain)

        store.openChunked(blobId, opener(blobId))!!.use { reader ->
            // 从块边界前一字节起读 2 字节：必须跨块，且两块的前后各取一字节
            val across = ByteArray(2)
            assertEquals(2, reader.readAt(cs - 1L, across, 0, 2))
            assertEquals(plain[cs - 1], across[0])
            assertEquals(plain[cs], across[1])

            // 一次读一大段（跨两个块）也必须拼对
            val span = ByteArray(cs + 8)
            assertEquals(span.size, reader.readAt(cs - 4L, span, 0, span.size))
            for (i in span.indices) {
                assertEquals("跨块拼接第 $i 字节不对", plain[cs - 4 + i], span[i])
            }

            // 伪随机顺序的单字节读取（模拟播放器的寻址）
            val rnd = Random(2026L)
            repeat(300) {
                val at = rnd.nextInt(plain.size)
                val one = ByteArray(1)
                assertEquals(1, reader.readAt(at.toLong(), one, 0, 1))
                assertEquals("位置 $at 的字节不对", plain[at], one[0])
            }
        }
    }

    @Test
    fun `越界读取返回 -1 而不是抛异常或返回 0`() {
        val blobId = "d".repeat(32)
        val plain = bytes(cs + 500, seed = 5L)
        write(blobId, plain)

        store.openChunked(blobId, opener(blobId))!!.use { reader ->
            // -1 = "读完了"。返回 0 会被播放器当成"暂时没数据"而无限空转，
            // 抛异常则会让一个正常的文件末尾被当成损坏。
            assertEquals(-1, reader.readAt(-1L, ByteArray(4), 0, 4))
            assertEquals(-1, reader.readAt(reader.size, ByteArray(4), 0, 4))
            assertEquals(-1, reader.readAt(reader.size + 10_000, ByteArray(4), 0, 4))

            // 尾部不够时要如实返回实际读到的字节数
            val tail = ByteArray(64)
            assertEquals(3, reader.readAt(reader.size - 3, tail, 0, 64))
        }
    }

    @Test
    fun `分块附件的明文不会原样出现在文件里`() {
        val blobId = "e".repeat(32)
        val marker = "顶格明文标记".toByteArray(Charsets.UTF_8)
        val plain = ByteArray(cs * 2)
        System.arraycopy(marker, 0, plain, cs - 2, marker.size) // 故意跨块边界
        write(blobId, plain)

        val onDisk = File(dir, blobId).readBytes()
        assertFalse("磁盘上不该找得到明文片段", onDisk.containsSequence(marker))
    }

    // ==================== 损坏与篡改 ====================

    @Test
    fun `文件被截短后认不出是分块格式`() {
        val blobId = "f".repeat(32)
        val plain = bytes(cs * 3, seed = 9L)
        write(blobId, plain)
        val full = store.chunkHeader(blobId)!!

        RandomAccessFile(File(dir, blobId), "rw").use { it.setLength(full.storedOffsetOf(2)) }

        // 长度与块布局不再相符 → "是不是分块格式"这一关就该把它挡下来。
        assertNull("截短后长度自洽校验必须失败", store.chunkHeader(blobId))
        assertNull("读不出来要返回 null，而不是给一个变短的读取器", store.openChunked(blobId, opener(blobId)))
    }

    @Test
    fun `改了块数又把文件截短的篡改会被块级认证挡下`() {
        // 这是这套格式最要紧的一条：头部是明文，攻击者可以把它改得**自洽** ——
        // "块数减一、明文总长同步改成整块"，于是长度校验反而通过。
        // 挡住它的是 AAD 里的末块标记：第 1 块当初是以 isLast=false 封的，
        // 现在被当成末块去解，AAD 对不上，认证必然失败。
        val blobId = "1".repeat(32)
        val plain = bytes(cs * 2 + 1000, seed = 13L)
        write(blobId, plain)

        val file = File(dir, blobId)
        RandomAccessFile(file, "rw").use { it.setLength(store.chunkHeader(blobId)!!.storedOffsetOf(2)) }
        val forged = ChunkedBlobFormat.encodeHeader(
            ChunkedBlobFormat.Header(cs, chunkCount = 2, plainSize = 2L * cs),
        )
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(forged) }

        // 改过的头是自洽的，长度也对得上 —— 所以"是不是分块格式"这一关放行了
        assertNotNull("自洽的伪造头部会通过长度校验", store.chunkHeader(blobId))

        store.openChunked(blobId, opener(blobId))!!.use { reader ->
            val first = ByteArray(cs)
            assertEquals("第 0 块本来就不是末块，照常解得开", cs, reader.readAt(0, first, 0, cs))

            // 第 1 块开始 → 必须报错，**绝不能**给出一个"静默变短"的影片
            val one = ByteArray(1)
            assertThrows(IOException::class.java) {
                reader.readAt(cs.toLong(), one, 0, 1)
            }
            // 坏掉之后整个读取器都不再可用（避免播放器在花屏里继续跑）
            assertThrows(IOException::class.java) {
                reader.readAt(0, one, 0, 1)
            }
        }
    }

    @Test
    fun `翻转密文中的一位会导致读取报错而不是吐出坏数据`() {
        val blobId = "2".repeat(32)
        val plain = bytes(cs + 64, seed = 17L)
        write(blobId, plain)

        val file = File(dir, blobId)
        RandomAccessFile(file, "rw").use { raf ->
            // 落在第 0 块的**密文**里（跳过 24 字节头 + 12 字节 nonce）
            val at = (ChunkedBlobFormat.HEADER_BYTES + AeadCipher.NONCE_BYTES + 5).toLong()
            raf.seek(at)
            val b = raf.read()
            raf.seek(at)
            raf.write(b xor 0x01)
        }

        store.openChunked(blobId, opener(blobId))!!.use { reader ->
            assertThrows(IOException::class.java) {
                reader.readAt(0, ByteArray(16), 0, 16)
            }
        }
    }

    @Test
    fun `整块格式的附件不会被误判成分块格式`() {
        val blobId = "3".repeat(32)
        val sealed = AeadCipher.seal(key, "这是一张图片".toByteArray(), Aad.blob(blobId))
        store.write(blobId, sealed.nonce, sealed.ciphertext)

        assertNull("图片必须走整块那条路", store.chunkHeader(blobId))
        assertNull(store.openChunked(blobId, opener(blobId)))
        assertNotNull(store.read(blobId))
    }

    // ==================== 上限与失败不留痕 ====================

    @Test
    fun `超过上限时报错且不在磁盘上留下任何东西`() {
        val blobId = "4".repeat(32)
        val limit = cs.toLong() + 10
        var sealedChunks = 0

        val thrown = assertThrows(BlobTooLargeException::class.java) {
            store.writeChunked(blobId, ByteArrayInputStream(bytes(cs * 2, seed = 19L)), cs, limit) { i, p, n, last ->
                sealedChunks++
                AeadCipher.seal(key, p.copyOf(n), Aad.blobChunk(blobId, i, cs, last))
            }
        }

        assertEquals(limit, thrown.limitBytes)
        assertEquals("超限的那一块不该被加密", 1, sealedChunks)
        assertTrue("临时文件必须被清掉", tempFiles().isEmpty())
        assertEquals("目录里不该有任何文件", 0, dir.listFiles()?.size ?: 0)
        assertTrue(store.listIds().isEmpty())
    }

    @Test
    fun `空流被拒且不留痕`() {
        val blobId = "5".repeat(32)
        assertThrows(BlobEmptyException::class.java) {
            store.writeChunked(blobId, ByteArrayInputStream(ByteArray(0)), cs, Long.MAX_VALUE, seal(blobId))
        }
        assertTrue(tempFiles().isEmpty())
        assertTrue(store.listIds().isEmpty())
    }

    @Test
    fun `写完就改名，目录里不会长期留着 part 文件`() {
        val blobId = "6".repeat(32)
        write(blobId, bytes(cs, seed = 23L))

        assertTrue("正常写完不该有 .part", tempFiles().isEmpty())
        assertTrue(File(dir, blobId).isFile)
    }

    @Test
    fun `清理残留临时文件`() {
        // 进程被强杀才会留下它（写入的 finally 没机会跑）。
        val leftover = File(dir, "7".repeat(32) + FileBlobStore.TEMP_SUFFIX)
        leftover.writeBytes(bytes(1024, seed = 29L))
        val unrelated = File(dir, "8".repeat(32))
        unrelated.writeBytes(bytes(64, seed = 31L))

        assertEquals(1, store.purgeStaleTempFiles())
        assertFalse(leftover.exists())
        assertTrue("正常附件不该被顺手删掉", unrelated.exists())
    }

    @Test
    fun `分块与整块附件同目录同命名，备份按目录收集时不会漏掉视频`() {
        // 备份只做一件事：把 attachments/ 下"名字是合法 RowId"的文件整体打进包里
        // （见 BackupManager.export 的 `listFiles()?.filter { it.isFile && RowIds.isValid(it.name) }`）。
        // 也就是说"视频会不会被备份"**不取决于备份代码知不知道视频这回事** ——
        // 它只取决于两种附件是不是落在同一个目录、文件名是不是恰好等于 blobId。
        //
        // 这条把那个前提钉住。少了它，将来谁把分块附件挪进子目录（或改个后缀），
        // 备份就会"导出成功、记录完好、视频全没了"，而且没有任何一步会报错。
        val videoId = "c".repeat(32)
        val imageId = "d".repeat(32)
        write(videoId, bytes(cs * 2 + 999, seed = 41L))     // 分块：视频走这条
        val whole = AeadCipher.seal(key, bytes(cs / 2, seed = 43L), Aad.blob(imageId))
        store.write(imageId, whole.nonce, whole.ciphertext) // 整块：图片走这条

        val collectable = dir.listFiles()
            ?.filter { it.isFile && RowIds.isValid(it.name) }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()
        assertEquals(
            "备份能收集到的附件集合必须恰好是这两个 blobId",
            setOf(videoId, imageId),
            collectable,
        )
        assertTrue("分块附件也不该留 .part", tempFiles().isEmpty())
    }
}

private fun ByteArray.containsSequence(target: ByteArray): Boolean {
    if (target.isEmpty()) return true
    if (target.size > size) return false
    outer@ for (i in 0..size - target.size) {
        for (j in target.indices) {
            if (this[i + j] != target[j]) continue@outer
        }
        return true
    }
    return false
}
