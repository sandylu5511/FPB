#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第三轮补丁：把"把控制条叫回来"这件事收成一处理，并不再把"读不到按钮"当缺陷。

## 为什么

B 轮跑完 25 PASS 之后，**在最后一步把整轮打断了**：

    AssertionError: 切回来后既不在播、也找不到播放键

而那一刻的状态是**正常**的：Surface 重建后应用按 `wantsPlay` 自动续播，
于是「画面正中那个播放键」不出现（它只在暂停态显示），而控制条又已经
在 3.5 秒后收起来了 —— a11y 树里一个按钮都没有。这不是缺陷，
是"播放中且控制条收起"本来长的样子。

两处教训各修一条：

1. **"叫回控制条"的姿势只能有一份实现。** `tap_pause` 里那套"每轮只 dump 一次、
   读到按钮就收工、落点避开正中"的逻辑，这里又需要一遍 —— 抄一遍迟早两边不一致。
   提成 `reveal_controls()`。
2. **读不到按钮时不该断言，该换一条判据。** "读取器还能不能用"这个问题，
   最终由"画面是否真的在动"回答（连拍帧之间白块位移），而不是由"某个按钮在不在"。
   按钮读不到只值得记一行日志。
"""

import io
import sys

TARGET = r"D:\MixiaVault\tools\walkthrough-v110-video.py"

src = io.open(TARGET, encoding="utf-8").read()
orig = src
report = []


def replace_once(old, new, tag):
    global src
    n = src.count(old)
    if n != 1:
        sys.exit(f"[{tag}] 期望命中 1 处，实测 {n} 处 —— 未写盘")
    src = src.replace(old, new, 1)
    report.append(f"OK  {tag}")


def replace_between(start_marker, end_marker, new, tag):
    global src
    if src.count(start_marker) != 1:
        sys.exit(f"[{tag}] 起点标记命中 {src.count(start_marker)} 处 —— 未写盘")
    if src.count(end_marker) != 1:
        sys.exit(f"[{tag}] 终点标记命中 {src.count(end_marker)} 处 —— 未写盘")
    a = src.index(start_marker)
    b = src.index(end_marker)
    if b <= a:
        sys.exit(f"[{tag}] 终点在起点之前 —— 未写盘")
    src = src[:a] + new + src[b:]
    report.append(f"OK  {tag}（替换 {b - a} 字符）")


# ---------- 1. 新增 reveal_controls，并让 tap_pause 复用它 ----------

replace_between(
    "def tap_pause(rounds=6):\n",
    "def current_page():\n",
    '''def reveal_controls(rounds=6):
    """轻点画面把控制条叫回来，返回读到「暂停」或「播放」时的节点树。

    ## 为什么要单独一个函数

    播放中控制条 3.5 秒后自己收起，而轻点画面是 `controls = !controls` ——
    它是个 **toggle**。这两条叠起来意味着"读控制条"必须按同一个姿势做：
    每轮只 dump 一次，读到按钮就立刻收工；都没读到才轻点一下。

    抄两遍迟早会不一致（`tap_pause` 一遍、切后台回来一遍），所以收在这里。

    落点取 `VIEW_H // 4`，**避开画面正中那个播放键**：暂停态下它就在正中，
    点在它身上会直接把影片又播起来（那就成了"想看一眼控制条反而开始播"）。

    多轮仍读不到就原样返回，不抛异常 —— 调用方自己决定怎么处理。
    读不到按钮**不一定**是缺陷：「正在播 + 控制条收起」本来就是这个样子。
    上一版正因为把"读不到"当成了"坏了"，在 B 轮最后一步把整轮打断。
    """
    ns = []
    for i in range(rounds):
        ns = rnodes()
        if any(n["desc"] in ("暂停", "播放") for n in ns):
            return ns
        W.log(f"    第 {i + 1} 轮没读到控制条，轻点画面把它叫回来")
        W.adb("shell", "input", "tap", str(VIEW_W // 2), str(VIEW_H // 4))
        time.sleep(0.6)
    W.log("    ⚠ 多轮都没能读到控制条")
    return ns


def tap_pause(rounds=6):
    """把影片点成**暂停**，返回 (暂停后的节点树, 这次是否真的点了「暂停」)。

    三条实测约束叠在一起，**播放态下读控制条只能靠运气**：

      · 轻点画面是 `controls = !controls` —— 它是个 **toggle**，不是"显示"。
        控制条已经在的时候再点一下，是把它**收起来**；
      · 控制条在**播放中 3.5 秒**后自动隐藏（暂停时不隐藏）；
      · 一次 `rnodes()` 要 1~3 秒。

    上一版写的是"先点一下把控制条叫出来，再从容地找按钮"，日志实证它不行：
    出现过"控制条一个节点都没有"（`times = []`、`slider = None`），
    几秒后又拿到了 `desc=['暂停']`。所以改成**先把影片停下**：
    暂停之后控制条常驻，时间 / 总时长 / 进度条 / 拖动才都是可重复的读数。

    返回的第二项是"这次真的执行了暂停动作"。下面那条"点暂停之前确实处于播放态"
    的判据直接用它 —— 否则影片若已播完，按钮本来就是「播放」，
    "点完变成播放"无论点不点都成立，断言会**自己满足自己**。
    """
    ns = reveal_controls(rounds)
    if any(n["desc"] == "暂停" for n in ns):
        hit = next(n for n in ns if n["desc"] == "暂停")
        W.log(f"    读到「暂停」，点它（{hit['cx']},{hit['cy']}）")
        W.tap(hit["cx"], hit["cy"])
        time.sleep(0.8)
        return rnodes(), True
    W.log("    读到「播放」—— 影片已经是停下状态")
    return ns, False


''',
    "新增 reveal_controls + tap_pause 复用",
)

# ---------- 2. 切后台回来：不再断言"必须有播放键" ----------

replace_once(
    "    ns = rnodes()\n"
    '    if any(n["desc"] == "暂停" for n in ns):\n'
    '        note("切回来时的状态", "已经在播（Surface 重建后按用户意图自动续播）")\n'
    "    else:\n"
    '        glyph = (next((n for n in ns if n["desc"] == "播放" and n["cy"] < 2100), None)\n'
    '                 or next((n for n in ns if n["desc"] == "播放"), None))\n'
    '        assert glyph is not None, "切回来后既不在播、也找不到播放键"\n'
    '        W.log(f"    切回来是暂停态，点播放键（{glyph[\'cx\']},{glyph[\'cy\']}）重新播一遍")\n'
    '        W.tap(glyph["cx"], glyph["cy"])\n'
    "        time.sleep(0.6)\n",
    "    # **不能断言一定读得到按钮。** Surface 重建后若按 `wantsPlay` 自动续播了，\n"
    "    # 那么此刻既没有画面正中的播放键（那个只在暂停态出现），控制条也会在\n"
    "    # 3.5 秒后自己收起 —— a11y 里一个按钮都没有，而这是**正常**的。\n"
    "    # 上一版在这里断言\"必须有播放键\"，于是 25 项 PASS 之后整轮被打断。\n"
    "    # 「读取器还能不能用」最终由下面那条连拍判据回答，不靠某个按钮在不在。\n"
    "    ns = reveal_controls()\n"
    '    if any(n["desc"] == "暂停" for n in ns):\n'
    '        note("切回来时的状态", "已经在播（Surface 重建后按用户意图自动续播）")\n'
    "    else:\n"
    '        glyph = (next((n for n in ns if n["desc"] == "播放" and n["cy"] < 2100), None)\n'
    '                 or next((n for n in ns if n["desc"] == "播放"), None))\n'
    "        if glyph is None:\n"
    '            W.log("    ⚠ 控制条上读不到播放键（多半已自动续播且控制条收起）—— "\n'
    '                  "这一步只验画面在不在动")\n'
    "        else:\n"
    '            W.log(f"    切回来是暂停态，点播放键（{glyph[\'cx\']},{glyph[\'cy\']}）重新播一遍")\n'
    '            W.tap(glyph["cx"], glyph["cy"])\n'
    "            time.sleep(0.6)\n",
    "切后台回来/不再断言播放键存在",
)

io.open(TARGET, "w", encoding="utf-8", newline="\n").write(src)
print("\n".join(report))
print(f"\n写入 {TARGET}：{len(orig)} → {len(src)} 字符")
