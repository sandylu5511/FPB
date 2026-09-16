#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""双击诊断：为什么双击没有触发缩放。

查两件事：
  1. 两次 `input tap` 之间的真实间隔（Compose 的双击窗口是 40ms ~ 300ms）；
  2. logcat 里有没有异常（如果回调抛了异常，界面同样不会动）。
另外试几种不同的双击姿势，看哪种能被识别。
"""

import importlib.util
import re
import sys
import time

from PIL import Image

PKG = "com.fpb.vault"


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V105 = load(r"D:\MixiaVault\tools\walkthrough-v105.py", "w105")
W = V105.W
W.OUT = r"D:\MixiaVault\dist\evidence\v106-probe"
W.SHOT_INDEX = 60


def measure(path):
    im = Image.open(path).convert("RGB")
    px = im.load()
    w, h = im.size
    x0, x1, y0, y1 = w, -1, h, -1
    for y in range(280, h):
        for x in range(0, w, 3):
            if y >= 2350 and 350 <= x <= 730:
                continue
            r, g, b = px[x, y]
            if r + g + b > 66:
                x0 = min(x0, x)
                x1 = max(x1, x)
                y0 = min(y0, y)
                y1 = max(y1, y)
    return f"{x1 - x0 + 1}×{y1 - y0 + 1} @ y{y0}..{y1}" if x1 >= 0 else "无"


def timed(cmd):
    t = time.time()
    W.adb("shell", cmd)
    return (time.time() - t) * 1000


def main():
    # 单个 tap 的耗时（它就是两次点击之间间隔的主要成分）
    t1 = timed("input tap 540 1200")
    t2 = timed("input tap 540 1200; input tap 540 1200")
    print(f"单次 input tap 耗时 ≈ {t1:.0f} ms；两次串行总耗时 ≈ {t2:.0f} ms")
    print(f"→ 两次点击之间大约相隔 {t2 - t1:.0f} ms（Compose 窗口：40~300ms）")

    print("\n清空 logcat 并打开大图（第 4 张 = 4000×3000 的横图，缩放倍数最大，最容易看出来）")
    W.adb("logcat", "-c")
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(6)

    # 需要时先解锁
    if W.find("忘记密码") is not None:
        es = [n for n in W.nodes() if n["cls"] == "EditText"]
        W.tap(es[0]["cx"], es[0]["cy"])
        W.type_ascii("VaultMaster2026x")
        W.hide_ime()
        W.tap_text("解锁")
        W.wait_text("本机加密 ·", timeout=90, what="主页")

    hit = W.find("照片库")
    W.tap(hit["cx"], hit["cy"])
    time.sleep(2)
    cells = sorted([n for n in W.nodes() if n["clickable"]
                    and not (n["text"] or n["desc"]) and 250 < n["cy"] < 1900],
                   key=lambda n: (n["cy"], n["cx"]))
    print(f"照片库里 {len(cells)} 个格子")
    W.tap(cells[-1]["cx"], cells[-1]["cy"])      # 最后一张
    W.wait_text("/ 4", timeout=25, what="大图")
    time.sleep(1.5)
    print(f"打开后：{measure(W.shot('诊断-打开'))}")
    print(f"当前页码：{next((n['text'] for n in W.nodes() if re.fullmatch(r'\\d+ / \\d+', n['text'].strip())), '?')}")

    for label, cmd in [
        ("串行两次 input tap", "input tap 540 1200; input tap 540 1200"),
        ("间隔 0.05s", "input tap 540 1200; sleep 0.05; input tap 540 1200"),
        ("间隔 0.12s", "input tap 540 1200; sleep 0.12; input tap 540 1200"),
        ("motionevent 连击", "input motionevent DOWN 540 1200; input motionevent UP 540 1200; "
                             "input motionevent DOWN 540 1200; input motionevent UP 540 1200"),
    ]:
        p = W.shot(f"诊断-{label}")
        before = measure(p)
        ms = timed(cmd)
        time.sleep(1.6)
        after = measure(W.shot(f"诊断-{label}-之后"))
        flag = "变化了" if before != after else "没变化"
        print(f"  {label:<20} 耗时 {ms:5.0f}ms  {before}  →  {after}   [{flag}]")
        if before != after:
            print("      >>> 这种姿势有效 <<<")
            break

    out = W.adb("logcat", "-d", "-t", "300")
    lines = [l for l in out.splitlines()
             if "fpb" in l.lower() or "AndroidRuntime" in l or "Exception" in l]
    print(f"\nlogcat 相关行 {len(lines)} 条：")
    for l in lines[-25:]:
        print("   ", l[:150])


if __name__ == "__main__":
    main()
