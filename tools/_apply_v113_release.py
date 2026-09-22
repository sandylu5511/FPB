# -*- coding: utf-8 -*-
"""把 v1.1.3 的事实同步进发布说明（文件已从 FPB-v1.1.2-发布说明.md 改名）。

原则：**只改"本包是什么"，不动"历史当时是什么"** ——
run4/run7 那几段引用 `v1.1.2`/`e19cc5c9`/`722818fc` 的文字是历史记录，一字不改。
"""
import io

BASE = r"D:\MixiaVault"
P = BASE + r"\dist\FPB-v1.1.3-发布说明.md"

OLD_SHA = "722818fc709ec32e5ea3994f19e9ed1d42948cc86cd8b64aafca362438e1d7b6"
NEW_SHA = "9527b0b8de5edfb65c36f6c7632ffbc06e6c8e3e121b61e2337de99590468c23"

NEW_HEAD = """\
> 这是**可覆盖升级**的正式包。装到已经装了旧版的设备上，数据不会被清掉（依据见 §4）。
> 前序正式包：v1.0.2 / v1.0.3 / v1.0.4 / v1.0.5 / v1.0.6 / v1.0.7 / v1.0.8 / v1.0.9 / v1.1.0 / v1.1.1。

> **为什么会有 v1.1.3（2026-09-18）**：**v1.1.2 这个版本号，在发布过程中对应过两个不同的包** ——
> 11:57 构建的 `e19cc5c9…`（不含第九轮修复）和 14:07 构建的 `722818fc…`（含修复）。
> 两者的 `versionName` 都是 `1.1.2`，**装到手机上从版本号完全分不出来**。
> 第九轮修掉 §5.4 那条"列表满额时吞掉用户输入"之后重建了包，但当时没有升版本号。
> 为了避免"装 v1.1.2 试试"装到没修的那一版，**把含修复的这份内容重新发为 v1.1.3**：
> 与 `722818fc…` 相比，**代码只差 `versionCode` / `versionName` 那两行**。

> 改动见 §2.11，单测见 §3.1，真机对照见 §3.5，验收见 §3.3；
> 完整的"改动 + 证据 + 踩过的坑"见 `dist/evidence/audit6/README.md`（第六节就是这次升版本号的经过）。

> **两个 1.1.2 的包都留着，别弄混**：
> `dist/FPB-v1.1.2.apk`（`722818fc…`，1.1.2 的最终包）与
> `dist/evidence/audit6/改前-v1.1.2-旧包-e19cc5c9.apk`（更早那个，不含修复，A/B 的"改前"一侧）。"""


def load(p):
    return io.open(p, encoding="utf-8", newline="").read()


def save(p, s):
    io.open(p, "w", encoding="utf-8", newline="").write(s)


s = load(P)
assert "\r\n" not in s, "这份是 LF，别用本脚本改"
lines = s.split("\n")


def sub1(old, new, what):
    hits = [i for i, l in enumerate(lines) if old in l]
    assert len(hits) == 1, f"{what}：应命中 1 行，实际 {len(hits)} 行 {hits}"
    lines[hits[0]] = lines[hits[0]].replace(old, new)
    return hits[0] + 1


log = []

# ---- 1. 标题 ----
log.append(("标题", sub1("# FPB v1.1.2 正式发布说明", "# FPB v1.1.3 正式发布说明", "标题")))

# ---- 2. 开头那两段 blockquote ----
i = next(i for i, l in enumerate(lines) if l.startswith("> 这是**可覆盖升级**的正式包"))
j = next(i for i, l in enumerate(lines) if l.startswith("> **版本号是否应当升到"))
assert i < j, "开头两段的位置不对"
lines[i:j + 1] = NEW_HEAD.split("\n")
log.append(("开头说明", f"L{i + 1}~L{j + 1} → 换成 v1.1.3 的来历"))

# ---- 3. §1 表格 ----
log.append(("versionName", sub1("| versionName | **1.1.2** |", "| versionName | **1.1.3** |", "versionName")))
log.append(("versionCode", sub1("| versionCode | **13**（上一版 12） |", "| versionCode | **14**（上一版 13） |", "versionCode")))
log.append(("包文件", sub1(
    "| 文件 | `dist/FPB-v1.1.2.apk`，3 322 973 B（3.17 MB） |",
    "| 文件 | `dist/FPB-v1.1.3.apk`，3 322 969 B（3.17 MB） |", "包文件大小")))
log.append(("SHA-256", sub1(OLD_SHA, NEW_SHA, "APK 指纹")))

log.append(("单测行", sub1(
    "全部是 `Audit6RegressionTest`） |",
    "全部是 `Audit6RegressionTest`；"
    "升 1.1.3 重建时**又跑了一遍确认**，留档 `dist/evidence/audit6/全量单测-1.1.3重建确认.txt`） |",
    "单测行")))

log.append(("验收行", sub1(
    "第九轮已按新包**重跑一次**，计数一致） |",
    "第九轮已按新包**重跑一次**，计数一致；升 1.1.3 后**再跑一次（run8）**，计数仍一致） |",
    "验收行")))

log.append(("签名自检命令", sub1(
    "--print-certs dist/FPB-v1.1.2.apk",
    "--print-certs dist/FPB-v1.1.3.apk", "签名自检命令路径")))

# ---- 4. §3.1 末尾补一句 ----
log.append(("§3.1", sub1(
    "所以现在是 **333 项 / 0 失败 0 错误**。",
    "所以现在是 **333 项 / 0 失败 0 错误**"
    "（升 1.1.3 重建时又跑了一遍，仍是这个数 —— 那一轮只改版本号两行，业务代码没动）。",
    "§3.1 单测结论句")))

# ---- 5. §3.3 的方框里补 run8 ----
RUN8 = """\
> **半途而废的轮次不算成绩，认的是末尾那两句自报。**
>
> **升 1.1.3 之后又跑了一轮（run8）**：包换成 `9527b0b8…`
> （`versionCode=14 / versionName=1.1.3`），结论**逐项一致** ——
> 49 通过 / 0 未通过 / 5 不适用，脚本自报「共 15 条判据，未通过 0 条」
> ＋「v1.1.3 正式包验收全部通过」。
> 日志 `dist/evidence/audit6/acceptance-console-run8.log`，
> 现场截图 `dist/evidence/v113-release/`。"""
anchor = "> **半途而废的轮次不算成绩，认的是末尾那两句自报。**"
k = [i for i, l in enumerate(lines) if l.strip() == anchor]
assert len(k) == 1, f"§3.3 那个方框的锚点命中 {len(k)} 处"
lines[k[0]:k[0] + 1] = RUN8.split("\n")
log.append(("§3.3", f"L{k[0] + 1} 处补 run8 结论"))

# ---- 6. §4 升级说明的路径 ----
log.append(("§4", sub1(
    "`adb install -r dist/FPB-v1.1.2.apk`",
    "`adb install -r dist/FPB-v1.1.3.apk`", "§4 升级命令路径")))

save(P, "\n".join(lines))
print(f"{P}\n")
for what, where in log:
    print(f"  {what:<14} L{where}" if isinstance(where, int) else f"  {what:<14} {where}")
print("\n完成。")
