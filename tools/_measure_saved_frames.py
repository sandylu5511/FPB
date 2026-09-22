#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线复量某次走查落盘的连拍帧 —— 不碰设备，只用走查自己那套量算法。

    python tools/_measure_saved_frames.py <证据目录> [文件名前缀 ...]

## 为什么要有这个

`返回后还能重新播放` 那条判据红了，而它的现场（连拍帧）**已经落在证据目录里**。
既然量算法是纯像素函数，就没有理由再上设备复现一次 —— 直接对落盘的 PNG 复量。
这比"再跑一遍"更快，也比"看日志猜"更硬：同一份图，谁都能量出同一个数。

输出的每一行都是 `band_of` + `block_x` 的原始读数，外加按夹具几何反推的**秒数**：

    进度 = (质心 - 块宽/2) / (条带宽 - 块宽)
    秒数 = 进度 × 片长

（白块从最左匀速走到最右，走完正好一个「条带宽 − 块宽」，这是它的定义式。）

另外打一个 md5 前 8 位：**逐字节相同的帧**说明画面根本没变，
这是"冻住了"最直接的证据，比"质心没变"更强（质心可能有量测噪声，md5 不会有）。
"""
import hashlib
import importlib.util
import os
import sys

import numpy as np
from PIL import Image

BASE = r"D:\MixiaVault"


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V110 = load(rf"{BASE}\tools\walkthrough-v110-video.py", "v110")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out_dir = sys.argv[1]
    prefixes = sys.argv[2:] or ["B-横屏-返回后重播"]

    names = sorted(n for n in os.listdir(out_dir)
                   if n.endswith(".png") and any(n.startswith(p) for p in prefixes))
    if not names:
        sys.exit(f"{out_dir} 里没有匹配 {prefixes} 的文件")

    duration_s = V110.CLIPS[V110.MOTION_CLIP]["duration_ms"] / 1000.0

    header = f"{'文件':<32}{'条带':<14}{'块 x0..x1':<16}{'宽':<6}{'质心':<8}{'反推秒数':<10}"
    print(header + "md5")
    print("-" * len(header) + "-" * 10)

    prev = None
    for n in names:
        p = os.path.join(out_dir, n)
        raw = open(p, "rb").read()
        md5 = hashlib.md5(raw).hexdigest()[:8]
        a = np.asarray(Image.open(p).convert("RGB"), dtype=np.uint8)

        band = V110.band_of(a)
        if not band:
            print(f"{n:<32}{'量不到条带':<14}{'':<16}{'':<6}{'':<8}{'':<10}{md5}")
            prev = md5
            continue
        blk = V110.block_x(a, band)
        band_txt = f"{band['w']}x{band['h']}"
        if not blk:
            print(f"{n:<32}{band_txt:<14}{'量不到白块':<16}{'':<6}{'':<8}{'':<10}{md5}")
            prev = md5
            continue

        # 换算走 `V110.implied_secs`，不在这里另写一遍：同一件事写两处，
        # 迟早一处改了另一处没改（这个仓库已经吃过几次这个亏）。
        secs = V110.implied_secs(blk["cx"], band["w"], blk["w"])
        secs_txt = "量不出" if secs is None else f"{secs:.1f}s"
        tag = "  ← 与上一帧逐字节相同" if md5 == prev else ""
        x_span = f"{blk['x0']}..{blk['x1']}"
        print(f"{n:<32}{band_txt:<14}{x_span:<16}"
              f"{blk['w']:<6}{blk['cx']:<8.1f}{secs_txt:>8}   {md5}{tag}")
        prev = md5


if __name__ == "__main__":
    main()
