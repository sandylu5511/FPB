package com.fpb.vault.vault

import android.graphics.Bitmap
import androidx.collection.LruCache
import java.util.concurrent.ConcurrentHashMap

/**
 * 解密后 bitmap 的内存缓存。
 *
 * ## 为什么不做磁盘缓存
 *
 * 这是整个应用里唯一持有可能被误解的优化：解密出来的图片如果写进磁盘缓存，
 * 那些缓存文件就是**明文**的 —— 加密存储会被这一步彻底架空，
 * 而且从文件管理器里就能看到缩略图。所以这里只有内存，
 * **锁定即清空**（见 [clear]），进程一退就什么也不剩。
 *
 * 容量按系统给应用的内存上限取 1/8，最多 32 MB —— 再多的话，
 * 低端机上"缓存图片"会变成"杀死后台应用"。
 */
class BitmapCache(
    maxBytes: Int = defaultMaxBytes(),
) {

    private val lru = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 每张图的"形态"信息（目前只有"是不是实况照片"）。
     *
     * ## 为什么放在这个类里，而不是另起一个缓存
     *
     * 因为 [clear] 是**唯一**保证"锁定后内存里不留下任何由明文派生的东西"的地方。
     * 另起一个缓存，就得记住在锁定路径上也调一次 —— 而"成对的操作只做了一半"
     * 正是这个项目里反复出现过的那类缺陷（写了不读、开了不关）。
     *
     * ## 为什么是 ConcurrentHashMap 而且是包一层的
     *
     * 写入发生在 IO 线程（[com.fpb.vault.ui.VaultAppState.ensureMotion]），
     * 读取发生在组合期的主线程，而 [clear] 又在锁定时的主线程 ——
     * 用普通 `HashMap` 的话，一次 `put` 与一次 `clear` 交错会把**这次清理丢掉**，
     * 那就正好破坏了这个类唯一的承诺。
     *
     * 包一层 [Known] 而不是直接存 `Motion?`：`ConcurrentHashMap` 不接受 null 值，
     * 而这里必须能存"看过、结果是不是实况照片"这个结论。
     * 剩下的那个问题（`get() == null` 分不清"不是实况"和"还没看过"）
     * 仍由 [containsKey] 回答，见 [motionKnown]。
     */
    private val motions = ConcurrentHashMap<String, Known>()

    /** [motions] 的值类型。存在的唯一理由就是让 `null` 能进 ConcurrentHashMap。 */
    private class Known(@JvmField val motion: MotionPhoto.Motion?)

    fun get(key: String): Bitmap? = lru.get(key)

    fun put(key: String, bitmap: Bitmap) {
        lru.put(key, bitmap)
    }

    fun motion(blobId: String): MotionPhoto.Motion? = motions[blobId]?.motion

    /**
     * 这张图的形态看过没有。
     *
     * 必须有这个方法：`motion() == null` 同时意味着"不是实况照片"和"还没看过"，
     * 而这两件事在"要不要现在去读一次盘"上完全不同 —— 少了它就只能靠再解密一次
     * 来消除歧义，而那正是这个缓存想省掉的开销。
     */
    fun motionKnown(blobId: String): Boolean = motions.containsKey(blobId)

    fun putMotion(blobId: String, motion: MotionPhoto.Motion?) {
        motions[blobId] = Known(motion)
    }

    /** 主动回收 —— 锁定保险库时必须调用，否则明文图像会留在内存里。 */
    fun clear() {
        lru.evictAll()
        motions.clear()
    }

    fun sizeBytes(): Int = lru.size()

    companion object {
        /** 缩略图与大图的 key 前缀，避免同一 blobId 的两个分辨率互相覆盖。 */
        fun thumbKey(blobId: String) = "t:$blobId"

        fun fullKey(blobId: String) = "f:$blobId"

        private fun defaultMaxBytes(): Int {
            val maxHeap = Runtime.getRuntime().maxMemory()
            val eighth = (maxHeap / 8).toInt()
            return eighth.coerceAtMost(32 * 1024 * 1024)
        }
    }
}
