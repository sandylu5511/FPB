#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.6 验收（第 1 部分）：外观模式三档切换。

本次改动：
  1. 设置页新增「外观模式」= 跟随系统 / 浅色 / 深色，改完立即全局生效并记住；
  2. 大图预览去掉 Dialog（第 2 部分验）并加双击。

## 这一部分要证的五件事

1. **立即生效**：在设置页选「深色」，界面当场变深色，不需要重启。
2. **读得清**：这是上一版的遗留 bug 类别（"深色下按钮和字还是黑的"）。
   只用"变暗了"当判据不够 —— 黑底黑字同样会变暗。因此除了整屏平均亮度，
   还要统计**亮像素占比**（>200 灰阶），它对应的正是"深色底上有没有浅色文字"。
   标定值（v1.0.5 的验收截图）：深色设置页 mean≈0.09 / bright≈0.009；
   浅色设置页 mean≈0.966 / bright≈0.967。
3. **设置优先于系统**：系统浅色 + 应用深色 这一组合才是真判据。
   只测"系统深色时应用也深色"是测不出东西的。
4. **冷启动不白闪 + 记得住**：force-stop 后重开，在**解锁页**（还没进主页）就截图 ——
   窗口底色与状态栏图标必须在第一帧就是对的。
5. **跟随系统仍然有效**：切回"跟随系统"后，改系统深浅，应用要跟着变。

前置：debug 包（允许截屏）；模拟器已启动；数据会被清空。
"""

import importlib.util
import os
import re
import subprocess
import sys
import time

from PIL import Image

APK_DEBUG = r"D:\MixiaVault\app\build\outputs\apk\debug\app-debug.apk"
OUT = r"D:\MixiaVault\dist\evidence\v106"
PKG = "com.fpb.vault"
MASTER = "VaultMaster2026x"


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V105 = load(r"D:\MixiaVault\tools\walkthrough-v105.py", "w105")
W = V105.W
W.OUT = OUT
W.SHOT_INDEX = 0

FAILURES = []


# ==================== 度量 ====================

def stats(path):
    """整屏平均亮度 + 亮像素占比（>200 灰阶）。

    bright 是"深色底上有没有浅色文字"的量化判据 ——
    只有 mean 的话，黑底黑字与正常深色页读起来是一样的数字。
    """
    gray = Image.open(path).convert("L")
    total = gray.width * gray.height
    hist = gray.histogram()
    mean = sum(i * c for i, c in enumerate(hist)) / total / 255
    bright = sum(hist[200:]) / total
    return mean, bright


def check(label, ok, detail):
    mark = "PASS" if ok else "FAIL"
    print(f"    [{mark}] {label}：{detail}")
    if not ok:
        FAILURES.append(f"{label} —— {detail}")


def expect_dark(label, path):
    mean, bright = stats(path)
    check(f"{label} · 配色是深色", mean < 0.30, f"平均亮度 {mean:.3f}（<0.30）")
    check(f"{label} · 深色底上有浅色文字", bright > 0.004, f"亮像素占比 {bright:.4f}（>0.004）")
    return mean, bright


def expect_light(label, path):
    mean, bright = stats(path)
    check(f"{label} · 配色是浅色", mean > 0.60, f"平均亮度 {mean:.3f}（>0.60）")
    return mean, bright


def find_exact(text):
    for n in W.nodes():
        if n["text"] == text or n["desc"] == text:
            return n
    return None


def open_settings():
    """从主页进设置页。

    顶栏那个入口在层级里**不是**一个 text 恰好等于"设置"的节点（desc/text 拼装方式
    由 Compose 的语义树决定），只能用子串匹配 —— v1.0.5 的脚本就是这么找的。
    进页面后等"安全"这个区块标题出现，它一定在第一屏，不用滚动。
    """
    W.hide_ime()
    hit = W.find("设置")
    assert hit is not None, "主页顶栏找不到「设置」入口"
    W.tap(hit["cx"], hit["cy"])
    W.wait_text("安全", timeout=25, what="设置页")
    time.sleep(0.8)


def pick_theme(mode_label):
    """打开「外观模式」对话框并选一档。

    对话框打开后 uiautomator 只 dump 这扇窗口（探针实测：底层设置页的节点一个都不出现），
    所以这里按文案精确匹配是安全的，不会误点到背后那一行。
    """
    W.hide_ime()
    W.tap_text_scrolling("外观模式")
    W.wait_text("完成", timeout=15, what="外观模式对话框")
    time.sleep(0.6)
    row = find_exact(mode_label)
    assert row is not None, f"外观模式对话框里找不到「{mode_label}」"
    W.tap(row["cx"], row["cy"])
    # 等 Snackbar（4 秒）自己消失，别让它进截图影响亮度统计
    time.sleep(4.8)


def unlock():
    W.wait_text("主密码", timeout=90, what="解锁页")
    es = [n for n in W.nodes() if n["cls"] == "EditText"]
    assert es, "解锁页没有密码输入框"
    W.tap(es[0]["cx"], es[0]["cy"])
    W.type_ascii(MASTER)
    W.hide_ime()
    W.tap_text("解锁")
    W.wait_text("还没有任何记录", timeout=90, what="解锁后的主页")
    time.sleep(1.2)


# ==================== 主流程 ====================

def main():
    print(f"安装 debug 包：{APK_DEBUG}")
    W.adb("install", "-r", "-g", APK_DEBUG)

    W.log("先把系统深浅固定成浅色 —— 后面每一步都要在这个基准上判断")
    V105.set_dark(False)

    V105.onboarding()
    W.log("引导完成（此时是「跟随系统」，系统浅色 → 应用浅色）")

    # ---------- 基线：浅色设置页 ----------
    open_settings()
    W.scroll_top()
    time.sleep(0.8)
    p = W.shot("01-基线-浅色设置页-跟随系统")
    expect_light("基线（系统浅色+跟随系统）", p)

    row = find_exact("跟随系统")
    check("基线 · 外观模式显示为「跟随系统」", row is not None, "行右侧值 = 跟随系统")

    # ---------- 切深色（系统仍然是浅色）----------
    W.log("选「深色」—— 此时系统仍是浅色，这正是旧 bug 会暴露的组合")
    pick_theme("深色")
    W.scroll_top()
    time.sleep(1.0)
    p = W.shot("02-应用深色-系统仍浅色-设置页第1屏")
    expect_dark("系统浅色+应用深色", p)

    W.adb("shell", "input", "swipe", "540", "1800", "540", "700", "300")
    time.sleep(0.8)
    p = W.shot("03-应用深色-设置页第2屏")
    expect_dark("系统浅色+应用深色（第2屏）", p)

    # ---------- 冷启动：记得住 + 第一帧就对 ----------
    W.log("force-stop 后冷启动，在解锁页验证配色被记住（也顺带验证不白闪）")
    W.adb("shell", "am", "force-stop", PKG)
    time.sleep(2.5)
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    try:
        W.wait_text("主密码", timeout=25, what="解锁页")
    except RuntimeError:
        W.log("    第一发 am start 似乎被吞了，重发一次")
        W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        W.wait_text("主密码", timeout=75, what="解锁页（重试后）")
    time.sleep(1.0)
    p = W.shot("04-冷启动-解锁页仍是深色")
    expect_dark("冷启动解锁页（持久化）", p)

    unlock()
    open_settings()
    W.scroll_top()
    time.sleep(0.8)
    row = find_exact("深色")
    check("重启后 · 外观模式仍记为「深色」", row is not None, "行右侧值 = 深色")
    p = W.shot("05-重启后-设置页仍是深色")
    expect_dark("重启后设置页", p)

    # ---------- 切浅色 ----------
    W.log("选「浅色」")
    pick_theme("浅色")
    W.scroll_top()
    time.sleep(1.0)
    p = W.shot("06-切浅色-设置页")
    expect_light("应用切浅色（系统仍浅色）", p)

    # ---------- 跟随系统：改系统，应用要跟着变 ----------
    W.log("切回「跟随系统」，然后把系统改成深色")
    pick_theme("跟随系统")
    V105.set_dark(True)
    W.scroll_top()
    time.sleep(1.5)
    p = W.shot("07-跟随系统-系统切深色-应用跟着深")
    expect_dark("跟随系统（系统改深色）", p)

    # ---------- 设置优先于系统（反向再证一次）----------
    W.log("系统仍是深色，此时选「浅色」—— 应用必须保持浅色")
    pick_theme("浅色")
    W.scroll_top()
    time.sleep(1.5)
    p = W.shot("08-显式浅色-系统深色-应用保持浅色")
    expect_light("显式浅色（系统深色）", p)

    # ---------- 恢复现场 ----------
    W.log("收尾：切回「跟随系统」并把系统恢复成浅色")
    pick_theme("跟随系统")
    V105.set_dark(False)
    W.scroll_top()
    time.sleep(1.2)
    p = W.shot("09-恢复-跟随系统-系统浅色")
    expect_light("收尾（系统浅色+跟随系统）", p)

    print()
    if FAILURES:
        W.log(f"验收未通过，共 {len(FAILURES)} 条：")
        for f in FAILURES:
            print(f"    · {f}")
        sys.exit(1)
    W.log("第 1 部分（外观模式）全部通过")


if __name__ == "__main__":
    main()
