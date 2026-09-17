#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.9 走查：实况照片「播放被拉伸」与「要手动点才播」两处修复。

     python tools/walkthrough-v109-motion.py A     # 对照轮：出货版 v1.0.8（release）
     python tools/walkthrough-v109-motion.py B     # 验证轮：本次修复版（debug）

## 为什么必须跑对照轮

"不再变形"这句话，光量一版数字证明不了 —— 得先让缺陷在**同一个台子、同一份素材**上重现，
修复版那组数字才有对照。所以两轮用的素材、设备、量法完全一致，只有 APK 不同。

## 判据：从截图里量几何，不靠肉眼看

测试影片（`tools/motion/make_probe_assets.py` 合成）满足三件事：

  · 源尺寸 **320×180 ＝ 16:9**。正确显示时应占 **1080×607** 的横向条带；
    被拉伸铺满整屏则是 **1080×2400**。相差 4 倍。
  · 画面正中央画了一个**正方形**（描边）。它的高宽比就是"有没有被拉伸"的直接读数：
    正确 → **1.00**；铺满整屏 → **0.25**。
  · 底色**纯红**，而静止图是深蓝底。色相差得远，"影片区域"用颜色就能一刀切出来。

上一版是靠"看截图觉得没变形"放行的。**纯色画面被拉伸之后看起来还是纯色** ——
那个判据天生失效，纵向拉长 25% 就这样活了一个版本。画一个正方形进去，几何自己会说话。

## 采样：连拍，不等

影片 4 秒，而 `screencap` 一次要几百毫秒。打开大图之后**立刻连拍一串**，
再挑出"红底条带出现"的帧来量。"播放中"那几个字在屏幕上活不到一次 dump
（坑 10），所以不信界面文案，只信像素。

## 两轮各验什么

  A（出货版）：① 打开大图**不会**自动播 → 连拍全是静止图；② 顶栏有「实况」胶囊（要手点）；
              ③ 点了之后才出条带 —— 记录它的实际几何。
  B（修复版）：① 打开大图**没点任何东西**就出条带 → "打开即播"成立；
              ② 条带是 1080×607、正方形比例 ≈1.00 → 没有拉伸；
              ③ 播完「实况」胶囊回来（可重播），且不会被自动播放死循环顶掉。
"""

import importlib.util
import os
import re
import subprocess
import sys
import time

from PIL import Image

BASE = r"D:\MixiaVault"
ADB = r"D:\AndroidSdk\platform-tools\adb.exe"
PKG = "com.fpb.vault"
OUT = rf"{BASE}\dist\evidence\v109-motion"
APK_BROKEN = rf"{BASE}\dist\FPB-v1.0.8.apk"                     # 出货版（release）
APK_FIXED = rf"{BASE}\app\build\outputs\apk\debug\app-debug.apk"  # 本次修复版

VIEW_W, VIEW_H = 1080, 2400
VIDEO_W, VIDEO_H = 320, 180                      # 影片源尺寸
BAND_EXPECT = VIEW_W * VIDEO_H / VIDEO_W         # 16:9 铺进 1080 宽应得的高度 = 607.5
SCAN_TOP = 280                                   # 顶栏那一行文案会变（实况↔停止），不参与量
BURST = 12                                       # 每串连拍张数（实测约 0.5s/张，够盖住 4s 影片）

# 系统手势条（白色胶囊）实测位置：y 2364..2373、x 398..681。
# 影片一旦铺满整屏，这东西就落进条带里，必须排掉，否则白像素包围盒会量成它。
# 沿用 walkthrough-v106-part2.py 里量出来的那组数字（同型号 AVD、同分辨率）。
GESTURE_Y, GESTURE_X0, GESTURE_X1 = 2350, 350, 730

FAILURES = []


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V105 = load(rf"{BASE}\tools\walkthrough-v105.py", "w105")
R108 = load(rf"{BASE}\tools\v108-release-acceptance.py", "r108")
W = V105.W
W.OUT = OUT
W.SHOT_INDEX = 0

PHASE_TAG = "?"

_real_shot = W.shot


def phase_shot(name):
    """把引导流程里的截图名换成这一轮该有的样子。

    `V105.onboarding()` 是"深色模式"那次验收写的，截图名统一带「深色-」前缀。
    直接拿来跑这一轮，证据目录里会躺一堆叫「深色-引导-欢迎」的图 ——
    与本次结论无关，还会误导后来翻证据的人。这里统一改成「A/B-…」。
    """
    return _real_shot(f"{PHASE_TAG}-{name.replace('深色-', '')}")


W.shot = phase_shot


def sh(*args):
    """跑一条 adb 命令，stdout / stderr 合并返回。

    必须合并：`run-as` 的拒绝、`pm clear` 的结果都可能只走一边，
    只读 stdout 会把"失败了"看成"没输出"。
    """
    p = subprocess.run([ADB, *args], capture_output=True, timeout=180)
    return (p.stdout + p.stderr).decode("utf-8", "replace").replace("\r", "")


def check(label, ok, detail=""):
    print(f"    [{'PASS' if ok else 'FAIL'}] {label}：{detail}")
    if not ok:
        FAILURES.append(f"{label} —— {detail}")
    return ok


def note(label, detail):
    """只记录、不判定。对照轮的几何数据用这个，避免"对照组没重现"把整轮判红。"""
    print(f"    [记录] {label}：{detail}")


# ==================== 像素度量 ====================

def is_red(r, g, b):
    return r > 140 and g < 90 and b < 90


def is_white(r, g, b):
    return r > 200 and g > 200 and b > 200


def band_of(im):
    """找出影片条带（红底）的 y 范围与 x 范围；整幅画面都不是红的就返回 None。

    判据是"这一行有 45% 以上的采样点是红的"。横向取样步长 3，够稳也够快。
    中间那几行被白色正方形/移动白块占掉一部分，占比掉到 80% 左右，仍远高于阈值。
    """
    px = im.load()
    w, h = im.size
    step = 3
    xs = list(range(0, w, step))
    rows = [y for y in range(SCAN_TOP, h)
            if sum(1 for x in xs if is_red(*px[x, y])) >= 0.45 * len(xs)]
    if not rows:
        return None
    y0, y1 = rows[0], rows[-1]

    cols = [x for x in range(0, w) if any(is_red(*px[x, y]) for y in range(y0, y1 + 1, 9))]
    return {"y0": y0, "y1": y1, "h": y1 - y0 + 1,
            "x0": cols[0], "x1": cols[-1], "w": cols[-1] - cols[0] + 1}


def square_of(im, band):
    """条带里那个白色正方形（描边）的包围盒。

    两处**都必须排掉**，否则白像素的包围盒会量成别的东西：

    1. **顶部那个横向移动的白块**：它在源画面里位于 y 14..32（占高 7.8%~17.8%），
       把条带最上面 25% 切掉就不会误收。切完之后条带里的白像素只剩那个正方形。
    2. **系统的手势条**（底部那个白色胶囊，实测 y 2364..2373、x 398..681）：
       影片铺满整屏时条带一直延伸到屏幕底部，这个胶囊就落在条带里了 ——
       它会把包围盒的 x 从正方形的 [439,641] 撑到 [398,681]、y 撑到 2373。
       第一跑就是这么量出 284×1564（比例 0.182）的，而正方形真身是 202×800（0.253）。
       数字看着"确实变形了"，但那个数不是正方形的，拿去做结论迟早要出事。
    """
    px = im.load()
    w, _ = im.size
    y_from = band["y0"] + int(0.25 * band["h"])
    x0 = y0 = 10 ** 9
    x1 = y1 = -1
    for y in range(y_from, band["y1"] + 1):
        skip_gesture = y >= GESTURE_Y
        for x in range(w):
            if skip_gesture and GESTURE_X0 <= x <= GESTURE_X1:
                continue
            if is_white(*px[x, y]):
                x0 = min(x0, x); x1 = max(x1, x)
                y0 = min(y0, y); y1 = max(y1, y)
    if x1 < 0:
        return None
    bw, bh = x1 - x0 + 1, y1 - y0 + 1
    return {"x0": x0, "x1": x1, "y0": y0, "y1": y1, "w": bw, "h": bh, "ratio": bw / bh}


def describe(band, sq):
    s = (f"条带 x[{band['x0']},{band['x1']}] y[{band['y0']},{band['y1']}] = {band['w']}×{band['h']}"
         f"，宽高比 {band['w'] / band['h']:.3f}")
    if sq:
        s += (f"；正方形 {sq['w']}×{sq['h']} 比例 {sq['ratio']:.3f}"
              f"（源 60×60 应得 1.000）")
    else:
        s += "；量不到正方形"
    return s


# ==================== 截图 ====================

def grab(name):
    """抓一帧存盘，返回 (路径, PIL Image)。"""
    os.makedirs(OUT, exist_ok=True)
    raw = subprocess.run([ADB, "exec-out", "screencap", "-p"],
                         capture_output=True, timeout=60).stdout
    path = os.path.join(OUT, f"{name}.png")
    with open(path, "wb") as f:
        f.write(raw)
    return path, Image.open(path).convert("RGB")


def burst(prefix):
    """连拍一串，返回 [(路径, band)]。

    **不 sleep**：一 sleep 就把采样点推到播放结束之后了。影片 4 秒、
    screencap 一次几百毫秒，连拍 16 张刚好覆盖整个播放窗口。
    """
    rows = []
    for i in range(BURST):
        raw = subprocess.run([ADB, "exec-out", "screencap", "-p"],
                             capture_output=True, timeout=60).stdout
        path = os.path.join(OUT, f"{prefix}-{i:02d}.png")
        with open(path, "wb") as f:
            f.write(raw)
        try:
            rows.append((path, band_of(Image.open(path).convert("RGB"))))
        except Exception as e:
            W.log(f"    第 {i} 帧读不出来（{e}）")
            rows.append((path, None))
    hit = [(p, b) for p, b in rows if b]
    W.log(f"    连拍 {len(rows)} 帧，其中 {len(hit)} 帧看到影片条带")
    return rows, hit


# ==================== 界面动作 ====================

def installed_version():
    out = sh("shell", "dumpsys", "package", PKG)
    m = re.search(r"versionName=(\S+)", out)
    n = re.search(r"versionCode=(\d+)", out)
    return f"{m.group(1) if m else '?'} (code {n.group(1) if n else '?'})"


def exact(text):
    """精确匹配的文案节点。

    不能用子串：顶栏同时存在「实况」胶囊与「正在播放实况」，
    子串匹配会把两者混成一个。
    """
    return next((n for n in W.nodes() if n["text"] == text), None)


def grant_screenshots():
    """release 包默认开 FLAG_SECURE（截图全黑），进设置关掉它。

    debug 包默认就是关的（`BuildConfig.DEBUG` 决定），所以这一步只在对照轮做。
    关掉之前先留一张黑屏证据：那本身就证明"默认禁止截屏"是真的接在窗口上的。
    """
    W.log("release 包：进设置关掉「禁止截屏」（默认开着，不然截出来全是黑的）")
    time.sleep(2.0)
    W.shot("约束-默认禁止截屏")
    assert R108.open_settings(W), "打不开设置页"
    time.sleep(1.5)
    before = R108.is_checked(W, "禁止截屏")
    assert before is True, f"release 包「禁止截屏」默认应为开，实际 {before}"
    R108.toggle_row(W, "禁止截屏")
    after = R108.is_checked(W, "禁止截屏")
    assert after is False, f"「禁止截屏」没关掉（仍是 {after}）"
    W.log("    ✓ 已允许截屏")
    back = W.find("返回")
    if back:
        W.tap(back["cx"], back["cy"])
        time.sleep(1.5)


def open_library():
    W.log("主页 → 照片库")
    hit = W.find("照片库")
    assert hit is not None, "主页顶栏找不到照片库入口"
    W.tap(hit["cx"], hit["cy"])
    W.wait_text("原图加密存储", timeout=30, what="照片库页")


def library_count():
    hit = W.find("原图加密存储 · ")
    if not hit:
        return None
    m = re.search(r"(\d+)\s*张", hit["text"])
    return int(m.group(1)) if m else None


def picker_cells():
    return [n for n in W.nodes()
            if n["clickable"] and not (n["text"] or n["desc"]) and 900 < n["cy"] < 1800]


def import_probe():
    """把那张合成实况照片导进库。

    ## 为什么要按**缩略图颜色**挑格子，而不是"左上第一格"

    媒体库里还留着几条指向**已被搬走文件**的记录 —— `mv` 走不会让 MediaProvider 删行
    （重启、`pm clear`、对已删路径重发扫描广播，实测都清不掉；设备没有 root）。
    于是选图器里可能有几个格子是坏的。上一版脚本写死"左上第一格就是它"，
    在这台设备上就是不成立的假设。

    改成量每一格缩略图的平均色，挑**最像那张静止图**的一格。判据不能写成"最暗的一格" ——
    第一次跑就是这么写的，结果挑中了一个纯黑 (0,0,0) 的坏格子（媒体库里只剩灰白/黑占位），
    导入自然不是实况照片，后面一路报"找不到实况胶囊"，排查方向被带偏一整轮。
    期望色也不写死在代码里，直接**问我们自己的静止图**（`probe-base.jpg` 的均值），
    素材换了判据自动跟着走。
    """
    before = library_count()
    W.log(f"打开选图器（导入前库里有 {before} 张）")
    fab = next((n for n in W.nodes()
                if n["clickable"] and not (n["text"] or n["desc"]) and n["cy"] > 1900), None)
    assert fab is not None, "照片库里找不到「添加照片」按钮"
    W.tap(fab["cx"], fab["cy"])

    deadline = time.time() + 45
    cells = []
    while time.time() < deadline:
        cells = picker_cells()
        if cells:
            break
        d = W.find("Dismiss")
        if d:
            W.tap(d["cx"], d["cy"])
        time.sleep(1.5)
    assert cells, "选图器没有出现照片网格"
    W.log(f"    选图器里看到 {len(cells)} 个格子")

    ref = Image.open(rf"{BASE}\tools\motion\probe-base.jpg").convert("RGB").resize((60, 60))
    rp = ref.load()
    want = tuple(sum(rp[x, y][i] for y in range(60) for x in range(60)) / 3600.0
                 for i in range(3))
    W.log(f"    目标色（静止图缩略后的均值）"
          f"({want[0]:.0f},{want[1]:.0f},{want[2]:.0f})")

    _, im = grab("_picker")
    px = im.load()
    scored = []
    for n in cells:
        r = g = b = cnt = 0
        for y in range(max(0, n["cy"] - 50), min(im.size[1], n["cy"] + 50), 4):
            for x in range(max(0, n["cx"] - 50), min(im.size[0], n["cx"] + 50), 4):
                pr, pg, pb = px[x, y]
                r += pr; g += pg; b += pb; cnt += 1
        mean = (r / cnt, g / cnt, b / cnt)
        dist = sum((mean[i] - want[i]) ** 2 for i in range(3)) ** 0.5
        scored.append((dist, mean, n))
    for dist, mean, n in sorted(scored, key=lambda t: t[0]):
        W.log(f"    格子 ({n['cx']},{n['cy']}) 平均色 "
              f"({mean[0]:.0f},{mean[1]:.0f},{mean[2]:.0f})  距目标 {dist:.0f}")
    dist, mean, cell = min(scored, key=lambda t: t[0])
    assert dist < 50, (f"没有一格像那张静止图（最近的也有 {dist:.0f}）——"
                       f"相册里没有那张实况照片了吗？")
    W.log(f"    → 选中 ({cell['cx']},{cell['cy']})，距目标色 {dist:.0f}")
    W.tap(cell["cx"], cell["cy"])
    time.sleep(1.5)

    done = W.find("Done") or W.find("完成")
    assert done is not None, "多选后没有出现 Done 按钮"
    W.tap(done["cx"], done["cy"])

    # 页头张数**不是立刻**变成新值：一看到数字就 return 会读到导入前的旧值（踩过）。
    # 判据是"真的变了"。
    deadline = time.time() + 180
    last = None
    while time.time() < deadline:
        try:
            last = library_count()
        except RuntimeError as e:
            W.log(f"    （dump 暂时不可用，继续等：{e}）")
            last = None
        if last is not None and (before is None or last > before):
            W.log(f"    页头：原图加密存储 · {last} 张（导入前 {before} 张）")
            # 顺手自证"导对的那一张"：实况照片在照片库里带「实况」角标。
            # 少了这一步，导错图要等到打开大图才暴露，报出来的是"找不到实况胶囊" ——
            # 看起来像"角标/识别功能坏了"，实际只是导入的源文件不对（第一跑就这么白跑了一轮）。
            time.sleep(1.5)
            badge = W.find("实况")
            W.log(f"    照片库角标：{'看到「实况」✓' if badge else '⚠ 没看到「实况」，可能导错了图'}")
            return last
        time.sleep(1.5)
    raise RuntimeError(f"照片库张数始终没变（导入前 {before}，最后读到 {last}）")


def tap_thumbnail():
    """点开第 1 张大图。**只点，不等** —— 连拍必须紧跟着这一下。"""
    cells = [n for n in W.nodes()
             if n["clickable"] and not (n["text"] or n["desc"]) and 280 < n["y1"] < 1900]
    assert cells, "照片网格里找不到可点的缩略图"
    cell = min(cells, key=lambda n: (n["cy"], n["cx"]))
    W.log(f"点开第 1 张大图 (缩略图在 {cell['cx']},{cell['cy']})")
    W.tap(cell["cx"], cell["cy"])


def wait_viewer(timeout=30):
    """等大图页真的起来（顶栏出现 "N / M" 页码）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if any(re.fullmatch(r"\d+ / \d+", n["text"].strip()) for n in W.nodes()):
                return True
        except RuntimeError:
            pass
        time.sleep(1.0)
    return False


def leave_viewer():
    """退回照片库，好再进一次。"""
    back = W.find("关闭") or W.find("返回")
    if back:
        W.tap(back["cx"], back["cy"])
        time.sleep(1.5)


def enter_and_burst(tag, attempts=3):
    """反复"点开大图 → **立刻**连拍"，直到抓到影片条带；抓不到才算"没播"。

    ## 为什么必须重试、以及为什么这一条要写这么细

    影片只有 4 秒，连拍的起点必须卡在"点开的那一瞬"。第一版是"等大图页页码出现"之后
    再 `sleep 1.0` 才开拍 —— 等页码本身就要 1~3 秒，于是连拍永远晚于播放结束。

    2026-09-16 那次报出来的结论是「没点任何东西，自动播没生效」，
    而 logcat 里明明是：

        09:36:36.491  NuPlayerDriver created        ← 点开那一刻（全程无任何点击）
        09:36:36.755  info/warning (3, 0)           ← 第一帧已渲染
        09:36:40.747  reset                          ← 播完，正好 4.0 秒
        09:36:42      …脚本的连拍才开始…             ← 晚了 2 秒

    **"没抓到"被当成了"没播"** —— 修复其实一直是好的。所以这里既把采样点提到点开的那一瞬，
    又安排多次尝试：抓到了就说明原先只是采样问题，三次都抓不到才说明真的没播。
    单次采样去证否一件事，本来就是靠不住的（同 walkthrough 里"坑 10"那条）。
    """
    everything = []
    for k in range(attempts):
        if k:
            W.log(f"    第 {k + 1} 次尝试：退回照片库再进一次")
            leave_viewer()
        tap_thumbnail()
        rows, hit = burst(f"{tag}-try{k}")
        everything += rows
        if hit:
            if k:
                W.log(f"    第 {k + 1} 次尝试才抓到 —— 前几次是采样没对上，不是没播")
            return everything, hit
        # 没抓到：先把大图页等出来，否则下一次"退回"会点空
        wait_viewer(timeout=20)
    return everything, []


# ==================== 一轮 ====================

def run(phase):
    global PHASE_TAG
    PHASE_TAG = phase
    broken = phase == "A"
    label = "对照轮 · 出货版 v1.0.8（缺陷版）" if broken else "验证轮 · 本次修复版"
    apk = APK_BROKEN if broken else APK_FIXED
    W.log(f"================ {label} ================")
    W.log(f"安装 {apk}")

    sh("uninstall", PKG)
    out = sh("install", "-r", "-g", apk)
    assert "Success" in out, f"安装失败：{out}"
    ver = installed_version()
    W.log(f"    设备上装的是 {ver}")
    if broken:
        assert "1.0.8" in ver, f"要装的对照包是 1.0.8，实际 {ver}"

    V105.onboarding()          # 内含 pm clear，所以每轮都是全新安装路径

    if broken:
        grant_screenshots()

    open_library()
    import_probe()

    # 清一次 logcat：后面两条判据是"缓冲区里有没有某行"这种形式，
    # 不清就会把**上一轮**的记录算成这一轮的 —— 而对照轮与验证轮跑在同一台设备上。
    sh("logcat", "-c")
    W.log("==== 打开大图：**不点任何东西**，看会不会自动播 ====")
    rows_nobrand, hit_nobrand = enter_and_burst(f"{phase}-noplay")

    if broken:
        check("出货版：连点开三次都不自动播，必须手动点「实况」（缺陷：要手动点）",
              not hit_nobrand, f"{len(rows_nobrand)} 帧里 {len(hit_nobrand)} 帧出现影片条带")
        if not wait_viewer(timeout=20):
            raise RuntimeError("大图页没起来")
        pill = exact("实况")
        check("出货版：顶栏有「实况」胶囊（手动播放入口）", pill is not None,
              "找到了" if pill else "没找到")
        assert pill is not None, "找不到「实况」胶囊，点不了"
        W.log("点顶栏「实况」胶囊，再连拍")
        W.tap(pill["cx"], pill["cy"])
        time.sleep(1.0)
        _, hit = burst(f"{phase}-after-tap")
    else:
        check("修复版：**没点任何东西**就自动播起来了（缺陷已修）",
              bool(hit_nobrand),
              f"{len(rows_nobrand)} 帧里 {len(hit_nobrand)} 帧出现影片条带")
        hit = hit_nobrand
        wait_viewer(timeout=20)

    assert hit, "整轮都没有一帧出现影片条带 —— 影片根本没播起来，后面的几何量不了"
    best_path, band = max(hit, key=lambda t: t[1]["h"])
    best = Image.open(best_path).convert("RGB")
    sq = square_of(best, band)
    W.log(f"量到的帧：{os.path.basename(best_path)}")
    W.log(f"    {describe(band, sq)}")

    # ---- 几何判据 ----
    if broken:
        # 对照轮的几何**只记录**、不判定：出货版在模拟器上到底拉不拉伸，
        # 是这一轮要问出来的问题，不是预设的答案。
        note("出货版实测几何（这就是要复现的缺陷）", describe(band, sq))
        note("出货版条带是否铺满整屏",
             f"y {band['y0']}..{band['y1']}，屏高 {VIEW_H} → "
             + ("铺满（=> 纵向被拉伸）" if band["h"] > 1200 else "未铺满"))
    else:
        check(f"条带高度 ≈ {BAND_EXPECT:.0f}px（16:9 铺进 1080 宽应得的高度）",
              abs(band["h"] - BAND_EXPECT) <= 15,
              f"实测 {band['h']}px，差 {band['h'] - BAND_EXPECT:+.0f}px")
        check("条带横向铺满可视宽度（影片没被裁成更窄的一条）",
              band["x0"] <= 4 and band["x1"] >= VIEW_W - 5,
              f"x[{band['x0']},{band['x1']}]，屏宽 {VIEW_W}")
        if sq:
            check("画面里的正方形渲染出来还是正方形（比例 ≈1.00；被拉伸会变成 0.25）",
                  abs(sq["ratio"] - 1.0) <= 0.08,
                  f"{sq['w']}×{sq['h']} → 比例 {sq['ratio']:.3f}")
        else:
            check("画面里的正方形能量到", False, "条带里找不到白像素")

    # ---- 播完之后 ----
    W.log("等播放结束，确认界面回到「可重播」而不是卡在播放态")
    time.sleep(3.0)
    _, after = grab(f"{phase}-after-playback")
    check("播完后条带消失（回到静止图）", band_of(after) is None,
          f"条带 {band_of(after)}")
    check("播完后顶栏「实况」胶囊回来（用户想再看一次有入口）",
          exact("实况") is not None, f"{'有' if exact('实况') else '没有'}")

    # ---- 解码链路证据 ----
    check("解码器被创建过（logcat）", R108.decoder_created(), "NuPlayerDriver / c2.*.h264.decoder")
    check("第一帧确实渲染到了 surface 上（logcat MEDIA_INFO_VIDEO_RENDERING_START）",
          R108.video_render_started(), "info/warning (3, 0)")

    W.log(f"============ {label} 结束 ============")


def main():
    phase = (sys.argv[1] if len(sys.argv) > 1 else "B").upper()
    assert phase in ("A", "B"), "用法：walkthrough-v109-motion.py [A|B]"
    os.makedirs(OUT, exist_ok=True)
    run(phase)

    print()
    if FAILURES:
        W.log(f"共 {len(FAILURES)} 条未通过：")
        for f in FAILURES:
            print(f"    · {f}")
        sys.exit(1)
    W.log(f"第 {phase} 轮全部通过")


if __name__ == "__main__":
    main()
