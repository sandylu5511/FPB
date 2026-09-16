#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""探针：Snackbar 到底显示了多久。

## 怀疑

`AppRoot` 里是这么消费一次性提示的：

    LaunchedEffect(state.notice) {
        val text = state.notice ?: return@LaunchedEffect
        state.notice = null            // ← 这里改了 key
        hostState.showSnackbar(text)
    }

`state.notice = null` 会让这个 LaunchedEffect 的 key 从"文本"变成 null，
按 Compose 的语义就是**取消并重启**当前协程 ——
而 `showSnackbar` 一被取消，Snackbar 就被撤下（它的 finally 会清 currentSnackbarData）。
若真如此，症状是"所有提示一闪而过"，而不是显示 4 秒。

## 怎么量

触发一次会产生提示的操作（切外观模式），然后**连续截图**，
在 Snackbar 该出现的那条横带上统计"深色像素占比"。
浅色主题下 Snackbar 是深底浅字，会比周围背景暗得多。
逐帧对比就能得出它实际存活了几百毫秒还是几秒。
"""
import importlib.util
import os
import subprocess
import sys
import time

from PIL import Image

OUT = r"D:\MixiaVault\dist\evidence\audit-20260916\probe-snackbar"
ADB = os.environ.get("ADB", r"D:\AndroidSdk\platform-tools\adb.exe")

os.makedirs(OUT, exist_ok=True)

spec = importlib.util.spec_from_file_location("w", r"D:\MixiaVault\tools\walkthrough.py")
W = importlib.util.module_from_spec(spec)
sys.modules["w"] = W
spec.loader.exec_module(W)
W.OUT = OUT
W.SHOT_INDEX = 0


def shot(name):
    raw = subprocess.run([ADB, "exec-out", "screencap", "-p"],
                         capture_output=True, timeout=60).stdout
    path = os.path.join(OUT, name + ".png")
    open(path, "wb").write(raw)
    return path


def dark_ratio(path, y0, y1, x0=0, x1=1080, threshold=110):
    """统计横带里"偏深"的像素占比。Snackbar（深底）会把它显著抬高。"""
    img = Image.open(path).convert("RGB")
    px = img.load()
    total = 0
    dark = 0
    for y in range(y0, y1, 3):
        for x in range(x0, x1, 3):
            r, g, b = px[x, y]
            total += 1
            if (r + g + b) / 3 < threshold:
                dark += 1
    return dark / max(total, 1)


def main():
    W.log("确保停在主页")
    W.adb("shell", "am", "start", "-n", "com.fpb.vault/.MainActivity")
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            if W.find("本机加密 ·") is not None:
                break
            if W.find("忘记密码") is not None:
                raise RuntimeError("当前在解锁页，无法继续（需要先解锁）")
        except RuntimeError:
            raise
        except Exception:
            pass  # dump 暂时不可用（冷启动窗口），继续等
        time.sleep(1.5)

    base = shot("00-基线")
    band = (2000, 2260)
    W.log(f"基线横带 y{band[0]}..{band[1]} 深色占比 = {dark_ratio(base, *band):.4f}")

    W.log("进设置页")
    W.tap_text("设置")
    # 设置页可能停在任意滚动位置（上一轮脚本留下的），先滚回顶部再找「外观模式」。
    for _ in range(6):
        if W.find("外观与伪装") is not None:
            break
        W.adb("shell", "input", "swipe", "540", "700", "540", "2000", "200")
        time.sleep(0.4)
    W.wait_text("外观与伪装", timeout=30)
    W.tap_text_scrolling("外观模式")
    W.wait_text("跟随系统", timeout=20)

    # 选一个与当前不同的档位，确保一定会产生一次提示
    for label in ("深色", "浅色"):
        node = W.find(label)
        if node is not None:
            break
    W.log(f"点「{label}」→ 立刻连续截图")
    t0 = time.time()
    W.adb("shell", "input", "tap", str(node["cx"]), str(node["cy"]))

    frames = []
    for i in range(9):
        p = shot(f"{i + 1:02d}-t{time.time() - t0:.1f}s")
        frames.append((time.time() - t0, p))
    for t, p in frames:
        print(f"  +{t:5.2f}s  深色占比 {dark_ratio(p, *band):.4f}   {os.path.basename(p)}")

    W.log("再用 dump 确认提示文案是否还在（dump 本身约 1.2s）")
    t = time.time()
    hit = W.find("外观模式已切换")
    print(f"  dump 耗时 {time.time() - t:.2f}s，文案命中 = {hit is not None}")


if __name__ == "__main__":
    main()
