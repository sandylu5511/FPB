package com.fpb.vault.testing

import com.fpb.vault.crypto.AeadCipher
import com.fpb.vault.data.BlobEmptyException
import com.fpb.vault.data.BlobReader
import com.fpb.vault.data.BlobSink
import com.fpb.vault.data.BlobTooLargeException
import com.fpb.vault.data.ChunkedBlobFormat
import com.fpb.vault.data.ChunkedBlobReader
import com.fpb.vault.data.ChunkedWrite
import com.fpb.vault.data.CipherRow
import com.fpb.vault.data.CipherRowStore
import com.fpb.vault.data.EncryptedBlob
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 内存版密文行存储。
 *
 * 存在的意义不只是"跑得快"：它让"数据库文件里到底存了什么"这件事
 * 变得可以**直接断言**。[containsPlaintext] 就是给"全库无明文"这条
 * 验收标准提供的可执行证据 —— 它扫描的是真正会被写进 SQLite 的那些字节，
 * 不是加密函数的返回值，也不是我们的假设。
 */
class InMemoryRowStore : CipherRowStore {

    private val map = LinkedHashMap<String, CipherRow>()

    /** 累计写入次数（含清单），用于确认清单确实随每次改动一起更新。 */
    var writes = 0
        private set

    /**
     * 故障注入：允许接下来 K 次 [upsert] 正常落盘，之后的第一次抛异常。`-1` 表示不注入。
     *
     * 语义定为"再写第 K+1 次时失败"，因为会话层一次 create 恰好写两行
     * （先笔记行、再清单行）。设成 1 就精确模拟"笔记行写完了、清单行还没写时进程被杀"，
     * 这正是"写入必须原子"这条要求要防的那个瞬间。
     */
    var failUpsertAfter = -1

    val size: Int get() = map.size

    val ids: Set<String> get() = map.keys.toSet()

    override fun load(id: String): CipherRow? = map[id]

    override fun loadAll(): List<CipherRow> = map.values.toList()

    override fun upsert(row: CipherRow) {
        when {
            failUpsertAfter == 0 -> {
                failUpsertAfter = -1
                throw IOException("模拟写盘失败（${row.id.take(8)}…）")
            }

            failUpsertAfter > 0 -> failUpsertAfter--
        }
        // 存副本，模拟"落盘之后内存里再怎么改都不影响已写入的数据"
        map[row.id] = CipherRow(row.id, row.nonce.copyOf(), row.ciphertext.copyOf())
        writes++
    }

    override fun delete(id: String) {
        map.remove(id)
    }

    override fun totalBytes(): Long = map.values.sumOf { it.storedBytes }

    /**
     * 快照回滚。
     *
     * 必须实现，否则"写入不是原子的"这类缺陷会在测试里被掩盖过去：
     * 生产代码依赖存储层提供原子性，替身若不回滚，测出来的通过是假通过。
     * 快照是浅拷贝即可 —— [upsert] 存进来的行本身就是副本，且不会被就地修改。
     */
    override fun <T> inTransaction(block: () -> T): T {
        val snapshot = LinkedHashMap(map)
        val injectedFailure = failUpsertAfter
        val writeCount = writes
        return try {
            block()
        } catch (t: Throwable) {
            map.clear()
            map.putAll(snapshot)
            failUpsertAfter = injectedFailure
            writes = writeCount
            throw t
        }
    }

    /** 翻转某一行的密文字节，模拟存储介质损坏或有人改动了文件。 */
    fun flipByte(id: String, offset: Int = 0): Boolean {
        val row = map[id] ?: return false
        if (offset < 0 || offset >= row.ciphertext.size) return false
        row.ciphertext[offset] = (row.ciphertext[offset].toInt() xor 0x01).toByte()
        return true
    }

    /** 把每一行都破坏掉。用于验证"清单本身读不出来时拒绝解锁"这条防线。 */
    fun flipAll() {
        map.keys.toList().forEach { flipByte(it, 0) }
    }

    /**
     * 在**所有落盘字节**中搜索明文片段。
     *
     * 覆盖 nonce 与密文两部分，并且是逐字节子串匹配（不是解码后比较）——
     * 只有这样才能证明"文件里连半个明文片段都找不到"。
     */
    fun containsPlaintext(needle: String): Boolean {
        val target = needle.toByteArray(StandardCharsets.UTF_8)
        if (target.isEmpty()) return false
        return map.values.any {
            it.ciphertext.containsSequence(target) || it.nonce.containsSequence(target)
        }
    }
}

/** 内存版附件密文存储。 */
class InMemoryBlobSink : BlobSink {

    private val map = LinkedHashMap<String, EncryptedBlob>()

    /**
     * 分块附件：blobId → (块序号 → 该块密文) 与头部。
     *
     * 与 [map] 分开放，是为了让"整块格式的断言"（比如 [containsPlaintext]、
     * 单个 nonce 的形状检查）不会被视频的块数据污染 —— 那类断言经常是
     * "整个 store 里不应出现明文"，混在一起会让它悄悄变成永真。
     */
    private class Chunked(
        val header: ChunkedBlobFormat.Header,
        val chunks: LinkedHashMap<Int, EncryptedBlob>,
    ) {
        val storedBytes: Long get() = chunks.values.sumOf { it.storedBytes } +
            ChunkedBlobFormat.HEADER_BYTES
    }

    private val chunked = LinkedHashMap<String, Chunked>()

    val ids: Set<String> get() = map.keys.toSet() + chunked.keys

    val size: Int get() = map.size + chunked.size

    override fun write(blobId: String, nonce: ByteArray, ciphertext: ByteArray) {
        map[blobId] = EncryptedBlob(nonce.copyOf(), ciphertext.copyOf())
    }

    override fun read(blobId: String): EncryptedBlob? =
        // 返回副本而不是内部实例：与 write 的语义保持一致，
        // 否则测试里一次不经意的就地修改会直接改到"落盘"的内容。
        map[blobId]?.let { EncryptedBlob(it.nonce.copyOf(), it.ciphertext.copyOf()) }

    override fun delete(blobId: String): Boolean =
        (map.remove(blobId) != null) or (chunked.remove(blobId) != null)

    override fun listIds(): List<String> = map.keys.toList() + chunked.keys

    override fun totalBytes(): Long =
        map.values.sumOf { it.storedBytes } + chunked.values.sumOf { it.storedBytes }

    fun containsPlaintext(needle: String): Boolean {
        val target = needle.toByteArray(StandardCharsets.UTF_8)
        if (target.isEmpty()) return false
        val all = map.values.toList() + chunked.values.flatMap { it.chunks.values }
        return all.any {
            it.ciphertext.containsSequence(target) || it.nonce.containsSequence(target)
        }
    }

    // ==================== 分块格式 ====================

    override fun writeChunked(
        blobId: String,
        source: InputStream,
        chunkSize: Int,
        limitBytes: Long,
        seal: (index: Int, plain: ByteArray, length: Int, isLast: Boolean) -> AeadCipher.Sealed,
    ): ChunkedWrite {
        val chunks = LinkedHashMap<Int, EncryptedBlob>()
        var plainSize = 0L
        var index = 0

        val pending = ByteArray(chunkSize)
        var pendingLength = readInto(source, pending, chunkSize)
        try {
            while (pendingLength > 0) {
                if (plainSize + pendingLength > limitBytes) throw BlobTooLargeException(limitBytes)
                // 与真实实现同构：超前读一块，才知道手上这块是不是末块。
                val next = ByteArray(chunkSize)
                val nextLength = readInto(source, next, chunkSize)
                val sealed = seal(index, pending, pendingLength, nextLength <= 0)
                chunks[index] = EncryptedBlob(sealed.nonce.copyOf(), sealed.ciphertext.copyOf())
                plainSize += pendingLength
                index++
                System.arraycopy(next, 0, pending, 0, nextLength)
                pendingLength = nextLength
            }
            if (plainSize <= 0) throw BlobEmptyException()
            chunked[blobId] = Chunked(ChunkedBlobFormat.headerOf(plainSize, chunkSize), chunks)
            return ChunkedWrite(
                plainBytes = plainSize,
                storedBytes = chunks.values.sumOf { it.storedBytes } + ChunkedBlobFormat.HEADER_BYTES,
            )
        } finally {
            // 失败时不留痕：真实实现是删掉 .part 临时文件，这里是"没写进去"。
            // 两者对外表现必须一致，否则测试会通过而真机上留下垃圾。
            if (chunked[blobId]?.chunks !== chunks) chunks.clear()
        }
    }

    override fun chunkHeader(blobId: String): ChunkedBlobFormat.Header? = chunked[blobId]?.header

    override fun openChunked(blobId: String, open: ChunkedBlobReader.ChunkOpener): BlobReader? {
        val stored = chunked[blobId] ?: return null
        val header = stored.header
        // 按需解密 + 缓存，与真实实现的语义对齐：读到坏块要抛 IOException，
        // 而不是伪装成"读完了"。测试若要验证"损坏必须报错"，这一层不能偷懒。
        val cache = HashMap<Int, ByteArray>()
        return object : BlobReader {
            override val size: Long get() = header.plainSize

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position < 0 || position >= header.plainSize) return -1
                if (size <= 0) return 0
                val total = minOf(size.toLong(), header.plainSize - position).toInt()
                var copied = 0
                var cursor = position
                while (copied < total) {
                    val chunkIndex = (cursor / header.chunkSize).toInt()
                    val plain = cache.getOrPut(chunkIndex) {
                        val blob = stored.chunks[chunkIndex]
                            ?: throw IOException("附件缺块: $chunkIndex")
                        open.open(
                            chunkIndex,
                            blob.nonce,
                            blob.ciphertext,
                            chunkIndex == header.chunkCount - 1,
                        ) ?: throw IOException("附件第 $chunkIndex 块校验失败")
                    }
                    val within = (cursor % header.chunkSize).toInt()
                    val take = minOf(plain.size - within, total - copied)
                    System.arraycopy(plain, within, buffer, offset + copied, take)
                    copied += take
                    cursor += take
                }
                return copied
            }

            override fun close() {
                cache.clear()
            }
        }
    }
}

/** 把一个流填满 [max] 字节（除非先读到结尾），与 `FileBlobStore.readUpTo` 同义。 */
private fun readInto(source: InputStream, buffer: ByteArray, max: Int): Int {
    var total = 0
    while (total < max) {
        val read = source.read(buffer, total, max - total)
        if (read <= 0) break
        total += read
    }
    return total
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
