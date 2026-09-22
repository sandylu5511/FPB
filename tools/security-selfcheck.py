#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
四项加固建议的机检脚本（**只读**：不改源码、不装包、不碰设备数据）。

用法：
    python tools/security-selfcheck.py                    # 只查源码 + APK
    python tools/security-selfcheck.py --device           # 额外查当前连接的设备能力

它回答的是"这四条建议逐条落到本工程上，哪些已经满足、哪些是真缺口、
哪些不适用"。设计上刻意做成机检而不是读一遍代码，因为后者的可靠性
等于检查者的注意力。

## 判据的四种状态（**不合并**）

- PASS  已满足
- FAIL  明确缺口 —— 会实际降低某项安全属性
- N/A   该建议在本工程的形态下**没有对象**（例如"证书绑定"，本工程不联网）
- INFO  架构事实 —— 无对错，但决定了别条建议的适用性

**为什么要单列 N/A 与 INFO**：一条判据"不适用"和"通过"在日志里长得一样时，
读者会以为那件事被验过了。本工程真正没有的东西（网络通信）必须显式写出来。

## 已知的边界

1. Keystore 的密钥属性无法从 adb 读出（需要 root 或 instrumented test）。
   所以建议 1、2 能做的是**源码级**判定（spec 构造参数）＋"平台接受了这份规格"，
   读不回"这把密钥上真的带了这个属性" —— 例如能读出的只有
   `isUserAuthenticationRequired` / `isInvalidatedByBiometricEnrollment` /
   `getSecurityLevel()`，**没有** `isStrongBoxBacked()`、**没有**
   `isUnlockedDeviceRequired()`。③④ 的直接证据在
   `app/src/androidTest/.../BiometricGateInstrumentedTest.kt` 打印的 `[规格实测]` 行。
2. 内存清零只能验"有没有这个动作"，验不了"JVM 是否真的擦干净"——
   后者在任何托管运行时上都做不到，见 SecureBytes 的 KDoc。

## 修订记录（**改了判据就要在这里留痕**）

- [审计8] 删掉"`SecretKeySpec` 实现 `Destroyable`，调 `destroy()` 即可"这条判据。
  它建立在一个**被实测推翻的前提**上：`javap` 打在 Android SDK 真实 `android.jar`
  上，Android 的 `SecretKeySpec` **没有覆盖** `Destroyable.destroy()`（那方法是
  default 实现，直接抛 `DestroyFailedException`），它能清的只有一个**包级私有**的
  `void clear()`，应用侧碰不到。OpenJDK 覆盖了它 —— 于是"在 JVM 单测里能清掉"
  会让人以为 Android 上也可以。**同一段代码在 JVM 与 Android 上结论相反**，
  这是本次最值得记住的一点。现在的判据落在真实形态上：自持副本的
  `TransientSecretKey` + `destroy()` 真填零 + 探针计数写在 `destroy()` 内部。
  旧判据若留着，会在新代码上直接报 PASS —— 那比没有判据更坏，
  因为它把"没验"说成了"验过"。
- [审计8] 建议 1、2 原本读**原文**（含注释）做子串匹配，于是"KDoc 里解释
  `setUserAuthenticationValidityDurationSeconds` 为何刻意不设"会被读成"代码里设了"，
  而取值正则会从注释里抓一段文字当成"取值"打印出来。已改为剥离注释后判定，
  并回传命中行号。
- [审计8] 凭据清零的判据从"数 `toCharArray()` 与 `fill('\\u0000')` 的条数"改为
  **逐调用点判定它是否紧跟 `.wiping`**。原因：两个总数相等不代表一一对应，
  而"总数对上"恰恰是最容易被当成通过的一种假绿。
- [审计8] 豁免清单从"按 (文件, 行号)"改为"按 (文件, 代码片段)"匹配。
  按行号的第一版在**同一天里就失效了一次**：我只改了设置页上方一段注释（+5 行），
  豁免就指向了别处。那次报得对（说明"条目必须命中"这条机制有效），
  但按行号会让每一次无关编辑都触发告警 —— 那种告警很快会被当成噪声忽略。
  改成按代码片段后，只有"这个调用点真的变了"才会动。
- [1.1.6] `TransientAesKey` 泛化为 `TransientSecretKey`（AES 与 HMAC 共用一套清零机制），
  于是**判据里的文件名与类名全部跟着改**。留这一条是因为这类改名最容易漏掉脚本 ——
  漏掉之后 `read()` 返回空串，一串判据会**静静地**从 PASS 变成"文件不存在"，
  而脚本原来的写法在空文件上多数判 `else` 分支……也就是说它会报 FAIL 还是 PASS
  取决于判据写法，而两种都不是"文件被改名了"这个事实。改名要连带脚本一起 grep。
- [1.1.6] 原来的 INFO「HMAC 密钥副本仍用 `SecretKeySpec`」**升为正式判据**。
  它当时是 INFO，是因为"是否一并收口"属于用户没做的决定，把它写成 FAIL
  等于替用户宣布决定。2026-09-20 用户决定收口，于是它变成两条 FAIL：
  一条钉"VaultSession 里不得再出现 `SecretKeySpec`"，一条钉"每个 HMAC 副本都被销毁过"。
  **这两条判据的写法有两个坑，都写进注释了**：匹配前必须剥注释
  （否则 KDoc 里那些"为什么不能用 `SecretKeySpec`"会把判据判成永远 FAIL），
  以及"在 `finally` 里"这半条不能省（少了它，异常路径漏清照样绿）。

## 判据的对照实验（**这张表是跑出来的**）

改了判据就要证明它有鉴别力，否则"通过"与"没验"在日志里长得一样。
做法：把缺陷逐条放回去、跑脚本、看是否变红，再把源码原样还原。

| 放回去的缺陷 | 新增红色 |
| --- | --- |
| D1 走回 `KeyFactory.getInstance(...).getKeySpec(k, KeyInfo::class.java)` 读 AES | 1 条（`不得用 KeyFactory 读 AES…`） |
| D2 生成成功时不写 `settings.biometricKeyTier` | 1 条（`保护等级来自生成时的档位记录`） |
| D3 读取端不从档位记录取（改成写死一个档） | 1 条（同上） |
| D4 `wipe()` 不填零 | 1 条（`` `wipe()` 真的填零 ``） |
| D5 `wiping` 去掉 `finally`（只在成功路径清） | 1 条（`` `wiping` 在 finally 里清零 ``） |
| D6 `TransientSecretKey.destroy()` 不填零 | 1 条（`` `destroy()` 真的填零并断开引用 ``） |
| D7 `VaultSession.derivedRowIdFrom` 走回 `SecretKeySpec` | 1 条（`VaultSession 不得再用 SecretKeySpec`） |
| D8 `VaultSession.derivedRowIdFrom` 去掉 `finally { secret.destroy() }` | 1 条（`每个 HMAC 密钥副本都被销毁过`） |
| D9 把 `secret.destroy()` 从 `finally` 挪到 `try` 体末尾（数量不变、异常路径漏清） | 1 条（同上，失败信息应指出是"不在 finally 里"那半条） |

基线只有 1 条 FAIL（反调试，用户已明确决定暂缓），还原后回到该基线。
D1 还顺带说明了另一件事：**这个脚本自己也会被"看起来在干活"的代码骗过去** ——
旧判据（"`SecretKeySpec` 实现 `Destroyable`"）在新代码上会直接报 PASS。
"""

import argparse
import io
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "fpb", "vault")
APP_GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
AAPT2 = r"D:/AndroidSdk/build-tools/36.0.0/aapt2.exe"
ADB = r"D:/AndroidSdk/platform-tools/adb.exe"

RESULTS = []

# 凭据清零的豁免清单：**按 (文件名, 代码片段) 匹配**，每条都必须写明理由。
# （第一版按 (文件名, 行号) 匹配，同一天里就因为它上面的注释改了 5 行而指到别处；
# 那时它确实报了警——说明"条目必须命中"这条机制有效——但按行号会让每一次无关编辑
# 都触发告警，而那种告警很快会被当成噪声忽略。改按代码片段后，只有调用点真的变了才会动。）
#
# 判据是"每一个凭据型 `toCharArray()` 都紧跟 `.wiping`"，豁免只留给
# **所有权真的被交出去**这种结构上就不该就地清的情形。
# 一旦出现"先放过，以后再说"的条目，这条判据就开始退化成装饰品。
#
# 另一类（源码里写死的字面量，如 KDF 预热的常量口令）不进这张表 ——
# 它由判据自动识别并单独计数，不需要人肉备案。
# 把"能自动判的"和"必须人判的"混在一张表里，会让这张表很快没人维护。
CREDENTIAL_WIPE_EXEMPT = {
    ("SettingsScreen.kt", "onConfirm(decoy.toCharArray())"): (
        "数组的**所有权移交给同步消费方**（对话框的 onConfirm 回调就地消费掉它）。"
        "转换点自己不负责清理 —— 在这里就地 wiping 反而会在消费方读到之前把数组清空。"
        "这是全工程**唯一**的转换点豁免，下面会校验它必须命中"
    ),
}

# HMAC 密钥副本的豁免清单：与上面那张表同构，**按 (文件名, 代码片段) 匹配**。
#
# 当前**为空**，而且这不是"还没来得及填"：全工程没有任何一处"合法地需要一个
# 我们清不掉的 HMAC 密钥副本"。留着这张空表是机制问题 —— 将来若真出现合法用途，
# 必须显式登记在这里，而"登记了却命中不了"会被下面校验出来。
# 没有这层机制，下一个人只会在判据里加一句 `if "某理由" not in code`，
# 那条判据从那一刻起就开始退化成装饰品。
SECRETKEYSPEC_EXEMPT = {}


def record(verdict, group, label, detail=""):
    RESULTS.append((verdict, group, label, detail))


def read(rel):
    path = os.path.join(ROOT, rel)
    if not os.path.exists(path):
        return ""
    return io.open(path, encoding="utf-8").read()


def strip_comments(code):
    """去掉行注释与块注释（等长空白，保留行号），避免"注释里提到"被算成"代码里有"。"""
    code = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), code, flags=re.S)
    code = re.sub(r"//[^\n]*", "", code)
    return code


def walk_sources():
    out = {}
    for dirpath, _, files in os.walk(SRC):
        for f in files:
            if f.endswith(".kt"):
                p = os.path.join(dirpath, f)
                out[os.path.relpath(p, ROOT).replace("\\", "/")] = io.open(
                    p, encoding="utf-8"
                ).read()
    return out


def grep_count(sources, pattern, flags=0, exclude_paths=()):
    """返回 (命中文件数, [(文件, 行号, 内容)])。注释已剥离。"""
    hits = []
    rx = re.compile(pattern, flags)
    for path, text in sources.items():
        if any(ex in path for ex in exclude_paths):
            continue
        for i, line in enumerate(strip_comments(text).split("\n"), 1):
            if rx.search(line):
                hits.append((path, i, line.strip()))
    files = {h[0] for h in hits}
    return len(files), hits


def line_text(code, index):
    """回传 `index` 所在行的整行原文（含缩进，不含换行符）。"""
    start = code.rfind("\n", 0, index) + 1
    end = code.find("\n", index)
    return code[start:end if end >= 0 else len(code)]


def line_of(text, index):
    """把字符偏移换算成 1 起的行号。"""
    return text.count("\n", 0, index) + 1


def hit_lines(code, pattern):
    """在**已剥离注释**的代码里找 pattern，回传 1 起的行号列表。"""
    rx = re.compile(pattern)
    return [line_of(code, m.start()) for m in rx.finditer(code)]


def guarded_line(code, pattern):
    """回传 pattern 在代码里**命中处的整行文本**（已 strip），没命中回 None。

    用它把"调用了这个 API"升级成"这个调用长什么样" —— 例如
    `if (level.strongBox) builder.setIsStrongBoxBacked(true)` 与
    裸的 `builder.setIsStrongBoxBacked(true)` 是两件后果完全不同的事，
    而"文件里出现过这个字符串"对两者取同一个值。
    """
    m = re.search(pattern, code)
    if not m:
        return None
    start = code.rfind("\n", 0, m.start()) + 1
    end = code.find("\n", m.end())
    return code[start:end if end >= 0 else len(code)].strip()


def pretty(rx):
    """把判据正则折成可读的写法 —— 别把 `\\s*` 原样打到屏幕上给人看。"""
    return rx.replace(r"\s*", "").replace("\\(", "(").replace("\\)", ")").replace("\\b", "")


DECL_ENDERS = ("@", "override ", "internal ", "private ", "public ", "protected ",
               "fun ", "val ", "var ", "class ", "object ", "enum ", "companion ")


def decl_body(code, decl):
    """回传从 `decl`（含）到**下一个同级声明**（不含）之间的文本。

    ## 为什么不用"从第一个 `{` 开始配对花括号"

    第一版用的是花括号配对。它在**块体函数**上是对的，在**表达式体函数**上是错的：
    `override fun getEncoded(): ByteArray = material ?: error(...)` 这种写法没有花括号，
    于是"第一个 `{`"会一路走到**下一个函数的函数体**里去。

    具体后果不是报错，而是**在另一个函数上通过** —— "getEncoded 里有没有 copyOf"
    实际查的是 `destroy()` 的函数体。这类错误的鉴别力是零，而且它长得和正常通过一模一样。

    现在的做法是按**声明边界**切：下一个行的缩进不深于本声明、且以声明关键字开头，就结束。
    KDoc 里的续行（`*` 开头）与函数体内的语句（缩进更深）都不会被误判成边界。
    """
    i = code.find(decl)
    if i < 0:
        return ""
    line_start = code.rfind("\n", 0, i) + 1
    indent = len(code[line_start:i]) - len(code[line_start:i].lstrip())
    pos = code.find("\n", i)
    while pos >= 0:
        nxt = code.find("\n", pos + 1)
        row = code[pos + 1:nxt if nxt >= 0 else len(code)]
        body = row.lstrip()
        if body and not body.startswith("*"):
            row_indent = len(row) - len(body)
            if row_indent <= indent and body.startswith(DECL_ENDERS):
                return code[i:pos]
        pos = nxt
    return code[i:]


def apk_path():
    """取 dist 下**版本号最大**的那个包，并回传它的名字与指纹供打印。

    第一版按 `sorted()` 取第一个 —— 于是字典序把 `FPB-v1.0.2.apk` 排在
    `FPB-v1.1.4.apk` 前面，整组"产物级实测"验的是三个版本前的旧包。
    这正是"先断言被测对象真的是我以为的那个版本"那条：**选错包不会报错**，
    它只会让所有断言在另一个对象上通过。
    """
    dist = os.path.join(ROOT, "dist")
    if not os.path.isdir(dist):
        return None
    best = None
    for name in os.listdir(dist):
        m = re.fullmatch(r"FPB-v(\d+)\.(\d+)\.(\d+)\.apk", name)
        if m:
            key = tuple(int(x) for x in m.groups())
            if best is None or key > best[0]:
                best = (key, name)
    return os.path.join(dist, best[1]) if best else None


def run(cmd):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except Exception as e:  # noqa: BLE001
        return -1, str(e)


# ============================================================ 建议 1

def check_group1(sources, dev):
    g = "建议1 硬件支持的 Keystore"
    bio = os.path.join("app/src/main/java/com/fpb/vault/vault/BiometricGate.kt")
    code = strip_comments(read(bio))

    ks = hit_lines(code, r"KeyStore\.getInstance\(\s*ANDROID_KEYSTORE\s*\)")
    if ks and '"AndroidKeyStore"' in code:
        record("PASS", g, "硬件密钥提供方为 AndroidKeyStore（TEE 内）",
               f"BiometricGate.kt:{ks[0]} 用 AndroidKeyStore provider 持有生物识别 KEK")
    else:
        record("FAIL", g, "硬件密钥提供方为 AndroidKeyStore（TEE 内）",
               "未找到 AndroidKeyStore 用法")

    # ③ StrongBox。**必须连同"它是条件调用"一起判**：无条件写死最严一档，
    # 现象是"没有该芯片的设备上指纹解锁整个消失"（生成时抛 StrongBoxUnavailableException，
    # 而它发生在用户按下指纹的那一刻）。所以查的是"请求了 + 请求被条件包住"。
    sb_line = guarded_line(code, r"setIsStrongBoxBacked\(\s*true\s*\)")
    if sb_line is None:
        record("FAIL", g, "启用 StrongBox（专用安全芯片）",
               "未调用 setIsStrongBoxBacked(true)：密钥只落在 TEE（Keymaster/KeyMint），"
               "抗物理侧信道/故障注入的能力弱于 StrongBox")
    elif not sb_line.startswith("if"):
        record("FAIL", g, "启用 StrongBox（专用安全芯片）",
               f"调用了但**未见条件保护**：`{sb_line}`。无条件请求 StrongBox 会让"
               f"不支持该特性的设备直接无法生成密钥 —— 现象是用户点指纹时解锁失败，"
               f"且他没有任何办法自己救回来")
    else:
        record("PASS", g, "启用 StrongBox（专用安全芯片）",
               f"条件调用：`{sb_line}`")

    probe = hit_lines(code, r"FEATURE_STRONGBOX_KEYSTORE")
    if probe:
        record("PASS", g, "StrongBox 能力探测",
               f"BiometricGate.kt:{probe[0]} 读 PackageManager.FEATURE_STRONGBOX_KEYSTORE")
    else:
        record("FAIL", g, "StrongBox 能力探测",
               "无能力探测。这是**前置条件**：无条件 setIsStrongBoxBacked(true) 会在"
               "不支持该特性的设备上抛 StrongBoxUnavailableException，直接让生物识别不可用")

    # ④⑤ 的形态是"阶梯"而不是"开关"：逐档尝试、失败才降级。
    # 查四件事：档位表不止一档、生成时真的在循环里逐档试、有 catch 兜住失败、API 门槛写对了。
    #
    # 判据不能只认某一种写法：`for (level in keySpecLadder())` 与
    # `val ladder = keySpecLadder(); for (level in ladder)` 是同一件事的两种写法。
    # 第一版只认前者 —— 我在同一轮里重构成后者时，它立刻报了一条**假 FAIL**。
    # 所以下面按"循环的集合表达式里提到 ladder"来判，并把实际读到的循环打出来，
    # 让一个陈旧的判据表现为一条看得懂的信息，而不是一条无从下手的失败。
    ladder_body = decl_body(code, "fun keySpecLadder")
    levels = list(dict.fromkeys(re.findall(r"Hardening\.([A-Z_]+)", ladder_body)))
    api_gate = re.search(r"SDK_INT\s*<\s*Build\.VERSION_CODES\.P", ladder_body)
    gen_body = decl_body(code, "fun generateKey")
    loops = re.search(r"for\s*\(\s*\w+\s+in\s+\w*ladder\s*(?:\(\))?\s*\)", gen_body)
    catches = re.search(r"catch\s*\(", gen_body)
    from_ladder = "keySpecLadder()" in gen_body

    if len(levels) >= 2 and loops and catches and from_ladder:
        record("PASS", g, "StrongBox 不可用时逐级降级",
               f"档位 {len(levels)} 档（{' → '.join(levels)}），generateKey 逐档 try 后降级"
               + ("；API 28 以下跳过前两档" if api_gate else ""))
    else:
        record("FAIL", g, "StrongBox 不可用时逐级降级",
               f"档位 {levels or '未找到'}；generateKey 里——"
               f"循环 {'有' if loops else '无'}、catch {'有' if catches else '无'}、"
               f"取自 keySpecLadder() {'是' if from_ladder else '否'}。"
               f"没有阶梯就只有两种结局：要么要不到最好的，要么在弱设备上完全不可用")

    # ③ "这把钥匙由哪一级硬件守着"必须有一个**真能取到值**的来源。
    #
    # 原设计用 `KeyFactory.getKeySpec(aesKey, KeyInfo::class.java)` 把属性读回来。
    # 实测（问 provider 自己）：**AndroidKeyStore 不注册 KeyFactory/AES** ——
    # 它注册的是 EC / RSA / XDH / ED25519 / ML-DSA。所以那条路对 AES 密钥
    # **不存在**，`guard` 永远是 null，界面上两条保护等级文案都不会出现
    # （"无法确认密钥受安全硬件保护"恰恰是该被看见的那条），
    # 而仪器测试里的核心断言会永远以"环境限制"为由跳过。
    #
    # 它最坏的地方不是坏，是**看起来在认真读回规格**。
    # 判据钉两件事：不得再走那条不存在的路；档位必须"生成时记下、读取时取用"。
    #
    # 匹配的是**真实调用**（`KeyFactory.getInstance` / `KeyInfo::class`），
    # 不是字面量 "KeyFactory"：本工程在诊断输出里有一行
    # "不适用：AndroidKeyStore 未注册 KeyFactory/AES" —— 那是**记录这个结论的说明文字**，
    # 第一版判据把它判成了"还在用 KeyFactory"，报出一条假 FAIL。
    # 一句解释被自己的检查器当成违规，是最容易让人干脆关掉检查器的一种错。
    kf_hits = hit_lines(code, r"KeyFactory\s*\.\s*getInstance")
    ki_hits = hit_lines(code, r"KeyInfo\s*::\s*class")
    gen_body = decl_body(code, "fun generateKey")
    spec_body = decl_body(code, "fun enrollmentSpec")
    records_tier = "biometricKeyTier" in gen_body
    reads_tier = "biometricKeyTier" in spec_body

    if kf_hits and ki_hits:
        record("FAIL", g, "不得用 KeyFactory 读 AES 密钥的 KeyInfo",
               f"**现象**：保护等级永远取不到值 —— 界面上"
               f"\"密钥在独立安全芯片内\"与\"无法确认密钥受安全硬件保护\"两句话都不会出现，"
               f"而后者正是应该被看见的那一句。\n         "
               f"实测：`AndroidKeyStore` 未注册 `KeyFactory/AES`，"
               f"所以 `getKeySpec(aesKey, KeyInfo::class.java)` 必然抛 "
               f"`NoSuchAlgorithmException`（命中行 {kf_hits[:3]} / {ki_hits[:3]}）")
    else:
        record("PASS", g, "不得用 KeyFactory 读 AES 密钥的 KeyInfo",
               "已避开这条对 AES 密钥不存在的路径")

    if records_tier and reads_tier:
        record("PASS", g, "保护等级来自生成时的档位记录",
               "生成成功即记档（平台对不支持的要求抛异常、不会静默忽略），读取端从记录取")
    else:
        record("FAIL", g, "保护等级来自生成时的档位记录",
               f"generateKey 记档={records_tier}、enrollmentSpec 读档={reads_tier}。"
               f"**现象**：界面永远不说明密钥的保护等级。"
               f"只记不读是「写完没人取」，只读不记是「永远取到空值」 —— "
               f"两种情况都让这套说明变成装饰")

    # 架构事实：密码路径的 KEK 不可能进硬件
    kr = read(os.path.join("app/src/main/java/com/fpb/vault/crypto/VaultKeyring.kt"))
    if "Argon2Kdf.derive" in kr:
        record("INFO", g, "密码路径的 KEK 由 Argon2 派生，无法放入硬件",
               "建议里\"真库 DEK 或 KEK 存 TEE\"只对**生物识别槽**成立；"
               "主密码/恢复码/假密码三条路径的 KEK 是从用户输入派生的，"
               "硬件无法接收密码 —— 这三条路径的防护落点只有 Argon2 参数本身")

    if dev is not None:
        if dev["strongbox"]:
            record("INFO", g, f"当前设备具备 StrongBox（{dev['model']}）", "可实测 StrongBox 路径")
        else:
            record("INFO", g, f"当前设备**不具备** StrongBox（{dev['model']}，API {dev['sdk']}）",
                   f"hardware_keystore={dev['keystore_level']}，无 strongbox_keystore 特性 —— "
                   f"正说明\"必须探测后降级\"不是纸面要求")


# ============================================================ 建议 2

def check_group2(sources, dev):
    g = "建议2 严格限制密钥使用"
    code = strip_comments(read("app/src/main/java/com/fpb/vault/vault/BiometricGate.kt"))

    checks = [
        (r"setUserAuthenticationRequired\(\s*true\s*\)",
         "密钥必须用户认证后才能使用"),
        (r"setInvalidatedByBiometricEnrollment\(\s*true\s*\)",
         "新增/变更生物识别后密钥作废"),
        (r"setKeySize\(\s*KEY_BITS\s*\)", "密钥长度 256 位"),
        (r"BLOCK_MODE_GCM", "分组模式 GCM（带认证）"),
        (r"ENCRYPTION_PADDING_NONE", "无填充（GCM 要求）"),
        (r"BIOMETRIC_STRONG", "只接受强生物识别（不支持 2D 人脸等弱档）"),
        (r"CryptoObject\(", "认证与解密绑定同一个 Cipher（不能只验界面）"),
    ]
    for needle, label in checks:
        lines = hit_lines(code, needle)
        if lines:
            record("PASS", g, label, f"BiometricGate.kt:{lines[0]} {pretty(needle)}")
        else:
            record("FAIL", g, label, f"未找到 {needle}")

    # 有效期：**不设**才是最严档（每次使用都要认证）。
    # 这一段以前读的是原文，于是 KDoc 里那句"刻意不设 setUserAuthenticationValidityDurationSeconds"
    # 被读成"设了"，还从注释里抓了一段文字当取值打印出来 —— 判据的结论与实际相反，
    # 而它的输出看起来像一次认真的核查。现在只认代码。
    dur = hit_lines(code, r"setUserAuthenticationValidityDurationSeconds\s*\(")
    par = hit_lines(code, r"setUserAuthenticationParameters\s*\(")
    if not dur and not par:
        record("PASS", g, "有效期未显式设置 ⇒ 每次使用都要认证（auth-per-use）",
               "这是最严的一档：不存在\"认证后一段窗口内免认证\"的降级窗口 —— "
               "窗口恰恰是应用切到后台再切回来时最不该有的东西")
    else:
        where = dur + par
        record("INFO", g, "认证有效期已显式设置（需确认不是宽窗口）",
               f"命中行 {where}；取值："
               f"{guarded_line(code, r'setUserAuthentication(ValidityDurationSeconds|Parameters)\\s*\\(')}")

    # ④ 设备锁屏期间密钥不可用
    udr = guarded_line(code, r"setUnlockedDeviceRequired\(\s*true\s*\)")
    if udr is None:
        record("FAIL", g, "设备锁屏期间密钥不可用",
               "未调用 setUnlockedDeviceRequired(true)（API 28+）。"
               "**边际收益要说清**：本密钥已是 auth-per-use，认证与使用是同一次操作、"
               "没有\"认证后待用\"的窗口，所以这条是纵深防御而非补漏")
    elif not udr.startswith("if"):
        record("FAIL", g, "设备锁屏期间密钥不可用",
               f"调用了但**未见条件保护**：`{udr}`。部分厂商的 Keystore 会拒绝这项要求，"
               f"无条件写死会让那些设备上的指纹解锁整个不可用")
    else:
        record("PASS", g, "设备锁屏期间密钥不可用", f"条件调用：`{udr}`")
        # **证据上限必须写出来**，否则读者会以为"从密钥上读回了这个属性"。
        record("INFO", g, "`setUnlockedDeviceRequired` 的证据上限",
               "KeyInfo 里**没有** `isUnlockedDeviceRequired()` —— 能读回的只有 "
               "`isUserAuthenticationRequired` / `isInvalidatedByBiometricEnrollment` / "
               "`getSecurityLevel()`。所以这条能证明的是"
               "\"我们请求了 ＋ 平台没有拒绝这份规格\"，**不是**"
               "\"从密钥上读回确认生效\"。要更强的证据只能在真机上做行为测试"
               "（锁屏后调用解密，期望失败）")

    if "clear()" in code and "deleteEntry" in code:
        record("PASS", g, "密钥失效后主动销毁",
               "KeyPermanentlyInvalidated / UserNotAuthenticated / UnrecoverableKey 三类异常都会 clear()")
    else:
        record("FAIL", g, "密钥失效后主动销毁", "未找到失效路径的清理")


# ============================================================ 建议 3

def check_group3(sources, dev):
    g = "建议3 最小化内存中的明文密钥"

    sb = read("app/src/main/java/com/fpb/vault/crypto/SecureBytes.kt")
    if re.search(r"fun close\(\)\s*\{[^}]*fill\(0\)", sb, re.S):
        record("PASS", g, "SecureBytes 释放时填零", "close() 对内部数组 fill(0)")
    else:
        record("FAIL", g, "SecureBytes 释放时填零", "close() 未见填零")

    if "MeshDigest" in sb or "MessageDigest.isEqual" in sb:
        record("PASS", g, "密钥字节比对为恒定时间", "constantTimeEquals 用 MessageDigest.isEqual")
    else:
        record("INFO", g, "密钥字节比对方式", "未找到恒定时间比较")

    if "takeOwnership" in sb:
        record("PASS", g, "接管语义（避免无谓复制）",
               "takeOwnership 不复制，KDF/随机数产出可直接接管")
    else:
        record("INFO", g, "接管语义", "未找到 takeOwnership")

    # ---- 真缺口 A：密码 CharArray 的调用方清零（原缺口，已修） ----
    #
    # 判据必须精确到**"这一个调用点上的这一段数组，清没清"**。
    #
    # 走过两次弯路，都值得记下来：
    #  1. 第一版把 `SecureBytes.zeroize(plain)` 也算成"清零" —— 而它清的是
    #     **解密出来的明文 ByteArray**，与密码字符数组是两件不相干的事，
    #     于是本条报了"清零 1 处"这个假绿。
    #  2. 第二版改成"数 `toCharArray()` 的条数 vs 数 `fill('\u0000')` 的条数"。
    #     这仍然不够：两个总数相等不代表一一对应，而且在新的 `wiping` 约定下，
    #     全工程只有 `SecretChars.kt` 里那**一处** `fill('\u0000')`，
    #     却要覆盖 9 个调用点 —— 数字上永远是"9 vs 1"。
    #
    # 现在的判据是**逐调用点**的：每一个凭据型 `toCharArray()` 都必须**紧跟** `.wiping`。
    # 这样做还有个好处：它不依赖任何计数，新加一个漏掉的调用点立刻就是一条 FAIL。
    # 后缀判据：`.toCharArray()` 之后（可跨行）必须紧跟 `.wiping`。
    # 注意这里**不能**把 `.toCharArray()` 写进这个正则 —— 匹配对象是它的后缀，
    # 带上前缀就永远匹配不上，而"永远不匹配"的表现是**每一处都报 FAIL**：
    # 一个坏掉的判据不一定偏向"通过"，它也可能把全绿的东西报成全红。
    wipe_suffix = re.compile(r"\s*\n?\s*\.wiping\b")
    wiped, unwiped, consts, byte_zero = [], [], [], []
    for path, text in sorted(sources.items()):
        code = strip_comments(text)
        fname = path.split("/")[-1]
        for m in re.finditer(r"\.toCharArray\(\)", code):
            ln = line_of(code, m.start())
            # 命中处**所在行、且在 toCharArray 之前**的那段文本：
            # 用来区分"用户输入"与"源码里写死的字面量"。
            head = code[code.rfind("\n", 0, m.start()) + 1:m.start()].strip()
            tail = code[m.end():m.end() + 40]
            if re.search(r'"[^"]*"$', head.rstrip()):
                consts.append(f"{fname}:{ln}")
            elif wipe_suffix.match(tail):
                wiped.append(f"{fname}:{ln}")
            else:
                # 存整行原文：豁免清单按**代码片段**匹配，不按行号（理由见下）。
                unwiped.append((fname, ln, line_text(code, m.start()).strip()))
        for m in re.finditer(r"zeroize\(|\.fill\(0\)", code):
            byte_zero.append(f"{fname}:{line_of(code, m.start())}")

    # 豁免清单：**按 (文件, 代码片段) 匹配**，且要求必须命中。
    #
    # 第一版按 (文件, 行号) 匹配。它在同一天里就失效了一次 ——
    # 我只改了设置页上方的一段注释文案（+5 行），这条豁免就指向了别处，
    # 于是检查报出"清单已失效"。那次报得对（说明机制有效），但**按行号匹配
    # 会让每一次无关编辑都触发一次告警**，很快就会被当成噪声忽略掉。
    # 按代码片段匹配则只在"这个调用点真的变了"时才动。
    #
    # 无论是哪种，"条目必须命中"这条都不能松：一条会继续匹配到别处的豁免，
    # 本身就是一台假绿机器。
    for (fname, snippet), reason in sorted(CREDENTIAL_WIPE_EXEMPT.items()):
        hits = [e for e in unwiped if e[0] == fname and snippet in e[2]]
        if hits:
            for e in hits:
                unwiped.remove(e)
            record("INFO", g, f"凭据清零豁免（备案）：{fname} 的 {snippet}（现于第 {hits[0][1]} 行）", reason)
        else:
            record("FAIL", g, f"凭据清零豁免清单已失效：{fname} 的 {snippet}",
                   "这条不是代码缺口，是**检查器自身的维护告警**："
                   "已经找不到这段代码了（被改了、被清掉了、或文件名变了）。"
                   "留着它意味着将来会有一处调用点被静默豁免 —— "
                   "请核对后更新或删除 CREDENTIAL_WIPE_EXEMPT")

    if unwiped:
        record("FAIL", g, "密码字符数组在用后被清零",
               f"**现象**：解锁 / 改密码 / 开假密码时输入的那串口令，会在堆上多留一份"
               f"可用后也不清除的副本，要等 GC 才走。有 root 或被注入（Frida）的设备上，"
               f"攻击者可以直接从进程内存里读出主密码 —— 而主密码能解开真库，"
               f"整套密码学设计被绕过，一个字节的密文都不用碰。\n"
               f"         未清零的调用点 {len(unwiped)} 处："
               f"{'、'.join(f'{e[0]}:{e[1]}' for e in unwiped)}"
               f"（判据：`.toCharArray()` 之后必须紧跟 `.wiping`）。"
               f"\n         已按约定清零 {len(wiped)} 处，写死的常量 {len(consts)} 处不计"
               f"（{'、'.join(consts) if consts else '无'}）。"
               f"对 ByteArray 的清零确有 {len(byte_zero)} 处，"
               f"说明作者对\"用完清零\"是有意识的")
    elif wiped:
        record("PASS", g, "密码字符数组在用后被清零",
               f"{len(wiped)} 个凭据调用点全部由 `.wiping` 包住"
               f"（{'、'.join(wiped)}）；另 {len(consts)} 处是写死的常量，与凭据无关")
    else:
        record("INFO", g, "密码字符数组在用后被清零", "未找到凭据型的 toCharArray() 调用点")

    # `wiping` 自身必须成立。三条都要，缺一条这个机制就是装饰：
    #  - `inline`：不是为了性能，是为了让 `try/finally` 能被内联进协程体 ——
    #    否则只能"先把数组交给协程、再回头清"，那是在**并发时间点**上清零，
    #    既可能清早了（协程还没用）也可能清晚了。
    #  - `finally`：只在成功路径上清，等于在最需要清的那条路径（密码错、
    #    Argon2 抛异常、协程被取消）上不清。
    #  - `fill('\u0000')`：真填零。
    sc = strip_comments(read("app/src/main/java/com/fpb/vault/crypto/SecretChars.kt"))
    wipe_body = decl_body(sc, "fun CharArray.wipe()")
    wiping_body = decl_body(sc, "fun <T> CharArray.wiping")
    if "inline fun <T> CharArray.wiping" in sc:
        record("PASS", g, "`wiping` 是 inline（才能包住 suspend 调用）",
               "`try/finally` 被内联进协程体，清零点与使用点是同一段顺序代码 —— "
               "而不是\"先把数组交给协程、再回头清\"")
    else:
        record("FAIL", g, "`wiping` 是 inline（才能包住 suspend 调用）",
               "**现象**：解锁/改密码这些入口都在协程里，非 inline 的 `wiping` 没法把 "
               "`try/finally` 带进协程体，只能先把数组交给协程再回头清 —— "
               "那是在并发时间点上清零，可能清早了（协程还没用）也可能清晚了")

    if "finally" in wiping_body and "wipe()" in wiping_body:
        record("PASS", g, "`wiping` 在 finally 里清零",
               "密码错、Argon2 抛异常、协程被取消这三条路径一样会清到位")
    else:
        record("FAIL", g, "`wiping` 在 finally 里清零",
               "**现象**：只在成功路径上清，等于在最需要清的那条路径上不清 —— "
               "输错密码的人比输对的人更容易被读走口令，这是反的")

    if re.search(r"fill\(\s*'\\u0000'\s*\)", wipe_body):
        record("PASS", g, "`wipe()` 真的填零", "`fill('\\u0000')`")
    else:
        record("FAIL", g, "`wipe()` 真的填零", "未见 fill('\\u0000')")

    # ---- 真缺口 B：每次加解密产生的密钥副本能被清零（原缺口，已修） ----
    #
    # **这一段是本次修订的重点**：旧判据的前提（"`SecretKeySpec` 实现 Destroyable，
    # 调 destroy() 即可"）已被实测推翻，而它在**新代码上会直接报 PASS** ——
    # 因为 `SecretKeySpec` 已经不再出现，"找不到缺陷"被当成了"没有缺陷"。
    # 一条前提错误的判据比没有判据更坏：它把"没验"说成了"验过"。
    # 现在的判据落在真实形态上，并且**同时钉住"探针的位置"**。
    ae = strip_comments(read("app/src/main/java/com/fpb/vault/crypto/AeadCipher.kt"))
    tk = strip_comments(read("app/src/main/java/com/fpb/vault/crypto/TransientSecretKey.kt"))
    tk_raw = read("app/src/main/java/com/fpb/vault/crypto/TransientSecretKey.kt")

    regress = re.search(r"\bSecretKeySpec\s*\(", ae)
    if regress:
        record("FAIL", g, "AeadCipher 不得再用 SecretKeySpec",
               "**现象**：每次打开笔记/图片都会把 DEK 明文复制一份进堆，而且**够不到** ——"
               "Android 的 `SecretKeySpec` 没有覆盖 `Destroyable.destroy()`"
               "（`javap` 实测：只有一个**包级私有**的 `void clear()`），"
               "调 `destroy()` 一个字节也清不掉、还会抛异常，只能等 GC")
    else:
        record("PASS", g, "AeadCipher 不得再用 SecretKeySpec",
               "已换成自持副本的 TransientSecretKey（原因见其 KDoc 的 javap 实测）")

    got_encoded = decl_body(tk, "fun getEncoded")
    if tk and ": SecretKey" in tk and re.search(r"private var material: ByteArray\?", tk):
        record("PASS", g, "密钥副本由自己持有（能清零的前提）",
               "TransientSecretKey 实现 SecretKey，明文放在自己的 material 字段里")
    else:
        record("FAIL", g, "密钥副本由自己持有（能清零的前提）",
               "**现象**：副本若由平台类持有，我们既拿不到它也没有销毁手段 —— "
               "要能清就必须自己拿所有权")

    destroy_body = decl_body(tk, "fun destroy")
    if re.search(r"fill\(\s*0\s*\)", destroy_body) and re.search(r"material\s*=\s*null", destroy_body):
        record("PASS", g, "`destroy()` 真的填零并断开引用", "material.fill(0); material = null")
    else:
        record("FAIL", g, "`destroy()` 真的填零并断开引用",
               "**现象**：`destroy()` 被调用了、什么也没发生 —— 这是最难发现的一类"
               "（调用点看起来完全正确）")

    if got_encoded and "copyOf" not in got_encoded:
        record("PASS", g, "`getEncoded()` 不再多复制一份明文",
               "交出内部数组本身（Cipher 只在 init 时读一次），"
               "多复制一份只会多留一份够不到的明文 —— 与这个类的目的相反")
    else:
        record("FAIL", g, "`getEncoded()` 不再多复制一份明文",
               "`getEncoded()` 里出现了 copyOf：又多了一份我们够不到的密钥明文")

    # 探针必须写在 destroy() 内部。这是"探针有没有鉴别力"的问题：
    # 第一版把计数写在 AeadCipher 的 finally 里，于是删掉 destroy() 后
    # 对照实验**一条都没红** —— 因为计数记的是"代码走到了这一行"。
    probe_in_destroy = "scrubbedAesKeyCount" in destroy_body
    probe_faked = "scrubbedAesKeyCount" in ae
    if probe_in_destroy and not probe_faked:
        record("PASS", g, "探针计数落在 destroy() 内部（保证有鉴别力）",
               "只有真正执行了清零才自增；计数不在调用点上，避免"
               "\"把代码走没走到当成密钥清没清\"")
    else:
        record("FAIL", g, "探针计数落在 destroy() 内部（保证有鉴别力）",
               f"destroy() 内有计数={probe_in_destroy}、AeadCipher 里也有计数={probe_faked}。"
               f"**现象**：探针测的是错的东西时，它会在缺陷存在时照样绿 —— "
               f"这比没有探针更危险，因为它会让人停止怀疑这块代码")

    made = len(re.findall(r"TransientSecretKey\(", ae))
    killed = len(re.findall(r"\.destroy\(\)", ae))
    if made and killed >= made:
        record("PASS", g, "每个密钥副本都被销毁",
               f"AeadCipher 里构造 {made} 处 / destroy {killed} 处（seal 与 open 各自在 finally 里）")
    else:
        record("FAIL", g, "每个密钥副本都被销毁",
               f"构造 {made} 处、destroy {killed} 处。"
               f"**现象**：打开一条笔记或一张图就是一次未清零的明文密钥副本留在堆上")

    # ---- 真缺口 C：派生行 id 的 HMAC 密钥副本（1.1.6 收口，原来是一条 INFO） ----
    #
    # 原来是 INFO，因为"是否一并收口"是用户没做的决定，写成 FAIL 等于替他宣布决定。
    # 2026-09-20 用户决定单独收口，它就成了正式判据。
    #
    # **这条判据的写法本身有两个坑，都是踩出来的：**
    #
    # 1. **必须先剥注释。** 新代码的注释里到处是 `SecretKeySpec` —— 解释"为什么不能用它"。
    #    不剥注释就会把"解释"读成"使用"，判据当场变成永远 FAIL，
    #    然后下一个人会把这条 FAIL 当噪声删掉。本工程在建议 1、2 上吃过同款亏
    #    （KDoc 里"为何刻意不设 X"被读成"代码里设了 X"）。
    # 2. **匹配的是整个文件，不只是 `mac.init` 那一处。** 只钉调用点的话，
    #    在别处新加一个 `SecretKeySpec` 不会红 —— 而"再多一处"正是缺陷回归的形状。
    vs = strip_comments(read("app/src/main/java/com/fpb/vault/session/VaultSession.kt"))
    hm_lines = []
    for m in re.finditer(r"\bSecretKeySpec\s*\(", vs):
        row = line_text(vs, m.start())
        if any(snippet in row for _, snippet in SECRETKEYSPEC_EXEMPT):
            continue
        hm_lines.append(line_of(vs, m.start()))
    if hm_lines:
        hit = "、".join(str(n) for n in hm_lines)
        record("FAIL", g, "VaultSession 不得再用 SecretKeySpec",
               f"**现象**：每一次派生系统行 id（清单、真库账本、审计槽、诱饵账本）"
               f"都会把 32 字节的 DEK / 审计钥匙明文复制一份进堆，而且**够不到** ——"
               f"Android 的 `SecretKeySpec` 没有覆盖 `Destroyable.destroy()`"
               f"（`javap` 实测：只有一个**包级私有**的 `void clear()`），"
               f"调 `destroy()` 一个字节也清不掉、还会抛异常，只能等 GC。"
               f"命中第 {hit} 行。修法是自持副本（`TransientSecretKey`）")
    else:
        record("PASS", g, "VaultSession 不得再用 SecretKeySpec",
               "已换成自持副本的 `TransientSecretKey`（原因见其 KDoc 的 javap 实测与 "
               "dist/HMAC密钥副本-独立评估.md）")
    # 豁免条目必须命中（与凭据清零那条同款机制）：登记了却命中不了，
    # 说明它已经过期，而它保护的是一个**已经不存在**的调用点。
    for (fname, snippet), reason in SECRETKEYSPEC_EXEMPT.items():
        target = vs if fname == "VaultSession.kt" else ""
        if snippet not in target:
            record("FAIL", g, f"HMAC 密钥副本豁免条目已失效：{fname} 的 {snippet}", reason)

    # 第二个必要条件：**每一个副本都被销毁，而且清零点在 `finally` 里**。
    # 这里比 AES 那条多钉了"在 finally 里"，理由不是对称 —— 是这半条真的会漏：
    # `Mac.getInstance` 与 `mac.init` 都会抛，把 `destroy()` 挪到 `try` 体末尾
    # 之后"数量仍然对得上"，只有异常路径不再清。
    dbody = decl_body(vs, "private fun derivedRowIdFrom")
    hm_made = len(re.findall(r"TransientSecretKey\(", dbody))
    hm_killed = len(re.findall(r"secret\.destroy\(\)", dbody))
    hm_in_finally = re.search(r"finally\s*\{[^}]*secret\.destroy\(\)", dbody) is not None
    hm_problems = []
    if hm_made == 0:
        hm_problems.append(
            "`derivedRowIdFrom` 里已经找不到 `TransientSecretKey` —— 这条判据失去了对象")
    if hm_killed < hm_made:
        hm_problems.append(f"构造 {hm_made} 处、destroy {hm_killed} 处")
    if hm_made and not hm_in_finally:
        hm_problems.append(
            "销毁不在 `finally` 里（异常路径漏清 —— 而派生失败往往正是反复试错的时刻）")
    if hm_made and not hm_problems:
        record("PASS", g, "每个 HMAC 密钥副本都被销毁过",
               f"`derivedRowIdFrom` 里构造 {hm_made} 处 / destroy {hm_killed} 处，"
               "且落在 `finally` 内")
    else:
        record("FAIL", g, "每个 HMAC 密钥副本都被销毁过",
               "；".join(hm_problems) +
               "。**现象**：每次派生系统行 id 都会在堆上留下一条未清零的 32 字节明文密钥副本")

    # ---- KDF 实现形态 ----
    if "org.bouncycastle" in read("app/src/main/java/com/fpb/vault/crypto/Argon2Kdf.kt"):
        record("INFO", g, "Argon2 为纯 Java 实现（BouncyCastle），非 native",
               "标准档 64 MiB 工作内存全部落在 Java 堆里，GC 不清零，且 BC 内部还会把 "
               "char[] password 转成一份 byte[] 副本 —— 这两块都不受 SecureBytes 管。"
               "换 native（libsodium / 官方 argon2 C 库）能显著缩小暴露面，"
               "代价是失去 JVM 单元测试能力（Argon2Kdf 的 KDoc 已把这条取舍写明）")

    # ---- SQLCipher ----
    any_sqlcipher = any("sqlcipher" in t.lower() or "zetetic" in t.lower() for t in sources.values())
    g1 = read("app/build.gradle.kts").lower()
    any_sqlcipher = any_sqlcipher or "sqlcipher" in g1 or "zetetic" in g1
    if not any_sqlcipher:
        record("N/A", g, "遵循 SQLCipher 的最佳实践",
               "本工程**没有使用 SQLCipher**：内容是\"每行一个 AEAD 密文\"存进普通 SQLite，"
               "所以这条要换算成\"明文密钥的生存时间\"来审（见上面三条）。"
               "注意：**引入 SQLCipher 会破坏诱饵库设计** —— 它是\"一个库文件一把密钥\"，"
               "而本项目的真库与诱饵库刻意共用同一文件、靠不同 DEK 隔离行；"
               "拆成两个文件就会暴露\"存在第二个库\"。这不是升级，是换掉核心安全属性")
    else:
        record("INFO", g, "已引入 SQLCipher", "需按 SQLCipher 口径复核")


# ============================================================ 建议 4

def check_group4(sources, dev, apk):
    g = "建议4 减少攻击面"
    gr = read("app/build.gradle.kts")

    if re.search(r"isMinifyEnabled\s*=\s*true", gr):
        record("PASS", g, "代码混淆（R8）", "release 打开 isMinifyEnabled")
    else:
        record("FAIL", g, "代码混淆（R8）", "release 未打开 minify")

    if re.search(r"isShrinkResources\s*=\s*true", gr):
        record("PASS", g, "资源压缩", "isShrinkResources = true")
    else:
        record("INFO", g, "资源压缩", "未打开")

    pg = read("app/proguard-rules.pro")
    keeps = re.findall(r"^\s*-keep\b.*$", pg, re.M)
    record("INFO", g, f"保留规则共 {len(keeps)} 条",
           "全部业务类都不保名（无反射），反编译看不出 KDF 实现与密钥处理类")

    debug_hits = re.search(r"isDebuggable\s*=\s*true", gr)
    if debug_hits:
        record("FAIL", g, "release 包不可调试", "build.gradle.kts 里 release 打开了 isDebuggable")
    else:
        record("PASS", g, "release 包不可调试", "未显式打开 isDebuggable（release 默认 false）")

    # 反调试 / 完整性
    n, hits = grep_count(
        sources,
        r"isDebuggerConnected|ptrace|SafetyNet|PlayIntegrity|RootBeer|rootBeer|debuggerConnected",
    )
    if n == 0:
        record("FAIL", g, "反调试 / 运行环境完整性校验",
               "全工程 0 处。**对\"已解密内容\"的防护有真实影响**：Frida/Xposed 可 hook "
               "AeadCipher.open 的返回值直接拿到笔记明文，反调试是把这条路从\"开箱即用\""
               "抬到\"需要绕过工作\"的唯一手段。**诚实的效力边界**：反调试/反 root 挡得住"
               "自动化工具与脚本小子，对定向攻击只是延时，且会带来假阳性"
               "（定制 ROM 被误判 → 正常用户用不了）。"
               "\n         **本条属已知缺口，用户已于 2026-09-18 明确决定本轮暂不处置**"
               "（当时把它编为第 ⑥ 条）。列在这里是**记录状态**，不是新发现 —— "
               "一条已被拍板暂缓的缺口若报得像首次暴露，会让读者重新去评估一件已经评估过的事")
    else:
        record("PASS", g, "反调试 / 运行环境完整性校验", f"{n} 个文件含相关代码")

    # 证书绑定
    net_hits, net_loc = grep_count(
        sources, r"okhttp|retrofit|HttpURLConnection|java\.net\.|URLConnection|OkHttpClient"
    )
    # manifest 必须**先剥掉 XML 注释再判**：本工程在 manifest 里写了一整段
    # "刻意不声明 android.permission.INTERNET" 的说明，直接对原文做子串匹配
    # 会把这段**解释**读成**声明**，于是"无网络"这条被自己的注释否决掉。
    man = re.sub(r"<!--.*?-->", "", read("app/src/main/AndroidManifest.xml"), flags=re.S)
    has_internet_src = "android.permission.INTERNET" in man
    if not net_hits and not has_internet_src:
        record("N/A", g, "证书绑定（Certificate Pinning）",
               "**无网络通信通道，pinning 没有对象可绑**："
               "manifest 刻意不声明 INTERNET，源码 0 处网络库引用。"
               "这是可被第三方独立审计验证的硬约束（见下面的 APK 实测）")
    else:
        record("INFO", g, "证书绑定（Certificate Pinning）",
               f"网络引用 {net_hits} 个文件，manifest INTERNET={has_internet_src} —— 需逐条评估")

    # 数据外传面
    send_hits, _ = grep_count(sources, r"ACTION_SEND|ACTION_VIEW|FileProvider|grantUriPermission")
    if send_hits == 0:
        record("PASS", g, "无隐式数据外传通道",
               "无 ACTION_SEND / FileProvider / grantUriPermission；"
               "媒体导入走 Photo Picker 与 SAF（OpenDocument），不需要存储权限")
    else:
        record("INFO", g, "存在 Intent 外传相关代码", f"{send_hits} 处，需人工确认")

    # ---- APK 实测 ----
    if not apk:
        record("INFO", g, "APK 实测", "dist/ 下没有 FPB-v*.apk，跳过产物级校验")
        return

    name = os.path.basename(apk)
    # 自证"这一轮验的是哪个文件"。选错包不会报错，它只会让所有断言在另一个对象上通过。
    try:
        import hashlib

        digest = hashlib.sha256(open(apk, "rb").read()).hexdigest()
        size = os.path.getsize(apk)
    except Exception:  # noqa: BLE001
        digest, size = "?", 0
    print(f"产物级校验对象：{name}（{size} B，sha256={digest[:16]}…）\n")

    rc, out = run([AAPT2, "dump", "permissions", apk])
    if rc == 0:
        perms = [l.split("name='")[1].rstrip("'")
                 for l in out.split("\n") if l.startswith("uses-permission")]
        net = [p for p in perms if p.endswith("INTERNET")]
        if net:
            record("FAIL", g, f"{name} 声明了 INTERNET 权限", ", ".join(net))
        else:
            record("PASS", g, f"{name} 无 INTERNET 权限（产物级实测）",
                   f"全部权限 {len(perms)} 条：{', '.join(p.split('.')[-1] for p in perms)}")
    else:
        record("INFO", g, f"{name} 权限实测失败", out[:120])

    rc, out = run([AAPT2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
    if rc == 0:
        if re.search(r":debuggable\([^)]*\)=true", out):
            record("FAIL", g, f"{name} debuggable", "产物里 debuggable=true")
        else:
            record("PASS", g, f"{name} 不可调试（产物级实测）", "manifest 无 debuggable=true")
        if re.search(r":allowBackup\([^)]*\)=false", out):
            record("PASS", g, f"{name} 禁止系统备份（产物级实测）",
                   "allowBackup=false 且 fullBackupContent=false")
        else:
            record("FAIL", g, f"{name} 禁止系统备份", "未禁用 allowBackup")
    else:
        record("INFO", g, f"{name} manifest 实测失败", out[:120])

    try:
        with zipfile.ZipFile(apk) as z:
            sos = [n for n in z.namelist() if n.endswith(".so")]
            own = [n for n in sos if "androidx" not in n]
            if own:
                record("INFO", g, "APK 内含自有 native 库", ", ".join(own[:5]))
            else:
                record("INFO", g, "APK 内无自有 native 库",
                       f"仅 {len(sos)} 个 androidx 自带的 .so —— 印证 Argon2 与 AES 都跑在 "
                       f"Java 层，密钥材料没有 native 侧的可控清零区")
    except Exception as e:  # noqa: BLE001
        record("INFO", g, "APK 解包失败", str(e)[:120])


# ============================================================ 设备探测

def device_info():
    rc, out = run([ADB, "devices"])
    if rc != 0:
        return None
    lines = [l for l in out.split("\n")[1:] if l.strip().endswith("device")]
    if not lines:
        return None

    def prop(p):
        r, o = run([ADB, "shell", "getprop", p])
        return o.strip() if r == 0 else "?"

    r, feats = run([ADB, "shell", "pm", "list", "features"])
    feature_lines = feats if r == 0 else ""
    ks = re.search(r"hardware\.hardware_keystore=(\S+)", feature_lines)
    return {
        "model": prop("ro.product.model"),
        "sdk": prop("ro.build.version.sdk"),
        "build_type": prop("ro.build.type"),
        "strongbox": "android.hardware.strongbox_keystore" in feature_lines,
        "keystore_level": ks.group(1) if ks else "?",
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", action="store_true", help="额外探测当前连接的设备能力")
    args = ap.parse_args()

    sources = walk_sources()
    print(f"扫描源文件 {len(sources)} 个\n")

    dev = device_info() if args.device else None
    if args.device:
        print("设备：" + (f"{dev['model']} / API {dev['sdk']} / {dev['build_type']} / "
                        f"keystore={dev['keystore_level']} / strongbox={dev['strongbox']}"
                        if dev else "未连接") + "\n")

    apk = apk_path()
    check_group1(sources, dev)
    check_group2(sources, dev)
    check_group3(sources, dev)
    check_group4(sources, dev, apk)

    order = {"FAIL": 0, "PASS": 1, "N/A": 2, "INFO": 3}
    icon = {"PASS": "PASS", "FAIL": "FAIL", "N/A": "N/A ", "INFO": "INFO"}

    group = None
    for verdict, g, label, detail in sorted(RESULTS, key=lambda r: (r[1], order[r[0]])):
        if g != group:
            group = g
            print(f"\n=== {g} ===")
        print(f"  [{icon[verdict]}] {label}")
        if detail:
            print(f"         {detail}")

    counts = {}
    for verdict, *_ in RESULTS:
        counts[verdict] = counts.get(verdict, 0) + 1
    print("\n" + "-" * 72)
    print(f"判据 {len(RESULTS)} 条 | " + " ".join(
        f"{k} {counts.get(k, 0)}" for k in ("PASS", "FAIL", "N/A", "INFO")))
    if counts.get("FAIL"):
        print("\n需要处置的缺口：")
        for verdict, g, label, _ in RESULTS:
            if verdict == "FAIL":
                print(f"  - [{g}] {label}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
