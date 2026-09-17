#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""FPB v1.0.9 正式包验收（release，R8 之后）。

    python tools/v109-release-acceptance.py

## 为什么 release 包必须单独跑一遍

debug 包不跑 R8，所以它全绿**推不出** release 全绿。本版最值得在 release 上重验的一点：

    `BytesMediaSource.readAt/getSize` 是**被 MediaPlayer 的 native 侧按名字调用**的，
    字节码里看不到调用点 —— R8 一旦把它们当死代码删掉/改名，播放会静默失败。
    实测 v1.0.8 release 能播，说明 @Keep/默认规则保住了；本版改了播放器的尺寸逻辑，
    所以这条要再验一次（判据自己会说话：条带 1080×608 + 正方形比例 1.000）。

## 覆盖的五件事

  0. 包本身的身份：versionCode 10 / versionName 1.0.9 / v2+v3 签名 / **证书指纹与前序包一致**
     （指纹不一致 = 装不上，等于所有老用户必须卸载重装 = 数据全丢）
  1. **全新安装**时 release 包默认禁止截屏（这不是"设置里有个开关"，是窗口上真有 FLAG_SECURE）
  2. **可覆盖升级**：先装 v1.0.8 造出一条记录与一张实况照片，再 `install -r` v1.0.9，
     **不清数据**，断言那张照片还在、还能播
  3. 实况照片在 release 上仍然是"打开即播"，且**画面不被拉伸**（量像素，判据见
     `walkthrough-v109-motion.py`）
  4. 图库多选删除仍然可用（轻量回归，防止这版改动碰坏列表交互）

## 采样与判据的坑

判据全部复用 `walkthrough-v109-motion.py` 里那套（已带自测 `tools/motion/selftest_measure.py`）。
其中"连拍必须紧跟着点开那一瞬"这条是踩出来的：影片只有 4 秒，
先等页码再开拍会永远晚于播放结束，把"没抓到"错读成"没播"。

**注意模块副作用**：`walkthrough-v109-motion.py` 在模块顶层就设了 `W.OUT`，
所以本文件是"先 exec_module、**再**覆盖 W.OUT/SHOT_INDEX"的顺序，否则截图会悄悄落到它那个目录去。
"""

import glob
import hashlib
import importlib.util
import os
import re
import shutil
import subprocess
import sys
import time

BASE = r"D:\MixiaVault"
ADB = r"D:\AndroidSdk\platform-tools\adb.exe"
PKG = "com.fpb.vault"
OUT = rf"{BASE}\dist\evidence\v109-release"

APK_NEW = rf"{BASE}\dist\FPB-v1.0.9.apk"
APK_OLD = rf"{BASE}\dist\FPB-v1.0.8.apk"
APK_BUILT = rf"{BASE}\app\build\outputs\apk\release\app-release.apk"

JAVA = r"C:\Program Files\Android\Android Studio\jbr\bin\java.exe"

RESULTS = []


def build_tools():
    ds = sorted(glob.glob(r"D:\AndroidSdk\build-tools\*"))
    assert ds, "找不到 build-tools"
    return ds[-1]


def sh(*args, timeout=300):
    p = subprocess.run([ADB, *args], capture_output=True, timeout=timeout)
    return (p.stdout + p.stderr).decode("utf-8", "replace").replace("\r", "")


def host(*args, timeout=300):
    p = subprocess.run(list(args), capture_output=True, timeout=timeout)
    return (p.stdout + p.stderr).decode("utf-8", "replace")


def check(label, ok, detail=""):
    RESULTS.append((label, bool(ok), detail))
    print(f"    [{'PASS' if ok else 'FAIL'}] {label}：" + (detail or ""))
    return ok


def note(label, detail):
    print(f"    [记录] {label}：{detail}")


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


# ---------- 加载被测脚本：先 exec_module，再覆盖它的输出目录（顺序不能反） ----------
M = load(rf"{BASE}\tools\walkthrough-v109-motion.py", "v109")
W = M.W
V105 = M.V105
R108 = M.R108
W.OUT = OUT
W.SHOT_INDEX = 0
M.PHASE_TAG = "R"
# **两个都要改。** `M.OUT` 和 `W.OUT` 是**两个不同的全局**：
# M 模块里 `grab()` / `burst()` 用的是自己的模块级 OUT，`W.shot()` 用的是 walkthrough 的 OUT。
# 只改后者的话，连拍帧会静默落回 v109-motion/ —— 证据目录里会莫名其妙多出一批
# 属于 release 轮的帧，而 A/B 那两份证据的目录也不再是"一次一轮"了。
# （这个坑在 tools/README.md 里记过一次，这次是同一个坑的另一半。）
M.OUT = OUT


def installed_version():
    out = sh("shell", "dumpsys", "package", PKG)
    m = re.search(r"versionName=(\S+)", out)
    n = re.search(r"versionCode=(\d+)", out)
    return (m.group(1) if m else "?"), (n.group(1) if n else "?")


def cert_digest(apk):
    """签名的证书 SHA-256 指纹。apksigner.bat 在无头环境会静默失败，直接跑 jar。"""
    jar = os.path.join(build_tools(), "lib", "apksigner.jar")
    out = host(JAVA, "-jar", jar, "verify", "--verbose", "--print-certs", apk)
    m = re.search(r"certificate SHA-256 digest:\s*([0-9a-fA-F]+)", out)
    v1 = "Verified using v1 scheme (JAR signing): true" in out
    v2 = "Verified using v2 scheme (APK Signature Scheme v2): true" in out
    v3 = "Verified using v3 scheme (APK Signature Scheme v3): true" in out
    return (m.group(1).lower() if m else None), v1, v2, v3


def badging(apk):
    aapt = os.path.join(build_tools(), "aapt2.exe")
    out = host(aapt, "dump", "badging", apk)
    ver = re.search(r"versionCode='(\d+)'\s+versionName='([^']*)'", out)
    return (ver.group(1), ver.group(2)) if ver else (None, None)


def wait_home(timeout=240):
    """等到"已解锁的主页"。冷启动期间 dump 会连失败几十秒 —— 那是读不到，不是没到。"""
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    end = time.time() + timeout
    blind = 0
    while time.time() < end:
        try:
            if W.find("本机加密 ·") is not None:
                time.sleep(1.0)
                return True
            on_unlock = W.find("忘记密码") is not None
        except RuntimeError:
            blind += 1
            time.sleep(3.0)
            continue
        if on_unlock:
            W.log("    停在解锁页，用主密码解锁")
            es = [n for n in W.nodes() if n["cls"] == "EditText"]
            if es:
                W.tap(es[0]["cx"], es[0]["cy"])
                W.type_ascii(V105.MASTER)
                W.hide_ime()
                W.tap_text("解锁")
                time.sleep(3.0)
        time.sleep(1.5)
    raise RuntimeError(f"等不到主页（其中 {blind} 轮连屏幕都读不到）")


def launch_and_secure_check():
    """全新安装后，launch 一下就问窗口标志 —— 不必走完引导。

    用 v108 那份已经踩过坑的实现（符号名与十六进制两种格式都认），
    不在这里重写一遍：重写就等于把"只认十六进制、于是解析器悄悄返回 None"那个坑再挖一次。
    """
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(8)
    return R108.window_secure()


def library_count():
    hit = W.find("原图加密存储 · ")
    if not hit:
        return None
    m = re.search(r"(\d+)\s*张", hit["text"])
    return int(m.group(1)) if m else None


def main():
    os.makedirs(OUT, exist_ok=True)

    # ============ 0. 包的身份 ============
    print("\n=== 0. 包的身份与签名 ===")
    shutil.copyfile(APK_BUILT, APK_NEW)
    size = os.path.getsize(APK_NEW)
    sha = hashlib.sha256(open(APK_NEW, "rb").read()).hexdigest()
    code, name = badging(APK_NEW)
    dig_new, v1, v2, v3 = cert_digest(APK_NEW)
    dig_old, *_ = cert_digest(APK_OLD)

    note("文件", f"{APK_NEW}  {size:,} B")
    note("SHA-256", sha)
    note("签名", f"v1={v1} v2={v2} v3={v3}")
    note("证书指纹", f"{dig_new}\n          （v1.0.8：{dig_old}）")

    check("versionCode/Name = 10 / 1.0.9", (code, name) == ("10", "1.0.9"), f"{code} / {name}")
    check("v2 与 v3 签名都有效", v2 and v3, f"v2={v2} v3={v3}")
    check("v1 关闭（minSdk 26 起只会多一份可被篡改的清单）", v1 is False, f"v1={v1}")
    check("证书指纹与前序包一致（能覆盖升级的前提）", dig_new == dig_old,
          f"{dig_new}")
    check("体积落在 release 包的合理量级（不是未签名的残包）",
          1_000_000 < size < 8_000_000, f"{size:,} B")

    # ============ 1. 全新安装：release 默认禁止截屏 ============
    print("\n=== 1. 全新安装 v1.0.9：默认禁止截屏 ===")
    sh("uninstall", PKG)
    out = sh("install", "-r", "-g", APK_NEW)
    assert "Success" in out, f"安装失败：{out}"
    name_dev, code_dev = installed_version()
    check("设备上装的是 1.0.9 / code 10", (name_dev, code_dev) == ("1.0.9", "10"),
          f"{name_dev} / {code_dev}")
    secure = launch_and_secure_check()
    check("release 包开箱即禁止截屏（窗口上真有 FLAG_SECURE）", secure is True,
          f"fl 里 SECURE={secure}")
    M.grab("01-全新安装-默认禁止截屏")
    # 用 raw 像素均值判"这一帧是不是黑的"，比看 PNG 体积更直接
    # （体积那条只是个便宜的启发式：黑帧实测约 15 KB）。
    fr = R108.screen_frame()
    check("此时这一帧确实是黑的（像素证据）", fr is not None and fr[2] < 8.0,
          f"平均亮度 {fr[2]:.2f}/255，亮像素占比 {fr[3] * 100:.2f}%" if fr else "量不到画面")

    # ============ 2. 造"旧数据"：装 v1.0.8 并导入实况照片 ============
    print("\n=== 2. 装 v1.0.8、导入一张实况照片，作为「升级前」的基线 ===")
    sh("uninstall", PKG)
    out = sh("install", "-r", "-g", APK_OLD)
    assert "Success" in out, f"安装 v1.0.8 失败：{out}"
    V105.onboarding()                     # 内含 pm clear
    M.grant_screenshots()                 # release 默认禁截屏，关掉才有视觉证据
    M.open_library()
    before = M.import_probe()
    check("v1.0.8 里已导入 1 张实况照片", before == 1, f"库里 {before} 张")

    # ============ 3. 覆盖升级到 v1.0.9（不清数据） ============
    print("\n=== 3. install -r 覆盖升级到 v1.0.9（**不做 pm clear**）===")
    out = sh("install", "-r", "-g", APK_NEW)
    assert "Success" in out, f"覆盖安装失败：{out}"
    name_dev, code_dev = installed_version()
    check("升级后版本变成 1.0.9 / code 10", (name_dev, code_dev) == ("1.0.9", "10"),
          f"{name_dev} / {code_dev}")

    wait_home()
    M.open_library()
    after = library_count()
    check("升级后那张照片还在（数据没被清）", after == 1, f"库里 {after} 张")
    badge = W.find("实况")
    check("升级后仍被识别为实况（角标在）", badge is not None,
          "看到「实况」" if badge else "没看到")
    fr2 = R108.screen_frame()
    check("升级后「禁止截屏」这个偏好也随数据保留下来（画面不再是黑的）",
          fr2 is not None and fr2[2] > 8.0,
          f"平均亮度 {fr2[2]:.2f}/255" if fr2 else "量不到画面")

    # ============ 4. R8 之后的 release 包：自动播放 + 不拉伸 ============
    print("\n=== 4. release 包上的实况播放：自动播 + 画面不拉伸 ===")
    rows_nobrand, hit = M.enter_and_burst("R-noplay")
    check("没点任何东西就自动播起来了（R8 没把 MediaDataSource 那条路弄坏）",
          bool(hit), f"{len(rows_nobrand)} 帧里 {len(hit)} 帧出现影片条带")

    if hit:
        from PIL import Image
        best_path, band = max(hit, key=lambda t: t[1]["h"])
        sq = M.square_of(Image.open(best_path).convert("RGB"), band)
        W.log(f"    {M.describe(band, sq)}")
        check(f"条带高度 ≈ {M.BAND_EXPECT:.0f}px（16:9 铺进 1080 宽应得的高度）",
              abs(band["h"] - M.BAND_EXPECT) <= 15, f"实测 {band['h']}px")
        check("画面里的正方形渲染出来还是正方形（比例 ≈1.00）",
              sq is not None and abs(sq["ratio"] - 1.0) <= 0.08,
              f"{sq['w']}×{sq['h']} → {sq['ratio']:.3f}" if sq else "量不到正方形")
        check("条带横向铺满可视宽度",
              band["x0"] <= 4 and band["x1"] >= M.VIEW_W - 5,
              f"x[{band['x0']},{band['x1']}]")
    M.wait_viewer(timeout=20)

    # ============ 5. 轻量回归：图库多选删除 ============
    print("\n=== 5. 回归：图库多选删除仍可用 ===")
    M.leave_viewer()
    cell = next((n for n in W.nodes()
                 if n["clickable"] and not (n["text"] or n["desc"])
                 and 280 < n["y1"] < 1900), None)
    if check("照片库里找得到缩略图", cell is not None, f"{cell and (cell['cx'], cell['cy'])}"):
        R108.long_press(W, cell["cx"], cell["cy"])
        head = W.find("共 ")
        check("长按进入多选（出现「共 N」）", head is not None,
              head["text"] if head else "没有出现")
        if head:
            cancel = next((n for n in W.nodes()
                           if n["text"] in ("退出多选", "取消") and n["clickable"]), None)
            if cancel:
                W.tap(cancel["cx"], cancel["cy"])
                time.sleep(1.0)
            else:
                W.adb("shell", "input", "keyevent", "4")
                time.sleep(1.0)
        M.grab("05-回归-图库多选")

    print()
    failed = [r for r in RESULTS if not r[1]]
    W.log(f"共 {len(RESULTS)} 条判据，未通过 {len(failed)} 条")
    if failed:
        for label, _, detail in failed:
            print(f"    · {label} —— {detail}")
        return 1
    W.log("v1.0.9 正式包验收全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
