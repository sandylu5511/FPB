#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""探针（第 2 段）：假设库已建好、人已在主页，dump 主页与外观模式对话框。"""

import importlib.util
import sys
import time


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V105 = load(r"D:\MixiaVault\tools\walkthrough-v105.py", "w105")
W = V105.W
W.OUT = r"D:\MixiaVault\dist\evidence\v106-probe"
W.SHOT_INDEX = 20


def dump(label):
    print(f"\n===== {label} =====")
    for n in W.nodes():
        print(f'  {n["cls"]:<14} chk={str(n["checkable"]):<5} sel={str(n["checked"]):<5} '
              f'clk={str(n["clickable"]):<5} y1={n["y1"]:<5} '
              f'text={n["text"][:30]!r} desc={n["desc"][:20]!r}')


def main():
    if W.find("本机加密") is None:
        W.adb("shell", "am", "start", "-n", "com.fpb.vault/.MainActivity")
        time.sleep(6)
    dump("当前界面")

    hit = W.find("设置")
    assert hit is not None, "找不到设置入口"
    print(f"\n设置入口：text={hit['text']!r} desc={hit['desc']!r} cx={hit['cx']} cy={hit['cy']}")
    W.tap(hit["cx"], hit["cy"])
    W.wait_text("安全", timeout=25, what="设置页")
    time.sleep(1.2)
    dump("设置页第一屏")

    W.tap_text_scrolling("外观模式")
    time.sleep(1.6)
    dump("外观模式对话框")
    W.shot("探针-外观模式对话框")

    radios = [n for n in W.nodes() if n["cls"] == "RadioButton"]
    print(f"\nRadioButton 数量：{len(radios)}  " + "; ".join(f'y1={r["y1"]}' for r in radios))
    if len(radios) >= 3:
        W.tap(radios[2]["cx"], radios[2]["cy"])
        time.sleep(5.5)
        dump("点第 3 个 RadioButton（期望=深色）之后")
        W.shot("探针-选深色之后")


if __name__ == "__main__":
    main()
