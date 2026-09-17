#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在**碰模拟器之前**，先用走查自己的量测函数体检一遍新素材。

## 为什么要有这一步

这一轮给横屏素材加了底部红噪声（为了让分块密文的落盘体积与竖屏那条**分档**，
顺带把跨块读取覆盖上）。噪声是个双刃剑：

  · 它必须**仍然被 `red_mask` 认**（R>140, G<90, B<90）—— 否则条带量测会断，
    走查会在"画面是黑的"这个错误方向上查半天；
  · 它必须**绝不被 `white_mask` 认**（R,G,B 全 >200）—— 否则白像素包围盒会被
    噪声撑歪，那个"正方形比例 1.000"的判据就废了，而它恰是本轮几何结论的核心。

这两件事在**源码像素上**成立，不等于在**解码之后**还成立（4:2:0 色度下采样
加上量化会把像素值挪一截）。所以不能靠"我算的时候 R 是 200 以上"来推结论 ——
要拿真正解出来的帧去量。

## 量法

把解码帧按播放器的实际摆法合成到 1080×2400 黑底上（**按比例居中**，
与 `VideoPage` 里 `Modifier.aspectRatio(aspect)` 的布局一致），
然后**直接调用走查模块里的 `band_of` / `square_of` / `block_x`** ——
不复制一份：用哪套量，就用哪套验。

噪声区不靠"行数常量"定位（那是第二份真值，迟早与 `_mkvideos.py` 对不上），
直接取**条带底部 10%** —— 噪声本来就是铺在画面最下沿的。
"""

import importlib.util
import os
import sys

import av
import numpy as np
from PIL import Image

BASE = r"D:\MixiaVault"
VIEW_W, VIEW_H = 1080, 2400
BAD = []


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


WALK = load(rf"{BASE}\tools\walkthrough-v110-video.py", "w110geo")


def composite(frame_rgb, src_w, src_h):
    """按播放器的摆法合成：铺满 1080 宽、按比例居中、其余留黑。"""
    h = int(round(VIEW_W * src_h / src_w))
    img = Image.fromarray(frame_rgb).resize((VIEW_W, h), Image.LANCZOS)
    canvas = Image.new("RGB", (VIEW_W, VIEW_H), (0, 0, 0))
    canvas.paste(img, (0, (VIEW_H - h) // 2))
    return np.asarray(canvas, dtype=np.uint8)


def probe(name, spec):
    src = os.path.join(BASE, "build", "videos", name)
    print(f"\n===== {name}（{spec['tag']}）=====")
    n_chunks = (os.path.getsize(src) + (1 << 20) - 1) // (1 << 20)
    # **末块是短的**：Σ(块明文) = 明文总长，不是 N × 块大小。
    # 早先这里写成 `24 + N × (12 + 块大小 + 16)`，把末块按满块算，
    # 于是横屏报 5 243 044（设备实际 5 197 651）、竖屏报 1 048 628（设备实际 109 481）。
    # 这种"独立实现错在期望值上"最误导人：报出来的失败跟产品缺陷长得一模一样。
    stored = 24 + n_chunks * (12 + 16) + os.path.getsize(src)
    print(f"  源文件 {os.path.getsize(src)}B → 分块落盘应是 {stored}B（{n_chunks} 块）")
    print(f"  预期条带高 {spec['band_h']:.1f}px")

    want_times = [1, 2, 5, 10, 20, 30, 45, 58]
    with av.open(src) as c:
        st = c.streams.video[0]
        sw, sh = st.width, st.height
        dur = c.duration / av.time_base
        # 只采**这段片子真的有时刻**的采样点。竖屏只有 8 秒，按同一张表去取
        # 10s/20s/… 会得到 5 条"没有解出帧"的假失败 —— 而它们看起来像编解码问题。
        want_times = [t for t in want_times if t < dur]
        got = {}
        for frame in c.decode(st):
            t = float(frame.pts * st.time_base)
            for wt in want_times:
                if wt not in got and t >= wt:
                    got[wt] = composite(frame.to_ndarray(format="rgb24"), sw, sh)
            if len(got) == len(want_times):
                break
        print(f"  容器时长 {dur:.2f}s，采样点 {want_times}")

    blocks = []
    for wt in want_times:
        a = got.get(wt)
        if a is None:
            BAD.append(f"{name}：{wt}s 处没有解出帧")
            continue
        band = WALK.band_of(a)
        if band is None:
            BAD.append(f"{name}：{wt}s 的帧量不到红底条带 —— 噪声把 red_mask 顶掉了")
            continue
        sq = WALK.square_of(a, band)
        blk = WALK.block_x(a, band)

        dh = band["h"] - spec["band_h"]
        ok_h = abs(dh) <= 3
        ok_w = band["x0"] <= 2 and band["x1"] >= VIEW_W - 3
        ok_sq = sq is not None and abs(sq["ratio"] - 1.0) <= 0.02

        # 噪声区（条带底部 10%）的两条硬要求
        m = WALK.white_mask(a).copy()
        ny0 = band["y0"] + int(0.90 * band["h"])
        noise_white = int(m[ny0:band["y1"] + 1, :].sum())
        sub = a[ny0:band["y1"] + 1, band["x0"]:band["x1"] + 1]
        min_r = int(sub[..., 0].min())
        max_gb = int(sub[..., 1:].max())
        ok_white = noise_white == 0
        ok_red = min_r > 141 and max_gb < 89

        if blk:
            blocks.append((wt, round(blk["cx"], 1)))
        sq_txt = (f"{sq['w']}×{sq['h']}={sq['ratio']:.3f}") if sq else "量不到"
        blk_txt = (f"x{blk['x0']}..{blk['x1']}") if blk else "量不到"
        print(f"  {wt:>3}s  条带 {band['w']}×{band['h']}（Δ{dh:+.0f}）  "
              f"正方形 {sq_txt}  白块 {blk_txt}")
        print(f"        [{'OK' if ok_h else 'BAD'}] 条带高   "
              f"[{'OK' if ok_w else 'BAD'}] 横向铺满   "
              f"[{'OK' if ok_sq else 'BAD'}] 正方形   "
              f"[{'OK' if ok_white else 'BAD'}] 噪声区白像素={noise_white}   "
              f"[{'OK' if ok_red else 'BAD'}] 噪声区 R≥{min_r}>141、G/B≤{max_gb}<89")

        if not ok_h:
            BAD.append(f"{name}：{wt}s 条带高 {band['h']}，预期 {spec['band_h']:.0f}")
        if not ok_w:
            BAD.append(f"{name}：{wt}s 条带没铺满 x[{band['x0']},{band['x1']}]")
        if not ok_sq:
            BAD.append(f"{name}：{wt}s 正方形比例 {sq and round(sq['ratio'], 3)}")
        if not ok_white:
            BAD.append(f"{name}：{wt}s 噪声区出现 {noise_white} 个白像素 —— 会撑歪正方形量测")
        if not ok_red:
            BAD.append(f"{name}：{wt}s 噪声区贴到 red_mask 阈值（R={min_r}, G/B={max_gb}）")

    spread = (max(x[1] for x in blocks) - min(x[1] for x in blocks)) if len(blocks) > 1 else 0
    print(f"  白块质心（{len(blocks)} 个采样）：{blocks}")
    print(f"        [{'OK' if spread > 20 else 'BAD'}] 跨帧位移 {spread:.1f}px"
          f" —— 「确实在播」的读数，全片必须一直在动")
    if spread <= 20:
        BAD.append(f"{name}：跨帧白块位移只有 {spread:.1f}px，动不起来")


def main():
    for name, spec in WALK.CLIPS.items():
        probe(name, spec)

    print()
    if BAD:
        print(f"共 {len(BAD)} 条不合格：")
        for b in BAD:
            print(f"  · {b}")
        sys.exit(1)
    print("素材几何自检全部通过（用的是走查自己的 band_of / square_of / block_x）")


if __name__ == "__main__":
    main()
