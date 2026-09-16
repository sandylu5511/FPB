#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.2 验收续篇：主流程已验证到图片查看页，这里补完剩余两项。

  A. 假密码解锁进诱饵库 / 主密码回真库（修复1 端到端）
  B. 备忘录/计算器桌面伪装图标截图（修复2）

依赖 walkthrough-v102.py 已跑完的前半段：当前模拟器上主密码=VaultMaster2026x，
假密码=DecoyPass2026x，库里已有文字/图片记录。
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, r"D:\MixiaVault\tools")
import walkthrough as W

ADB = W.ADB
PKG = "com.fpb.vault"
OUT = r"D:\MixiaVault\dist\evidence\v102"
W.OUT = OUT
W.SHOT_INDEX = 20  # 接着前一段的编号

MASTER = "VaultMaster2026x"
DECOY = "DecoyPass2026x"


def adb_out(*args, timeout=90):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout)\
        .stdout.decode("utf-8", errors="replace")


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def cold_unlock(password, expect_text, what):
    W.adb("shell", "am", "force-stop", PKG)
    time.sleep(2.5)
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    try:
        W.wait_text("主密码", timeout=25, what="解锁页")
    except RuntimeError:
        W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        W.wait_text("主密码", timeout=75, what="解锁页（重试）")
    es = W.edits()
    assert es, "解锁页没有密码输入框"
    W.tap(es[0]["cx"], es[0]["cy"])
    W.type_ascii(password)
    W.hide_ime()
    W.tap_text("解锁")
    W.wait_text(expect_text, timeout=90, what=what)
    time.sleep(1.0)


def decoy_unlock_check():
    log("[修复1 续] 用假密码解锁：应进入独立的诱饵空库")
    cold_unlock(DECOY, "还没有任何记录", "诱饵库主页（空）")
    W.shot("假密码-进入诱饵库")

    W.tap_text("设置")
    W.wait_text("当前在诱饵库", timeout=20, what="诱饵库设置页标识")
    log("    设置页标识「当前在诱饵库」✓")
    W.shot("假密码-诱饵库设置")
    W.adb("shell", "input", "keyevent", "4")
    time.sleep(0.8)

    log("再用主密码解锁：应回到真库")
    cold_unlock(MASTER, "Acceptance-View-01", "真库主页（有记录）")
    W.shot("主密码-回到真库")
    log("    假密码进诱饵库、主密码回真库 ✓")


def launcher_icon_check():
    log("[修复2] 桌面伪装图标（备忘录/计算器）")
    W.tap_text("设置")
    W.wait_text("外观与伪装", timeout=20, what="设置页")

    for label in ("备忘录", "计算器"):
        W.tap_text_scrolling(label)
        time.sleep(1.5)
        W.adb("shell", "input", "keyevent", "3")  # HOME
        time.sleep(5.0)
        W.shot(f"桌面-{label}图标")
        W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        time.sleep(3.0)

    W.wait_text("外观与伪装", timeout=20, what="回到设置页")
    W.tap_text_scrolling("FPB")
    time.sleep(1.5)
    W.adb("shell", "input", "keyevent", "3")
    time.sleep(5.0)
    W.shot("桌面-FPB图标")
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2.0)
    log("    三种图标桌面截图完成（见 dist/evidence/v102/桌面-*.png）")


def main():
    decoy_unlock_check()
    launcher_icon_check()
    print()
    log("v1.0.2 验收续篇完成")


if __name__ == "__main__":
    main()
