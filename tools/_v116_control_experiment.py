"""1.1.6 对照实验：把缺陷逐个放回生产代码，确认对应用例 / 自检判据真的会红。

这不是测试的一部分，是一次**一次性取证**：用户的要求原话是
"改写后要重跑对照实验 —— 至少对新增/改写的 HMAC 相关判据，
把缺陷放回去看是否恰好新增 1 条红"。

"这些断言能发现问题"这件事，只能通过让它们失败来证明。
脚本自己负责打补丁、跑、再原样还原（异常路径也还原）。

两组：
- U*  走 `testDebugUnitTest`（TransientSecretKeyTest + Audit8RegressionTest）
- S*  走 `tools/security-selfcheck.py`（判据级）

基线：单测 0 失败；自检只有 1 条 FAIL（反调试，用户 2026-09-18 明确决定暂缓）。
所以 S* 的判据是"**除基线那条之外**恰好新增 1 条红"。
"""

import glob
import io
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = r"D:\MixiaVault"
CRYPTO = os.path.join(ROOT, "app", "src", "main", "java", "com", "fpb", "vault", "crypto")
SESSION = os.path.join(ROOT, "app", "src", "main", "java", "com", "fpb", "vault", "session")
TSK = os.path.join(CRYPTO, "TransientSecretKey.kt")
Aead = os.path.join(CRYPTO, "AeadCipher.kt")
VS = os.path.join(SESSION, "VaultSession.kt")

ENV = dict(os.environ)
ENV["JAVA_HOME"] = r"C:/Program Files/Android/Android Studio/jbr"

BASE_FAIL = "反调试"

UNIT_CLASSES = ("TransientSecretKeyTest", "Audit8RegressionTest")


def read(path):
    return io.open(path, encoding="utf-8", newline="").read()


def write(path, text):
    io.open(path, "w", encoding="utf-8", newline="").write(text)


def run_unit():
    p = subprocess.run(
        [os.path.join(ROOT, "gradlew.bat"), "testDebugUnitTest",
         "--tests", "com.fpb.vault.crypto.TransientSecretKeyTest",
         "--tests", "com.fpb.vault.audit.Audit8RegressionTest",
         "--console=plain"],
        cwd=ROOT, env=ENV, capture_output=True, text=True,
    )
    return p.returncode == 0, p.stdout + p.stderr


def failed_unit_cases():
    """从测试结果 XML 里读失败用例名（不靠 gradle 的文本输出）。"""
    names = []
    pat = os.path.join(ROOT, "app", "build", "test-results", "testDebugUnitTest", "*.xml")
    for f in glob.glob(pat):
        try:
            root = ET.parse(f).getroot()
        except Exception:
            continue
        if root.get("name", "").split(".")[-1] not in UNIT_CLASSES:
            continue
        for case in root.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                names.append(case.get("name"))
    return sorted(names)


def run_selfcheck(tag):
    out_dir = os.path.join(ROOT, "dist", "evidence", "v116-control")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, f"selfcheck-{tag}.log")
    with io.open(out, "w", encoding="utf-8") as fh:
        subprocess.run(
            [sys.executable, os.path.join(ROOT, "tools", "security-selfcheck.py")],
            cwd=ROOT, env=ENV, stdout=fh, stderr=subprocess.STDOUT,
        )
    text = io.open(out, encoding="utf-8").read()
    fails = [l.strip() for l in text.splitlines() if re.match(r"\s*\[FAIL\]", l)]
    return [f for f in fails if BASE_FAIL not in f], fails


def experiment(title, patches, mode, tag):
    """patches: [(path, old, new)]。返回 (新增红的条目, 全部条目)。"""
    originals = {p: read(p) for p, _, _ in patches}
    try:
        for path, old, new in patches:
            text = read(path)
            assert text.count(old) == 1, f"补丁未命中或有歧义（{text.count(old)} 处）：{old[:60]}"
            write(path, text.replace(old, new))
        if mode == "unit":
            ok, out = run_unit()
            new_red = failed_unit_cases()
            baseline = []
        else:
            new_red, baseline = run_selfcheck(tag)
            ok = True
            out = ""
        print(f"\n=== {title} ===")
        print(f"  新增红 {len(new_red)} 条" + (f"（另有基线 {len(baseline)} 条）" if baseline else ""))
        for n in new_red:
            print("   ✗", n)
        if not new_red:
            print("   ⚠️ 一条都没红 —— 这组断言发现不了这个缺陷！")
            if out:
                print(out[-1200:])
        return new_red, baseline
    finally:
        for path, text in originals.items():
            write(path, text)
        print("   （已还原生产代码）")


if __name__ == "__main__":
    unit_result = {}
    check_result = {}

    # ---------- A. 封装本身（跑单测） ----------

    unit_result["U1"] = experiment(
        "U1 TransientSecretKey.destroy() 去掉 fill(0)（引用照常置空、计数照常自增）",
        [(TSK, "        current.fill(0)\n        material = null",
          "        /* 对照实验 U1：故意不清零字节 */\n        material = null")],
        "unit", "U1")[0]

    unit_result["U2"] = experiment(
        "U2 TransientSecretKey.destroy() 去掉 material = null（字节照常清零）",
        [(TSK, "        current.fill(0)\n        material = null",
          "        current.fill(0)\n        /* 对照实验 U2：故意不断开引用 */")],
        "unit", "U2")[0]

    unit_result["U3"] = experiment(
        "U3 destroy() 里整段计数分派去掉（清零照常做）",
        [(TSK, """        when (algorithm) {
            AES -> scrubbedAesKeyCount.incrementAndGet()
            HMAC_SHA256 -> scrubbedHmacKeyCount.incrementAndGet()
            else -> Unit
        }""",
          "        /* 对照实验 U3：故意不计数 */")],
        "unit", "U3")[0]

    unit_result["U4"] = experiment(
        "U4 getEncoded() 改成每次复制一份（消灭这个类存在的理由）",
        [(TSK, "    override fun getEncoded(): ByteArray =\n        material ?: error(\"密钥副本已销毁，不能再交给 Cipher / Mac\")",
          "    override fun getEncoded(): ByteArray =\n        (material ?: error(\"密钥副本已销毁，不能再交给 Cipher / Mac\")).copyOf()")],
        "unit", "U4")[0]

    unit_result["U5"] = experiment(
        "U5 getAlgorithm() 写死 AES（两类实例在日志里就分不出来了）",
        [(TSK, "    override fun getAlgorithm(): String = algorithm",
          "    override fun getAlgorithm(): String = AES")],
        "unit", "U5")[0]

    unit_result["U6"] = experiment(
        "U6 未知算法分支改成喂 HMAC 探针（探针从此不能证明它声明的事）",
        [(TSK, "            else -> Unit",
          "            else -> scrubbedHmacKeyCount.incrementAndGet()")],
        "unit", "U6")[0]

    unit_result["U7"] = experiment(
        "U7 AeadCipher.seal 的 finally { secret.destroy() } 去掉",
        [(Aead, "            secret.destroy()\n        }",
          "            /* 对照实验 U7：故意不销毁 */\n        }")],
        "unit", "U7")[0]

    # ---------- B. 派生路径（跑自检判据） ----------

    check_result["S1"] = experiment(
        "S1 derivedRowIdFrom 走回 SecretKeySpec",
        [(VS, "        val secret = TransientSecretKey(material, HMAC_ALGORITHM)",
          "        val secret = javax.crypto.spec.SecretKeySpec(material, HMAC_ALGORITHM)")],
        "check", "S1")[0]

    check_result["S2"] = experiment(
        "S2 derivedRowIdFrom 去掉 finally { secret.destroy() }",
        [(VS, "        } finally {\n            // 唯一的清零点。放在 init 之后是安全的（Mac 在 init 就读完了密钥），\n"
              "            // 而 `finally` 保证\"中途抛异常也清\" —— 派生失败往往正是反复试错的时刻。\n"
              "            // 少了这一行不会有任何症状，所以它由 `scrubbedHmacKeyCount` 这条探针钉住。\n"
              "            secret.destroy()\n        }",
          "        } /* 对照实验 S2：故意不销毁 */")],
        "check", "S2")[0]

    check_result["S3"] = experiment(
        "S3 secret.destroy() 从 finally 挪到 try 体末尾（数量不变、异常路径漏清）",
        [(VS, "            mac.doFinal(tag).copyOf(MANIFEST_ID_BYTES).toHex()\n        } finally {\n"
              "            // 唯一的清零点。放在 init 之后是安全的（Mac 在 init 就读完了密钥），\n"
              "            // 而 `finally` 保证\"中途抛异常也清\" —— 派生失败往往正是反复试错的时刻。\n"
              "            // 少了这一行不会有任何症状，所以它由 `scrubbedHmacKeyCount` 这条探针钉住。\n"
              "            secret.destroy()\n        }",
          "            val derived = mac.doFinal(tag).copyOf(MANIFEST_ID_BYTES).toHex()\n"
          "            /* 对照实验 S3：故意挪出 finally —— 数量仍对得上，异常路径漏清 */\n"
          "            secret.destroy()\n            derived\n        }")],
        "check", "S3")[0]

    print("\n================ 汇总 ================")
    print("单测侧（基线 0 失败）：")
    for k, v in unit_result.items():
        flag = "OK" if len(v) == 1 else f"注意：{len(v)} 条"
        print(f"  {k}: {len(v)} 条变红  [{flag}]")
    print("自检侧（基线 1 条 FAIL = 反调试，已扣除）：")
    for k, v in check_result.items():
        flag = "OK" if len(v) == 1 else f"注意：{len(v)} 条"
        print(f"  {k}: {len(v)} 条变红  [{flag}]  {v}")
    sys.exit(0)
