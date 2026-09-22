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

  · `landscape.mp4` 320×180 120 秒（16:9）—— 验常规路径（长度被走查节奏逼过两次，见下）
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

横屏素材加长过两次，都是被这一段逼的（30 → 60 → 120 秒，第二次的经过见
`tools/_mkvideos.py` 的说明）：打开 → 连拍取"在播" → 暂停 → 读数 → 拖到 30% →
恢复播放 → **切后台再回来**（这一段必须在"仍在播"的状态下做）→ 再连拍。
release 包上这一路要 80 秒上下，片子短了就会撞上"刚好播完"，
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
        "duration_ms": 120_000,
        "label": "2:00",
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

# 控制条在**播放中**只亮这么久（app 侧 `CONTROLS_TIMEOUT_MS`，见 ImageViewerScreen）。
# 抄过来不是为了让两处数字长得一样，是为了让 [TAP_GAP_S] 的**依据**留在代码里：
# 连点两下的间隔必须远小于它，否则第二下落在已经收起的控制条上 ——
# 那一下只会把控制条再 toggle 一次，"点了几次都没反应"就是这么来的。
CONTROLS_LIFE_S = 3.5
TAP_GAP_S = 0.45    # 连点两下的间隔：够控制条滑出来，又远小于 3.5 秒
MAX_PAUSE_ROUNDS = 3   # 连点两下算一轮；三轮还停不下就当失败，别无限重试
# 「暂停前画面确实在动」这一读数的重试次数。
# 画面读数会陈旧（`screencap` 返回一两秒前的帧），而陈旧只持续一两秒 ——
# 重试一次基本就能拿到活读数。见 [tap_pause] 里那段说明。
BEFORE_TRIES = 3
# 「应用自己报的播放位置」两次读数之间隔多久。
# 显示精度是 1 秒，播放中隔 2 秒必然跨过至少一个整秒 —— 拿它当"播放器还在走"的证据。
CLOCK_SETTLE_S = 2.0

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
# 「不适用」的判据（这一轮的包/环境不具备验证条件）。**不计入 FAILURES** ——
# 把它算成红会让"验不了"看起来像"验不过"；而算成绿就等于撒谎。
# 见 [skip] 的说明：恒绿比恒红危险。
SKIPPED = []


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


def skip(label, why):
    """判不了的一条：明确记成「不适用」，既不占 PASS 也不占 FAIL。

    ## 为什么需要第三种状态

    2026-09-17 在 release 包上跑这一轮时踩出来的：三条靠 `run-as` 读沙箱的判据
    全部报红（`run-as` 只对 debuggable 的包开放），而同一段里
    "删除后密文消失"那条**恒绿** —— 它的断言是 `len(files) == 0`，
    而读不到沙箱时 `files` 恒为空，于是 0 == 0 无条件成立。

    恒绿比恒红危险得多：恒红至少会有人去看，恒绿会让人以为"这件事验过了"。
    可验不了和验过了是两件事，所以这里给它们第三种状态。
    凡是"这一轮的包/环境不具备验证条件"的判据，都走这里，
    **并在文案里说清去哪一轮找它的证据。**
    """
    SKIPPED.append(label)
    print(f"    [不适用] {label}：{why}")


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


# ==================== 装包/卸包：每一步都验结果 ====================
#
# 2026-09-17 第四次假红之后补的。这台机器上 adb server 会自己掉，掉线期间
# 命令带着 `error: device offline` **正常返回**（不抛异常、退出码也不一定非零）。
# 于是"调了卸载、不看结果、接着装"这条路会以一句
# `INSTALL_FAILED_VERSION_DOWNGRADE` / `signatures do not match` 冒出来 ——
# 症状指向"版本号/签名配错了"，而真因是设备掉线。
#
# 判据一律是**复核包的存亡**，不是那句话：这台设备实测过
# `DELETE_FAILED_INTERNAL_ERROR` 会误报（包其实已经删掉了）。

def wait_device(timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        blob = sh("get-state")
        if "device" in blob and "offline" not in blob:
            return True
        subprocess.run([ADB, "start-server"], capture_output=True, timeout=120)
        time.sleep(2)
    return False


def pkg_installed():
    """设备离线时返回 True（保守：当作还装着，让调用方重试）。"""
    if not wait_device(timeout=30):
        return True
    return PKG in sh("shell", "pm", "list", "packages")


def uninstall_pkg():
    for attempt in range(1, 4):
        wait_device()
        out = sh("uninstall", PKG).strip()
        if not pkg_installed():
            if "Success" not in out:
                W.log(f"    卸载第 {attempt} 次返回 {out[:80]}，但复核后包确实已消失"
                      f"（这台设备会误报 DELETE_FAILED_INTERNAL_ERROR）")
            return True
        W.log(f"    卸载第 {attempt} 次返回 {out[:80]}，包仍在，重试")
        time.sleep(2)
    return False


def install_apk(path):
    """装，并**确认装上了**。返回 adb 的输出供调用方记录。"""
    wait_device()
    out = sh("install", "-r", "-g", path)
    if "Success" in out and pkg_installed():
        return out
    raise AssertionError(
        f"安装 {path} 未确认成功。adb 说：{out.strip()[:300]}；"
        f"复核 pm list 时{'在' if pkg_installed() else '不在'}"
    )


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


# ==================== 「在播」/「停住」的门槛 ====================
#
# 判据是**速度与真值的比**，不是写死的 px/s。
#
# 白块在影片里从最左匀速走到最右，走完正好一个 (条带宽 − 块宽)，
# 所以它在屏幕上的速度真值就是
#
#     真值 = (条带宽 − 块宽) / 片长
#
# 两个宽度都从连拍帧上量（`band_of` / `block_x` 用的是同一个屏幕坐标系，
# 屏幕缩放自动消掉），片长取 [CLIPS] 里那份夹具事实。
# `motion_stats` 返回 `ratio = 速度 / 真值`，下面两个门槛判的就是它。
#
# ## 为什么不再写死（2026-09-17 的第五次假红）
#
# 这里原来是 `MOVING_SPEED = 8.0` / `STILL_SPEED = 3.0` 两个常数，
# 照当时注释里那句"真值约 16.7 px/s"各取一半与五分之一标的。
# 而 `_mkvideos.py` 的白块位移是**按整片长度归一化**的
# （`bx = int(span * (i / max(1, total - 1)))`）—— 那一轮为了让控制条那 3.5 秒的
# 窗口够用，把横屏素材从 60 秒加长到 120 秒（见 `_mkvideos.py` 顶部的说明），
# **片长翻倍，像素速度就减半**：真值 16.7 → 8.3，正好落到写死的门槛上。
#
# 实测那一轮读到 8.3 / 8.0 / 11.0 / 7.8 px/s —— 一半的读数被判成"没在动"，
# "点暂停之前确实处于播放态"这条**前提**于是变成 False，
# 后面一串依赖它的判据（控制条读数、进度条拖动、切后台再回来）跟着全红。
# 八个 FAIL 里没有一个能归到应用头上，全是量具拿旧标定去量新素材。
# 门槛改由素材自己给，以后再改片长、改分辨率都不会再失配。
#
# 取值：在播取真值的一半、停住取真值的五分之一，两侧都留出三倍以上余量
# （实测在播读数落在真值的 0.94~1.05，停住读数就是 0.00）。
MOVING_RATIO = 0.5
STILL_RATIO = 0.2

# 运动读数只在大图页那段横屏影片上取：竖屏那条只抓单帧验比例，不看运动。
# 速度真值要片长，这里把它固定成一处，别让它在各个调用点上漂。
MOTION_CLIP = "landscape.mp4"

# 切后台再回来时，画面位置允许比切走前**回退**多少秒。
#
# 时间上只应该往前走（切出去那几秒不算播放），所以正常值是"回来 ≥ 切走前"。
# 留 3 秒是给两个方向都留的余量：① 切走前那一帧的采样本身就晚了不到一秒；
# ② 重建 + prepare + seek 落地需要时间，最早那几帧可能还没跳到目标位置。
#
# 而"从头重播"这条缺陷的偏移量是**几十秒**（切走前在第 36 秒，回来在第 0 秒），
# 离 3 秒的门槛差着一个数量级 —— 不会有灰色地带。
# 详见 B 轮 5.6 段。
POSITION_KEEP_TOL = 3.0


def centroids(rows):
    """取出「白块横向质心」序列，外加**量到的几何** → `{"points", "band_w", "block_w"}`。

    连拍帧里量不到影片画面的那些直接跳过（可能是黑屏过渡帧或系统弹窗），
    所以 `points` 的条数**可能少于**连拍张数 —— 判据里要按实际条数说话。

    [band_w] / [block_w] 取**第一个量到白块的帧**上的读数，供 [motion_stats]
    推"速度真值"用。两者在整段里都是常数：块宽来自夹具里的
    `bw = max(16, width // 13)`，条带宽就是影片在屏幕上的显示宽度。
    量不到就是 `None` —— 此时真值推不出来，判据会**响亮地失败**，
    而不是拿一个假的阈值凑一个通过。
    """
    xs = []
    band_w = block_w = None
    for p, a in rows:
        if a is None:
            continue
        b = band_of(a)
        if not b:
            continue
        blk = block_x(a, b)
        if blk:
            xs.append((os.path.basename(p), round(blk["cx"], 1)))
            if band_w is None:
                band_w, block_w = b["w"], blk["w"]
    return {"points": xs, "band_w": band_w, "block_w": block_w}


def motion_stats(m, out_dir=None, clip=MOTION_CLIP):
    """从质心与几何算出位移、**速度**、**速度真值**，以及两者的比 [ratio]。

    不足两帧、或时间戳不可用返回 None。

    ## 为什么判据是速度，不是固定像素数

    连拍两帧之间的间隔取决于 `screencap` 的耗时（实测 0.3~0.9 秒，随模拟器负载浮动），
    所以"5 帧位移 20px"与"8 帧位移 91px"说的是同一件事 —— 都在播。
    按固定像素阈值判，前者会被误判成"没动"：B 轮就出过这条假 FAIL，
    返回后重播那组刚好 20.0px，恰好卡在 `> 20` 的门槛上。

    ## 为什么判据是**比值**，不是写死的速度

    白块从最左走到最右，走完正好一个 (条带宽 − 块宽) —— 所以速度真值是
    `(band_w - block_w) / 片长`，这是它的**定义式**，不是拟合。
    连拍帧上量到的宽度与质心在同一个屏幕坐标系里，屏幕缩放自动消掉。

    原先门槛写死成 px/s，靠"素材是 60 秒"这个隐含前提活着；
    片长一变，真值跟着变，门槛就成了刻舟求剑（见 [MOVING_RATIO] 上面那段）。
    改成比值之后，换素材、换分辨率、换片长都不必再动门槛。
    """
    if len(m["points"]) < 2:
        return None
    base = out_dir or OUT
    try:
        t0 = os.path.getmtime(os.path.join(base, m["points"][0][0]))
        t1 = os.path.getmtime(os.path.join(base, m["points"][-1][0]))
    except OSError:
        return None
    dt = t1 - t0
    if dt <= 0:
        return None

    xs = m["points"]
    span = max(x[1] for x in xs) - min(x[1] for x in xs)
    speed = span / dt

    duration_s = CLIPS[clip]["duration_ms"] / 1000.0
    band_w, block_w = m["band_w"], m["block_w"]
    truth = ratio = None
    if band_w and block_w and band_w > block_w:
        truth = (band_w - block_w) / duration_s
        if truth > 0:
            ratio = speed / truth

    return {"n": len(xs), "span": span, "dt": dt, "speed": speed,
            "truth": truth, "ratio": ratio, "band_w": band_w,
            "block_w": block_w, "duration_s": duration_s}


def motion_text(st):
    if st is None:
        return "量不到足够的两帧（或截图时间戳不可用）"
    if st["ratio"] is None:
        return (f"{st['n']} 个读数，位移 {st['span']:.1f}px / {st['dt']:.2f}s "
                f"→ {st['speed']:.1f} px/s，但**真值推不出来**"
                f"（条带宽 {st['band_w']} / 块宽 {st['block_w']} 没量到）"
                f" —— 没法判断，按失败处理")
    return (f"{st['n']} 个读数，位移 {st['span']:.1f}px / {st['dt']:.2f}s "
            f"→ {st['speed']:.1f} px/s = 真值的 {st['ratio']:.2f} 倍"
            f"（真值 {st['truth']:.1f} = ({st['band_w']}−{st['block_w']})px"
            f" / {st['duration_s']:.0f}s；门槛 在播 ≥{MOVING_RATIO:.2f}、"
            f"停住 ≤{STILL_RATIO:.2f}）")


def moving(st):
    """此刻在播？—— 速度达到真值的一半。

    实测在播读数落在真值的 0.94~1.05，取 0.5 是给采样抖动留的余量。
    `ratio` 为 None（真值量不到）**一律判否**：宁可响亮地失败，
    也不拿一个没有依据的门槛凑通过。
    """
    return st is not None and st["ratio"] is not None and st["ratio"] >= MOVING_RATIO


def still(st):
    """此刻停住了？—— 速度低到真值的五分之一以下。

    暂停时白块逐帧位置完全相同，速度就是 0.00，离门槛很远。
    门槛取 0.2 而不是更松，是因为两侧后果不对称：
    "把在播读成停住"会**伪造通过**（危险），"把停住读成在播"只会多点一次
    （见 `tap_pause` 的三次重试），最后仍以返回 False 响亮收场。
    """
    return st is not None and st["ratio"] is not None and st["ratio"] <= STILL_RATIO


def implied_secs(cx, band_w, block_w, clip=MOTION_CLIP):
    """白块质心 → 这段影片**播到第几秒**。量不出来返回 None。

    进度 = (质心 − 块宽/2) / (条带宽 − 块宽)；秒数 = 进度 × 片长。

    两个宽度取自**同一帧上的测量**（同一屏幕坐标系），所以设备分辨率、系统缩放
    都被约掉，不用重标；片长取自 [CLIPS] 的夹具事实，不写死秒数。

    ## 为什么不能只靠界面上的 `m:ss`

    控制条那个读数**是应用自己的说法**。2026-09-17 那次冻结的现场就是：
    界面说 `0:52` + 暂停图标（应用认为在播），而画面里白块一直贴在 0 秒的位置。
    两个读数打架时，**画面才是事实** —— 用户看的也是画面。
    """
    if band_w is None or block_w is None or band_w <= block_w:
        return None
    prog = (cx - block_w / 2.0) / float(band_w - block_w)
    if prog <= 0:
        return 0.0
    if prog >= 1:
        return CLIPS[clip]["duration_ms"] / 1000.0
    return prog * (CLIPS[clip]["duration_ms"] / 1000.0)


def position_keep_verdict(before_secs, after_secs, tol=POSITION_KEEP_TOL):
    """B 轮 5.6 段那条判据的判定逻辑 → `(状态, 说明)`，状态 ∈ {"pass","fail","skip"}。

    抽成一个函数而不是写在 B 轮里，是为了**能被自测**：`tools/motion/
    selftest_position_keep.py` 会拿**已经发生过的失败现场**（`dist/evidence/
    v110-release/` 那批连拍）喂进来，确认它真的判红。判定逻辑只有这一份 ——
    自测和走查跑的是同一段代码，不会各写一套然后各自漂走。

    ## 为什么"切走前不足 10 秒"要判 skip 而不是 pass

    切走前本来就在第 2 秒，那么"回来也在第 2 秒"既可能是**保住了**、
    也可能是**复位了** —— 两种解释都成立，这时候判 pass 就是假通过。
    第一版复现探针正是栽在这儿：它每轮都重新打开影片，位置天然接近 0，
    于是跑出"3/3 全绿"，却什么都没证明。宁可记成「判不了」。

    ## 为什么用 after 的**最大值**

    重建 + prepare + seek 落地要时间，连拍最早那几帧可能还停在 seek 之前的画面上
    —— 那个不算"位置丢了"，是多等一帧的事。反过来，"从头重播"时**每一帧**都贴在
    0 秒附近，最大值也救不了它（偏移量是几十秒，离 3 秒的容差差一个数量级）。
    """
    if before_secs is None:
        return "skip", "切走前那一帧量不出白块位置（没有对照值）"
    if before_secs < 10.0:
        return "skip", (f"切走前只量到第 {before_secs:.1f} 秒（<10 秒）——"
                        f"「复位到 0」和「本来就接近 0」分辨不开")
    if not after_secs:
        return "fail", f"切走前在第 {before_secs:.1f} 秒，但回来后一帧都没量到白块位置"
    low, high = min(after_secs), max(after_secs)
    ok = high >= before_secs - tol
    return ("pass" if ok else "fail"), (
        f"切走前 画面在第 {before_secs:.1f} 秒，回来后 第 {low:.1f}~{high:.1f} 秒"
        f"（门槛：最大读数 ≥ {before_secs - tol:.1f}）")


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


# 「画面没动」这个读数最多重测几次。三次的依据见 [motion_settled]：
# 前两个测量打架时，多测一两轮基本就能让第三个测量（控制条）把话说清。
MOTION_TRIES = 3


def motion_settled(prefix, count, tries=MOTION_TRIES):
    """连拍 [count] 张，回答"画面到底在不在动" → `(verdict, xs, why)`。

    `verdict` 取 True / False / None：在动 / 没动 / 三个测量互相打架、判不了。
    `xs` 是最后一次的 `centroids` 结果，供调用方继续拿几何算位置。

    ## 为什么不直接 `moving(motion_stats(centroids(burst(...))))`

    那条路只用**一个**测量（影片里白块的位置），而它有一个**已知的**失效模式：
    `screencap` 会返回一两秒前的旧帧。旧帧一旦"恰好和上一帧一样"，看起来就是"停住了"
    —— 而那正好是最危险的方向：把"量具没量到"读成"用户的应用坏了"。

    `run2` 里那三条判据就是这么红掉的（`B-横屏-再播`、`B-横屏-返回后重播`）：
    读数 `0.7 / 0.0 px/s`、质心逐帧完全相同，而同段"切后台回来没有从头重播"却是 PASS。
    只加帧数解决不了这件事 —— 探针里最极端的一次是**连测两组各 4 帧都是同一个质心**，
    随后"啪"地跳回正位。要解决它只能**换一个不走像素的测量**。

    ## 三条读数怎么合起来定案

    | 白块位置 | 应用时钟 | 控制条 | 结论 |
    |---|---|---|---|
    | 在动 | —— | —— | **在动**，立刻收工 |
    | 不动 | 已经到片尾 | —— | **影片正常播完** —— 不是缺陷 |
    | 不动 | 也不动 | —— | **真的停住 / 已播完** —— 两个独立测量一致，如实判否 |
    | 不动 | 在走 | 量得到 | **画面冻住了** —— 截图通路是活的（见 `controls_visible`），只有影片那块不动。判否并点名 |
    | 不动 | 在走 | 量不到 | 截图通路陈旧 → **重测**，不拿这个读数当结论 |
    | 不动 | 读不到 | —— | 说不清 → **重测** |

    重测 [tries] 轮仍停在"说不清"那一格时返回 `None`。调用方要把 `None` 记成**失败**
    （"读不到是常态，不能当成通过"），但说明里必须写清是**量具没能定案**，
    而不是"应用坏了" —— 这两句话给用户的信息完全不同。
    """
    xs, st, tried = None, None, 0
    while tried < tries:
        tried += 1
        rows = burst(prefix, count)
        xs = centroids(rows)
        st = motion_stats(xs)
        bars = sum(1 for _, a in rows if a is not None and controls_visible(a))
        W.log(f"    第 {tried} 次连拍：{motion_text(st)}"
              f"（{bars}/{len(rows)} 帧里控制条在）")
        if moving(st):
            return True, xs, f"画面在动 —— {motion_text(st)}"

        # 画面没动。**不能就此判否**：先问另外两个测量。
        #
        # 控制条收起时 a11y 树里根本没有那个 `m:ss`，所以先把它叫回来。
        # 用 `reveal_controls()` 的**返回值**读时钟，而不是再 dump 一次：
        # 那一次 dump 的树里按钮和时钟都在，多 dump 一次只会给控制条
        # 那 3.5 秒的自动收起留出空隙（两次 dump 加起来就超过 3.5 秒了）。
        #
        # 两次读数之间自然隔着一次 dump（2~4 秒），远大于时钟 1 秒的显示精度，
        # 所以"两次读到同一个值"足以说明它没在走 —— 不需要额外 sleep。
        ns0 = reveal_controls()
        ns1 = reveal_controls()
        c0, c1 = clock_of(ns0), clock_of(ns1)
        ended = at_video_end(ns0) or at_video_end(ns1)
        verdict, why = settle_verdict(False, bars, c0, c1, motion_text(st), ended)
        if verdict == "retry":
            W.log(f"    ⚠ {why} —— 重测")
            continue
        if verdict in ("frozen", "ended"):
            W.log(f"    ⚠ {why}")
        else:
            W.log(f"    {why}")
        return False, xs, why

    return None, xs, (f"连测 {tries} 次都是'白块没动'，而应用时钟始终读不到，"
                      f"没法交叉验证 —— 量具没能定案，按失败记录")


def settle_verdict(pixel_moving, bars, c0, c1, pixel_text="白块没动", at_end=False):
    """三条读数 → `(判定, 说明)`。

    判定 ∈ {"moving", "retry", "stopped", "frozen", "ended"}。

    ## 判定表（就是 `motion_settled` 里那张，抽出来是为了**能被自测**）

    | 白块位置 | 应用时钟 | 控制条 | 判定 |
    |---|---|---|---|
    | 在动 | —— | —— | `moving` |
    | 不动 | 位置已到片尾 | —— | `ended` —— **影片正常播完**，不是坏了 |
    | 不动 | 也不动 | —— | `stopped` —— 两个独立测量一致，确实没在播 |
    | 不动 | 在走 | 量得到 | `frozen` —— 截图通路是活的，只有影片那块不动 |
    | 不动 | 在走 | 量不到 | `retry` —— 截图通路本身陈旧，这个读数作废 |
    | 不动 | 读不到 | —— | `retry` —— 说不清 |

    抽成纯函数不是为了让代码好看：这条判据的五个分支里有四个是**我在真机上
    撞过现场才写出来的**，而它们各自对应一句给用户完全不同的话
    （"真的停"、"画面冻住了"、"影片播完了"、"量具没量到"）。只有让它能被自测
    逐一钉住，以后才不会有人顺手把 `retry` 合并进 `stopped` ——
    那等于把量具的锅扣给应用。

    ## `ended` 为什么必须单列

    真机上撞到过（2026-09-18 `_probe_pause.py duel`）：影片播到片尾之后
    **画面当然不再变化**，而那一刻 a11y 树里可能一个时间都读不到
    （控制条被探针自己的点击收起了，只剩画面正中那个「播放」键）。
    没有这一支，这件事会被判成 `retry` → 用完重试 → 记红，
    而红出来的那句话是"量具没能定案"，看的人只会去查应用。
    """
    if pixel_moving:
        return "moving", ""
    if at_end:
        return "ended", (f"控制条显示位置已经到片尾（{c0} = 总时长 {c1}）"
                         f" —— 画面不动是因为**影片播完了**，不是读取器坏了；"
                         f"这一步没验到要验的东西（用例没安排好）")
    if c0 is None or c1 is None:
        return "retry", "应用时钟读不到，说不出是截图陈旧还是真的停"
    if c0 != c1:
        if bars == 0:
            return "retry", (f"应用时钟在走（{c0} → {c1}）而白块没动，且这批帧里"
                             f"控制条一次都没出现 —— 截图通路本身是陈旧的")
        return "frozen", (f"{pixel_text}，但应用自己报的播放位置在走"
                          f"（{c0} → {c1}）、控制条也量得到（{bars} 帧）"
                          f" —— 截图通路是活的，只有影片那块不动：画面真的冻住了")
    return "stopped", (f"{pixel_text}，应用自己报的播放位置也停在 {c0}"
                       f" —— 两个独立测量一致，确实没在播")


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


# 控制条所在的那条横带（屏幕坐标）。取 2100..2400 是因为控制条贴屏幕底边：
# 实测横屏那一轮 SeekBar 的节点是 y[2205,2321]、时间文字在它同一行。
CONTROLS_BAND_Y0, CONTROLS_BAND_Y1 = 2100, 2400

# 控制条"在不在"的亮像素阈值（只看上面那条带里的**非纯黑**像素个数）。
#
# 2026-09-18 拿 `dist/evidence/v112-release/` 那批现场帧标出来的，读数分得很开：
#
#     控制条已收起 → 2828（就是系统手势条那点像素，恒定）
#     控制条已展开 → 34413 / 34429 / 34900 / 34942 / 35390（`19-R-横屏-已暂停` 等）
#
# 阈值取两侧的**几何中点**（√(2828×34413) ≈ 9865 取整到 10000），
# 这样两边的相对余量一样大：34413/10000 = 3.4 倍、10000/2828 = 3.5 倍。
#
# 第一次取的是 5000（"离收起 1.8 倍"），被自测当场拦下 ——
# `tools/motion/selftest_motion_settled.py` 的 D 段要求两侧各有 3 倍余量。
# 留 3 倍不是洁癖：这条读数只在"两个主测量打架"时才被问到，
# 而它一旦答错方向，量具就会把陈旧读数判成"画面真的冻住了" —— 把锅扣给应用。
CONTROLS_PIXELS = 10000

# 这一帧"是不是看片页"的门槛：整幅的**强白**像素个数上限。
#
# 看片页是黑底 + 一条影片，实测强白只有 19110~31195（`B-横屏-播放中-*`、
# `19-R-横屏-已暂停`、`20-R-横屏-拖到30%`）；而媒体库、空库、选图器那类页面
# 整屏都是浅色，强白 1.58M~2.55M。两者差两个数量级，取 150000 居中。
#
# 为什么要这道闸门：底下那个"控制条在不在"的判据在**非看片页**上会**因为错误的
# 原因**为真 —— 媒体库下半屏本来就亮着（实测 324000），会被读成"控制条在"。
# 一个能因为别的原因成立的条件，不能用来当"截图通路是活的"的证据。
VIEWER_MAX_BRIGHT = 150000


def controls_visible(a):
    """这一帧里**控制条**在不在。用来证明"截图通路此刻是活的"。

    ## 它是第三个独立测量，补的正是前两个都补不上的那个洞

    前两个测量是"影片里白块的位置"和"应用自己报的播放位置（`m:ss`）"。
    当它们**打架**（时钟在走、白块不动）时，有两种完全不同的可能，
    只看这两个永远分不开：

    1. **截图通路陈旧** —— 报出来的那张图是一两秒前的旧帧，所以"没动"是假的。
       这是**量具的锅**，不该判用户的应用失败。
    2. **画面真的冻住了** —— 截图通路是活的、只有影片那块不动。
       这是**应用或宿主机侧的事**，必须如实报红。

    控制条提供了一个跟影片内容无关、但同样"只在屏幕上"的读数：
    它由 Compose 直接画在窗口图层上，**不经过影片的 Surface**，却要经过同一条
    截图通路。所以"轻点画面 → 控制条出现 → 这一帧里量得到它"就证明了
    截图通路当下是活的；在这种前提下影片那块仍然不动，才是真的冻住。

    ## 为什么用"非纯黑像素个数"而不是找某个控件

    控制条收起时屏幕下半部是**纯黑**，只剩系统手势条那 2828 个像素；
    展开时会多出进度条与时间文字，读数直接跳到三万四以上（实测值见上面常量）。
    两者相差一个数量级，不需要认控件形状，也就不会被布局微调弄坏。

    ## 先确认这一帧是看片页，再说控制条的事

    见 [VIEWER_MAX_BRIGHT]：不看这一步，媒体库那类页面下半屏本来就是亮的，
    会被读成"控制条在" —— 那是**因为错误的原因**为真，不能当证据用。
    """
    if int((a > 120).all(axis=2).sum()) > VIEWER_MAX_BRIGHT:
        return False
    sub = a[CONTROLS_BAND_Y0:CONTROLS_BAND_Y1, :, :].astype(np.int16)
    return int(((sub > 60).any(axis=2)).sum()) > CONTROLS_PIXELS


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

def is_debuggable():
    """`run-as` 只对 debuggable 的包开放 —— 由此判断沙箱类判据做不做得了。

    2026-09-17 在 release 包上实测：`run-as` 被系统拒绝
    （`package com.fpb.vault is not debuggable`），[attachments] 于是**恒返回空**。
    同一轮里页头「· 2 项」、时长角标、播放几何全过，唯独三条沙箱判据报红；
    而"删除后密文消失"那条反而**恒绿**（它的断言就是 `len(files) == 0`）。

    恒绿比恒红危险 —— 所以沙箱类判据一律先过这道闸，判不了就报「不适用」。
    """
    out = sh("shell", "run-as", PKG, "id")
    return "uid=" in out and "not debuggable" not in out


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
    （横屏 10 秒确实比竖屏 8 秒短）。横屏加到 60 秒、后来又加到 120 秒之后大小关系反转，
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
        _, im = grab("_picker_probe")
        blobs = picker_red_blobs(im)
        if grid_filled(im):
            # **格子必须重新 dump 一次，不能复用上面那份 [ns]。**
            #
            # ns 是"截图**之前**"的快照，而这里的判定用的是截图**之后**的像素。
            # 抽屉刚把网格铺上来的那一瞬，两者会不一致：像素说"有图了"、
            # 节点树里还一个格子都没有 —— 于是报出"网格已加载、可勾格子 0 个"，
            # 而那时屏幕上明明有一整屏缩略图。
            #
            # 2026-09-17 实测（v1.0.9 release 包冷启动后第一次开选图器）：
            # 走到 `tap_one_photo` 才炸在"选图器里没有非红的格子（可勾 0、红 0）"。
            # 事后对同一屏取证的节点树是 9 个可点无文案的格子、y 落在 [923,2003]，
            # **完全满足 [picker_cells] 的判据** —— 说明不是判据太严，是那份快照太旧。
            # 这与坑 13（`dump` 是几秒前的快照）是同一个根因，只是这次它咬的是
            # "用新像素下结论、却用旧节点取数据"这个组合。
            cells = picker_cells(rnodes())
            if not cells:
                # 像素说"有图了"、节点树里却一个格子都没有 —— 两者不一致时
                # **不要就这么返回**：上层拿到的会是一个空列表，然后炸在
                # "找不到非红的格子 / 找不到可点的格子"之类的地方，
                # 看起来像产品问题，实际是这一轮的取样还没对齐。
                W.log(f"    第 {rounds} 轮：像素判定网格已加载，但节点树里还没有可勾格子，继续等")
                time.sleep(1.5)
                continue
            W.log(f"    选图器网格已加载（等了 {time.time() - started:.0f}s / {rounds} 轮）："
                  f"可勾格子 {len(cells)} 个、红色缩略图 {len(blobs)} 个")
            if os.path.exists(probe):
                os.replace(probe, keep)
            return cells, blobs, im
        # 还没铺满，留一份快照供超时路径诊断用（正常路径不会用到它）
        cells = picker_cells(ns)
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

    ## 还要按尺寸滤一道（第三处竞态的产物）

    抽屉样式的 picker（v1.0.9 那条路走的就是它）在网格**上方**还有一层自己的表头：
    拖拽条 + `Photos`/`Collections` 分段 + 溢出菜单。那几个控件同样"可点、无文案"，
    而且 y 落在同一个区间里（实测约 [601,855]）—— 只按 y 分段会把它们当成格子。
    2026-09-17 实测：`cand[0]` 取到了最上面那个 **126×127** 的表头控件，
    点上去当然不会出现"完成"按钮，而报出来的是"选中一张照片之后没有出现确认按钮"。

    缩略图是接近正方形的：1080 宽屏三列时约 **358×358**；表头控件只有 126 px 上下。
    取屏宽的 1/4（=270）作门槛，两边都有余量。
    """
    floor = VIEW_W // 4
    return [n for n in ns if n["clickable"] and not (n["text"] or n["desc"])
            and 300 < n["y1"] and n["y2"] < 2150
            and n["w"] >= floor and n["h"] >= floor]


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

    ## 这条路断过：两个时间尺度刚好撞在边上（2026-09-18）

    上一版是"dump 只问一次按钮在哪 → 点一下 → 用截图确认停住"。它在 v110/v111
    那几轮是绿的，2026-09-18 的 release 验收却连红 6 条。现场读数：

        第 1 次点击后：4 个读数 → 7.7 px/s（还在播）
        第 2 次点击后：4 个读数 → 0.0 px/s
        （按钮已收起，本次只有像素一个测量 — 未能交叉验证）
        暂停后连拍（4 帧）：8.5 px/s → 全速

    根因是**两个时间尺度撞在一起**，而且撞在临界点上：

      · 控制条在播放中**只在轻点之后亮 [CONTROLS_LIFE_S] 秒**（app 侧
        `CONTROLS_TIMEOUT_MS`，见 ImageViewerScreen）；
      · 定位按钮要 `uiautomator dump`，**2~4 秒**（随模拟器负载浮动）。

    于是"dump 出来的落点还有没有效"完全看 dump 有多快。两次实测构成对照：

      · v111 那轮 dump 用 **2 秒** → 叫出来 + 2 秒 = 3.0 < 3.5 → **点中了**；
      · audit5 这轮 dump 用 **4 秒** → 5.0 > 3.5 → **点空了**，而落空的那一下打在
        外层 Box 上（轻点画面是 toggle），只是把控制条又打开一次。

    更隐蔽的是：**真正的按钮点击永远轮不到**。上一次点击刚把控制条打开，
    下一次点击已经隔了 4 秒（那时它又收起了）—— 这正是"点了三下、一下没中"的来源。
    这不是运气问题，是结构性竞态。

    2026-09-18 用短间隔对照实验在真机上把这两件事分开了（`tools/_probe_pause.py`）：
    轻点叫出控制条后 0.6 秒内点按钮坐标，画面**当场停住**、按钮翻成「播放」、
    画面正中还多出那个播放键 —— 坐标是对的、应用也是好的，坏的是落点的时效。

    ## 现在的写法：同一个坐标连点两下，再用按钮的字判定

    坐标一旦拿到就不会变（布局固定），所以定位只做一次。之后每轮：

    1. **连点两下**（间隔 [TAP_GAP_S]，远小于控制条的存活时间）：
       · 控制条**已收起** → 第一下是 toggle（把它叫出来），第二下落在按钮上 → 暂停；
       · 控制条**亮着**   → 第一下就落在按钮上（暂停），第二下会把影片又播起来。
    2. **dump 一次，看按钮的字**。两种起始状态各自收敛，最坏多花一轮。

    第二种情况说明"连点"不是无条件的正确姿势 —— 所以**不拿"点过了"当结论**。
    连点间隔与存活时间的耦合写成了一条断言（见函数体开头），改其中一个数会当场炸，
    而不是留到走查里变成"点了没反应"。

    ## 为什么不再用像素判"停住"

    上一版把"位移是 0"当成暂停成功的证据，而**同一段里 3 秒后的 4 帧读数又是全速** ——
    同一次走查给出了两个相反的像素结论（见 `dist/evidence/audit5/README.md`）。
    像素在这一步只能证伪、不能证成：4 帧全同既可来自"真的停了"，也可来自一次瞬时卡顿，
    它没有"按钮翻了字"这个维度。

    所以分工是：

      · **这里**只负责「让它停下」，判据是**节点树**（按钮从「暂停」翻成「播放」）；
      · **像素**负责「解码器有没有真的停」，由调用方在暂停之后再连拍一次来判。

    两个测量各自独立、缺一不可：界面状态翻了而解码器没停，调用方那条会红。

    ## 读不到按钮 **不等于** 暂停成功

    暂停之后控制条**常驻**，所以"读不到按钮"只说明这次 dump 没拿到（窗口动画期、
    或 dump 失败），**不能**当成通过。上一版留了一个口子 —— 读不到按钮就记一句
    "未能交叉验证"然后照常返回 —— 而"读不到"恰恰是常态，那条防线等于不设防。
    现在读不到就再来一轮。

    ## `before` 为什么取 4 帧

    2026-09-17 那次假 FAIL 逼出来的读数（当时夹具还是 60 秒那版，速度真值 16.7 px/s）：

        暂停前快测（2 帧）：位移 6.9px / 0.86s → 8.0 px/s（门槛 8）→ 判为"没在动"
        同一轮同一段影片的另外三处（5~8 帧）：16.6 / 16.9 / 17.3 px/s

    画面**确实在播**，而 2 帧的估计把它算低了一半：`motion_stats` 的位移取
    `max - min`，样本只有 2 个时任何一个质心读偏，整段位移就跟着偏 —— 没有第三个点
    把它拉回来。`before` 只测画面、不读节点，没有"必须落在控制条那 3.5 秒里"的约束，
    所以可以多花点时间换准确度。

    它现在跑在 [reveal_controls] **之后**（因为"在不在播"要跟按钮的字对起来看），
    但那不影响它 —— [reveal_controls] 只轻点画面开关控制条，不动播放状态。
    `BEFORE_TRIES` 次都读不到运动就当作"这一组结论不成立"，见函数体里的说明。
    """
    # 连点间隔与"控制条活多久"是一对耦合的数：间隔必须远小于存活时间。
    # 写成断言是为了改其中一个数时当场炸，而不是留到走查里变成"点了没反应"。
    assert TAP_GAP_S < CONTROLS_LIFE_S / 2, \
        f"连点间隔 {TAP_GAP_S}s 太接近控制条存活时间 {CONTROLS_LIFE_S}s"

    # ---- 先确认"此刻真的在播"，并且让**画面**留下一条活的读数 ----
    #
    # 主判据是按钮的字：「暂停」= 应用认为自己在播。视频播完时按钮是「播放」，
    # 那时下面"点完变成播放"那条断言会自己满足自己 —— 这正是这个前提要挡住的。
    ns = reveal_controls()
    hit = next((n for n in ns if n["desc"] == "暂停"), None)
    if hit is None:
        if any(n["desc"] == "播放" for n in ns):
            W.log("    控制条上已经是「播放」—— 影片本来就没在播")
        else:
            W.log("    ⚠ 控制条始终读不到，连按钮位置都拿不到，无法执行暂停")
        return ns, False

    # 画面也必须留下一条**活的**读数 —— 后面"点完停住"用的就是画面。
    #
    # 画面有一个已知的失效模式：`screencap` 会返回一两秒前的陈旧帧。
    # 2026-09-18 实测到最极端的一次（`_probe_pause.py track`）：应用时钟已经走到
    # 约 0:52，屏幕上还是**片内第 0.97 秒**那一帧，连测两组 4 帧都是同一个质心；
    # 下一个读数"啪"地跳回 454px（≈54 秒处）。陈旧只持续一两秒，所以给它几次机会。
    #
    # 不重试的后果很具体：若画面本来就是死的，后面"停住"会拿一张死画面当证据，
    # 那条判据就成了假通过 —— 而它正是这个走查里唯一能看出"解码器没停"的地方。
    was_moving = False
    for attempt in range(1, BEFORE_TRIES + 1):
        before = motion_probe(4)
        was_moving = moving(before)
        W.log(f"    暂停前快测（第 {attempt} 次）：{motion_text(before)}"
              f" → {'在播' if was_moving else '没量到运动'}")
        if was_moving:
            break
    if not was_moving:
        W.log(f"    ⚠ 按钮显示「暂停」（应用认为在播），画面却连测 {BEFORE_TRIES} 次都没动 ——"
              " 两个测量矛盾，这一组的结论不成立")
        return ns, False

    # ---- 唯一的一条路：点控制条上的「暂停」按钮 ----
    #
    # 这里原先还有一条排在更前面的"媒体键"捷径，2026-09-18 删掉了。
    # 它在这个应用上**机制上不可能成功**：媒体按键要被投递到 `MediaSession` 才有目标，
    # 而全工程 `grep MediaSession|onKeyEvent` 零命中、`build.gradle.kts` 里零 media 依赖，
    # 播放器是裸 `android.media.MediaPlayer` + `SurfaceView`。
    #
    # 真正有害的不是它不生效，而是它**看起来生效了**：它靠一次像素读数确认"停住了"，
    # 而像素读数会陈旧（见上）—— 于是流程认定成功、直接返回，
    # **再也没走下面这条本来能用的路**，而那之后正式测量（4 帧）读到的是全速播放。
    coords = (hit["cx"], hit["cy"])
    W.log(f"    「暂停」键在 {coords}；落点固定（布局不会变），之后只点这个坐标")

    for round_no in range(1, MAX_PAUSE_ROUNDS + 1):
        # 连点两下：第一下若落在已收起的控制条上就是"把它叫出来"，
        # 第二下才落在按钮上；若控制条本来就亮着，第一下就中。
        W.tap(*coords)
        time.sleep(TAP_GAP_S)
        W.tap(*coords)
        time.sleep(0.6)
        ns2 = rnodes()
        descs = [n["desc"] for n in ns2 if n["desc"] in ("播放", "暂停")]

        # 判定只看**按钮的字**，不看"点过了"：
        #   · 暂停后画面正中会多出一个播放键，加上控制条上那个，通常是两个「播放」；
        #   · 还在播时控制条上是「暂停」，没有任何「播放」。
        if "暂停" in descs:
            W.log(f"    第 {round_no} 轮后按钮仍是「暂停」（{descs}）—— 还在播，再来一轮")
            continue
        if not descs:
            # 读不到按钮 **不是** 成功。暂停之后控制条常驻，读不到只说明这次 dump
            # 没拿到（窗口动画期、或 dump 失败）—— 上一版就是在这里放行的。
            W.log(f"    第 {round_no} 轮后读不到播放/暂停按钮 —— 无法确认，再来一轮")
            continue
        W.log(f"    第 {round_no} 轮后按钮变成「播放」：{descs}")
        return ns2, was_moving

    W.log(f"    ⚠ 连试 {MAX_PAUSE_ROUNDS} 轮都没能让按钮翻成「播放」")
    return rnodes(), False


def clocks_of(ns):
    """节点树里所有 `m:ss` 文本，按树里的先后顺序。控制条上是 `[位置, 总时长]`。

    拆出"全部"而不只是"第一个"，是为了能判**影片是不是已经播完**：
    位置 == 总时长就是播到了片尾。这件事必须能判出来 ——
    真机上撞到过（2026-09-18，`_probe_pause.py duel`）：影片播完后画面自然不再变化，
    而那一刻 a11y 树里可能一个时间都读不到（控制条被探针自己的点击收起了，
    只剩画面正中那个「播放」键），于是旧判据会把**正常播完**记成"读取器坏了"。
    """
    out = []
    for n in ns:
        t = (n["text"] or "").strip()
        if re.fullmatch(r"\d+:\d\d", t):
            out.append(t)
    return out


def clock_of(ns):
    """节点树里那个 `m:ss` 播放位置；读不到返回 None。

    它是唯一直接读**播放器本体**的测量：界面上的位置来自
    `MediaPlayer.currentPosition`，每 250ms 轮询一次（app 侧 `POSITION_POLL_MS`）。
    另外两个测量（影片白块的位置、控制条在不在）读的都是**屏幕上是什么**，
    而这个读的是**播放器认为自己在哪** —— 两者正好是"画面冻住"这件事的两端，
    所以它们打架的时候能定案。见 `motion_settled` 的判定表。
    """
    cs = clocks_of(ns)
    return cs[0] if cs else None


def at_video_end(ns):
    """控制条上的位置是不是已经到片尾（位置 == 总时长，且总时长不为 0）。

    读不全返回 False —— "判不出来"不能当成"已经播完"，否则一条正常的失败
    会被这句话解释掉（那正是这个项目里最不能容忍的一类假绿）。
    """
    cs = clocks_of(ns)
    if len(cs) < 2:
        return False
    return cs[1] != "0:00" and cs[0] == cs[1]


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
    而时长是从容器里读出来的**事实** —— 2:00 的那段就是横屏那一段。
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
    uninstall_pkg()
    install_apk(APK_OLD)
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
    # 这一轮的包**可能是 release 包**（验收脚本会把 APK_NEW 指过去），那时
    # `run-as` 会被系统拒绝，沙箱类判据就做不成 —— 先问一次，
    # 让下面那些判据自己决定"验"还是"报不适用"。
    # 日志里原来写死"（debug）"，指到 release 包时就成了名实不符，一并去掉。
    W.log("================ 验证轮 · 本次实现版 ================")
    W.log(f"安装 {APK_NEW}")
    uninstall_pkg()
    install_apk(APK_NEW)
    W.log(f"    设备上装的是 {installed_version()}")

    # 沙箱类判据做不做得了，取决于**刚装上的这个包**。
    # 2026-09-17：这一句原来写在安装**之前**，问的是上一个包 —— 于是在 debug 包上
    # 单独跑这一轮时，四条沙箱判据全被报成「不适用」，而它们本可以做。
    # （在验收脚本里因为上一个包正好是 release，结论凑巧对了，所以一直没暴露。）
    # 判"能不能"的闸门必须设在"装完之后"。
    SANDBOX_OK = is_debuggable()
    W.log("    沙箱类判据：" + ("验" if SANDBOX_OK else
                              "报「不适用」（这个包不可调试，run-as 被系统拒绝）"))

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
    #
    # 这一段要靠 `run-as` 进沙箱读密文，而 release 包（`android:debuggable=false`）
    # 上 `run-as` 被系统拒绝 —— 判不了就明确报「不适用」，绝不留下恒真的绿。
    if SANDBOX_OK:
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
    else:
        for _label in ("沙箱里确实落盘了两个附件密文",
                       "每个附件的落盘体积都能唯一对上某一段素材",
                       "两段影片各自对上自己的素材（不是互相配错）",
                       "至少有一段素材在设备上落成多块（跨块读取才真的被走过）"):
            skip(_label, "release 包不可调试，run-as 被系统拒绝 —— "
                         "这一组在 debug 包那轮验过（见 v110-video 走查证据）")

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

        # 运动读数：连拍帧之间白块位置必须变。
        #
        # **不复用**上面那批帧了：那批只连拍一次，而单次连拍有个已知的失效模式
        # （`screencap` 返回一两秒前的旧帧 → 一整批看起来都是静止图）。
        # 改走 [motion_settled]：它在"像素说没动"时会拿应用时钟与控制条交叉验证，
        # 必要时重测，而不是把量具的陈旧读数当成"用户的应用停在第一帧"。
        # 上面那批帧照旧供条带、正方形两个几何判据使用，一行都不用改。
        resumed0, xs, why0 = motion_settled("B-横屏-播放中", BURST_PLAY)
        W.log(f"    连拍各帧白块质心：{xs['points']}")
        check("连拍帧之间白块位置在变 → 画面确实在播（不是停在第一帧的静止图）",
              resumed0 is True, why0)

    # ---- 5. 控制条：先把影片停下，再读数 ----
    #
    # 这一段整体是"暂停优先"的（原因见 tap_pause 的注释）：控制条在播放中 3.5 秒
    # 就自己收起来，而轻点画面是 toggle —— 播放态下读数只能靠运气。
    # 停下之后控制条常驻，时间、总时长、进度条、拖动才都是可重复的读数。
    ns, did_pause = tap_pause()

    # 这一条是下面那一组的**前提**：影片若已播完，按钮本来就是「播放」，
    # 于是"点完暂停后按钮变成播放"无论点不点都成立。前提不成立时要响，不要绿。
    #
    # 判据由两个测量合成（见 [tap_pause]）：控制条上的字说「暂停」= 应用认为自己在播，
    # 且**画面确实在动** —— 后者是为了让下面"点完停住"那条有资格成立：
    # 拿一张本来就死的画面去证明"停住了"，等于没证明。
    check("点暂停之前确实处于播放态（否则这一组判据会被「播完」顶成假通过）",
          did_pause,
          "按钮显示「暂停」，且画面确实在动（两个测量一致）" if did_pause
          else "按钮与画面没能同时证明「正在播放」—— 这一组的结论不成立")

    paused = next((n for n in ns if n["desc"] == "播放"), None)
    check("点暂停后按钮变成「播放」（状态真的切了）", paused is not None,
          f"节点 desc {[n['desc'] for n in ns if n['desc'] in ('播放', '暂停')]}")

    # 第三个独立测量：**应用自己报的播放位置**。
    #
    # 像素那个测量有一个已知的失效模式（`screencap` 会返回一两秒前的陈旧帧，
    # 见 [tap_pause] 里那组实测）。陈旧一旦"和上一帧一样"，看起来就是"停住了" ——
    # 而那正是最危险的方向：**解码器还在跑、界面说停**，像素看不出来。
    # 时钟看得出来，而且它不走像素这条路：`positionMs` 每 250ms 从
    # `MediaPlayer.currentPosition` 读一次，播放器只要还在走，这个数就会变。
    #
    # 比的是"两次读数是否相同"，所以不受文案格式影响。2 秒足够 ——
    # 显示精度是 1 秒，播放中 2 秒必然跨过至少一个整秒。
    clock_before = clock_of(ns)
    time.sleep(CLOCK_SETTLE_S)
    clock_after = clock_of(rnodes())
    check("暂停后应用自己报的播放位置不再前进（第三个独立测量：像素会陈旧，时钟不会）",
          clock_before is not None and clock_before == clock_after,
          f"{clock_before} → {clock_after}")

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
    # 那一段要留足剩余片长（120 秒的 30% 处还剩 84 秒）。
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
    # 三条读数合起来定案（白块位置 + 应用时钟 + 控制条），**不能只看白块位置**：
    # `screencap` 会返回旧帧，"连续几帧质心相同"于是既可能是"真的停"
    # 也可能是"一帧没换"。见 [motion_settled] 里那张判定表。
    resumed, xs, why = motion_settled("B-横屏-再播", BURST_PAUSE + 2)
    W.log(f"    恢复播放后各帧白块质心：{xs['points']}")
    # 这一项还要给 5.5 段当前提：Surface 重建后播放器只在**播放中**才重绘，
    # 暂停态重建本来就该是黑的（那是播放器的正常行为，不是缺陷）。
    check("点「播放」后画面重新动起来（可暂停、可恢复）", resumed is True, why)

    # 切走前画面在第几秒。**必须在这里取**：这是 5.6 段那条判据的对照值，
    # 而下一段就要按 HOME 了 —— 之后再想量"切走前"已经不可能。
    # 取连拍的**最后一帧**：它离按 HOME 最近。
    before_secs = implied_secs(xs["points"][-1][1], xs["band_w"], xs["block_w"]) \
        if xs["points"] else None

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
          resumed is True,
          "播放中" if resumed is True else f"上一步没量到运动，这一段的结论无效（{why}）")

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

    # ---- 5.6 切后台回来，**位置**不能丢 ----
    #
    # 「画面还在动」和「从我离开的地方接着播」是两件事，必须各有一条判据。
    # 2026-09-17 之前这里只有前者，而它一直是 PASS 的 —— 用户回来后影片却是从
    # 第 0 秒开始的。两次现场（同一份包，`v110-release.run1` / `.run2`）：
    # 切走前分别在第 62.8 / 40.5 秒，回来第一批帧落在第 0.9~13.8 / 0.0 秒。
    #
    # ## 判据的取样点必须紧贴这批帧
    #
    # 用的是**上面这 4 张**，不是后面"返回后重播"那 5 张。后者要等 `reveal_controls`
    # 走完（模拟器一慢就是几十秒），而位置一旦被复位、画面从 0 重播几十秒后早就又
    # **反超**了切走前的位置 —— 拿那批帧判断会把缺陷放过。这不是假想：
    # `run1` 里切走前 62.8s、回来第一批 0.9~13.8s（位置已丢），
    # 而 45 秒后的那批已经是 70.9~87.1s（看起来"接上了"，其实是重播追上的）。
    #
    # 量的是**画面**而不是控制条上那个 `m:ss`：后者是应用自己的说法，而那次冻结的
    # 现场就是"界面说 0:52、画面贴在 0 秒"—— 两个读数打架时以画面为准。
    xs_back = centroids(rows)
    W.log(f"    回来那一刻各帧白块质心：{xs_back['points']}")
    after_secs = [implied_secs(cx, xs_back["band_w"], xs_back["block_w"])
                  for _, cx in xs_back["points"]]
    after_secs = [s for s in after_secs if s is not None]
    verdict, why = position_keep_verdict(before_secs, after_secs)
    if verdict == "skip":
        skip("切后台回来没有从头重播", why)
    else:
        check("切后台回来没有从头重播（从我离开的地方接着播）",
              verdict == "pass", why)
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
    resumed2, xs2, why2 = motion_settled("B-横屏-返回后重播", BURST_PAUSE + 1)
    W.log(f"    返回后重播各帧白块质心：{xs2['points']}")
    check("返回后还能重新播放（画面真的在动，说明读取器仍可用）",
          resumed2 is True, why2)

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

    if SANDBOX_OK:
        files = attachments()
        check("删除记录后视频密文也一起消失（不是只删了记录）",
              len(files) == 0, f"attachments 里还剩 {list(files)}")
    else:
        # **这条原来是 `len(files) == 0`，在 release 包上恒真**：run-as 被拒 →
        # files 永远是空的 → 0 == 0 无条件成立。一条永远为真的断言等于没有断言，
        # 而且它比红的更危险（红至少会有人看）。2026-09-17 实测就是这样：
        # 同一轮里它绿着，旁边三条同源的判据红着。
        skip("删除记录后视频密文也一起消失（不是只删了记录）",
             "release 包不可调试，读不到沙箱（run-as 被拒）—— "
             "页头「· 0 项」已证明记录删了；密文一并消失这条在 debug 包那轮验过")


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
    if SKIPPED:
        # 单独说一段，措辞上就不能让人读成"通过了"。
        W.log(f"另有 {len(SKIPPED)} 条在这一轮**不适用**（既不算通过、也不算失败）：")
        for s in SKIPPED:
            print(f"    · {s}")
    if FAILURES:
        W.log(f"共 {len(FAILURES)} 条未通过：")
        for f in FAILURES:
            print(f"    · {f}")
        sys.exit(1)
    W.log(f"第 {phase} 轮全部通过"
          + (f"（另有 {len(SKIPPED)} 条不适用，见上）" if SKIPPED else ""))


if __name__ == "__main__":
    main()
