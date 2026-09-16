# -*- coding: utf-8 -*-
"""探测：编辑器里调出输入法之后，底部的「保存」按钮还在不在屏幕上看得到。

## 为什么要量，而不是看着截图说"挡住了"

布局问题的口头结论最容易走样 —— "我记得是挡住了"。这里改成量可复算的数：
输入法是否真的弹出（`dumpsys input_method` 的 `mInputShown`）、底部「保存」按钮
的屏幕范围（uiautomator dump 的 bounds），再加上键盘弹出前后的两张截图对照。

背景知识（决定了这条探测必须做）：`enableEdgeToEdge()` 会把窗口设成"不避让系统栏"
（`setDecorFitsSystemWindows(false)`）。此时清单里那句
`android:windowSoftInputMode="adjustResize"` **不再生效** —— 实测窗口始终是
`0,0-1080,2400`（整屏），输入法不会把窗口顶上去，而是以"窗口内边距"的形式被报告出来。

于是：内容区自己写了 `.imePadding()` 的部分会躲开键盘；没写的部分（例如底部那条
保存按钮）会原地不动。同一个页面里一半躲开、一半不躲，就是这次的怀疑点。

## 环境要求

模拟器默认带硬件键盘且 `show_ime_with_hard_keyboard=0`，此时点输入框
**不会弹出软键盘**（`mInputShown` 可能在 true/false 之间抖动，但屏幕上没有键盘），
探测会得到"看不到键盘"的假结论。本脚本会把这个开关打开，结束时恢复原值。
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import walkthrough as W
import uitest


def get_setting(key):
    return subprocess.run([W.ADB, "shell", "settings", "get", "secure", key],
                          capture_output=True, text=True).stdout.strip()


def set_setting(key, value):
    subprocess.run([W.ADB, "shell", "settings", "put", "secure", key, value],
                   capture_output=True, text=True)


def nodes_with_bounds():
    """带屏幕边界的节点列表（walkthrough.nodes 只保留中心点，这里要看上下沿）。"""
    root = ET.fromstring(uitest.dump_xml())
    out = []
    for n in root.iter():
        if not n.tag.endswith("node"):
            continue
        text = n.get("text") or ""
        desc = n.get("content-desc") or ""
        if not (text or desc):
            continue
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds") or "")
        if not m:
            continue
        l, t, r, b = (int(v) for v in m.groups())
        out.append({"text": text, "desc": desc, "l": l, "t": t, "r": r, "b": b,
                    "cx": (l + r) // 2, "cy": (t + b) // 2})
    return out


def label(n):
    return n["text"] or "<%s>" % n["desc"]


def screen_height():
    out = subprocess.run([W.ADB, "shell", "wm", "size"], capture_output=True, text=True).stdout
    m = re.search(r"(\d+)x(\d+)", out)
    return int(m.group(2)) if m else 2400


def open_editor():
    """从主页进到一个条目的编辑器。返回后停在编辑器。"""
    W.ensure_home()
    card = None
    for n in nodes_with_bounds():
        # **不能**用 tap_text("照片")：顶栏「照片库」图标的 content-desc 里也含"照片"，
        # 且它在节点顺序里排在前面，会一头扎进照片库。
        if n["text"].startswith(("照片 · ", "文字 · ", "待办 · ", "账号 · ")):
            card = n
            break
    if card is None:
        raise RuntimeError("主页上没找到条目卡片")
    W.tap(card["cx"], card["cy"], settle=1.8)
    if W.find("编辑"):
        W.tap_text("编辑")
        time.sleep(1.8)


def main():
    original = get_setting("show_ime_with_hard_keyboard")
    set_setting("show_ime_with_hard_keyboard", "1")
    W.log(f"已打开软键盘开关（原值 {original!r}）")

    try:
        open_editor()
        W.log("已进入编辑器：" + str([label(n) for n in nodes_with_bounds()][:6]))

        # 没有键盘时就截一张，作为"有键盘"那张的对照基线
        W.hide_ime()
        before = W.shot("ime-closed")

        fields = [n for n in W.nodes() if n["cls"] == "EditText"]
        if not fields:
            raise RuntimeError("编辑器里没找到可输入的字段")
        W.tap(fields[0]["cx"], fields[0]["cy"], settle=1.2)

        shown = False
        for _ in range(12):
            if W.ime_shown():
                shown = True
                break
            time.sleep(0.5)
        W.log(f"输入法是否弹出：{shown}")
        if not shown:
            raise RuntimeError("输入法没有弹出，这次探测没有意义")

        h = screen_height()
        W.log(f"屏幕高度：{h}")
        W.log("=== 输入法弹出时的底部节点（y > 1200）===")
        below = [n for n in nodes_with_bounds() if n["t"] > 1200]
        for n in below:
            W.log(f"  y{n['t']:>5}..{n['b']:<5} x{n['l']:>5}..{n['r']:<5} {label(n)}")

        after = W.shot("ime-open")

        save = [n for n in nodes_with_bounds() if "保存" in n["text"]]
        W.log("")
        W.log("=== 结论 ===")
        W.log(f"  键盘弹出前截图：{os.path.basename(before)}")
        W.log(f"  键盘弹出后截图：{os.path.basename(after)}")
        for n in save:
            W.log(f"  「保存」按钮 y{n['t']}..{n['b']}（屏幕高 {h}）")
        # 键盘一定会占据屏幕底部；节点仍在屏幕最下沿，就说明它没有被垫高
        if save and save[0]["b"] > h - 60:
            W.log("  ⚠ 按钮仍贴着屏幕最下沿 —— 键盘占据的正是这一带")
        else:
            W.log("  ✓ 按钮已被垫到键盘上方，不会被遮挡")

        W.hide_ime()
        W.log("已收起输入法")
    finally:
        set_setting("show_ime_with_hard_keyboard", original or "0")
        W.log(f"已恢复软键盘开关为 {original!r}")


if __name__ == "__main__":
    main()
