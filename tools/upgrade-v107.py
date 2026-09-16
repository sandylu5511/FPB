#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.6（versionCode 7）→ v1.0.7（versionCode 8）覆盖升级验证。

## 这份脚本要证明的三件事

1. **`adb install -r` 覆盖安装保留应用私有数据** —— 用文件级 md5 逐字节比对，
   而不是看界面"好像还在"。这是回答"覆盖升级会不会丢数据"的硬证据。
2. **v1.0.6 写下的偏好键，v1.0.7 还能读到** —— 专打本轮重构改过的那批名字
   （`lastBackupReminderAt` 改名成 `lastExportedAt`，但偏好键必须仍是
   `backup_reminder_at`）。模拟"老用户刚导出过备份"的状态，升级后
   **不能**看到一个假的"还没有导出过备份"催促条。
3. **新加的周期性提醒真的会响** —— 把时间戳改到 8 天前，冷启动后必须出现
   "上次导出备份已经超过 7 天"。只写不读曾经是真缺陷，这条是它的回归测试。

## 为什么"造数据"这一步必须写在这里而不是复用别的脚本

因为要测的是**跨版本**：数据必须在 v1.0.6 上产生、在 v1.0.7 上校验。
所以分两个阶段跑，中间由人（或外层命令）执行 `adb install -r`：

    python tools/upgrade-v107.py seed     # 在已装的 v1.0.6 上造数据、存基线
    adb install -r dist/FPB-v1.0.7-debug.apk
    python tools/upgrade-v107.py verify   # 核对数据与偏好都还在

    python tools/upgrade-v107.py release  # release 包覆盖升级冒烟（v1.0.6 → v1.0.7）

## `verify` 必须紧跟在 `install -r` 之后跑，中间不要打开应用

基线比对的前提是"两次取样之间**只有安装**这件事发生"。`install -r` 会杀掉进程，
所以装完的那一刻磁盘状态才和基线可比。一旦应用被启动过（尤其解锁进入保险库），
它自己就可能正常地写点东西（`profileInstalled`、偏好、SQLite 的日志页）——
此时再比对会报出"数据被改了"，而那**不是升级造成的**，是应用正常运行的副作用。
这条误导性结论踩过一次，别踩第二次。

## 已知的坑（都踩过，别再踩）

- `run-as ... sh -c '...'` 里的引号会被 adb 远端 shell 吃掉，模式串悄悄变样还不报错。
  **一律用无引号的单条命令**：`run-as <pkg> md5sum files/fpb.key`。
- 改偏好文件必须 **先 `am force-stop`**：应用把 SharedPreferences 缓存在内存里，
  运行中改文件会在它下次 `apply()` 时被覆盖回去，看起来像"改动没生效"。
- 改偏好文件的正确姿势是"本地改好 → `adb push` → `run-as cp`"：
  `run-as sed -i` 会因为引号问题静默失败。
- 记录标题只能用 **ASCII**：脚本用 `input text` 打字，中文进不去。
"""

import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import time

BASE = r"D:/MixiaVault"
ADB = r"D:/AndroidSdk/platform-tools/adb.exe"
PKG = "com.fpb.vault"
OUT = rf"{BASE}/dist/evidence/upgrade-v107"
BASELINE = rf"{OUT}/baseline.json"

APK_RELEASE_OLD = rf"{BASE}/dist/FPB-v1.0.6.apk"
APK_RELEASE_NEW = rf"{BASE}/dist/FPB-v1.0.7.apk"
APK_DEBUG_NEW = rf"{BASE}/app/build/outputs/apk/debug/app-debug.apk"

# 记录标题：纯 ASCII（input text 打不进中文），且够独特，能在列表里一眼找到
NOTE_TITLE = "UPGRADE_MARK_070"

PREF_FILE = "shared_prefs/fpb_settings.xml"
KEY_BACKUP_REMINDER = "backup_reminder_at"

FAILURES = []


# ==================== 基础设施 ====================

def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def sh(*args, timeout=120):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout) \
        .stdout.decode("utf-8", errors="replace")


def out(*args, timeout=120):
    """exec-out：二进制安全，拉文件必须用它（shell 会做换行转换）。"""
    return subprocess.run([ADB, "exec-out", *args], capture_output=True, timeout=timeout) \
        .stdout


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def check(label, ok, detail=""):
    """打印一条断言。

    `detail` 是**失败原因**，只在失败时打印。曾经无条件打印过，于是日志里出现
    `[PASS] 记录的标题还在：列表里找不到标题` 这种自相矛盾的行 ——
    看日志的人会先信后半句。断言输出本身就是证据的一部分，不能有歧义。
    """
    if ok:
        print(f"  [PASS] {label}", flush=True)
    else:
        FAILURES.append(f"{label} —— {detail}")
        print(f"  [FAIL] {label}" + (f"：{detail}" if detail else ""), flush=True)
    return ok


def pkg_info():
    """解析 `dumpsys package` 里的版本与安装时间。"""
    text = sh("shell", "dumpsys", "package", PKG)
    info = {}
    for key, pat in [
        ("versionCode", r"versionCode=(\d+)"),
        ("versionName", r"versionName=([\w.]+)"),
        ("firstInstallTime", r"firstInstallTime=([\d\-: ]+)"),
        ("lastUpdateTime", r"lastUpdateTime=([\d\-: ]+)"),
    ]:
        m = re.search(pat, text)
        info[key] = m.group(1).strip() if m else None
    return info


def md5_state():
    """应用私有目录下所有文件的 md5。

    刻意覆盖 `files`（含密钥与附件）、`databases`、`shared_prefs` 三处 ——
    覆盖安装要保住的正是这三类东西：能不能解密、记录在不在、设置有没有被重置。
    """
    listing = sh("shell", "run-as", PKG, "find", "files", "databases", "shared_prefs", "-type", "f")
    state = {}
    for line in listing.splitlines():
        path = line.strip()
        if not path or path.startswith("find:"):
            continue
        digest = sh("shell", "run-as", PKG, "md5sum", path).strip()
        if not digest or "No such file" in digest or "Permission denied" in digest:
            state[path] = "<读不到>"
            continue
        state[path] = digest.split()[0]
    return state


def read_pref_value(key):
    """从 shared_prefs 的 XML 里读一个键的值。

    SharedPreferences 写出来的是**自闭合**标签（`<long name="x" value="1" />`），
    所以必须直接抓 value 属性 —— 用"标签之间的文本"那套正则会匹配到
    标签后面的换行，读出来是一个空串，看起来就像"写入没生效"。
    """
    raw = out("run-as", PKG, "cat", PREF_FILE)
    if not raw or b"No such file" in raw[:200]:
        return None
    m = re.search(rf'name="{re.escape(key)}"[^>]*?value="([^"]*)"',
                  raw.decode("utf-8", "replace"))
    return m.group(1) if m else None


def write_pref_value(key, value):
    """改偏好文件里的一个键（必须先 force-stop，否则会被内存里的副本覆盖回去）。"""
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1.0)
    raw = out("run-as", PKG, "cat", PREF_FILE).decode("utf-8", "replace")
    if f'name="{key}"' not in raw:
        # 键不存在就插一个进去（SharedPreferences 的 XML 形如 <long name="k" value="v"/>）
        body = re.search(r"<map>(.*)</map>", raw, re.S)
        if not body:
            raise RuntimeError(f"偏好文件结构不认识：{raw[:200]}")
        raw = raw.replace("</map>", f'<long name="{key}" value="{value}" />\n</map>', 1)
    else:
        raw = re.sub(
            rf'(<long name="{re.escape(key)}" value=")[^"]*(")',
            rf"\g<1>{value}\g<2>",
            raw,
        )
    local = rf"{OUT}/_prefs_tmp.xml"
    io.open(local, "w", encoding="utf-8", newline="\n").write(raw)
    sh("push", local, "/data/local/tmp/fpb_settings.xml")
    sh("shell", "chmod", "644", "/data/local/tmp/fpb_settings.xml")
    sh("shell", "run-as", PKG, "cp", "/data/local/tmp/fpb_settings.xml", PREF_FILE)
    got = read_pref_value(key)
    if got != str(value):
        raise RuntimeError(f"偏好写入没生效：{key} 期望 {value}，实际 {got}")


def home_note_count(W):
    """主页页头「本机加密 · N 条」里的 N。"""
    hit = W.find("本机加密 · ")
    if not hit:
        return None
    m = re.search(r"(\d+)\s*条", hit["text"])
    return int(m.group(1)) if m else None


# ---- 照片导入（从 walkthrough-v106-part2.py 搬过来）----
# 本来想直接 import 那个模块，但它顶层 `from PIL import Image`，
# 而托管 Python 里没有 Pillow。这几个函数只用标准库，搬过来最省事，
# 也免了"被加载模块的顶层代码改掉输出目录"那个坑。

def library_count(W):
    """照片库页头「原图加密存储 · N 张」里的 N。"""
    hit = W.find("原图加密存储 · ")
    if not hit:
        return None
    m = re.search(r"(\d+)\s*张", hit["text"])
    return int(m.group(1)) if m else None


def open_photo_library(W):
    hit = W.find("照片库")
    assert hit is not None, "主页顶栏找不到照片库入口"
    W.tap(hit["cx"], hit["cy"])
    W.wait_text("照片库", timeout=25, what="照片库页")


def import_photos(W, max_pick):
    """多选导入，返回导入**之后**库里的总张数。

    选图器的坑：计数器与 Done 只在选中第一张之后出现；已选中的格子外层没有任何标记，
    重复点同一格会把它取消掉 —— 自己记坐标避让。
    页头张数**不是立刻刷新**的，所以必须等它"真的变了"，否则读到的是导入前的旧值。
    """
    before = library_count(W)
    log(f"打开选图器，最多选 {max_pick} 张（导入前库里有 {before} 张）")
    fab = next((n for n in W.nodes()
                if n["clickable"] and not (n["text"] or n["desc"]) and n["cy"] > 1900), None)
    assert fab is not None, "照片库里找不到「添加照片」按钮"
    W.tap(fab["cx"], fab["cy"])

    deadline = time.time() + 45
    cells = []
    while time.time() < deadline:
        cells = [n for n in W.nodes() if n["clickable"]
                 and not (n["text"] or n["desc"]) and 900 < n["cy"] < 1800]
        if cells:
            break
        d = W.find("Dismiss")
        if d:
            W.tap(d["cx"], d["cy"])
        time.sleep(1.5)
    if not cells:
        raise RuntimeError("选图器没有出现照片网格")

    picked = []
    for want in range(1, min(max_pick, len(cells)) + 1):
        free = [n for n in W.nodes() if n["clickable"]
                and not (n["text"] or n["desc"]) and 900 < n["cy"] < 1800
                and all(abs(n["cx"] - x) > 40 or abs(n["cy"] - y) > 40 for x, y in picked)]
        assert free, "选图器里没有可选的空格子"
        cell = min(free, key=lambda n: (n["cy"], n["cx"]))
        W.tap(cell["cx"], cell["cy"])
        picked.append((cell["cx"], cell["cy"]))
        for _ in range(8):
            c = next((n for n in W.nodes() if "selected" in n["desc"]), None)
            if c and c["text"] == str(want):
                break
            time.sleep(1.0)
        else:
            raise RuntimeError(f"点了第 {want} 格后，选择计数器没有变成 {want}")

    done = W.find("Done")
    assert done is not None, "多选后没有出现 Done 按钮"
    W.tap(done["cx"], done["cy"])

    deadline = time.time() + 240
    last = None
    while time.time() < deadline:
        try:
            last = library_count(W)
        except RuntimeError as e:
            log(f"    （dump 暂时不可用，继续等：{e}）")
            last = None
        if last is not None and (before is None or last > before):
            log(f"    页头：原图加密存储 · {last} 张（导入前 {before} 张）")
            return last
        time.sleep(1.5)
    raise RuntimeError(f"照片库张数始终没变（导入前 {before}，最后读到 {last}）")


def has_text(W, sub):
    try:
        return W.find(sub) is not None
    except RuntimeError:
        return False


def pending_journal():
    """是否存在**有待回滚内容**的回滚日志。

    判据是"日志非空"，不是"日志存在" —— SQLite 提交之后会留下一个 **0 字节**的
    `vault.db-journal` 占位（日志模式为 PERSIST 时的正常行为）。
    把"文件存在"当成"有未提交事务"，会让这个检查永远过不去，
    而且会让人误以为数据库一直处于危险状态。
    """
    listing = sh("shell", "run-as", PKG, "ls", "-l", "databases")
    for line in listing.splitlines():
        parts = line.split()
        # `ls -l` 的列：权限 链接数 属主 属组 **大小** 日期 时间 名字
        # 大小是第 5 列（下标 4）。别从尾部倒数 —— 从尾部数会数到"时间"，
        # int("04:45") 抛异常后被当成"有日志"，于是这个检查永远过不去。
        if len(parts) >= 8 and parts[-1].endswith("-journal"):
            try:
                return int(parts[4]) > 0
            except ValueError:
                return True
    return False


def settle_db(W, tries=6):
    """把数据库留在"没有待回滚内容"的干净状态。

    这是个**真的会把结论带偏**的坑：`am force-stop` 恰好砍在事务中间会留下**非空**的
    `vault.db-journal`，SQLite 下次打开它会把未提交的改动回滚掉 —— 于是
    `vault.db` 的 md5 变了，看起来像"升级把数据改了"，实际是**升级之前就欠下的账**。
    所以取基线要挑在"应用已经正常开过一次库、账都结清"的时刻。
    """
    for i in range(tries):
        if not pending_journal():
            return True
        log(f"    数据库还有未结清的回滚日志，让应用开一次库（第 {i + 1} 次）")
        sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        try:
            W.ensure_home(timeout=120)
        except Exception as e:
            log(f"    （进主页未成功：{e}）")
        time.sleep(3.0)
    return not pending_journal()


def create_note(W, title):
    """新建一条纯文字记录。

    **主页那个「新建」浮标在 uiautomator 里完全没有文本**（Compose 的语义合并把
    `Text("新建")` 吞进了外层的可点节点，而外层节点又不带 text/desc）——
    按文字找必然找不到。判据只能用"可点 + 无文字 + 在屏幕下方"，
    屏幕上半部分那个同类节点是照片库的「添加照片」，靠 y 区分。
    这个形态和 `import_photos` 里找同一个 FAB 的判据是一致的。
    """
    log(f"新建一条文字记录：{title}")
    cands = [n for n in W.nodes()
             if n["clickable"] and not (n["text"] or n["desc"]) and n["cy"] > 1900]
    if not cands:
        raise RuntimeError("主页找不到「新建」浮标按钮")
    fab = max(cands, key=lambda n: (n["cy"], n["cx"]))
    W.tap(fab["cx"], fab["cy"])

    # 类型对话框：行的文字与可点区域是两个节点，按文字坐标点仍然落在行内
    W.wait_text("文字", what="新建类型对话框")
    W.tap_text("文字")
    W.wait_text("新建文字", what="文字编辑器")
    W.fill_field(0, title)
    W.fill_field(1, "written-before-upgrade")
    W.hide_ime()
    save = next((n for n in W.nodes() if n["text"] == "保存" and n["enabled"]), None)
    if save is None:
        raise RuntimeError("编辑器里找不到可用的「保存」")
    W.tap(save["cx"], save["cy"])
    W.wait_text("本机加密 · ", timeout=40, what="回到主页")
    time.sleep(1.5)


# ==================== 阶段一：在 v1.0.6 上造数据 ====================

def phase_seed():
    os.makedirs(OUT, exist_ok=True)
    info = pkg_info()
    log(f"当前安装：{info['versionName']}（versionCode {info['versionCode']}）")
    check("造数据的基线必须是 v1.0.6（versionCode 7）",
          info["versionCode"] == "7" and info["versionName"] == "1.0.6",
          f"实际 {info['versionName']}/{info['versionCode']}")

    # 引导 + 一条记录 + 两张照片
    W = load(rf"{BASE}/tools/walkthrough.py", "W")
    # 被加载模块的顶层代码会把输出目录改回它自己的，必须在 exec_module 之后重设
    W.OUT = OUT
    W.SHOT_INDEX = 0

    log("pm clear → 冷启动 → 走完引导")
    sh("shell", "pm", "clear", PKG)
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    W.wait_text("开始设置", timeout=90, what="引导第一屏")
    W.tap_text("开始设置")

    W.wait_text("设置主密码")
    W.fill_field(0, W.MASTER)
    W.fill_field(1, W.MASTER)
    W.hide_ime()
    W.tap_text("下一步")

    W.wait_text("要不要再设一个假密码")
    W.tap_text("跳过，生成保险库")

    W.wait_text("抄下恢复码", timeout=240, what="恢复码屏")
    W.scroll_top()
    ns = W.nodes()
    groups = [n["text"] for n in ns if re.fullmatch(r"[0-9A-Z]{4}", n["text"])]
    assert len(groups) == 12, f"应显示 12 组恢复码，实际 {len(groups)}"
    labels = [int(m.group(1)) for n in ns
              for m in [re.fullmatch(r"第 (\d+) 组", n["text"])] if m]
    assert len(labels) == 3, f"应随机抽 3 组回填，实际 {labels}"
    wanted = [groups[i - 1] for i in labels]
    W.hide_ime()
    W.scroll_top()
    es = W.edits()
    W.tap(es[0]["cx"], es[0]["cy"])
    W.type_ascii(wanted[0])
    for value in wanted[1:]:
        sh("shell", "input", "keyevent", "61")   # TAB：按下标取坐标会填错框
        time.sleep(0.6)
        W.type_ascii(value)
    W.hide_ime()

    for _ in range(6):
        cb = next((n for n in W.nodes() if n["checkable"]), None)
        if cb is None:
            sh("shell", "input", "swipe", "540", "1500", "540", "800", "250")
            time.sleep(0.9)
            continue
        if cb["checked"]:
            break
        W.tap(cb["cx"], cb["cy"])
        time.sleep(0.7)
    else:
        raise RuntimeError("复选框始终勾不上")

    enter = W.find("进入保险库")
    assert enter is not None and enter["enabled"], "「进入保险库」不可用"
    W.tap(enter["cx"], enter["cy"])
    W.wait_text("还没有任何记录", timeout=120, what="主页空列表")
    time.sleep(1.5)
    W.shot("seed-01-主页空列表")

    create_note(W, NOTE_TITLE)

    # 照片：走系统选图器（相册里要有图，前面几轮已推进去几张纯色图）
    log("导入照片")
    open_photo_library(W)
    total = import_photos(W, 2)
    W.shot("seed-02-已导入照片")
    log(f"    照片库现有 {total} 张")

    # 回主页，把「老用户刚导出过备份」这个状态摆出来
    W.ensure_home()
    count = home_note_count(W)
    # 注意不是 1：往照片库导图会**另生成一条图片记录**（导入即成为库里独立的一份），
    # 所以这里应该是"1 条文字 + 1 条图片"。断言的是"它有一个确定的值"，
    # 升级之后必须还是这个值。
    check("升级前主页记录数已确定", count is not None and count >= 2,
          f"实际 {count}（期望至少 2：1 条文字 + 1 条图片）")
    W.shot("seed-03-主页有内容")

    now_ms = int(time.time() * 1000)
    log(f"模拟「刚导出过备份」：把 {KEY_BACKUP_REMINDER} 写成当前时间")
    write_pref_value(KEY_BACKUP_REMINDER, now_ms)

    check("数据库已结清回滚日志（否则 md5 比对会得出错误结论）", settle_db(W))
    sh("shell", "am", "force-stop", PKG)
    time.sleep(2.0)

    state = md5_state()
    baseline = {
        "versionName": info["versionName"],
        "versionCode": info["versionCode"],
        "firstInstallTime": info["firstInstallTime"],
        "lastUpdateTime": info["lastUpdateTime"],
        "noteTitle": NOTE_TITLE,
        "noteCount": count,
        "photoCount": total,
        "prefBackupReminder": str(now_ms),
        "md5": state,
    }
    io.open(BASELINE, "w", encoding="utf-8", newline="\n").write(
        json.dumps(baseline, ensure_ascii=False, indent=2))
    log(f"基线已存 {BASELINE}")
    for path, digest in sorted(state.items()):
        print(f"    {digest}  {path}")
    print(f"\n文件数 {len(state)}；下一步执行：")
    print(f"    {ADB} install -r {APK_DEBUG_NEW.replace('/', chr(92))}")
    print(f"    然后 python tools/upgrade-v107.py verify")


# ==================== 阶段二：升级之后核对 ====================

def phase_verify():
    base = json.loads(io.open(BASELINE, encoding="utf-8").read())
    info = pkg_info()
    log(f"当前安装：{info['versionName']}（versionCode {info['versionCode']}）")

    check("已是 v1.0.7（versionCode 8）", info["versionCode"] == "8",
          f"实际 {info['versionCode']}")
    check("firstInstallTime 未变 → 这是升级而不是重装",
          info["firstInstallTime"] == base["firstInstallTime"],
          f"升级前 {base['firstInstallTime']}，现在 {info['firstInstallTime']}")
    check("lastUpdateTime 已更新到这次安装",
          info["lastUpdateTime"] != base["lastUpdateTime"],
          f"升级前 {base['lastUpdateTime']}，现在 {info['lastUpdateTime']}")

    after = md5_state()
    before = base["md5"]

    log("逐字节比对私有目录（文件级证据）")
    missing = [p for p in before if p not in after]
    changed = [p for p in before if p in after and after[p] != before[p]]
    added = [p for p in after if p not in before]
    check("升级前的文件一个都没少", not missing, f"少了 {missing}")
    check("升级前的文件内容一字未改", not changed,
          "; ".join(f"{p}: {before[p]} → {after[p]}" for p in changed))
    if added:
        log(f"    （升级后新增的文件，属正常：{added}）")
    for path in sorted(before):
        print(f"    {'OK ' if after.get(path) == before[path] else '!! '}{after.get(path)}  {path}")

    # ---- 偏好：本轮重构动过的键必须还能被读到 ----
    got = read_pref_value(KEY_BACKUP_REMINDER)
    check(f"{KEY_BACKUP_REMINDER} 的值在升级后仍然是升级前那个",
          got == base["prefBackupReminder"],
          f"升级前 {base['prefBackupReminder']}，现在 {got}")

    # ---- 界面：数据在不在、有没有冒出假的催促条 ----
    W = load(rf"{BASE}/tools/walkthrough.py", "W")
    W.OUT = OUT
    W.SHOT_INDEX = 0
    W.ensure_home()
    W.shot("verify-01-升级后主页")

    count = home_note_count(W)
    check(f"升级后主页仍是 {base['noteCount']} 条记录", count == base["noteCount"],
          f"升级前 {base['noteCount']}，现在 {count}")
    check("升级前写的那条记录标题还在", has_text(W, NOTE_TITLE), "列表里找不到标题")
    check("升级后没有冒出假的「还没有导出过备份」催促条",
          not has_text(W, "还没有导出过备份"),
          "刚导出过却提示从未导出 —— 偏好键多半被改名了")

    # md5 只证明"字节没变"，不代表"还解得开"。能显示出来才算真的还在。
    log("进照片库核对图片还解得开")
    open_photo_library(W)
    photos = None
    deadline = time.time() + 40
    while time.time() < deadline:
        photos = library_count(W)
        if photos is not None:
            break
        time.sleep(1.5)
    W.shot("verify-02-升级后照片库")
    check(f"升级后照片库仍是 {base['photoCount']} 张（且能被解密显示）",
          photos == base["photoCount"],
          f"升级前 {base['photoCount']}，现在 {photos}")
    W.ensure_home()

    # ---- 新功能回归：把时间戳推到 8 天前，催促条必须出现 ----
    log("把时间戳改到 8 天前，验证周期性提醒真的会响")
    stale = int(time.time() * 1000) - 8 * 24 * 60 * 60 * 1000
    write_pref_value(KEY_BACKUP_REMINDER, stale)
    sh("shell", "am", "force-stop", PKG)
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    W.ensure_home()
    appeared = False
    deadline = time.time() + 60
    while time.time() < deadline:
        if has_text(W, "已经超过 7 天"):
            appeared = True
            break
        time.sleep(1.5)
    W.shot("verify-03-过期催促条")
    check("超过 7 天后出现「已经超过 7 天」催促条", appeared,
          "只写不读的老毛病又回来了？")
    check("催促条出现时不再同时提示从未导出",
          not has_text(W, "还没有导出过备份"))

    # 收尾：把时间戳还原，别把设备留在"一直催促"的状态
    write_pref_value(KEY_BACKUP_REMINDER, base["prefBackupReminder"])
    log("已把时间戳还原")

    print(f"\n结果：{len(FAILURES)} 项失败")
    for f in FAILURES:
        print(f"  FAIL {f}")
    return 1 if FAILURES else 0


# ==================== 阶段三：release 包覆盖升级冒烟 ====================

def phase_release():
    """release 包唯一能做的是"装得上、起得来"。

    它既不能 `run-as`（非 debuggable），也截不到图（FLAG_SECURE 默认开），
    所以界面级取证留给 debug 包；这里守的是**签名一致 + 能覆盖安装**这条链路。
    """
    os.makedirs(OUT, exist_ok=True)
    log("卸载 → 装 v1.0.6 release → 覆盖装 v1.0.7 release")
    sh("uninstall", PKG)
    time.sleep(2)

    r1 = sh("install", APK_RELEASE_OLD)
    check("v1.0.6 release 安装成功", "Success" in r1, r1.strip()[-200:])
    old = pkg_info()
    check("装上的确实是 v1.0.6", old["versionCode"] == "7", f"实际 {old['versionCode']}")

    # 冷启动一次，确认这个基线包本身能跑起来（否则后面的失败无法归因）
    sh("shell", "logcat", "-c")
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(25)
    focus = sh("shell", "dumpsys", "window")
    check("v1.0.6 能冷启动到前台", PKG in focus or "fpb" in focus.lower(),
          "前台窗口里没有本应用")

    r2 = sh("install", "-r", APK_RELEASE_NEW)
    check("v1.0.7 release **覆盖安装**成功（签名一致，未被系统拒绝）",
          "Success" in r2, r2.strip()[-300:])
    check("没有被 INSTALL_FAILED_UPDATE_INCOMPATIBLE 拦住",
          "INSTALL_FAILED_UPDATE_INCOMPATIBLE" not in r2, r2.strip()[-300:])

    new = pkg_info()
    check("版本升到 1.0.7（versionCode 8）",
          new["versionCode"] == "8" and new["versionName"] == "1.0.7",
          f"实际 {new['versionName']}/{new['versionCode']}")
    check("firstInstallTime 未变 → 确实是覆盖安装，没有走卸载重装",
          new["firstInstallTime"] == old["firstInstallTime"],
          f"升级前 {old['firstInstallTime']}，现在 {new['firstInstallTime']}")

    # 冷启动 + 崩溃检查
    sh("shell", "logcat", "-c")
    sh("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(30)
    logs = sh("shell", "logcat", "-d")
    crashes = [l for l in logs.splitlines()
               if "FATAL EXCEPTION" in l or PKG in l and "AndroidRuntime" in l]
    check("v1.0.7 release 冷启动无崩溃", not crashes, "; ".join(crashes[:3]))
    focus = sh("shell", "dumpsys", "window")
    check("v1.0.7 release 冷启动后在前台", PKG in focus, "前台窗口里没有本应用")

    io.open(rf"{OUT}/release-upgrade.txt", "w", encoding="utf-8", newline="\n").write(
        f"v1.0.6 release 安装输出:\n{r1}\n\nv1.0.7 release 覆盖安装输出:\n{r2}\n\n"
        f"升级前: {old}\n\n升级后: {new}\n")

    print(f"\n结果：{len(FAILURES)} 项失败")
    for f in FAILURES:
        print(f"  FAIL {f}")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    os.makedirs(OUT, exist_ok=True)
    if mode == "seed":
        phase_seed()
        sys.exit(1 if FAILURES else 0)
    elif mode == "verify":
        sys.exit(phase_verify())
    elif mode == "release":
        sys.exit(phase_release())
    else:
        print(__doc__)
        sys.exit(2)
