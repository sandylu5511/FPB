"""探针：六处「超限会提示」在不在。

**不是走查的一部分**，只回答一件事：在输入框里塞进超过上限的文字之后，
界面上有没有出现"…已超过 N 字上限，保存时只保留前 N 字"这句话。

用法：

    python tools/_probe_hints.py                 # 只看这一屏有没有那句话
    python tools/_probe_hints.py title 201       # 打开编辑页，往标题里塞 201 个字符
    python tools/_probe_hints.py tag 33 改前     # 最后那个参数是**这一侧的名字**

为什么要有这个探针：`overLimitHint` 是个私有 composable，单测够不到它；
能证明"提示真的出现在屏幕上"的只有真机。所以改前跑一次（应当找不到）、
改后再跑一次（应当找得到）—— 这才是一组对照，而不是"我改了，所以它出现"。

## 最后一那个参数为什么必须有

它是截图文件名的一部分。**两次运行写同一个文件名就等于没有改前那一侧** ——
2026-09-18 第一次跑 A/B 就是这么丢的：改前的 `01-提示-标签超限.png`
被改后那次整个覆盖掉了，事后只剩改后一张。
`dist/evidence/audit5/README.md` 第二节第 12 条记的是同一个病
（窗口转储固定叫 `window-dump-1.txt`，一次验收里被问好几遍，事后分不清是谁留下的）。
证据链上"两个东西共用一个名字"，与"判据写错"一样能让人得出错误结论。
"""
import importlib.util
import os
import sys
import time

BASE = r"D:\MixiaVault"

# 这一侧的名字（改前 / 改后 / …），进截图文件名。缺省用 `未标注`，
# 就是为了在日志里显眼：看到它就说明这一次的截图**不能**用来做对照。
SIDE = "未标注"


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


W = load(os.path.join(BASE, "tools", "walkthrough.py"), "base")
W.OUT = os.path.join(BASE, "dist", "evidence", "hints-probe")


def hints():
    """当前界面里所有带"超过"的文本 —— 也就是超限提示。"""
    out = []
    for n in W.nodes():
        t = (n["text"] or "") + (n["desc"] or "")
        if "超过" in t:
            out.append(t.strip())
    return out


def open_text_editor():
    """主页 → 右下角浮标 → 选「文字」→ 文字编辑器。"""
    fab = next((n for n in W.nodes() if n["clickable"] and not (n["text"] or n["desc"])
                and n["cy"] > 1900), None)
    assert fab is not None, "主页右下角找不到新建浮标"
    W.tap(fab["cx"], fab["cy"])
    W.wait_text("文字", timeout=20, what="新建类型选择")
    W.tap_text("文字")
    W.wait_text("标题", timeout=20, what="文字编辑器")
    return [n for n in W.nodes() if n["cls"] == "EditText"]


def _fields():
    W.hide_ime()
    return [n for n in W.nodes() if n["cls"] == "EditText"]


def _target_len(fields, x, y):
    """这次填的是哪个框、它现在有几个字。

    基础模块的节点只有 `cx/cy`（没有 x1..y2），所以按"离落点最近 + 内容最长"认。
    顺便查一遍**别的框里有没有也进了字** —— 字要是落到了备注框，
    读数同样是"没有提示"，那就成了另一次假红。
    """
    if not fields:
        return None, 0
    near = sorted(fields, key=lambda n: abs(n["cx"] - x) + abs(n["cy"] - y))
    hit = near[0]
    others = [n for n in fields if n is not hit and (n["text"] or "")]
    assert not others, (f"字落进了别的输入框：{[(n['cx'], n['cy'], len(n['text'])) for n in others]}"
                        f" —— 读数会变成'没有提示'，那是假红")
    return hit, len(hit["text"] or "")


def fill_long(index, ch, count, cap=14):
    """往第 [index] 个输入框塞 [count] 个 [ch]，**分段推进、逐段核对**。

    ## 为什么不能一次性塞进去（这一条是实测撞出来的）

    2026-09-18 用 `input text` 一次送 201 个 `a`，**只有 87 个落进了框里**。
    而"没落够"与"没有提示"在读数上**完全一样**（都是空列表 `[]`）——
    于是量具的问题会被记成应用的问题，更糟的是它恰好落在"改前"那一侧的
    期望值上，于是"改后仍旧没有提示"看起来像回归没修好。

    所以这里每段只送 40 个，送完立刻 dump 数一遍框里的字符数，
    涨不动就**抛异常**（不是记一句"没有提示"）。
    """
    W.hide_ime()
    es = [n for n in W.nodes() if n["cls"] == "EditText"]
    assert len(es) > index, f"编辑器里只有 {len(es)} 个输入框，取不到第 {index} 个"
    x, y = es[index]["cx"], es[index]["cy"]
    W.tap(x, y)
    W.clear_focused_field()
    W.hide_ime()

    cur = 0
    for i in range(cap):
        _, cur = _target_len(_fields(), x, y)
        if cur >= count:
            print(f"  第 {index} 个输入框已落入 {cur} 个字符（目标 {count}）")
            return cur
        W.type_ascii(ch * min(count - cur, 40))
        W.hide_ime()
        time.sleep(0.4)
        _, nxt = _target_len(_fields(), x, y)
        print(f"  第 {i + 1} 段：{cur} → {nxt}")
        if nxt <= cur:
            raise RuntimeError(
                f"第 {index} 个输入框卡在 {cur} 个字符（目标 {count}），再塞也不涨 ——"
                f"**不能**把这一条读成'没有提示'：没落够与没有提示的读数一模一样")
    raise RuntimeError(f"{cap} 段之后仍然只有 {cur} 个字符（目标 {count}）")


def scroll_to(sub, tries=8):
    """往下滚，直到屏幕上出现含 [sub] 的节点；返回那批节点，滚不到返回 None。

    标签输入框在编辑页下方，一屏放不下 —— 不滚就取不到它的坐标，
    而"取不到"与"没有提示"在这里的读数又是一样的。
    """
    for i in range(tries):
        ns = W.nodes()
        if any(sub in ((n["text"] or "") + (n["desc"] or "")) for n in ns):
            return ns
        W.adb("shell", "input", "swipe", "540", "1500", "540", "700", "250")
        time.sleep(0.9)
    return None


def field_near(ns, label_sub):
    """标签附近那个输入框。标签文字可能是独立节点、也可能就画在框上，两种都认。"""
    hits = [n for n in ns if label_sub in ((n["text"] or "") + (n["desc"] or ""))]
    assert hits, f"屏幕上没有含 {label_sub!r} 的节点"
    lab = hits[0]
    if lab["cls"] == "EditText":
        return lab
    cands = [n for n in ns if n["cls"] == "EditText"]
    assert cands, "标签旁边找不到输入框"
    return min(cands, key=lambda n: abs(n["cy"] - lab["cy"]))


def fill_field_obj(f, ch, count, cap=6):
    """往**指定节点**那个输入框里分段塞字，逐段核对（理由见 [fill_long]）。"""
    x, y = f["cx"], f["cy"]
    W.tap(x, y)
    W.clear_focused_field()
    W.hide_ime()
    cur = 0
    for i in range(cap):
        _, cur = _target_len(_fields(), x, y)
        if cur >= count:
            print(f"  标签框已落入 {cur} 个字符（目标 {count}）")
            return cur
        W.type_ascii(ch * min(count - cur, 40))
        W.hide_ime()
        time.sleep(0.4)
        _, nxt = _target_len(_fields(), x, y)
        print(f"  第 {i + 1} 段：{cur} → {nxt}")
        if nxt <= cur:
            raise RuntimeError(f"标签框卡在 {cur} 个字符（目标 {count}）—— 不能读成'没有提示'")
    raise RuntimeError(f"{cap} 段之后仍然只有 {cur} 个字符（目标 {count}）")


def main():
    global SIDE
    what = sys.argv[1] if len(sys.argv) > 1 else "show"
    # 最后那个参数是"这一侧的名字"，进截图文件名 —— 两次运行不能共用一个名字。
    if len(sys.argv) > 3:
        SIDE = sys.argv[3]
    if what == "show":
        print(f"屏幕上的超限提示：{hints()}")
        return
    if what == "title":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 201
        open_text_editor()
        print(f"往标题里塞 {n} 个字符")
        fill_long(0, "a", n)
        hs = hints()
        print(f"屏幕上的超限提示：{hs}")
        W.shot(f"提示-标题超限-{SIDE}")
        if not hs:
            print("  ⚠ 没有读到提示 —— 文字已经确认超过上限，所以这一条是**真的没有提示**")
        return
    if what == "tag":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 33
        open_text_editor()
        ns = scroll_to("加标签")
        assert ns is not None, "滚不到标签输入框"
        f = field_near(ns, "加标签")
        print(f"往标签框（上限 32 字符）里塞 {n} 个字符")
        fill_field_obj(f, "a", n)
        hs = hints()
        print(f"屏幕上的超限提示：{hs}")
        W.shot(f"提示-标签超限-{SIDE}")
        if not hs:
            print("  ⚠ 没有读到提示 —— 文字已经确认超过上限，所以这一条是**真的没有提示**")
        return
    if what == "editor":
        open_text_editor()
        print("编辑页已打开")
        return
    print(f"未知命令 {what}")


if __name__ == "__main__":
    main()
