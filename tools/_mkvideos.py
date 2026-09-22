#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成验收用的测试影片（图库视频功能的几何/动作判据素材）。

## 为什么画面要长成这样

不是随便一段彩条。每一块颜色都在回答一个具体问题：

- **整幅纯红底**：`band_of()` 靠"这一行有多少比例是红的"把**影片区域**从黑底里切出来。
  黑 (0,0,0) 与红 (240,30,30) 在任何缩放、任何 h264 量化下都不会混淆。
- **正中央一个白色正方形描边**：它在屏幕上量出来的**高宽比就是"有没有被拉伸"的直接读数**。
  正确 → 1.00；把 16:9 铺进 1080×2400 整屏 → 0.25。相差 4 倍，不需要靠肉眼判断。
- **顶部一个横向移动的白块**：证明"这真的在播"，而不是停在第一帧的静止画面。
  它同时是**暂停判据**：连拍两帧、白块位置不变 → 画面确实停住了。
  位置刻意放在画面顶部 5% —— `square_of()` 会把条带最上面 25% 切掉，
  白块的包围盒因此永远不会污染正方形的量测。
- **底部一条红噪声**（只横屏那条有）：把明文顶过 1 MiB，让分块密文的落盘体积
  与竖屏那条**分档**，同时把"跨块读取"这条路真的走一遍。
  见 `render()` 里 `grain_rows` 的注释 —— 它是**红色**的噪声，所以仍然被
  `red_mask` 认、又绝不被 `white_mask` 认，两条量测都不受影响。

## 为什么必须用 numpy

前两版是逐像素 Python 循环。320×180 跑得动，480×854 是 41 万像素/帧 ——
拉长到 8 秒就是 160 帧、6500 万次下标写入，慢到没法迭代。
改成"整帧 numpy 数组 → `VideoFrame.from_ndarray`"之后是秒级。

## 输出

    build/videos/landscape.mp4   320×180（16:9），120 秒，**多块**（底部有红噪声，见下）
    build/videos/portrait.mp4    480×854（9:16），8 秒

横屏那条**必须超过 1 MiB**，否则分块密文的落盘体积与竖屏那条**一模一样**
（块大小固定 1 MiB，两条都只有一块 → 都是 `24 + 1×28 + 明文长度`），
走查"按体积认领是哪一段素材"就失效；而且一块的文件永远读在第 0 块里，
跨块边界、块号参与 AAD、末块标记这些**一个都验不到**。
底部那几行红噪声就是为这两件事铺的（生成时会断言块数 ≥ 2）。

横屏的时长是**照着走查的实际节奏倒推的**，已经加长过两次：

  · 10 秒 → 60 秒：按钮状态还没检查，片子就放完了，
    "点暂停后按钮变成播放"于是变成一条**自己满足自己**的断言；
  · 60 秒 → 120 秒（2026-09-17）：release 包上的节奏更慢 ——
    打开 → 连拍取"正在播"的证据（8 帧要 22 秒）→ 4 帧快测（10 秒）→
    `reveal_controls` 找「暂停」键（10 秒），等要点暂停时已经过了 51 秒；
    三次重试跨过 60 秒线之后片子播完，probe 于是读到"停住"（因为没得播了），
    判据响亮地失败。

这一段的判据（拖进度条、切后台再回来画面还在不在）**必须在"仍在播"的状态下做**，
片子比"走查全程"短就只能撞上"刚好播完"，而"刚好播完"会让这些断言失去意义。
120 秒是照 2026-09-17 那轮的实测倒推：走查全程约 83 秒，留五成余量。

竖屏只抓单帧、验的是比例，8 秒够用，多造只会拖长生成时间（480×854 每秒 1.2 MB）。

两个方向都要：横屏验常规路径，**竖屏验"竖拍视频不横躺"** ——
手机竖拍的真实形态就是"横向帧 + 旋转标记"，而这里用真实的竖向分辨率，
验的是另一半（记录里的宽高是否被摆正、播放窗口是否按竖向比例摆放）。
"""

import os
import sys

import av
import numpy as np
from av.video.frame import VideoFrame

OUT_DIR = r"D:\MixiaVault\build\videos"
FPS = 20

RED = (240, 30, 30)
WHITE = (255, 255, 255)


def render(width, height, seconds, square_side, grain_rows=0, seed=20260917):
    """整段影片的所有帧，一次性算完（numpy 广播）。

    ## `grain_rows`：底部那几行红噪声，不是装饰

    两个硬理由，都是走查逼出来的：

    1. **分块格式的落盘体积必须能唯一认领素材。** 分块文件的体积是
       `24 + 块数 × (nonce 12 + tag 16) + 明文长度`（**末块是短的**，
       不能按"块数 × 块大小"算），而块大小固定 1 MiB —— 两段素材只要
       都小于 1 MiB，块数就都是 1，落盘体积只差各自的明文长度 ——
       而两段素材的明文长度本来就不同，所以真正的问题是**块数不分档**：
       走查那边"按体积认领是哪一段"会退化成"两段都只读第 0 块"，
       跨块的路径一次都没被走过。让横屏这条突破 1 MiB，块数就分档了。
    2. **单块文件根本没验到分块偏移算术。** 一块的文件，随机读取永远落在
       第 0 块里 —— 跨块边界、块号参与 AAD、末块标记这些全都没被碰过。
       刻意让它变成多块，这一段才有内容。

    噪声必须是**红噪声**（R 高、G/B 低），这样它同时满足两条：
    `red_mask` 仍然认它（否则条带量测会断），`white_mask` 绝不认它
    （否则白像素包围盒会被噪声撑歪，正方形比例就废了）。
    """
    total = FPS * seconds
    cx, cy = width // 2, height // 2
    half = square_side // 2

    # 背景：整幅纯红
    frames = np.zeros((total, height, width, 3), dtype=np.uint8)
    frames[:, :, :] = RED

    # 噪声只铺在**底部**：顶部 25% 是白块的量测区、中间是正方形，都不能碰。
    if grain_rows:
        g0 = height - grain_rows
        rng = np.random.default_rng(seed)
        shape = (total, grain_rows, width)
        frames[:, g0:, :, 0] = rng.integers(200, 256, shape, dtype=np.uint8)
        frames[:, g0:, :, 1] = rng.integers(0, 41, shape, dtype=np.uint8)
        frames[:, g0:, :, 2] = rng.integers(0, 41, shape, dtype=np.uint8)

    # 中央正方形描边。边长写成 2*half+1（两端闭合），量出来的高宽比必须 ≈1.000。
    t = 3  # 线宽
    y_from, y_to = cy - half, cy + half
    x_from, x_to = cx - half, cx + half
    frames[:, y_from:y_from + t, x_from:x_to + 1] = WHITE
    frames[:, y_to - t + 1:y_to + 1, x_from:x_to + 1] = WHITE
    frames[:, y_from:y_to + 1, x_from:x_from + t] = WHITE
    frames[:, y_from:y_to + 1, x_to - t + 1:x_to + 1] = WHITE

    # 顶部的横向移动白块：只占画面 5%~14% 那一条，远离正方形
    bh, bw = max(8, height // 11), max(16, width // 13)
    by0 = max(2, int(height * 0.05))
    by1 = by0 + bh
    span = width - bw
    for i in range(total):
        bx = int(span * (i / max(1, total - 1)))
        frames[i, by0:by1, bx:bx + bw] = WHITE

    return frames, (x_to - x_from + 1, y_to - y_from + 1), (by0, by1)


def write(path, frames, width, height):
    if os.path.exists(path):
        os.remove(path)

    container = av.open(path, mode="w")
    stream = container.add_stream("libx264", rate=FPS)
    stream.width = width
    stream.height = height
    stream.pix_fmt = "yuv420p"
    # baseline + 每 10 帧一个关键帧：`getFrameAtTime(OPTION_CLOSEST_SYNC)` 才不用为了
    # 取一张封面把整段解完（那是首帧封面的耗时来源）。
    stream.options = {"crf": "20", "preset": "ultrafast",
                      "profile": "baseline", "g": "10"}

    for arr in frames:
        # from_ndarray 要求 C 连续的 (h, w, 3) uint8
        frame = VideoFrame.from_ndarray(np.ascontiguousarray(arr), format="rgb24")
        for packet in stream.encode(frame):
            container.mux(packet)

    for packet in stream.encode():
        container.mux(packet)
    container.close()


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    spec = [
        # 文件名, 宽, 高, 秒数, 正方形边长（取短边的 1/4 上下，两个方向上都能完整放进画面）,
        # 底部红噪声行数（0 = 不铺；见 render 的注释，横屏那段要靠它突破一块）
        ("landscape.mp4", 320, 180, 120, 46, 12),
        ("portrait.mp4", 480, 854, 8, 120, 0),
    ]

    print(f"{'文件':<16}{'尺寸':<12}{'时长':<8}{'大小':<12}{'正方形(源)':<12}源宽高比")
    for name, w, h, sec, side, grain in spec:
        frames, (sq_w, sq_h), block = render(w, h, sec, side, grain)
        path = os.path.join(OUT_DIR, name)
        write(path, frames, w, h)
        print(f"{name:<16}{f'{w}x{h}':<12}{f'{sec}s':<8}"
              f"{f'{os.path.getsize(path) / 1024:.0f} KB':<12}"
              f"{f'{sq_w}x{sq_h}':<12}{w / h:.4f}")
        print(f"    白块 y {block[0]}..{block[1]}（占画面 {block[0] / h:.1%}~{block[1] / h:.1%}，"
              f"应在 square_of 切掉的顶部 25% 以内）")
        print(f"    源正方形边长/短边 = {sq_w / min(w, h):.4f}，渲染后高宽比应为 1.000")
        sq_top = (h - sq_h) / 2
        # 三条几何前提，破了它们 `square_of()` 量出来的就不是正方形：
        # ① 白块整个落在被切掉的顶部 25% 里；② 正方形的上边在被切区域之下；
        # ③ 噪声行（若有）整个在正方形下边之下。
        assert block[1] / h < 0.25, f"{name} 的白块跑出了顶部 25%，会污染正方形量测"
        assert sq_top / h > 0.25, (
            f"{name} 的正方形上边在 {sq_top / h:.1%}，落在顶部 25% 的切法里，会被削掉一条边")
        assert sq_top > block[1], f"{name} 的正方形与白块重叠"
        if grain:
            g0 = h - grain
            sq_bottom = (h + sq_h) / 2
            assert g0 > sq_bottom, (
                f"{name} 的噪声行从 y={g0} 开始，正方形下边在 y={sq_bottom:.0f}"
                f" —— 噪声会顶到正方形，破坏比例量测")
            # 分块落盘体积分档的前提：这条素材必须超过一块（1 MiB）。
            n_chunks = (os.path.getsize(path) + (1 << 20) - 1) // (1 << 20)
            size = os.path.getsize(path)
            # 落盘 = 头 24 + 每块 28（nonce 12 + tag 16）+ 明文总长（末块是短的）。
            print(f"    噪声 {grain} 行（y {g0}..{h - 1}）；明文 {size}B "
                  f"→ 分块落盘 {24 + n_chunks * 28 + size}B（{n_chunks} 块）")
            assert n_chunks >= 2, (
                f"{name} 只有 {n_chunks} 块 —— 走查那边两段素材的落盘体积会分不开档，"
                f"而且跨块读取完全没被覆盖。把 grain 调大。")

    sys.stdout.flush()


if __name__ == "__main__":
    main()
