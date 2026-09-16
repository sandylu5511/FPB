#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.5 验收：深色模式可读性 + 照片库大图左右滑动。

只验本次改动的两件事，不重复全流程：

  1. **深色模式**。根因是 AppRoot 拿 Box+background 当根节点、没有提供 contentColor，
     于是所有没显式写颜色的 Text/Icon 都继承 Compose 默认的纯黑 —— 浅色主题下
     碰巧看不出来，深色主题下就是"黑底黑字"。修复 = 换成 Surface。
     验证方式：切系统深色模式后把主要界面各截一张，肉眼可读即通过。
     注意 MainActivity 声明了 configChanges="uiMode"，切模式不会重建 Activity，
     正好顺带验证"Compose 能就地重组出正确配色"。

  2. **大图左右滑动**。根因是 detectTransformGestures 无条件消费指针事件，
     外层 HorizontalPager 收不到水平拖动。修复 = 只在缩放/已放大时才消费。
     验证方式：翻页后断言页码从 "1 / 2" 变成 "2 / 2"，再滑回来变回 "1 / 2"。
     （双指缩放没法用 adb input 模拟，只能靠代码审查 + 编译保证，这里不假装验证。）

前置：debug 包（允许截屏）；模拟器已启动；数据会被清空。
"""

import importlib.util
import os
import re
import subprocess
import sys
import time

APK_DEBUG = r"D:\MixiaVault\app\build\outputs\apk\debug\app-debug.apk"
OUT = r"D:\MixiaVault\dist\evidence\v105"
PKG = "com.fpb.vault"
MASTER = "VaultMaster2026x"
KEYCODE_TAB = "61"


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


W = load(r"D:\MixiaVault\tools\walkthrough.py", "walkthrough")
W.OUT = OUT
W.SHOT_INDEX = 0


def cold_start(first_text, timeout=60):
    """冷启动到某个首屏文案出现。

    force-stop 之后的第一发 am start 偶尔会被系统吞掉（模拟器上稳定复现过），
    因此等不到就重发一次，别把它当成"应用起不来"。
    """
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    try:
        return W.wait_text(first_text, timeout=timeout)
    except RuntimeError:
        W.log("    第一发 am start 似乎被吞了，重发一次")
        W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        return W.wait_text(first_text, timeout=timeout + 60)


def set_dark(enabled):
    W.adb("shell", "cmd", "uimode", "night", "yes" if enabled else "no")
    time.sleep(3.0)
    W.log(f"    系统深色模式已{'开启' if enabled else '关闭'}")


def onboarding():
    """走完引导（跳过假密码，本次不回归那部分）。"""
    W.log("清空应用数据并冷启动，走完整引导")
    W.adb("shell", "pm", "clear", PKG)
    cold_start("开始设置", timeout=60)
    W.shot("深色-引导-欢迎")
    W.tap_text("开始设置")

    W.log("设置主密码")
    W.wait_text("设置主密码")
    W.fill_field(0, MASTER)
    W.fill_field(1, MASTER)
    W.hide_ime()
    W.shot("深色-引导-主密码")
    W.tap_text("下一步")

    W.log("跳过假密码")
    W.wait_text("要不要再设一个假密码")
    W.shot("深色-引导-假密码")
    W.tap_text("跳过，生成保险库")

    W.log("等待 Argon2id 派生完成")
    W.wait_text("抄下恢复码", timeout=180, what="恢复码屏")

    W.scroll_top()
    ns = W.nodes()
    groups = [n["text"] for n in ns if re.fullmatch(r"[0-9A-Z]{4}", n["text"])]
    assert len(groups) == 12, f"应显示 12 组恢复码，实际 {len(groups)}: {groups}"
    labels = [int(m.group(1)) for n in ns
              for m in [re.fullmatch(r"第 (\d+) 组", n["text"])] if m]
    assert len(labels) == 3, f"应随机抽 3 组回填，实际 {labels}"
    wanted = [groups[i - 1] for i in labels]
    W.log(f"    要求回填第 {labels} 组")

    # TAB 遍历填三格：键盘收起时第三个框在可视区外，按下标取坐标必然填错。
    W.hide_ime()
    W.scroll_top()
    es = W.edits()
    assert es, "找不到回填输入框"
    W.tap(es[0]["cx"], es[0]["cy"])
    W.type_ascii(wanted[0])
    for value in wanted[1:]:
        W.adb("shell", "input", "keyevent", KEYCODE_TAB)
        time.sleep(0.6)
        W.type_ascii(value)
    W.hide_ime()
    W.shot("深色-引导-恢复码已回填")

    W.hide_ime()
    for attempt in range(5):
        cb = next((n for n in W.nodes() if n["checkable"]), None)
        if cb is None:
            W.adb("shell", "input", "swipe", "540", "1500", "540", "800", "250")
            time.sleep(0.9)
            continue
        if cb["checked"]:
            W.log(f"    复选框已勾选（第 {attempt + 1} 次尝试）")
            break
        W.tap(cb["cx"], cb["cy"])
        time.sleep(0.7)
    else:
        raise RuntimeError("复选框始终勾不上")
    W.shot("深色-引导-已确认抄写")

    enter = W.find("进入保险库")
    assert enter is not None and enter["enabled"], "「进入保险库」不可用"
    W.tap(enter["cx"], enter["cy"])

    W.log("等待进入主页")
    W.wait_text("还没有任何记录", timeout=90, what="保险库主页")
    time.sleep(1.5)
    W.shot("深色-主页-空列表")


def open_photo_library():
    W.log("从主页顶栏进入照片库")
    hit = W.find("照片库")
    assert hit is not None, "主页顶栏找不到照片库入口"
    W.tap(hit["cx"], hit["cy"])
    W.wait_text("还没有照片", timeout=25, what="照片库空态")
    W.shot("深色-照片库-空态")


def import_photos(count=2):
    """从相册多选导入。

    选图器的两个坑（v103 踩过）：
      · 选择计数器和 Done 按钮**只在选中第一张之后**才出现，不能提前等；
      · 已选中的格子外层节点没有任何 desc 标记，无法从属性判断"这格选过了"，
        重复点同一格会把它取消掉 —— 因此自己记录已点坐标来避让。
    """
    W.log(f"打开选图器，多选 {count} 张")
    fab = next((n for n in W.nodes()
                if n["clickable"] and not (n["text"] or n["desc"]) and n["cy"] > 1900), None)
    assert fab is not None, "照片库里找不到「添加照片」按钮"
    W.tap(fab["cx"], fab["cy"])

    deadline = time.time() + 40
    while time.time() < deadline:
        cells = [n for n in W.nodes() if n["clickable"]
                 and not (n["text"] or n["desc"]) and 900 < n["cy"] < 1800]
        if cells:
            break
        d = W.find("Dismiss")
        if d:
            W.tap(d["cx"], d["cy"])
        time.sleep(1.5)
    else:
        raise RuntimeError("选图器没有出现照片网格")

    picked_xy = []
    for want in range(1, count + 1):
        cells = [n for n in W.nodes() if n["clickable"]
                 and not (n["text"] or n["desc"]) and 900 < n["cy"] < 1800
                 and all(abs(n["cx"] - x) > 40 or abs(n["cy"] - y) > 40 for x, y in picked_xy)]
        assert cells, "选图器里没有可选的空格子"
        cell = min(cells, key=lambda n: (n["cy"], n["cx"]))
        W.tap(cell["cx"], cell["cy"])
        picked_xy.append((cell["cx"], cell["cy"]))

        ok = False
        for _ in range(8):
            ns = W.nodes()
            counter = next((n for n in ns if "selected" in n["desc"]), None)
            if counter and counter["text"] == str(want):
                ok = True
                break
            time.sleep(1.0)
        if not ok:
            raise RuntimeError(f"点了第 {want} 格后，选择计数器没有变成 {want}")
    W.log(f"    计数器确认已选中 {len(picked_xy)} 张")

    done = W.find("Done")
    assert done is not None, "多选后没有出现 Done 按钮"
    W.tap(done["cx"], done["cy"])

    W.log("等待落库（两张原图加密）")
    deadline = time.time() + 120
    while time.time() < deadline:
        hit = W.find("原图加密存储 · ")
        if hit:
            m = re.search(r"(\d+)\s*张", hit["text"])
            if m and int(m.group(1)) >= count:
                W.log(f"    页头：{hit['text']}")
                return int(m.group(1))
        time.sleep(1.5)
    raise RuntimeError("照片库里始终没有出现足够数量的照片")


def open_viewer_and_swipe():
    """本次的核心：大图预览能不能左右滑动翻页。"""
    W.log("点开第一张大图")
    W.hide_ime()
    # 缩略图在第一行时 cy≈460，别把下界设太高（踩过：设 600 直接漏掉整行的图）。
    # 上界 1900 是为了排掉右下角的「添加照片」FAB。
    cells = sorted([n for n in W.nodes() if n["clickable"]
                    and not (n["text"] or n["desc"]) and 250 < n["cy"] < 1900],
                   key=lambda n: (n["cy"], n["cx"]))
    assert cells, "照片网格里找不到可点的缩略图"
    W.tap(cells[0]["cx"], cells[0]["cy"])

    W.wait_text("1 / 2", timeout=30, what="大图预览（第 1 张）")
    time.sleep(1.2)
    W.shot("深色-大图-第1张")

    W.log("向左滑动，看是否翻到第 2 张")
    W.adb("shell", "input", "swipe", "900", "1150", "180", "1150", "180")
    time.sleep(1.4)
    W.wait_text("2 / 2", timeout=20, what="翻页到第 2 张")
    W.shot("深色-大图-第2张-左滑成功")
    W.log("    左滑翻页成功 → 2 / 2")

    W.log("向右滑动，看是否翻回第 1 张")
    W.adb("shell", "input", "swipe", "180", "1150", "900", "1150", "180")
    time.sleep(1.4)
    W.wait_text("1 / 2", timeout=20, what="翻回第 1 张")
    W.log("    右滑翻页成功 → 1 / 2")

    close = W.find("关闭")
    if close:
        W.tap(close["cx"], close["cy"])
        time.sleep(1.0)


def tour_appearance():
    """深色模式下把各个界面走一遍留证。"""
    W.log("退回主页，检查各界面在深色下的可读性")
    # 看图用的是 Dialog，关掉之后人还在照片库里 —— 必须再退一层才是主页。
    # 判据用主页顶栏的"本机加密 · N 条"，别用"照片库"：那个词主页顶栏的入口也有。
    for _ in range(3):
        if W.find("本机加密") is not None:
            break
        W.adb("shell", "input", "keyevent", "4")
        time.sleep(1.2)
    else:
        raise RuntimeError("退不回主页")
    time.sleep(0.8)
    W.shot("深色-主页-有记录")

    W.log("新建文字记录 → 编辑器")
    fab = next((n for n in W.nodes()
                if n["clickable"] and not (n["text"] or n["desc"]) and n["cy"] > 1900), None)
    assert fab is not None
    W.tap(fab["cx"], fab["cy"])
    W.wait_text("文字", timeout=20, what="类型选择")
    W.shot("深色-新建-类型选择")
    W.tap_text("文字")
    W.wait_text("标题", timeout=20, what="编辑器")
    es = [n for n in W.nodes() if n["cls"] == "EditText"]
    W.tap(es[0]["cx"], es[0]["cy"])
    W.type_ascii("Dark-Mode-Note")
    W.hide_ime()
    es = [n for n in W.nodes() if n["cls"] == "EditText"]
    W.tap(es[1]["cx"], es[1]["cy"])
    W.type_ascii("readable-in-the-dark")
    W.hide_ime()
    W.shot("深色-编辑器-已填写")
    W.tap_text("保存")
    W.wait_text("Dark-Mode-Note", timeout=30, what="主页新记录")

    W.log("进入只读查看页")
    W.tap_text("Dark-Mode-Note")
    time.sleep(1.2)
    W.shot("深色-查看页")

    W.log("打开设置页，逐屏截")
    W.adb("shell", "input", "keyevent", "4")   # 返回主页
    time.sleep(1.0)
    hit = W.find("设置")
    assert hit is not None, "主页找不到设置入口"
    W.tap(hit["cx"], hit["cy"])
    W.wait_text("安全", timeout=25, what="设置页")
    time.sleep(0.8)
    W.shot("深色-设置-第1屏")
    for i in (2, 3):
        W.adb("shell", "input", "swipe", "540", "1700", "540", "700", "300")
        time.sleep(1.0)
        W.shot(f"深色-设置-第{i}屏")


def main():
    os.makedirs(OUT, exist_ok=True)

    W.log("安装 debug 包（允许截屏，便于取证）")
    W.adb("uninstall", PKG, timeout=120)
    install = W.adb("install", APK_DEBUG, timeout=240)
    W.log(f"    {install.strip().splitlines()[-1] if install.strip() else '(无输出)'}")

    # 先切深色再走引导 —— 这样引导页本身也在深色下被截到。
    # MainActivity 声明了 configChanges="uiMode"，所以这次切换不会重建 Activity，
    # 顺带验证 Compose 能不能就地重组出正确配色。
    W.log("切到系统深色模式")
    set_dark(True)

    onboarding()
    open_photo_library()
    total = import_photos(2)
    W.shot("深色-照片库-网格")
    open_viewer_and_swipe()
    tour_appearance()

    W.log("恢复浅色模式")
    set_dark(False)
    time.sleep(1.5)
    W.shot("浅色-设置页-对照")

    print()
    W.log(f"验收完成，共 {W.SHOT_INDEX} 张截图 → {OUT}")
    W.log(f"照片库共 {total} 张")


if __name__ == "__main__":
    main()
