#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图库视频功能走查：导入 → 网格 → 播放 → 删除。

    python tools/walkthrough-v110-video.py A    # 对照轮：出货版 v1.0.9（图库只认图片）
    python tools/walkthrough-v110-video.py B    # 验证轮：本次实现版（debug）

## 这一轮分三层取证，一层比一层硬

1. **交互层**：选图器里能不能选中视频、媒体库里有没有视频格、时长角标对不对。
2. **像素层**：点开后画面上真的有影片、几何没被拉伸、白块在动（真的在播），
   点暂停后白块**不动了**。
3. **存储层**：`run-as` 进沙箱直接看落盘的文件 —— 头部是 `FPBCHK`、大小符合分块公式、
   文件里找不到明文 MP4 特征串、删掉记录后密文文件真的消失。

第 3 层是这一轮最硬的一段。前两层都可能被"界面画对了但存错了"骗过去，存储层不会 ——
而"图库读不出视频"这件事，根子恰恰在存储层（整块 AES-GCM 没法随机访问，
`MediaPlayer` 拿任意 position 调 `readAt` 时无解）。

## 为什么必须跑对照轮

用户报的是"图库无法读取视频文件"。这句话要成立，得先让它在**同一台设备、同一份素材**上
重现出来：v1.0.9 的选图器只认图片，而设备相册里此刻只有两段影片 ——
于是选图器里一个可选项都不会有。B 轮那组"能读"的数字才有对照。

## 素材

`tools/_mkvideos.py` 合成两段**纯红底**影片：

  · `landscape.mp4` 320×180 60 秒（16:9）—— 验常规路径（长度是被第 5 段逼出来的，见下）
  · `portrait.mp4`  480×854  8 秒（9:16）—— 验"竖屏不被摆成横的"

红底是为了复用 v1.0.9 那一轮已经跑通的 `band_of()`：靠"这一行有多少比例是红的"
把**影片区域**从黑底里切出来。画面中央那个白正方形是**拉伸的直接读数**
（正确 1.000，铺满整屏 0.25，差 4 倍）；顶部那个移动白块是**播放/暂停的读数**
（位置在变 = 在播，位置不动 = 停住了）。

## 控制条为什么必须"先停下、再读数"（这一轮踩出来的）

三条实测约束叠在一起，**播放态下根本读不到控制条**：

  · 轻点画面是 `controls = !controls` —— 它是个 **toggle**，不是"显示"。
    控制条已经在的时候再点一下，是把它**收起来**；
  · 控制条在**播放中 3.5 秒**后自动隐藏（暂停时不隐藏）；
  · 一次 `uiautomator dump` 要 1~3 秒。

于是"先点一下画面把控制条叫出来，再从容地找按钮"这个写法必然赛跑失败：
日志里出现过"控制条一个节点都没有"（`times = []`、`slider = None`），
几秒后又拿到了 `desc=['暂停']` —— 能不能读到完全看运气。

所以第 5 段整体改成**先把影片停下**：暂停后控制条常驻，时间 / 总时长 /
进度条 / 拖动这些读数全部挪到那之后再取。

但「读到「暂停」就立刻点」**还不够** —— **dump 本身是几秒前的快照**。
一次 `uiautomator dump` 要 2~3 秒，而控制条在播放中只亮 3.5 秒，
于是落点还在不在完全看 dump 有多快。两次实测构成了对照：

  · dump 用 **2.2 秒**的那一轮 → 点中了；
  · dump 用 **3.3 秒**的那一轮 → **点空了**，而点空的那一下打在外层 Box 上
    （轻点画面是 toggle），反而把控制条重新打开 —— 看起来就是「点了暂停没反应」。

最终定下来的写法：**dump 只用来问一次按钮在哪**（布局固定，坐标不会变），
之后「点坐标 → 用截图判断停了没有」，`screencap` 只要 0.3~0.9 秒，
整个动作落在控制条亮着的窗口里。

横屏素材从 30 秒加到 60 秒，也是被这一段逼的：打开 → 连拍取"在播" →
暂停 → 读数 → 拖到 30% → 恢复播放 → **切后台再回来**（这一段必须在"仍在播"
的状态下做）→ 再连拍，全程四十多秒。片子短了就会撞上"刚好播完"，
而"播完"会让按钮状态那一组断言**变成自己满足自己**。

## 进度条的落点不能用"节点宽度 × 比例"算（这一轮踩出来的）

Slider 的节点 bounds 比它的**可拖轨迹**宽：轨迹两侧各留有内边距
（`padding(horizontal = 10.dp)` 加滑块半径），而且两者同心。

于是 `x = x1 + 0.30 × 节点宽度` 落到的**不是** 30%：实测读到 `0:14`，
14/60 = **23.3%**。**系统性地偏小**，而不是随机抖动 —— 这种偏差最危险的地方是
它报出来的失败长得跟"拖动功能坏了"一模一样，会把人往产品缺陷的方向带。

内边距是多少不去猜，**量出来**：落点 x 与读数分数是一次仿射映射
`f(x) = (x - a) / travel`。在节点宽度的 30% 与 60% 两处各拖一把，
两式相减即得 `travel = 0.30·w / (f2 - f1)`，代回即得轨迹零点 `a`；
之后按解出的 `a`、`travel` 拖到**真正的** 30%。

三次拖动全部从节点正中按下，这样**不必知道** Slider 是"按下即跳到触点"（绝对）
还是"从滑块当前位置起算"（相对）—— 固定按下点 s 时两者化简后是同一个仿射函数。
读数只有整秒（±0.5 秒量化），行程的相对误差约 6%，传到落点上约 1 秒，
远小于 ±3 秒的容差。

标定解不出来时（读数读不到、或两把拖没生效）会**单独响一条前提判据**，
并退回按节点宽度算 —— 那条路上的结论不可信，日志会写明原因，
不会把"量错了"混进产品结论里。

## 两轮各验什么

  A（v1.0.9）：① 主页入口叫「照片库」、页头是「N 张」；② 打开选图器，**一个可选项都没有**
              —— 库里只有两段影片，而它只认图片。这就是缺陷本身。
  B（本次）：  ① 入口叫「照片与视频」、页头是「N 项」；
              ② 选图器里两段影片都能勾；③ 网格出现两个带时长角标的视频格；
              ④ 落盘是分块格式、正文不含任何明文 MP4 特征；
              ⑤ 点开自动播、几何 1.000、白块在动；⑥ 点暂停白块停住；
              ⑦ 拖进度条能跳；⑧ 删掉记录密文一起消失。
"""

import importlib.util
import os
import re
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image

BASE = r"D:\MixiaVault"
ADB = r"D:\AndroidSdk\platform-tools\adb.exe"
PKG = "com.fpb.vault"
OUT = rf"{BASE}\dist\evidence\v110-video"
APK_OLD = rf"{BASE}\dist\FPB-v1.0.9.apk"                        # 对照：出货版（无视频）
APK_NEW = rf"{BASE}\app\build\outputs\apk\debug\app-debug.apk"    # 本次实现版

# 过程中的读数（`motion_probe` 的探针截图）落在这里，**不进证据目录**：
# 它们不是结论的证据，只是「这一下暂停按下去没有」这种瞬时判断。
# 而且**不做删除** —— 桌面端有批量删除保护（阈值 50），
# 删两张临时图会把整轮走查打断；固定文件名反复覆盖，文件数恒定。
PROBE_DIR = os.path.join(BASE, "build", "_probe")

VIEW_W, VIEW_H = 1080, 2400

# 两段素材的"预期"。band 高度 = 1080 宽铺进去之后应得的高度。
CLIPS = {
    "landscape.mp4": {
        "duration_ms": 60_000,
        "label": "1:00",
        "src": (320, 180),
        "band_h": VIEW_W * 180 / 320,      # 607.5
        "tag": "横屏 16:9",
    },
    "portrait.mp4": {
        "duration_ms": 8_000,
        "label": "0:08",
        "src": (480, 854),
        "band_h": VIEW_W * 854 / 480,      # 1921.5
        "tag": "竖屏 9:16",
    },
}

# 素材的明文字节数**从本地源文件读**，不写死：写死的那个数字一旦素材重做就会全部失配，
# 而失败信息看起来像"加密写错了"，排查方向直接跑偏。
for _name, _spec in CLIPS.items():
    _p = os.path.join(BASE, "build", "videos", _name)
    _spec["plain_bytes"] = os.path.getsize(_p) if os.path.exists(_p) else -1


# 系统手势条（白色胶囊）。影片是竖屏时条带一直铺到屏幕底部附近，必须排掉，
# 否则白像素包围盒会量成它 —— v1.0.9 那一轮就是这么量出 0.182 的假数字。
# 沿用 walkthrough-v106-part2.py 里量出来的那组数字（同型号 AVD、同分辨率）。
GESTURE_Y, GESTURE_X0, GESTURE_X1 = 2350, 350, 730

BURST_PLAY = 8      # 打开后连拍张数：够覆盖"在播"这个结论
BURST_PAUSE = 4     # 暂停后连拍张数：只需证明"不再变"

# 分块格式的头部形状，与 app 侧 ChunkedBlobFormat 对齐。这里**独立实现一遍**，
# 不 import 被测代码 —— 用被测代码去验被测代码的产物，等于没验。
CHUNK_MAGIC = b"FPBCHK\x01\x00"
CHUNK_HEADER_BYTES = 24
# 默认块大小（app 侧 `ChunkedBlobFormat.DEFAULT_CHUNK_BYTES = 1 shl 20`）。
# 提成常量是因为它同时出现在"断言头部字段"与"按体积认领素材"两处 ——
# 各写一个 `1 << 20` 的话，改一处忘一处就是一条说不清的失败。
CHUNK_SIZE = 1 << 20
# 明文 MP4 里必然出现的四个 box 名。密文里出现任何一个都说明"没真的加密"。
PLAINTEXT_MARKERS = (b"ftyp", b"moov", b"mdat", b"avc1")

FAILURES = []


# ==================== 基础设施 ====================

def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V105 = load(rf"{BASE}\tools\walkthrough-v105.py", "w105")
R108 = load(rf"{BASE}\tools\v108-release-acceptance.py", "r108")
W = V105.W

PHASE_TAG = "?"

_real_shot = W.shot


def phase_shot(name):
    """把引导流程里的截图名换成这一轮该有的样子。

    `V105.onboarding()` 是"深色模式"那次验收写的，截图名统一带「深色-」前缀。
    直接拿来跑，证据目录里会躺一堆叫「深色-引导-欢迎」的图 —— 与本次结论无关，
    还会误导后来翻证据的人。
    """
    return _real_shot(f"{PHASE_TAG}-{name.replace('深色-', '')}")


# **这两行必须在 onboarding 之前执行**，而且它们漏掉的后果不是"名字不好看"：
#   · 不换 shot  → 引导那几张截图会顶着「深色-」前缀落到**别的证据目录**里，
#     还会把上一轮的同名文件覆盖掉（SHOT_INDEX 从 0 重数）。
#   · 不换 OUT   → 本轮所有人工截图（选图器、媒体库、暂停、删除）全落到
#     walkthrough.py 的默认目录 dist/evidence/m3 去，本轮的证据目录里只剩连拍帧。
# 2026-09-17 第一次跑就是这么丢的：写入这个文件时这一行没落盘，
# 日志里立刻出现「01-深色-引导-欢迎.png」，而 `ls dist/evidence/v110-video` 是空的。
W.shot = phase_shot
W.OUT = OUT
W.SHOT_INDEX = 0


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
    """只记录、不判定。对照轮的观测用这个，避免"对照组没重现"把整轮判红。"""
    print(f"    [记录] {label}：{detail}")


def rnodes(retries=8):
    """dump 当前界面，返回**带完整 bounds** 的节点。

    `walkthrough.W.nodes()` 只给 cx/cy/y1，而这一轮要用到：
    进度条要按 x1..x2 拖、格子要按四边定位。所以这里自己解析一份。

    通道沿用 walkthrough.py 里踩出来的两条经验（不要改成 `dump /sdcard/ui.xml`）：
    · `exec-out uiautomator dump /dev/tty` —— 绕过"打印了 dumped to 却没写文件"那个坑；
    · stdout 与 stderr **都要收** —— 失败信息走 stderr，只读 stdout 会得到一句
      "no xml in output"，事后分不清是启动窗口期还是设备掉线。
    """
    last = None
    for _ in range(retries):
        done = subprocess.run([ADB, "exec-out", "uiautomator", "dump", "/dev/tty"],
                              capture_output=True, timeout=60)
        text = (done.stdout + done.stderr).decode("utf-8", errors="replace")
        start, end = text.find("<?xml"), text.rfind(">")
        if start >= 0 and end > start:
            try:
                root = ET.fromstring(text[start:end + 1])
            except ET.ParseError as e:
                last = f"XML 解析失败：{e}"
            else:
                out = []
                for n in root.iter():
                    if not n.tag.endswith("node"):
                        continue
                    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
                    if not m:
                        continue
                    x1, y1, x2, y2 = (int(v) for v in m.groups())
                    if x2 - x1 <= 1 or y2 - y1 <= 1:
                        continue
                    out.append({
                        "text": n.get("text") or "",
                        "desc": n.get("content-desc") or "",
                        "cls": (n.get("class") or "").split(".")[-1],
                        "clickable": n.get("clickable") == "true",
                        "x1": x1, "y1": y1, "x2": x2, "y2": y2,
                        "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
                        "w": x2 - x1, "h": y2 - y1,
                    })
                out.sort(key=lambda n: (n["y1"], n["cx"]))
                return out
        else:
            last = (text.strip() or "stdout/stderr 均为空").splitlines()[0][:120]
        time.sleep(0.9)
    raise RuntimeError(f"dump 解析失败：{last}")


def rfind(sub, ns=None):
    """按文本或 content-desc 精确/子串查找（与 W.find 同义，但基于 rnodes）。"""
    ns = ns if ns is not None else rnodes()
    for n in ns:
        if sub in n["text"] or sub in n["desc"]:
            return n
    return None


def installed_version():
    out = sh("shell", "dumpsys", "package", PKG)
    m = re.search(r"versionName=(\S+)", out)
    n = re.search(r"versionCode=(\d+)", out)
    return f"{m.group(1) if m else '?'} (code {n.group(1) if n else '?'})"


# ==================== 像素度量（numpy） ====================

def grab(name):
    """抓一帧存盘，返回 (路径, RGB ndarray)。"""
    os.makedirs(OUT, exist_ok=True)
    raw = subprocess.run([ADB, "exec-out", "screencap", "-p"],
                         capture_output=True, timeout=60).stdout
    path = os.path.join(OUT, f"{name}.png")
    with open(path, "wb") as f:
        f.write(raw)
    return path, np.asarray(Image.open(path).convert("RGB"), dtype=np.uint8)


def burst(prefix, count, out_dir=None):
    """连拍一串。**不 sleep**：一 sleep 就把采样点推出播放窗口了。

    返回 [(路径, ndarray)]，读不出来的帧返回 None 并记一行日志。

    [out_dir] 默认是证据目录。探针（[motion_probe]）会把它指到暂存目录去 ——
    那些帧是过程读数，不该混进证据里。
    """
    out = out_dir or OUT
    os.makedirs(out, exist_ok=True)
    rows = []
    for i in range(count):
        raw = subprocess.run([ADB, "exec-out", "screencap", "-p"],
                             capture_output=True, timeout=60).stdout
        path = os.path.join(out, f"{prefix}-{i:02d}.png")
        with open(path, "wb") as f:
            f.write(raw)
        try:
            rows.append((path, np.asarray(Image.open(path).convert("RGB"), dtype=np.uint8)))
        except Exception as e:
            W.log(f"    第 {i} 帧读不出来（{e}）")
            rows.append((path, None))
    return rows


# 「在播」与「停住」的门槛，单位 px/s。探针影片里白块的横移真值约 16.7 px/s，
# 两个门槛各取真值的一半与五分之一，中间留出很宽的判定带。
MOVING_SPEED = 8.0
STILL_SPEED = 3.0


def centroids(rows):
    """从连拍结果里取出「白块横向质心」序列 → [(文件名, cx)]。

    连拍帧里量不到影片画面的那些直接跳过（可能是黑屏过渡帧或系统弹窗），
    所以返回的条数**可能少于**连拍张数 —— 判据里要按实际条数说话。
    """
    xs = []
    for p, a in rows:
        if a is None:
            continue
        b = band_of(a)
        if not b:
            continue
        blk = block_x(a, b)
        if blk:
            xs.append((os.path.basename(p), round(blk["cx"], 1)))
    return xs


def motion_stats(xs, out_dir=None):
    """从质心序列算出位移与**速度**；不足两帧、或时间戳不可用返回 None。

    ## 为什么判据是速度，不是固定像素数

    连拍两帧之间的间隔取决于 `screencap` 的耗时（实测 0.3~0.9 秒，随模拟器负载浮动），
    所以"5 帧位移 20px"与"8 帧位移 91px"说的是同一件事 —— 都在播。
    按固定像素阈值判，前者会被误判成"没动"：B 轮就出过这条假 FAIL，
    返回后重播那组刚好 20.0px，恰好卡在 `> 20` 的门槛上。

    而速度与模拟器快慢无关：探针影片里白块的横移速度是
    (画面宽 - 块宽) / 时长 ≈ 16.7 px/s，两条门檻取它的一半与五分之一。
    """
    if len(xs) < 2:
        return None
    base = out_dir or OUT
    try:
        t0 = os.path.getmtime(os.path.join(base, xs[0][0]))
        t1 = os.path.getmtime(os.path.join(base, xs[-1][0]))
    except OSError:
        return None
    dt = t1 - t0
    if dt <= 0:
        return None
    span = max(x[1] for x in xs) - min(x[1] for x in xs)
    return {"n": len(xs), "span": span, "dt": dt, "speed": span / dt}


def motion_text(st):
    if st is None:
        return "量不到足够的两帧（或截图时间戳不可用）"
    return (f"{st['n']} 个读数，位移 {st['span']:.1f}px / {st['dt']:.2f}s "
            f"→ {st['speed']:.1f} px/s（在播门槛 {MOVING_SPEED:.0f}、"
            f"停住门槛 {STILL_SPEED:.0f}）")


def moving(st):
    return st is not None and st["speed"] >= MOVING_SPEED


def still(st):
    return st is not None and st["speed"] <= STILL_SPEED


def motion_probe(frames=2):
    """快测一下**此刻**画面在不在动：连拍 [frames] 张，返回 [motion_stats] 的结果。

    与落进证据目录的连拍不同，这几张是**过程中的读数**：写到 `build/_probe/`，
    固定文件名反复覆盖，**不删除**（见 [PROBE_DIR] 的注释）——
    所以它只花 0.3~0.9 秒/张，能塞进控制条那 3.5 秒的窗口里。
    这正是"dump 读到「暂停」再点它"做不到的事：那条路要 dump，而 dump 要 2~3 秒。

    量不到（帧读不出来、或画面里没有影片）返回 None。调用方必须把 None 当成
    "测不出来"，而不是"没在动" —— 这两件事的后果完全不同。
    """
    rows = burst("motion", frames, out_dir=PROBE_DIR)
    return motion_stats(centroids(rows), out_dir=PROBE_DIR)


def grid_filled(a):
    """选图器的网格里是否**已经有缩略图**。

    ## 为什么不能按"高饱和像素的个数"判 —— 第一版就是这么写的，真被咬了

    picker 是自下而上滑出来的弹层。它滑到位、网格还没加载的那一瞬间，
    屏幕上只有弹层顶栏那两个 chip，其中「Photos」是**蓝底白字** ——
    面积约 330×66 ≈ 22000 像素，刚好把一个按面积设的 20000 阈值顶满。
    于是走查在 picker 还是一片空白时就宣布"加载完了"：那一帧没有红块、
    a11y 里也没有格子，断言当场失败，而**下一帧截图里两段红底影片明明已经在了**
    （同一轮的 08 号截图至今还能看到它们）。

    ## 现在的判据：这一行有多杂

    空白 picker 是一整片浅灰，圆角、分割线、纯色 chip 的**颜色标准差都接近 0**；
    只要有缩略图进来，一行就会横跨好几个不同颜色的格子，标准差立刻上到几十。
    纯色块的面积再大也贡献不了标准差 —— 这是它比"数像素个数"稳的根本原因。

    y 取 500..2000 是刻意**避开顶栏 chip**（实测在 y≈330..405），
    同时避开底部按钮区。实测读数：媒体库空态 221 行、picker 已加载 865 行，
    阈值取 600 在两边的余量都很宽。
    """
    sub = a[500:2000, :, :].astype(np.float32)
    row_std = sub.reshape(sub.shape[0], -1).std(axis=1)
    return int((row_std > 25).sum()) > 600


def red_mask(a):
    return (a[..., 0] > 140) & (a[..., 1] < 90) & (a[..., 2] < 90)


def white_mask(a):
    return (a[..., 0] > 200) & (a[..., 1] > 200) & (a[..., 2] > 200)


def band_of(a):
    """影片区域（红底）的包围盒；整幅都不是红的返回 None。

    判据是"这一行有 45% 以上的像素是红的"。走白块的那几行占比掉到 92%，仍远高于阈值。
    """
    m = red_mask(a)
    rows = np.where(m.sum(axis=1) >= 0.45 * a.shape[1])[0]
    if rows.size == 0:
        return None
    y0, y1 = int(rows[0]), int(rows[-1])
    cols = np.where(m[y0:y1 + 1].sum(axis=0) > 0)[0]
    x0, x1 = int(cols[0]), int(cols[-1])
    return {"y0": y0, "y1": y1, "h": y1 - y0 + 1,
            "x0": x0, "x1": x1, "w": x1 - x0 + 1}


def square_of(a, band):
    """条带里那个白色正方形（描边）的包围盒；量不到返回 None。

    两处**都必须排掉**，否则白像素包围盒会量成别的东西：

    1. **顶部那个横向移动的白块**：它占画面高 5%~14%，把条带最上面 25% 切掉就收不到它。
    2. **系统手势条**（底部白色胶囊，实测 y 2364..2373、x 398..681）：竖屏条带会一直
       延伸到屏幕底部，这个胶囊就落在条带里，会把包围盒整个撑歪。
       v1.0.9 那一轮第一跑就是这么量出 284×1564（比例 0.182）的，
       而正方形真身是 202×800（0.253）。
    """
    m = white_mask(a).copy()
    cut = band["y0"] + int(0.25 * band["h"])
    m[:cut, :] = False
    m[band["y1"] + 1:, :] = False
    m[:, :band["x0"]] = False
    m[:, band["x1"] + 1:] = False
    m[GESTURE_Y:, GESTURE_X0:GESTURE_X1 + 1] = False
    ys, xs = np.nonzero(m)
    if xs.size == 0:
        return None
    bw = int(xs.max() - xs.min() + 1)
    bh = int(ys.max() - ys.min() + 1)
    return {"x0": int(xs.min()), "x1": int(xs.max()),
            "y0": int(ys.min()), "y1": int(ys.max()),
            "w": bw, "h": bh, "ratio": bw / bh}


def block_x(a, band):
    """顶部白块的横向质心与宽度；找不到返回 None。

    白块是这一轮唯一的"运动读数"：它在影片里匀速从左走到右，所以连拍帧之间
    它的横坐标**必然**不同 —— 这是"真的在播"的客观证据（界面文案不算，
    "正在播放"那几个字在屏幕上活不过一次 dump）。
    反过来，暂停之后它必须**不动**。
    """
    m = white_mask(a).copy()
    m[:band["y0"], :] = False
    m[band["y0"] + int(0.25 * band["h"]):, :] = False
    m[:, :band["x0"]] = False
    m[:, band["x1"] + 1:] = False
    ys, xs = np.nonzero(m)
    if xs.size == 0:
        return None
    return {"cx": float(xs.mean()), "w": int(xs.max() - xs.min() + 1),
            "x0": int(xs.min()), "x1": int(xs.max())}


def describe(band, sq, blk):
    s = (f"条带 x[{band['x0']},{band['x1']}] y[{band['y0']},{band['y1']}] "
         f"= {band['w']}×{band['h']}")
    s += f"，正方形 {sq['w']}×{sq['h']} 比例 {sq['ratio']:.3f}" if sq else "，量不到正方形"
    s += f"，白块 x{blk['x0']}..{blk['x1']}（宽 {blk['w']}）" if blk else "，量不到白块"
    return s


# ==================== 存储层证据 ====================

def attachments():
    """沙箱里 `files/attachments/` 下的文件（名 → 字节数）。

    `ls -la` 在 toybox 下的列是：权限 链接数 属主 属组 **大小** 日期 时间 名字。
    取 parts[4] 与 parts[-1]；`-l` 与 `-la` 的行形状一致，多出来的 `.` / `..` 用
    "首字符是 `-`"过滤掉。
    """
    out = sh("shell", "run-as", PKG, "ls", "-la", "files/attachments")
    rows = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 8 or not parts[0].startswith("-"):
            continue
        try:
            rows[parts[-1]] = int(parts[4])
        except ValueError:
            continue
    return rows


def pull_blob(blob_id):
    """把一个 blob 的密文拉回本地。走 exec-out，不落设备上的中转文件。"""
    p = subprocess.run([ADB, "exec-out", "run-as", PKG, "cat", f"files/attachments/{blob_id}"],
                       capture_output=True, timeout=120)
    return p.stdout


def parse_chunk_header(data):
    """按**独立实现**的分块头部定义解析，返回 (chunkSize, chunkCount, plainSize) 或 None。"""
    if len(data) < CHUNK_HEADER_BYTES or data[:8] != CHUNK_MAGIC:
        return None
    # 字节序必须是**大端**：app 侧 ChunkedBlobFormat 用的是 DataOutputStream.writeInt/writeLong
    # （Java 的 Data*Stream 一律大端），parseHeader 里的 readInt 也是手写的大端。
    # 这里第一版按小端读了，于是 chunkSize 读出 4096（而非 1048576）、
    # chunkCount 读出 16777216（而非 1）、plainSize 读出一个天文数字 ——
    # 四条判据集体变红，而失败信息看起来像"加密写错了"。
    # 独立实现要验的是"同一份规格"，所以规格里没写清的字节序，两边都要有据可依。
    chunk_size, chunk_count = struct.unpack_from(">ii", data, 8)
    plain_size, = struct.unpack_from(">q", data, 16)
    return chunk_size, chunk_count, plain_size


def expected_stored(plain_size, chunk_size):
    """分块格式落盘后的字节数。

    ## 别按"每块都是满块"算 —— 这里第一版就是这么写错的

    每一块在文件里是 `[12 字节 nonce][该块密文][16 字节 tag]`，
    而**最后一块的明文是短的**（`plainSize % chunkSize`，整除时才是满块）。
    第一版写成 `24 + 块数 × (12 + chunkSize + 16)`，等于把末块也按满块算，
    于是对横屏那条会多算出 `chunkSize - 末块明文 = 45393` 字节，
    对恰好一块的竖屏更是离谱（算出 1048628，实际 109481）。

    实际落盘 = `24 + Σ(28 + 第 i 块明文)`，而各块明文之和**就是** `plainSize`：

        expected = 24 + 块数 × (nonce 12 + tag 16) + plainSize

    对设备上实测的两条：`24 + 28×1 + 109429 = 109481`、
    `24 + 28×5 + 5197487 = 5197651` —— 与应用落盘的字节数**完全一致**。

    ## 这里的教训值得写下来

    这个函数是"独立实现一遍规格"，目的就是不要用被测代码去验被测代码。
    但**独立实现错了，报出来的失败会长得跟产品缺陷一模一样**：
    它当时给出的信息是"文件总长不符"，看起来像加密写错了，
    而实际上应用是对的、脚本是错的。所以遇到这类失败，
    第一件事是拿应用侧的契约（`ChunkedBlobFormat.Header.expectedStoredBytes`）
    反推一遍，而不是先去怀疑加密。
    """
    count = (plain_size + chunk_size - 1) // chunk_size
    return CHUNK_HEADER_BYTES + count * (12 + 16) + plain_size


def specs_for_size(size_on_disk):
    """按**落盘体积**列出所有可能的素材；正常应当**恰好一个**。

    返回列表而不是"第一个匹配"：返回单个值时，两段素材只要落在同一个
    块数档位，就会静默地认领到错的那一段，而失败信息（plainSize 不符、
    文件总长不符）看起来像"加密写错了"。宁可在认领这一步就响亮地失败。

    不能按"体积小的当横屏、大的当竖屏"排序配对 —— 那只是旧素材的巧合
    （横屏 10 秒确实比竖屏 8 秒短）。横屏加到 60 秒之后大小关系反转，
    于是横屏那份密文被拿竖屏的期望值去验，报出来的是
    "plainSize 不符 + 文件总长不符"：看着像加密写错了，其实是配错了对象。

    两段素材的明文长度不同，各自的落盘体积也就不同，可以唯一认领。
    注意这只是**用来选对象**：认领之后头部里的 plainSize / chunkCount /
    总长仍然逐条对着本地源文件核，不是自证。
    """
    return [spec for spec in CLIPS.values()
            if spec["plain_bytes"] > 0
            and expected_stored(spec["plain_bytes"], CHUNK_SIZE) == size_on_disk]


def blob_chunks(plain_bytes):
    """这份明文会被切成几块 —— 用来判断两条素材是否落在同一个块数档位。"""
    return (plain_bytes + CHUNK_SIZE - 1) // CHUNK_SIZE


def verify_stored_video(blob_id, size_on_disk, spec):
    """对一个视频 blob 做全套存储层判据，返回它的块数（量不到时返回 None）。"""
    data = pull_blob(blob_id)
    if len(data) != size_on_disk:
        check(f"{spec['tag']}：拉回来的字节数与设备上一致", False,
              f"设备上 {size_on_disk}，拉回 {len(data)}")
        return
    header = parse_chunk_header(data)
    if header is None:
        check(f"{spec['tag']}：落盘格式是分块密文（头部 magic = FPBCHK\\x01\\x00）", False,
              f"前 8 字节是 {data[:8]!r}")
        return
    chunk_size, chunk_count, plain_size = header

    check(f"{spec['tag']}：头部魔数正确", data[:8] == CHUNK_MAGIC, f"{data[:8]!r}")
    check(f"{spec['tag']}：chunkSize 与默认块大小一致（{CHUNK_SIZE} = 1 MiB）",
          chunk_size == CHUNK_SIZE, f"{chunk_size}")
    check(f"{spec['tag']}：plainSize 等于源文件字节数（{spec['plain_bytes']}）",
          plain_size == spec["plain_bytes"],
          f"记录 {plain_size}，磁盘上源文件 {spec['plain_bytes']}")
    check(f"{spec['tag']}：chunkCount 与 plainSize 自洽",
          chunk_count == (plain_size + chunk_size - 1) // chunk_size,
          f"{chunk_count} 块")
    check(f"{spec['tag']}：文件总长符合 24 + 块数×28 + 明文长度（末块是短的）",
          len(data) == expected_stored(plain_size, chunk_size),
          f"实测 {len(data)}，公式应得 {expected_stored(plain_size, chunk_size)}")

    found = [m.decode() for m in PLAINTEXT_MARKERS if m in data]
    check(f"{spec['tag']}：密文里找不到任何明文 MP4 特征串",
          not found, f"命中 {found}" if found else f"搜过 {[m.decode() for m in PLAINTEXT_MARKERS]}")
    note(f"{spec['tag']}：明文/密文体积比",
         f"{plain_size} → {len(data)}（+{len(data) - plain_size} 字节 = "
         f"头 {CHUNK_HEADER_BYTES} + {chunk_count} 块 × (nonce 12 + tag 16)）")
    return chunk_count


# ==================== 界面动作 ====================

def open_media_library(entry_word):
    """从主页顶栏进入媒体库。返回入口节点的文案，方便两轮各自断言。"""
    hit = W.find("照片与视频") or W.find("照片库")
    if hit is None:
        # 顶栏那个按钮在 a11y 树里可能只剩 content-desc，再按 desc 找一遍
        hit = next((n for n in W.nodes()
                    if (n["desc"] or n["text"]) and "照片" in (n["desc"] + n["text"])), None)
    assert hit is not None, "主页顶栏找不到媒体库入口"
    W.log(f"主页 → 媒体库（入口文案「{hit['text'] or hit['desc']}」）")
    W.tap(hit["cx"], hit["cy"])
    W.wait_text(entry_word, timeout=30, what="媒体库页头")
    return hit["text"] or hit["desc"]


def media_count():
    """页头「… · N 项」/「… · N 张」里的 N。"""
    for n in W.nodes():
        m = re.search(r"·\s*(\d+)\s*(项|张)", n["text"])
        if m:
            return int(m.group(1))
    return None


def find_fab():
    """媒体库右下角那个「添加照片或视频」浮标。

    **不能按"clickable 且无 text/desc"找**：那样会把顶栏返回按钮一起捞进来
    （v1.0.9 那一轮踩过，报出来的结论是"大图页没出现"，方向被带偏一整轮）。
    这里优先按文案找，找不到才退回"cy 最大且位于下半屏"。
    """
    hit = W.find("添加照片或视频") or W.find("添加照片")
    if hit is not None:
        return hit
    cands = [n for n in W.nodes() if n["clickable"] and n["cy"] > 1900]
    return max(cands, key=lambda n: (n["cy"], n["cx"])) if cands else None



def await_picker(timeout=180):
    """点开系统选图器，等它的网格**真的填进缩略图**再收工。

    网格是懒加载的：刚打开会先给一屏空白，之后才把缩略图补上来。
    实测冷启动后第一次打开（或刚 push 过媒体、picker 数据库还没同步完时）
    **可以拖到一分钟以上**，所以这里的窗口给到 180 秒。

    **只等 a11y 节点是不够的** —— 空白页上照样有可点节点，
    于是"等到了格子"其实是拿到一张空相册。

    判定通过的那一帧会另存成「选图器-判定帧.png」：截图与判定必须对得上，
    否则事后回看证据时会分不清"当时真的没有"还是"判定提前收工了"。
    返回 (cells, blobs, im)。
    """
    fab = find_fab()
    assert fab is not None, "媒体库里找不到导入按钮"
    W.log(f"点导入按钮（文案「{fab['text'] or fab['desc'] or '（无语义）'}」）")
    W.tap(fab["cx"], fab["cy"])

    probe = os.path.join(OUT, "_picker_probe.png")
    keep = os.path.join(OUT, f"{PHASE_TAG}-选图器-判定帧.png")
    deadline = time.time() + timeout
    started = time.time()
    cells, blobs, im = [], [], None
    rounds = 0
    while time.time() < deadline:
        rounds += 1
        ns = rnodes()
        # 系统选图器可能的确认弹窗
        dismiss = next((n for n in ns
                        if n["text"] in ("Dismiss", "知道了", "允许") and n["clickable"]), None)
        if dismiss is not None:
            W.tap(dismiss["cx"], dismiss["cy"])
            time.sleep(1.2)
            continue
        cells = picker_cells(ns)
        _, im = grab("_picker_probe")
        blobs = picker_red_blobs(im)
        if grid_filled(im):
            W.log(f"    选图器网格已加载（等了 {time.time() - started:.0f}s / {rounds} 轮）："
                  f"可勾格子 {len(cells)} 个、红色缩略图 {len(blobs)} 个")
            if os.path.exists(probe):
                os.replace(probe, keep)
            return cells, blobs, im
        time.sleep(1.5)
    W.log(f"    （等了 {timeout} 秒仍然没有缩略图：这台设备的相册没同步上，"
          f"不是应用侧的问题）")
    if os.path.exists(probe):
        os.replace(probe, keep)
    return cells, blobs, im


def picker_cells(ns):
    """选图器里可勾的媒体格。

    系统 picker 的格子是无语义的可点节点，且**顶栏也有无语义的可点节点**
    （返回箭头），所以按 y 区间分段 —— 网格在屏幕中段。
    实测 picker 顶栏到 y≈250、底部按钮区从 y≈2150 起。
    """
    return [n for n in ns if n["clickable"] and not (n["text"] or n["desc"])
            and 300 < n["y1"] and n["y2"] < 2150]


def picker_red_blobs(a):
    """选图器截图里所有红色缩略图的质心。

    兜底路径：少数系统版本不给视频格任何文本（连时长都不写），只剩图像。
    两段素材都是纯红底，所以红像素的横向连通段就是一个个缩略图。
    """
    m = red_mask(a)
    cols = m.any(axis=0)
    runs, start = [], None
    for x, on in enumerate(cols):
        if on and start is None:
            start = x
        elif not on and start is not None:
            if x - start > 30:
                runs.append((start, x - 1))
            start = None
    if start is not None and len(cols) - start > 30:
        runs.append((start, len(cols) - 1))
    out = []
    for x0, x1 in runs:
        sub = m[:, x0:x1 + 1]
        ys = np.where(sub.any(axis=1))[0]
        if ys.size == 0:
            continue
        out.append({"cx": (x0 + x1) // 2, "cy": int((ys[0] + ys[-1]) // 2),
                    "w": x1 - x0 + 1, "h": int(ys[-1] - ys[0] + 1)})
    return out


def tap_picker_cells(cells, a):
    """勾选**那两段影片**，然后点确认。

    ## 为什么不能"把所有可勾格子都点了"

    设备相册里除这两段影片外还有若干老照片，而系统选图器的每个格子都是
    "可点、无文本、无 desc"—— 实测本轮 9 个。全点一遍会得到一条混着 7 张照片的
    记录，而这一轮要验的是视频，`2 项` 这种判据直接被污染。
    （第一次跑就是这么干的：页头变成「· 5 项」。）

    ## 判据为什么用缩略图颜色

    格子节点上没有任何可用来分辨"这是视频"的信息（`desc='Video taken on…'` 挂在一个
    **不可点的兄弟节点**上，点不到），所以只能看像素。两段素材都是纯红底，
    而相册里的照片没有一张是红的 —— 实测正好 2 个红块。

    这个判据**不能只看"有红块"，必须断言恰好 2 个**：数量一旦不对，就说明
    要么红块识别把别的图算进来了、要么相册里多了一张红图。那时应当**响亮地失败**，
    而不是悄悄勾错东西（v1.0.9 那一轮就吃过"按最暗的一格挑、挑中一个纯黑坏格"的亏）。
    """
    blobs = picker_red_blobs(a)
    W.log(f"    可勾格子 {len(cells)} 个（含相册里的老照片），其中红色缩略图 {len(blobs)} 个"
          + "".join(f" →({b['cx']},{b['cy']}) {b['w']}×{b['h']}" for b in blobs))
    assert len(blobs) == 2, (
        f"选图器里的红色缩略图应为 2 个（两段探针影片），实测 {len(blobs)} 个。"
        f"相册里是不是多了一张红图，或者有一张探针影片没被扫进 MediaStore？")

    for b in blobs:
        W.tap(b["cx"], b["cy"])
        time.sleep(0.9)

    done = (W.find("Done") or W.find("完成") or W.find("Add") or W.find("添加")
            or W.find("选择") or W.find("完成选择"))
    assert done is not None, "多选之后没有出现确认按钮"
    W.log(f"    点确认（「{done['text'] or done['desc']}」）")
    W.tap(done["cx"], done["cy"])


def reveal_controls(rounds=6):
    """轻点画面把控制条叫回来，返回读到「暂停」或「播放」时的节点树。

    ## 为什么要单独一个函数

    播放中控制条 3.5 秒后自己收起，而轻点画面是 `controls = !controls` ——
    它是个 **toggle**。这两条叠起来意味着"读控制条"必须按同一个姿势做：
    每轮只 dump 一次，读到按钮就立刻收工；都没读到才轻点一下。

    抄两遍迟早会不一致（`tap_pause` 一遍、切后台回来一遍），所以收在这里。

    落点取 `VIEW_H // 4`，**避开画面正中那个播放键**：暂停态下它就在正中，
    点在它身上会直接把影片又播起来（那就成了"想看一眼控制条反而开始播"）。

    多轮仍读不到就原样返回，不抛异常 —— 调用方自己决定怎么处理。
    读不到按钮**不一定**是缺陷：「正在播 + 控制条收起」本来就是这个样子。
    上一版正因为把"读不到"当成了"坏了"，在 B 轮最后一步把整轮打断。
    """
    ns = []
    for i in range(rounds):
        ns = rnodes()
        if any(n["desc"] in ("暂停", "播放") for n in ns):
            return ns
        W.log(f"    第 {i + 1} 轮没读到控制条，轻点画面把它叫回来")
        W.adb("shell", "input", "tap", str(VIEW_W // 2), str(VIEW_H // 4))
        time.sleep(0.6)
    W.log("    ⚠ 多轮都没能读到控制条")
    return ns


def tap_pause():
    """把影片点成**暂停**，返回 (暂停后的节点树, 是否演示出了「在播 → 停住」)。

    ## 为什么不"dump 读到「暂停」再点它"

    控制条在**播放中只亮 3.5 秒**，而一次 `uiautomator dump` 要 2~3 秒。
    于是"读到按钮 → 点下去"这条路上，落点还在不在完全看 dump 有多快 ——
    两次实测给了对照：dump 用 2.2 秒那一轮**点中了**，用 3.3 秒那一轮**点空了**，
    而点空的那一下打在外层 Box 上（轻点画面是 toggle），反而把控制条重新打开，
    看起来就像"点了暂停没反应"。而且**重试也救不了**：每次重试都得先 dump，
    落点又过期。这是结构性的竞态，不是运气问题。

    ## 现在的写法

    1. dump **只问一次按钮在哪**（布局固定，坐标不会变）；
    2. 之后"点坐标 → 用**截图**判断停了没有"，中间不再插入 dump。
       `screencap` 只要 0.3~0.9 秒，整个动作落在控制条亮着的窗口里。

    连点同一个坐标是安全的：第一次若落在已经收起的控制条上，那一下等于点了画面
    （toggle），会把控制条**打开**；0.4 秒后的第二下就必然落在按钮上。

    ## 返回的第二项由像素给出，不由节点给出

    先确认暂停前画面在动（这是"正在播放"的定义），再确认按下之后不动了 ——
    比读一个可能已经过期的节点可靠。影片若本来就没在播（已经播完、或已停住），
    这一项就是 False，调用方那条"前提不成立"的判据会响亮地失败，
    而不是被"按钮本来就是播放"顶成一个假通过。
    """
    before = motion_probe(2)
    was_moving = moving(before)
    W.log(f"    暂停前快测：{motion_text(before)}"
          f" → {'在播' if was_moving else '没量到运动'}")

    ns = reveal_controls()
    hit = next((n for n in ns if n["desc"] == "暂停"), None)
    if hit is None:
        if any(n["desc"] == "播放" for n in ns):
            W.log("    控制条上已经是「播放」—— 影片本来就没在播")
        else:
            W.log("    ⚠ 控制条始终读不到，连按钮位置都拿不到，无法执行暂停")
        return ns, False

    coords = (hit["cx"], hit["cy"])
    W.log(f"    「暂停」键在 {coords}；之后只点这个坐标，不再 dump")

    for attempt in range(1, 4):
        W.tap(*coords)
        time.sleep(0.4)
        after = motion_probe(2)
        W.log(f"    第 {attempt} 次点击后：{motion_text(after)}")
        if still(after):
            return rnodes(), was_moving
    W.log("    ⚠ 连点三次都没能让画面停住")
    return rnodes(), False


def current_page():
    """大图页顶栏那个「n / m」页码；读不到返回 None。

    用它来分辨两种长得很像的失败：进度条那条**横向**滑动如果被外层
    `HorizontalPager` 抢走，表现是"翻到下一张"，而不是"拖不动"。
    """
    return next((n["text"].strip() for n in rnodes()
                 if re.fullmatch(r"\d+ / \d+", n["text"].strip())), None)


def to_secs(t):
    """把控制条上的 `m:ss` 读成秒。"""
    m, sec = t.split(":")
    return int(m) * 60 + int(sec)


def drag_seek(slider, x, total_secs):
    """把进度条拖到屏幕横坐标 x，返回 (界面读到的当前秒数, 节点树)。

    ## 起点必须**固定**

    三次拖动全部从节点正中 `slider["cx"]` 按下。这样做是因为**不需要知道**
    Slider 到底是"按下即跳到触点"（绝对）还是"从滑块当前位置起算"（相对）：

    - 绝对：`f(x) = (x - a) / L`
    - 相对：`f(x) = f(s) + (x - s) / L`，其中 s 是按下点；而 `f(s) = (s - a) / L`，
      所以化简后**还是** `f(x) = (x - a) / L`

    两者在"固定按下点"这个前提下是同一个仿射函数。起点一飘，第二式就不成立了，
    标定解出来的行程会带上系统误差。

    ## 拖动之后不能直接 dump

    播放中控制条 3.5 秒后自己收起 —— 必须先用 `reveal_controls()` 把它叫回来，
    否则读到一个空列表（B 轮就是这么失败的）。暂停态下它常驻，这个调用会立刻返回。

    控制条上有两个 `m:ss`：当前时间与总时长。总时长恒等于素材时长，
    用它把自己排除掉，剩下的那个才是当前时间。读不到返回 None。
    """
    y = slider["cy"]
    W.adb("shell", "input", "swipe",
          str(slider["cx"]), str(y), str(x), str(y), "700")
    time.sleep(1.0)
    ns = reveal_controls()
    stamps = [n["text"].strip() for n in ns
              if re.fullmatch(r"\d+:\d\d", n["text"].strip())]
    cur = [to_secs(t) for t in stamps if to_secs(t) != int(total_secs)]
    return (min(cur) if cur else None), ns


def open_cell_by_label(label):
    """点开时长角标为 label 的那一格。

    角标在格子左下角、本身不可点，点它会穿透给格子的点击区域。
    用它定位而不是"第 N 格"，是因为网格顺序取决于落库顺序，
    而时长是从容器里读出来的**事实** —— 1:00 的那段就是横屏那一段。
    """
    hit = next((n for n in rnodes() if n["text"] == label), None)
    assert hit is not None, f"网格里找不到时长角标 {label}"
    W.log(f"点开角标 {label} 那一格（{hit['cx']},{hit['cy']}）")
    W.tap(hit["cx"], hit["cy"])


def wait_viewer(timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if any(re.fullmatch(r"\d+ / \d+", n["text"].strip()) for n in rnodes(3)):
                return True
        except RuntimeError:
            pass
        time.sleep(1.0)
    return False


def leave_viewer():
    back = W.find("关闭") or W.find("返回")
    if back:
        W.tap(back["cx"], back["cy"])
        time.sleep(1.5)


def grant_screenshots():
    """release 包默认开 FLAG_SECURE（截图全黑），进设置关掉它。

    **只在对照轮做**：debug 包默认就是关的（`BuildConfig.DEBUG` 决定）。
    不关的话对照轮所有截图都是纯黑，而"选图器里没有视频"这条结论就只剩
    a11y 节点一个来源 —— 少了一半证据，事后翻图也看不出当时屏幕上是什么。
    关掉之前先留一张黑屏：那本身就证明"默认禁止截屏"真的接在窗口上了。
    """
    W.log("release 包：进设置关掉「禁止截屏」（默认开着，不然截出来全是黑的）")
    time.sleep(1.5)
    W.shot("约束-默认禁止截屏")
    assert R108.open_settings(W), "打不开设置页"
    time.sleep(1.5)
    before = R108.is_checked(W, "禁止截屏")
    note("release 包「禁止截屏」默认状态", f"{before}（应为 True）")
    if before:
        R108.toggle_row(W, "禁止截屏")
        note("关掉之后", f"{R108.is_checked(W, '禁止截屏')}（应为 False）")
    back = W.find("返回")
    if back:
        W.tap(back["cx"], back["cy"])
        time.sleep(1.5)


# ==================== 一轮 ====================

def run_phase_a():
    """对照轮：出货版 v1.0.9 看不到任何视频。"""
    W.log("================ 对照轮 · 出货版 v1.0.9（图库只认图片） ================")
    W.log(f"安装 {APK_OLD}")
    sh("uninstall", PKG)
    out = sh("install", "-r", "-g", APK_OLD)
    assert "Success" in out, f"安装失败：{out}"
    ver = installed_version()
    W.log(f"    设备上装的是 {ver}")
    assert "1.0.9" in ver, f"对照包应是 1.0.9，实际 {ver}"

    warm_up_uiautomator()
    V105.onboarding()
    grant_screenshots()

    entry = open_media_library("原图加密存储")
    check("v1.0.9：主页入口叫「照片库」，没有任何「视频」字样（缺陷：功能不存在）",
          "视频" not in entry, f"实际入口文案「{entry}」")

    header = next((n["text"] for n in W.nodes() if "原图加密存储" in n["text"]), "")
    check("v1.0.9：页头按「张」计数（只统计图片）",
          "张" in header and "项" not in header, f"实际页头「{header}」")

    cells, blobs, im = await_picker()
    W.shot("选图器")
    note("v1.0.9 选图器里的可勾格子数", f"{len(cells)}（相册里有老照片，所以不是 0）")
    note("v1.0.9 选图器截图里的红色缩略图数", f"{len(blobs)}")
    check("v1.0.9：选图器里照片都在（相册确实非空），但两段纯红影片一张都看不见 → 视频不可选",
          grid_filled(im) and len(blobs) == 0,
          f"网格已加载={grid_filled(im)}（这是相册非空的证据）、红色缩略图 {len(blobs)}（应为 0）")
    note("这就是用户报的「图库无法读取视频文件」", "v1.0.9 的选图器与图库在四层上都只认图片")

    sh("shell", "input", "keyevent", "4")
    time.sleep(1.0)


def count_and_labels():
    """等页头张数变成 2，并返回网格上出现的时长角标集合。"""
    deadline = time.time() + 180
    last = None
    while time.time() < deadline:
        try:
            last = media_count()
        except RuntimeError as e:
            W.log(f"    （dump 暂时不可用，继续等：{e}）")
            last = None
        if last == 2:
            break
        time.sleep(1.5)
    labels = set()
    for n in W.nodes():
        if re.fullmatch(r"\d+:\d\d", n["text"].strip()):
            labels.add(n["text"].strip())
    return last, labels


def warm_up_uiautomator():
    """进引导之前，先把界面树抓取服务唤醒一次。

    长跑过的设备上 UiAutomation 会偶发连不上：logcat 里是
    `TimeoutException: Timeout while connecting UiAutomation@...[id=-1]`，
    崩掉的是 uiautomator 自己的进程，**不是被测应用**。
    那一刻 `W.wait_text` 什么文字都读不到，现象是"应用卡在启动页"——
    而截图里应用明明好好的（实测就是这样白等了一轮：应用占 88% CPU 在正常跑、
    前台就是 MainActivity、logcat 里没有任何应用崩溃，只是那台模拟器
    4 GB 内存只剩 466 MB，把启动拖到了 57.9 秒）。
    第一次抓成功之后服务就常驻了，所以这里先试几次，把这一枪挡在门外。
    """
    for i in range(6):
        try:
            if rnodes(retries=2):
                W.log(f"界面树抓取服务就绪（第 {i + 1} 次尝试）")
                return
        except Exception as e:
            W.log(f"    界面树抓取暂不可用：{type(e).__name__}: {e}")
        time.sleep(3)
    W.log("⚠ 界面树抓取服务始终不可用 —— 后面的判据会成片失败。"
          "这多半是模拟器跑太久，重启它比重试有效。")


def run_phase_b():
    """验证轮：本次实现版。"""
    W.log("================ 验证轮 · 本次实现版（debug） ================")
    W.log(f"安装 {APK_NEW}")
    sh("uninstall", PKG)
    out = sh("install", "-r", "-g", APK_NEW)
    assert "Success" in out, f"安装失败：{out}"
    W.log(f"    设备上装的是 {installed_version()}")

    warm_up_uiautomator()
    V105.onboarding()

    # ---- 1. 入口与空态 ----
    entry = open_media_library("照片与视频")
    W.shot("媒体库-空态")
    check("主页入口文案含「照片与视频」（能力在界面上有条目）",
          "视频" in entry, f"实际入口文案「{entry}」")
    header = next((n["text"] for n in W.nodes() if "原图原片加密存储" in n["text"]), "")
    check("空库页头是「原图原片加密存储 · 0 项」（计数口径从「张」改成「项」）",
          header.endswith("0 项"), f"实际页头「{header}」")

    # 浮标的文案**取不到**：`ExtendedFloatingActionButton` 的子文本被语义合并吞掉了，
    # a11y 树里那个节点只有 bounds 和 clickable（v1.0.8 那轮的「新建」浮标也是这样）。
    # 所以这里判"按钮在不在、在不在右下角"，文案交给截图 ——
    # 硬要断言一处读不到的东西，只会得到一条永远红的假警报。
    fab = find_fab()
    check("媒体库右下角有导入浮标",
          fab is not None and fab["cy"] > 1900,
          f"浮标 {fab and (fab['cx'], fab['cy'])}"
          f"（文案「添加照片或视频」见截图 媒体库-空态）")

    # ---- 2. 从选图器导入两段影片 ----
    cells, blobs, im = await_picker()
    W.shot("选图器")
    note("选图器里的可勾格子数",
         f"{len(cells)}（含相册里的老照片）；其中红色缩略图 {len(blobs)} 个")
    check("选图器里两段影片都在、都能勾（对照轮 v1.0.9 这里是 0）",
          len(blobs) == 2, f"红色缩略图 {len(blobs)} 个，可勾格子 {len(cells)} 个")
    tap_picker_cells(cells, im)

    total, labels = count_and_labels()
    W.shot("媒体库-两个视频格")
    check("两段影片都导进来了（页头「· 2 项」）", total == 2, f"页头读到 {total} 项")
    check("两个格子上都有时长角标，数值来自容器而不是猜的"
          f"（{CLIPS['landscape.mp4']['label']} 与 {CLIPS['portrait.mp4']['label']}）",
          labels == {CLIPS["landscape.mp4"]["label"], CLIPS["portrait.mp4"]["label"]},
          f"实测角标 {sorted(labels)}")

    # ---- 3. 存储层：落盘格式 ----
    files = attachments()
    W.log(f"沙箱 files/attachments/ 下有 {len(files)} 个文件："
          + "，".join(f"{k[:8]}…={v}B" for k, v in files.items()))
    check("沙箱里确实落盘了两个附件密文", len(files) == 2, f"{len(files)} 个")

    # 把"哪个 blob 是哪段影片"对上：**按落盘体积认领**，不按大小排序配对
    # （原因见 specs_for_size：横屏加长之后大小关系会反转，配对就错了）。
    claimed, counts = {}, {}
    for blob_id, size in files.items():
        cands = specs_for_size(size)
        if len(cands) != 1:
            # 0 个 = 体积对不上任何素材；2 个 = 两条素材落在**同一个块数档位**
            # （都小于 1 MiB 时必然如此），这时认领是猜的，必须停下来。
            hint = (
                " —— 两段素材的块数相同（"
                + "、".join(f"{s['tag']}={blob_chunks(s['plain_bytes'])} 块"
                            for s in CLIPS.values())
                + "），落盘体积因此无法区分，素材需要重做" if len(cands) > 1 else "")
            check("每个附件的落盘体积都能唯一对上某一段素材", False,
                  f"{blob_id[:8]}… 是 {size}B，对上 {[c['tag'] for c in cands]}"
                  f"（应为 1 个）{hint}")
            continue
        spec = cands[0]
        claimed[spec["tag"]] = blob_id
        n = verify_stored_video(blob_id, size, spec)
        if n is not None:
            counts[spec["tag"]] = n
    check("两段影片各自对上自己的素材（不是互相配错）",
          len(claimed) == 2, f"认领到 {sorted(claimed)}")
    # 一段素材如果只有一块，随机读取永远落在第 0 块里 —— 跨块边界的偏移算术、
    # 块号参与 AAD、末块标记，这三样**一个都不会被走到**。所以这条不是凑数判据。
    check("至少有一段素材在设备上落成多块（跨块读取才真的被走过）",
          len(counts) == 2 and max(counts.values()) >= 2, f"各段块数 {counts}")

    # ---- 4. 播放：几何 + 在播 ----
    sh("logcat", "-c")
    open_cell_by_label(CLIPS["landscape.mp4"]["label"])
    assert wait_viewer(timeout=30), "点开视频后大图页没起来"
    rows = burst("B-横屏-播放中", BURST_PLAY)
    shot = [(p, a, band_of(a)) for p, a in rows if a is not None]
    live = [(p, a, b) for p, a, b in shot if b]
    check("打开视频后画面就是影片（连拍每一帧都能量到红底条带，无需点任何东西）",
          len(live) == len(shot) and len(live) > 0,
          f"{len(shot)} 帧里 {len(live)} 帧有影片画面")

    if live:
        path, a, band = max(live, key=lambda t: t[2]["h"])
        sq = square_of(a, band)
        blk = block_x(a, band)
        W.log(f"    量到的帧：{os.path.basename(path)}")
        W.log(f"    {describe(band, sq, blk)}")
        spec = CLIPS["landscape.mp4"]
        check(f"横屏：条带高度 ≈ {spec['band_h']:.0f}px（16:9 铺进 1080 宽应得的高度）",
              abs(band["h"] - spec["band_h"]) <= 15,
              f"实测 {band['h']}px，差 {band['h'] - spec['band_h']:+.0f}px")
        check("横屏：条带横向铺满可视宽度（影片没被裁成更窄的一条）",
              band["x0"] <= 4 and band["x1"] >= VIEW_W - 5,
              f"x[{band['x0']},{band['x1']}]，屏宽 {VIEW_W}")
        if sq:
            check("画面里的正方形渲染出来还是正方形（比例 ≈1.000；被拉伸会变成 0.25）",
                  abs(sq["ratio"] - 1.0) <= 0.05, f"{sq['w']}×{sq['h']} → {sq['ratio']:.3f}")
        else:
            check("画面里的正方形能量到", False, "条带里找不到白像素")

        # 运动读数：连拍帧之间白块位置必须变
        xs = []
        for p, a2, b2 in live:
            b = block_x(a2, b2)
            if b:
                xs.append((os.path.basename(p), round(b["cx"], 1)))
        W.log(f"    连拍各帧白块质心：{xs}")
        check("连拍帧之间白块位置在变 → 画面确实在播（不是停在第一帧的静止图）",
              moving(motion_stats(xs)), motion_text(motion_stats(xs)))

    # ---- 5. 控制条：先把影片停下，再读数 ----
    #
    # 这一段整体是"暂停优先"的（原因见 tap_pause 的注释）：控制条在播放中 3.5 秒
    # 就自己收起来，而轻点画面是 toggle —— 播放态下读数只能靠运气。
    # 停下之后控制条常驻，时间、总时长、进度条、拖动才都是可重复的读数。
    ns, did_pause = tap_pause()

    # 这一条是下面那一组的**前提**：影片若已播完，按钮本来就是「播放」，
    # 于是"点完暂停后按钮变成播放"无论点不点都成立。前提不成立时要响，不要绿。
    check("点暂停之前确实处于播放态（否则这一组判据会被「播完」顶成假通过）",
          did_pause,
          "暂停前的快测量到画面在动（这是「正在播放」的定义）" if did_pause
          else "暂停前没量到画面在动 —— 这一组的结论不成立")

    paused = next((n for n in ns if n["desc"] == "播放"), None)
    check("点暂停后按钮变成「播放」（状态真的切了）", paused is not None,
          f"节点 desc {[n['desc'] for n in ns if n['desc'] in ('播放', '暂停')]}")

    # 画面里的白块必须真的停住：按钮变成「播放」只说明 Compose 的状态翻了，
    # **解码器有没有停**才是画面上的事实。
    rows = burst("B-横屏-暂停后", BURST_PAUSE)
    xs = centroids(rows)
    W.log(f"    暂停后各帧白块质心：{xs}")
    # 按钮变成「播放」只说明 Compose 的状态翻了，**解码器有没有停**才是画面上的事实。
    check("暂停后画面停住（白块位置不再变化）", still(motion_stats(xs)),
          motion_text(motion_stats(xs)))
    W.shot("横屏-已暂停")

    # 暂停态把控制条一次读全（此时它常驻，不必跟 3.5 秒赛跑）
    spec_l = CLIPS["landscape.mp4"]
    times = [n["text"].strip() for n in ns if re.fullmatch(r"\d+:\d\d", n["text"].strip())]
    check("控制条上显示了当前时间与总时长", len(times) >= 2, f"读到 {times}")
    check(f"总时长读出来是 {spec_l['label']}"
          f"（容器时长 {spec_l['duration_ms'] // 1000} 秒，不是猜的）",
          spec_l["label"] in times, f"读到 {times}")

    slider = next((n for n in ns if n["cls"] == "SeekBar"), None)
    if slider is None:
        slider = next((n for n in ns
                       if n["clickable"] and n["w"] > 500 and n["cy"] > 2100), None)
    W.log(f"    进度条节点："
          f"{slider if slider is None else {k: slider[k] for k in ('cls', 'x1', 'y1', 'x2', 'y2')}}")
    # 详情写成"找到的那个节点长什么样"，而不是一句常驻的"找不到" ——
    # `check()` 无论通过与否都会把详情打出来，写死一句失败语会让 PASS 行读起来像 FAIL。
    check("控制条上有可拖的进度条", slider is not None,
          (f"{slider['cls']} x[{slider['x1']},{slider['x2']}] "
           f"y[{slider['y1']},{slider['y2']}]") if slider else "找不到 SeekBar 形状的节点")

    # 拖之前先记下页码：进度条是**横向**拖动，而外层是 HorizontalPager ——
    # 万一这条滑动被 Pager 抢走，表现就是"翻到下一张"，而不是"拖不动"。
    # 记下来才能一眼分辨这两种失败。
    page_before = current_page()

    # 真的拖一把。**落点不能用"节点宽度 × 比例"算** —— Slider 的节点 bounds
    # 含两侧内边距（`padding(horizontal = 10.dp)` + 滑块半径），实际轨迹比节点窄、
    # 且与节点同心。实测按节点宽度算 30% 落到了 0:14（14/60 = 23.3%），
    # 系统性偏小，而报出来的失败长得跟"拖动功能坏了"一模一样。
    #
    # 不猜内边距，**量出来**。落点 x 与读数分数是一次仿射映射
    #     f(x) = (x - a) / travel
    # 在节点宽度的 30% 与 60% 两处各拖一把、把读数换成分数，两式相减即得行程
    #     travel = 0.30·w / (f2 - f1)
    # 代回即得轨迹零点 a，之后按解出的 a、travel 拖到**真正的** 30%。
    #
    # 精度：读数只有整秒（±0.5 秒量化），(f2 - f1) 约 0.3 → 行程相对误差约 6%，
    # 传到落点上约 1 秒，远小于 ±3 秒的容差，够用。
    #
    # 位置取 30% 而不是 70%：紧接着还有"恢复播放 → 切后台再回来 → 再验一次在播"，
    # 那一段要留足剩余片长（60 秒的 30% 处还剩 42 秒）。
    total_s = spec_l["duration_ms"] / 1000.0
    if slider is not None:
        w = slider["w"]
        probes = {}
        for frac in (0.30, 0.60):
            px = slider["x1"] + int(frac * w)
            cur, _ = drag_seek(slider, px, total_s)
            probes[frac] = cur
            W.log(f"    标定第一轮：拖到节点宽度 {frac:.0%} 处（x={px}）→ "
                  f"{'读不出时间' if cur is None else f'读到 {cur} 秒'}")
            time.sleep(0.3)

        f1, f2 = probes[0.30], probes[0.60]
        travel = None
        start = None
        if f1 is None or f2 is None:
            W.log("    ⚠ 标定没读出读数（控制条上没找到当前时间），落点退回按节点宽度算")
        elif f2 <= f1:
            W.log(f"    ⚠ 标定不成立：拖到 60% 处读到的 {f2} 秒没有比 30% 处的 "
                  f"{f1} 秒更靠后 —— 两把拖都没生效，落点退回按节点宽度算")
        else:
            travel = 0.30 * w / ((f2 / total_s) - (f1 / total_s))
            start = slider["x1"] + 0.30 * w - (f1 / total_s) * travel
            # 上界给到 2 倍节点宽度，**不是**节点宽度本身：实测解出来的行程是 902px，
            # 而 a11y 报的 SeekBar 节点只有 802px —— 实际轨迹比节点 bounds 宽。
            # 第一版把上界写成"节点宽度"（想的是"轨迹不可能比节点宽"），
            # 结果是**一条产品的 PASS 被我自己的前提判据顶成了 FAIL**：
            # 落点 19 秒明明落在 15~21 的容差里，却因为标定被否决而报红。
            # 物理直觉在这里不管用，量出来的才算。
            if not (0 < travel <= 2 * w):
                W.log(f"    ⚠ 标定解出的行程 {travel:.0f}px 离奇"
                      f"（节点只有 {w}px 宽），落点退回按节点宽度算")
                travel = start = None
            else:
                # 两侧内边距**分别**列出来：轨迹与节点未必同心，
                # `(w - travel) / 2` 那种写法一旦不同心就会给出负数的"内边距"。
                W.log(f"    标定结果：轨迹行程 {travel:.0f}px、零点 x≈{start:.0f}"
                      f"（节点 x[{slider['x1']},{slider['x2']}] 宽 {w}px → "
                      f"左侧让出 {start - slider['x1']:.0f}px、"
                      f"右侧让出 {slider['x2'] - (start + travel):.0f}px）")

        # 前提判据：标定不成立时，下面那条"能不能跳到目标位置"的结论没有意义，
        # 必须响亮地说出来，而不是拿"按节点宽度算"的结果当成产品结论。
        check("进度条落点标定成立（两把标定拖都读出了更靠后的时间）",
              travel is not None,
              f"读数为 30%→{f1} 秒、60%→{f2} 秒"
              if travel is None else
              f"行程 {travel:.0f}px，零点 x≈{start:.0f}")

        if travel is not None:
            x_to = int(round(start + 0.30 * travel))
        else:
            # 退回按节点宽度算。**这条路上的结论不可信**，日志里已说明原因。
            x_to = slider["x1"] + int(0.30 * w)
        x_to = max(slider["x1"], min(slider["x2"], x_to))

        cur, after = drag_seek(slider, x_to, total_s)
        left = [n["text"].strip() for n in after
                if re.fullmatch(r"\d+:\d\d", n["text"].strip())]
        W.log(f"    拖进度条到 x={x_to}（目标 30%）→ 控制条读到 {left}")
        page_after = current_page()
        if page_after != page_before:
            W.log(f"    ⚠ 页码从 {page_before} 变成了 {page_after} —— "
                  f"这条横滑被外层 Pager 抢走了，不是进度条没生效")

        # 目标位置按素材时长算，不写死秒数：拖到 30% 处，允许 ±3 秒的落点误差。
        target = total_s * 0.30
        seek_ok = cur is not None and abs(cur - target) <= 3
        check(f"拖动进度条能跳到目标位置（拖到 30% 后当前时间应落在"
              f" {target - 3:.0f}~{target + 3:.0f} 秒）",
              seek_ok,
              f"拖动后控制条读到 {left}"
              + (f"，其中当前时间 {cur} 秒" if cur is not None
                 else "（当前时间读不出来）"))
        check("拖进度条没有被外层翻页手势抢走（页码不变）",
              page_after == page_before, f"{page_before} → {page_after}")

        # 落点**重复性**：同一个坐标连拖 3 把，看读数散不散。
        #
        # 这一项只记录、不判定，但它是"±3 秒容差到底够不够"的唯一依据。
        # 历史实测（同一个落点 x=442）四轮依次读到 **14 / 15 / 18 / 19 秒** ——
        # 抖动约 5 秒，而且**没有系统性偏移**（均值 16.5，目标 18）。
        # 也就是说上面那条容差是贴着抖动边界定的；把散度打出来，
        # 下一次谁再看到它红了，一眼能分清是设备抖还是真回归。
        repeat = []
        for _ in range(3):
            cur_i, _ = drag_seek(slider, x_to, total_s)
            repeat.append(cur_i)
            time.sleep(0.2)
        W.log(f"    同一落点 x={x_to} 连拖 3 把的读数：{repeat}"
              f"（本次首把 {cur} 秒，目标 {target:.0f} 秒）")
        note("拖动落点的重复性（同一坐标连拖 3 把）",
             f"读数 {repeat}，极差 "
             f"{max(repeat) - min(repeat) if None not in repeat else '—'} 秒"
             f" —— 这就是 ±3 秒容差够不够的实测依据")
        W.shot("横屏-拖到30%")

    # 再点播放：白块重新动起来。**优先落在控制条那个按钮上** —— 暂停态下画面正中
    # 还有一个同样叫「播放」的大按钮；点它虽然也能播起来，但那是"点画面"，
    # 验不到控制条那一侧的联动。用 cy 区分（控制条贴屏幕底边）。
    ns = reveal_controls()
    play_btn = next((n for n in ns if n["desc"] == "播放" and n["cy"] > 2100), None)
    if play_btn is None:
        play_btn = next((n for n in ns if n["desc"] == "播放"), None)
        if play_btn is not None:
            W.log("    ⚠ 控制条上的播放键没读到，退而点画面正中那个")
    if play_btn is not None:
        W.tap(play_btn["cx"], play_btn["cy"])
        time.sleep(0.5)
    rows = burst("B-横屏-再播", BURST_PAUSE + 2)
    xs = centroids(rows)
    W.log(f"    恢复播放后各帧白块质心：{xs}")
    # 这一项还要给 5.5 段当前提：Surface 重建后播放器只在**播放中**才重绘，
    # 暂停态重建本来就该是黑的（那是播放器的正常行为，不是缺陷）。
    resumed = moving(motion_stats(xs))
    check("点「播放」后画面重新动起来（可暂停、可恢复）", resumed,
          motion_text(motion_stats(xs)))

    # ---- 5.5 切后台再回来：Surface 会重建 ----
    #
    # 这一条验的是一处**只在"切出去看一眼再回来"时才发作**的隐患，而它的成因很反直觉：
    # SurfaceView 被销毁时我们释放播放器，而 `MediaPlayer.release()` 会顺着 JNI
    # 回调 `MediaDataSource.close()`；如果那条 close 顺手把**读取器**也关了，
    # 那么回来时新建的播放器就会拿着一个已关闭的文件句柄去 setDataSource →
    # 抛异常 → 界面显示「这段视频读不出来」。
    #
    # 用户看到的现象是"我切出去回个消息，回来视频就坏了，重启才恢复" ——
    # 这类问题在主动走查里**永远不会出现**（走查不会去按 HOME），必须专门造出这个场景。
    #
    # 前提是**此刻在播**：Surface 重建后播放器只在播放时才重绘，暂停态重建本来
    # 就该是黑的。上一版就在这儿白追了一轮"读取器被误关"—— 真实原因是前一步的
    # "恢复播放"根本没执行到（控制条读不到），影片一直停在暂停态。
    check("切后台之前影片确实在播（上一条连拍已证明画面在动）",
          resumed, "播放中" if resumed else "上一步没量到运动，这一段的结论无效")

    page_before_home = current_page()
    W.log("按 HOME 切到后台，再切回来（Surface 会走一遍 destroyed → created）")
    W.adb("shell", "input", "keyevent", "3")   # KEYCODE_HOME
    time.sleep(2.5)
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3.0)
    rows = burst("B-横屏-后台返回", 4)
    back = [(p, a, band_of(a)) for p, a in rows if a is not None]
    alive = [t for t in back if t[2]]
    check("切后台再回来后画面上还有影片（播放器重建时读取器没被误关）",
          len(alive) > 0, f"{len(back)} 帧里 {len(alive)} 帧有影片画面")
    # 切出去看一眼不该让用户"回来发现自己翻到别的影片了"。页数只有 2 页，
    # 而这一条盯的正是"退到后台/回前台"这条路会不会把 Pager 的位置弄丢。
    page_after_home = current_page()
    check("切后台再回来仍停在原来那一页（没被翻到别的影片）",
          page_after_home == page_before_home,
          f"{page_before_home} → {page_after_home}")
    check("没有出现「这段视频读不出来」（那正是读取器被误关的表现）",
          W.find("读不出来") is None,
          f"界面文字：{[n['text'] for n in rnodes() if n['text']][:8]}")

    # 返回后**真的再播一次**：只是"画面还在"不足以证明读取器可用（末帧是静态的），
    # 必须让解码器重新去读一遍文件。这一步是这条判据的核心。
    # **不能断言一定读得到按钮。** Surface 重建后若按 `wantsPlay` 自动续播了，
    # 那么此刻既没有画面正中的播放键（那个只在暂停态出现），控制条也会在
    # 3.5 秒后自己收起 —— a11y 里一个按钮都没有，而这是**正常**的。
    # 上一版在这里断言"必须有播放键"，于是 25 项 PASS 之后整轮被打断。
    # 「读取器还能不能用」最终由下面那条连拍判据回答，不靠某个按钮在不在。
    ns = reveal_controls()
    if any(n["desc"] == "暂停" for n in ns):
        note("切回来时的状态", "已经在播（Surface 重建后按用户意图自动续播）")
    else:
        glyph = (next((n for n in ns if n["desc"] == "播放" and n["cy"] < 2100), None)
                 or next((n for n in ns if n["desc"] == "播放"), None))
        if glyph is None:
            W.log("    ⚠ 控制条上读不到播放键（多半已自动续播且控制条收起）—— "
                  "这一步只验画面在不在动")
        else:
            W.log(f"    切回来是暂停态，点播放键（{glyph['cx']},{glyph['cy']}）重新播一遍")
            W.tap(glyph["cx"], glyph["cy"])
            time.sleep(0.6)
    rows = burst("B-横屏-返回后重播", 5)
    xs = centroids(rows)
    W.log(f"    返回后重播各帧白块质心：{xs}")
    check("返回后还能重新播放（画面真的在动，说明读取器仍可用）",
          moving(motion_stats(xs)), motion_text(motion_stats(xs)))

    check("解码器被创建过（logcat）", R108.decoder_created(),
          "NuPlayerDriver / c2.*.h264.decoder")
    check("第一帧确实渲染到了 surface 上（logcat MEDIA_INFO_VIDEO_RENDERING_START）",
          R108.video_render_started(), "info/warning (3, 0)")

    # ---- 6. 竖屏：不被摆成横的 ----
    leave_viewer()
    open_cell_by_label("0:08")
    assert wait_viewer(timeout=30), "点开竖屏视频后大图页没起来"
    rows = burst("B-竖屏-播放中", BURST_PLAY)
    shot = [(p, a, band_of(a)) for p, a in rows if a is not None]
    live = [(p, a, b) for p, a, b in shot if b]
    if live:
        path, a, band = max(live, key=lambda t: t[2]["h"])
        sq = square_of(a, band)
        blk = block_x(a, band)
        W.log(f"    量到的帧：{os.path.basename(path)}")
        W.log(f"    {describe(band, sq, blk)}")
        spec = CLIPS["portrait.mp4"]
        check(f"竖屏：条带高度 ≈ {spec['band_h']:.0f}px（9:16 铺进 1080 宽应得的高度 —— "
              f"横躺的竖拍视频会得到一个扁条带）",
              abs(band["h"] - spec["band_h"]) <= 20,
              f"实测 {band['h']}px，差 {band['h'] - spec['band_h']:+.0f}px")
        if sq:
            check("竖屏：正方形比例仍为 1.000（竖向没被压扁）",
                  abs(sq["ratio"] - 1.0) <= 0.05, f"{sq['w']}×{sq['h']} → {sq['ratio']:.3f}")
    else:
        check("竖屏视频能播出来", False, f"{len(shot)} 帧里没有一帧有影片画面")
    leave_viewer()

    # ---- 7. 删除：密文必须一起消失 ----
    cells = W.nodes()
    cell = next((n for n in cells
                 if n["clickable"] and not (n["text"] or n["desc"]) and 280 < n["y1"] < 1900), None)
    assert cell is not None, "媒体库里找不到可长按的格子"
    W.log("长按一格进入选择模式")
    R108.long_press(W, cell["cx"], cell["cy"])
    check("长按后进入选择模式", W.find("已选 ") is not None,
          f"{[n['text'] for n in W.nodes() if '已选' in n['text']]}")
    assert R108.tap_select_all(W), "「全选」没生效（已选数没涨到总数）"
    W.shot("删除-全选")
    del_icon = next((n for n in W.nodes() if n["desc"] == "删除选中的内容"), None)
    assert del_icon is not None, "找不到顶栏删除按钮"
    W.tap(del_icon["cx"], del_icon["cy"])
    time.sleep(1.0)
    assert R108.tap_dialog_confirm(W, "删除"), "确认框里没找到「删除」"

    deadline = time.time() + 60
    left = None
    while time.time() < deadline:
        left = media_count()
        if left == 0:
            break
        time.sleep(1.0)
    W.shot("删除后-空库")
    check("删除后页头回到「· 0 项」", left == 0, f"页头读到 {left} 项")

    files = attachments()
    check("删除记录后视频密文也一起消失（不是只删了记录）",
          len(files) == 0, f"attachments 里还剩 {list(files)}")


def main():
    phase = (sys.argv[1] if len(sys.argv) > 1 else "B").upper()
    assert phase in ("A", "B"), "用法：walkthrough-v110-video.py [A|B]"

    global PHASE_TAG
    PHASE_TAG = phase
    os.makedirs(OUT, exist_ok=True)

    if phase == "A":
        run_phase_a()
    else:
        run_phase_b()

    print()
    if FAILURES:
        W.log(f"共 {len(FAILURES)} 条未通过：")
        for f in FAILURES:
            print(f"    · {f}")
        sys.exit(1)
    W.log(f"第 {phase} 轮全部通过")


if __name__ == "__main__":
    main()
