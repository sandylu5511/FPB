# -*- coding: utf-8 -*-
"""audit5 报告里三处**状态残留**：摘要表、结论段、分类那句。

上一轮只改了第 14 条的正文（§620 起），漏了"报告开头这几处对它的描述"，
于是同一份文档里同时存在"已修"和"留给下一版"两种说法。
"""
import io

BASE = r"D:\MixiaVault"
P = BASE + r"\dist\evidence\audit5\README.md"

NEW_BLOCK = """\
第 14 条是**报告收口时才发现的**（写第六节核数字时顺手数了一遍上限常量才看到）。
**这一轮只报不改**（当轮决定：留给下一版），所以它没有影响当时那 321 项单测
与 release 验收的结论 —— 但也意味着，当时那句
"现在没有任何一处静默丢用户输入的文字"**说得过头了**。
（**2026-09-18 的第九轮已按第 14 条末尾那三处改掉**：满额时留住草稿 + 给一句话，
并把待办的两个入口合并成一个。改法、12 条回归测试、回退对照与真机 A/B 见
`audit6/README.md`；发布说明相应换成 §5.4 + §3.5。）"""


def load(p):
    return io.open(p, encoding="utf-8", newline="").read()


def save(p, s):
    io.open(p, "w", encoding="utf-8", newline="").write(s)


s = load(P)
assert "\r\n" not in s, "这份是 LF，别用本脚本改"
lines = s.split("\n")

# ---- 1. 摘要表那一格 ----
old = "**只出方案 —— 已定：留给下一版**"
hits = [i for i, l in enumerate(lines) if old in l]
assert len(hits) == 1, f"摘要表格：命中 {len(hits)} 处"
lines[hits[0]] = lines[hits[0]].replace(old, "**已修（第九轮）**")
print(f"  摘要表       L{hits[0] + 1}")

# ---- 2. 结论段（整块换） ----
i = next(i for i, l in enumerate(lines) if l.startswith("第 14 条是**报告收口时才发现的**"))
j = next(i for i, l in enumerate(lines) if l.strip() == "准确的说法见第 14 条。")
assert i < j, "结论段的位置不对"
lines[i:j + 1] = NEW_BLOCK.split("\n")
print(f"  结论段       L{i + 1}~L{j + 1}")

# ---- 3. 分类那句 ----
old3 = "第 14 条是应用侧、但本轮只报不改"
hits3 = [i for i, l in enumerate(lines) if old3 in l]
assert len(hits3) == 1, f"分类那句：命中 {len(hits3)} 处"
lines[hits3[0]] = lines[hits3[0]].replace(old3, "第 14 条是应用侧：本轮只报了方案、第九轮修掉")
print(f"  分类那句     L{hits3[0] + 1}")

save(P, "\n".join(lines))
print("\n完成。")
