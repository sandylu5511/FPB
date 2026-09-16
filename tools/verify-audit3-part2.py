# -*- coding: utf-8 -*-
"""第三轮审核真机验证（续）：B 的日志补测 + C 的自愈端到端。

## 为什么要换一种方式制造"别名坏掉"

`adb shell pm disable <component>` 在这台设备上被系统直接拒绝：

    java.lang.SecurityException: Shell cannot change component state for
      ComponentInfo{com.fpb.vault/com.fpb.vault.alias.Notes} to 2

（Android 14 起 shell 不再有权限改第三方应用的组件状态；`adb root` 也拿不到 ——
镜像是 production build。）因此改用**偏好文件**制造同一个可观测的坏状态：

  把 `shared_prefs/fpb_settings.xml` 里的 `launcher_alias` 改成
  `com.fpb.vault.alias.Notes`，而系统里实际启用的是 `.alias.Fpb`。

于是 `LauncherIcon.verify(ctx, "…alias.Notes")` 必为 false，**自愈分支被真正驱动**。
它是 debug 包，`run-as` 可读写应用私有目录，所以这个手法可行。

预期结果（若修复生效）：
  · `cmd package query-activities` 的桌面入口从 `.alias.Fpb` 变成 `.alias.Notes`
  · `fpb_settings.xml` 里的 `launcher_alias` 被应用回写成规范值
把偏好改回 `.alias.Fpb` 再重启一次，图标应当自己转回来 —— 同一个机制双向生效。
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import walkthrough as W

PKG = "com.fpb.vault"
OUT = r"D:\MixiaVault\dist\evidence\audit3-20260916"
PREFS = "shared_prefs/fpb_settings.xml"

failures = []


def sh(*args, timeout=60):
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


def expect(cond, what, detail=""):
    print(f"  {'✓' if cond else '✗'} {what}{('  ' + detail) if detail else ''}")
    if not cond:
        failures.append(what)


def write_alias_pref(alias):
    """把偏好里的 launcher_alias 改成 [alias]（先停进程，避免被应用回写覆盖）。

    ## 为什么不用 sed

    第一版用 `run-as ... sed -i 's|...name="launcher_alias"...|...|'`，**静默失败**：
    adb shell 会把命令再交给远端 shell 解析一次，表达式的双引号在这一层被吃掉，
    于是 sed 收到的模式里 `name=launcher_alias` 没有引号，永远匹配不上 ——
    而 sed 匹配不上时既不报错也不改文件，看起来就像"应用把改动回写覆盖了"。

    改成"本地改好 → push → run-as cp"：全程没有需要转义的字符。
    """
    sh("am", "force-stop", PKG)
    time.sleep(1.0)
    xml = run_as("cat", PREFS)
    entry = '    <string name="launcher_alias">%s</string>' % alias
    if "launcher_alias" in xml:
        out = re.sub(r'[ \t]*<string name="launcher_alias">[^<]*</string>', entry, xml)
    else:
        out = xml.replace("<map>", "<map>\n" + entry, 1)

    local = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_prefs_tmp.xml")
    with open(local, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    sh("rm", "-f", "/data/local/tmp/fpb_settings.xml")
    subprocess.run([W.ADB, "push", local, "/data/local/tmp/fpb_settings.xml"],
                   capture_output=True, text=True)
    sh("run-as", PKG, "cp", "/data/local/tmp/fpb_settings.xml", PREFS)
    sh("rm", "-f", "/data/local/tmp/fpb_settings.xml")
    os.remove(local)
    return run_as("cat", PREFS)


def main():
    os.makedirs(OUT, exist_ok=True)
    log_lines = []

    # ==================== B（补测）：提示条是否走完完整时长 ====================
    print("\n========== B（补测）操作反馈：等待完整展示时长后再看日志 ==========")
    subprocess.run([W.ADB, "logcat", "-c"], capture_output=True)
    W.ensure_home()
    gear = W.find("设置")
    W.tap(gear["cx"], gear["cy"])
    time.sleep(1.5)
    W.scroll_top()
    n = W.tap_text_scrolling("清理无用图片")
    W.log(f"已点「清理无用图片」@ {n['cx']},{n['cy']}")
    # Snackbar 是 Short 时长（约 4 秒）。上一次只等了 1.2 秒就读日志，
    # 那时 showSnackbar 还没返回，自然只有"启动"没有"结束" —— 是测量方法的问题。
    W.log("等 6 秒让提示条展示完再读日志…")
    time.sleep(6.0)
    logs = subprocess.run([W.ADB, "logcat", "-d", "-s", "FPB-SNACK:I"],
                          capture_output=True, text=True, encoding="utf-8",
                          errors="replace").stdout.strip()
    print(logs or "（没有任何 FPB-SNACK 日志）")
    log_lines.append("=== logcat FPB-SNACK ===\n" + (logs or "(空)"))
    expect("showSnackbar 正常结束" in logs, "showSnackbar 正常结束（修复前是「被中断」）")
    expect("被中断" not in logs, "没有出现协程被取消")

    # ==================== C：桌面别名自愈（端到端） ====================
    print("\n========== C 桌面别名自愈：把偏好改成与实际不符 ==========")
    base = entries()
    expect(base == [f"{PKG}/.alias.Fpb"], "基线：桌面入口是 .alias.Fpb", str(base))

    after_xml = write_alias_pref("com.fpb.vault.alias.Notes")
    print("  改后的偏好：")
    for line in after_xml.splitlines():
        if "launcher_alias" in line:
            print("   ", line.strip())
    log_lines.append("=== 改后的偏好 ===\n" + after_xml)
    expect("alias.Notes" in after_xml, "偏好已被改成 .alias.Notes（制造出坏状态）")

    W.log("冷启动应用，观察是否把图标修回来")
    sh("am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(8)
    healed = entries()
    expect(healed == [f"{PKG}/.alias.Notes"], "桌面入口被修成 .alias.Notes", str(healed))
    back_xml = run_as("cat", PREFS)
    log_lines.append("=== 自愈后的偏好 ===\n" + back_xml)
    expect("alias.Notes" in back_xml, "偏好被应用回写为规范值")
    W.shot("C-自愈后-图标已切到备忘录")

    # 再翻回来，证明同一机制双向生效
    write_alias_pref("com.fpb.vault.alias.Fpb")
    sh("am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(8)
    final = entries()
    expect(final == [f"{PKG}/.alias.Fpb"], "改回 .alias.Fpb 后图标也自己转回来", str(final))
    W.shot("C-转回后-图标恢复FPB")

    with open(os.path.join(OUT, "alias-heal-and-snackbar.txt"), "w", encoding="utf-8") as f:
        f.write("\n\n".join(log_lines) + "\n\n最终桌面入口: %s\n" % final)

    print("\n========== 汇总 ==========")
    if failures:
        for x in failures:
            print("  ✗", x)
        sys.exit(1)
    print("  全部通过")


if __name__ == "__main__":
    main()
