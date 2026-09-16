#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.3 回归 part2：从"删除确认对话框已弹出"的状态续跑。

part1（walkthrough-v103.py）已完成：引导、照片库入口/空态、多选导入 2 张、
全屏查看、长按弹窗。本脚本接续：
  - 点删除按钮（注意：必须精确匹配 text=="删除"，子串会点到对话框标题）
  - 验证张数 2 → 1
  - 回主页验证照片记录
  - 清空对话框范围文案
  - 诱饵库隐藏照片库入口（核心）
  - 桌面图标截图
"""
import re
import sys
import time

sys.path.insert(0, r"D:\MixiaVault\tools")
import walkthrough as W

PKG = "com.fpb.vault"
OUT = r"D:\MixiaVault\dist\evidence\v103"
W.OUT = OUT
W.SHOT_INDEX = 5  # 下一张从 06 开始

MASTER = "VaultMaster2026x"
DECOY = "DecoyPass2026x"


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def header_count():
    hit = W.find("原图加密存储 · ")
    m = hit and re.search(r"原图加密存储 · (\d+) 张", hit["text"])
    return int(m.group(1)) if m else None


def main():
    # ---------- 接续：删除对话框 ----------
    log("接续 part1：删除确认对话框应还在屏幕上")
    W.wait_text("删除这张照片？", timeout=10, what="删除确认对话框")
    btn = next(n for n in W.nodes() if n["text"] == "删除")
    W.tap(btn["cx"], btn["cy"])

    deadline = time.time() + 30
    while time.time() < deadline:
        if header_count() == 1:
            break
        time.sleep(1.0)
    else:
        raise AssertionError(f"删除后张数没有变成 1（当前 {header_count()}）")
    log("    删除成功：2 → 1 张 ✓")
    W.shot("06-照片库-删除后")

    # ---------- 回主页：照片记录可见 ----------
    log("返回主页，照片记录应出现在列表")
    W.adb("shell", "input", "keyevent", "4")
    time.sleep(1.2)
    W.wait_text("照片 · ", timeout=20, what="列表里的照片记录")
    W.shot("07-主页-照片记录可见")

    # ---------- 需求1：清空对话框范围文案 ----------
    log("[需求1] 清空数据对话框：应说明只删 FPB 自己的数据")
    W.tap_text("设置")
    W.wait_text("外观与伪装", timeout=20, what="设置页")
    W.tap_text_scrolling("清空本机数据")
    W.wait_text("清空本机数据？", timeout=15, what="清空确认对话框")
    hit = W.find("只删除 FPB 自己保存的内容")
    assert hit is not None, "对话框里没有出现范围澄清文案"
    log("    范围澄清文案可见 ✓（相册/其他应用不受影响）")
    W.shot("08-清空数据-范围说明")
    W.tap_text("取消")
    time.sleep(0.8)

    # ---------- 需求2：诱饵库不显示照片库 ----------
    log("[需求2] 诱饵库：假密码进入后不应有照片库入口")
    W.adb("shell", "am", "force-stop", PKG)
    time.sleep(2.5)
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    try:
        W.wait_text("主密码", timeout=25, what="解锁页")
    except RuntimeError:
        W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        W.wait_text("主密码", timeout=75, what="解锁页（重试）")
    es = W.edits()
    W.tap(es[0]["cx"], es[0]["cy"])
    W.type_ascii(DECOY)
    W.hide_ime()
    W.tap_text("解锁")
    W.wait_text("还没有任何记录", timeout=90, what="诱饵库主页")
    time.sleep(1.2)
    W.shot("09-诱饵库-主页无照片库入口")
    hit = W.find("照片库")
    assert hit is None, "诱饵库主页出现了照片库入口 —— 违反「假密码页面不显示照片库」"
    log("    诱饵库顶栏无照片库入口 ✓")

    W.tap_text("设置")
    W.wait_text("当前在诱饵库", timeout=20, what="诱饵库设置页")
    W.shot("10-诱饵库-设置页")
    W.adb("shell", "input", "keyevent", "4")
    time.sleep(0.8)

    log("主密码重新解锁回真库，照片应还在")
    W.adb("shell", "am", "force-stop", PKG)
    time.sleep(2.5)
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    try:
        W.wait_text("主密码", timeout=25, what="解锁页")
    except RuntimeError:
        W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        W.wait_text("主密码", timeout=75, what="解锁页（重试）")
    es = W.edits()
    W.tap(es[0]["cx"], es[0]["cy"])
    W.type_ascii(MASTER)
    W.hide_ime()
    W.tap_text("解锁")
    W.wait_text("照片 · ", timeout=90, what="真库主页（照片记录还在）")
    entry = W.find("照片库")
    assert entry is not None, "回到真库后照片库入口应恢复显示"
    log("    真库入口恢复、照片记录仍在 ✓")
    W.shot("11-真库-主密码回归")

    # ---------- 需求3：桌面图标 ----------
    log("[需求3] 桌面伪装图标（徽章式重绘）")
    W.tap_text("设置")
    W.wait_text("外观与伪装", timeout=20, what="设置页")

    for label in ("备忘录", "计算器"):
        W.tap_text_scrolling(label)
        time.sleep(1.5)
        W.adb("shell", "input", "keyevent", "3")  # HOME
        time.sleep(5.0)
        W.shot(f"12-桌面-{label}图标-新")
        W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        time.sleep(3.0)

    W.wait_text("外观与伪装", timeout=20, what="回到设置页")
    W.tap_text_scrolling("FPB")
    time.sleep(1.5)
    W.adb("shell", "input", "keyevent", "3")
    time.sleep(5.0)
    W.shot("13-桌面-FPB图标")
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2.0)
    log("    桌面截图完成（12-*.png / 13-*.png）")

    print()
    log("v1.0.3 part2 验收完成")


if __name__ == "__main__":
    main()
