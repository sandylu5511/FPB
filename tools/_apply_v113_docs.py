# -*- coding: utf-8 -*-
"""把「升到 1.1.3」这个决定同步进 audit6 报告与硬限制清单（一次性脚本）。

两件必须守住的事：
1. **保留 CRLF**：dist/ 下这几份文档都是 CRLF，用 LF 覆盖会让整个文件变成"改动"。
2. **按整行定位**，不按含引号的子串定位 —— 文档里混着中英文引号，
   用子串匹配很容易"看着一样却匹配不上"。
"""
import io
import sys

BASE = r"D:\MixiaVault"

NEW_VER_ROW = ("| 版本 | `13 / 1.1.2` → **`14 / 1.1.3`**"
               "（升版本号的经过见第六节；**业务代码未动**） |")

LIMITS_VER = "**版本**：`1.1.3 (versionCode 14)` 代码基线 + 第九轮列表满额修复"
LIMITS_DATE = ("**日期**：2026-09-18（初版 2026-09-17，基线 `1.1.1 (versionCode 12)`；"
               "`1.1.2 (code 13)` 因同一版本号下出过两个包，已重发为 `1.1.3`）")

SEC6 = """## 六、版本号：已升到 `1.1.3`（2026-09-18 定）

**决定：升到 `1.1.3`（`versionCode` 14）。**

倾向当时就写在这儿，理由照抄：**不是"改动大"，而是同一个版本号对应两种行为**
是后面最容易出错的地方 —— 任何一次"装 v1.1.2 试试"都可能装到没修的那一版，
而两边长得一模一样。当时的对照表原样留着，只把结论列补上：

| | 保持 `1.1.2`（当时现状） | 升到 `1.1.3`（**已采纳**） |
|---|---|---|
| 覆盖安装 | ✅ 能装（签名相同），数据不清 | ✅ 能装 |
| 设备上区分两个包 | ❌ 版本号相同，只能靠 `lastUpdateTime` / 哈希 | ✅ 一眼能分 |
| `dist/` 里两个包 | 需要靠文件名/留档目录区分（当时的旧包就是这么留的） | ✅ 文件名天然分开，不会互相覆盖 |
| 已发布的 v1.1.2 说明文档 | 会被这一版覆盖掉含义（同一版本号、两种行为） | 说明改名为 v1.1.3；v1.1.2 的包原处留档 |
| 用户怎么知道该不该升 | 得看哈希 | 看版本号 |

### 6.1 动到的三处

| 文件 | 改前 | 改后 |
|---|---|---|
| `app/build.gradle.kts` | `versionCode = 13` / `versionName = "1.1.2"` | `versionCode = 14` / `versionName = "1.1.3"` |
| `tools/v110-release-acceptance.py` | `VERSION_CODE = "13"` / `VERSION_NAME = "1.1.2"` / `OUT = …\\evidence\\v112-release` | `"14"` / `"1.1.3"` / `…\\evidence\\v113-release` |
| `dist/FPB-v1.1.2-发布说明.md` | —— | 改名为 `FPB-v1.1.3-发布说明.md`，正文同步 |

验收脚本里版本号只有这两个常量（脚本自己的 docstring 写着"改版只动这两行 + 证据目录名"），
改完由脚本自己去校验包身份，不靠人肉比对。

**业务代码一行没动** —— 1.1.3 这个包与 `722818fc…` 的差别只有版本号那两行。

### 6.2 结果

| 项 | 值 |
|---|---|
| 包 | `dist/FPB-v1.1.3.apk`，3 322 969 B |
| APK SHA-256 | `9527b0b8de5edfb65c36f6c7632ffbc06e6c8e3e121b61e2337de99590468c23` |
| 身份 | `versionCode=14 / versionName=1.1.3` |
| 全量单测 | **333 项 / 0 失败 / 0 错误**（留档 `全量单测-1.1.3重建确认.txt`） |
| release 验收 | 见 §3.4（结论：**49 通过 / 0 未通过 / 5 不适用**，与上一版逐项一致） |

**`1.1.2` 这个版本号下现在躺着两个包，都留着，别弄混：**

| 文件 | 指纹 | 是什么 |
|---|---|---|
| `dist/FPB-v1.1.2.apk` | `722818fc…` | 1.1.2 的**最终包**（含第九轮修复，只是当时没升版本号） |
| `dist/evidence/audit6/改前-v1.1.2-旧包-e19cc5c9.apk` | `e19cc5c9…` | 同版本号的**更早一个包**（不含第九轮修复），A/B 的"改前"那一侧 |

同一版本号、两个不同的包 —— 这就是这次要升版本号的原因本身。
"""


def load(p):
    return io.open(p, encoding="utf-8", newline="").read()


def save(p, s):
    io.open(p, "w", encoding="utf-8", newline="").write(s)


def split(s):
    """按文件自己的行尾切。

    这几份文档的行尾**不统一**：`audit6/README.md` 是 CRLF，
    硬限制清单与发布说明是 LF。（别信 `grep -c $'\\r'` ——
    Git Bash 下它对纯 LF 文件也会报"每行都有 CR"，我因此在上一版脚本里
    写死 CRLF、断言直接炸掉。用字节数才是准的。）
    """
    eol = "\r\n" if "\r\n" in s else "\n"
    return s.split(eol), eol


# ---------------- 1. audit6/README.md ----------------
p = BASE + r"\dist\evidence\audit6\README.md"
lines, eol = split(load(p))
hits = []
for i, l in enumerate(lines):
    if l.startswith("| 版本 |") and "1.1.2" in l:
        lines[i] = NEW_VER_ROW
        hits.append(f"  版本行 L{i + 1}")
    if l.startswith("## 六、"):
        hits.append(f"  第六节 L{i + 1}")
        lines[i:] = SEC6.split("\n")
        break
assert len(hits) == 2, f"audit6 该改两处，实际命中 {hits}"
save(p, eol.join(lines))
print("audit6/README.md:")
for h in hits:
    print(h)

# ---------------- 2. FPB-硬限制清单.md ----------------
p = BASE + r"\dist\FPB-硬限制清单.md"
lines, eol = split(load(p))
done = set()
for i, l in enumerate(lines):
    if l.startswith("**版本**：") and "版本" not in done:
        lines[i] = LIMITS_VER
        done.add("版本")
    elif l.startswith("**日期**：") and "日期" not in done:
        lines[i] = LIMITS_DATE
        done.add("日期")
assert done == {"版本", "日期"}, f"硬限制清单该改两处，实际 {done}"
save(p, eol.join(lines))
print("FPB-硬限制清单.md: 版本行、日期行已更新")

print("\n全部完成。")
