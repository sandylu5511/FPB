#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对着当前设备跑一遍 `R108.window_secure()`，把"解析器看到的原始行"也打出来。

## 为什么要单独跑这一下

release 验收里那条 `release 包开箱即禁止截屏（窗口上真有 FLAG_SECURE）` 报过
`fl 里 SECURE=False`，而**同一时刻的截图是全黑的**（`01-全新安装-默认禁止截屏.png`
16,166 B，与那批恒黑的引导截图逐字节同量级）—— 两个证据互相矛盾：
像素说"生效了"，标志位说"没生效"。而手工 dump 出来的那一行明明是

    fl=LAYOUT_IN_SCREEN SECURE LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED ...

所以三种可能必须用**实跑**分开，不能靠读代码猜：

  ① 解析器（`v108-release-acceptance.window_secure`）本身有问题；
  ② 它匹配到了**另一个**同包名的窗口（比如启动窗口 / 输入法 / 弹窗），
     那个窗口本来就不带 SECURE；
  ③ 那一次跑的时候设备上装的根本不是 release 包。

本脚本把 ① 与 ② 一次问清：把 `dumpsys window windows` 的原始输出留下，
再让**被测函数自己**对这份输出说话，并把所有"W`indow{` 且含包名"的候选行都列出来。
"""
import io
import os
import re
import subprocess
import sys

BASE = r"D:\MixiaVault"
ADB = r"D:\AndroidSdk\platform-tools\adb.exe"
PKG = "com.fpb.vault"
OUT = os.path.join(BASE, "dist", "evidence", "v110-release", "secure-probe")


def sh(*args):
    """与 walkthrough 的 `sh` 同语义：stdout+stderr 合并，返回文本。"""
    p = subprocess.run([ADB, *args], capture_output=True, timeout=120)
    return (p.stdout + p.stderr).decode("utf-8", "replace")


def wait_device(timeout=60):
    import time
    end = time.time() + timeout
    while time.time() < end:
        blob = sh("get-state")
        if "device" in blob and "offline" not in blob:
            return True
        subprocess.run([ADB, "start-server"], capture_output=True, timeout=120)
        time.sleep(2)
    return False


def main():
    os.makedirs(OUT, exist_ok=True)
    if not wait_device():
        sys.exit("设备拉不起来")

    out = sh("shell", "dumpsys", "window", "windows")
    dump_path = os.path.join(OUT, "dumpsys-window-windows.txt")
    with io.open(dump_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print(f"原始 dump 落盘：{dump_path}（{len(out.splitlines())} 行）")

    lines = out.splitlines()

    # ---- ② 所有候选窗口：`Window{` 且含包名 ----
    print("\n---- 所有『Window{ 且含包名』的行 ----")
    cands = [i for i, l in enumerate(lines) if "Window{" in l and PKG in l]
    if not cands:
        print("  （一条都没有 —— 那就落进情况 ③：那一次跑的时候包不在/不是这个包）")
    for i in cands:
        print(f"  [{i}] {lines[i].strip()[:140]}")
        for j in range(i, min(i + 30, len(lines))):
            if re.match(r"\s*fl=", lines[j]):
                print(f"        fl 行 [{j}] {lines[j].strip()[:160]}")
                break
        else:
            print("        该窗口 30 行内没有 fl= 行")

    # ---- ① 让被测函数自己对这份**已经落盘的**输出说话 ----
    print("\n---- 被测函数 window_secure() 的答案 ----")
    sys.path.insert(0, os.path.join(BASE, "tools"))
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "r108", os.path.join(BASE, "tools", "v108-release-acceptance.py"))
    mod = importlib.util.module_from_spec(spec)
    sys.modules["r108"] = mod
    spec.loader.exec_module(mod)

    # 它内部自己会再 dump 一次；为了把"解析逻辑"与"取数"分开，
    # 这里同时给出：对着刚落的这份输出解析（本地判读），以及它的实跑答案。
    local = parse_like(mod, lines)
    print(f"  对这份落盘输出解析 → {local}")
    print(f"  它自己实跑一次      → {mod.window_secure()}")

    print("\n---- 复现 ② 的关键问题：如果有多个候选窗口，函数取的是**第一个** ----")
    if len(cands) > 1:
        print(f"  有 {len(cands)} 个候选窗口 —— 函数会扣在**最靠前**那个上。")
        print("  若最靠前那个是启动窗口/弹窗，它本来就不带 SECURE。")
    else:
        print("  只有 1 个候选窗口，排除情况 ②。")


def parse_like(mod, lines):
    """把 v108.window_secure 的解析逻辑原样搬一遍，喂给指定的行。"""
    secure_flag = mod.FLAG_SECURE
    for i, line in enumerate(lines):
        if "Window{" in line and PKG in line:
            for j in range(i, min(i + 30, len(lines))):
                if not re.match(r"\s*fl=", lines[j]):
                    continue
                m = re.match(r"\s*fl=#([0-9a-fA-F]+)", lines[j])
                if m:
                    return bool(int(m.group(1), 16) & secure_flag)
                return "SECURE" in lines[j].split("=", 1)[1].split()
    return None


if __name__ == "__main__":
    main()
