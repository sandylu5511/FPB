#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""复现并定位「画面冻住」：打开影片就冻 / 切后台再回来冻。

    python tools/_probe_surface_recreate.py [轮数]

## 起因

v1.1.0 的 release 验收里 `返回后还能重新播放` 红了。落盘证据（同一套量算法离线复量）：

    B-横屏-再播-00..05        白块 315→338，反推 37.7s→40.5s   ← 正常在播
    B-横屏-后台返回-01..03    白块恒为 0..79（反推 0.0s），**三帧 md5 完全相同**
    B-横屏-返回后重播-01..04  与上一组**逐字节同一份**（md5 f50e77bf）
    B-横屏-返回后重播-00      控制条显示 `0:52` + **暂停图标**（= 应用认为在播）

一个像素没变，而应用认为自己"在播"、位置读数在走。用户看到的是"进度条在走、画面不动"。

**不是必现**：`v110-release.run1` 同一条判据是过的（返回后画面从 0.9s 走到 13.8s），
所以这是竞态 —— 得靠不止一个视角去看。

## 两条改进（相对上一版）

1. **不再丢弃"切之前就没在动"的轮次。**

   上一版在 `if not moving(before): return None` 就把它扔了，跑出来是
   `有效轮次 0/3` —— 三轮全废、什么都没留下。可是"打开就冻"本来就是同一个现象的
   另一个入口，把它扔掉等于主动放弃样本。现在它被记成 `frozen_on_open` 并留现场。

2. **加一条 guest 之外的观测通道。**

   模拟器自己的日志（`build/_emu*.log`）里混着**宿主机侧** FFmpeg 的 H.264 解码器：
   它打 `no frame!` 就是"这段比特流没解出画面"。这条**在 logcat 里看不到** ——
   上一轮查遍 logcat 只见 `MEDIA_INFO_VIDEO_RENDERING_START`、毫无错误，就是缺了这个
   视角。现在每轮只统计**本轮时间窗内新增**的那几次，并另量一段"没在放视频"的基线
   做对照：没有基线的话，"出现了 5 次 no frame"说明不了任何事。

3. **复用走查自己的量算法**（`centroids` / `motion_stats`），不重写一遍 ——
   重写就等于给"量错了"再开一个入口。

## 怎么读结论

    frozen_on_open       打开就冻（跟切后台无关）→ 指向"起播"这条路
    frozen_after_return  切后台再回来冻 → 指向 Surface 重建这条路（就是那条 FAIL）
    ok                   画面在动 → 这一轮正常

三种都要看同一个东西：**画面冻住的那些轮次里，宿主机解码器是不是也在报 no frame**。
是 → 冻结发生在 decoder 输出之前（不是我们没把画面贴上去）；
否 → 解码器一直在出帧，那就是渲染/贴图这一侧的事。
"""
import glob
import hashlib
import importlib.util
import os
import sys
import time

BASE = r"D:\MixiaVault"
OUT = rf"{BASE}\dist\evidence\surface-recreate"


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V110 = load(rf"{BASE}\tools\walkthrough-v110-video.py", "v110")
V105 = V110.V105
W = V110.W
R108 = V110.R108

V110.OUT = OUT
W.OUT = OUT
# 截图名靠 PHASE_TAG 拼前缀，不设的话会拼出 `01-?-….png` —— 文件名里带 `?`
# 在 Windows 上直接 OSError(22)。release 验收那边也是这么设的。
V110.PHASE_TAG = "P"
W.SHOT_INDEX = 0


# ---------------------------------------------------------------- 宿主机解码器
def host_log_path():
    """模拟器进程自己的日志。取最新那个 —— 重启模拟器会换新文件。"""
    cands = glob.glob(os.path.join(BASE, "build", "_emu*.log"))
    return max(cands, key=os.path.getmtime) if cands else None


def host_window():
    """记一个"时间窗"：`(文件, 当前字节偏移)`。之后只数这个偏移之后新增的日志。"""
    p = host_log_path()
    if not p or not os.path.exists(p):
        return None
    return (p, os.path.getsize(p))


def host_noframes(mark):
    """[mark] 之后新增的日志里，宿主机解码器报了几次 `no frame!`；量不到返回 None。"""
    if mark is None:
        return None
    p, start = mark
    try:
        with open(p, "rb") as f:
            f.seek(start)
            seg = f.read()
    except OSError:
        return None
    return seg.decode("utf-8", "replace").count("no frame!")


def noframe_text(n):
    if n is None:
        return "宿主机日志读不到（没法用这条视角）"
    if n == 0:
        return "宿主机解码器 0 次 `no frame!`（它一直在出帧）"
    return f"宿主机解码器 **{n} 次 `no frame!`**（比特流没解出画面）"


# ---------------------------------------------------------------- 基本动作
def wait_home(timeout=180):
    """等到"已解锁、能干活"的状态。

    **不能只认主页**：上一轮验收结束时应用往往停在媒体库那类内页上（实测就是停在
    「照片与视频 · 0 项」），此时再去等主页会白等满 180 秒 —— 而这些内页意味着
    应用**已经解锁**了，直接就能往下走。

    三种可接受的状态：① 主页；② 已经停在媒体库（后续导入可以直接做）；
    ③ 解锁页 → 用主密码解锁（与 release 验收同一套判断）。
    """
    W.adb("shell", "am", "start", "-n", f"{V110.PKG}/.MainActivity")
    end = time.time() + timeout
    while time.time() < end:
        try:
            if W.find("本机加密 ·") is not None:
                W.log("    已在主页")
                time.sleep(1.0)
                return True
            if W.find("添加照片或视频") is not None or W.find("还没有照片或视频") is not None:
                W.log("    已解锁，且停在媒体库页 —— 直接用")
                time.sleep(1.0)
                return True
            on_unlock = W.find("忘记密码") is not None
        except RuntimeError:
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
    raise RuntimeError("等不到可干活的状态（主页 / 媒体库 / 解锁页 都没认出来）")


def ensure_videos_imported():
    """库里没有那两段影片就导一次。"""
    if W.find("2:00") is not None:
        W.log("    库里已经有 2:00 那段影片，直接进")
        return
    if W.find("添加照片或视频") is None:
        # 还在主页：从主页进媒体库
        V110.open_media_library("照片与视频")
    else:
        W.log("    已在媒体库页，直接点「添加照片或视频」")
        hit = W.find("添加照片或视频")
        W.tap(hit["cx"], hit["cy"])
    cells, _blobs, im = V110.await_picker()
    V110.tap_picker_cells(cells, im)
    end = time.time() + 180
    while time.time() < end:
        if W.find("2:00") is not None:
            W.log("    两段影片已落库")
            return
        time.sleep(3.0)
    raise RuntimeError("导入之后网格里等不到 2:00 角标")


def ensure_screenshots_visible():
    """只有在截图真的被 FLAG_SECURE 挡住时才去关它。

    **不能无条件调 `grant_screenshots()`**：它的入口在**主页**的设置里，而这个探针
    经常是在"应用已解锁、停在媒体库内页"的状态下启动的（上一轮验收就是这么收尾的），
    这时它会 `assert 打不开设置页` 直接崩掉。

    判据用像素：截一帧，平均亮度低于 8 就说明画面被挡成黑的（实测被挡时是 0.08/255，
    没被挡时是一百多 KB 的正常画面）。这样"已经关过了"就不会再白跑一趟。
    """
    fr = R108.screen_frame()
    if fr is not None and fr[2] >= 8.0:
        W.log(f"    截图不是黑的（平均亮度 {fr[2]:.2f}/255）→「禁止截屏」已经关着，跳过")
        return
    W.log(f"    截图是黑的（平均亮度 {fr[2] if fr else '量不到'}/255）→ 需要关掉「禁止截屏」")
    V110.grant_screenshots()


def in_viewer():
    """是不是真的停在大图页：只有大图页顶栏有 `n / m` 那个页码。"""
    try:
        return any(re_fullmatch_page(n["text"].strip()) for n in W.nodes())
    except RuntimeError:
        return False


def re_fullmatch_page(s):
    import re
    return re.fullmatch(r"\d+ / \d+", s) is not None


def leave_and_reopen():
    """回到网格，再点开 2:00 那一段。

    **必须先在不在大图页**：`leave_viewer()` 找的是「关闭」**或「返回」**，
    而媒体库网格页顶栏上就有一个「返回」—— 不在大图页时按它等于退出到主页，
    接着 `open_cell_by_label` 就报"网格里找不到时长角标 2:00"。
    （第一次跑就是这么死的：导入成功、然后一轮都没跑起来。）
    """
    if in_viewer():
        V110.leave_viewer()
        time.sleep(1.5)
    else:
        W.log("    已经不在大图页，直接点格子")

    end = time.time() + 20
    while time.time() < end and W.find("2:00") is None:
        time.sleep(1.0)
    V110.open_cell_by_label("2:00")
    assert V110.wait_viewer(timeout=30), "大图页没起来"
    time.sleep(2.0)


# ---------------------------------------------------------------- 量一轮
def burst_report(tag, n=6):
    """连拍 [n] 帧 + md5。

    质心只是"动没动"的粗判；`md5 逐字节相同` 才是"画面真的没变"的硬判据
    —— 质心差 0.1px 也会被判成"变了"，没有灰色地带这件事只有 md5 给得了。
    """
    rows = V110.burst(tag, n)
    xs = V110.centroids(rows)
    st = V110.motion_stats(xs)
    md5s = [hashlib.md5(open(p, "rb").read()).hexdigest()[:8] for p, _ in rows]
    frozen = len(set(md5s)) == 1
    W.log(f"    各帧白块质心：{xs['points']}")
    W.log(f"    {V110.motion_text(st)}")
    W.log(f"    各帧 md5：{md5s} → {'**逐字节相同**' if frozen else '在变'}")
    return {"st": st, "md5s": md5s, "frozen": frozen}


def app_state(prefix):
    """应用自己认为在播还是暂停 + 它报的时间。"""
    ns = V110.reveal_controls(rounds=3)
    glyphs = [n["desc"] for n in ns if n["desc"] in ("播放", "暂停")]
    times = [n["text"].strip() for n in ns if n["text"].strip() and ":" in n["text"]]
    W.log(f"    控制条按钮 {glyphs}、时间 {times}")
    W.shot(f"{prefix}-控制条")
    if "暂停" in glyphs:
        return "在播", glyphs, times
    if "播放" in glyphs:
        return "暂停", glyphs, times
    return "读不到", glyphs, times


def wait_moving(timeout=25):
    """等画面真的动起来，最多 [timeout] 秒。返回第一次量到"在播"的读数。"""
    end = time.time() + timeout
    last = None
    while True:
        last = V110.motion_probe(4)
        if V110.moving(last):
            return last
        if time.time() >= end:
            return last
        time.sleep(1.5)


def dump_logcat(idx):
    log = W.adb("logcat", "-d", "-v", "brief")
    path = os.path.join(OUT, f"logcat-R{idx}.txt")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(log)
    keys = ("MediaPlayer", "MediaCodec", "NuPlayer", "MediaHTTP", "AwesomePlayer",
            "JMediaDataSource", "Surface", "BufferQueue", "Decoder", "h264")
    hits = [l for l in log.splitlines()
            if any(k in l for k in keys) or " E " in l or " W " in l]
    print(f"    （logcat 命中 {len(hits)} 行，全量见 {path}）")
    for l in hits[-15:]:
        print("      " + l[:170])
    return len(hits)


def one_round(idx):
    """一轮：进影片 → 等它播起来 → （能播就）HOME → 回来 → 量画面 + 收两个日志。

    返回 `(kind, payload)`；`kind` 见模块 docstring 的三种结论。
    """
    print(f"\n================ 第 {idx} 轮 ================")
    win = host_window()
    leave_and_reopen()

    W.log("    先等它播起来（最多 25 秒）")
    before = wait_moving()
    if not V110.moving(before):
        # 上一版在这里 `return None` 把它扔了 —— 于是"打开就冻"这条入口从没被记录过。
        # 现在照样留现场：画面冻住时应用自己认为在播还是一直在转圈，是两种毛病。
        W.log(f"    ⚠ 打开了但画面没动：{V110.motion_text(before)}")
        br = burst_report(f"R{idx}-打开就冻", 6)
        state, glyphs, times = app_state(f"R{idx}-打开就冻")
        nf = host_noframes(win)
        W.log(f"    应用自己认为：{state}；{noframe_text(nf)}")
        dump_logcat(f"{idx}-open")
        print(f"    → **打开就冻**：md5 {'逐字节相同' if br['frozen'] else '在变'}"
              f"，应用认为 {state}，{noframe_text(nf)}")
        return "frozen_on_open", {"state": state, "glyphs": glyphs,
                                  "times": times, "noframe": nf,
                                  "frozen": br["frozen"], "speed": before["speed"],
                                  "truth": before["truth"]}

    W.log(f"    切之前确实在播：{V110.motion_text(before)}")

    # 清 logcat，只保留"切后台之后"那一段
    W.adb("logcat", "-c")
    W.log("    按 HOME 切后台 → 再切回来")
    W.adb("shell", "input", "keyevent", "3")
    time.sleep(2.5)
    W.adb("shell", "am", "start", "-n", f"{V110.PKG}/.MainActivity")
    time.sleep(3.0)

    br = burst_report(f"R{idx}-返回后", 6)
    state, glyphs, times = app_state(f"R{idx}-返回后")
    nf = host_noframes(win)
    W.log(f"    应用自己认为：{state}；{noframe_text(nf)}")
    dump_logcat(idx)

    kind = "frozen_after_return" if br["frozen"] else "ok"
    print(f"    → {'**切回来冻住**' if br['frozen'] else '正常在播'}"
          f"，md5 {'逐字节相同' if br['frozen'] else '在变'}"
          f"，应用认为 {state}，{noframe_text(nf)}")
    return kind, {"state": state, "glyphs": glyphs, "times": times,
                  "noframe": nf, "frozen": br["frozen"],
                  "speed": br["st"]["speed"] if br["st"] else None,
                  "truth": br["st"]["truth"] if br["st"] else None}


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    os.makedirs(OUT, exist_ok=True)
    W.log(f"宿主机日志：{host_log_path()}")

    V110.warm_up_uiautomator()
    wait_home()
    ensure_screenshots_visible()
    ensure_videos_imported()

    # 基线：此刻**没在放视频**。没有这一段，"出现了 N 次 no frame" 说明不了任何事
    # —— 它可能本来就在刷刷地打。
    W.log("基线：现在没在放视频，量 15 秒宿主机解码器的输出")
    mark = host_window()
    time.sleep(15)
    base = host_noframes(mark)
    W.log(f"    基线 15 秒：{noframe_text(base)}")

    results = []
    for i in range(1, rounds + 1):
        kind, payload = one_round(i)
        results.append((kind, payload))

    print("\n" + "=" * 74)
    print(f"共 {len(results)} 轮（基线 15 秒 {base} 次 no frame!）")
    for i, (kind, p) in enumerate(results, start=1):
        speed = "—" if p["speed"] is None else f"{p['speed']:.1f}"
        truth = "—" if p["truth"] is None else f"{p['truth']:.1f}"
        print(f"  第 {i} 轮：{kind:20s} 画面{'冻' if p['frozen'] else '动'}，"
              f"{speed}/{truth} px/s，应用认为 {p['state']}，no frame! {p['noframe']}")
    for k in ("frozen_on_open", "frozen_after_return", "ok"):
        n = sum(1 for kind, _ in results if kind == k)
        print(f"    {k:20s} {n} 次")
    print("=" * 74)
    return 0


if __name__ == "__main__":
    sys.exit(main())
