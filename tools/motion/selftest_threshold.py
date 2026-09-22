#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自测「在播 / 停住」的门槛 —— **先证明旧写法会失败，再证明新写法不会**。

    python tools/motion/selftest_threshold.py

## 为什么门槛也需要自证

`tools/motion/selftest_measure.py` 证的是**几何**（条带、正方形比例）那批量算法
"如果画面被拉伸，它给出的数会变"。**门槛**是另一回事：它是"把量到的数分成两类"
的那条线，写错了不会影响任何一次单点量测，只会让整组判据**集体判错**。
2026-09-17 就是这样：门槛写死成 8.0 px/s，而夹具加长到 120 秒后真值掉到 8.3 ——
实测读数在门槛上抖，一半判成"没在动"，八个 FAIL 里没有一个能归到应用头上。

所以这里不问设备、只喂**当时真实的读数**，看两种写法各自给出什么结论：

  · 旧写法 `speed >= 8.0`      → 7.8 px/s 判"没在动"（这就是那八个 FAIL 的来源）
  · 新写法 `ratio >= 0.5`      → 8.3 / 8.0 / 11.0 / 7.8 四个全判"在播"

若哪天有人把门槛改回去，这个自测会立刻变红 —— 它记的不是"现在能过"，
而是**"上一版的错法必须过不了"**。
"""

import importlib.util
import os
import shutil
import sys
import tempfile

BASE = r"D:\MixiaVault"
FAIL = []


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V110 = load(rf"{BASE}\tools\walkthrough-v110-video.py", "v110")

# 夹具的两条事实，从 `_mkvideos.py` 的几何推出来（不是抄来的）：
#   bw   = max(16, 320 // 13) = 24      块宽（源像素）
#   span = 320 - 24          = 296      块走完的横向行程（源像素）
# 铺到 1080 宽的屏幕上缩放 1080/320 = 3.375：
#   条带宽 1080、块宽 81、行程 999
FIXTURE_BAND_W = 1080
FIXTURE_BLOCK_W = 81
FIXTURE_TRAVERSE_PX = FIXTURE_BAND_W - FIXTURE_BLOCK_W     # 999

# 跑那一轮时它们分别是 120 秒（本轮）与 60 秒（上一轮）。
# 真值 = 行程 / 片长 —— 片长翻倍、真值减半，这正是当初漏掉的那一步。
TRUTH_120S = FIXTURE_TRAVERSE_PX / 120.0
TRUTH_60S = FIXTURE_TRAVERSE_PX / 60.0


def check(label, ok, detail):
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}：{detail}")
    if not ok:
        FAIL.append(label)


def stats(tmp, name0, name1, cx0, cx1, dt,
          band_w=FIXTURE_BAND_W, block_w=FIXTURE_BLOCK_W, clip="landscape.mp4"):
    """拿两个**真实存在、时间戳可控**的文件伪造一次连拍，喂给 `motion_stats`。

    不能用假时间戳直接构造 —— `motion_stats` 的时间基准是文件 mtime，
    所以这里老老实实建两个空文件并用 `os.utime` 把间隔钉死。
    """
    p0, p1 = os.path.join(tmp, name0), os.path.join(tmp, name1)
    for p in (p0, p1):
        with open(p, "wb"):
            pass
    t = 1_700_000_000.0
    os.utime(p0, (t, t))
    os.utime(p1, (t + dt, t + dt))
    m = {"points": [(name0, cx0), (name1, cx1)], "band_w": band_w, "block_w": block_w}
    return V110.motion_stats(m, out_dir=tmp, clip=clip)


def old_moving(st, old_threshold=8.0):
    """**修复前**的判据，原样搬过来当对照 —— 它就是那八个 FAIL 的成因。"""
    return st is not None and st["speed"] >= old_threshold


def main():
    print("=" * 74)
    print("门槛自测：先证明旧写法会失败")
    print("=" * 74)

    tmp = tempfile.mkdtemp(prefix="fpb_threshold_")

    print("\n---- A. 真值从夹具几何推得出来，且随片长变化 ----")
    check("120 秒夹具的真值 = (1080−81)/120 = 8.325 px/s",
          abs(TRUTH_120S - 8.325) < 0.01, f"{TRUTH_120S:.3f} px/s")
    check("60 秒那一版的真值 = (1080−81)/60 = 16.65 px/s（正是旧注释里那句「约 16.7」）",
          abs(TRUTH_60S - 16.65) < 0.01, f"{TRUTH_60S:.3f} px/s")
    check("片长翻倍 → 真值减半（这就是门槛失配的机制）",
          abs(TRUTH_60S / TRUTH_120S - 2.0) < 1e-9,
          f"{TRUTH_60S:.3f} / {TRUTH_120S:.3f} = {TRUTH_60S / TRUTH_120S:.3f}")

    print("\n---- B. 拿 run10 的真实读数过两种写法 ----")
    # 那一轮读到的四个速度（px/s），以及各自的连拍跨度与间隔。
    # 都用"2 帧、间隔 0.5 秒"这一种最不利的采样（样本最少、估计最不稳）。
    readings = [8.3, 8.0, 11.0, 7.8]
    for speed in readings:
        d = speed * 0.5                     # 0.5 秒里走了多少像素
        st = stats(tmp, f"a{speed}.png", f"b{speed}.png", 200.0, 200.0 + d, 0.5)
        got = st["speed"]
        check(f"读数 {speed:.1f} px/s 能复现（实测 {got:.2f}）",
              abs(got - speed) < 0.05, f"{got:.2f} px/s")
        check(f"  真值比 ratio = {speed:.1f}/{TRUTH_120S:.2f} = {st['ratio']:.2f}"
              f" → 新写法判「在播」", V110.moving(st), V110.motion_text(st))
        check(f"  同一条读数，旧写法（speed ≥ 8.0）判"
              f"{'在播' if old_moving(st) else '**没在动**'}"
              f"（{'一致' if old_moving(st) == V110.moving(st) else '与结论相反'}）",
              True, f"old={old_moving(st)}  new={V110.moving(st)}")

    print("\n---- C. 旧写法确实会把 7.8 px/s 判成「没在动」 ----")
    st78 = stats(tmp, "c0.png", "c1.png", 200.0, 200.0 + 7.8 * 0.5, 0.5)
    check("旧写法对 7.8 px/s 给 False（那八个 FAIL 的成因，能被复现出来）",
          old_moving(st78) is False, f"old_moving → {old_moving(st78)}")
    check("新写法对同一条读数给 True（比值 0.94，离门槛 0.50 很远）",
          V110.moving(st78) is True, f"ratio={st78['ratio']:.2f}")

    print("\n---- D. 暂停读数必须判「停住」，且与「在播」不重叠 ----")
    st0 = stats(tmp, "d0.png", "d1.png", 400.0, 400.0, 0.5)
    check("暂停（位移 0px）→ 速度 0.00", st0["speed"] == 0.0, f"{st0['speed']:.3f} px/s")
    check("判「停住」", V110.still(st0), V110.motion_text(st0))
    check("且**不**判「在播」", not V110.moving(st0), f"ratio={st0['ratio']:.2f}")
    check("两条门槛之间有判定带（0.20 < ratio < 0.50 的读数两边都不认，"
          "不会自相矛盾）", V110.STILL_RATIO < V110.MOVING_RATIO,
          f"停住 ≤{V110.STILL_RATIO}、在播 ≥{V110.MOVING_RATIO}")
    st_mid = stats(tmp, "d2.png", "d3.png", 200.0, 200.0 + 0.35 * TRUTH_120S * 0.5, 0.5)
    check("判定带里的读数（ratio≈0.35）两边都判否 —— 落在带里就是「说不清」，"
          "不是被硬塞进某一类",
          (not V110.moving(st_mid)) and (not V110.still(st_mid)),
          f"ratio={st_mid['ratio']:.2f}")

    print("\n---- E. 真值量不到时必须响亮失败，不能静默通过 ----")
    st_ng = stats(tmp, "e0.png", "e1.png", 200.0, 260.0, 0.5, band_w=None, block_w=None)
    check("几何没量到 → ratio 为 None", st_ng["ratio"] is None, f"{st_ng['ratio']}")
    check("`moving` 判否（不拿没有依据的门槛凑通过）", not V110.moving(st_ng),
          V110.motion_text(st_ng))
    check("`still` 也判否（不把「测不出来」当成「停住了」）", not V110.still(st_ng),
          V110.motion_text(st_ng))

    print("\n---- F. 同一条读数、不同片长 → 比值不同；写死的门槛没法同时伺候两边 ----")
    # 17.4 px/s 是 60 秒那一版夹具上的真实读数。把它放到两种片长下各算一次比值：
    # 只有"比值"这一种判据能在两种片长下都给出同一个结论。
    V110.CLIPS["_fixture_60s.mp4"] = {"duration_ms": 60_000}
    same = 17.4
    st120 = stats(tmp, "f0.png", "f1.png", 200.0, 200.0 + same * 0.5, 0.5)
    st60 = stats(tmp, "f2.png", "f3.png", 200.0, 200.0 + same * 0.5, 0.5,
                 clip="_fixture_60s.mp4")
    check("同一条 17.4 px/s 读数：120 秒片长下比值 2.09（超速，说明这条读数量错了片长）",
          abs(st120["ratio"] - 2.09) < 0.02, f"ratio={st120['ratio']:.2f}")
    check("换 60 秒片长后同一条读数比值 1.04（正常在播）",
          abs(st60["ratio"] - 1.045) < 0.02, f"ratio={st60['ratio']:.2f}")
    check("两种片长下 `moving` 都给 True —— 判据不依赖「素材正好多少秒」",
          V110.moving(st120) and V110.moving(st60),
          f"120s→{V110.moving(st120)}、60s→{V110.moving(st60)}")

    shutil.rmtree(tmp, ignore_errors=True)

    print("\n" + "=" * 74)
    if FAIL:
        print(f"自测失败 {len(FAIL)} 项：")
        for label in FAIL:
            print(f"  · {label}")
        sys.exit(1)
    print("自测全部通过（旧写法会失败这一点已被复现出来）")
    print("=" * 74)


if __name__ == "__main__":
    main()
