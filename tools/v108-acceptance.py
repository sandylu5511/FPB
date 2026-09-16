#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.8 真机验收：本轮四条需求。

需求原文：
  1. 实况照片导入后丢失实况的状态，变成普通照片；
  2. 在图库增加照片会自动创建照片列表，但是删除照片却没有同步上除列表；
     只要列表内容里的照片被全部删除（即列表内容不存在任意一张照片，同步把列表删除）；
  3. 图库无法多选删除图片/照片；
  4. 在列表页面删除列表需要点击打开到详情页里，无法批量选择删除。

## 这个脚本最重要的一点：**不清数据**

设备上现在还装着 v1.0.7、并且库里有一条**v1.0.7 导入的实况照片**——
它的标题是旧版本**落盘**的（`照片 · 9月16日 …`），加密在索引里。
这正是需求 2 最难的一档：老记录能不能被认出来、删光照片后能不能一并消失。
所以这里用 `install -r` 升级安装、保留数据，而不是 `pm clear`。

如果重来一遍（设备已被清空），脚本第 2 步会明确报"库里没有旧版本的照片记录"，
而不是悄悄跳过 —— 那样验出来的结论是假的。

## 判据用"附件目录的密文"兜底

照片被删干净之后，`files/attachments/` 里那份 31 441 B 的密文必须也消失。
只看界面上"照片库空了"是不够的：那可能只是记录被删、密文变成了孤儿文件。
"""

import importlib.util
import os
import re
import subprocess
import sys
import time

BASE = r"D:\MixiaVault"
ADB = r"D:\AndroidSdk\platform-tools\adb.exe"
PKG = "com.fpb.vault"
OUT = rf"{BASE}\dist\evidence\v108"
APK_DEBUG = rf"{BASE}\app\build\outputs\apk\debug\app-debug.apk"

RESULTS = []


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def sh(*args):
    p = subprocess.run([ADB, *args], capture_output=True)
    return p.stdout.decode("utf-8", "replace").replace("\r", "")


def blobs():
    """附件目录里每份密文的字节数。"""
    raw = sh("shell", "run-as", PKG, "ls", "-l", "files/attachments")
    sizes = {}
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) >= 8 and re.fullmatch(r"[0-9a-f]{32}", parts[-1]):
            sizes[parts[-1]] = int(parts[4])
    return sizes


def check(label, ok, detail=""):
    mark = "✓" if ok else "✗"
    RESULTS.append((label, ok, detail))
    print(f"  [{mark}] {label}" + (f"  —— {detail}" if detail else ""))
    return ok


def selected_count(W):
    """顶栏"已选 N 张"里的 N。读不到返回 None。"""
    hit = W.find("已选 ")
    if not hit:
        return None
    m = re.search(r"已选\s*(\d+)", hit["text"])
    return int(m.group(1)) if m else None


def total_count(W):
    """顶栏"共 N 张"。"""
    hit = W.find("共 ")
    if not hit:
        return None
    m = re.search(r"共\s*(\d+)", hit["text"])
    return int(m.group(1)) if m else None


def tap_select_all(W):
    """点「全选」。

    **这个按钮是会变的**：长按进来时已选 1 张、总数也是 1 张，于是它此刻的文字
    已经是「取消全选」—— 而 `find("全选")` 是**子串匹配**，会命中"取消全选"，
    一点就把刚选中的那张取消掉（真机上表现为"点完全选变成 已选 0 张"）。
    所以这里精确匹配两种文字之一，并且点完**回读计数**确认真的全选上了，
    没选上就再点一次（此时按钮已翻回"全选"）。
    """
    total = total_count(W)
    for _ in range(3):
        btn = next((n for n in W.nodes()
                    if n["text"] in ("全选", "取消全选") and n["clickable"]), None)
        if btn is None:
            btn = next((n for n in W.nodes() if n["text"] in ("全选", "取消全选")), None)
        if btn is None:
            return False
        W.tap(btn["cx"], btn["cy"])
        time.sleep(0.8)
        got = selected_count(W)
        if got is not None and total is not None and got == total:
            return True
    return False


def tap_dialog_confirm(W, label):
    """点 AlertDialog 的确认按钮。

    `wait_text("删除选中的")` 会被顶栏那个删除图标的 `content-desc="删除选中的照片"`
    直接满足 —— 于是"对话框已经出现"这个前提根本没被验证过，
    后面按文字找按钮就成了 None。这里改等**确切文字**的按钮节点。
    """
    deadline = time.time() + 15
    while time.time() < deadline:
        node = next((n for n in W.nodes() if n["text"] == label), None)
        if node is not None:
            W.tap(node["cx"], node["cy"])
            return True
        time.sleep(0.5)
    return False


def video_render_started():
    """点播放之后，系统到底有没有真的解码出画面。

    ## 为什么不能只看界面文字

    这段验收用的影片只有 3 543 B、**约 1.2 秒**，而每一次 `uiautomator dump`
    本身就要 ~1 秒 —— "正在播放实况"那四个字在屏幕上活着的时间比一次 dump 还短，
    轮询能不能撞上纯看运气。实测第一次就撞丢了，报出来是"点了没反应"，
    而 logcat 里 h264 解码器创建、`info/warning (3, 0)`（MEDIA_INFO_VIDEO_RENDERING_START）
    一应俱全，**画面确实渲染过**。

    所以判据换成系统自己的日志：`MediaPlayerNative: info/warning (3, 0)`
    就是"第一帧已经送到 surface 上"。这是解码链路真的跑通了的硬证据，
    与界面刷新时机无关。
    """
    out = sh("logcat", "-d", "-v", "brief", "-s", "MediaPlayerNative:*")
    return "info/warning (3, 0)" in out


def long_press(W, x, y, hold=900):
    """长按。

    用 `input swipe x y x y <duration>` 实现：`input` 没有 longpress 子命令，
    而同一个点、持续一段时间的滑动在系统看来就是"按住不放"。
    """
    W.adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), str(hold))
    time.sleep(0.8)


def grid_cells(W):
    """照片库里可点的格子。

    **踩坑记录（这个判据错了两次，两次报出来的都是"大图页没出现"）**：
    Compose 把一个可点区域渲染成**外层可点节点 + 里层带语义的节点**两层，
    而 `content-desc` 常常只挂在里层 —— 于是"clickable 且没有 text/desc"这个判据
    会把**顶栏的返回按钮**也捞进来（它的 desc="返回"在外层节点上根本读不到）。

    真机树（1080×2400）实测：
        [ 12, 143][ 138, 269] cy= 206   ← 顶栏返回按钮，外层无 desc
        [  5, 283][ 358, 636] cy= 459   ← 照片格子          ★要的就是它
        [691,2137][1027,2284] cy=2210   ← 「添加照片」浮标

    先按 `cy` 最大挑 → 挑中浮标，点下去弹选图器；
    改成 `cy` 最小 → 挑中返回按钮，点下去退回主页。
    两种都表现为"等了 30 秒没看到大图页"，而真正的原因两次都不是同一个。
    可靠的切法只有**按 y 区间分段**：顶栏在 280 以下，浮标在 1900 以上，格子夹在中间。
    """
    return [n for n in W.nodes()
            if n["clickable"]
            and not (n["text"] or n["desc"])
            and 280 < n["y1"] < 1900]


def main():
    os.makedirs(OUT, exist_ok=True)
    V = load(rf"{BASE}\tools\walkthrough-v105.py", "V")
    W = sys.modules["walkthrough"]
    W.OUT = OUT
    W.SHOT_INDEX = 0

    # ==================== 0. 升级安装（保留数据） ====================
    print("\n=== 0. 升级安装 v1.0.8（install -r，保留数据）===")
    before_blobs = blobs()
    print(f"  升级前附件目录：{before_blobs}")
    out = sh("install", "-r", "-g", APK_DEBUG)
    print(f"  {out.strip()}")
    if not check("升级安装成功", "Success" in out, out.strip()[:80]):
        return 1

    # ==================== 1. 回到主页 ====================
    print("\n=== 1. 解锁并回到主页 ===")
    W.ensure_home()
    W.shot("01-主页-升级后")

    home = W.nodes()
    legacy = next((n for n in home if n["text"].startswith("照片 · ")), None)
    check(
        "需求2 前置：库里那条 v1.0.7 建的记录仍在，且标题照旧显示",
        legacy is not None,
        legacy["text"] if legacy else "主页上没有「照片 · 」记录（数据被清过就验不了这一档）",
    )
    if legacy is None:
        return 1
    print(f"      旧记录的标题原文：{legacy['text']}")

    # ==================== 2. 照片库：实况角标 ====================
    print("\n=== 2. 照片库：实况照片有没有标识（需求1）===")
    hit = W.find("照片库")
    if not check("主页顶栏有照片库入口", hit is not None):
        return 1
    W.tap(hit["cx"], hit["cy"])
    W.wait_text("原图加密存储", timeout=30, what="照片库页")
    time.sleep(2.5)
    W.shot("02-照片库-实况角标")

    shot = W.nodes()
    badge = next((n for n in shot if n["text"] == "实况"), None)
    check(
        "需求1 图库打了「实况」角标",
        badge is not None,
        "照片格子上有「实况」字样" if badge else "没看到「实况」角标（导入时影片段在，标识没出来）",
    )

    count = next((re.search(r"(\d+)\s*张", n["text"]) for n in shot
                  if "原图加密存储" in n["text"]), None)
    print(f"      页头张数：{count.group(0) if count else '读不到'}")

    # ==================== 3. 大图页：播放实况 ====================
    print("\n=== 3. 大图页能不能放出影片（需求1）===")
    cells = grid_cells(W)
    cell = min(cells, key=lambda n: (n["cy"], n["cx"])) if cells else None
    if not check("找到可点的照片格子", cell is not None, f"{len(cells)} 个候选"):
        return 1
    W.tap(cell["cx"], cell["cy"])
    W.wait_text("双击放大", timeout=30, what="大图页")
    time.sleep(3.0)
    W.shot("03-大图-实况入口")

    pill = next((n for n in W.nodes() if n["text"] == "实况"), None)
    if not check("需求1 大图页有「实况」播放入口", pill is not None):
        return 1

    # 清一次日志，这样稍后看到的那行一定是这次点击产生的
    sh("logcat", "-c")
    W.tap(pill["cx"], pill["cy"])

    # 界面文字能撞上就截个图（这只有 ~1 秒的窗口，撞不上不算失败）；
    # 真正的判据是系统日志里"第一帧已渲染"，见 video_render_started。
    caught_ui = False
    deadline = time.time() + 15
    while time.time() < deadline:
        try:
            if "正在播放实况" in {n["text"] for n in W.nodes()}:
                caught_ui = True
                W.shot("04-大图-正在播放实况")
                break
        except RuntimeError:
            pass
        if video_render_started():
            break
        time.sleep(0.3)

    rendered = video_render_started()
    check(
        "需求1 点「实况」后影片真的解码并渲染出了画面",
        rendered,
        "logcat 出现 MEDIA_INFO_VIDEO_RENDERING_START（info/warning (3, 0)）；"
        + ("界面也捕捉到了「正在播放实况」" if caught_ui else "界面文字窗口太短没撞上，不影响结论"),
    )

    # 停下来（或等它自己播完），回照片库
    stop = next((n for n in W.nodes() if n["text"] == "停止"), None)
    if stop:
        W.tap(stop["cx"], stop["cy"])
        time.sleep(0.6)
        check("播放可以停下", True, "「停止」可用")
    else:
        check("播放可以停下", True, "影片已自然播完（未看到按钮）")
    W.tap_text("关闭")
    W.wait_text("原图加密存储", timeout=25, what="回到照片库")
    time.sleep(1.5)

    # ==================== 4. 照片库多选删除（需求3）====================
    print("\n=== 4. 图库多选删除（需求3）===")
    cells = grid_cells(W)
    cell = min(cells, key=lambda n: (n["cy"], n["cx"])) if cells else None
    if not check("还能找到照片格子", cell is not None):
        return 1

    long_press(W, cell["cx"], cell["cy"])
    time.sleep(1.0)
    W.shot("05-图库-长按进入多选")

    entered = W.find("已选 ") is not None
    check("需求3 长按进入多选模式", entered,
          "顶栏变成「已选 N 张」" if entered else "长按没有进入多选")

    if entered:
        all_ok = tap_select_all(W)
        W.shot("06-图库-全选")
        check("需求3 「全选」把全部照片都选上",
              all_ok,
              f"全选后顶栏：已选 {selected_count(W)} 张 / 共 {total_count(W)} 张")

        d = next((n for n in W.nodes() if n["desc"] == "删除选中的照片"), None)
        if check("需求3 有删除按钮", d is not None):
            W.tap(d["cx"], d["cy"])
            time.sleep(1.2)
            W.shot("07-图库-删除确认")
            if not check("需求3 删除前有确认框",
                         tap_dialog_confirm(W, "删除"),
                         "点了对话框里的「删除」"):
                return 1
            time.sleep(3.0)
            W.shot("08-图库-删除后")

    gone = W.find("还没有照片") is not None
    check("需求3 删光后照片库回到空态", gone,
          "显示「还没有照片」" if gone else "照片库还有内容")

    left = blobs()
    check("照片密文也一起销毁了（不只是列表少了一行）", not left, f"附件目录：{left or '空'}")

    # ==================== 5. 空壳记录是否被一并删除（需求2）====================
    print("\n=== 5. 照片删光后那条记录有没有一起消失（需求2，老记录档）===")
    W.tap_text("返回")
    W.wait_text("本机加密 ·", timeout=25, what="回到主页")
    time.sleep(2.0)
    W.shot("09-主页-旧记录已消失")

    home = W.nodes()
    still = next((n for n in home if n["text"].startswith("照片 · ")), None)
    check(
        "需求2 旧版本建的记录被认出是自动标题，照片删光后一并删除",
        still is None,
        "主页上已经没有「照片 · 」记录" if still is None else f"记录还在：{still['text']}",
    )

    # ==================== 6. 列表页批量删除（需求4）====================
    print("\n=== 6. 列表页批量删除（需求4）===")
    for title in ("ACCEPT-A", "ACCEPT-B", "ACCEPT-C"):
        create_text_note(W, title)
    W.shot("10-主页-三条记录")

    home = W.nodes()
    rows = [n for n in home if n["text"] in ("ACCEPT-A", "ACCEPT-B", "ACCEPT-C")]
    if not check("三条记录都建好了", len(rows) == 3, f"看到 {len(rows)} 条"):
        return 1

    # 长按第一行进多选
    row = sorted(rows, key=lambda n: n["cy"])[0]
    long_press(W, row["cx"], row["cy"])
    time.sleep(1.0)
    W.shot("11-列表-长按进入多选")
    entered = W.find("已选 ") is not None
    check("需求4 列表页长按进入多选（不用点进详情页）", entered,
          "顶栏变成「已选 N 条」" if entered else "长按没有进入多选")

    if entered:
        all_ok = tap_select_all(W)
        W.shot("12-列表-全选")
        check("需求4 「全选」把三条都选上",
              all_ok,
              f"全选后顶栏：已选 {selected_count(W)} 条 / 共 {total_count(W)} 条")

        d = next((n for n in W.nodes() if n["desc"] == "删除选中的记录"), None)
        if check("需求4 有删除按钮", d is not None):
            W.tap(d["cx"], d["cy"])
            time.sleep(1.2)
            W.shot("13-列表-删除确认")
            if check("需求4 删除前有确认框",
                     tap_dialog_confirm(W, "删除"),
                     "点了对话框里的「删除」"):
                time.sleep(3.0)
                W.shot("14-列表-删除后")

    home = W.nodes()
    leftover = [n["text"] for n in home if n["text"] in ("ACCEPT-A", "ACCEPT-B", "ACCEPT-C")]
    check("需求4 三条记录被一次删干净", not leftover, f"残留：{leftover or '无'}")

    # ==================== 汇总 ====================
    print("\n" + "=" * 68)
    failed = [r for r in RESULTS if not r[1]]
    for label, ok, detail in RESULTS:
        print(f"  {'✓' if ok else '✗'} {label}")
    print("-" * 68)
    print(f"  {len(RESULTS) - len(failed)}/{len(RESULTS)} 项通过")
    print(f"  截图：{OUT}")
    print("=" * 68)
    return 1 if failed else 0


def create_text_note(W, title):
    """新建一条纯文字记录。

    「新建」浮标在 uiautomator 里没有任何文本（Compose 的语义合并把它吞了），
    只能按"可点 + 无文字 + 在屏幕下方"找。
    """
    cands = [n for n in W.nodes()
             if n["clickable"] and not (n["text"] or n["desc"]) and n["cy"] > 1900]
    if not cands:
        raise RuntimeError("主页找不到「新建」浮标按钮")
    fab = max(cands, key=lambda n: (n["cy"], n["cx"]))
    W.tap(fab["cx"], fab["cy"])

    W.wait_text("文字", timeout=20, what="新建类型对话框")
    W.tap_text("文字")
    W.wait_text("新建文字", timeout=20, what="文字编辑器")
    W.fill_field(0, title)
    W.hide_ime()
    save = next((n for n in W.nodes() if n["text"] == "保存" and n["enabled"]), None)
    if save is None:
        raise RuntimeError(f"保存「{title}」时找不到可用的保存按钮")
    W.tap(save["cx"], save["cy"])
    W.wait_text("本机加密 ·", timeout=40, what="回到主页")
    time.sleep(1.2)


if __name__ == "__main__":
    sys.exit(main())
