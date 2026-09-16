#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""双击诊断（带日志版）：区分"事件没到"和"双击没被判为双击"。"""

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
W.SHOT_INDEX = 80


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
                x0 = min(x0, x)
                x1 = max(x1, x)
                y0 = min(y0, y)
                y1 = max(y1, y)
    return f"{x1 - x0 + 1}×{y1 - y0 + 1} @ y{y0}..{y1}" if x1 >= 0 else "无"


def taps_log():
    out = W.adb("logcat", "-d", "-s", "FPB-TAP")
    return [l.split("FPB-TAP", 1)[1].strip() for l in out.splitlines() if "FPB-TAP" in l]


def main():
    W.adb("install", "-r", "-g", r"D:\MixiaVault\app\build\outputs\apk\debug\app-debug.apk")
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(8)
    if W.find("忘记密码") is not None:
        es = [n for n in W.nodes() if n["cls"] == "EditText"]
        W.tap(es[0]["cx"], es[0]["cy"])
        W.type_ascii("VaultMaster2026x")
        W.hide_ime()
        W.tap_text("解锁")
        W.wait_text("本机加密 ·", timeout=120, what="主页")
    time.sleep(1)

    hit = W.find("照片库")
    W.tap(hit["cx"], hit["cy"])
    time.sleep(2)
    cells = sorted([n for n in W.nodes() if n["clickable"]
                    and not (n["text"] or n["desc"]) and 250 < n["cy"] < 1900],
                   key=lambda n: (n["cy"], n["cx"]))
    W.tap(cells[-1]["cx"], cells[-1]["cy"])
    W.wait_text("/ 4", timeout=25, what="大图")
    time.sleep(1.5)
    print(f"打开后：{measure(W.shot('日志诊断-打开'))}")

    for label, cmd in [
        ("单次点击（基线）", "input tap 540 1200"),
        ("串行两次", "input tap 540 1200; input tap 540 1200"),
        ("间隔 0.15s", "input tap 540 1200; sleep 0.15; input tap 540 1200"),
        ("间隔 0.30s", "input tap 540 1200; sleep 0.30; input tap 540 1200"),
    ]:
        W.adb("logcat", "-c")
        before = measure(W.shot(f"日志诊断-{label}"))
        W.adb("shell", cmd)
        time.sleep(1.8)
        after = measure(W.shot(f"日志诊断-{label}-之后"))
        logs = taps_log()
        print(f"\n  {label}：{before} → {after}")
        for l in logs:
            print(f"      · {l}")


if __name__ == "__main__":
    main()
