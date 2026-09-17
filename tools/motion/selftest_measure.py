#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自测 `walkthrough-v109-motion.py` 里的量算法 —— **先证明判据会失败**。

## 为什么判据需要"自证会失败"

"包围盒量出来是 1080×607"这句话本身不构成证据，除非能同时说明：
如果画面真的被拉伸，同一段量代码**会**给出另一组数字。
不然它可能只是"无论怎样都返回 607"——那种判据看着很绿，实际什么都没测。

所以这里用同一份影片帧，手工合成两张"屏幕上应该长什么样"的图：

    正确（16:9 铺进 1080 宽）→ 期望条带 1080×607、正方形比例 1.00
    出错（铺满 1080×2400）  → 期望条带 1080×2400、正方形比例 0.25

然后用走查脚本里**同一个** `band_of` / `square_of` 去量，看它对不对得上。
两张图的期望值差 4 倍，判据不可能同时通过 —— 这就是它有效性的证明。

（这条也是被真事逼出来的：上一版验收里有一条判据"实际大小下黑边必须消失"，
对"1600 < 2400"的竖图在数学上**永远不可能成立**，应用明明是对的却判 FAIL。
凡是"必须等于某个绝对值"的判据，先算一遍期望值再写。）
"""

import importlib.util
import os
import sys

from PIL import Image

BASE = r"D:\MixiaVault"
OUT = rf"{BASE}\dist\evidence\v109-motion"
VW, VH = 1080, 2400


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


M = load(rf"{BASE}\tools\walkthrough-v109-motion.py", "v109")

FAIL = []


def check(label, ok, detail):
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}：{detail}")
    if not ok:
        FAIL.append(label)


def compose(mode):
    """按 [mode] 把影片帧贴到静止图上，复现"屏幕上应该长什么样"。"""
    still = Image.open(rf"{BASE}\tools\motion\probe-base.jpg").convert("RGB").resize((VW, VH))
    clip = Image.open(rf"{BASE}\tools\motion\probe-preview.png").convert("RGB")

    canvas = still.copy()
    if mode == "fit":
        w, h = VW, round(VW * clip.size[1] / clip.size[0])     # 1080 × 607
    else:
        w, h = VW, VH                                          # 1080 × 2400（被拉满）
    canvas.paste(clip.resize((w, h)), (0, (VH - h) // 2))
    return canvas, w, h


def main():
    os.makedirs(OUT, exist_ok=True)

    print("=== 1. 正确显示：16:9 铺进 1080 宽 ===")
    img, w, h = compose("fit")
    img.save(rf"{OUT}\selftest-正确.png")
    band = M.band_of(img)
    assert band, "量不到条带 —— 判据连正确画面都认不出来"
    sq = M.square_of(img, band)
    print(f"    {M.describe(band, sq)}")
    check("条带高度 = 影片按比例应得的高度", abs(band["h"] - h) <= 6,
          f"期望 {h}，实测 {band['h']}")
    check("条带横向铺满", band["x0"] <= 2 and band["x1"] >= VW - 3,
          f"x[{band['x0']},{band['x1']}]")
    check("正方形比例 ≈ 1.00", sq and abs(sq["ratio"] - 1.0) <= 0.06,
          f"实测 {sq['ratio']:.3f}" if sq else "量不到正方形")

    print()
    print("=== 2. 被拉伸铺满整屏（要能判出来）===")
    img2, w2, h2 = compose("stretch")
    img2.save(rf"{OUT}\selftest-被拉伸.png")
    band2 = M.band_of(img2)
    assert band2, "量不到条带"
    sq2 = M.square_of(img2, band2)
    print(f"    {M.describe(band2, sq2)}")
    check("条带高度 = 整屏（缺陷可被量出来）", band2["h"] >= 2100,
          f"实测 {band2['h']}")
    check("正方形被压成 0.25 左右（缺陷可被量出来）",
          sq2 and abs(sq2["ratio"] - 0.25) <= 0.06,
          f"实测 {sq2['ratio']:.3f}" if sq2 else "量不到正方形")

    print()
    print("=== 3. 静止图（没有影片）必须量不到条带 ===")
    check("深蓝底静止图里找不到红带", M.band_of(Image.open(rf"{BASE}\tools\motion\probe-base.jpg").convert("RGB")) is None,
          "band_of 返回 None")

    print()
    print("=== 4. 被拉伸 + 系统手势条（白色胶囊）在场时，正方形的读数不能被它带跑 ===")
    from PIL import ImageDraw
    img3, _, _ = compose("stretch")
    d = ImageDraw.Draw(img3)
    d.rectangle([398, 2364, 681, 2373], fill=(255, 255, 255))   # 真机实测的手势条位置
    img3.save(rf"{OUT}\selftest-被拉伸-带手势条.png")
    band3 = M.band_of(img3)
    sq3 = M.square_of(img3, band3)
    print(f"    {M.describe(band3, sq3)}")
    check("正方形读数不受手势条影响（仍 ≈0.25）",
          sq3 and abs(sq3["ratio"] - 0.25) <= 0.06,
          f"实测 {sq3['ratio']:.3f}" if sq3 else "量不到正方形")
    check("量到的就是正方形本身，而不是白胶囊",
          sq3 and not (sq3["x0"] <= 400 or sq3["x1"] >= 680),
          f"x[{sq3['x0']},{sq3['x1']}]，手势条在 x 398..681" if sq3 else "量不到")

    print()
    if FAIL:
        print(f"判据自测未通过：{FAIL}")
        return 1
    print("判据自测通过：正确画面与缺陷画面给出的是两组分得开的数字")
    return 0


if __name__ == "__main__":
    sys.exit(main())
