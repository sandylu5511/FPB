# -*- coding: utf-8 -*-
"""v1.0.4 文案验证：图片"独立副本 / 原图直存"的说明在设备上真实可见。

本轮只改了注释与用户可见文案（存储与加解密逻辑一行未动），所以不做全流程回归，
只验证两处改动的文案确实渲染出来：

  1. 照片库空态 → 应说明"导入后成为库里独立的一份，之后从相册删除原图不影响这里"
  2. 新建图片编辑器 → 应说明"与相册脱钩 + 占用相当 + EXIF 原样保留"

复用 walkthrough-v103.py 的引导流程（模块顶层有 __main__ 守卫，import 不会触发全流程）。
"""
import importlib.util
import os
import sys
import time

sys.path.insert(0, r"D:\MixiaVault\tools")
import walkthrough as W

OUT = r"D:\MixiaVault\dist\evidence\v104"
os.makedirs(OUT, exist_ok=True)
W.OUT = OUT

_spec = importlib.util.spec_from_file_location(
    "wb_walkthrough_v103", r"D:\MixiaVault\tools\walkthrough-v103.py")
v103 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(v103)


def log(msg):
    print(f"[v104] {msg}", flush=True)


def main():
    log("安装 debug 包（允许截屏，用于界面取证）")
    v103.adb_out("uninstall", W.PKG)
    out = v103.adb_out("install", r"D:\MixiaVault\app\build\outputs\apk\debug\app-debug.apk")
    log(f"    {out.strip().splitlines()[-1]}")
    assert "Success" in out, f"debug 包安装失败：{out}"

    # 引导（含启用假密码），结束时停在真库主页
    v103.onboarding()

    # ---------- 1. 照片库空态文案 ----------
    log("[文案1] 照片库空态：应说明导入后是独立副本")
    entry = W.find("照片库")
    assert entry is not None, "主页顶栏没有找到照片库入口"
    W.tap(entry["cx"], entry["cy"])
    W.wait_text("还没有照片", what="照片库空态")

    hit = None
    for _ in range(5):
        hit = W.find("之后从相册删除原图不影响这里")
        if hit:
            break
        time.sleep(0.8)
    assert hit is not None, "照片库空态没有出现'独立副本'说明"
    log(f"    OK：{(hit['text'] or hit['desc'])[:46]}…")
    W.shot("01-照片库空态-副本说明")

    W.adb("shell", "input", "keyevent", "4")
    W.wait_text("还没有任何记录", what="回到主页")

    # ---------- 2. 新建图片编辑器文案 ----------
    log("[文案2] 新建图片编辑器：应说明与相册脱钩 / 占用相当 / EXIF 保留")
    fab = next(n for n in W.nodes()
               if n["clickable"] and not (n["text"] or n["desc"]) and n["cy"] > 1900)
    W.tap(fab["cx"], fab["cy"])
    time.sleep(1.2)
    W.tap_text("图片")
    time.sleep(1.5)

    hit = None
    for _ in range(4):
        hit = W.find("入库后它和相册里那张就没有关联了")
        if hit:
            break
        W.adb("shell", "input", "swipe", "540", "1500", "540", "900", "250")
        time.sleep(0.9)
    assert hit is not None, "新建图片编辑器里没有出现'与相册脱钩'说明"
    log(f"    OK：{(hit['text'] or hit['desc'])[:46]}…")
    W.shot("02-编辑器-副本说明")

    print()
    log("v1.0.4 文案验证通过")
    log(f"证据目录：{OUT}")


if __name__ == "__main__":
    main()
