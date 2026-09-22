"""第 4 轮审核：扫描「定义了但零引用」的符号。

技能里的 2.1 / 2.6 说：一个被精心命名、写了注释、却没有任何调用点的常量或方法，
几乎总是「作者打算做这个校验/这个功能，后来忘了」。这类发现的产出率极高。

做法：把 app/src 下所有 Kotlin 声明抽出来，再在**全部** Kotlin 文件（含测试）里
数这个名字出现了几次。出现次数 == 声明处次数 → 零引用。

注意：不能只看名字长度，还得排掉「同名多声明」（override、重载、data class 字段）。
"""
import os
import re
import sys
from collections import defaultdict

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(BASE, "app", "src")

# 声明形态：const val / val / var / fun / class / object / enum class
DECL = re.compile(
    r"^\s*"
    r"(?:public|internal|private|protected|open|abstract|sealed|data|enum|annotation|"
    r"override|suspend|inline|operator|infix|tailrec|external|companion|const|lateinit|"
    r"@\w+(?:\([^)]*\))?\s*)*"
    r"(?:val|var|fun|class|object|interface)\s+"
    r"(?:<[^>]*>\s*)?"
    r"([A-Za-z_][A-Za-z0-9_]*)",
)

SKIP_NAMES = {
    # 语言/框架里必然高频但抽取出来的噪声
    "it", "this", "Companion", "main", "toString", "hashCode", "equals", "copy",
    "invoke", "close", "values", "entries", "valueOf", "compareTo", "iterator",
}


def kt_files():
    out = []
    for root, _dirs, files in os.walk(SRC):
        for f in files:
            if f.endswith(".kt"):
                out.append(os.path.join(root, f))
    return sorted(out)


def main():
    files = kt_files()
    texts = {}
    for f in files:
        with open(f, "r", encoding="utf-8") as fh:
            texts[f] = fh.read()

    # 声明表：名字 -> [(文件, 行号, 行内容)]
    decls = defaultdict(list)
    for f, text in texts.items():
        for i, line in enumerate(text.splitlines(), 1):
            m = DECL.match(line)
            if not m:
                continue
            name = m.group(1)
            if name in SKIP_NAMES or len(name) < 4:
                continue
            decls[name].append((f, i, line.strip()))

    # 引用计数：整词匹配
    refs = defaultdict(int)
    for text in texts.values():
        # 去掉字符串与注释会误伤（注释里提到名字也算「被提及」），这里**保留**注释，
        # 因为我们要区分的是「零提及」——注释里提到也算提及，但会另行标出。
        for name in decls:
            refs[name] += len(re.findall(r"\b" + re.escape(name) + r"\b", text))

    print("=" * 100)
    print("零引用符号（声明 1 次、全工程只出现 1 次 → 没有任何调用/读取）")
    print("=" * 100)
    zero = []
    for name, sites in decls.items():
        if len(sites) == 1 and refs[name] == 1:
            zero.append((name, sites[0]))
    for name, (f, i, line) in sorted(zero, key=lambda x: x[1][0]):
        rel = os.path.relpath(f, BASE).replace("\\", "/")
        print(f"{rel}:{i}  {line}")

    print()
    print("=" * 100)
    print(f"合计 {len(zero)} 个零引用符号")
    print("=" * 100)

    # 反向：声明了但只在测试里被引用（生产代码没用）
    print()
    print("=" * 100)
    print("只在测试里被引用的符号（生产代码零引用 —— 可能是「为测试而留」的死代码）")
    print("=" * 100)
    for name, sites in sorted(decls.items()):
        if len(sites) != 1:
            continue
        f, i, line = sites[0]
        if "/test/" in f.replace("\\", "/"):
            continue
        prod = sum(
            len(re.findall(r"\b" + re.escape(name) + r"\b", t))
            for k, t in texts.items()
            if "/test/" not in k.replace("\\", "/")
        )
        if prod == 1 and refs[name] > 1:
            rel = os.path.relpath(f, BASE).replace("\\", "/")
            print(f"{rel}:{i}  {line}")


if __name__ == "__main__":
    sys.exit(main())
