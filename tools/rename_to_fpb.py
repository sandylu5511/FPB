"""把工程从「密匣 / com.mixia.app」迁移到「FPB / com.fpb.vault」。

刻意**不动**的东西（都是磁盘格式的一部分，改了等于让已有密文作废）：
  - Aad 里的 "mixia:v1:..." 命名空间前缀
  - VaultSession.MANIFEST_MAC_INPUT = "mixia:index:v1"
  - VaultSession.WARMUP_PASSWORD
  - VaultKeyFileCodec.MAGIC = "MXVK"
  - SQLite 文件名 vault.db

只改：Kotlin 包名 / applicationId / namespace / 资源里的显示名 / 注释与用户可见文案里的「密匣」。
"""

import os
import re
import shutil
import sys

ROOT = r"D:\MixiaVault"

OLD_PKG = "com.mixia.app"
NEW_PKG = "com.fpb.vault"

OLD_DIR = os.path.join(ROOT, "app", "src", "main", "java", *OLD_PKG.split("."))
NEW_DIR = os.path.join(ROOT, "app", "src", "main", "java", *NEW_PKG.split("."))
OLD_DIR_T = os.path.join(ROOT, "app", "src", "test", "java", *OLD_PKG.split("."))
NEW_DIR_T = os.path.join(ROOT, "app", "src", "test", "java", *NEW_PKG.split("."))

# 这些常量是磁盘格式的一部分，替换脚本必须绕开它们
PROTECTED = [
    '"mixia:index:v1"',
    '"mixia-kdf-warmup"',
    '"mixia:v$SCHEMA',
    'MXVK',
]

TEXT_EXT = {".kt", ".kts", ".xml", ".pro", ".properties", ".json", ".md"}


def collect_files():
    out = []
    for base in (
        os.path.join(ROOT, "app", "src"),
        os.path.join(ROOT, "app"),
        os.path.join(ROOT, ""),
    ):
        pass
    # 明确列举，避免误改设计原型与文档
    out.append(os.path.join(ROOT, "app", "build.gradle.kts"))
    out.append(os.path.join(ROOT, "settings.gradle.kts"))
    out.append(os.path.join(ROOT, "app", "proguard-rules.pro"))
    for dirpath, _dirs, files in os.walk(os.path.join(ROOT, "app", "src")):
        for name in files:
            if os.path.splitext(name)[1] in TEXT_EXT:
                out.append(os.path.join(dirpath, name))
    return sorted(set(out))


def transform(text: str) -> str:
    """先取出受保护片段，替换完再放回去。"""
    placeholders = {}
    for i, token in enumerate(PROTECTED):
        if token in text:
            key = f"\x00KEEP{i}\x00"
            placeholders[key] = token
            text = text.replace(token, key)

    text = text.replace(OLD_PKG, NEW_PKG)
    text = text.replace("Theme.MixiaVault", "Theme.FPB")
    text = text.replace("密匣", "FPB")

    for key, token in placeholders.items():
        text = text.replace(key, token)
    return text


def main():
    changed = []
    for path in collect_files():
        try:
            raw = open(path, "r", encoding="utf-8").read()
        except (UnicodeDecodeError, IsADirectoryError):
            continue
        new = transform(raw)
        if new != raw:
            open(path, "w", encoding="utf-8", newline="").write(new)
            changed.append(os.path.relpath(path, ROOT))

    print(f"内容已更新 {len(changed)} 个文件：")
    for c in changed:
        print("  ", c)

    for old, new in ((OLD_DIR, NEW_DIR), (OLD_DIR_T, NEW_DIR_T)):
        if os.path.isdir(old):
            os.makedirs(os.path.dirname(new), exist_ok=True)
            if os.path.isdir(new):
                shutil.rmtree(new)
            shutil.move(old, new)
            print(f"目录迁移: {os.path.relpath(old, ROOT)} -> {os.path.relpath(new, ROOT)}")
        else:
            print(f"!! 目录不存在: {old}")

    # 清掉迁移后留下的空目录树
    for leaf in ("crypto", "data", "codec", "model", "session", "diagnostics", "ui", "vault"):
        pass
    for parent in (os.path.join(ROOT, "app", "src", "main", "java", "com", "mixia"),
                   os.path.join(ROOT, "app", "src", "test", "java", "com", "mixia")):
        if os.path.isdir(parent):
            for dirpath, dirnames, filenames in os.walk(parent, topdown=False):
                if not os.listdir(dirpath):
                    os.rmdir(dirpath)
                    print(f"清空目录: {os.path.relpath(dirpath, ROOT)}")


if __name__ == "__main__":
    sys.exit(main())
