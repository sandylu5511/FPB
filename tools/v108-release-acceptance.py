"""FPB v1.0.8 **release 包**真机验收。

## 为什么要单独跑这一遍

`v108-acceptance.py`（debug 包）验的是"覆盖升级 + 旧记录认领"，用的是**有历史数据的设备**。
这一次验的是别的东西：

  1. **R8 之后的产物能不能跑** —— debug 包不跑 R8，所以它全绿**推不出** release 全绿。
     本轮就有一个只在 release 上会发作的隐患：`BytesMediaSource` 的 `readAt/getSize`
     是**被 MediaPlayer 的 native 侧按名字调用**的，字节码里看不到调用点。
  2. **全新安装** 这条路径（上一轮走的是覆盖安装）。

## release 包带来的两个限制（都是"非 debuggable"的直接后果）

  · `run-as` 用不了（实测 `run-as: package not debuggable`），`adb root` 也不行
    （`adbd cannot run as root in production builds`）→ **读不到 files/attachments**。
    所以 debug 那次最硬的判据"密文 31 441 B = 图片段 + 影片段 + 28"这次用不上，
    判据换成：界面文案 + logcat + 截图。
  · 默认开 FLAG_SECURE（debug 包默认关，就是为了验收能截图）→ 截图全黑。
    脚本第 0 步把"黑屏"本身当成一条证据记下来，验证完再进设置把它关掉。

## 判据为什么这样选

  截图黑不黑，用 `screencap` 的 **raw** 输出（不加 `-p`）算像素均值 ——
  托管 Python 没有 PIL，而 raw 输出本来就带尺寸头，直接统计字节就行。
  窗口有没有带 SECURE 标志，直接问系统（`dumpsys window`），比看截图更直接。
"""

import importlib.util
import os
import re
import subprocess
import sys
import time

BASE = r"D:\MixiaVault"
ADB = r"D:\AndroidSdk\platform-tools\adb.exe"
PKG = "com.fpb.vault"
OUT = rf"{BASE}\dist\evidence\v108-release"
ROOT = rf"{BASE}\dist\evidence\v108-release"
MASTER = "VaultMaster2026x"

# FLAG_SECURE = 1 << 13，dumpsys 打印的窗口 flags 是十六进制
FLAG_SECURE = 0x2000

RESULTS = []


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def sh(*args):
    """跑一条 adb 命令，**stdout 与 stderr 合并**返回。

    必须合并：`run-as` 的拒绝信息走 stderr（`run-as: package not debuggable`），
    只读 stdout 会拿到空串 —— 于是"run-as 用不了"这条判据会判成 False，
    看起来像"run-as 居然能用"。本轮第一次跑就是这么误报的。
    """
    p = subprocess.run([ADB, *args], capture_output=True)
    out = p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")
    return out.replace("\r", "")


def check(label, ok, detail=""):
    mark = "✓" if ok else "✗"
    RESULTS.append((label, ok, detail))
    print(f"  [{mark}] {label}" + (f"  —— {detail}" if detail else ""))
    return ok


# ---------------------------------------------------------------- 屏幕取证

def screen_frame():
    """截一帧 raw 画面，返回 (宽, 高, 平均亮度 0-255, 亮像素占比)。

    raw 格式 = 4 字节宽 + 4 字节高 + 4 字节 format (+ 4 字节 colorspace) + 像素。
    不去用 PIL：托管 Python 里没装，而这里需要的只是"整屏是不是黑的"。
    """
    raw = subprocess.run([ADB, "exec-out", "screencap"], capture_output=True).stdout
    if len(raw) < 64:
        return None
    w = int.from_bytes(raw[0:4], "little")
    h = int.from_bytes(raw[4:8], "little")
    if w <= 0 or h <= 0 or w > 10000 or h > 10000:
        return None
    head = 16 if len(raw) - 16 >= w * h * 4 else 12
    px = raw[head:head + w * h * 4]
    n = w * h
    if n == 0 or len(px) < n * 4:
        return None
    luma = (sum(px[0::4]) + sum(px[1::4]) + sum(px[2::4])) / (3 * n)
    lit = sum(1 for v in px[0::4] if v > 24) / n
    return w, h, luma, lit


def window_secure():
    """**应用自己的活动窗口**有没有带 FLAG_SECURE。读不到返回 None。

    **两种打印格式都要认**：

      · Android 37 起，`dumpsys window windows` 把 flags 打印成**符号名列表**
            fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED …
      · 早期版本是十六进制
            fl=#81810200        （FLAG_SECURE = 1 << 13 = 0x2000）

    本轮第一次跑只认了十六进制，解析器悄悄返回 None，于是三条判据全变红 ——
    而功能完全是对的（像素证据：全黑 vs 99.52% 亮像素）。**解析不到不等于没生效。**

    ## 不能"扣最靠前那个同包名窗口"（2026-09-18 抓到了现场）

    冷启动的一瞬间，同一个包里会**同时**有两个窗口，而启动图那个排在前：

        Window #8 Window{4797ae9 u0 Splash Screen com.fpb.vault}:              ← fl 里没有 SECURE
        Window #9 Window{1fa1e7a u0 com.fpb.vault/com.fpb.vault.MainActivity}: ← fl 里有 SECURE

    于是「release 包开箱即禁止截屏」这条判据**假红**：验收报"这版包没开防截屏"，
    而同一时刻的截图是全黑的 —— 防截屏正生效。它会把人引去查一个并不存在的安全回退。
    （2026-09-17 出过同样的现象，但当时没留现场、事后复现不出来；
    这次留了 `window-dump-1.txt`，两个窗口一目了然。）

    所以按**窗口名**挑：优先 `<包名>/<Activity>` 这种（真正的活动窗口），
    **跳过名字里带 `Splash` 的启动图窗口**。若台面上只有启动图窗口，
    返回 `None` → "还没到能判的时候"，交给调用方重试 ——
    **不是 `False`（那会说成"真没开"）**。挑不到任何活动窗口才退回第一个候选。
    """
    out = sh("shell", "dumpsys", "window", "windows")
    lines = out.splitlines()
    hits = [i for i, line in enumerate(lines) if "Window{" in line and PKG in line]
    if not hits:
        return None
    # `<包名>/` 只出现在"活动窗口"的窗口名里（`com.fpb.vault/com.fpb.vault.MainActivity`）。
    # 启动图窗口叫 `Splash Screen com.fpb.vault`，匹配不上。
    live = [i for i in hits if f"{PKG}/" in lines[i]]
    if not live:
        return None
    return _secure_flag_at(lines, live[0])


def _secure_flag_at(lines, i):
    """第 i 行那个窗口的 `fl=` 里有没有 SECURE。读不到返回 None。

    只在这里解析一次 —— 多一个解析副本就多一个"只认十六进制"的坑
    （见 [window_secure] 的 KDoc）。
    """
    for j in range(i, min(i + 30, len(lines))):
        if not re.match(r"\s*fl=", lines[j]):
            continue
        m = re.match(r"\s*fl=#([0-9a-fA-F]+)", lines[j])
        if m:
            return bool(int(m.group(1), 16) & FLAG_SECURE)
        return "SECURE" in lines[j].split("=", 1)[1].split()
    return None


# ---------------------------------------------------------------- 界面动作

def long_press(W, x, y, hold=900):
    """长按：`input` 没有 longpress 子命令，用"同一点按住一段时间"实现。"""
    W.adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), str(hold))
    time.sleep(0.8)


def grid_cells(W):
    """照片库里可点的格子。

    **踩坑记录（这个判据错过两次，两次报的都是"大图页没出现"）**：
    Compose 渲染成"外层可点节点 + 里层带语义节点"两层，`content-desc` 常只挂里层，
    于是"clickable 且无 text/desc"会把**顶栏返回按钮**（desc 读不到）和
    **右下角「添加照片」浮标**一起捞进来。按 cy 最大挑 → 中浮标（弹选图器）；
    按 cy 最小挑 → 中返回按钮（退回主页）。只能**按 y 区间分段**。
    """
    return [n for n in W.nodes()
            if n["clickable"]
            and not (n["text"] or n["desc"])
            and 280 < n["y1"] < 1900]


def is_checked(W, title):
    """某一行开关当前是不是开着。行的层级不保证，所以按"标题文字所在的行"找。"""
    hit = next((n for n in W.nodes() if n["text"] == title), None)
    if hit is None:
        return None
    for n in W.nodes():
        if n["checkable"] and abs(n["cy"] - hit["cy"]) < 60:
            return n["checked"]
    return None


def toggle_row(W, title):
    """点某一行开关。找不到 checkable 就退回"点标题那一行"。"""
    hit = next((n for n in W.nodes() if n["text"] == title), None)
    if hit is None:
        return False
    cand = [n for n in W.nodes()
            if (n["checkable"] or n["clickable"]) and abs(n["cy"] - hit["cy"]) < 60]
    if not cand:
        cand = [hit]
    W.tap(cand[0]["cx"], cand[0]["cy"])
    time.sleep(1.2)
    return True


def open_settings(W):
    hit = next((n for n in W.nodes() if n["desc"] == "设置"), None)
    if hit is None:
        hit = W.find("设置")
    if hit is None:
        return False
    W.tap(hit["cx"], hit["cy"])
    time.sleep(2.0)
    return True


def video_render_started():
    """logcat 里有没有"第一帧已渲染"。

    这段影片只有 3 543 B、约 1.2 秒，而一次 `uiautomator dump` 本身要 ~1 秒 ——
    "正在播放实况"那几个字在屏幕上活着的时间比一次 dump 还短，轮询撞上纯看运气。
    `MediaPlayerNative: info/warning (3, 0)` 是 MEDIA_INFO_VIDEO_RENDERING_START，
    说明解码器起来了、第一帧真的送到 surface 上了，与界面刷新时机无关。
    """
    out = sh("logcat", "-d", "-v", "brief", "-s", "MediaPlayerNative:*")
    return "info/warning (3, 0)" in out


def decoder_created():
    """解码器真的被创建过（比上面那条更靠前的一道证据）。"""
    out = sh("logcat", "-d", "-v", "brief")
    return ("NuPlayerDriver created" in out) or ("c2.goldfish.h264.decoder" in out)


def import_motion_photo(W):
    """从选图器导入那张实况照片。

    选图器里现在有三张图（`/sdcard/Pictures/motion-photo.jpg` 31 413 B 是最近写入的，
    另外两张是 09-15 的测试图）。选图器按时间倒序，**左上第一格就是它**。
    脚本不假设这一点：导入后如果「实况」角标没出现，就说明选错了图，直接判失败。
    """
    fab = next((n for n in W.nodes()
                if n["clickable"] and not (n["text"] or n["desc"]) and n["cy"] > 1900), None)
    if fab is None:
        return False
    W.tap(fab["cx"], fab["cy"])

    deadline = time.time() + 45
    cells = []
    while time.time() < deadline:
        cells = [n for n in W.nodes()
                 if n["clickable"] and not (n["text"] or n["desc"]) and 900 < n["cy"] < 1800]
        if cells:
            break
        d = W.find("Dismiss")
        if d:
            W.tap(d["cx"], d["cy"])
        time.sleep(1.5)
    if not cells:
        return False

    # 左上角第一格 = 最近写入的那张 = motion-photo.jpg
    cell = min(cells, key=lambda n: (n["cy"], n["cx"]))
    W.tap(cell["cx"], cell["cy"])
    time.sleep(1.5)

    done = W.find("Done") or W.find("完成")
    if done is None:
        return False
    W.tap(done["cx"], done["cy"])

    deadline = time.time() + 180
    while time.time() < deadline:
        hit = W.find("原图加密存储 · ")
        if hit:
            m = re.search(r"(\d+)\s*张", hit["text"])
            if m and int(m.group(1)) >= 1:
                return True
        time.sleep(1.5)
    return False


def tap_select_all(W):
    """点「全选」。按钮是会变的（已选=总数时它显示「取消全选」），所以点完要回读计数。"""
    hit = W.find("共 ")
    total = int(re.search(r"共\s*(\d+)", hit["text"]).group(1)) if hit else None
    for _ in range(3):
        btn = next((n for n in W.nodes()
                    if n["text"] in ("全选", "取消全选") and n["clickable"]), None)
        if btn is None:
            btn = next((n for n in W.nodes()
                        if n["text"] in ("全选", "取消全选")), None)
        if btn is None:
            return False
        W.tap(btn["cx"], btn["cy"])
        time.sleep(0.8)
        hit = W.find("已选 ")
        got = int(re.search(r"已选\s*(\d+)", hit["text"]).group(1)) if hit else None
        if got is not None and total is not None and got == total:
            return True
    return False


def tap_dialog_confirm(W, label):
    """点确认框里的按钮。

    `wait_text("删除选中的")` 会被顶栏删除图标的 `content-desc="删除选中的照片"` 满足 ——
    "对话框已经出现"这个前提根本没被验证。改成等**确切文字**的按钮节点。
    """
    deadline = time.time() + 15
    while time.time() < deadline:
        node = next((n for n in W.nodes() if n["text"] == label), None)
        if node is not None:
            W.tap(node["cx"], node["cy"])
            return True
        time.sleep(0.5)
    return False


def create_text_note(W, title):
    """新建一条纯文字记录。「新建」浮标在 a11y 树里没有任何文本（语义合并吞了）。"""
    cands = [n for n in W.nodes()
             if n["clickable"] and not (n["text"] or n["desc"]) and n["cy"] > 1900]
    if not cands:
        raise RuntimeError("主页找不到「新建」浮标")
    fab = max(cands, key=lambda n: (n["cy"], n["cx"]))
    W.tap(fab["cx"], fab["cy"])
    W.wait_text("文字", timeout=20, what="新建类型对话框")
    W.tap_text("文字")
    W.wait_text("标题", timeout=20, what="编辑器")

    es = [n for n in W.nodes() if n["cls"] == "EditText"]
    W.tap(es[0]["cx"], es[0]["cy"])
    W.type_ascii(title)
    W.hide_ime()
    es = [n for n in W.nodes() if n["cls"] == "EditText"]
    if len(es) > 1:
        W.tap(es[1]["cx"], es[1]["cy"])
        W.type_ascii("acceptance-sentinel")
        W.hide_ime()
    W.tap_text("保存")
    W.wait_text(title, timeout=30, what=f"主页新记录 {title}")


def main():
    os.makedirs(OUT, exist_ok=True)
    V = load(rf"{BASE}\tools\walkthrough-v105.py", "V")
    W = sys.modules["walkthrough"]
    W.OUT = OUT
    W.SHOT_INDEX = 0

    # ============ 0. release 包的身份与默认截屏策略 ============
    print("\n=== 0. 确认装的是 release 包，并记录它默认的截屏策略 ===")
    dump = sh("shell", "dumpsys", "package", PKG)
    flags = (re.search(r"pkgFlags=\[([^\]]*)\]", dump) or [None, ""])[1]
    check("装的是 release 包（没有 DEBUGGABLE）", "DEBUGGABLE" not in flags, f"pkgFlags=[{flags}]")
    runas = sh("shell", "run-as", PKG, "ls", "files/")
    check("run-as 用不了（本轮判据里因此没有「密文尺寸」这一条）",
          "not debuggable" in runas, runas.strip()[:90])

    # ============ 1. 全新安装的引导 ============
    print("\n=== 1. 全新安装：走完整引导 ===")
    V.onboarding()
    W.shot("01-引导完成-空库主页")
    check("引导能在 release 包上走完（Argon2id 派生 + 恢复码回填 + 进入主页）",
          W.find("本机加密") is not None or W.find("还没有任何记录") is not None,
          "已进入主页")

    # ============ 2. 默认截屏策略：先证明它黑，再关掉换回视觉证据 ============
    # 放在引导之后是有意的：引导内部会 pm clear，设置回到默认值 ——
    # 这样验的才是"release 包的默认行为"，而且脚本可以重复跑。
    print("\n=== 2. release 包默认禁止截屏（debug 包默认关，就为了验收能截图）===")
    secure_first = window_secure()
    frame = screen_frame()
    W.shot("02-release-默认禁止截屏")
    if frame:
        _, _, luma, lit = frame
        print(f"      窗口带 FLAG_SECURE：{secure_first}")
        print(f"      整屏平均亮度 {luma:.1f}/255，亮像素占比 {lit * 100:.2f}%")
    check(
        "release 包引导后默认就是禁止截屏，且截图确实是全黑的",
        secure_first is True and frame is not None and frame[3] < 0.01,
        "窗口 flags 里有 SECURE，且整屏亮像素占比 ≈ 0（隐私功能的预期行为，不是故障）",
    )

    print("   —— 进设置关掉「禁止截屏」，换回可截图的验收环境 ——")
    if check("能进设置页", open_settings(W)):
        before = is_checked(W, "禁止截屏")
        if check("设置页有「禁止截屏」开关，且默认是开的", before is True,
                 f"开关当前值：{before}"):
            toggle_row(W, "禁止截屏")
            time.sleep(1.5)
            after = is_checked(W, "禁止截屏")
            check("关掉之后开关状态确实翻转", after is False, f"开关当前值：{after}")
        else:
            toggle_row(W, "禁止截屏")
        for _ in range(3):
            if W.find("本机加密") is not None:
                break
            W.adb("shell", "input", "keyevent", "4")
            time.sleep(1.2)

    frame = screen_frame()
    secure_after = window_secure()
    W.shot("03-已允许截屏")
    if frame:
        _, _, luma, lit = frame
        print(f"      关闭后：窗口带 SECURE={secure_after}，平均亮度 {luma:.1f}，亮像素 {lit * 100:.2f}%")
    check(
        "关掉开关后窗口不再带 SECURE，截图恢复正常",
        secure_after is False and frame is not None and frame[3] > 0.5,
        "像素证据：亮像素占比从 ≈0% 变成 >50%，说明开关真的接到了窗口上（不是只改了个偏好值）",
    )

    # ============ 3. 照片库：导入实况照片并看角标 ============
    print("\n=== 3. 导入实况照片，看有没有「实况」标识（需求1）===")
    hit = W.find("照片库")
    if not check("主页顶栏有照片库入口", hit is not None):
        return 1
    W.tap(hit["cx"], hit["cy"])
    W.wait_text("还没有照片", timeout=30, what="照片库空态")
    W.shot("03-照片库-空态")

    if not check("能从系统选图器导入照片（release 包上选图器调用正常）",
                 import_motion_photo(W),
                 "导入完成，页头出现张数"):
        return 1
    time.sleep(2.5)
    W.shot("04-照片库-实况角标")

    shot = W.nodes()
    badge = next((n for n in shot if n["text"] == "实况"), None)
    check(
        "需求1 图库打了「实况」角标（release 包 / R8 之后）",
        badge is not None,
        "照片格子上有「实况」字样" if badge else "没有「实况」角标 —— 要么选错了图，要么识别被 R8 改坏了",
    )
    head = next((n["text"] for n in shot if "原图加密存储" in n["text"]), None)
    print(f"      页头：{head}")

    # ============ 4. 大图页：播放实况（本轮唯一有 R8 风险的运行时行为）============
    print("\n=== 4. 大图页能不能放出影片（需求1，R8 风险点）===")
    cells = grid_cells(W)
    cell = min(cells, key=lambda n: (n["cy"], n["cx"])) if cells else None
    if not check("找到可点的照片格子", cell is not None, f"{len(cells)} 个候选"):
        return 1
    W.tap(cell["cx"], cell["cy"])
    W.wait_text("双击放大", timeout=30, what="大图页")
    time.sleep(3.0)
    W.shot("05-大图-实况入口")

    pill = next((n for n in W.nodes() if n["text"] == "实况"), None)
    if not check("需求1 大图页有「实况」播放入口", pill is not None):
        return 1

    sh("logcat", "-c")          # 清一次日志，后面看到的行一定是这次点击产生的
    W.tap(pill["cx"], pill["cy"])

    caught = False
    deadline = time.time() + 15
    while time.time() < deadline:
        try:
            if "正在播放实况" in {n["text"] for n in W.nodes()}:
                caught = True
                W.shot("06-大图-正在播放实况")
                break
        except RuntimeError:
            pass
        if video_render_started():
            break
        time.sleep(0.3)

    check(
        "需求1 影片真的被解码并渲染出画面（R8 没把 native 回调入口搞掉）",
        video_render_started(),
        "logcat 出现 MediaPlayerNative: info/warning (3, 0)（第一帧已上屏）"
        + ("；界面也捕捉到了「正在播放实况」" if caught else "；界面文字窗口只有约 1 秒，没撞上不影响结论"),
    )
    check("解码器确实被创建（比上一行更靠前的一道证据）", decoder_created(),
          "logcat 里有 NuPlayerDriver created / c2.goldfish.h264.decoder")

    stop = next((n for n in W.nodes() if n["text"] == "停止"), None)
    if stop:
        W.tap(stop["cx"], stop["cy"])
        time.sleep(0.6)
    W.tap_text("关闭")
    W.wait_text("原图加密存储", timeout=25, what="回到照片库")
    time.sleep(1.5)

    # ============ 5. 图库多选删除（需求3）============
    print("\n=== 5. 图库多选删除（需求3）===")
    cells = grid_cells(W)
    cell = min(cells, key=lambda n: (n["cy"], n["cx"])) if cells else None
    if not check("还能找到照片格子", cell is not None):
        return 1

    long_press(W, cell["cx"], cell["cy"])
    time.sleep(1.0)
    W.shot("07-图库-长按进入多选")
    entered = W.find("已选 ") is not None
    check("需求3 长按进入多选模式", entered,
          "顶栏变成「已选 N 张」" if entered else "长按没有进入多选")

    if entered:
        all_ok = tap_select_all(W)
        W.shot("08-图库-全选")
        check("需求3 「全选」把照片都选上", all_ok, "点完回读的计数与总数一致")

        d = next((n for n in W.nodes() if n["desc"] == "删除选中的照片"), None)
        if check("需求3 有删除按钮", d is not None):
            W.tap(d["cx"], d["cy"])
            time.sleep(1.2)
            W.shot("09-图库-删除确认")
            if not check("需求3 删除前有确认框", tap_dialog_confirm(W, "删除"),
                         "点了对话框里的「删除」"):
                return 1
            time.sleep(3.0)
            W.shot("10-图库-删除后")

    check("需求3 删光后照片库回到空态", W.find("还没有照片") is not None,
          "显示「还没有照片」")

    # ============ 6. 空壳记录是否被一并删除（需求2）============
    print("\n=== 6. 照片删光后那条记录有没有一起消失（需求2）===")
    W.tap_text("返回")
    W.wait_text("本机加密 ·", timeout=25, what="回到主页")
    time.sleep(2.0)
    W.shot("11-主页-记录已消失")

    home = W.nodes()
    still = next((n for n in home if n["text"].startswith("照片 · ")), None)
    check(
        "需求2 只剩照片的记录在照片删光后被自动删除",
        still is None,
        "主页上已经没有「照片 · 」记录" if still is None else f"记录还在：{still['text']}",
    )

    # ============ 7. 列表页批量删除（需求4）============
    print("\n=== 7. 列表页批量删除（需求4）===")
    for title in ("REL-A", "REL-B", "REL-C"):
        create_text_note(W, title)
    W.shot("12-主页-三条记录")

    home = W.nodes()
    rows = [n for n in home if n["text"] in ("REL-A", "REL-B", "REL-C")]
    if not check("三条记录都建好了", len(rows) == 3, f"看到 {len(rows)} 条"):
        return 1

    row = sorted(rows, key=lambda n: n["cy"])[0]
    long_press(W, row["cx"], row["cy"])
    time.sleep(1.0)
    W.shot("13-列表-长按进入多选")
    entered = W.find("已选 ") is not None
    check("需求4 列表页长按进入多选（不用点进详情页）", entered,
          "顶栏变成「已选 N 条」" if entered else "长按没有进入多选")

    if entered:
        all_ok = tap_select_all(W)
        W.shot("14-列表-全选")
        check("需求4 「全选」把三条都选上", all_ok, "点完回读的计数与总数一致")

        d = next((n for n in W.nodes() if n["desc"] == "删除选中的记录"), None)
        if check("需求4 有删除按钮", d is not None):
            W.tap(d["cx"], d["cy"])
            time.sleep(1.2)
            W.shot("15-列表-删除确认")
            if check("需求4 删除前有确认框", tap_dialog_confirm(W, "删除"),
                     "点了对话框里的「删除」"):
                time.sleep(3.0)
                W.shot("16-列表-删除后")

    home = W.nodes()
    leftover = [n["text"] for n in home if n["text"] in ("REL-A", "REL-B", "REL-C")]
    check("需求4 三条记录被一次删干净", not leftover, f"残留：{leftover or '无'}")

    # ============ 汇总 ============
    print("\n" + "=" * 68)
    failed = [r for r in RESULTS if not r[1]]
    for label, ok, detail in RESULTS:
        print(f"  {'✓' if ok else '✗'} {label}")
    print("-" * 68)
    print(f"  {len(RESULTS) - len(failed)}/{len(RESULTS)} 项通过")
    print(f"  截图：{OUT}")
    print("=" * 68)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
