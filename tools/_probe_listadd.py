"""探针：往"会满额"的列表里加东西，加不进去时草稿还在不在、有没有话。

**不是走查的一部分**，只回答一件事 —— 第 14 条那条修复在真机上到底生效没有：

    满额（或重复）时按"添加"，输入框里那行字是被清掉了，还是留着并给一句解释。

改前那一侧（`e19cc5c9…`）的预期读数是「输入框空了 + 屏幕上一句话都没有」，
改后那一侧（`722818fc…`）的预期读数是「输入框里还是那几个字 + 多出一句解释」。
两侧都要跑，`改前` / `改后` 会进截图文件名。

用法：

    python tools/_probe_listadd.py fill 32          # 新建一条记录，把标签攒到 32 个
    python tools/_probe_listadd.py tagfull 改后      # 满额时再加一个 → 读数 + 截图
    python tools/_probe_listadd.py tagdup 改后       # 加一个已经存在的标签
    python tools/_probe_listadd.py show              # 看一眼当前屏幕

## 为什么要"先造 32 个"

标签上限是 32，不是 3。要证明"满额时不再丢字"，就得真的让它满 ——
用一个"看起来很满"的近似（比如 3 个标签）去测，测到的是另一段代码。

## 为什么不做"逐段核对落字"

`_probe_hints.py` 里那套逐段核对是为**一次要灌 201 个字符**准备的
（实测一次送 201 个只落了 87 个）。这里每次只送 3 个字符，
而且每个词短、成功/失败在读数上分得开（草稿留没留），所以不套那一层。
但**"字真的进去了"仍然要核**：`fill` 每 8 轮数一次屏上的 chip。
"""
import importlib.util
import os
import re
import subprocess
import sys
import time

BASE = r"D:\MixiaVault"

# 这一侧的名字（改前 / 改后 / …），进截图文件名。缺省用 `未标注`，
# 看到它就说明这一次的截图**不能**用来做对照 —— 与 `_probe_hints.py` 同一个理由。
SIDE = "未标注"


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


W = load(os.path.join(BASE, "tools", "walkthrough.py"), "base")
W.OUT = os.path.join(BASE, "dist", "evidence", "audit6", "listadd-probe")

MAX_TAGS = 32


# ---------------------------------------------------------------- 定位

def hide_ime_once():
    """收键盘，但**最多只发一次** KEYCODE_BACK。

    `walkthrough.hide_ime()` 会在"输入法正在关"的那段窗口期里再补发一次 BACK，
    而补的那一次已经打到应用上了 —— 编辑页把它当成"返回"，弹丢弃确认，
    接着把**还没保存的记录连同十几个标签一起丢掉**。2026-09-18 实测撞到过一次，
    之后所有读数都变成"点击收不到 / 屏幕上没有输入框"，看着像应用坏了。

    这里只发一次：即使键盘没完全收起，后果也只是页面被压缩（内容看不见），
    不会变成"数据没了"。而数据没了是不可挽回的。
    """
    if not W.ime_shown():
        return
    W.adb("shell", "input", "keyevent", "4")
    time.sleep(1.0)


def go_tags():
    """滚到"标签"那一行，返回 (输入框节点, 加号按钮节点)。

    认加号按钮的 `contentDescription`（"添加标签"）而不是认文字：
    输入框的 label 会随着框里有没有内容上下移动，chips 的长短也会变，
    只有这个 desc 是固定的。
    """
    # **先把键盘收起来，而且只收一次**（`hide_ime_once` 最多发一次 BACK）。
    # 键盘占着下半屏时，标签区被压在它下面，swipe 落在输入框/键盘上根本滚不动 ——
    # 2026-09-18 实测连着滑两次页面纹丝不动，一度被读成"这个类型没有标签区"。
    hide_ime_once()
    W.scroll_top()
    for _ in range(8):
        ns = W.nodes()
        btn = next((n for n in ns if n["desc"] == "添加标签"), None)
        if btn is not None:
            return field_for(btn, ns, "go_tags"), btn
        W.adb("shell", "input", "swipe", "540", "1500", "540", "700", "250")
        time.sleep(0.9)
    raise RuntimeError("滚不到标签那一行（找不到 desc=添加标签 的按钮）")


def tag_row(hide=True):
    """dump 一次，取标签输入框与「添加标签」按钮**当前**的坐标。

    **每次都要重新量，不要复用循环开头那一份。**
    坐标是在某个布局状态下量的，而键盘弹出/收起、chips 从 3 行涨到 4 行、
    supportingText 出现，都会让这一行整体位移 ——
    于是"点空了"与"加不进去"的读数**完全一样**（草稿都还在）。
    2026-09-18 实测：复用坐标让第 14 个就"加不进去"，而那时只有 13 个标签、
    屏上一句解释都没有 —— 差一点被读成"这一版改坏了"。
    """
    # 量之前先收键盘：坐标必须与"用它去点的那一刻"处在同一状态。
    # 否则就是 2026-09-18 那个坑：坐标是键盘弹起时量的，点之前键盘被收起来了，
    # 布局整体下移，点击落空 —— 字留在了原先聚焦的正文框里，
    # 而读"标签框里有没有字"只会读到空，看起来像"输入法坏了"。
    #
    # `hide=False` 用于**键盘已经弹起来、且马上还要在键盘起着的这一态里操作**的场合
    # （见 type_into）：那种时候收键盘反而会让坐标失效。
    if hide:
        hide_ime_once()
    ns = W.nodes()
    btn = next((n for n in ns if n["desc"] == "添加标签"), None)
    if btn is None:
        raise RuntimeError("屏幕上找不到 desc=添加标签 的按钮 —— 滚动位置可能偏了")
    return field_for(btn, ns, "tag_row"), btn


# ---------------------------------------------------------------- 读数

def chips():
    """屏上可见的标签 chip。界面上每个 chip 的文字就是 `#<标签>`。"""
    return [n["text"] for n in W.nodes() if (n["text"] or "").startswith("#")]


def count_tags(max_scrolls=10):
    """把页面往下滚一遍，把**去重后**的 chip 全收起来 —— 独立数量旁证。

    为什么不能"数一屏上的 chip"就完事：32 个 chip 排 8 行，底部那两行本来就在屏幕外，
    数一屏一定偏小。但**滚一遍就能收全** —— 这一页本身是可滚动的
    （`go_tags` 找标签行时就是在滚它）。所以"数 chip 不可靠"这个说法只对
    "数一屏"成立，对"滚一遍收全"不成立。

    这条通道补的是造数逻辑里那个**根本性的盲点**：
    "草稿被清空"在改后版上等于"加进去了"，但**改前版上既可能是"加进去了"、
    也可能是"满额被静默丢掉"** —— 两个完全相反的事实、同一个读数。
    所以必须有一条跟草稿无关的数量旁证，否则两侧的读数都不可证伪。
    """
    seen, stale = set(), 0
    W.scroll_top()
    for _ in range(max_scrolls):
        n = len(seen)
        seen.update(chips())
        if len(seen) == n:
            stale += 1
            if stale >= 3:      # 连着三次滚都没收到新 chip → 到底了
                break
        else:
            stale = 0
        W.adb("shell", "input", "swipe", "540", "1600", "540", "900", "250")
        time.sleep(0.7)
    seen.update(chips())
    return seen


def report_count(prefix, added):
    """把编辑页"能收到的 chip"与"以为加进去的"对一遍 —— **这只是个下界**。

    原先这里会打一句"数量站得住"的结论，**那句话是错的**：
    编辑页的标签区**根本不会把所有 chip 暴露出来**。实测记录里明明有 5 个标签，
    界面上只列出 4 个（`#…00` / `#…01` / `#…30` / `#…zz`），
    把页面上下滚、把标签条左右划，第 5 个都出不来。

    所以这里**只报数、不下结论**。真正的数量判据在 `verify` 子命令里 ——
    它读的是**主页卡片**，那是另一个界面，与本页的渲染方式无关。
    """
    got = {t for t in count_tags()}
    want = {f"#{prefix}{i:02d}" for i in range(added)}
    got_mine = {t for t in got if t.startswith("#" + prefix)}
    print(f"  【下界读数】编辑页滚一遍收到的 chip：{len(got)} 个（带本轮前缀 {len(got_mine)} 个）")
    print(f"    ※ 编辑页不会把所有 chip 都暴露出来，所以这只是**下界**，不能当判据；"
          f"数量结论一律以 `verify` 读主页卡片为准")
    missing = sorted(want - got_mine)
    if missing:
        print(f"    编辑页没暴露出来的：{len(missing)} 个（**不代表不存在**）")
    extra = sorted(got_mine - want)
    if extra:
        print(f"    编辑页上多出来的：{extra}")
    return got


# 提示语原文里的片段。**不要**退化成"含不含「最多」"这种宽泛关键词 ——
# 输入框上方那个 label 就叫「加标签（最多 32 个）」，它会命中宽泛词，
# 于是改前改后都"读到一句话"、读数一样、对照作废，而它看起来毫无异常。
NOTICE_MARKERS = ("标签最多", "条待办", "已经在标签里了")


def notices():
    """屏幕上"为什么没加进去"那句话（本轮新增的提示原文）。"""
    out = []
    for n in W.nodes():
        t = (n["text"] or "") + (n["desc"] or "")
        if any(m in t for m in NOTICE_MARKERS):
            out.append(t.strip())
    return out


# 标签输入框与「添加标签」按钮在同一行，两者的 y 相差很小。超过这个容差就不认。
ROW_TOL = 120


def all_edittexts():
    return [n for n in W.nodes() if n["cls"] == "EditText"]


def field_for(btn, ns, where="标签"):
    """与加号按钮**同一行**的那个输入框。找不到、或者找到多个，一律抛异常。"""
    es = [n for n in ns if n["cls"] == "EditText"]
    near = [n for n in es if abs(n["cy"] - btn["cy"]) <= ROW_TOL]
    if len(near) != 1:
        raise RuntimeError(
            f"{where}：与加号按钮同一行（y≈{btn['cy']}，容差 {ROW_TOL}）的输入框"
            f"找到 {len(near)} 个（屏上共 {len(es)} 个）—— 读数不可信，别猜")
    return near[0]


def read_screen(prefix=None):
    """收键盘 → dump **一次** → 一次给出全部读数。

    返回 `(标签框里是什么, 屏上的解释 list, 跑错地方的词 list)`。

    ## 为什么"一次 dump 出全部读数"是这一轮最要紧的结构性改动

    之前的写法是"先量坐标 → 再拿坐标去**另一次 dump** 里找"。只要布局在两次 dump
    之间动过，那个坐标就失效了 —— 而布局变动的诱因在这一页上有好几个：
    键盘弹起/收起（实测顶 323px）、chips 从一行涨到两行（把这一行整体上推 ~90px）、
    提示文字出现。2026-09-18 实测：`type_into` 打完字收键盘后按旧 y 去读，
    屏上找到 **0 个**输入框（`y≈1425` 那个位置已经空了）。

    两种后果都撞过，而且都很难看：

    - 检查严格的时候：直接抛异常，一整轮 12 分钟的造数白跑（就是这次）。
    - 检查不严格的时候：**静默选中正文框**，读数照样有值、节点类型也对 ——
      `added` 被数到 32，而屏上真正的标签只有 4 个（见第 9 条事故）。

    改法是把"量"和"读"压进同一次 dump：`field_for` 从 dump 里取出那个节点，
    直接用它**自己带的** `text`。这样读数永远与它被定位的那一刻同态，
    不存在"坐标过期"这回事 —— 于是上面两种后果都不可能再发生。
    """
    hide_ime_once()               # 让"读"发生在与"下一次点击"相同的布局态里
    ns = W.nodes()
    btn = next((n for n in ns if n["desc"] == "添加标签"), None)
    if btn is None:
        raise RuntimeError("屏幕上找不到 desc=添加标签 的按钮 —— 滚动位置可能偏了")
    field = field_for(btn, ns, "read_screen")

    seen = []
    for n in ns:
        t = ((n["text"] or "") + (n["desc"] or "")).strip()
        if any(m in t for m in NOTICE_MARKERS) and t not in seen:
            seen.append(t)

    stray = []
    if prefix:
        stray = [(n["cy"], n["text"]) for n in ns
                 if n["cls"] == "EditText"
                 and abs(n["cy"] - field["cy"]) > ROW_TOL
                 and prefix in (n["text"] or "")]
    return field["text"], seen, stray


def shot(name):
    """截图，文件名 = 场景 + 哪一侧 + **时刻**。

    光带"哪一侧"还不够：同一个场景同一侧可能跑两次（第一次没看清、或者中途出错重来），
    而 `walkthrough.shot` 的序号每次都从 1 开始 —— 第二次会把第一次整个覆盖掉。
    2026-09-18 丢过一次"改前"那一侧，就是这么丢的。
    """
    os.makedirs(W.OUT, exist_ok=True)
    path = os.path.join(W.OUT, f"{name}-{time.strftime('%H%M%S')}.png")
    data = subprocess.run([W.ADB, "exec-out", "screencap", "-p"],
                          capture_output=True).stdout
    with open(path, "wb") as f:
        f.write(data)
    print(f"    [截图] {os.path.basename(path)}  {len(data)} B")
    return path


# ---------------------------------------------------------------- 动作

def type_into(fx, fy, word, prefix=None):
    """点到输入框、清干净、把 [word] 打进去、收起键盘。

    返回 `(收完键盘后框里的内容, 跑错地方的词 list)`。
    """
    # **这里不收键盘。** `walkthrough.hide_ime()` 收键盘的手段是发 KEYCODE_BACK；
    # 一旦输入法其实没开着，这个 BACK 就被编辑页当成"返回" —— 弹丢弃确认，
    # 再按一次就把**还没保存的记录连同十几个标签一起丢掉**。
    # 2026-09-18 实测撞到过：整条记录消失、页面回到主页，
    # 之后所有读数都变成"点击收不到 / 屏幕上没有输入框"，看起来像应用坏了。
    hide_ime_once()          # 点之前收：坐标必须是"键盘收起"这一态的
    W.tap(fx, fy)
    # 点下去键盘就弹起来了，整页被顶上 **323px**（实测 1515 → 1192）。
    # 所以**必须在这个新布局里重新量一次**标签框 —— 而且是要用它**自己带的** text，
    # 不能"量出坐标、再拿坐标去另一次 dump 里找"（那正是这次的坑）。
    upfield, _ = tag_row(hide=False)
    cur = upfield["text"]
    if cur:
        for _ in range(len(cur) + 2):
            W.adb("shell", "input", "keyevent", "67")
        time.sleep(0.3)
    W.adb("shell", "input", "text", word)
    draft, _, stray = read_screen(prefix)   # 收键盘 + 一次 dump 出全部读数
    return draft, stray


def click_add(bx, by, fx, fy, tries=4):
    """点加号，直到**这一次点击确实被应用收到了**为止。返回 (草稿, 解释)。

    怎么算收到了：草稿被清空（加成功），或者屏幕上出现了满额那句解释（被拦住）。

    两者都没有 = **点击落空**。落空的读数与"加不进去"一模一样（草稿都留着），
    所以必须重试，绝不能把它写成结论。
    2026-09-18 实测：同一个坐标连点，四次里就有一次收不到 ——
    第一次读到的"第 14 个加不进去"，其实只有 13 个标签、屏幕上一句解释都没有。

    `fx` 只用来保持签名可读，判定只看 `fy`（标签框与加号在同一行）。
    """
    for k in range(tries):
        W.tap(bx, by)
        left, ns, _ = read_screen()
        if left == "" or ns:
            return left, ns
        print(f"    第 {k + 1} 次点加号没有被收到（草稿还留着 {left!r}），重新量坐标再点")
        field, btn = tag_row()  # 同样不收键盘（理由见 type_into）
        bx, by = btn["cx"], btn["cy"]
        fy = field["cy"]        # 行可能整体挪了，y 也要跟着更新
    return read_screen()[:2]


def cmd_fill(target):
    """把标签一直加到"加不进去"为止（上限 [target] 个）。

    到达的判据是**"加不进去 + 屏幕上有满额那句解释"**，不是自己数屏上的 chip ——
    32 个 chip 有 8 行，底部那两行本来就在屏幕外，数出来一定偏小。
    """
    go_tags()

    # 开工前先清掉上一次可能留下的残稿（理由见 type_into）。
    field, _ = tag_row()
    stale = field["text"]          # 同一次 dump 里取出来的，不存在坐标过期
    if stale:
        W.tap(field["cx"], field["cy"])
        for _ in range(len(stale) + 2):
            W.adb("shell", "input", "keyevent", "67")
        time.sleep(0.4)
        hide_ime_once()      # 收掉键盘，否则后面的坐标全是在"键盘顶上去"那一态量的
        print(f"  清掉上一次残留的草稿 {stale!r}")

    print(f"  开工时屏上可见 chip {len(chips())} 个（仅供参考，可能不全）")
    print("  开工时屏上的输入框（正文框里若有东西，本轮读数全部作废）：")
    for n in all_edittexts():
        print(f"    y={n['cy']:<5} text={n['text']!r}")

    # 词必须**绝不会撞上已有标签**。撞上了会走"重复"那条分支，
    # 而"重复"的读数（草稿被清空 + 给出一句话）与"满了"几乎一样，
    # 会被读成"已经加满"，于是整组对照挂在一个假前提上。
    prefix = "u" + time.strftime("%H%M%S")
    added, misses = 0, 0
    while added < target:
        field, btn = tag_row()
        fx, fy, bx, by = field["cx"], field["cy"], btn["cx"], btn["cy"]
        word = f"{prefix}{added:02d}"

        left, stray = type_into(fx, fy, word, prefix)
        if left != word:
            raise RuntimeError(
                f"词 {word!r} 没打进输入框（框里是 {left!r}）——"
                f"别把'没打进去'当成'已经加进去了'")

        # 独立旁证：这一轮造的词有没有跑进别的输入框。跑进去了就说明点击落到了别处，
        # 本轮所有读数全部作废 —— 2026-09-18 就是这样把 `added` 数到 32 的。
        if stray:
            raise RuntimeError(
                f"造的词跑进了别的输入框：{stray} ——"
                f"说明有点击落空/坐标漂移，本轮读数作废，不能拿来写结论")

        # **打完字之后必须重新量加号的位置，再点。**
        # 这一步是本轮最要命的一处：`bx, by` 是**打字之前**那个布局态里量的，
        # 而打完字、chips 多占一行之后，这一整行会往上挪 ~90px ——
        # 于是那个坐标落到了**标签 chip 的 × 上**，一次点击就删掉一个标签。
        # 表现极具迷惑性：草稿还留着（因为没删到输入框），于是被判成"点击没被收到"，
        # 重试一次、成功加上 —— 一次迭代"删一个、加一个"，净变化为零。
        # 造数于是把 `added` 数到 32，而记录里只有 5 个标签
        # （`u14503200` / `01` / `30` / `31` / `zz`，2026-09-18 在主页卡片上核到）。
        # **量具一边数、一边把被数的东西删掉**，比读错数更坏。
        field, btn = tag_row()
        bx, by, fx, fy = btn["cx"], btn["cy"], field["cx"], field["cy"]

        left, ns = click_add(bx, by, fx, fy)
        if left == "":
            added += 1
            misses = 0
            if added % 8 == 0:
                print(f"  已加 {added} 个")
            continue

        if ns:
            if any("已经在标签里了" in x for x in ns):
                raise RuntimeError(
                    f"词 {word!r} 撞上了已有标签（{ns}）——"
                    f"「重复」同样会清空草稿并给一句话，与「满了」在读数上很像，"
                    f"不能把它当成'已经加满'。换一个不会重复的前缀再来")
            print(f"  加到第 {added + 1} 个时被拦住了。")
            print(f"  屏上的解释：{ns}")
            print(f"  本次成功加了 {added} 个标签（这就是「满」的证据）")
            shot(f"标签-加到被拦住-{SIDE}")
            report_count(prefix, added)
            return

        misses += 1
        print(f"    第 {added + 1} 个连点都没被收到（草稿 {left!r}、屏上无解释）")
        if misses >= 12:
            raise RuntimeError("连续 12 次点击都收不到 —— 停下来看清楚，别硬跑")
        continue

    print(f"  已经加了 {added} 个（到达设定的上限 {target}），再试一个确认能拦住")
    field, btn = tag_row()
    # 这个词同样必须是这一轮**没出现过**的：写死一个 "zzz" 的话，
    # 万一上一轮已经把它加成了标签，这一轮读到的"加不进去"就是「重复」而不是「满额」——
    # 2026-09-18 实测屏幕上真的留着一个 `#zzz`，这条差点又成立。
    probe = f"{prefix}zz"
    left, _ = type_into(field["cx"], field["cy"], probe)
    if left != probe:
        raise RuntimeError(f"词 {probe!r} 没打进输入框（框里是 {left!r}）—— 读数无效")
    field, btn = tag_row()      # 同上：打完字重新量，别拿打字前的坐标去点
    left, ns = click_add(btn["cx"], btn["cy"], field["cx"], field["cy"])
    print(f"  第 {target + 1} 个（{probe}）：草稿 {left!r}，屏上解释 {ns}")
    if any("已经在标签里了" in x for x in ns):
        raise RuntimeError(f"{probe!r} 被判成了重复 —— 说明前面并没有真的加满，结论无效")
    shot(f"标签-已加满{target}个-{SIDE}")
    report_count(prefix, added)


def cmd_try(word, what):
    """填一个词、点加号，把三条读数打出来并截图。"""
    field, btn = go_tags()
    fx, fy, bx, by = field["cx"], field["cy"], btn["cx"], btn["cy"]
    before = chips()
    print(f"  加之前屏上 chip：{len(before)} 个")

    typed, stray = type_into(fx, fy, word)
    print(f"  点加号之前，输入框里是 {typed!r}")
    if typed != word:
        raise RuntimeError(f"词没打进输入框（框里是 {typed!r}）—— 读数无效，别往下写结论")
    if stray:
        raise RuntimeError(f"词跑进了别的输入框：{stray} —— 读数作废，别写结论")

    # 同上：打完字布局已经变了，加号必须重新量 —— 否则这一点击会落到 chip 的 × 上
    field, btn = tag_row()
    left, ns = click_add(btn["cx"], btn["cy"], field["cx"], field["cy"])
    after = chips()

    print("  点完加号之后：")
    print(f"    输入框里剩：{left!r}")
    print(f"    屏上的解释：{ns}")
    print(f"    chip 数：{len(before)} → {len(after)}")
    print("    （改前的期望：输入框 ''，解释 []）")
    print("    （改后的期望：输入框还是那个词，解释里有一句）")
    shot(f"标签-{what}-{SIDE}")


def main():
    global SIDE
    cmd = sys.argv[1] if len(sys.argv) > 1 else "show"
    # 「侧」的位置有两个：`fill <个数> <侧>` 在 argv[3]，`<子命令> <侧>` 在 argv[2]。
    # 原先只写了一句 `if len(sys.argv) > 3: SIDE = sys.argv[3]` —— 于是
    # `tagfull 改后` 读到的侧名是空的、截图被命名成"未标注"，
    # 而按本文件自己的约定"未标注"就等于"这张图不能用于对照" —— 又白跑一趟。
    # （2026-09-18 实测：改后侧那两张关键截图就是这么变成"未标注"的。）
    if len(sys.argv) > 3:
        SIDE = sys.argv[3]
    elif len(sys.argv) > 2 and cmd in ("tagfull", "tagdup"):
        SIDE = sys.argv[2]

    if cmd == "show":
        print(f"  chip：{chips()}")
        print(f"  解释：{notices()}")
        return
    if cmd == "dump":
        # 把含关键词的节点连同它的 class 打出来 —— "这句话挂在哪种节点上"
        # 本身就是判据的一部分（见 notices 的注释）。
        for n in W.nodes():
            t = (n["text"] or "") + (n["desc"] or "")
            mark = "★提示" if any(m in t for m in NOTICE_MARKERS) else "  label"
            if "最多" in t or "已经在" in t or t.startswith("#"):
                print(f"    {mark} cls={n['cls']:<14} y={n['cy']:<5} text={n['text']!r}")
        return
    if cmd == "enter1":
        # 诊断：点击到底有没有把焦点送进标签输入框。
        go_tags()
        field, btn = tag_row()
        fx, fy = field["cx"], field["cy"]
        print(f"  量到标签输入框 ({fx},{fy})；加号在 ({btn['cx']},{btn['cy']})")
        for tag, ns in (("点击前", W.nodes()),):
            for n in [x for x in ns if x["cls"] == "EditText"]:
                print(f"    {tag} y={n['cy']:<5} focused={n['focused']} text={n['text']!r}")
        W.tap(fx, fy)
        for n in [x for x in W.nodes() if x["cls"] == "EditText"]:
            print(f"    点击后 y={n['cy']:<5} focused={n['focused']} text={n['text']!r}")
        W.adb("shell", "input", "text", "zz1")
        for n in [x for x in W.nodes() if x["cls"] == "EditText"]:
            print(f"    送字后 y={n['cy']:<5} focused={n['focused']} text={n['text']!r}")
        return
    if cmd == "unlock":
        # 必须用走查那个带落字校验的 fill_field：手写"点一下 + input text"
        # 会静默失败（框没聚焦，字打在空气里，界面回「密码不正确」）。
        W.wait_text("主密码", timeout=90, what="解锁页")
        W.fill_field(0, W.MASTER)
        W.tap_text("解锁")
        W.wait_text("本机加密", timeout=90, what="解锁后的主页")
        print("  已解锁")
        return
    if cmd == "open1":
        # 打开主页最上面那张卡片（就是刚存进去的那条），回到编辑器。
        # 有了它，`verify` 保存之后还能再进同一条记录把读数重跑一遍 ——
        # 造 32 个标签要十分钟，不该为了补一张截图就重造一次。
        W.wait_text("本机加密", timeout=30, what="主页")
        ns = W.nodes()
        # 别用"可点 + y 在某段里"来挑卡片：主页顶部的**筛选维度那一行**
        # （「全部」/「标签 条数」）也在同一段 y 里、也是可点的，
        # 按 y 最小去挑会挑中筛选 chip —— 2026-09-18 实测就把列表过滤了一遍，
        # 之后找卡片当然找不到。用卡片自己的标题当锚点。
        titles = [n for n in ns if (n["text"] or "") == "文字记录"]
        assert titles, "主页上找不到记录卡片（一个『文字记录』标题都没有）"
        c = min(titles, key=lambda n: n["y1"])
        W.tap(c["cx"], c["cy"])
        time.sleep(1.6)
        ns = W.nodes()
        ed = next((n for n in ns if (n["desc"] or n["text"]) == "编辑"), None)
        if ed is not None:
            W.tap(ed["cx"], ed["cy"])
            time.sleep(1.4)
        W.scroll_top()
        time.sleep(0.4)
        ns = W.nodes()
        if not any("加标签" in ((n["text"] or "") + (n["desc"] or "")) for n in ns):
            raise RuntimeError("没进到编辑器（屏上没有『加标签』）—— 看一眼屏幕上是什么")
        print("  已回到这条记录的编辑器")
        return
    if cmd == "reset":
        # 回主页。必须**循环按 BACK 直到真的在主页**：
        # 第一次 BACK 往往只是收键盘，第二次才是"离开编辑页"，
        # 中间还可能夹一个"丢弃"确认框。只按一次 BACK 就当成回到主页，
        # 2026-09-18 实测把后续步骤全带偏了（停在记录详情页上继续跑造数）。
        for k in range(6):
            ns = W.nodes()
            home = [n for n in ns if "本机加密" in ((n["text"] or "") + (n["desc"] or ""))]
            if home:
                print(f"  已在主页（第 {k} 次 BACK 之后）")
                break
            dlg = next((n for n in ns
                        for label in ("丢弃", "放弃", "不保存")
                        if label in ((n["text"] or "") + (n["desc"] or ""))), None)
            if dlg is not None:
                W.tap(dlg["cx"], dlg["cy"])
                print(f"  确认了丢弃（{dlg['text'] or dlg['desc']}）")
            else:
                W.adb("shell", "input", "keyevent", "4")
            time.sleep(1.2)
        else:
            raise RuntimeError("按了 6 次 BACK 还是没回到主页 —— 先看清楚屏幕上是什么")
        ns = W.nodes()
        cnt = next((n["text"] for n in ns if "本机加密" in (n["text"] or "")), "")
        print(f"  主页状态：{cnt!r}")
        return
    if cmd == "verify":
        # 保存这条记录 → 回主页读卡片上的标签。
        # **这是唯一一条与编辑页渲染无关的数量通道**，所以"到底加进去几个"以它为准。
        # 编辑页那边只能看到一个下界（见 report_count 的说明）。
        # 已经在主页就直接读（重复跑 verify 时会出现这种情形）
        ns0 = W.nodes()
        if any("本机加密" in ((n["text"] or "") + (n["desc"] or "")) for n in ns0):
            print("  已经在主页，直接读")
        else:
            W.tap_text("保存")
            time.sleep(2.0)
        ns = W.nodes()
        hashes = [n for n in ns if (n["text"] or "").startswith("#")]
        if not hashes:
            raise RuntimeError("主页上没读到任何 # 开头的标签 —— 记录没保存成功？")
        # 节点按 y 排序，最上面那张卡片就是刚存进去的那条。
        card = min(hashes, key=lambda n: n["y1"])
        tags = [x for x in (card["text"] or "").split() if x.startswith("#")]
        uniq = sorted(set(tags))
        print(f"  主页卡片上的标签行：{card['text']!r}")
        print(f"  → 去重后 **{len(uniq)} 个**：{uniq}")
        # 第二条通道：主页顶部的"筛选维度"，每个标签一个 chip（文字形如「名字 条数」）
        filters = [re.match(r"^(\S+)\s+\d+$", n["text"] or "").group(1)
                   for n in ns if re.match(r"^(\S+)\s+\d+$", n["text"] or "")]
        print(f"  主页筛选维度上的标签（{len(filters)} 个）：{sorted(set(filters))}")
        if len(hashes) > 1:
            print(f"  注意：主页上还有 {len(hashes) - 1} 条别的带标签卡片，"
                  f"本次只读了最上面那条（刚存的）")
        return
    if cmd == "newtext":
        # 主页 → 右下角浮标 → 选「文字」。
        # 先确认真的在主页：不在的话**别碰运气找浮标** —— 上一轮就是这么在
        # 记录详情页上乱点，把整条流程带偏的。先跑 `reset`。
        ns = W.nodes()
        if not any("本机加密" in ((n["text"] or "") + (n["desc"] or "")) for n in ns):
            raise RuntimeError("不在主页 —— 先跑 `reset` 回到主页，别在别的页面上找新建按钮")
        fab = next((n for n in ns
                    if n["clickable"] and not (n["text"] or n["desc"]) and n["cy"] > 1900), None)
        assert fab is not None, "主页右下角找不到新建浮标"
        W.tap(fab["cx"], fab["cy"])
        W.wait_text("文字", timeout=20, what="新建类型选择")
        W.tap_text("文字")
        W.wait_text("标题", timeout=20, what="文字编辑器")
        print("  已进入文字编辑器")
        return
    if cmd == "fill":
        target = int(sys.argv[2]) if len(sys.argv) > 2 else MAX_TAGS
        if len(sys.argv) > 3:
            SIDE = sys.argv[3]
        cmd_fill(target)
        return
    if cmd == "tagfull":
        cmd_try("zzz", "满额再加")
        return
    if cmd == "tagdup":
        # 词必须**从屏幕上真实的 chip 里挑**，不能写死。
        # 写死一个 "t00" 只有在"上一轮恰好加过 t00"时才真的是重复；
        # 否则它会走"正常添加"那条分支（草稿清空 + 不说话），
        # 而那个读数与"已经加满"极像，看起来还挺像成功 —— 又一次假绿。
        ex = chips()
        if not ex:
            raise SystemExit("屏幕上没有可见 chip，挑不出一个已存在的标签来试重复")
        word = ex[0].lstrip("#")
        print(f"  从屏上已有的 chip 里挑一个来试重复：{word!r}")
        cmd_try(word, "加重复的")
        return
    raise SystemExit(f"不认识的子命令：{cmd}")


if __name__ == "__main__":
    main()
