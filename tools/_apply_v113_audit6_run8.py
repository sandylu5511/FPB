# -*- coding: utf-8 -*-
"""把 run8（1.1.3 的验收）补进 audit6 报告的 §3.4。

这一节原本只写 run7（v1.1.2）。第六节已经写了"release 验收见 §3.4"，
所以这里必须真的落到 §3.4，不能让读者自己去找。
"""
import io

P = r"D:\MixiaVault\dist\evidence\audit6\README.md"

RUN8_ROW = ("| **run8** | **49 / 0 / 5** | —— | 升 1.1.3 之后重跑，**计数一致**"
            "（脚本自报「v1.1.3 正式包验收全部通过」） |")

RUN8_NOTE = """\
**升到 1.1.3 之后又跑了一轮（run8，经过见第六节）**：包换成 `9527b0b8…`
（`versionCode=14 / versionName=1.1.3`），结果同样是 **49 / 0 / 5**，
脚本自报「共 15 条判据，未通过 0 条」＋「v1.1.3 正式包验收全部通过」。
版本判据那一条也随之变成 `1.1.3 / code 14` —— 这条是**脚本自己校验包身份**得出的，
不是人肉比对（脚本里版本号只有 `VERSION_CODE` / `VERSION_NAME` 两个常量，
改完由它去问设备）。
日志 `acceptance-console-run8.log`，现场截图 `dist/evidence/v113-release/`。"""


def load(p):
    return io.open(p, encoding="utf-8", newline="").read()


def save(p, s):
    io.open(p, "w", encoding="utf-8", newline="").write(s)


s = load(P)
eol = "\r\n" if "\r\n" in s else "\n"
lines = s.split(eol)

# ---- 1. 标题 ----
i = next(i for i, l in enumerate(lines) if l.startswith("### 3.4 release 包验收"))
lines[i] = "### 3.4 release 包验收（run7 / run8，两轮计数一致）：**49 通过 / 0 未通过 / 5 不适用**"
print(f"  标题        L{i + 1}")

# ---- 2. 表格补 run8 行 + 给 run7 行标注版本 ----
k = next(i for i, l in enumerate(lines) if l.startswith("| **run7** |"))
lines[k] = lines[k].replace("**正式结论**", "**正式结论（v1.1.2）**")
assert lines[k].endswith("|"), lines[k]
lines.insert(k + 1, RUN8_ROW)
print(f"  表格 run8   L{k + 2}")

# ---- 3. 计数那段后面补 run8 说明 ----
m = next(i for i, l in enumerate(lines) if l.startswith("读不到沙箱里的附件密文"))
lines[m + 1:m + 1] = [""] + RUN8_NOTE.split("\n")
print(f"  run8 说明   插在 L{m + 1} 之后")

save(P, eol.join(lines))
print("\n完成。")
