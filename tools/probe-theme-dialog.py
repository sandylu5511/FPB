#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""探针：把「外观模式」对话框的节点树打出来，供验收脚本定位控件。

不猜控件类型 —— Compose 的层级里 RadioButton / Switch / Text 各是什么样子，
只有 dump 一次才知道。
"""

import importlib.util
import sys
import time

W = None


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V105 = load(r"D:\MixiaVault\tools\walkthrough-v105.py", "w105")
W = V105.W
W.OUT = r"D:\MixiaVault\dist\evidence\v106-probe"
W.SHOT_INDEX = 0

MASTER = "VaultMaster2026x"


def dump(label):
    print(f"\n===== {label} =====")
    for n in W.nodes():
        print(f'  {n["cls"]:<14} checkable={str(n["checkable"]):<5} checked={str(n["checked"]):<5} '
              f'clickable={str(n["clickable"]):<5} y1={n["y1"]:<5} '
              f'text={n["text"][:34]!r} desc={n["desc"][:22]!r}')


def main():
    apk = r"D:\MixiaVault\app\build\outputs\apk\debug\app-debug.apk"
    W.adb("install", "-r", "-g", apk)
    V105.set_dark(False)
    V105.onboarding()

    W.hide_ime()
    hit = next((n for n in W.nodes() if n["text"] == "设置"), None)
    assert hit, "主页找不到「设置」"
    W.tap(hit["cx"], hit["cy"])
    W.wait_text("外观与伪装", timeout=25, what="设置页")
    time.sleep(1.0)
    dump("设置页第一屏")

    W.tap_text_scrolling("外观模式")
    time.sleep(1.5)
    dump("外观模式对话框（已打开）")
    W.shot("探针-外观模式对话框")

    # 试着按 RadioButton 定位并点第三档（深色）
    radios = [n for n in W.nodes() if n["cls"] == "RadioButton"]
    print(f"\nRadioButton 数量：{len(radios)}")
    for r in radios:
        print(f'  y1={r["y1"]} cy={r["cy"]} checked={r["checked"]}')
    if len(radios) == 3:
        W.tap(radios[2]["cx"], radios[2]["cy"])
        time.sleep(1.0)
        dump("点了第 3 个 RadioButton（期望=深色）之后")
        W.shot("探针-点第三个-结果")
    else:
        print("!! RadioButton 数量不是 3，坐标策略不成立")


if __name__ == "__main__":
    main()
