#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自测「切后台回来，位置有没有丢」这条判据 —— **先用真实失败现场证明它会红**。

    python tools/motion/selftest_position_keep.py

## 为什么这条判据必须自证

2026-09-17 之前，B 轮里跟这事有关联的判据只有「返回后还能重新播放（画面真的在动）」，
它在 `v110-release.run1` 里是 **PASS** 的 —— 而那一次用户回来后影片其实是从第 0 秒
重新开始的（切走前在第 62.8 秒，回来第一批帧在 0.9~13.8 秒）。
「画面在动」和「从我离开的地方接着播」是两件事，旧判据对后者**是瞎的**。

所以新加的 5.6 段在"变绿"之前，必须先证明**它会红**。喂进去的不是构造数据，
是两轮验收**真实落盘的连拍帧**（每个数都当场从 PNG 上量出来，不是抄的）：

    v110-release.run1              切走前 62.8s → 回来 0.9/5.7/9.8/13.8s（位置丢了，画面在动）
    v110-release.run3-position-lost 切走前 40.5s → 回来 0.0/0.0/0.0s（位置丢了，画面还冻着）

一个判据如果连"已经发生过的失败"都判不出来，那它变绿说明不了任何事。

## 还有一条：**取样点**也是判据的一部分

`run1` 的同一轮里，45 秒之后那批「返回后重播」帧是 70.9~87.1 秒 —— 比切走前的
62.8 秒还大。位置明明丢了，用那批帧判却是 PASS，因为画面从 0 重播几十秒后**反超**了。
所以 5.6 段必须紧贴「后台返回」那 4 张。这条也在这里被钉住（见 D 段）：
哪天有人把取样点挪到后面去，这个自测会红。
"""
import hashlib
import importlib.util
import os
import sys

import numpy as np
from PIL import Image

BASE = r"D:\MixiaVault"
EVID = os.path.join(BASE, "dist", "evidence")

# 两轮真实现场的目录名。写死是有意的：它们**已经归档**，
# 是这条判据的历史证据，不该跟着"最近一次跑的是哪轮"漂。
RUN_ALIVE = "v110-release.run1"                 # 位置丢了，但画面还在动
RUN_FROZEN = "v110-release.run3-position-lost"  # 位置丢了，且画面冻住

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


def secs_of(path):
    """把落盘的连拍帧量成 `(反推秒数, md5前8位)`；量不到位置返回 `(None, md5)`。

    走的全是走查自己那套：`band_of` → `block_x` → `implied_secs`。
    这里不重写任何换算 —— 重写就等于给"量错了"再开一个入口。
    """
    md5 = hashlib.md5(open(path, "rb").read()).hexdigest()[:8]
    a = np.asarray(Image.open(path).convert("RGB"), dtype=np.uint8)
    band = V110.band_of(a)
    if not band:
        return None, md5
    blk = V110.block_x(a, band)
    if not blk:
        return None, md5
    return V110.implied_secs(blk["cx"], band["w"], blk["w"]), md5


def burst(out_dir, prefix):
    """读一批连拍 → `[(文件名, 秒数或None, md5)]`，按文件名排序（＝拍摄顺序）。"""
    d = os.path.join(EVID, out_dir)
    names = sorted(n for n in os.listdir(d)
                   if n.startswith(prefix) and n.endswith(".png"))
    return [(n,) + secs_of(os.path.join(d, n)) for n in names]


def secs_list(items):
    return [s for _, s, _ in items if s is not None]


# ---------------------------------------------------------------- A
def section_a():
    print("\nA. 换算本身：白块位置 → 秒数（夹具几何的直接推论）")
    band_w, block_w = 1080, 79
    dur = V110.CLIPS[V110.MOTION_CLIP]["duration_ms"] / 1000.0

    got = V110.implied_secs(block_w / 2.0, band_w, block_w)
    check("白块贴最左 → 第 0 秒", got is not None and abs(got) < 0.05, f"{got}")

    got = V110.implied_secs(band_w - block_w / 2.0, band_w, block_w)
    check(f"白块贴最右 → 第 {dur:.0f} 秒（片尾）",
          got is not None and abs(got - dur) < 0.05, f"{got}")

    # 真值 = 行程 / 片长；中点的秒数就该是片长的一半。
    got = V110.implied_secs(block_w / 2.0 + (band_w - block_w) / 2.0, band_w, block_w)
    check("过半行程 → 片长一半", got is not None and abs(got - dur / 2) < 0.2, f"{got}")

    check("两个宽度没量到时不硬凑（返回 None）",
          V110.implied_secs(500.0, None, block_w) is None
          and V110.implied_secs(500.0, band_w, None) is None,
          "band_w / block_w 任一为 None → None")


# ---------------------------------------------------------------- B / C
def section_bc():
    for tag, run in (("B. run1（画面在动，位置照样丢）", RUN_ALIVE),
                     ("C. run3（位置丢 + 画面冻）", RUN_FROZEN)):
        print(f"\n{tag}")
        pre = burst(run, "B-横屏-再播")
        post = burst(run, "B-横屏-后台返回")
        if not pre or not post:
            check(f"{run} 的连拍帧齐不齐", False, "证据目录里缺少 再播 / 后台返回 的帧")
            continue

        before = pre[-1][1]
        after = secs_list(post)
        # md5 只在**量到画面**的那些帧之间比：run3 的第一帧是切回来的黑过渡帧，
        # 它本来就跟后面不一样，把它算进来会把"冻住"读成"在变"。
        live_md5 = {m for _, s, m in post if s is not None}
        frozen = len(live_md5) == 1
        W = [f"{s:.1f}" if s is not None else "量不到" for _, s, _ in post]
        print(f"       切走前（{pre[-1][0]}）：第 {before:.1f} 秒")
        print(f"       回来后（{post[0][0]} 起）：{'/'.join(W)} 秒"
              f"，量到画面的 {len(live_md5)} 帧里 md5 "
              f"{'逐字节相同（画面冻住）' if frozen else '在变'}")

        v, why = V110.position_keep_verdict(before, after)
        check(f"{run}：真实现场必须判红", v == "fail", f"判定 {v} —— {why}")


# ---------------------------------------------------------------- D
def section_d():
    print("\nD. 取样点也是判据的一部分：用晚了 45 秒的那批帧会把 run1 放过")
    pre = burst(RUN_ALIVE, "B-横屏-再播")
    late = burst(RUN_ALIVE, "B-横屏-返回后重播")
    if not pre or not late:
        check("run1 的 返回后重播 帧齐不齐", False, "缺帧")
        return
    before = pre[-1][1]
    after = secs_list(late)
    v, why = V110.position_keep_verdict(before, after)
    print(f"       切走前 第 {before:.1f} 秒 →（45 秒后那批）第 "
          f"{min(after):.1f}~{max(after):.1f} 秒")
    check("同一轮、换成后面那批帧 → 判成了 pass（这就是取样点挪错的样子）",
          v == "pass", f"判定 {v} —— {why}")
    check("而位置其实是丢的（回来第一批帧远低于切走前）",
          max(secs_list(burst(RUN_ALIVE, "B-横屏-后台返回"))) < before - V110.POSITION_KEEP_TOL,
          f"回来第一批的最大值 {max(secs_list(burst(RUN_ALIVE, 'B-横屏-后台返回'))):.1f} "
          f"< 门槛 {before - V110.POSITION_KEEP_TOL:.1f}")


# ---------------------------------------------------------------- E
def section_e():
    print("\nE. 修好之后长什么样（接续播放 → PASS）")
    before = 40.5
    # 重建 + prepare + seek 落地要时间，最早那一两帧可能还停在 seek 之前 ——
    # 5.6 段用的是**最大值**，正是为了容下这一点。
    v, why = V110.position_keep_verdict(before, [0.0, 38.1, 41.6, 44.2, 46.9])
    check("最早几帧还在 seek 之前（0.0），后面接上了 → 仍判 PASS", v == "pass",
          f"判定 {v} —— {why}")
    v, why = V110.position_keep_verdict(before, [40.0, 42.1, 44.5])
    check("干净接续 → PASS", v == "pass", f"判定 {v} —— {why}")
    v, why = V110.position_keep_verdict(before, [0.0, 1.2, 2.8, 4.1])
    check("从头重播 → FAIL", v == "fail", f"判定 {v} —— {why}")
    v, why = V110.position_keep_verdict(before, [])
    check("一帧都量不到位置 → FAIL（不是 skip：这是量不出来，不是判不了）",
          v == "fail", f"判定 {v} —— {why}")


# ---------------------------------------------------------------- F
def section_f():
    print("\nF. 对照值不足时必须记「判不了」，不能记 PASS")
    for before, note_ in ((0.0, "切走前就在第 0 秒"),
                          (8.5, "切走前只量到 8.5 秒")):
        v, why = V110.position_keep_verdict(before, [0.0, 1.0, 2.0])
        check(f"{note_} → skip（「复位到 0」和「本来就接近 0」分辨不开）",
              v == "skip", f"判定 {v} —— {why}")
    v, why = V110.position_keep_verdict(None, [1.0, 2.0])
    check("对照值根本没量到 → skip", v == "skip", f"判定 {v} —— {why}")


# ---------------------------------------------------------------- G
def section_g():
    print("\nG. 换分辨率/缩放不用重标：宽度同时放大，秒数不变")
    base = V110.implied_secs(500.0, 1080.0, 80.0)
    scaled = V110.implied_secs(500.0 * 3.375, 1080.0 * 3.375, 80.0 * 3.375)
    check("整条几何同乘一个倍率 → 秒数不变",
          base is not None and scaled is not None and abs(base - scaled) < 1e-6,
          f"{base:.4f} vs {scaled:.4f}")


def main():
    print("=" * 74)
    print("位置保持判据自测（真实失败现场 + 判定分支 + 取样点）")
    print("=" * 74)
    section_a()
    section_bc()
    section_d()
    section_e()
    section_f()
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
