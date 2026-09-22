#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在"画面冻在首帧"的现场按一下播放键，看画面会不会动。

    python tools/_probe_play_button.py

判据只有一条：按下播放之后，白块的位置会不会变。
  · 会动 → 读取器与解码器都还活着，问题在"谁该在什么时候渲染"（播放器/表面状态）；
  · 不动 → 连解码都没发生，问题在数据供给那一侧。

同时把控制条上的时间读出来：它告诉我们是"停在 0"还是"以为已经播完（2:00）"。
"""
import importlib.util
import os
import sys
import time

BASE = r"D:\MixiaVault"
OUT = rf"{BASE}\dist\evidence\surface-recreate"


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V110 = load(rf"{BASE}\tools\walkthrough-v110-video.py", "v110")
W = V110.W


def main():
    os.makedirs(OUT, exist_ok=True)

    ns = V110.reveal_controls(rounds=3)
    glyphs = [(n["desc"], n["cx"], n["cy"]) for n in ns if n["desc"] in ("播放", "暂停")]
    times = [n["text"].strip() for n in ns if ":" in (n["text"] or "")]
    print(f"控制条：按钮 {glyphs}、时间 {times}")

    if not glyphs:
        sys.exit("控制条上读不到播放/暂停键 —— 先把控制条叫出来再跑")

    # 点**控制条**上那个（贴屏幕底边），不是画面正中那个 —— 两个都能播，
    # 但控制条那个才是用户会用的路。
    kind, cx, cy = max(glyphs, key=lambda g: g[2])
    print(f"点「{kind}」({cx},{cy})")
    W.tap(cx, cy)
    time.sleep(1.5)

    rows = V110.burst("playbtn", 5)
    xs = V110.centroids(rows)
    st = V110.motion_stats(xs)
    print(f"点完之后各帧白块质心：{xs['points']}")
    print(f"{V110.motion_text(st)}")
    print(f"→ {'画面在动（读取器与解码器都活着）' if V110.moving(st) else '画面仍不动'}")

    ns2 = V110.reveal_controls(rounds=3)
    print(f"之后的控制条："
          f"{[(n['desc'],) for n in ns2 if n['desc'] in ('播放', '暂停')]}、"
          f"{[n['text'].strip() for n in ns2 if ':' in (n['text'] or '')]}")
    W.shot("按播放后")


if __name__ == "__main__":
    main()
