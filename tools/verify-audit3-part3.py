# -*- coding: utf-8 -*-
"""第三轮审核真机验证（收尾）：最终包覆盖安装 + 全新安装行为。

## A2：最终包（已移除临时诊断日志）再证一次覆盖安装不丢数据

## D：卸载重装 —— 这是唯一能拿到"组件状态全为 DEFAULT"的干净环境

为什么要专门做这一步：`LauncherIcon.isEnabled` 的缺陷是"把系统的 DEFAULT 当成 DISABLED"。
在**全新安装**时，清单里 `.alias.Fpb` 写着 `android:enabled="true"`，但系统里三个别名
都还是 DEFAULT：

  · 修好的判据 → `verify()` 为真 → 自愈分支**不触发** → 偏好文件里不会出现 `launcher_alias`
  · 有缺陷的判据 → `verify()` 为假 → 自愈分支触发 → 会调用 `apply()` 并**回写**偏好文件

于是"偏好文件里有没有 `launcher_alias` 这个键"就是这次修复的可观测证据。
（`pm clear` 拿不到这个环境：它清应用数据但**不清组件状态**；只有卸载才会把
`package-restrictions.xml` 里那份状态一起删掉。）

顺带这也记录了"卸载 = 数据全丢"的现场，以及全新安装后应当看到什么。
"""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import walkthrough as W

PKG = "com.fpb.vault"
APK = r"D:\MixiaVault\app\build\outputs\apk\debug\app-debug.apk"
OUT = r"D:\MixiaVault\dist\evidence\audit3-20260916"

failures = []


def sh(*args, timeout=120):
    return subprocess.run([W.ADB, "shell"] + [str(a) for a in args],
                          capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout).stdout


def run_as(*args):
    return sh("run-as", PKG, *args)


def entries():
    out = sh("cmd", "package", "query-activities", "--brief",
             "-a", "android.intent.action.MAIN",
             "-c", "android.intent.category.LAUNCHER")
    return sorted(l.strip() for l in out.splitlines() if PKG in l)


def install(flag):
    r = subprocess.run([W.ADB, "install"] + (["-r"] if flag else []) + [APK],
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace")
    return (r.stdout + r.stderr).strip()


def expect(cond, what, detail=""):
    print(f"  {'✓' if cond else '✗'} {what}{('  ' + detail) if detail else ''}")
    if not cond:
        failures.append(what)


def main():
    os.makedirs(OUT, exist_ok=True)
    notes = []

    # ==================== A2：最终包覆盖安装 ====================
    print("\n========== A2 最终包（已移除诊断日志）覆盖安装 ==========")
    W.ensure_home()
    before = run_as("md5sum", "files/fpb.key", "databases/vault.db").strip()
    print(before)
    out = install(flag=True)
    print(out)
    expect("Success" in out, "覆盖安装成功")
    after = run_as("md5sum", "files/fpb.key", "databases/vault.db").strip()
    expect(before == after and before, "数据文件 md5 依然逐字节一致")

    W.ensure_home()
    home = " ".join(n["text"] for n in W.nodes())
    expect("本机加密 · 1 条" in home, "解锁后条目数仍为 1 条")
    expect("4 张图片" in home, "图片仍为 4 张")

    # 提示条：最终包里已经没有 FPB-SNACK 日志，改为纯截图判据
    gear = W.find("设置")
    W.tap(gear["cx"], gear["cy"])
    time.sleep(1.5)
    W.scroll_top()
    W.tap_text_scrolling("清理无用图片")
    time.sleep(1.5)
    shot = W.shot("D-最终包-提示条")
    notes.append("最终包提示条截图：%s" % shot)

    # ==================== D：卸载重装 ====================
    print("\n========== D 卸载重装：全新安装的干净状态 ==========")
    sh("am", "force-stop", PKG)
    r = subprocess.run([W.ADB, "uninstall", PKG], capture_output=True, text=True)
    print(r.stdout.strip())
    expect("Success" in r.stdout, "卸载成功")
    gone = run_as("cat", "files/fpb.key").strip()
    expect("No such file" in gone or "not found" in gone.lower() or gone == "",
           "卸载后密钥文件已不存在（数据目录被删）", gone[:60])

    out = install(flag=False)
    print(out)
    expect("Success" in out, "全新安装成功")
    expect(entries() == [f"{PKG}/.alias.Fpb"], "全新安装后桌面入口是 .alias.Fpb", str(entries()))

    W.log("冷启动，观察是否误触发自愈")
    sh("am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(9)
    prefs = run_as("cat", "shared_prefs/fpb_settings.xml").strip()
    print("  全新安装后的偏好文件：")
    print("   ", prefs.replace("\n", "\n    ") or "(文件不存在)")
    expect("launcher_alias" not in prefs,
           "偏好里没有 launcher_alias —— 自愈没有被误触发（DEFAULT 已被正确识别为启用）")

    nodes = " ".join(n["text"] for n in W.nodes())
    expect(any(k in nodes for k in ("创建", "主密码", "开始")),
           "停在引导（建库）流程，而不是解锁页", nodes[:60])
    W.shot("D-全新安装-引导流程")
    notes.append("全新安装后的偏好文件：\n" + (prefs or "(不存在)"))

    with open(os.path.join(OUT, "fresh-install-and-final-apk.txt"), "w", encoding="utf-8") as f:
        f.write("\n\n".join(notes) + "\n")

    print("\n========== 汇总 ==========")
    if failures:
        for x in failures:
            print("  ✗", x)
        sys.exit(1)
    print("  全部通过")


if __name__ == "__main__":
    main()
