#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M3 验收：在模拟器上走通 FPB 的完整界面流程并留证。

为什么写成脚本而不是手敲 adb：
  1. Compose 的界面层级里输入框、复选框都没有 resource-id，只能靠
     "class + 第几个 + 坐标"来定位。手敲必然算错坐标。
  2. 恢复码抄写校验要求把随机抽出的 3 组打回去 —— 需要先把界面上显示的
     12 组读出来，再倒推该填哪 3 组，这一步只能程序化。

踩过的两个坑，都在下面用代码兜住了：
  **坑一：软键盘弹起后窗口重排。** 弹键盘会让可绘制区域变矮，下面的控件整体上移。
  如果在弹键盘之前把坐标缓存在列表里，第二个框就会点到别处（第一版把三组答案
  全打进同一个框了）。因此每次点击前都重新 dump，而不是复用旧坐标。
  **坑二：`adb shell cat` 读 XML 会截断。** 层级 XML 是单行大文本，走 shell 会被
  按行缓冲截断，解析时报 "no element found"。改用 `adb exec-out cat` 拿二进制流，
  并且解析失败就重试。

主密码只用 ASCII：`adb shell input text` 不支持非 ASCII。
"""

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", r"D:/AndroidSdk/platform-tools/adb.exe")
PKG = "com.fpb.vault"
OUT = r"D:/MixiaVault/dist/evidence/m3"

MASTER = "VaultMaster2026x"
SHOT_INDEX = 0
KEYCODE_BACK = "4"       # 关闭输入法；键盘没开时会被当成系统返回，用前必须探测
KEYCODE_DEL = "67"
KEYCODE_TAB = "61"      # 焦点遍历到下一个输入框


def adb(*args, timeout=60):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout)\
        .stdout.decode("utf-8", errors="replace")


def _parse(xml_text):
    root = ET.fromstring(xml_text)
    out = []
    for n in root.iter():
        if not n.tag.endswith("node"):
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if not m:
            continue
        x1, y1, x2, y2 = (int(v) for v in m.groups())
        if x2 - x1 <= 1 or y2 - y1 <= 1:
            continue
        out.append({
            "text": n.get("text") or "",
            "desc": n.get("content-desc") or "",
            "cls": (n.get("class") or "").split(".")[-1],
            "checkable": n.get("checkable") == "true",
            "checked": n.get("checked") == "true",
            "focused": n.get("focused") == "true",
            "clickable": n.get("clickable") == "true",
            "enabled": n.get("enabled") == "true",
            "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
            "y1": y1,
        })
    out.sort(key=lambda n: (n["y1"], n["cx"]))
    return out


def nodes(retries=10):
    """dump 当前窗口的节点树。

    **踩坑记录一**：`uiautomator dump /sdcard/ui.xml` 会打印
    "UI hierchary dumped to: ..." 却**不写文件**（模拟器跑久了之后稳定复现），
    重试也没用。可靠通道是 `exec-out uiautomator dump /dev/tty` ——
    dump 内容直接走 stdout，绕过写盘这一步。
    输出前后可能混入日志行，因此只截取首个 '<' 到最后一个 '>' 之间的 XML。

    **踩坑记录二（冷启动期间 dump 必然失败，且时长不可控）**：应用冷启动的那段时间里
    dump 会**稳定**失败，报 `ERROR: null root node returned by UiTestAutomationBridge.`（走 stderr）。
    原因是前台此时是系统画的启动窗口（starting window），UiAutomation 拿不到可访问的根节点。
    这不是"设备坏了"，重启也没用，只能等。实测（宿主空闲）窗口约 **25 秒**，
    加每次失败尝试本身还要 3~4 秒，所以要 8 次左右才穿过去。

    但**"等够久"不是可靠解法**：这个窗口的长度取决于宿主负载 ——
    同一份脚本，只要宿主上同时跑着 Gradle/Kotlin 编译，窗口就会从 25 秒拉到 40 秒以上，
    于是"重试 N 次"永远只是把失败概率推小、推不到零（实测 10 次也全灭过）。

    因此这里保留重试（应付偶发抖动），但**调用方不要指望它一定成功**：
    需要"反复看屏幕直到某个标记出现"的地方（如 ensure_home），应当自己捕获 RuntimeError
    并继续轮询 —— "现在 dump 不出来"和"不在那个界面上"是两回事，不能混为一谈。
    """
    last = None
    for _ in range(retries):
        # 失败信息（ERROR: null root node ...）走的是 **stderr**，成功时的 XML 走 stdout，
        # 所以两边都要收，否则出错时只能得到一句无从下手的"no xml in output"。
        done = subprocess.run([ADB, "exec-out", "uiautomator", "dump", "/dev/tty"],
                              capture_output=True, timeout=60)
        text = (done.stdout + done.stderr).decode("utf-8", errors="replace")
        start, end = text.find("<?xml"), text.rfind(">")
        if start >= 0 and end > start:
            try:
                return _parse(text[start:end + 1])
            except ET.ParseError as e:
                last = f"XML 解析失败：{e}"
        else:
            # 把 uiautomator 自己那句话带出来，否则只有一句"no xml in output"，
            # 事后根本分不清是"启动窗口期"还是"设备真的掉线了"。
            last = (text.strip() or "stdout/stderr 均为空").splitlines()[0][:120]
        time.sleep(0.9)
    raise RuntimeError(f"dump 解析失败：{last}")


def tap(x, y, settle=0.5):
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(settle)


def ime_shown():
    out = adb("shell", "dumpsys", "input_method")
    m = re.search(r"mInputShown=(\w+)", out)
    return bool(m) and m.group(1) == "true"


def hide_ime(tries=4):
    """真正把输入法收起来。

    **踩坑记录**：先用的是 `keyevent 111`（ESCAPE），它在这里**完全无效** ——
    `dumpsys input_method` 始终显示 `mInputShown=true`。后果不是"看起来没生效"，
    而是后续所有落在屏幕下半部分的点击都打在键盘上：勾选框永远勾不上，
    在联想条上滑动还会把 "to the by by by" 这类候选词灌进正在编辑的输入框。
    因此这里改用 `keyevent 4`（BACK）—— 它才会真正关掉输入法。

    发 BACK 之前必须先确认"键盘确实是开着的"：键盘没开时 BACK 会被当成
    系统返回，在解锁页上就等于退出应用。
    """
    for _ in range(tries):
        if not ime_shown():
            time.sleep(0.3)
            return
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.6)
    raise RuntimeError("无法收起输入法（mInputShown 仍为 true）")


def find(sub, ns=None):
    ns = ns if ns is not None else nodes()
    for n in ns:
        if sub in n["text"] or sub in n["desc"]:
            return n
    return None


def wait_text(sub, timeout=25, what=None):
    """等某段文案出现。

    **"读不到屏幕"不等于"没出现"。** 模拟器冷启动、或宿主负载高的时候，
    `uiautomator dump` 会连续失败几十秒到上百秒（前台是系统画的启动窗口，
    UiAutomation 拿不到可访问的根节点 —— 详见 [nodes] 的说明）。

    早期版本在这里直接让异常穿透出去，于是报的是"等待超时：未出现解锁页"。
    这句话会把排查方向带偏十万八千里：现象像是"应用没起来/没跳转"，
    实际是这整段时间里**根本没人看过一眼屏幕**。
    实测宿主在跑 Gradle 编译时，同一个"失败窗口"能从 25 秒涨到 100 秒以上。
    所以这里必须把两种情况分开：读不到就继续等，并在超时信息里说明"有多少轮是盲的"。
    """
    end = time.time() + timeout
    blind = 0
    last_err = None
    while time.time() < end:
        try:
            n = find(sub)
        except RuntimeError as e:
            blind += 1
            last_err = e
            # 读不到屏幕时不要退化成 0.7 秒一轮 —— 那只会白刷失败次数
            time.sleep(1.0)
            continue
        if n:
            return n
        time.sleep(0.7)
    extra = f"（其中 {blind} 轮连屏幕都读不到：{last_err}）" if blind else ""
    raise RuntimeError(f"等待超时：未出现 {what or sub}{extra}")


def tap_text(sub, ns=None, tries=3):
    """点某段文案。读不到屏幕时重试几次，别把 dump 抖动报成"未找到文本"。"""
    n = None
    for _ in range(tries):
        try:
            n = find(sub, ns)
        except RuntimeError:
            time.sleep(1.0)
            continue
        if n:
            break
    if not n:
        raise RuntimeError(f"未找到可点文本：{sub}")
    tap(n["cx"], n["cy"])
    return n


def scroll_top():
    """把可滚动页面拉回顶部。坐标定位在滚动位置漂移后就不可信了，先归零。"""
    adb("shell", "input", "swipe", "540", "700", "540", "2100", "250")
    time.sleep(0.8)


def tap_text_scrolling(sub, tries=6):
    """文本可能在可视区外：一路下滑寻找，找到就点。

    两个约束都是踩出来的：
      · **先收起键盘**，否则屏幕下半部分是输入法，页面根本不会滚动；
      · **在内容区滑动（y 1500→700）**。第一版在 y=1800 处滑，正好划过
        输入法的联想条，把 "to the by by by" 这类候选词灌进了当时
        正在编辑的恢复码输入框 —— 界面上看不出异常，但校验必然失败。
    """
    hide_ime()
    for _ in range(tries):
        n = find(sub)
        if n:
            tap(n["cx"], n["cy"])
            return n
        adb("shell", "input", "swipe", "540", "1500", "540", "700", "250")
        time.sleep(0.8)
    raise RuntimeError(f"下滑 {tries} 屏后仍未找到：{sub}")


def ensure_home(password=None, timeout=180):
    """确保停在**已解锁的主页**，必要时自动用 [password] 解锁。

    为什么要有这个函数：几乎每个验收脚本的第一件事都是"从任意状态回到主页"，
    而这件事有四个坑，每个都各自骗过一次人：

    1. **冷启动期间 dump 必然失败**（`null root node returned by UiTestAutomationBridge`），
       窗口长度随宿主负载从 25 秒飘到 100 秒以上。所以只能轮询，
       并且必须把"dump 暂时不可用"与"界面不对"区分开 —— 前者不是失败。
    2. **`install -r` 会杀掉进程**，装完包的第一步一定是冷启动。
       曾经在这里固定 sleep 5 秒就去判断，判断落空后又连按 BACK，
       结果把应用退到了桌面（报错"退不回主页"）。
    3. **应用可能停在解锁页**（冷启动、自动锁定、上次强停），要输密码才能继续。
       判据用"忘记密码"，**不能用"主密码"** —— 设置页的"修改主密码"也含这三个字。
    4. **应用可能停在内页**（设置页、照片库、详情页、编辑器、图片查看器）。
       此时既没有主页标题也没有"忘记密码"，本函数会一直空等到超时，
       报出来的却是"既没回到主页，也没能完成解锁" —— 现象像是应用坏了，
       实际只是停在上一轮脚本留下的页面上。
       判据是顶栏的「返回」（`content-desc="返回"`，图片查看器是「关闭」），
       点它一层层退回主页。
       这里**刻意不用 BACK 键兜底**：在主页上 BACK 会被当成"退出应用"，
       那正是坑 2 里栽过的同一个跟头 —— 宁可超时报错，也不要把应用退到桌面。
    """
    if password is None:
        password = MASTER
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    end = time.time() + timeout
    tried_unlock = False
    back_outs = 0
    while time.time() < end:
        try:
            if find("本机加密 ·") is not None:
                time.sleep(1.0)
                return
            on_unlock = find("忘记密码") is not None
            back_node = find("返回") or find("关闭")
        except Exception:
            time.sleep(1.5)
            continue
        if on_unlock and not tried_unlock:
            tried_unlock = True
            log("    应用停在解锁页，用主密码解锁")
            try:
                es = [n for n in nodes() if n["cls"] == "EditText"]
                if es:
                    tap(es[0]["cx"], es[0]["cy"])
                    type_ascii(password)
                    hide_ime()
                    tap_text("解锁")
            except Exception as e:
                log(f"    解锁动作没走通（{e}），继续轮询")
                tried_unlock = False
        elif back_node is not None and back_outs < 6:
            back_outs += 1
            log(f"    应用停在内页，点顶栏返回退回主页（第 {back_outs} 次）")
            try:
                tap(back_node["cx"], back_node["cy"])
            except Exception:
                pass
        time.sleep(1.5)
    raise RuntimeError("超时：既没回到主页，也没能完成解锁")


def type_ascii(s):
    adb("shell", "input", "text", s.replace(" ", "%s"))
    time.sleep(0.4)


def clear_focused_field():
    """把当前焦点输入框清空（长按全选不可靠，直接退格足够长度）。"""
    for _ in range(32):
        adb("shell", "input", "keyevent", KEYCODE_DEL)
    time.sleep(0.3)


def edits():
    return [n for n in nodes() if n["cls"] == "EditText"]


def _value_present(value):
    """在**收起键盘后**的完整层级里，找一个内容等于 value 的输入框。

    密码框在层级里只有掩码字符，因此退化成比长度。
    """
    for n in edits():
        raw = n["text"]
        if raw == value:
            return True
        if raw and set(raw) <= {"\u2022", "*"} and len(raw) == len(value):
            return True
    return False


def fill_field(index, value):
    """重新 dump → 点第 index 个输入框 → 输入 → 校验落字。

    两次"收起键盘"是关键：
      · **点击前**收起：键盘弹起会让可滚动页面整体位移，复用旧坐标必然点错。
      · **校验前**收起：uiautomator 只报告当前可见的节点，键盘占掉半屏时
        下面的输入框根本不在层级里，按下标取 `edits()[index]` 会取到别的框。
    校验也刻意不按下标，而是"整个页面里存在这个值" —— 滚动位置不影响结论。
    """
    hide_ime()
    es = edits()
    if index >= len(es):
        raise RuntimeError(f"只有 {len(es)} 个输入框，取不到第 {index} 个")
    tap(es[index]["cx"], es[index]["cy"])
    clear_focused_field()
    type_ascii(value)

    hide_ime()
    if not _value_present(value):
        raise RuntimeError(f"第 {index} 个输入框落字校验失败：页面里找不到 {value!r}")
    return value


def shot(name):
    global SHOT_INDEX
    SHOT_INDEX += 1
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{SHOT_INDEX:02d}-{name}.png")
    data = subprocess.run([ADB, "exec-out", "screencap", "-p"],
                          capture_output=True).stdout
    with open(path, "wb") as f:
        f.write(data)
    print(f"    [截图] {os.path.basename(path)}  {len(data)} B")
    return path


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def main():
    log("清空应用数据并冷启动（确保从引导流程开始）")
    adb("shell", "pm", "clear", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    wait_text("开始设置", timeout=45, what="引导页第一屏")
    shot("引导-欢迎")
    tap_text("开始设置")

    # ---------- 第 2 屏：主密码 ----------
    log("设置主密码")
    wait_text("设置主密码")
    fill_field(0, MASTER)
    fill_field(1, MASTER)
    hide_ime()
    shot("引导-主密码")
    tap_text("下一步")

    # ---------- 第 3 屏：假密码 ----------
    log("本次跳过假密码（假密码流程单独验收）")
    wait_text("要不要再设一个假密码")
    shot("引导-假密码")
    tap_text("跳过，生成保险库")

    # ---------- 第 4 屏：生成中 ----------
    log("等待 Argon2id 派生完成")
    wait_text("正在派生密钥", timeout=10)
    shot("引导-生成中")

    # ---------- 第 5 屏：恢复码 ----------
    log("恢复码抄写校验")
    wait_text("抄下恢复码", timeout=120)

    scroll_top()
    ns = nodes()
    groups = [n["text"] for n in ns if re.fullmatch(r"[0-9A-Z]{4}", n["text"])]
    assert len(groups) == 12, f"应显示 12 组恢复码，实际 {len(groups)}: {groups}"
    log(f"    界面显示 12 组：{' '.join(groups)}")

    labels = [int(m.group(1)) for n in ns
              for m in [re.fullmatch(r"第 (\d+) 组", n["text"])] if m]
    assert len(labels) == 3, f"应随机抽 3 组回填，实际 {labels}"
    wanted = [groups[i - 1] for i in labels]
    log(f"    要求回填：第 {labels} 组 → {' / '.join(wanted)}")

    # 用 TAB 焦点遍历来填，不用坐标：
    # 键盘收起时 uiautomator 只报告**可见**节点，而可滚动页面上的第三个框
    # 此时在可视区外、根本不在层级里，按下标取坐标必然填错框。
    # 焦点遍历的顺序就是布局顺序（三个输入框 → 复选框 → 按钮），
    # 因此"点第一个框 + 连按两次 TAB"能稳定地依次落在三个框上。
    hide_ime()
    scroll_top()
    es = edits()
    assert es, "找不到回填输入框"
    tap(es[0]["cx"], es[0]["cy"])
    type_ascii(wanted[0])
    for value in wanted[1:]:
        adb("shell", "input", "keyevent", KEYCODE_TAB)
        time.sleep(0.6)
        type_ascii(value)
    hide_ime()
    shot("引导-恢复码已回填")

    # ---------- 勾选 + 进入 ----------
    # "我已抄好"必须回读确认：点偏一点就会静默变成没勾上，
    # 而"没勾上"的后果是下面的按钮保持禁用，点击它没任何反应 ——
    # 表现为"脚本卡住"，排查起来很费劲。这里直接重试到它真的为 true。
    hide_ime()
    for attempt in range(5):
        cb = next((n for n in nodes() if n["checkable"]), None)
        if cb is None:
            adb("shell", "input", "swipe", "540", "1500", "540", "800", "250")
            time.sleep(0.9)
            continue
        if cb["checked"]:
            log(f"    复选框已勾选（第 {attempt + 1} 次尝试）")
            break
        tap(cb["cx"], cb["cy"])
        time.sleep(0.7)
    else:
        raise RuntimeError("复选框始终勾不上")
    shot("引导-已确认抄写")

    enter = find("进入保险库")
    assert enter is not None, "找不到「进入保险库」按钮"
    assert enter["enabled"], "「进入保险库」按钮是禁用的，说明恢复码或勾选没通过"
    tap(enter["cx"], enter["cy"])

    # ---------- 主页 ----------
    # 关键：能到这里本身就是"三组恢复码全部填对 + 已勾选"的证明 ——
    # finishOnboarding 在 recoveryConfirmed=false 时会直接把人留在原地。
    # 注意断言用"还没有任何记录"而不是 FAB 文案"新建"：Compose 的语义合并
    # 让 ExtendedFloatingActionButton 在 uiautomator 层级里只剩一个
    # 无文本的 View，文本根本读不到（坑三）。
    log("等待进入保险库主页")
    wait_text("还没有任何记录", timeout=90, what="保险库主页")
    time.sleep(1.5)
    shot("主页-空列表")

    # ---------- 新建一条文字记录 ----------
    log("新建一条文字记录")
    fab = next((n for n in nodes() if n["clickable"] and not (n["text"] or n["desc"])
                and n["cy"] > 1900), None)
    assert fab is not None, "找不到右下角的悬浮按钮（无文本、位于屏幕底部）"
    tap(fab["cx"], fab["cy"])
    wait_text("文字", timeout=20, what="新建类型选择")
    shot("新建-类型选择")
    tap_text("文字")

    log("填写标题与正文")
    wait_text("标题", timeout=20, what="文字编辑器")
    es = [n for n in nodes() if n["cls"] == "EditText"]
    assert len(es) >= 2, f"编辑器应有标题+正文两个输入框，实际 {len(es)}"
    tap(es[0]["cx"], es[0]["cy"])           # 标题
    type_ascii("Acceptance-01")
    hide_ime()
    es = [n for n in nodes() if n["cls"] == "EditText"]
    tap(es[1]["cx"], es[1]["cy"])           # 正文
    type_ascii("top-secret-body-2026")
    hide_ime()
    shot("编辑器-已填写")
    tap_text("保存")

    wait_text("Acceptance-01", timeout=30, what="主页出现新记录")
    time.sleep(1.0)
    shot("主页-已有记录")
    log("记录已落库并在主页可见")

    # ---------- 新建一条图片记录（回归：requestCode 16 位崩溃）----------
    # 历史 bug：activity 1.10 的随机 requestCode（>=0x10000）撞上
    # fragment 1.2.5 的 16 位校验，点"+"调起选图器直接闪退。
    # 修复 = 显式依赖 fragment 1.8.9。此步骤防止回归。
    log("新建一条图片记录")
    fab = next((n for n in nodes() if n["clickable"] and not (n["text"] or n["desc"])
                and n["cy"] > 1900), None)
    tap(fab["cx"], fab["cy"])
    time.sleep(1.2)
    tap_text("图片")
    time.sleep(1.5)
    btn = next((n for n in nodes() if n["cls"] == "Button" and 700 < n["cy"] < 950), None)
    assert btn is not None, "找不到添加图片按钮"
    tap(btn["cx"], btn["cy"])
    # 系统选图器首次会有两层提示（云相册/权限说明），都点掉
    for _ in range(3):
        time.sleep(2.0)
        d = find("Dismiss")
        if d:
            tap(d["cx"], d["cy"])
            time.sleep(0.8)
    cells = [n for n in nodes() if n["clickable"]
             and 950 < n["cy"] < 1700 and not (n["text"] or n["desc"])]
    assert cells, "选图器里没有照片单元格（或布局变化）"
    tap(cells[0]["cx"], cells[0]["cy"])
    time.sleep(2.0)
    done = find("Done")
    assert done is not None, "未出现 Done 按钮，照片可能没选中"
    tap(done["cx"], done["cy"])
    wait_text("图片（1 / 100）", timeout=30, what="编辑器出现缩略图")
    log("    图片已进入编辑器")
    es = [n for n in nodes() if n["cls"] == "EditText"]
    tap(es[0]["cx"], es[0]["cy"])
    type_ascii("Photo-Regression")
    hide_ime()
    tap_text("保存")
    wait_text("Photo-Regression", timeout=30, what="主页出现图片记录")
    log("    图片记录保存成功")

    # ---------- 打开设置 ----------
    log("打开设置页")
    tap_text("设置")
    wait_text("外观与伪装", timeout=20, what="设置页")
    # 设置页可滚动，dump 只含可视节点：逐个区块"滚动查找"而不是断言首屏齐全
    found = {}
    for section in ("安全", "外观与伪装", "存储", "备份", "危险操作", "关于"):
        hit = find(section)
        for _ in range(6):
            if hit is not None:
                break
            adb("shell", "input", "swipe", "540", "1700", "540", "700", "300")
            time.sleep(0.7)
            hit = find(section)
        assert hit is not None, f"滚动 6 屏仍未找到区块：{section}"
        found[section] = hit
    log("设置页区块齐全：" + "/".join(found.keys()))
    shot("设置页-总览")

    # 滚到中部再截一张（证明可滚动内容真实存在）
    hide_ime()
    adb("shell", "input", "swipe", "540", "1800", "540", "700", "300")
    time.sleep(0.8)
    shot("设置页-中部")

    # ---------- 锁定与重新解锁 ----------
    log("强停应用后冷启动（验证锁定态与主密码解锁）")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(2.5)
    # 冷启动到解锁页实测约 20s（boot() 里的 Argon2id 预热），
    # 而且 force-stop 刚结束时的第一发 am start 偶尔会被系统吞掉 ——
    # 20 秒还没动静就再发一次。
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    try:
        wait_text("主密码", timeout=25, what="解锁页")
    except RuntimeError:
        log("    第一发 am start 似乎被吞了，重发一次")
        adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        wait_text("主密码", timeout=75, what="解锁页（重试后）")
    shot("锁定-解锁页")

    es = [n for n in nodes() if n["cls"] == "EditText"]
    assert es, "解锁页没有密码输入框"
    tap(es[0]["cx"], es[0]["cy"])
    type_ascii(MASTER)
    hide_ime()
    shot("锁定-已输入密码")
    tap_text("解锁")

    wait_text("Acceptance-01", timeout=90, what="解锁后的主页")
    time.sleep(1.0)
    shot("解锁-成功回到主页")
    log("用主密码重新解锁成功，记录仍在")

    print()
    log("全流程验收完成")


if __name__ == "__main__":
    main()
