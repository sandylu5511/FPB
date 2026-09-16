#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.2 回归验收：五项修复/改造的端到端验证。

覆盖：
  1. 引导时启用假密码 → 设置页应显示「已开启」，且假密码真的能解锁进诱饵库（Bug 修复）
  2. 备忘录/计算器伪装图标重绘（桌面截图留证）
  3. 图片原图直存（密文大小 == 源文件大小 + 28B 的二进制证据）
  4. 查看/编辑分离（点卡片进只读页，无输入框；「编辑」才进编辑器）
  5. iOS 风格 UI（设置分组卡片、居中标题等，全程截图）

复用 walkthrough.py 里踩过坑的 adb 辅助函数（dump 走 /dev/tty、BACK 收输入法等）。
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
W.SHOT_INDEX = 0

MASTER = "VaultMaster2026x"
DECOY = "DecoyPass2026x"
TEST_PHOTO_LOCAL = r"D:\MixiaVault\tools\test-photo.png"
TEST_PHOTO_REMOTE = "/sdcard/DCIM/Camera/fpb-test-photo.png"
BLOB_OVERHEAD = 28  # 12B nonce + 16B GCM tag


def adb_out(*args, timeout=90):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout)\
        .stdout.decode("utf-8", errors="replace")


def onboarding():
    log("清空应用数据并冷启动")
    W.adb("shell", "pm", "clear", PKG)
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    W.wait_text("开始设置", timeout=45, what="引导页第一屏")
    W.shot("引导-欢迎")
    W.tap_text("开始设置")

    W.wait_text("设置主密码")
    W.fill_field(0, MASTER)
    W.fill_field(1, MASTER)
    W.hide_ime()
    W.shot("引导-主密码")
    W.tap_text("下一步")

    # ---------- 第 3 屏：这次【启用】假密码 ----------
    W.wait_text("要不要再设一个假密码")
    cb = next(n for n in W.nodes() if n["checkable"])
    assert not cb["checked"], "假密码复选框初始态应为未勾选"
    W.tap(cb["cx"], cb["cy"])
    time.sleep(0.8)
    cb = next(n for n in W.nodes() if n["checkable"])
    assert cb["checked"], "假密码复选框没勾上"
    W.fill_field(0, DECOY)  # 勾选后本屏唯一的输入框就是假密码
    W.hide_ime()
    W.shot("引导-假密码已填写")
    W.tap_text("生成保险库")

    W.wait_text("抄下恢复码", timeout=180, what="恢复码屏（Argon2 双域派生较慢）")
    W.scroll_top()
    ns = W.nodes()
    groups = [n["text"] for n in ns if re.fullmatch(r"[0-9A-Z]{4}", n["text"])]
    assert len(groups) == 12, f"应显示 12 组恢复码，实际 {len(groups)}"
    labels = [int(m.group(1)) for n in ns
              for m in [re.fullmatch(r"第 (\d+) 组", n["text"])] if m]
    assert len(labels) == 3, f"应随机抽 3 组回填，实际 {labels}"
    wanted = [groups[i - 1] for i in labels]
    log(f"恢复码回填：第 {labels} 组")

    W.hide_ime()
    W.scroll_top()
    es = W.edits()
    assert es, "找不到回填输入框"
    W.tap(es[0]["cx"], es[0]["cy"])
    W.type_ascii(wanted[0])
    for value in wanted[1:]:
        W.adb("shell", "input", "keyevent", W.KEYCODE_TAB)
        time.sleep(0.6)
        W.type_ascii(value)
    W.hide_ime()
    W.shot("引导-恢复码已回填")

    W.hide_ime()
    for attempt in range(5):
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
    W.shot("引导-已确认抄写")

    enter = W.find("进入保险库")
    assert enter is not None and enter["enabled"]
    W.tap(enter["cx"], enter["cy"])
    W.wait_text("还没有任何记录", timeout=90, what="保险库主页")
    time.sleep(1.2)
    W.shot("主页-空列表")


def settings_decoy_check():
    log("[修复1] 设置页假密码应显示「已开启」")
    W.tap_text("设置")
    W.wait_text("外观与伪装", timeout=20, what="设置页")
    hit = W.find("已开启")
    assert hit is not None, "设置页没有出现「已开启」—— 引导设置的假密码没写进标志位（Bug 未修复）"
    log("    设置页显示：假密码 已开启 ✓")
    W.shot("设置-假密码已开启")

    # 顺手把设置页分组卡片整体截两张（iOS 风格 UI 证据）
    W.adb("shell", "input", "swipe", "540", "1800", "540", "700", "300")
    time.sleep(0.8)
    W.shot("设置-iOS分组卡片-中部")
    W.adb("shell", "input", "swipe", "540", "1800", "540", "700", "300")
    time.sleep(0.8)
    W.shot("设置-iOS分组卡片-下部")
    W.adb("shell", "input", "keyevent", "4")  # 返回主页
    time.sleep(1.0)


def create_text_note_and_check_view():
    log("[修复4] 新建文字记录，点卡片应进只读查看页")
    fab = next(n for n in W.nodes() if n["clickable"] and not (n["text"] or n["desc"])
               and n["cy"] > 1900)
    W.tap(fab["cx"], fab["cy"])
    W.wait_text("文字", timeout=20)
    W.shot("新建-类型选择")
    W.tap_text("文字")

    W.wait_text("标题", timeout=20, what="文字编辑器")
    es = W.edits()
    W.tap(es[0]["cx"], es[0]["cy"])
    W.type_ascii("Acceptance-View-01")
    W.hide_ime()
    es = W.edits()
    W.tap(es[1]["cx"], es[1]["cy"])
    W.type_ascii("top-secret-body-2026")
    W.hide_ime()
    W.tap_text("保存")
    W.wait_text("Acceptance-View-01", timeout=30, what="主页出现新记录")
    time.sleep(0.8)

    # 点卡片 → 查看页：不允许再出现任何 EditText
    W.tap_text("Acceptance-View-01")
    W.wait_text("更新于", timeout=20, what="只读查看页")
    time.sleep(0.8)
    assert not W.edits(), "查看页不应有输入框（仍直接进了编辑器 = 未修复）"
    log("    查看页无输入框、无保存按钮 ✓")
    W.shot("查看-文字只读页")

    # 查看页 → 编辑入口 → 编辑器
    edit = W.find("编辑")
    assert edit is not None, "查看页找不到「编辑」按钮"
    W.tap(edit["cx"], edit["cy"])
    W.wait_text("标题", timeout=20, what="从查看页进入编辑器")
    assert W.edits(), "编辑器应有输入框"
    W.shot("查看-切到编辑器")
    W.adb("shell", "input", "keyevent", "4")  # 编辑器 → 查看页
    time.sleep(0.8)
    W.wait_text("更新于", timeout=20, what="返回查看页")
    W.shot("查看-从编辑器返回")
    W.adb("shell", "input", "keyevent", "4")  # 查看页 → 主页
    time.sleep(0.8)
    log("    查看/编辑两级导航闭环 ✓")


def create_image_note_and_check_original():
    log("[修复3] 新建图片记录（原图直存验证）")
    local_size = os.path.getsize(TEST_PHOTO_LOCAL)
    log(f"    源图：test-photo.png = {local_size} B（4000x3000）")

    fab = next(n for n in W.nodes() if n["clickable"] and not (n["text"] or n["desc"])
               and n["cy"] > 1900)
    W.tap(fab["cx"], fab["cy"])
    time.sleep(1.2)
    W.tap_text("图片")
    time.sleep(1.5)
    btn = next(n for n in W.nodes() if n["cls"] == "Button" and 700 < n["cy"] < 950)
    W.tap(btn["cx"], btn["cy"])
    for _ in range(3):
        time.sleep(2.0)
        d = W.find("Dismiss")
        if d:
            W.tap(d["cx"], d["cy"])
            time.sleep(0.8)
    cells = [n for n in W.nodes() if n["clickable"]
             and 950 < n["cy"] < 1700 and not (n["text"] or n["desc"])]
    assert cells, "选图器里没有照片单元格"
    # Photo Picker 按时间倒序：最新 push 的测试图应在第一格。为了稳妥，
    # 优先找左上角（cx 最小）的格子 —— 若选错，后面有二进制尺寸校验兜底。
    cell = min(cells, key=lambda n: (n["cy"], n["cx"]))
    W.tap(cell["cx"], cell["cy"])
    time.sleep(2.5)
    done = W.find("Done")
    assert done is not None, "未出现 Done 按钮"
    W.tap(done["cx"], done["cy"])
    W.wait_text("图片（1 / 100）", timeout=60, what="编辑器出现缩略图")
    log("    图片已进入编辑器（未闪退，fragment 1.8.9 修复仍有效）")
    W.shot("编辑器-图片已选")

    es = W.edits()
    W.tap(es[0]["cx"], es[0]["cy"])
    W.type_ascii("Photo-Original-01")
    W.hide_ime()
    W.tap_text("保存")
    W.wait_text("Photo-Original-01", timeout=60, what="主页出现图片记录")

    # ---------- 二进制证据：密文大小 == 源文件 + 28 ----------
    listing = adb_out("exec-out", "run-as", PKG, "ls", "-l", "files/attachments")
    sizes = []
    for line in listing.splitlines():
        parts = line.split()
        # toybox ls -l：perms links owner group size date time name
        if len(parts) >= 8 and parts[0].startswith("-"):
            sizes.append(int(parts[4]))
    assert sizes, f"run-as 列不出密文：{listing!r}"
    log(f"    附件密文大小：{sizes}")
    if local_size + BLOB_OVERHEAD in sizes:
        log(f"    原图直存验证 ✓：存在 {local_size}+{BLOB_OVERHEAD} = {local_size + BLOB_OVERHEAD} B 的密文")
        exact = True
    else:
        exact = False
        log(f"    ⚠ 未找到 {local_size + BLOB_OVERHEAD} B 的密文 —— 选图器选到的可能不是测试图，")
        log("      尝试用媒体库其他图片尺寸匹配……")
        media = adb_out("shell", "content", "query", "--uri",
                        "content://media/external/images/media",
                        "--projection", "_size")
        media_sizes = set(int(m.group(1)) for m in re.finditer(r"_size=(\d+)", media))
        matched = [s for s in sizes if s - BLOB_OVERHEAD in media_sizes]
        assert matched, f"没有任何密文能和媒体库原图对上：blobs={sizes} media={sorted(media_sizes)}"
        log(f"    原图直存验证 ✓（按媒体库匹配）：{matched}")

    # ---------- 查看页：图片网格 ----------
    W.tap_text("Photo-Original-01")
    W.wait_text("更新于", timeout=20, what="图片记录查看页")
    time.sleep(1.5)  # 缩略图解密是异步的
    assert not W.edits(), "图片查看页不应有输入框"
    W.shot("查看-图片只读页")
    W.adb("shell", "input", "keyevent", "4")
    time.sleep(0.8)
    return exact


def decoy_unlock_check():
    log("[修复1 续] 用假密码解锁：应进入独立的诱饵空库")
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
    W.wait_text("还没有任何记录", timeout=90, what="诱饵库主页（空）")
    time.sleep(1.0)
    W.shot("假密码-进入诱饵库")

    W.tap_text("设置")
    W.wait_text("当前在诱饵库", timeout=20, what="诱饵库设置页标识")
    log("    设置页标识「当前在诱饵库」✓")
    W.shot("假密码-诱饵库设置")
    W.adb("shell", "input", "keyevent", "4")
    time.sleep(0.8)

    log("再用主密码解锁：应回到真库")
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
    W.wait_text("Acceptance-View-01", timeout=90, what="真库主页（有记录）")
    W.shot("主密码-回到真库")
    log("    假密码进诱饵库、主密码回真库 ✓")


def launcher_icon_check():
    log("[修复2] 桌面伪装图标（备忘录/计算器）")
    W.tap_text("设置")
    W.wait_text("外观与伪装", timeout=20, what="设置页")

    for label in ("备忘录", "计算器"):
        W.tap_text_scrolling(label)
        time.sleep(1.5)
        # 回到桌面截图（别名切换后 launcher 刷新要几秒）
        W.adb("shell", "input", "keyevent", "3")  # HOME
        time.sleep(5.0)
        W.shot(f"桌面-{label}图标")
        # 从别名入口重新拉起（MainActivity 本体始终可显式启动）
        W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        time.sleep(3.0)

    # 切回 FPB
    W.wait_text("外观与伪装", timeout=20, what="回到设置页")
    W.tap_text_scrolling("FPB")
    time.sleep(1.5)
    W.adb("shell", "input", "keyevent", "3")
    time.sleep(5.0)
    W.shot("桌面-FPB图标")
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2.0)
    log("    三种图标桌面截图完成（见 dist/evidence/v102/桌面-*.png）")


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def main():
    os.makedirs(OUT, exist_ok=True)

    log("卸载旧包（上轮装过 release，签名不同会导致 install -r 静默失败）并安装 debug 包")
    adb_out("uninstall", PKG)
    out = adb_out("install", r"D:\MixiaVault\app\build\outputs\apk\debug\app-debug.apk")
    log(f"    {out.strip().splitlines()[-1]}")
    assert "Success" in out, f"debug 包安装失败：{out}"

    log("推送 4000x3000 测试图进相册并触发媒体扫描")
    subprocess.run([ADB, "push", TEST_PHOTO_LOCAL, TEST_PHOTO_REMOTE], check=True,
                   capture_output=True)
    W.adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
          "-d", f"file://{TEST_PHOTO_REMOTE}")
    time.sleep(2.0)

    onboarding()
    settings_decoy_check()
    create_text_note_and_check_view()
    exact = create_image_note_and_check_original()
    decoy_unlock_check()
    launcher_icon_check()

    print()
    log("v1.0.2 全流程验收完成")
    log(f"证据目录：{OUT}")
    if not exact:
        log("（注：原图直存为媒体库匹配验证，非 push 源图直配）")


if __name__ == "__main__":
    main()
