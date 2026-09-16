#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.3 回归验收：本轮三项需求的端到端验证。

覆盖：
  1. 清空数据范围澄清（对话框文案出现"只删除 FPB 自己保存的内容"）
  2. 照片库：入口/空态/多选导入/网格/全屏查看/长按删除；
     诱饵库（假密码进入）主页【不显示】照片库入口
  3. 备忘录/计算器伪装图标重绘（徽章式，桌面截图留证）

复用 walkthrough.py 的 adb 辅助函数。
"""
import os
import re
import shutil
import subprocess
import sys
import time

sys.path.insert(0, r"D:\MixiaVault\tools")
import walkthrough as W

ADB = W.ADB
PKG = "com.fpb.vault"
OUT = r"D:\MixiaVault\dist\evidence\v103"
W.OUT = OUT
W.SHOT_INDEX = 0

MASTER = "VaultMaster2026x"
DECOY = "DecoyPass2026x"
TEST_PHOTO_LOCAL = r"D:\MixiaVault\tools\test-photo.png"
TEST_PHOTO_LOCAL_B = r"D:\MixiaVault\tools\test-photo-b.png"
REMOTE_A = "/sdcard/DCIM/Camera/fpb-test-photo.png"
REMOTE_B = "/sdcard/DCIM/Camera/fpb-test-photo-b.png"


def adb_out(*args, timeout=90):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout)\
        .stdout.decode("utf-8", errors="replace")


def fill_retry(index, value, tries=3):
    """fill_field 的重试版：模拟器输入偶发丢字/点偏，失败就重来一次。"""
    for attempt in range(tries):
        try:
            return W.fill_field(index, value)
        except RuntimeError as e:
            log(f"    fill_field({index}) 第 {attempt + 1} 次失败：{e}，重试")
            time.sleep(1.5)
    raise RuntimeError(f"输入框 {index} 填 {value!r} 连续 {tries} 次失败")


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def onboarding():
    log("清空应用数据并冷启动，走完整引导（含启用假密码）")
    W.adb("shell", "pm", "clear", PKG)
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    # pm clear 后首发 am start 偶尔被系统吞掉，且 BootScreen 的 KDF 预热要几十秒：
    # 30 秒没动静就再发一次，总超时放宽到 150 秒。
    try:
        W.wait_text("开始设置", timeout=30, what="引导页第一屏")
    except RuntimeError:
        log("    第一发 am start 疑似被吞，重发")
        W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        W.wait_text("开始设置", timeout=120, what="引导页第一屏（重试）")
    W.tap_text("开始设置")

    W.wait_text("设置主密码")
    fill_retry(0, MASTER)
    fill_retry(1, MASTER)
    W.hide_ime()
    W.tap_text("下一步")

    W.wait_text("要不要再设一个假密码")
    cb = next(n for n in W.nodes() if n["checkable"])
    assert not cb["checked"]
    W.tap(cb["cx"], cb["cy"])
    time.sleep(0.8)
    cb = next(n for n in W.nodes() if n["checkable"])
    assert cb["checked"], "假密码复选框没勾上"
    fill_retry(0, DECOY)
    W.hide_ime()
    W.tap_text("生成保险库")

    W.wait_text("抄下恢复码", timeout=180, what="恢复码屏")
    W.scroll_top()
    ns = W.nodes()
    groups = [n["text"] for n in ns if re.fullmatch(r"[0-9A-Z]{4}", n["text"])]
    assert len(groups) == 12
    labels = [int(m.group(1)) for n in ns
              for m in [re.fullmatch(r"第 (\d+) 组", n["text"])] if m]
    wanted = [groups[i - 1] for i in labels]

    W.hide_ime()
    W.scroll_top()
    es = W.edits()
    W.tap(es[0]["cx"], es[0]["cy"])
    W.type_ascii(wanted[0])
    for value in wanted[1:]:
        W.adb("shell", "input", "keyevent", W.KEYCODE_TAB)
        time.sleep(0.6)
        W.type_ascii(value)
    W.hide_ime()

    for _ in range(5):
        cb = next((n for n in W.nodes() if n["checkable"]), None)
        if cb is None:
            W.adb("shell", "input", "swipe", "540", "1500", "540", "800", "250")
            time.sleep(0.9)
            continue
        if cb["checked"]:
            break
        W.tap(cb["cx"], cb["cy"])
        time.sleep(0.7)
    else:
        raise RuntimeError("复选框始终勾不上")

    enter = W.find("进入保险库")
    assert enter is not None and enter["enabled"]
    W.tap(enter["cx"], enter["cy"])
    W.wait_text("还没有任何记录", timeout=90, what="保险库主页")
    time.sleep(1.2)
    W.shot("01-主页-真库空列表")


def photo_library_flow():
    log("[需求2] 照片库：入口与空态")
    entry = W.find("照片库")
    assert entry is not None, "真库主页顶栏没有照片库入口"
    W.tap(entry["cx"], entry["cy"])
    W.wait_text("还没有照片", timeout=20, what="照片库空态")
    time.sleep(1.0)
    W.shot("02-照片库-空态")

    log("[需求2] 照片库：多选导入两张测试图")
    fab = next(n for n in W.nodes() if n["clickable"] and not (n["text"] or n["desc"])
               and n["cy"] > 1900)
    W.tap(fab["cx"], fab["cy"])

    # 等选图器网格真正起来（Media grid / Collections 标记）。
    # 踩坑：底部的选择计数器和 Done 按钮**只在选中第一张后**才出现，
    # 不能用它们判断选图器是否已加载；太早点格子会点空。
    deadline = time.time() + 40
    while time.time() < deadline:
        ns = W.nodes()
        if any(n["desc"] == "Media grid" or n["desc"] == "Collections" for n in ns):
            break
        d = W.find("Dismiss")
        if d:
            W.tap(d["cx"], d["cy"])
        time.sleep(1.5)
    else:
        raise RuntimeError("选图器网格一直没有出现")
    time.sleep(1.5)  # 底部面板滑入动画 settle

    picked = 0
    tapped = []  # 已点过的格子坐标（选中外层 View 永远没有 desc，
    # 「Selected」只出现在内层说明节点上，靠 desc 过滤排除不掉已选格）
    for want in (1, 2):
        ns = W.nodes()
        cells = [n for n in ns if n["clickable"] and not n["desc"]
                 and 900 < n["cy"] < 1800
                 and all(abs(n["cx"] - x) > 80 or abs(n["cy"] - y) > 80 for x, y in tapped)]
        assert cells, "选图器里没有可选的照片单元格"
        cell = min(cells, key=lambda n: (n["cy"], n["cx"]))
        W.tap(cell["cx"], cell["cy"])
        tapped.append((cell["cx"], cell["cy"]))
        # 回读确认：计数器必须真的变成 want，点空了就重试
        ok = False
        for _ in range(6):
            ns = W.nodes()
            counter = next((n for n in ns if "selected" in n["desc"]), None)
            if counter and counter["text"] == str(want):
                ok = True
                break
            time.sleep(1.0)
        if not ok:
            raise RuntimeError(f"点了第 {want} 格后，选择计数器没有变成 {want}")
        picked = want
    log(f"    已多选 {picked} 张（计数器回读确认）")

    done = W.find("Done")
    assert done is not None, "多选后未出现 Done 按钮"
    W.tap(done["cx"], done["cy"])
    log("    已点 Done，等待落库")

    # 回到照片库，等待落库完成。页头从 picker 返回的瞬间就存在（显示 0 张），
    # 必须等到张数真正变成 ≥1 —— 否则会把导入中途的瞬时值当成结果。
    deadline = time.time() + 90
    count_text = None
    while time.time() < deadline:
        hit = W.find("原图加密存储 · ")
        if hit and re.search(r"原图加密存储 · [1-9]\d* 张", hit["text"]):
            count_text = hit["text"]
            break
        time.sleep(1.5)
    assert count_text, "照片库页头的张数统计一直没出现"
    log(f"    页头统计：{count_text}")
    W.shot("03-照片库-导入后网格")
    m = re.search(r"(\d+)\s*张", count_text)
    assert m, f"页头统计里解析不出张数：{count_text}"
    count = int(m.group(1))
    assert count == picked, f"预期导入 {picked} 张，实际 {count} 张"

    log("[需求2] 照片库：点缩略图进全屏查看")
    cells = [n for n in W.nodes() if n["clickable"] and not (n["text"] or n["desc"])
             and 300 < n["cy"] < 1800]
    assert cells, "照片网格里找不到可点的缩略图"
    cell = min(cells, key=lambda n: (n["cy"], n["cx"]))
    W.tap(cell["cx"], cell["cy"])
    pager = W.wait_text(f"1 / {count}", timeout=30, what="全屏看图页码")
    assert pager is not None
    W.shot("04-照片库-全屏查看")
    W.adb("shell", "input", "keyevent", "4")
    time.sleep(1.0)

    log("[需求2] 照片库：长按删除一张")
    cells = [n for n in W.nodes() if n["clickable"] and not (n["text"] or n["desc"])
             and 300 < n["cy"] < 1800]
    cell = min(cells, key=lambda n: (n["cy"], n["cx"]))
    W.adb("shell", "input", "swipe", str(cell["cx"]), str(cell["cy"]),
          str(cell["cx"]), str(cell["cy"]), "700")
    time.sleep(1.2)
    W.wait_text("删除这张照片？", timeout=15, what="删除确认对话框")
    W.shot("05-照片库-删除确认")
    W.tap_text("删除")

    deadline = time.time() + 30
    while time.time() < deadline:
        hit = W.find("原图加密存储 · ")
        if hit and f"{count - 1} 张" in hit["text"]:
            break
        time.sleep(1.0)
    else:
        raise AssertionError(f"删除后张数没有变成 {count - 1}")
    log(f"    删除成功：{count} → {count - 1} 张 ✓")
    W.shot("06-照片库-删除后")

    log("[需求2] 照片库导入也出现在记录列表里")
    W.adb("shell", "input", "keyevent", "4")
    time.sleep(1.0)
    hit = W.wait_text("照片 · ", timeout=20, what="列表里的照片记录")
    W.shot("07-主页-照片记录可见")
    return count


def wipe_dialog_check():
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


def decoy_hide_check():
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

    # 顺带验证：诱饵库设置页同样看不到入口以外的东西（基本健康检查）
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


def launcher_icon_check():
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
    log("    桌面截图完成（见 dist/evidence/v103/12-*.png 与 13-*.png）")


def main():
    os.makedirs(OUT, exist_ok=True)

    log("卸载旧包并安装 v1.0.3 debug 包")
    adb_out("uninstall", PKG)
    out = adb_out("install", r"D:\MixiaVault\app\build\outputs\apk\debug\app-debug.apk")
    log(f"    {out.strip().splitlines()[-1]}")
    assert "Success" in out, f"debug 包安装失败：{out}"

    log("推送两张测试图进相册并触发媒体扫描")
    if not os.path.exists(TEST_PHOTO_LOCAL):
        raise FileNotFoundError("缺少测试图 tools/test-photo.png，先跑 make_test_photo.py")
    shutil.copyfile(TEST_PHOTO_LOCAL, TEST_PHOTO_LOCAL_B)
    subprocess.run([ADB, "push", TEST_PHOTO_LOCAL, REMOTE_A], check=True, capture_output=True)
    subprocess.run([ADB, "push", TEST_PHOTO_LOCAL_B, REMOTE_B], check=True, capture_output=True)
    for remote in (REMOTE_A, REMOTE_B):
        W.adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
              "-d", f"file://{remote}")
    time.sleep(2.0)

    onboarding()
    photo_library_flow()
    wipe_dialog_check()
    decoy_hide_check()
    launcher_icon_check()

    print()
    log("v1.0.3 全流程验收完成")
    log(f"证据目录：{OUT}")


if __name__ == "__main__":
    main()
