#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第四轮补丁：修掉"拿几秒前的树去点"这个竞态，并把运动判据改成速度。

## 一、暂停为什么点不中（两次实测构成了对照）

控制条在**播放中只亮 3.5 秒**，而一次 `uiautomator dump` 要 2~3 秒。
于是"dump 读到「暂停」→ 点下去"这条路上，落点还在不在完全看 dump 有多快：

    dump 用 2.2 秒的那一轮 → 点中了（11:51 那次）
    dump 用 3.3 秒的那一轮 → 点空了（11:55 那次）

而点空的那一下打在外层 Box 上 —— 轻点画面是 toggle，于是控制条被重新打开，
表现就是"点了暂停，状态却没切"。更糟的是，**重试也救不了**：
每次重试都要先 dump（又 3 秒），落点又过期。这是个结构性的竞态，不是运气。

修法：dump **只用来问一次按钮在哪**（布局固定，坐标不会变），
之后"点坐标 → 用**截图**判断停了没有"，中间不再插入 dump。
`screencap` 只要 0.3~0.9 秒，整个动作落在控制条亮着的窗口里。

连点两下同一个坐标是安全的：第一次若落在已经收起的控制条上，那一下等于点了画面
（toggle），会把控制条**打开**；0.4 秒后的第二下就必然落在按钮上。

## 二、运动判据为什么不能用固定像素数

连拍两帧之间的间隔取决于 `screencap` 的耗时（0.3~0.9 秒，随模拟器负载浮动），
所以"5 帧位移 20px"与"8 帧位移 91px"说的是同一件事 —— 都在播。
按固定阈值判，前者会被误判成"没动"：B 轮就出过这条假 FAIL，
返回后重播那组恰好 20.0px，卡在 `> 20` 的门槛上。

改成**速度**：位移 ÷ 首末帧时间差。探针影片里白块的横移速度
= (画面宽 - 块宽) / 时长 ≈ 16.7 px/s，与模拟器快慢无关。
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


def replace_between(start_marker, end_marker, new, tag):
    global src
    if src.count(start_marker) != 1:
        sys.exit(f"[{tag}] 起点标记命中 {src.count(start_marker)} 处 —— 未写盘")
    if src.count(end_marker) != 1:
        sys.exit(f"[{tag}] 终点标记命中 {src.count(end_marker)} 处 —— 未写盘")
    a = src.index(start_marker)
    b = src.index(end_marker)
    if b <= a:
        sys.exit(f"[{tag}] 终点在起点之前 —— 未写盘")
    src = src[:a] + new + src[b:]
    report.append(f"OK  {tag}（替换 {b - a} 字符）")


# ---------- 1. 新增：质心序列、速度判据、快测 ----------

replace_once(
    "def grid_filled(a):\n",
    '''# 「在播」与「停住」的门槛，单位 px/s。探针影片里白块的横移真值约 16.7 px/s，
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


def motion_stats(xs):
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
    try:
        t0 = os.path.getmtime(os.path.join(OUT, xs[0][0]))
        t1 = os.path.getmtime(os.path.join(OUT, xs[-1][0]))
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

    与落进证据目录的连拍不同，这几张是**过程中的读数**，用完就删 ——
    所以它只花 0.3~0.9 秒/张，能塞进控制条那 3.5 秒的窗口里。
    这正是"dump 读到「暂停」再点它"做不到的事：那条路要 dump，而 dump 要 2~3 秒。

    量不到（帧读不出来、或画面里没有影片）返回 None。调用方必须把 None 当成
    "测不出来"，而不是"没在动" —— 这两件事的后果完全不同。
    """
    rows = burst("_probe-motion", frames)
    try:
        return motion_stats(centroids(rows))
    finally:
        for p, _ in rows:
            try:
                os.remove(p)
            except OSError:
                pass


def grid_filled(a):
''',
    "新增 质心/速度/快测",
)

# ---------- 2. 文档：把"dump 是几秒前的快照"这层写清楚 ----------

replace_once(
    "所以第 5 段整体改成**先把影片停下**：`tap_pause()` 每一轮只 dump 一次，\n"
    "拿到「暂停」就立刻点，中间不插入任何别的读写；暂停后控制条常驻，\n"
    "时间 / 总时长 / 进度条 / 拖动这些读数全部挪到那之后再取。\n",
    "所以第 5 段整体改成**先把影片停下**：暂停后控制条常驻，时间 / 总时长 /\n"
    "进度条 / 拖动这些读数全部挪到那之后再取。\n"
    "\n"
    "但「读到「暂停」就立刻点」**还不够** —— **dump 本身是几秒前的快照**。\n"
    "一次 `uiautomator dump` 要 2~3 秒，而控制条在播放中只亮 3.5 秒，\n"
    "于是落点还在不在完全看 dump 有多快。两次实测构成了对照：\n"
    "\n"
    "  · dump 用 **2.2 秒**的那一轮 → 点中了；\n"
    "  · dump 用 **3.3 秒**的那一轮 → **点空了**，而点空的那一下打在外层 Box 上\n"
    "    （轻点画面是 toggle），反而把控制条重新打开 —— 看起来就是「点了暂停没反应」。\n"
    "\n"
    "最终定下来的写法：**dump 只用来问一次按钮在哪**（布局固定，坐标不会变），\n"
    "之后「点坐标 → 用截图判断停了没有」，`screencap` 只要 0.3~0.9 秒，\n"
    "整个动作落在控制条亮着的窗口里。\n",
    "文档/补 dump 快照这一层",
)

# ---------- 3. tap_pause 重写 ----------

replace_between(
    "def tap_pause(rounds=6):\n",
    "def current_page():\n",
    '''def tap_pause():
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


''',
    "tap_pause 重写",
)

# ---------- 4. 段 4 的在播判据 ----------

replace_once(
    '        check("连拍帧之间白块位置在变 → 画面确实在播（不是停在第一帧的静止图）",\n'
    "              len(xs) >= 2 and max(x[1] for x in xs) - min(x[1] for x in xs) > 20,\n"
    '              f"{len(xs)} 个读数，极差 "\n'
    '              f"{(max(x[1] for x in xs) - min(x[1] for x in xs)) if xs else 0:.1f}px")\n',
    '        check("连拍帧之间白块位置在变 → 画面确实在播（不是停在第一帧的静止图）",\n'
    "              moving(motion_stats(xs)), motion_text(motion_stats(xs)))\n",
    "段 4/在播判据改速度",
)

# ---------- 5. 段 5：前提判据的详情 + 暂停后停住 ----------

replace_once(
    '    check("点暂停之前确实处于播放态（否则这一组判据会被「播完」顶成假通过）",\n'
    "          did_pause,\n"
    '          "读到「暂停」并点了下去" if did_pause\n'
    "          else f\"读到的是 {[n['desc'] for n in ns if n['desc'] in ('播放', '暂停')]}\")\n",
    '    check("点暂停之前确实处于播放态（否则这一组判据会被「播完」顶成假通过）",\n'
    "          did_pause,\n"
    '          "暂停前的快测量到画面在动（这是「正在播放」的定义）" if did_pause\n'
    '          else "暂停前没量到画面在动 —— 这一组的结论不成立")\n',
    "段 5/前提判据详情",
)

replace_between(
    '    rows = burst("B-横屏-暂停后", BURST_PAUSE)\n',
    '    W.shot("横屏-已暂停")\n',
    '''    rows = burst("B-横屏-暂停后", BURST_PAUSE)
    xs = centroids(rows)
    W.log(f"    暂停后各帧白块质心：{xs}")
    # 按钮变成「播放」只说明 Compose 的状态翻了，**解码器有没有停**才是画面上的事实。
    check("暂停后画面停住（白块位置不再变化）", still(motion_stats(xs)),
          motion_text(motion_stats(xs)))
''',
    "段 5/暂停后停住改速度",
)

# ---------- 6. 段 5：拖动之后先把控制条叫回来再读时间 ----------

replace_once(
    '        W.adb("shell", "input", "swipe", str(x_from), str(y), str(x_to), str(y), "700")\n'
    "        time.sleep(1.0)\n"
    "        after = rnodes()\n",
    '        W.adb("shell", "input", "swipe", str(x_from), str(y), str(x_to), str(y), "700")\n'
    "        time.sleep(1.0)\n"
    "        # **不能直接 dump**：控制条在播放中会自己收起，而拖动之后是否还在播，\n"
    "        # 取决于上一步有没有真的停下。先把控制条叫回来（已亮着的话它会立刻返回），\n"
    "        # 再读时间。B 轮就是因为没叫回来，这里读到一个空列表。\n"
    "        after = reveal_controls()\n",
    "段 5/拖动后叫回控制条",
)

# ---------- 7. 段 5：恢复播放 ----------

replace_between(
    "    # 再点播放：白块重新动起来。**优先落在控制条那个按钮上** —— 暂停态下画面正中\n",
    "    # ---- 5.5 切后台再回来：Surface 会重建 ----\n",
    '''    # 再点播放：白块重新动起来。**优先落在控制条那个按钮上** —— 暂停态下画面正中
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

''',
    "段 5/恢复播放改速度",
)

# ---------- 8. 段 5.5：返回后重播 ----------

replace_between(
    '    rows = burst("B-横屏-返回后重播", 5)\n',
    '    check("解码器被创建过（logcat）"',
    '''    rows = burst("B-横屏-返回后重播", 5)
    xs = centroids(rows)
    W.log(f"    返回后重播各帧白块质心：{xs}")
    check("返回后还能重新播放（画面真的在动，说明读取器仍可用）",
          moving(motion_stats(xs)), motion_text(motion_stats(xs)))

''',
    "段 5.5/返回后重播改速度",
)

if "max(x[1] for x in xs) - min(x[1] for x in xs) > 20" in src:
    sys.exit("还有按固定像素数判运动的写法 —— 未写盘")
if "def tap_pause(rounds=6)" in src:
    sys.exit("tap_pause 还是旧版 —— 未写盘")

io.open(TARGET, "w", encoding="utf-8", newline="\n").write(src)
print("\n".join(report))
print(f"\n写入 {TARGET}：{len(orig)} → {len(src)} 字符")
