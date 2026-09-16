#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v1.0.6 验收（第 2 部分）：大图按原图比例 + 双击在实际大小之间切换。

## 为什么必须用"量像素"来判，而不是肉眼看截图

"比例对不对"这件事，在小图上肉眼分辨不出来（一块纯色方块，横着放竖着放都像对的）。
上一版正是靠肉眼看不出来才漏掉的。所以这里把截图里**非黑像素的包围盒**量出来，
和"按 Fit 铺进 1080×2400 视口、居中"应得的矩形做像素级比对 ——
裁剪、拉伸、位置偏移，三者都会让这个比对失败。

"位置"这一项是**本次改动的核心证据**：旧实现把大图挂在 Dialog 上，
实测容器整体下移约 135px（1200×1600 的图落在 y 616..2054，而不是 480..1920），
和屏幕一样高的竖图底部会被切掉。改成一屏之内的覆盖层之后，它必须回到正中。

## 扫描窗口的三处排他（都是量出来的，不是猜的）

1. **y < 280 不扫**：顶栏的关闭按钮与页码是白色文字，扫进来会把包围盒顶到屏幕边缘。
2. **底部手势条不扫**（y ≥ 2350 且 350 ≤ x ≤ 730）：系统画的白色胶囊，
   实测位于 y 2364..2373、x 398..681，它不属于图片。
3. **比扫描窗口更大的图**：期望矩形会与扫描窗口求交后再比对 ——
   否则一张满屏竖图（1080×2400）永远量不出 2400 的高度。

前置：库已建好（第 1 部分跑完）、系统浅色。数据不清空。
"""

import importlib.util
import re
import subprocess
import sys
import time

from PIL import Image

APK_DEBUG = r"D:\MixiaVault\app\build\outputs\apk\debug\app-debug.apk"
OUT = r"D:\MixiaVault\dist\evidence\v106"
PKG = "com.fpb.vault"
MASTER = "VaultMaster2026x"

VIEW_W, VIEW_H = 1080, 2400
SCAN_TOP = 280
PILL_Y, PILL_X0, PILL_X1 = 2350, 350, 730
MAX_PICK = 4

FAILURES = []


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V105 = load(r"D:\MixiaVault\tools\walkthrough-v105.py", "w105")
W = V105.W
W.OUT = OUT
W.SHOT_INDEX = 40


# ==================== 度量 ====================

def measure(path):
    """量出图片（非黑像素）的包围盒与平均色。背景是纯黑，所以"非黑"就是图片。"""
    im = Image.open(path).convert("RGB")
    px = im.load()
    width, height = im.size
    x0, x1, y0, y1 = width, -1, height, -1
    rs = gs = bs = n = 0
    for y in range(SCAN_TOP, height):
        skip_band = y >= PILL_Y
        for x in range(0, width, 2):
            if skip_band and PILL_X0 <= x <= PILL_X1:
                continue
            r, g, b = px[x, y]
            if r + g + b > 66:
                x0 = min(x0, x)
                x1 = max(x1, x)
                y0 = min(y0, y)
                y1 = max(y1, y)
                rs += r
                gs += g
                bs += b
                n += 1
    if n == 0:
        return None
    return {"x0": x0, "x1": x1, "y0": y0, "y1": y1,
            "w": x1 - x0 + 1, "h": y1 - y0 + 1,
            "rgb": (rs // n, gs // n, bs // n), "px": n}


def describe(m):
    return (f"包围盒 x[{m['x0']},{m['x1']}] y[{m['y0']},{m['y1']}] "
            f"= {m['w']}×{m['h']}，平均色 {m['rgb']}")


def fit_scale(bw, bh):
    return min(VIEW_W / bw, VIEW_H / bh)


def actual_scale(bw, bh):
    """"实际大小"相当于在 Fit 基础上再放大多少倍（1 图片像素 = 1 屏幕像素）。

    Fit 时整张图被缩了 fit_scale 倍，所以 1:1 要乘它的倒数。
    与实现里的 `actualSizeScale()` 同源；这里用的是"媒体库里的原始尺寸"，
    而实现里用的是解码出来的位图尺寸 —— 两者对测试图一致。
    """
    return 1.0 / fit_scale(bw, bh)


def expected_box(bw, bh, scale=1.0):
    """图按 [scale] 倍（相对"适应屏幕"）居中铺开后，与扫描窗口求交得到的期望包围盒。

    - `scale=1.0`（默认）→ 适应屏幕（Fit）
    - `scale=actual_scale(bw, bh)` → 实际大小（1 图片像素 = 1 屏幕像素）

    **为什么要能算"实际大小"的期望值**：上一版这里只算了 Fit 的期望矩形，
    双击那一步则硬要求"包围盒铺满整屏、黑边消失"。那个判据对 9:20 的屏幕
    配 3:4 的竖图**永远不可能成立** —— 1200×1600 的图做 1:1，纵向只占 1600px，
    屏幕有 2400px，上下必然留黑边。于是明明双击正确生效了（包围盒从 1080×1440
    变成 1080×1600），断言照样判 FAIL，还会打印一句自相矛盾的
    "实际大小应为 1200×1600（大于视口故四边都被截断）"。
    """
    s = fit_scale(bw, bh) * scale
    dw, dh = bw * s, bh * s
    left = (VIEW_W - dw) / 2
    top = (VIEW_H - dh) / 2
    return {
        "x0": max(0, left), "x1": min(VIEW_W - 1, left + dw - 1),
        "y0": max(SCAN_TOP, top), "y1": min(VIEW_H - 1, top + dh - 1),
    }


def box_eq(m, e, tol=9):
    return (abs(m["x0"] - e["x0"]) <= tol and abs(m["x1"] - e["x1"]) <= tol
            and abs(m["y0"] - e["y0"]) <= tol and abs(m["y1"] - e["y1"]) <= tol)


def box_str(bw, bh, scale=1.0):
    e = expected_box(bw, bh, scale)
    return f"x[{e['x0']:.0f},{e['x1']:.0f}] y[{e['y0']:.0f},{e['y1']:.0f}]"


def check(label, ok, detail):
    print(f"    [{'PASS' if ok else 'FAIL'}] {label}：{detail}")
    if not ok:
        FAILURES.append(f"{label} —— {detail}")


def media_aspects():
    """从媒体库取台上现有图片的宽高，作为"可能是哪一张"的候选集。

    这样就不用把测试图的尺寸硬编码进断言 —— 台上有几张、各是什么比例，
    脚本自己问系统。反正每一页都是从这里面选出来的，一定落在候选集里。
    """
    out = subprocess.run(
        [W.ADB, "shell",
         "content query --uri content://media/external/images/media --projection width:height"],
        capture_output=True, timeout=60,
    ).stdout.decode("utf-8", errors="replace")
    pairs = set()
    for m in re.finditer(r"width=(\d+),\s*height=(\d+)", out):
        w, h = int(m.group(1)), int(m.group(2))
        if w > 0 and h > 0:
            pairs.add((w, h))
    return sorted(pairs)


def identify(m, candidates, scale_of=None, tol=9):
    """在候选尺寸里反推"这一页是哪张图"：谁的期望矩形和量到的包围盒对得上。

    `scale_of(bw, bh)` 给出这一帧对应的倍数，默认 1.0（适应屏幕）。
    判"实际大小"那一帧时传 [actual_scale] —— 注意它是**逐张不同**的，
    所以传的是函数而不是一个常数。
    """
    pick = scale_of or (lambda bw, bh: 1.0)
    return [c for c in candidates
            if box_eq(m, expected_box(c[0], c[1], pick(c[0], c[1])), tol)]


# ==================== 交互 ====================

def double_tap(x, y):
    """双击。

    Compose 判定双击有**两个**时间界，太快不算、太慢也不算：

        doubleTapMinTimeMillis = 40ms   两次按下之间至少要隔这么久
        doubleTapTimeoutMillis = 300ms  超过就不再等第二次

    **踩坑记录（本次唯一的假失败就出在这里）**：`input tap` 每次都要起一个 JVM，
    所以"两次 adb input 之间的真实间隔"完全取决于设备有多忙 ——
    模拟器空闲时单条只要 ~35ms，**低于 40ms 下界**，两次点击被当成两个独立单击，
    双击根本不成立；模拟器一忙，单条涨到 95ms 以上，反倒恰好落进窗口。
    于是同一份脚本时好时坏，而"坏"的表现是界面纹丝不动，看起来完全像应用 bug。
    旧版注释里写的"实测两次之间约 200ms"只是在某一次设备较忙的情况下量到的，
    被当成了常数。

    现场对照（1200×1600 的图、第 1 页、同一个模拟器同一次会话）：

        串行无间隔        → 包围盒 1079×1440（适应屏幕，没动）
        `sleep 0.12`      → 包围盒 1079×1600 @ y400..1999（1:1，正确）
        `sleep 0.12` 再点 → 包围盒 1079×1440（复位，正确）

    所以这里**显式**插入 0.12s，把间隔钉在窗口正中（约 155ms），不再看设备脸色。
    """
    W.adb("shell", f"input tap {x} {y}; sleep 0.12; input tap {x} {y}")
    time.sleep(1.5)


def counter_text():
    for n in W.nodes():
        if re.fullmatch(r"\d+ / \d+", n["text"].strip()):
            return n["text"].strip()
    return None


def ensure_home(timeout=240):
    """确保停在**已解锁的主页**。

    **踩坑记录一**：`adb install -r` 会把应用进程杀掉，所以脚本装完包之后的第一件事
    必然是冷启动 —— 而冷启动要先把 Argon2 的代码路径焐热（模拟器上几秒到二十几秒），
    这段时间界面上既没有主页标记也没有解锁页标记。
    第一版在这里固定 sleep 5 秒就去判断，判断落空后又连按 4 次 BACK，
    结果把应用直接退到了桌面（报错"退不回主页"）。
    因此改成**轮询等标记出现**，而不是等固定时长。

    **踩坑记录二**：光轮询还不够，冷启动期间 `uiautomator dump` 本身就会连失败二十几秒
    （前台是系统画的启动窗口，UiAutomation 拿不到根节点，详见 walkthrough.nodes）。
    第二版在轮询里直接调 `W.find()`，dump 一失败就抛异常穿透出去，
    报的还是"dump 解析失败"——**看起来像设备故障，实际只是起得慢**。
    而且这个窗口长度随宿主负载变化：同时跑 Gradle 编译时能从 25 秒拉到 40 秒以上，
    靠"多试几次"是堵不住的。现在改成捕获取不到屏幕的情况继续等，并把它记进日志；
    超时信息里也会报出"有多少轮连屏幕都读不到"，便于区分"起太慢"和"卡在别的界面"。

    两个标记都是各页独有的长串，避免子串误判：
    主页顶栏副标题"本机加密 · N 条"；解锁页的"忘记密码？用恢复码"。
    （"主密码"不能用 —— 设置页那一行"修改主密码"里就含有它。）
    """
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    deadline = time.time() + timeout
    state = None
    blind = 0
    while time.time() < deadline:
        try:
            # 每轮只要一次轻量 dump（retries=2），"重试"这件事交给外层轮询去做。
            # 让 nodes() 自己硬扛（它默认要连试 10 次）会一次吃掉三十几秒，
            # 等于整轮超时只有一两次判断机会；而这里本来就打算反复看，分摊开更划算。
            ns = W.nodes(retries=2)
        except RuntimeError as e:
            # 冷启动期间 dump 会连失败二十几秒（宿主忙时更久）。
            # **"现在 dump 不出来" ≠ "不在主页"** —— 早期版本把它当成后者，
            # 于是判定落空、连按 4 次 BACK 把应用退到桌面，报出极具误导性的"退不回主页"。
            blind += 1
            W.log(f"    （dump 暂时不可用，继续等：{e}）")
            time.sleep(3.0)
            continue
        if W.find("本机加密 ·", ns) is not None:
            state = "home"
            break
        if W.find("忘记密码", ns) is not None:
            state = "locked"
            break
        time.sleep(1.5)
    if state is None:
        raise RuntimeError(
            f"启动后既没到主页也没到解锁页（超时；其中 {blind} 轮连屏幕都读不到）")

    if state == "locked":
        W.log("    冷启动到了解锁页，用主密码解锁")
        es = [n for n in W.nodes() if n["cls"] == "EditText"]
        assert es, "解锁页没有密码输入框"
        W.tap(es[0]["cx"], es[0]["cy"])
        W.type_ascii(MASTER)
        W.hide_ime()
        W.tap_text("解锁")
        W.wait_text("本机加密 ·", timeout=120, what="解锁后的主页")

    time.sleep(1.0)
    W.log("    已在主页")


def open_photo_library():
    hit = W.find("照片库")
    assert hit is not None, "主页顶栏找不到照片库入口"
    W.tap(hit["cx"], hit["cy"])
    W.wait_text("照片库", timeout=25, what="照片库页")


def library_count():
    """照片库页头「原图加密存储 · N 张」里的 N。页头还没渲染出来时返回 None。"""
    hit = W.find("原图加密存储 · ")
    if not hit:
        return None
    m = re.search(r"(\d+)\s*张", hit["text"])
    return int(m.group(1)) if m else None


def import_photos(max_pick):
    """多选导入，返回导入**之后**库里的总张数。

    选图器的坑：计数器与 Done 只在选中第一张后出现；
    已选中的格子外层没有任何标记，重复点同一格会把它取消掉 —— 自己记坐标避让。

    **踩坑记录（这条让整轮验收白跑过一次）**：导入完成回到照片库之后，页头那张数
    并不是立刻变成新值。第一版一看到页头里有个数字就 return，读到的是**导入前的旧值**，
    于是 total 偏小；后面每一步 `wait_text("1 / 4")` 都永远等不到（实际是 "1 / 8"），
    报出来的却是"未出现 第 1 张"——看起来像"大图没打开"，实际是张数读错了，
    排查方向会被带偏很远。所以这里必须等到张数**真的变了**才算导入完成。
    """
    before = library_count()
    W.log(f"打开选图器，最多选 {max_pick} 张（导入前库里有 {before} 张）")
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
    W.log(f"    选图器里看到 {len(cells)} 个格子")

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

    deadline = time.time() + 180
    last = None
    while time.time() < deadline:
        try:
            last = library_count()
        except RuntimeError as e:
            # 导入刚结束时也会有一小段 dump 不可用的窗口，同上：读不到 ≠ 没导入。
            W.log(f"    （dump 暂时不可用，继续等：{e}）")
            last = None
        if last is not None and (before is None or last > before):
            W.log(f"    页头：原图加密存储 · {last} 张（导入前 {before} 张）")
            return last
        time.sleep(1.5)
    raise RuntimeError(
        f"照片库张数始终没变（导入前 {before}，最后读到 {last}）——"
        f"注意这是**张数没刷新**，不是照片没导进去")


def open_viewer_first_cell():
    cells = sorted([n for n in W.nodes() if n["clickable"]
                    and not (n["text"] or n["desc"]) and 250 < n["cy"] < 1900],
                   key=lambda n: (n["cy"], n["cx"]))
    assert cells, "照片网格里找不到可点的缩略图"
    W.tap(cells[0]["cx"], cells[0]["cy"])


def swipe_left():
    """向左滑 = 翻到下一张。"""
    W.adb("shell", "input", "swipe", "900", "1150", "180", "1150", "180")
    time.sleep(1.6)


def swipe_right():
    W.adb("shell", "input", "swipe", "180", "1150", "900", "1150", "180")
    time.sleep(1.6)


# ==================== 主流程 ====================

def main():
    W.adb("install", "-r", "-g", APK_DEBUG)
    ensure_home()

    candidates = media_aspects()
    W.log(f"媒体库里现有 {len(candidates)} 种宽高组合：{candidates}")

    open_photo_library()
    total = import_photos(MAX_PICK)
    W.log(f"    已入库 {total} 张")

    W.log("点开第 1 张大图，逐页量比例")
    open_viewer_first_cell()
    W.wait_text("1 / ", timeout=30, what="大图预览")
    time.sleep(1.3)

    zoomed_page = None
    for page in range(1, total + 1):
        W.wait_text(f"{page} / {total}", timeout=25, what=f"第 {page} 张")
        time.sleep(1.0)
        p = W.shot(f"{40 + page}-大图-第{page}张-原比例")
        m = measure(p)
        assert m is not None, f"第 {page} 张量不到图片区域"
        hits = identify(m, candidates)
        print(f"    第 {page} 张：{describe(m)}")
        print(f"             → 候选匹配 {hits if hits else '（无）'}")
        check(f"第 {page} 张 · 大图按原图比例居中呈现（未被裁剪/拉伸/偏移）",
              bool(hits), f"{describe(m)}；期望之一见下")

        if not hits:
            for bw, bh in candidates:
                e = expected_box(bw, bh)
                print(f"                期望（{bw}×{bh}）：x[{e['x0']:.0f},{e['x1']:.0f}] "
                      f"y[{e['y0']:.0f},{e['y1']:.0f}]")
        elif zoomed_page is None and any(fit_scale(bw, bh) < 0.95 for bw, bh in hits):
            # 找一张"比屏幕大"的图来做双击验证：只有这种图"实际大小"才与"适应屏幕"不同
            zoomed_page = page
            bw, bh = hits[0]
            W.log(f"    在第 {page} 张（{bw}×{bh}）上做双击验证")
            before = counter_text()
            p1 = W.shot(f"{48 + page}-双击前-适应屏幕")

            W.log("    双击 → 期望放大到实际大小（1 图片像素 = 1 屏幕像素）")
            double_tap(VIEW_W // 2, VIEW_H // 2)
            p2 = W.shot(f"{52 + page}-双击后-实际大小")
            m2 = measure(p2)
            print(f"            双击后：{describe(m2)}")
            # 同一张图要在**两种倍数下都对得上**才算认准了：Fit 那一帧已经筛过一遍（hits），
            # 这里再用实际大小的期望矩形筛一遍，取交集。
            # 这样既不需要知道"到底是哪张"（候选里有 4:3 的 1600×1200 和 4000×3000 两张，
            # 横向 Fit 后包围盒一模一样，光看 Fit 分辨不出来），也不会张冠李戴。
            hits2 = [c for c in identify(m2, candidates, actual_scale) if c in hits]
            expect_txt = "；".join(
                f"{c[0]}×{c[1]} → {box_str(c[0], c[1], actual_scale(c[0], c[1]))}"
                for c in hits)
            check("双击 · 放大到实际大小（1 图片像素 = 1 屏幕像素、居中）",
                  bool(hits2),
                  f"{describe(m2)}；命中 {hits2}；期望（{expect_txt}）")
            check("双击 · 放大过程中没有翻页", counter_text() == before,
                  f"页码 {before} → {counter_text()}")

            # "黑边消失"只有在这种情况下才该成立：实际大小下**两个方向**都超出视口。
            # 竖图（1200×1600）在 2400px 高的屏上做 1:1 时纵向只有 1600px，
            # 上下必然留黑边 —— 上一版拿"铺满整屏"当判据，对它永远判 FAIL。
            if hits2 and hits2[0][0] > VIEW_W and hits2[0][1] > VIEW_H:
                check("双击 · 图比屏幕大，实际大小下四边都被截断（黑边消失）",
                      m2["x0"] <= 3 and m2["x1"] >= VIEW_W - 4
                      and m2["y0"] <= SCAN_TOP + 8 and m2["y1"] >= VIEW_H - 30,
                      describe(m2))

            W.log("    再双击 → 期望回到适应屏幕（按原比例、居中）")
            double_tap(VIEW_W // 2, VIEW_H // 2)
            p3 = W.shot(f"{56 + page}-再双击-回到原比例")
            m3 = measure(p3)
            print(f"            复位后：{describe(m3)}")
            rw, rh = hits2[0] if hits2 else (bw, bh)
            e = expected_box(rw, rh)
            check("双击两下 · 回到按原比例居中", box_eq(m3, e),
                  f"{describe(m3)}；期望 {box_str(rw, rh)}（对应 {rw}×{rh}）")

        if page < total:
            swipe_left()

    check("至少有一张比屏幕大的图可供双击验证", zoomed_page is not None,
          f"在第 {zoomed_page} 页完成双击验证")

    # ---------- 翻页回归：新的双击识别器不能把翻页手势吃掉 ----------
    W.log("左右滑动回归（确认双击识别器没有破坏翻页）")
    W.wait_text(f"{total} / {total}", timeout=25, what="最后一页")
    swipe_right()
    W.wait_text(f"{total - 1} / {total}", timeout=25, what="右滑到上一页")
    check("右滑翻页正常", True, f"{total} / {total} → {total - 1} / {total}")
    swipe_left()
    W.wait_text(f"{total} / {total}", timeout=25, what="左滑回最后一页")
    check("左滑翻页正常", True, f"{total - 1} / {total} → {total} / {total}")

    close = W.find("关闭")
    if close:
        W.tap(close["cx"], close["cy"])
        time.sleep(1.0)

    print()
    if FAILURES:
        W.log(f"验收未通过，共 {len(FAILURES)} 条：")
        for f in FAILURES:
            print(f"    · {f}")
        sys.exit(1)
    W.log("第 2 部分（大图原比例 + 双击）全部通过")


if __name__ == "__main__":
    main()
