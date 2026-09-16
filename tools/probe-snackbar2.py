#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""探针：Snackbar 是"显示 4 秒"还是"刚画出就被撤掉"。

上一版探针（probe-snackbar.py）已经排除了"根本没触发提示"：
它点的那一行会同时关掉对话框 + setMessage(...)，而 +0.6s..+2.9s 的 8 帧
完全一致、且都看不到 Snackbar —— 若它像正常那样活 4 秒，这几帧必然都拍得到。

这一版把"tap → 截图"压进**同一条 adb shell**，
把采样点从「几百毫秒」推到「几十毫秒」，从而分清两种情况：

  A. Snackbar 压根没被画出来（Compose 没来得及渲染就被取消）
  B. 画出来了，但下一帧的重组把它取消掉

判据：`a/b/c` 三帧里，如果 c（+0.5s）已经干净而 a/b 有 Snackbar → 是 B。
"""
import importlib.util
import os
import subprocess
import sys
import time

from PIL import Image

OUT = r"D:\MixiaVault\dist\evidence\audit-20260916\probe-snackbar2"
ADB = os.environ.get("ADB", r"D:\AndroidSdk\platform-tools\adb.exe")

os.makedirs(OUT, exist_ok=True)

spec = importlib.util.spec_from_file_location("w", r"D:\MixiaVault\tools\walkthrough.py")
W = importlib.util.module_from_spec(spec)
sys.modules["w"] = W
spec.loader.exec_module(W)
W.OUT = OUT
W.SHOT_INDEX = 0

# Snackbar 的横带：Material3 把它放在底部，导航栏之上
BAND = (2040, 2270)


def pull(name):
    raw = subprocess.run([ADB, "exec-out", "screencap", "-p"],
                         capture_output=True, timeout=60).stdout
    path = os.path.join(OUT, name + ".png")
    open(path, "wb").write(raw)
    return path


def stats(path):
    """返回横带里的平均亮度与"深色像素占比"。Snackbar 是深底，会把两者都拉低/拉高。"""
    img = Image.open(path).convert("RGB")
    px = img.load()
    n = 0
    s = 0
    dark = 0
    for y in range(BAND[0], BAND[1], 2):
        for x in range(0, 1080, 2):
            r, g, b = px[x, y]
            n += 1
            s += (r + g + b) / 3
            if (r + g + b) / 3 < 120:
                dark += 1
    return s / n, dark / n


def main():
    W.log("回到设置页并找到「清理无用图片」")
    W.adb("shell", "am", "start", "-n", "com.fpb.vault/.MainActivity")
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            if W.find("本机加密 ·") is not None or W.find("安全") is not None:
                break
        except Exception:
            pass
        time.sleep(1.5)
    if W.find("安全") is None:
        W.tap_text("设置")
    for _ in range(8):
        if W.find("清理无用图片") is not None:
            break
        W.adb("shell", "input", "swipe", "540", "1600", "540", "600", "200")
        time.sleep(0.4)
    row = W.find("清理无用图片")
    assert row is not None, "没找到「清理无用图片」这一行"
    x, y = row["cx"], row["cy"]
    W.log(f"目标行 ({x}, {y})")

    W.log("tap 前基线")
    b = pull("00-基线")
    m, d = stats(b)
    print(f"  基线          mean={m:6.2f}  dark={d:.4f}")

    W.log("同一条 shell：tap 之后立刻连拍两张，再睡 0.5s 拍第三张")
    W.adb(
        "shell",
        f"input tap {x} {y}; screencap -p /sdcard/sn_a.png; "
        "screencap -p /sdcard/sn_b.png; sleep 0.5; screencap -p /sdcard/sn_c.png; "
        "sleep 1.0; screencap -p /sdcard/sn_d.png",
    )
    for tag in ("a", "b", "c", "d"):
        rawname = f"sn_{tag}.png"
        raw = subprocess.run([ADB, "exec-out", "cat", f"/sdcard/{rawname}"],
                             capture_output=True, timeout=60).stdout
        path = os.path.join(OUT, f"{tag}.png")
        open(path, "wb").write(raw)
        m, d = stats(path)
        print(f"  帧 {tag}          mean={m:6.2f}  dark={d:.4f}")

    W.log("收尾：清掉设备上的临时截图")
    W.adb("shell", "rm", "-f", "/sdcard/sn_a.png", "/sdcard/sn_b.png",
          "/sdcard/sn_c.png", "/sdcard/sn_d.png")


if __name__ == "__main__":
    main()
