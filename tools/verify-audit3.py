# -*- coding: utf-8 -*-
"""第三轮审核的三项真机验证。

## A. 覆盖安装不丢数据（这是用户第二个问题的实测依据）

做法是**比对文件指纹**，不是"看起来还在"：
`adb install -r` 前后各跑一次 `run-as` 列目录 + `md5sum`，
哈希逐字节一致才算证明"数据一个字节都没动"。
之后再冷启动、用主密码解锁，确认条目数与图片数都还在。

## B. 缺陷 #1：操作反馈（Snackbar）到底显不显示

判据是应用自己打的日志。修复前是：
    03:59:12.239 I FPB-SNACK: effect 启动 text=没有发现无用图片
    03:59:12.276 I FPB-SNACK: showSnackbar 被中断：LeftCompositionCancellationException
修复后应当是 `showSnackbar 正常结束`。同时补一张底部截图，人眼也能确认。

## C. 缺陷 #4：桌面别名坏掉之后能不能自愈

用 `cmd package query-activities` 看**桌面实际能查到什么**（这是启动器看到的同一个视角），
而不是读组件状态字段：
  1. 基线：应当恰好 1 个 LAUNCHER 入口；
  2. 把三个别名全部 disable（模拟"apply 两步之间进程被杀 + 第三方工具整组禁用"）；
  3. 重新启动应用（此时桌面上已经没有入口，只能靠 am start）；
  4. 自愈后应当又回到恰好 1 个入口。
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import walkthrough as W

PKG = "com.fpb.vault"
APK = r"D:\MixiaVault\app\build\outputs\apk\debug\app-debug.apk"
OUT = r"D:\MixiaVault\dist\evidence\audit3-20260916"
ALIASES = [".alias.Fpb", ".alias.Notes", ".alias.Calc"]

failures = []


def sh(*args, timeout=60):
    return subprocess.run([W.ADB, "shell"] + [str(a) for a in args],
                          capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout).stdout


def run_as(*args):
    return sh("run-as", PKG, *args)


def fingerprint(label):
    """数据目录指纹：文件名 + 大小 + md5。"""
    print(f"--- {label} ---")
    listing = run_as("ls", "-l", "files", "databases", "shared_prefs")
    print(listing.strip())
    hashes = run_as("md5sum", "files/fpb.key", "databases/vault.db")
    print(hashes.strip())
    # 图片密文单独统计（文件名是随机的 32 位 hex，逐个列出来太长）
    blob = run_as("sh", "-c", "ls -l files/attachments | wc -l; "
                              "cat files/attachments/* 2>/dev/null | md5sum")
    print("附件目录：", blob.strip().replace("\n", " | "))
    return {"listing": listing.strip(), "hashes": hashes.strip(), "blob": blob.strip()}


def launcher_entries():
    out = sh("cmd", "package", "query-activities", "--brief",
             "-a", "android.intent.action.MAIN",
             "-c", "android.intent.category.LAUNCHER")
    return sorted(l.strip() for l in out.splitlines() if PKG in l)


def expect(cond, what, detail=""):
    mark = "✓" if cond else "✗"
    print(f"  {mark} {what}{('  ' + detail) if detail else ''}")
    if not cond:
        failures.append(what)
    return cond


def main():
    os.makedirs(OUT, exist_ok=True)

    # ==================== A. 覆盖安装不丢数据 ====================
    print("\n==================== A. 覆盖安装是否丢数据 ====================")
    W.ensure_home()
    before = fingerprint("安装前")
    entries_before = launcher_entries()
    print("桌面入口：", entries_before)

    print(f"\n>>> adb install -r {os.path.basename(APK)}")
    r = subprocess.run([W.ADB, "install", "-r", APK], capture_output=True,
                       text=True, encoding="utf-8", errors="replace")
    print(r.stdout.strip(), r.stderr.strip())
    expect("Success" in (r.stdout + r.stderr), "覆盖安装成功")

    after = fingerprint("安装后")
    expect(before["hashes"] == after["hashes"] and before["hashes"],
           "密钥文件与数据库的 md5 逐字节一致",
           before["hashes"].split("\n")[0].split()[0] if before["hashes"] else "")
    expect(before["listing"] == after["listing"], "数据目录清单完全一致")
    expect(before["blob"] == after["blob"], "图片密文集合完全一致")

    # 冷启动 → 解锁 → 确认内容还在
    W.ensure_home()
    home_txt = " ".join(n["text"] for n in W.nodes())
    expect("本机加密 · 1 条" in home_txt, "解锁后条目数仍为 1 条", home_txt[:60])
    expect("4 张图片" in home_txt, "图片数仍为 4 张")
    W.shot("A-覆盖安装后-数据仍在")

    # ==================== B. Snackbar 是否真的显示 ====================
    print("\n==================== B. 操作反馈是否真的显示 ====================")
    subprocess.run([W.ADB, "logcat", "-c"], capture_output=True)
    W.ensure_home()
    gear = W.find("设置")
    W.tap(gear["cx"], gear["cy"])
    time.sleep(1.5)
    W.scroll_top()
    n = W.tap_text_scrolling("清理无用图片")
    W.log(f"已点「清理无用图片」@ {n['cx']},{n['cy']}")
    time.sleep(1.2)
    W.shot("B-清理无用图片-应有提示条")
    logs = subprocess.run([W.ADB, "logcat", "-d", "-s", "FPB-SNACK:I"],
                          capture_output=True, text=True, encoding="utf-8",
                          errors="replace").stdout.strip()
    print(logs)
    with open(os.path.join(OUT, "snackbar-logcat.txt"), "w", encoding="utf-8") as f:
        f.write(logs + "\n")
    expect("showSnackbar 正常结束" in logs, "提示条走完了完整时长（修复前是「被中断」）")
    expect("被中断" not in logs, "没有出现协程被取消")

    # ==================== C. 桌面别名自愈 ====================
    print("\n==================== C. 桌面别名坏了能否自愈 ====================")
    base = launcher_entries()
    expect(len(base) == 1, "基线：桌面入口恰好 1 个", str(base))

    for alias in ALIASES:
        sh("pm", "disable", f"{PKG}/{PKG}{alias}")
    time.sleep(1.0)
    broken = launcher_entries()
    expect(len(broken) == 0, "强制禁用三个别名后桌面入口为 0（模拟坏状态）", str(broken))

    W.log("重新启动应用（此时桌面上已经没有入口）")
    sh("am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(6)
    healed = launcher_entries()
    expect(len(healed) == 1, "自愈后桌面入口恢复为 1 个", str(healed))
    print("  dumpsys 组件状态：")
    d = sh("dumpsys", "package", PKG)
    for line in d.splitlines():
        if "alias" in line and "enabled" in line:
            print("   ", line.strip())
    with open(os.path.join(OUT, "launcher-alias-recovery.txt"), "w", encoding="utf-8") as f:
        f.write("基线：%s\n强制禁用后：%s\n自愈后：%s\n" % (base, broken, healed))

    # ==================== 汇总 ====================
    print("\n==================== 汇总 ====================")
    if failures:
        for f_ in failures:
            print("  ✗", f_)
        print(f"\n{len(failures)} 项未通过")
        sys.exit(1)
    print("  全部通过")


if __name__ == "__main__":
    main()
