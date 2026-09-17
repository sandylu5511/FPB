#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第五轮补丁：探针截图不再删除，改写到独立暂存目录并反复覆盖。

## 为什么必须改

`motion_probe()` 用完就把两张探针截图删掉。结果整轮走查**在这里中止了**：

    [safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED] {"count":78,"threshold":50,
      "scope":"turn","targets":["...\\_probe-motion-00.png"],"targetCount":1}

桌面端有批量删除保护（阈值 50），它拦下这次删除并终止了进程 ——
而这一轮前面 26 项全 PASS、速度判据也刚刚打印出漂亮的 15.9 px/s，
却因为"清理两张临时图"这种与结论毫无关系的动作而断掉。

## 改成什么

1. 探针截图写到 `build/_probe/`，**不写进证据目录**（它本来就不是结论的证据）；
2. 用**固定文件名**，每次覆盖自己那两张 —— 文件数恒定，不需要任何删除；
3. 反正也不需要删：两张图加起来不到 200 KB，而且是进程内一次性的读数。

顺带把 `burst` 加一个 `out_dir` 参数（默认还是证据目录），
`motion_stats` 同理加 `out_dir` —— 因为探针图不再与证据图同目录，
按 basename 拼路径会找不到文件。
"""

import io
import sys

TARGET = r"D:\MixiaVault\tools\walkthrough-v110-video.py"

src = io.open(TARGET, encoding="utf-8").read()
orig = src
report = []


def replace_once(old, new, tag):
    global src
    n = src.count(old)
    if n != 1:
        sys.exit(f"[{tag}] 期望命中 1 处，实测 {n} 处 —— 未写盘")
    src = src.replace(old, new, 1)
    report.append(f"OK  {tag}")


# ---------- 1. 暂存目录常量 ----------

replace_once(
    'APK_NEW = rf"{BASE}\\app\\build\\outputs\\apk\\debug\\app-debug.apk"    # 本次实现版\n',
    'APK_NEW = rf"{BASE}\\app\\build\\outputs\\apk\\debug\\app-debug.apk"    # 本次实现版\n'
    "\n"
    "# 过程中的读数（`motion_probe` 的探针截图）落在这里，**不进证据目录**：\n"
    "# 它们不是结论的证据，只是「这一下暂停按下去没有」这种瞬时判断。\n"
    "# 而且**不做删除** —— 桌面端有批量删除保护（阈值 50），\n"
    "# 删两张临时图会把整轮走查打断；固定文件名反复覆盖，文件数恒定。\n"
    'PROBE_DIR = os.path.join(BASE, "build", "_probe")\n',
    "新增 PROBE_DIR",
)

# ---------- 2. burst 支持指定目录 ----------

replace_once(
    'def burst(prefix, count):\n'
    '    """连拍一串。**不 sleep**：一 sleep 就把采样点推出播放窗口了。\n'
    "\n"
    "    返回 [(路径, ndarray)]，读不出来的帧返回 None 并记一行日志。\n"
    '    """\n'
    "    rows = []\n"
    "    for i in range(count):\n"
    "        raw = subprocess.run([ADB, \"exec-out\", \"screencap\", \"-p\"],\n"
    "                             capture_output=True, timeout=60).stdout\n"
    '        path = os.path.join(OUT, f"{prefix}-{i:02d}.png")\n',
    'def burst(prefix, count, out_dir=None):\n'
    '    """连拍一串。**不 sleep**：一 sleep 就把采样点推出播放窗口了。\n'
    "\n"
    "    返回 [(路径, ndarray)]，读不出来的帧返回 None 并记一行日志。\n"
    "\n"
    "    [out_dir] 默认是证据目录。探针（[motion_probe]）会把它指到暂存目录去 ——\n"
    "    那些帧是过程读数，不该混进证据里。\n"
    '    """\n'
    "    out = out_dir or OUT\n"
    "    os.makedirs(out, exist_ok=True)\n"
    "    rows = []\n"
    "    for i in range(count):\n"
    "        raw = subprocess.run([ADB, \"exec-out\", \"screencap\", \"-p\"],\n"
    "                             capture_output=True, timeout=60).stdout\n"
    '        path = os.path.join(out, f"{prefix}-{i:02d}.png")\n',
    "burst 支持 out_dir",
)

# ---------- 3. motion_stats 支持指定目录 ----------

replace_once(
    "def motion_stats(xs):\n",
    "def motion_stats(xs, out_dir=None):\n",
    "motion_stats 签名",
)

replace_once(
    "    if len(xs) < 2:\n"
    "        return None\n"
    "    try:\n"
    "        t0 = os.path.getmtime(os.path.join(OUT, xs[0][0]))\n"
    "        t1 = os.path.getmtime(os.path.join(OUT, xs[-1][0]))\n",
    "    if len(xs) < 2:\n"
    "        return None\n"
    "    base = out_dir or OUT\n"
    "    try:\n"
    "        t0 = os.path.getmtime(os.path.join(base, xs[0][0]))\n"
    "        t1 = os.path.getmtime(os.path.join(base, xs[-1][0]))\n",
    "motion_stats 用 out_dir 取时间戳",
)

# ---------- 4. motion_probe 改成不删除 ----------

replace_once(
    '    rows = burst("_probe-motion", frames)\n'
    "    try:\n"
    "        return motion_stats(centroids(rows))\n"
    "    finally:\n"
    "        for p, _ in rows:\n"
    "            try:\n"
    "                os.remove(p)\n"
    "            except OSError:\n"
    "                pass\n",
    '    rows = burst("motion", frames, out_dir=PROBE_DIR)\n'
    "    return motion_stats(centroids(rows), out_dir=PROBE_DIR)\n",
    "motion_probe 不删除",
)

replace_once(
    "    与落进证据目录的连拍不同，这几张是**过程中的读数**，用完就删 ——\n"
    "    所以它只花 0.3~0.9 秒/张，能塞进控制条那 3.5 秒的窗口里。\n"
    "    这正是\"dump 读到「暂停」再点它\"做不到的事：那条路要 dump，而 dump 要 2~3 秒。\n",
    "    与落进证据目录的连拍不同，这几张是**过程中的读数**：写到 `build/_probe/`，\n"
    "    固定文件名反复覆盖，**不删除**（见 [PROBE_DIR] 的注释）——\n"
    "    所以它只花 0.3~0.9 秒/张，能塞进控制条那 3.5 秒的窗口里。\n"
    "    这正是\"dump 读到「暂停」再点它\"做不到的事：那条路要 dump，而 dump 要 2~3 秒。\n",
    "motion_probe 文档",
)

if "os.remove(p)" in src:
    sys.exit("还有删除探针截图的地方 —— 未写盘")
if 'out_dir=PROBE_DIR' not in src:
    sys.exit("PROBE_DIR 没接上 —— 未写盘")

io.open(TARGET, "w", encoding="utf-8", newline="\n").write(src)
print("\n".join(report))
print(f"\n写入 {TARGET}：{len(orig)} → {len(src)} 字符")
