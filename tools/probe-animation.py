#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""双击诊断：看 handleDoubleTap 之后动画到底有没有把 transform 改掉。

复用 part2 的"回到主页 / 进照片库"逻辑（它已经处理了冷启动与锁定态）。
"""

import importlib.util
import sys
import time

from PIL import Image


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


P2 = load(r"D:\MixiaVault\tools\walkthrough-v106-part2.py", "p2")
W = P2.W
W.OUT = r"D:\MixiaVault\dist\evidence\v106-probe"
W.SHOT_INDEX = 90


def measure(path):
    im = Image.open(path).convert("RGB")
    px = im.load()
    w, h = im.size
    x0, x1, y0, y1 = w, -1, h, -1
    for y in range(280, h, 2):
        for x in range(0, w, 3):
            if y >= 2350 and 350 <= x <= 730:
                continue
            r, g, b = px[x, y]
            if r + g + b > 66:
                x0, x1 = min(x0, x), max(x1, x)
                y0, y1 = min(y0, y), max(y1, y)
    return f"{x1 - x0 + 1}×{y1 - y0 + 1} @ y{y0}..{y1}（x{x0}..{x1}）" if x1 >= 0 else "无"


def main():
    W.adb("install", "-r", "-g", P2.APK_DEBUG)
    P2.ensure_home()
    P2.open_photo_library()
    cells = sorted([n for n in W.nodes() if n["clickable"]
                    and not (n["text"] or n["desc"]) and 250 < n["cy"] < 1900],
                   key=lambda n: (n["cy"], n["cx"]))
    W.tap(cells[-1]["cx"], cells[-1]["cy"])
    W.wait_text("/ 4", timeout=25, what="大图")
    time.sleep(1.5)
    print(f"打开后：{measure(W.shot('动画诊断-打开'))}")

    W.adb("logcat", "-c")
    W.adb("shell", "input tap 540 1200; input tap 540 1200")
    time.sleep(2.5)
    print(f"双击后：{measure(W.shot('动画诊断-双击后'))}")

    print("--- FPB-TAP 日志 ---")
    for l in W.adb("logcat", "-d", "-s", "FPB-TAP").splitlines():
        if "FPB-TAP" in l:
            print("   ", l.split("FPB-TAP", 1)[1].strip())


if __name__ == "__main__":
    main()
