"""审核用静态扫描：找出"定义了但从未被引用"的公开声明。

对应 code-audit-regression-proof 技能里的扫描模式 2.1 ——
一个被精心命名、写了注释、却没有任何调用点的常量或函数，
几乎总是"作者打算做这个校验，后来忘了"的指纹。

用法：python tools/audit-scan.py
"""
import collections
import os
import re

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src")


def load():
    srcs = {}
    for base, _dirs, files in os.walk(ROOT):
        for f in files:
            if f.endswith(".kt"):
                p = os.path.join(base, f)
                srcs[p] = open(p, encoding="utf-8").read()
    sep = os.sep
    main = {p: t for p, t in srcs.items() if f"{sep}main{sep}" in p}
    test = {p: t for p, t in srcs.items() if f"{sep}test{sep}" in p}
    return main, test


def report(title, decls, allmain, alltest):
    print(f"\n=== {title} ===")
    hits = 0
    for name, locs in sorted(decls.items()):
        uses = len(re.findall(r"\b" + re.escape(name) + r"\b", allmain))
        if uses <= len(locs):
            t = len(re.findall(r"\b" + re.escape(name) + r"\b", alltest))
            for p, line in locs:
                print(f"  {name:32s} main引用0  test引用{t:2d}  {os.path.basename(p)}:{line}")
            hits += 1
    if hits == 0:
        print("  （无）")


def main():
    main, test = load()
    allmain = "\n".join(main.values())
    alltest = "\n".join(test.values())

    consts = collections.defaultdict(list)
    for p, t in main.items():
        for m in re.finditer(r"^\s*(?:internal |public )?(?:const )?val ([A-Z][A-Z0-9_]{2,})\b", t, re.M):
            consts[m.group(1)].append((p, t[: m.start()].count("\n") + 1))

    funcs = collections.defaultdict(list)
    for p, t in main.items():
        for m in re.finditer(r"^\s{4}(?:internal |public )?fun ([a-zA-Z][A-Za-z0-9_]*)\s*\(", t, re.M):
            funcs[m.group(1)].append((p, t[: m.start()].count("\n") + 1))

    report("常量：main 内无任何引用", consts, allmain, alltest)
    report("成员函数：main 内无任何引用（可能是死代码或缺失的调用）", funcs, allmain, alltest)


if __name__ == "__main__":
    main()
