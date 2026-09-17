#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""为"实况照片播放会不会被拉伸"造一套**肉眼可辨**的测试素材。

## 为什么原来的素材不够用

`tools/motion/motion-photo.jpg` 的影片段是随手录的一小段，内容是什么没人说得清。
拿它验"有没有变形"有个致命缺口：**纯色画面被拉伸，看起来还是纯色** ——
1080×607 的一块红和 1080×2400 的一块红，在截图里都一样是"一块红"。
上一版正是靠肉眼比对，才让"纵向拉长 25%"活了整整一个版本。

## 这一套的做法

影片画面里画一个**正方形**。几何是比例的唯一尺子：

    正确显示（4:3 影片 → 1080 宽的 16:9 带）→ 正方形渲染出来还是正方形
    被拉伸铺满 1080×2400            → 同一个正方形变成又窄又高的长方形

配套两点便于机器量：

- 底色用**纯红**，静止图用**深蓝**：色相差得远，"影片区域"用颜色就能一刀切出来，
  不必去猜"这块像素到底是不是影片"。
- 顶部一个白块随帧横向移动：证明这一帧**真的是在播放**，不是把静止图当影片摆着。

## 影片为什么选 16:9

`ImageViewerScreen.kt` 里记的那个现场就是 16:9（1080×1920 的影片放在 1080×2400 的屏上，
纵向拉长 25%）。素材按现实里最常见的比例来，结论才对得上。
"""

import io
import os
import sys
from fractions import Fraction

import av
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

HERE = os.path.dirname(os.path.abspath(__file__))
BASE_OUT = os.path.join(HERE, "probe-base.jpg")
CLIP_OUT = os.path.join(HERE, "probe-clip.mp4")
MOTION_OUT = os.path.join(HERE, "probe-motion.jpg")
PREVIEW_OUT = os.path.join(HERE, "probe-preview.png")

BASE_W, BASE_H = 1080, 2400          # 9:20，与模拟器屏幕同比例 → 铺满整屏，不留黑边
CLIP_W, CLIP_H = 320, 180            # 16:9
FPS = 30
DURATION = 4.0                       # 秒。太短会撞上 screencap 的采样周期（见坑 10）
FRAMES = int(FPS * DURATION)

NAVY = (11, 27, 51)
GRID = (30, 110, 140)
RED = (224, 16, 16)
WHITE = (255, 255, 255)

# 影片画面里那个正方形的边长（源像素）。它渲染出来是不是正方形，就是比例判据。
SQUARE = 60
SQUARE_LINE = 6


def font(size):
    try:
        return ImageFont.load_default(size=size)
    except TypeError:                      # 老 Pillow 不接受 size
        return ImageFont.load_default()


def make_base():
    """静止图：深蓝底 + 青色网格 + 白色说明字。铺满整屏。"""
    im = Image.new("RGB", (BASE_W, BASE_H), NAVY)
    d = ImageDraw.Draw(im)
    for x in range(0, BASE_W, 120):
        d.line([(x, 0), (x, BASE_H)], fill=GRID, width=2)
    for y in range(0, BASE_H, 120):
        d.line([(0, y), (BASE_W, y)], fill=GRID, width=2)

    d.text((90, 300), "STILL PHOTO", font=font(96), fill=WHITE)
    d.text((90, 420), "no motion video playing", font=font(44), fill=(150, 190, 210))
    d.text((90, 2020), "expected: this blue page", font=font(44), fill=(150, 190, 210))
    d.text((90, 2090), "with a red band in the middle", font=font(44), fill=(150, 190, 210))

    im.save(BASE_OUT, quality=88)
    print(f"静止图  {BASE_W}×{BASE_H}  {os.path.getsize(BASE_OUT):>8,} B  {BASE_OUT}")


def clip_frame(i):
    """第 i 帧的影片画面：红底 + 中央白正方形 + 顶部横向移动的白块。"""
    im = Image.new("RGB", (CLIP_W, CLIP_H), RED)
    d = ImageDraw.Draw(im)

    # 中央正方形（描边）。它被渲染成什么形状，直接反映横向/纵向缩放是否一致。
    cx, cy = CLIP_W // 2, CLIP_H // 2
    h = SQUARE // 2
    d.rectangle([cx - h, cy - h, cx + h, cy + h], outline=WHITE, width=SQUARE_LINE)

    # 刻意**不画**横贯全宽的分隔线：那会让"白色像素的包围盒"直接等于整幅画面宽度，
    # 量正方形的脚本就再也分不出它和别的白东西。留在画面里的白色物体越少越好量。

    # 移动白块：证明这是"正在播放"而不是摆着一帧静止画面
    x = 16 + (CLIP_W - 32 - 18) * i / max(1, FRAMES - 1)
    d.rectangle([x, 14, x + 18, 32], fill=WHITE)
    return im


def make_clip():
    container = av.open(CLIP_OUT, mode="w")
    stream = container.add_stream("libx264", rate=FPS)
    stream.width, stream.height = CLIP_W, CLIP_H
    stream.pix_fmt = "yuv420p"
    # baseline + 无 B 帧 + 密集 I 帧：模拟器上的软解最不容易挑剔
    stream.options = {"crf": "18", "preset": "ultrafast", "profile": "baseline", "g": "10"}

    for i in range(FRAMES):
        frame = av.VideoFrame.from_image(clip_frame(i)).reformat(format="yuv420p")
        frame.pts = i
        frame.time_base = Fraction(1, FPS)
        for packet in stream.encode(frame):
            container.mux(packet)
    for packet in stream.encode():
        container.mux(packet)
    container.close()

    with av.open(CLIP_OUT) as c:
        s = c.streams.video[0]
        print(f"影片    {s.codec_context.width}×{s.codec_context.height}  "
              f"{float(s.duration * s.time_base):.2f} s  {s.frames or '?'} 帧  "
              f"{os.path.getsize(CLIP_OUT):>8,} B  {CLIP_OUT}")

    # Android 的 MediaDataSource 是随机读取的，但"盒长自洽"这类便宜的自检还是做一下：
    # 影片字节数必须过得了 MotionPhoto.MIN_VIDEO_BYTES，否则应用直接不认。
    if os.path.getsize(CLIP_OUT) < 1024:
        raise SystemExit("影片段小于 1024 B，应用不会认它")

    # 顺手落一帧成 PNG，给判据自测（selftest_measure.py）当输入。
    # **必须由这里生成**：早先自测读的是一个手工存的预览图，影片改版后它没跟着更新，
    # 于是自测量的还是旧画面（带一条横贯全宽的白线），量出来的正方形比例是 5.27 ——
    # 看着像判据坏了，实际是素材陈旧。生成物就该跟素材一起产出。
    with av.open(CLIP_OUT) as c:
        for i, frame in enumerate(c.decode(video=0)):
            if i == FRAMES // 2:
                frame.to_image().save(PREVIEW_OUT)
                print(f"预览帧  第 {i} 帧 → {PREVIEW_OUT}")
                break


def make_motion_photo():
    """把静止图与影片拼成一张实况照片，XMP 复用 make_motion_photo.py 的写法。

    刻意 import 而不是抄一遍：XMP 里 Primary/Videophoto 的 Length 必须逐字节正确
    （差 1 字节系统就不认），两处写法一旦分叉就会得到"合成成功但识别不到"这种
    最难查的结果。
    """
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "mp", os.path.join(HERE, "make_motion_photo.py"))
    mp = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mp)

    jpeg = io.open(BASE_OUT, "rb").read()
    clip = io.open(CLIP_OUT, "rb").read()

    image_len = len(jpeg)
    for _ in range(8):
        seg = mp.build_xmp_segment(image_len, len(clip))
        candidate = len(jpeg) + len(seg)
        if candidate == image_len:
            break
        image_len = candidate
    else:
        raise SystemExit("XMP 长度没有收敛")

    seg = mp.build_xmp_segment(image_len, len(clip))
    out = mp.insert_after_soi(jpeg, seg)
    if len(out) != image_len:
        raise SystemExit(f"自检失败：算出 {image_len}，实际 {len(out)}")
    out += clip
    io.open(MOTION_OUT, "wb").write(out)

    print(f"实况照片  图片段 {image_len:,} B + 影片段 {len(clip):,} B = {len(out):,} B  {MOTION_OUT}")


if __name__ == "__main__":
    make_base()
    make_clip()
    make_motion_photo()
