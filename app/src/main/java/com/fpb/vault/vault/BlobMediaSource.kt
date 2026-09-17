package com.fpb.vault.vault

import android.media.MediaDataSource
import com.fpb.vault.data.BlobReader

/**
 * 把 [BlobReader] 接成 `MediaPlayer` / `MediaMetadataRetriever` 认得的 [MediaDataSource]。
 *
 * ## 为什么必须走这条路
 *
 * 播放器常规只认**文件路径**或**文件描述符**，那两个都会把明文写到磁盘上 ——
 * 一个把"明文绝不落盘"当卖点的应用，不该为了让系统播放器方便就先写一个 mp4 出去。
 * `MediaDataSource` 是唯一"从内存喂数据"的入口。
 *
 * ## 两处契约必须严格照抄
 *
 * 1. **越界返回 -1，不是 0。** 返回 0 会被理解成"此刻暂时没数据，过会儿再来问"，
 *    于是播放器一直等一段永远不会到来的数据（表现为无限转圈）。
 *    这条语义由 [BlobReader] 保证，这里只是转交。
 * 2. **`readAt` 允许抛 IOException。** 密文损坏时抛异常是对的 ——
 *    把它伪装成"读到结尾"会让用户拿到一个静默截断的影片，
 *    而播放器一旦收到异常就会回调 `OnErrorListener`，界面才能如实说"这段视频读不出来"。
 */
class BlobMediaSource(private val reader: BlobReader) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.readAt(position, buffer, offset, size)

    /** 播放器靠它算时长与定位；必须返回明文字节数，不是密文。 */
    override fun getSize(): Long = reader.size

    /**
     * **故意不关读取器。**
     *
     * 播放器（native 侧那个 `JMediaDataSource` 包装器）在 `reset()` / `release()` 时
     * 会回调这里，这是它的正常清理动作 —— 但**读取器不是它拥有的**。
     * 读取器的生命周期属于调用方，而"一个页面里 Surface 会被重建好几次"是常态：
     * 切后台再回来、锁屏解锁、分屏切换都会走一遍 `surfaceDestroyed` → `surfaceCreated`，
     * 而 `MediaDataSource` 不能挂到两个播放器上，所以每一次重建都得新建一个播放器。
     *
     * 如果这里把读取器关掉，第二次 `surfaceCreated` 就会拿着一个已经关掉的文件句柄去
     * `setDataSource` → 读取器抛 IOException → `OnErrorListener` → 界面显示
     * 「这段视频读不出来」。用户看到的是"切出去看一眼消息，回来视频就坏了"。
     *
     * 与实况照片那条路一致：[com.fpb.vault.ui.BytesMediaSource] 的 `close()` 也是空操作。
     * 关闭只发生在 [com.fpb.vault.ui.VideoPage] 的 `DisposableEffect` 里 —— 那里才是
     * 真正拥有它的地方。
     */
    override fun close() = Unit
}
