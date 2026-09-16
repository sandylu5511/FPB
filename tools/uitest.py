#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""adb 界面操作小工具（Windows / Git Bash 友好）。

用途：在模拟器或真机上稳定地走一遍界面流程并留证。之所以要包一层，
是因为 `uiautomator dump` 的输出是带命名空间的 XML，直接 grep 出来的
bounds 字符串（" [x1,y1][x2,y2] "）不好直接用来点，手算中心点很容易算错。

子命令：
  dump                     打印当前界面所有可点/可读节点的 文本、resource-id、中心点
  tap-text <文本>          点击第一个文本（或 content-desc）匹配的节点
  tap-id <resource-id>     按 resource-id 点击
  tap <x> <y>              按坐标点击
  text <字符串>            在当前焦点控件里输入文本（不支持中文，用 input-text-cn）
  input-text-cn <字符串>   输入含中文的文本（走 ADBKeyboard 之外的剪贴板方案，见下）
  key <KEYCODE>            发送按键，如 BACK / ENTER
  shot <路径>              截图到本地
  launch                   启动应用主界面
  stop                     强制停止应用
  logcat-clear / logcat-crash   清日志 / 只看崩溃

关于中文输入：`adb shell input text` 不接受非 ASCII。这里用
"先 base64 写进剪贴板再粘贴"的办法绕过 —— Android 上写剪贴板需要
一个能接收 IME 动作的应用，因此实际做法是把文本通过 `am broadcast` 交给
系统的剪贴板服务不可行；可靠的做法是安装 ADBKeyboard。本工具在
`input-text-cn` 里会明确报错并提示用 ADBKeyboard，而不是静默丢字符。
"""

import base64
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", r"D:/AndroidSdk/platform-tools/adb.exe")
PKG = "com.fpb.vault"


def adb(*args: str, timeout: int = 60) -> str:
    result = subprocess.run(
        [ADB, *args], capture_output=True, timeout=timeout
    )
    out = result.stdout.decode("utf-8", errors="replace")
    err = result.stderr.decode("utf-8", errors="replace")
    if result.returncode != 0 and "error" in err.lower():
        sys.stderr.write(f"[adb {args[0]}] {err.strip()}\n")
    return out


def dump_xml(retries: int = 3) -> str:
    """把界面层级 dump 到 /sdcard 再读出来。uiautomator 偶尔会失败，因此重试。"""
    last = ""
    for i in range(retries):
        adb("shell", "rm", "-f", "/sdcard/ui.xml")
        last = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        if "dumped to" in last:
            return adb("shell", "cat", "/sdcard/ui.xml")
        time.sleep(0.8)
    raise RuntimeError(f"uiautomator dump 失败：{last.strip()}")


def parse_bounds(raw: str):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw or "")
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return x1, y1, x2, y2, (x1 + x2) // 2, (y1 + y2) // 2


def nodes(xml_text: str):
    root = ET.fromstring(xml_text)
    out = []
    for n in root.iter():
        if not n.tag.endswith("node"):
            continue
        text = n.get("text") or ""
        desc = n.get("content-desc") or ""
        rid = n.get("resource-id") or ""
        bounds = parse_bounds(n.get("bounds"))
        if bounds is None:
            continue
        if not (text or desc or rid):
            continue
        out.append(
            {
                "text": text,
                "desc": desc,
                "id": rid,
                "cls": (n.get("class") or "").split(".")[-1],
                "clickable": n.get("clickable") == "true",
                "checked": n.get("checked") == "true",
                "center": (bounds[4], bounds[5]),
            }
        )
    return out


def cmd_dump(_args):
    for n in nodes(dump_xml()):
        label = n["text"] or f"<desc:{n['desc']}>" if n["desc"] else n["text"]
        if not label and n["id"]:
            label = f"<id:{n['id'].split('/')[-1]}>"
        flags = "可点" if n["clickable"] else "  "
        if n["checked"]:
            flags += " 已勾选"
        print(f"{flags} {n['center'][0]:>4},{n['center'][1]:>4}  {n['cls']:<12} {label}")


def _tap(center):
    adb("shell", "input", "tap", str(center[0]), str(center[1]))


def cmd_tap_text(args):
    needle = args[0]
    for n in nodes(dump_xml()):
        if needle in n["text"] or needle in n["desc"]:
            _tap(n["center"])
            print(f"tapped {n['center']}  «{n['text'] or n['desc']}»")
            return
    sys.exit(f"未找到文本：{needle}")


def cmd_tap_id(args):
    needle = args[0]
    for n in nodes(dump_xml()):
        if needle in n["id"]:
            _tap(n["center"])
            print(f"tapped {n['center']}  {n['id']}")
            return
    sys.exit(f"未找到 resource-id：{needle}")


def cmd_tap(args):
    _tap((int(args[0]), int(args[1])))
    print(f"tapped {args[0]},{args[1]}")


def cmd_text(args):
    raw = args[0]
    if not raw.isascii():
        sys.exit("`input text` 不支持非 ASCII，请改用 input-text-cn")
    adb("shell", "input", "text", raw.replace(" ", "%s"))
    print(f"input: {raw}")


def cmd_input_text_cn(args):
    raw = args[0]
    if raw.isascii():
        cmd_text([raw])
        return
    # 中文输入：直接把文本塞进剪贴板，再发粘贴键。
    # `adb shell am broadcast` 无法写系统剪贴板，Android 10+ 也禁止后台应用写剪贴板，
    # 因此这条路在原生模拟器上走不通 —— 明确报错，让调用方换英文输入。
    sys.exit(
        "模拟器未安装 ADBKeyboard，无法输入非 ASCII 文本。"
        "本脚本不静默丢字符：请把该字段改成 ASCII 内容再测。"
    )


def cmd_key(args):
    adb("shell", "input", "keyevent", args[0])
    print(f"key: {args[0]}")


def cmd_shot(args):
    path = args[0]
    adb("shell", "screencap", "-p", "/sdcard/shot.png")
    with open(path, "wb") as f:
        f.write(subprocess.run(
            [ADB, "exec-out", "cat", "/sdcard/shot.png"], capture_output=True
        ).stdout)
    print(f"saved {path}")


def cmd_launch(_args):
    print(adb("shell", "monkey", "-p", PKG, "-c",
              "android.intent.category.LAUNCHER", "1").strip()[:200])


def cmd_stop(_args):
    adb("shell", "am", "force-stop", PKG)
    print("stopped")


def cmd_logcat_clear(_args):
    adb("logcat", "-c")
    print("logcat cleared")


def cmd_logcat_crash(_args):
    out = adb("logcat", "-d", "-b", "crash", timeout=90)
    print(out if out.strip() else "(崩溃缓冲区为空)")
    out2 = adb("logcat", "-d", timeout=90)
    bad = [l for l in out2.splitlines()
           if "FATAL" in l or "AndroidRuntime" in l or (" E " in l and PKG in l)]
    print("\n--- 可疑行 ---")
    print("\n".join(bad) if bad else "(无 FATAL / AndroidRuntime 记录)")


COMMANDS = {
    "dump": cmd_dump,
    "tap-text": cmd_tap_text,
    "tap-id": cmd_tap_id,
    "tap": cmd_tap,
    "text": cmd_text,
    "input-text-cn": cmd_input_text_cn,
    "key": cmd_key,
    "shot": cmd_shot,
    "launch": cmd_launch,
    "stop": cmd_stop,
    "logcat-clear": cmd_logcat_clear,
    "logcat-crash": cmd_logcat_crash,
}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        sys.exit(f"用法：{sys.argv[0]} <{'|'.join(COMMANDS)}> [参数]")
    COMMANDS[sys.argv[1]](sys.argv[2:])
