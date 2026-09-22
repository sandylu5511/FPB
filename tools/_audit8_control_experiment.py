"""Audit8 对照实验：把生产代码里的清零动作逐个去掉，确认对应用例真的会红。

这不是测试的一部分，是一次**一次性取证**：
"这些断言能发现问题"这件事，只能通过让它们失败来证明。
脚本自己负责在原文件上打补丁、跑用例、再原样还原（失败/异常也会还原）。
"""

import io
import os
import re
import subprocess
import sys

ROOT = r"D:\MixiaVault"
CRYPTO = os.path.join(ROOT, "app", "src", "main", "java", "com", "fpb", "vault", "crypto")
Aead = os.path.join(CRYPTO, "AeadCipher.kt")
SecretChars = os.path.join(CRYPTO, "SecretChars.kt")

# 这里原本还有一组 C 实验（"去掉 TransientAesKey.destroy() 里的 fill(0)"）。
# 1.1.6 把那个类泛化成 `TransientSecretKey`，而它对应的断言现在住在
# `TransientSecretKeyTest` 里（不在 `Audit8RegressionTest` 里了）——
# 所以那一组搬到了 `tools/_v116_control_experiment.py`（那里的 U1/U2）。
# 本文件保留 A/B 两组：它们钉的是"生产路径有没有真的调用封装"。

ENV = dict(os.environ)
ENV["JAVA_HOME"] = r"C:/Program Files/Android/Android Studio/jbr"


def read(path):
    return io.open(path, encoding="utf-8", newline="").read()


def write(path, text):
    io.open(path, "w", encoding="utf-8", newline="").write(text)


def run_tests():
    p = subprocess.run(
        [os.path.join(ROOT, "gradlew.bat"), "testDebugUnitTest",
         "--tests", "com.fpb.vault.audit.Audit8RegressionTest", "--console=plain"],
        cwd=ROOT, env=ENV, capture_output=True, text=True,
    )
    out = p.stdout + p.stderr
    return p.returncode == 0, out


def failed_cases():
    """从测试结果 XML 里读出真正失败的用例名（不靠 gradle 的文本输出）。"""
    import glob
    import xml.etree.ElementTree as ET

    names = []
    for f in glob.glob(os.path.join(ROOT, "app", "build", "test-results", "testDebugUnitTest", "*.xml")):
        try:
            root = ET.parse(f).getroot()
        except Exception:
            continue
        if root.get("name", "").split(".")[-1] != "Audit8RegressionTest":
            continue
        for case in root.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                names.append(case.get("name"))
    return sorted(names)


def experiment(title, patches):
    """patches: [(path, old, new)]；返回失败用例名列表。"""
    originals = {p: read(p) for p, _, _ in patches}
    try:
        for path, old, new in patches:
            text = read(path)
            assert text.count(old) >= 1, f"补丁未命中：{old[:60]}"
            write(path, text.replace(old, new))
        ok, out = run_tests()
        names = failed_cases()
        print(f"\n=== {title} ===")
        print(f"gradle 退出码正常={ok}；失败用例 {len(names)} 条：")
        for n in names:
            print("   ✗", n)
        if not names:
            print("   ⚠️ 一条都没红 —— 说明这组断言发现不了这个缺陷！")
            print(out[-1500:])
        return names
    finally:
        for path, text in originals.items():
            write(path, text)
        print("   （已还原生产代码）")


if __name__ == "__main__":
    all_failed = {}

    all_failed["A"] = experiment(
        "A. 去掉 seal / open 里的 secret.destroy()",
        [
            (Aead,
             "            secret.destroy()\n        }",
             "            /* 对照实验 A：故意不销毁 */\n        }"),
            (Aead,
             "                secret.destroy()\n            }",
             "                /* 对照实验 A：故意不销毁 */\n            }"),
        ],
    )

    all_failed["B"] = experiment(
        "B. 让 CharArray.wipe() 变成空操作",
        [(SecretChars,
          "    if (isNotEmpty()) fill('\\u0000')",
          "    if (isNotEmpty()) { /* 对照实验 B：故意不清零 */ }")],
    )

    print("\n================ 汇总 ================")
    for k, v in all_failed.items():
        print(f"{k}: {len(v)} 条变红")
    sys.exit(0)
