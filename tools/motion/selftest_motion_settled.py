#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自测「画面到底在不在动」这条判据的两个新零件 —— **先证明它们会红**。

    python tools/motion/selftest_motion_settled.py

## 为什么要给这条判据加零件

`run2` 里三条判据红掉，读数长这样：

    [FAIL] 点「播放」后画面重新动起来：6 个读数，位移 3.9px / 5.28s → 0.7 px/s
    [FAIL] 返回后还能重新播放：5 个读数，位移 0.0px / 3.78s → 0.0 px/s

而同一段里「切后台回来没有从头重播」是 PASS，`13 张跨约 40 秒的现场帧 sha256 完全相同`。
判它们的是**裸像素**（`burst` → `centroids` → `moving`），而像素读数有一个已证实的
失效模式：`screencap` 会返回旧帧。真机上抓到的极端读数（`_probe_pause.py track`）：

    #0 质心 [77.1, 77.1, 77.1, 77.1] → 片内 4.5 秒     ← 连测三轮都是同一格
    #1 质心 [77.1, 77.1, 77.1, 77.1] → 片内 4.5 秒
    #2 质心 [77.1, 77.1, 77.1, 77.1] → 片内 4.5 秒
    #3 质心 [310.0, 313.0, 323.5, 334.0] → 片内 32.4 秒  ← 一帧之内跳过 28 秒
    #4 质心 [333.9, 333.9, 381.0, 381.0] → 片内 35.3 秒  ← 2.17 倍速追赶

**"连续几帧质心相同 ⇒ 停住了"于是根本不成立**，而它错的方向恰好是危险的：
把"量具没量到"读成"用户的应用坏了"。只加帧数解决不了 —— 上面第 #0~#2 轮
每轮都是 4 帧、整整三轮。

## 两个新零件，各测各的

    A/B/C  `controls_visible`：第三个测量。它不看影片，只看屏幕底部那一条
           （控制条在不在）—— 由 Compose 直接画在窗口图层上，**不经过影片的
           Surface**，却要经过同一条截图通路。所以它证明的是"截图通路此刻是活的"，
           从而把上面那两种可能分开。
    D/E    `settle_verdict`：三条读数 → 判定。四个分支各对应一句给用户完全不同的话，
           这里逐条钉住，防止有人顺手把 `retry` 并进 `stopped`
           （那等于把量具的锅扣给应用）。

用到的帧全部来自**已经落盘的现场**（`dist/evidence/v112-release/`），
每个数当场从 PNG 上量出来，不是抄的。
"""
import importlib.util
import os
import sys

import numpy as np
from PIL import Image

BASE = r"D:\MixiaVault"
# **归档**的现场帧，不是 `dist/evidence/v112-release/`。
#
# 后者是"最近一次验收跑"的落盘位置 —— 下一次验收会把它整个覆盖掉，
# 而验收本来就要在每一版上重跑。自测的依据要是活的，那它今天绿明天红
# 就都不说明任何事（比如某一次跑"播放中-00"那张正好赶上控制条已收起，
# A 段就会红，而判据本身一点问题都没有）。
# 与 `selftest_position_keep.py` 用 `v110-release.run1` 是同一个道理：
# 证据一旦归档就不再跟着"最近跑了哪轮"漂。
EVID = os.path.join(BASE, "dist", "evidence", "v112-release.run2-frames")

FAIL = []


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V110 = load(rf"{BASE}\tools\walkthrough-v110-video.py", "v110")


def check(label, ok, detail):
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}：{detail}")
    if not ok:
        FAIL.append(label)


def frame(name):
    return np.asarray(Image.open(os.path.join(EVID, name)).convert("RGB"), dtype=np.uint8)


def bottom_bright(a):
    """量具里那个"底部亮不亮"的原始读数 —— 复现一遍只为把余量打出来给读者看。"""
    sub = a[V110.CONTROLS_BAND_Y0:V110.CONTROLS_BAND_Y1, :, :].astype(np.int16)
    return int(((sub > 60).any(axis=2)).sum())


def whole_bright(a):
    """量具里那个"这一帧是不是看片页"的原始读数。"""
    return int((a > 120).all(axis=2).sum())


# ==================== A. 第三个测量：看片页上，控制条在不在 ====================
#
# 正例是"控制条明明在"的帧（暂停态、拖动中、刚点完播放），
# 反例是同一页上"控制条已经自己收起"的帧 —— 都在同一个影片、同一个横屏方向上，
# 所以它们之间的差别只可能是控制条本身。
ON_VIEWER_BAR_UP = [
    "19-R-横屏-已暂停.png",     # 暂停态：控制条常驻
    "20-R-横屏-拖到30%.png",    # 拖进度条时：控制条常驻
    "B-横屏-再播-00.png",       # 刚点完「播放」，控制条还没到 3.5 秒的收起时刻
    "B-横屏-再播-01.png",
    "B-横屏-播放中-00.png",     # 打开影片后的连拍第一张，控制条还在
]
ON_VIEWER_BAR_DOWN = [
    "B-横屏-播放中-01.png",
    "B-横屏-播放中-05.png",
    "B-横屏-再播-02.png",
    "B-横屏-再播-05.png",
    "B-横屏-后台返回-02.png",
    "B-横屏-返回后重播-04.png",
]


def section_a():
    print("\nA. 看片页：控制条展开的那几张 → 量得到")
    for f in ON_VIEWER_BAR_UP:
        a = frame(f)
        check(f, V110.controls_visible(a) is True,
              f"底部亮像素 {bottom_bright(a)} > 阈值 {V110.CONTROLS_PIXELS}")
    print("\nB. 看片页：控制条收起的那几张 → 量不到")
    for f in ON_VIEWER_BAR_DOWN:
        a = frame(f)
        check(f, V110.controls_visible(a) is False,
              f"底部亮像素 {bottom_bright(a)} ≤ 阈值 {V110.CONTROLS_PIXELS}")


# ==================== C. 那道"这一帧是不是看片页"的闸门 ====================
#
# **这一节是这条判据最容易被漏掉的一半。**
# 媒体库、空库、选图器那几页，屏幕下半部本来就亮着（列表/按钮就在那儿），
# 只看"底部亮不亮"会把它们读成"控制条在" —— 那是**因为错误的原因**为真。
# 一个能因为别的原因成立的条件，不能用来当"截图通路是活的"的证据。
NOT_VIEWER = [
    "18-R-媒体库-两个视频格.png",
    "16-R-媒体库-空态.png",
    "22-R-删除后-空库.png",
    "17-R-选图器.png",
]


def section_c():
    print("\nC. 非看片页：底部本来就亮着，但**不能**读成'控制条在'")
    for f in NOT_VIEWER:
        a = frame(f)
        check(f, V110.controls_visible(a) is False,
              f"整幅强白 {whole_bright(a)} > {V110.VIEWER_MAX_BRIGHT}"
              f"（底部亮像素 {bottom_bright(a)} —— 正是被闸门拦掉的那一类）")
    print("\nD. 余量：两侧都还留着数量级，不是压着阈值过")
    up = min(bottom_bright(frame(f)) for f in ON_VIEWER_BAR_UP)
    down = max(bottom_bright(frame(f)) for f in ON_VIEWER_BAR_DOWN)
    check("最暗的'展开'仍高于阈值的 3 倍",
          up > 3 * V110.CONTROLS_PIXELS,
          f"{up} > 3×{V110.CONTROLS_PIXELS}")
    check("最亮的'收起'仍低于阈值的 1/3",
          down < V110.CONTROLS_PIXELS / 3,
          f"{down} < {V110.CONTROLS_PIXELS}÷3 = {V110.CONTROLS_PIXELS // 3}"
          f"（两侧相对余量一样大，因为阈值取的是几何中点）")
    seen = min(whole_bright(frame(f)) for f in NOT_VIEWER)
    viewer = max(whole_bright(frame(f)) for f in ON_VIEWER_BAR_UP + ON_VIEWER_BAR_DOWN)
    check("最暗的非看片页仍高于'看片页'闸门",
          seen > V110.VIEWER_MAX_BRIGHT,
          f"{seen} > {V110.VIEWER_MAX_BRIGHT}")
    check("最亮的看片页仍低于'看片页'闸门",
          viewer < V110.VIEWER_MAX_BRIGHT,
          f"{viewer} < {V110.VIEWER_MAX_BRIGHT}")


# ==================== E. 判定表：四个分支各说各的话 ====================
def section_e():
    print("\nE. 三条读数 → 判定（`settle_verdict`）")
    cases = [
        # (像素在动, 控制条帧数, 时钟一次, 时钟二次, 期望判定, 这里的判断依据)
        (True,   0, None,    None,    "moving",
         "像素在动就直接收工：另外两个测量此时没有发言权"),
        (False,  0, "0:21",  "0:22",  "retry",
         "时钟在走 + 控制条一帧都没出现 = 截图通路陈旧 → 重测，**不能**判否"),
        (False,  2, "0:21",  "0:22",  "frozen",
         "时钟在走 + 控制条量得到 = 截图通路是活的，只有影片那块不动"),
        (False,  0, "0:21",  "0:21",  "stopped",
         "两个独立测量都说停 → 如实判否"),
        (False,  3, "0:21",  "0:21",  "stopped",
         "时钟不走就是不走，控制条在不在不影响这个结论"),
        (False,  0, None,    "0:22",  "retry",
         "时钟只读到一次 = 说不出话 → 重测"),
        (False,  0, "0:21",  None,    "retry",
         "时钟只读到一次 = 说不出话 → 重测"),
        (False,  0, None,    None,    "retry",
         "时钟完全读不到 → 重测（走查用完重试后按失败记录，**不会**变绿）"),
    ]
    for pm, bars, c0, c1, want, why in cases:
        got, text = V110.settle_verdict(pm, bars, c0, c1)
        check(f"像素{'动' if pm else '不动'} / 控制条{bars}帧 / 时钟{c0}→{c1} = {want}",
              got == want, f"实得 {got} —— {why}｜文案：{text[:46]}…")

    print("\nF. `retry` 与 `stopped` 是两件不能混的事")
    _, retry_text = V110.settle_verdict(False, 0, "0:21", "0:22")
    _, stop_text = V110.settle_verdict(False, 0, "0:21", "0:21")
    check("'截图通路陈旧'与'确实没在播'的文案不同",
          retry_text != stop_text, "两句给用户的结论完全不同")
    check("陈旧那一句里说清了是**截图通路**的问题",
          "截图通路" in retry_text, retry_text[:60])
    check("冻住那一句里说清了是**画面**冻住（而不是读取器坏了）",
          "画面真的冻住" in V110.settle_verdict(False, 2, "0:21", "0:22")[1],
          V110.settle_verdict(False, 2, "0:21", "0:22")[1][:60])


# ==================== G. "影片已经播完"必须跟"停住"分开 ====================
#
# 真机上撞到的现场（2026-09-18 `_probe_pause.py duel`）：
# 影片播到 115.7 秒那一格之后画面当然不再变化，而那一刻 a11y 树里
# **一个时间都读不到** —— 控制条被探针自己那些"轻点画面"的点击收起了，
# 只剩画面正中那个「播放」键。下一次轻点正中，影片**从 0:16 重新开始**
# （不是从 115.7 秒接着播），这才证明它刚才是"播完了"而不是"坏了"。
#
# 没有这一支，那件事会走 `retry` → 用完重试 → 记红，而红出来的话是
# "量具没能定案"，看的人只会去查应用。
def section_g():
    print("\nG. 「影片已经播完」是第三件事，不能混进'停住'")
    node = lambda t: {"text": t, "desc": "", "cls": "TextView"}
    cases = [
        ("位置 2:00 / 总时长 2:00", [node("2:00"), node("2:00")], True,
         "位置追上了总时长 = 播到片尾"),
        ("位置 0:21 / 总时长 2:00", [node("0:21"), node("2:00")], False,
         "还在中途"),
        ("只读到一个 2:00", [node("2:00")], False,
         "读不全就**不能**说'播完了' —— 判不出来不等于已经播完"),
        ("位置 0:00 / 总时长 0:00", [node("0:00"), node("0:00")], False,
         "时长未知时两边都是 0:00，那不是片尾"),
        ("树里一个时间都没有", [], False, "读不到就是读不到"),
    ]
    for label, ns, want, why in cases:
        check(f"at_video_end：{label} = {want}",
              V110.at_video_end(ns) is want, why)

    print("\nH. `at_end` 这一支真的改变了判定（不是白加的）")
    fake_nodes = [node("2:00"), node("2:00")]
    at_end = V110.at_video_end(fake_nodes)
    without = V110.settle_verdict(False, 0, "2:00", "2:00")[0]
    with_flag = V110.settle_verdict(False, 0, "2:00", "2:00", at_end=at_end)[0]
    check("不带这个标志时会判成 stopped（'确实没在播'）",
          without == "stopped", f"实得 {without} —— 这句话会把'播完了'说成故障")
    check("带上之后判成 ended（'影片播完了，不是坏了'）",
          with_flag == "ended", f"实得 {with_flag}")
    check("ended 的文案里点明了'不是读取器坏了'",
          "不是读取器坏了" in V110.settle_verdict(False, 0, "2:00", "2:00",
                                                 at_end=True)[1],
          V110.settle_verdict(False, 0, "2:00", "2:00", at_end=True)[1][:70])


def main():
    print("=" * 74)
    print("「画面到底在不在动」判据自测（第三个测量 + 判定表）")
    print("=" * 74)
    section_a()
    section_c()
    section_e()
    section_g()
    print("\n" + "=" * 74)
    if FAIL:
        print(f"未通过 {len(FAIL)} 条：")
        for f in FAIL:
            print(f"  · {f}")
        return 1
    print("全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
