#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.5 验收续跑：从"照片库已导入 2 张"的状态接着做翻页验证与各界面留证。

为什么要拆开：引导流程里的 Argon2id 派生很慢，而上面那些步骤（装包、切深色、
走引导、导入照片）在第一次跑时已经跑完了。中断点之后的部分没必要重来一遍。
"""

import importlib.util
import sys
import time


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


P = load(r"D:\MixiaVault\tools\walkthrough-v105.py", "walkthrough_v105")
W = P.W
W.OUT = P.OUT
W.SHOT_INDEX = 8          # 前面已经出了 01~08 号截图，这里接着往后编

W.log("当前位置：照片库（2 张）—— 继续做翻页验证")
P.open_viewer_and_swipe()

P.tour_appearance()

W.log("恢复浅色模式，留一张对照图")
P.set_dark(False)
time.sleep(1.5)
W.shot("浅色-设置页-对照")

print()
W.log(f"续跑完成，截图已更新到 {W.SHOT_INDEX} 张 → {W.OUT}")
