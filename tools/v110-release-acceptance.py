#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""FPB v1.1.x 正式包验收（release，R8 之后）。

    python tools/v110-release-acceptance.py

## 版本号只在这里定义一处

`VERSION_CODE` / `VERSION_NAME` 是**唯一**写死版本号的地方，别处一律引用它们。
2026-09-17 的教训：这套判据原来把 `11 / 1.1.0` 抄在 8 个地方，升到 1.1.1 时
只要漏一处，脚本就会红着脸说"设备上装的是 1.1.1，期望 1.1.0" —— 报出来的
是**版本配错了**，真因却是**量具自己没跟上**。同一天运动门槛写死 `8.0` 的
那次假红，是同一个毛病换了个地方。

## 为什么 release 包必须单独跑一遍

debug 包不跑 R8，所以它全绿**推不出** release 全绿。本版（视频支持）最值得在
release 上重验的一点，和 v1.0.9 是**同一类**、但落点换了：

    `BlobMediaSource` 继承 `android.media.MediaDataSource`，它的
    `readAt` / `getSize` 是**被 MediaPlayer 的 native 侧按名字回调**的 ——
    字节码里看不到调用点。R8 一旦把类或方法删掉/改名，播放会静默失败：
    不崩、不报错，只是画面不出来。整条视频链路（流式加密落盘 → 随机访问读取器
    → 交给 MediaPlayer）里，这是唯一一处"编译器看不见的契约"。

    所以本轮的判据不是"能装能开"，而是**打开就播、画面在动、几何不被拉伸** ——
    这三条只有真的走完 native 回调才可能出现。

## 覆盖的五件事

  0. 包本身的身份：versionCode / versionName（唯一写在 `VERSION_CODE` / `VERSION_NAME`）
     / v2+v3 签名 / **证书指纹与前序包一致**
     （指纹不一致 = 装不上，等于所有老用户必须卸载重装 = 数据全丢）
  1. **全新安装**时 release 包默认禁止截屏（不是"设置里有个开关"，是窗口上真有 FLAG_SECURE）
  2. **可覆盖升级**：先装 v1.0.9 造出一条记录与一张照片，再 `install -r` 本次的包，
     **不清数据**，断言那条记录还在、**还能打开**（能不能解密才是数据在不在的判据）
  3. **整条视频走查在 R8 之后重跑一遍**（直接复用 `walkthrough-v110-video.py` 的 B 轮）
  4. 图库多选删除仍可用（这条在 B 轮里自带）

## 与 v1.0.9 那份验收的两处差异（不是抄漏）

  · 第 4 段不再是"实况照片自动播 + 正方形不拉伸"那一套：那是 v1.0.9 改播放器尺寸时
    的判据，而本版新增的面是**视频**。R8 的风险点同一个（MediaDataSource），
    但**要走的路更长**（视频要流式解密 N 个块，实况照片只有一帧），
    所以直接把 v110 的 B 轮整轮搬过来跑，比只挑一两个动作更能说明问题。
  · "升级前基线"里导入的是**照片**而不是实况照片：v1.0.9 用户的真实库里
    绝大多数是照片，而升级路径要证的是"通用记录+附件"能活下来。

## 采样与判据的坑

判据与度量全部复用 `walkthrough-v110-video.py`（已带 `tools/motion/selftest_measure.py` 自测）。
**注意模块副作用**：v110 在模块顶层就设了 `W.shot` / `W.OUT`，所以本文件是
"先 exec_module、**再**覆盖 OUT / PHASE_TAG / SHOT_INDEX"的顺序，否则截图会悄悄落到
`dist/evidence/v110-video` 去，而本轮的证据目录里只剩零星几张。
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

# —— 唯一写死版本号的地方（见模块 docstring）。改版只动这两行 + 证据目录名。
VERSION_CODE = "17"
VERSION_NAME = "1.1.6"

OUT = rf"{BASE}\dist\evidence\v116-release"

APK_NEW = rf"{BASE}\dist\FPB-v{VERSION_NAME}.apk"
APK_OLD = rf"{BASE}\dist\FPB-v1.0.9.apk"
APK_BUILT = rf"{BASE}\app\build\outputs\apk\release\app-release.apk"

JAVA = r"C:\Program Files\Android\Android Studio\jbr\bin\java.exe"

# `am start` 之后等多久再去问窗口标志。窗口是在 `onCreate` 里就 setFlags 的，
# 所以 8 秒不是"够不够第一帧"的问题，是"窗口有没有被 WindowManager 收进去"的问题。
SECURE_SETTLE = 8

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
V110 = load(rf"{BASE}\tools\walkthrough-v110-video.py", "v110")
W = V110.W
V105 = V110.V105
R108 = V110.R108

# 三个全局都要改，理由见模块 docstring：
#   · W.OUT    —— walkthrough 的截图落点
#   · V110.OUT —— v110 自己那批（grab / burst / 选图器判定帧）的落点
#   · SHOT_INDEX —— 否则截图序号从上一轮的计数接着编，证据对不上顺序
V110.OUT = OUT
V110.PHASE_TAG = "R"
W.OUT = OUT
W.SHOT_INDEX = 0


def installed_version(attempts=3):
    """设备上这个包的 (versionName, versionCode)；**读不到要重试，不要给一个 `?`**。

    这台设备 adb 会自己掉线，掉线时 `dumpsys package` 返回的是一句
    `error: device offline`。老实现于是返回 `("?", "?")`，而调用方拿它跟
    `("1.1.0", "11")` 比 —— 报出来是"设备上装的是 ? / ?"，
    读起来像**装错了包**（版本号没对上/装成老包了），真因却是设备掉线。
    "读不到"和"读到了但不是这个版本"必须分开。
    """
    for _ in range(attempts):
        out = sh("shell", "dumpsys", "package", PKG)
        m = re.search(r"versionName=(\S+)", out)
        n = re.search(r"versionCode=(\d+)", out)
        if m and n:
            return m.group(1), n.group(1)
        W.log(f"    读版本失败（dumpsys 返回 {out.strip()[:90]!r}），重试")
        time.sleep(2)
    return "?", "?"


# `ask_secure` 被调用的次数 —— 只用来给窗口转储编号（见那里的说明）。
_SECURE_CALLS = 0


def secure_candidates(lines):
    """所有『`Window{` 且含包名』的窗口 → [(窗口行, 它 30 行内的 `fl=` 行 或 None)]。

    列出来是为了让"读到的到底是谁"可查：台面上同时有多个同包名窗口是**正常的**
    （冷启动那一下会多一个 `Splash Screen` 启动图窗口），而启动图窗口不带 `SECURE`。
    `window_secure()` 现在按**窗口名**挑活动窗口、跳过启动图；
    这份清单给日志用（[window_label] 把名字打出来），也让事后能回看原始 dump。
    """
    rows = []
    for i, line in enumerate(lines):
        if "Window{" in line and PKG in line:
            fl = None
            for j in range(i, min(i + 30, len(lines))):
                if re.match(r"\s*fl=", lines[j]):
                    fl = lines[j].strip()
                    break
            rows.append((line.strip(), fl))
    return rows


def ask_secure(attempts=3):
    """launch，然后**问到读得出来为止**。返回 `(secure, 说明)`。

    ## `None` / `False` / `True` 三者含义必须分开

      · `True`  —— 读到了这个包的窗口，`fl=` 里有 `SECURE`；
      · `False` —— 读到了窗口，`fl=` 里**没有** `SECURE`（这才是"真没开"）；
      · `None`  —— 压根没读到这个包的窗口（多半是设备掉线，或窗口还没被收进去），
                   **不是"没开"**。

    ## 为什么这一版要重试 + 落盘原始输出

    这一条在 2026-09-17 那次跑里报过
    `[FAIL] release 包开箱即禁止截屏：fl 里 SECURE=False` ——
    **而同一时刻的截图是全黑的**（`01-全新安装-默认禁止截屏.png`，16,166 B，
    与那批恒黑的引导截图同一量级）。两个独立测量互相打架：像素说"生效了"，
    标志位说"没生效"。

    事后手工复现（干净装 release → `am start` → 8/15/25 秒各 dump 一次）读到的是

        fl=LAYOUT_IN_SCREEN SECURE LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED ...

    把当时那份 dump 喂回 `R108.window_secure()` 也返回 True；候选窗口只有 1 个。
    **也就是说那次 FAIL 复现不出来** —— 而它当时没留下任何原始输出，只能靠猜。

    "复现不出来"不等于"没问题"。所以这一版把两件事做掉：
      ① **读不到（`None`）就重试**，不再把它和"真没开（`False`）"混成一句；
      ② 每次尝试的**原始 dump 都落盘**（`window-dump-N.txt`）——
         下次再有矛盾，看文件就行，不必再靠复现。

    解析**仍然**调 `R108.window_secure()`，不在这里重写一遍：
    重写就等于把"只认十六进制、于是解析器悄悄返回 None"那个坑再挖一次。
    """
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(SECURE_SETTLE)
    # 落盘的文件名带上"第几次调用"：这条判据在一次验收里会被问好几遍
    # （全新安装、覆盖升级、R8 之后各一次），文件名复用的话，
    # 事后拿着 `window-dump-1.txt` **分不清是哪一段留下的** ——
    # 而它正是用来裁决"那一次为什么报错"的现场。2026-09-18 就吃了这一口。
    global _SECURE_CALLS
    _SECURE_CALLS += 1
    for attempt in range(1, attempts + 1):
        out = sh("shell", "dumpsys", "window", "windows")
        name = f"window-dump-{_SECURE_CALLS}-{attempt}.txt"
        with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(out)
        secure = R108.window_secure()
        cands = secure_candidates(out.splitlines())
        why = (f"第 {attempt} 次：候选窗口 {len(cands)} 个，原始输出见 {name}")
        if secure is not None:
            if len(cands) > 1:
                # 把候选的**窗口名**打出来：扣的是哪个窗口必须一眼可查。
                # 2026-09-18 那次假红就是扣到了 `Splash Screen com.fpb.vault`
                # （启动图窗口本来就不带 SECURE），而真正带标志的
                # `com.fpb.vault/com.fpb.vault.MainActivity` 排在它后面。
                W.log(f"    ⚠ 有 {len(cands)} 个同包名窗口，按窗口名挑活动窗口（跳过启动图）："
                      + "、".join(window_label(c) for c, _ in cands)
                      + f"；若结论不合常理，先看 {name}")
            return secure, why
        W.log(f"    {why}，但没读到本包的活动窗口（dump {len(out.splitlines())} 行）—— 重试")
        time.sleep(2)
    return None, f"问了 {attempts} 次都没读到本包的活动窗口（原始输出见 window-dump-*.txt）"


def window_label(cand_line):
    """候选窗口行 → `Window{…}` 里的那个名字。

    列出来是为了让"读到的到底是谁"可查 —— 光说"候选 2 个"看不出扣错了。
    """
    m = re.search(r"Window\{([^}]*)\}", cand_line)
    return m.group(1) if m else cand_line[:48]


def launch_and_secure_check():
    """全新安装后 launch 一下就问窗口标志 —— 不必走完引导。

    用 v108 那份已经踩过坑的 `window_secure()`（符号名与十六进制两种格式都认），
    外加 [ask_secure] 的重试与留证。
    """
    return ask_secure()


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
                try:
                    W.tap_text("解锁")
                except RuntimeError:
                    # **不要把这一下当成应用的问题。** `hide_ime()` 收键盘的手段是发 BACK，
                    # 在"键盘其实已经收起"时它会多打一次 —— 解锁页把这个 BACK 当成"退出"，
                    # 应用于是回到桌面，屏幕上自然没有「解锁」可点。
                    # 2026-09-18 run5 就停在这里（同一类坑在本轮第 2 条量具事故里也出现过）。
                    # 处理方式：记一句、回到循环顶部重新 `am start` 拉起，不当失败。
                    W.log("    没找到「解锁」按钮（多半被多余的 BACK 送回桌面了）——重新拉起再等")
                    time.sleep(2.0)
                    continue
                time.sleep(3.0)
        else:
            # 既不在主页、也不在解锁页 —— 多半是被多余的 BACK 送到桌面了，重新拉起。
            W.log("    既不在主页也不在解锁页 —— 重新拉起应用")
            sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
            time.sleep(4.0)
        time.sleep(1.5)
    raise RuntimeError(f"等不到主页（其中 {blind} 轮连屏幕都读不到）")


def tap_one_photo(cells, a):
    """勾一张**不是红色缩略图**的格子，然后点确认。

    两段探针影片是纯红底（见 `tools/_mkvideos.py`），所以"非红"就等于"相册里的照片"。
    在 v1.0.9 上应用给选图器下的是图片过滤器，红块本来就该是 0 —— 这里不假设这一点，
    按颜色排除，两种版本上都能选中同一类东西。
    """
    blobs = V110.picker_red_blobs(a)
    red = [(b["cx"], b["w"]) for b in blobs]

    def is_red(c):
        return any(abs(c["cx"] - bx) <= w // 2 + 20 for bx, w in red)

    cand = [c for c in cells if not is_red(c)]
    assert cand, f"选图器里没有非红的格子（可勾 {len(cells)}、红 {len(blobs)}）"
    c = cand[0]
    W.log(f"    勾一张照片（{c['cx']},{c['cy']}）{c['w']}×{c['h']}，"
          f"可勾格子 {len(cells)}、红色 {len(blobs)}")
    for i, x in enumerate(cand[1:6], start=2):
        W.log(f"      候选 {i}：（{x['cx']},{x['cy']}）{x['w']}×{x['h']}")
    W.tap(c["cx"], c["cy"])

    # 点完**轮询**等确认按钮，并且把"读不到屏幕"与"树里真没有"分开。
    #
    # 2026-09-17 第三次假红：原来 `sleep 0.9` 之后只查一次，且拿 `W.find` 直接问 ——
    # 一旦 dump 抖动，`W.find` 抛的 RuntimeError 被 `or` 链吞成 None，
    # 报出来的是"没有出现确认按钮"，把量具的问题说成了应用的问题。
    # 那次失败连现场节点树都没留下，只能事后靠猜（怀疑点到了 126×127 的表头控件）。
    # 所以这里两件事一起做：轮询 + 失败存树。
    done, blind, last_err = None, 0, None
    end = time.time() + 15
    while time.time() < end:
        try:
            ns = V110.rnodes()
        except RuntimeError as e:
            blind += 1
            last_err = e
            time.sleep(1.0)
            continue
        # 一次抓树、多次查找（rfind 支持传入 ns），避免"每查一个词就重抓一次"
        done = (V110.rfind("Done", ns) or V110.rfind("完成", ns)
                or V110.rfind("Add", ns) or V110.rfind("添加", ns)
                or V110.rfind("选择", ns))
        if done is not None:
            break
        time.sleep(0.6)

    if done is None:
        tree = os.path.join(OUT, f"{V110.PHASE_TAG}-选图器-无确认按钮.tree.xml")
        try:
            os.makedirs(OUT, exist_ok=True)
            p = subprocess.run([ADB, "exec-out", "uiautomator", "dump", "/dev/tty"],
                               capture_output=True, timeout=60)
            with open(tree, "wb") as f:
                f.write(p.stdout + p.stderr)
            saved = os.path.basename(tree)
        except Exception as e:                      # 存证失败不能盖过原始结论
            saved = f"（存盘失败：{e}）"
        extra = f"；其中 {blind} 轮连屏幕都读不到：{last_err}" if blind else ""
        raise AssertionError(f"选中一张照片之后没有出现确认按钮"
                             f"（现场节点树 {saved}{extra}）")

    W.log(f"    点确认（「{done['text'] or done['desc']}」）")
    W.tap(done["cx"], done["cy"])


def wait_media_count(want, timeout=180):
    """等页头的计数变成 want。导入要落盘 + 建记录，不是瞬时的。"""
    end = time.time() + timeout
    last = None
    while time.time() < end:
        try:
            last = V110.media_count()
        except RuntimeError:
            time.sleep(1.0)
            continue
        if last == want:
            return last
        time.sleep(1.5)
    return last


def wait_device(timeout=90):
    """等 adb 回到 device 状态。

    **这台机器上 adb server 会自己掉**（实测反复出现 `daemon not running; starting now`），
    而掉线期间 adb 命令会带着一句 `error: device offline` **正常返回**（不抛异常、
    退出码也不一定非零）。于是"调了卸载、没看结果、直接装"这条路上，
    卸载可能压根没执行，下一步才以一句 `INSTALL_FAILED_UPDATE_INCOMPATIBLE:
    signatures do not match` 冒出来 —— 症状指向"签名配错了"，而真因是设备掉线。
    2026-09-17 第一次跑本脚本就是这么挂的。

    所以：每一步"必须成功"的 adb 调用之前先确认设备在线，之后**验结果**。
    """
    end = time.time() + timeout
    while time.time() < end:
        p = subprocess.run([ADB, "get-state"], capture_output=True, timeout=60)
        blob = (p.stdout + p.stderr).decode("utf-8", "replace")
        if "device" in blob and "offline" not in blob:
            return True
        subprocess.run([ADB, "start-server"], capture_output=True, timeout=120)
        time.sleep(2)
    return False


def pkg_installed():
    """设备在线时才可信；离线时返回 True（保守：当作还装着，让调用方重试）。"""
    if not wait_device(timeout=30):
        return True
    return PKG in sh("shell", "pm", "list", "packages")


def uninstall_pkg():
    """卸载，并**确认真的没了** —— 不只看 `Success` 那句话。

    这台模拟器上实测过一个反常现象：`adb uninstall` 连续三次回
    `Failure [DELETE_FAILED_INTERNAL_ERROR]`，而**包其实已经删掉了** ——
    紧接着 `install` 成功，且 `pm list packages` 里查不到它。
    也就是说 `DELETE_FAILED_INTERNAL_ERROR` 在这台设备上会"误报"。
    所以判据是**复核包的存亡**，不是那句话；两次结论不一致时以复核为准，
    并把不一致本身记下来（否则事后翻日志会以为卸载失败了）。
    """
    for attempt in range(1, 4):
        wait_device()
        out = sh("uninstall", PKG)
        if "Success" in out and not pkg_installed():
            return True
        W.log(f"    卸载第 {attempt} 次返回 {out.strip()[:100]}")
        time.sleep(2)
    gone = not pkg_installed()
    W.log("    三次返回都不是 Success，但复核后包"
          + ("确实已消失（这台设备会误报 DELETE_FAILED_INTERNAL_ERROR）" if gone
             else "**仍然在**，下面这一步大概率会失败"))
    return gone


def install_apk(apk):
    assert wait_device(), "adb 设备始终不在线"
    return sh("install", "-r", "-g", apk)


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
    note("证书指纹", f"{dig_new}\n          （v1.0.9：{dig_old}）")

    check(f"versionCode/Name = {VERSION_CODE} / {VERSION_NAME}",
          (code, name) == (VERSION_CODE, VERSION_NAME), f"{code} / {name}")
    check("v2 与 v3 签名都有效", v2 and v3, f"v2={v2} v3={v3}")
    check("v1 关闭（minSdk 26 起只会多一份可被篡改的清单）", v1 is False, f"v1={v1}")
    check("证书指纹与前序包一致（能覆盖升级的前提）", dig_new == dig_old, f"{dig_new}")
    check("体积落在 release 包的合理量级（不是未签名的残包）",
          1_000_000 < size < 9_000_000, f"{size:,} B")

    # ============ 1. 全新安装：release 默认禁止截屏 ============
    print(f"\n=== 1. 全新安装 v{VERSION_NAME}：默认禁止截屏 ===")
    assert uninstall_pkg(), "卸载残留的同名包失败（签名不同就装不上）"
    out = install_apk(APK_NEW)
    assert "Success" in out, f"安装失败：{out}"
    name_dev, code_dev = installed_version()
    check(f"设备上装的是 {VERSION_NAME} / code {VERSION_CODE}",
          (name_dev, code_dev) == (VERSION_NAME, VERSION_CODE),
          f"{name_dev} / {code_dev}")

    secure, why = launch_and_secure_check()
    # 像素这条**先量**，因为它要参与下面那条判据的细节：
    # 两个独立测量（窗口标志 / 截图亮度）如果互相打架，本身就是缺陷信号 ——
    # 2026-09-17 那次就是"标志位说没开、像素说全黑"，而当时没把这句矛盾写进日志。
    V110.grab("01-全新安装-默认禁止截屏")
    fr = R108.screen_frame()
    detail = f"fl 里 SECURE={secure}（{why}）"
    if secure is False and fr is not None and fr[2] < 8.0:
        detail += " ⚠ 但像素是全黑 —— 两个独立测量打架，先怀疑量具（看 window-dump-*.txt）"
    elif secure is None:
        detail += "（注意：这是**读不到**，不等于「没开」）"
    check("release 包开箱即禁止截屏（窗口上真有 FLAG_SECURE）", secure is True, detail)
    check("此时这一帧确实是黑的（像素证据）", fr is not None and fr[2] < 8.0,
          f"平均亮度 {fr[2]:.2f}/255，亮像素占比 {fr[3] * 100:.2f}%" if fr else "量不到画面")

    # ============ 2. 造"旧数据"：装 v1.0.9 并导入一张照片 ============
    print("\n=== 2. 装 v1.0.9、导入一张照片，作为「升级前」的基线 ===")
    assert uninstall_pkg(), f"卸载 v{VERSION_NAME} 失败"
    out = install_apk(APK_OLD)
    assert "Success" in out, f"安装 v1.0.9 失败：{out}"
    V110.warm_up_uiautomator()
    V105.onboarding()                      # 内含 pm clear
    V110.grant_screenshots()               # release 默认禁截屏，关掉才有视觉证据

    V110.open_media_library("原图加密存储")
    cells, _blobs, im = V110.await_picker()
    W.shot("升级前-选图器")
    tap_one_photo(cells, im)
    before = wait_media_count(1)
    check("v1.0.9 里已导入 1 张照片", before == 1, f"库里 {before} 张")
    # 退回并退出应用：让这一次导入的写入彻底落定（DB 事务 + 密文 rename）再做覆盖升级。
    # 在"应用还开着"的状态下 install -r，多多少少是在测一个用户不会遇到的时序。
    sh("shell", "input", "keyevent", "4")
    time.sleep(1.5)
    sh("shell", "input", "keyevent", "4")
    time.sleep(1.5)

    # ============ 3. 覆盖升级到本次的包（不清数据） ============
    print(f"\n=== 3. install -r 覆盖升级到 v{VERSION_NAME}（**不做 pm clear**）===")
    out = install_apk(APK_NEW)
    assert "Success" in out, f"覆盖安装失败：{out}"
    name_dev, code_dev = installed_version()
    check(f"升级后版本变成 {VERSION_NAME} / code {VERSION_CODE}",
          (name_dev, code_dev) == (VERSION_NAME, VERSION_CODE),
          f"{name_dev} / {code_dev}")

    wait_home()
    V110.open_media_library("原图原片加密存储")
    after = wait_media_count(1)
    check("升级后那张照片还在（数据没被清）", after == 1, f"库里 {after} 项")
    fr2 = R108.screen_frame()
    check("升级后「禁止截屏」这个偏好也随数据保留下来（画面不再是黑的）",
          fr2 is not None and fr2[2] > 8.0,
          f"平均亮度 {fr2[2]:.2f}/255" if fr2 else "量不到画面")
    V110.grab("03-升级后-照片仍在")

    # 数据在不在，最终要看**还能不能解密**：记录行还在但密钥/密文丢了，
    # 表现同样是"库里有一项"。点开它。
    cell = next((n for n in W.nodes()
                 if n["clickable"] and not (n["text"] or n["desc"])
                 and 280 < n["y1"] < 1900), None)
    if check("升级后照片库里找得到缩略图", cell is not None,
             f"{cell and (cell['cx'], cell['cy'])}"):
        W.tap(cell["cx"], cell["cy"])
        end = time.time() + 45
        opened = False
        while time.time() < end:
            if W.find("关闭") is not None:
                opened = True
                break
            time.sleep(1.0)
        check("升级后那张照片仍能打开（密文与密钥都还在）", opened,
              "看到大图页的「关闭」" if opened else "点开之后没进大图页")
        V110.grab("03-升级后-照片能打开")
        V110.leave_viewer()

    # ============ 4. R8 之后的 release 包：整条视频链路重跑一遍 ============
    print("\n=== 4. release 包上的视频链路（复用 v110 走查的 B 轮，判据一条不减）===")
    V110.APK_NEW = APK_NEW              # 把走查里的"本次实现版"指到 R8 之后的包
    V110.FAILURES = []                  # 单独收，末尾并入本轮的 RESULTS

    # **必须补的这一刀。** 走查是按 debug 包写的，而 debug 包默认不禁截屏
    # （由 `BuildConfig.DEBUG` 决定）—— 换成 release 包之后，
    # `V105.onboarding()` 里的那次 `pm clear` 会把"禁止截屏"的偏好冲回**默认开**，
    # 于是 B 轮所有像素判据量的都是一张黑图：`grid_filled` 永远为假（选图器空转 180 秒）、
    # 红块识别到 0 个、条带与白块更无从谈起。
    # 症状会**长得很像功能坏了**，而真正的原因是截图被窗口挡了。
    # 把 onboarding 包一层，做完就立刻关掉它 —— 与 A 轮的做法一致。
    _real_onboarding = V105.onboarding

    def onboarding_then_allow_screenshots():
        _real_onboarding()
        V110.grant_screenshots()

    V105.onboarding = onboarding_then_allow_screenshots

    try:
        V110.run_phase_b()
    finally:
        for f in V110.FAILURES:
            RESULTS.append((f"（B 轮）{f}", False, "见 build 日志"))
    # B 轮自己用 print 报 PASS/FAIL，条数从日志里数不准；这里用它的 FAILURES 判：
    # 为空即"那 40 多条判据一条没红"。
    check("R8 之后的 release 包：v110 走查的 B 轮全部判据无一失败",
          not V110.FAILURES, f"未通过 {len(V110.FAILURES)} 条")

    print()
    failed = [r for r in RESULTS if not r[1]]
    W.log(f"共 {len(RESULTS)} 条判据，未通过 {len(failed)} 条")
    if failed:
        for label, _, detail in failed:
            print(f"    · {label} —— {detail}")
        return 1
    W.log(f"v{VERSION_NAME} 正式包验收全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
