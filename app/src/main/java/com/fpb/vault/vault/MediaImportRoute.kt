package com.fpb.vault.vault

import com.fpb.vault.model.NoteType

/**
 * 一次导入里"这一项该走哪条路"的判定。
 *
 * ## 为什么把它从界面里抽出来
 *
 * 这段规则原来是内联在图库那个 `uris.forEach` 里的 `when` 分支。放在那里有两个问题：
 * 一是它**没法被任何测试碰到**（要 Context、Uri 与一个正在跑的 Compose 作用域），
 * 二是"什么时候该回退去试视频"这件事只有读完整段协程代码才看得出来 ——
 * 而它恰好是个容易写漏的规则（漏掉一支的表现是"某一类文件永远导不进来，
 * 给出的理由还是错的"）。
 *
 * 抽成纯函数之后规则本身可以被逐条断言，界面那一侧只负责派发。
 *
 * ## 三条路的由来
 *
 * 图片与视频的准备方式完全不同：图片要**整份读进内存**才能由 `BitmapFactory` 定出宽高，
 * 视频要**流式写盘**再用随机访问读取器探元数据。所以"先试哪条"不是风格问题：
 * 试错一次的代价可能是一次几百 MB 的写盘。
 */
object MediaImportRoute {

    /** 这一项接下来该做什么。 */
    sealed interface Plan {
        /** 图片已经原样读进内存，直接入库。 */
        data class UseImage(val prepared: ImagePipeline.Prepared) : Plan

        /**
         * 交给视频那条路：流式加密落盘 → 探测元数据 → 失败则把刚写的那份删掉。
         *
         * 它比图片那条路**贵**：要写完整份文件才轮到探测。所以只在"有理由怀疑
         * 这不是（或不止是）一张图片"时才走这里，而不是当成兜底无脑试一遍。
         */
        data object TryVideo : Plan

        /** 图片超出单张上限，且没有理由怀疑它是视频 —— 按"图片过大"报给用户。 */
        data class ImageTooLarge(val message: String) : Plan
    }

    /**
     * @param mime `classify` 给出的判断，null 表示内容提供方没有给 type。
     * @param image 图片那条路的尝试结果。调用方必须**先真的试过一次图片**
     *              （MIME 明确说是视频的情形除外），因为这里的判定全部基于它的结果。
     */
    fun plan(mime: NoteType?, image: ImagePipeline.Preparation): Plan = when {
        mime == NoteType.VIDEO -> Plan.TryVideo
        image is ImagePipeline.Preparation.Ready -> Plan.UseImage(image.prepared)

        // 图片那条路报"过大"，而 MIME **也确认这就是一张图片** → 按"图片过大"报给用户，
        // 不再去走一遍视频（那要先完整写一遍盘才轮到探测，白花几百 MB 的写入）。
        image is ImagePipeline.Preparation.TooLarge && mime == NoteType.IMAGE ->
            Plan.ImageTooLarge(image.message)

        // 其余一律去试视频。这里包含两支，都是原来漏掉或写错的：
        // - `Unreadable`：不是图片、或 MIME 误标 —— 本来就该试；
        // - `TooLarge` 且 **MIME 说不清**：视频几乎必然超过图片那条 32 MiB 的线，
        //   所以这一支恰恰是为它准备的。原来它无条件按"图片过大"报错，于是
        //   一个大视频会被回一句「图片超过单张 32 MB 的上限，请先在系统相册里裁剪」——
        //   而它连一次被当成视频试的机会都没有。
        else -> Plan.TryVideo
    }
}
