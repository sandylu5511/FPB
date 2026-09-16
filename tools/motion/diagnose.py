#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断：导入一张「实况照片」之后，影片段到底还在不在？

## 为什么不能靠读代码判断

`ImagePipeline.prepare` 读的是 `contentResolver.openInputStream(uri)` 交出来的字节，
看起来"原样直存"就万事大吉。但**相册不是磁盘** —— 中间隔着 MediaProvider：
它会在交给你之前做转码（HEIC→JPEG 就是它干的），而实况照片正好是它认识的一种特殊形态。
"会不会被摘掉影片段"是 MediaProvider 的行为，不是我们代码的行为，只能实测。

## 判据

一张实况照片 = 图片部分 + 影片部分，两部分字节数在合成时就是已知的（见 make_motion_photo.py）。
导入后看应用私有目录里那份密文有多大：密文 = 明文 + 12 字节 nonce + 16 字节认证标签，
所以**密文尺寸能反推出明文尺寸**，不需要主密码就能判断影片段在不在。

    图片部分 27 870 B   →  保留影片段 31 441 B（31413+28）
    影片部分  3 543 B   →  被摘掉影片段 27 898 B（27870+28）

两者相差 3 543 B，不存在"看错"的空间。

## 依赖

只用 walkthrough.py（adb 封装）与 walkthrough-v105.py（引导流程）。
**刻意不加载 walkthrough-v106-part2.py** —— 它在模块顶层 `from PIL import Image`，
而托管的 Python 里没有 PIL，加载它会让整个脚本起不来。照片库那几个动作在这里内联。
"""

import importlib.util
import os
import re
import subprocess
import sys
import time

ADB = r"D:\AndroidSdk\platform-tools\adb.exe"
PKG = "com.fpb.vault"
BASE = r"D:\MixiaVault"
OUT = rf"{BASE}\dist\evidence\motion"
APK_DEBUG = rf"{BASE}\app\build\outputs\apk\debug\app-debug.apk"
MASTER = "VaultMaster2026x"

IMAGE_PART = 27_870
VIDEO_PART = 3_543
SEAL_OVERHEAD = 12 + 16  # AeadCipher: nonce + GCM tag
KEEP = IMAGE_PART + VIDEO_PART + SEAL_OVERHEAD   # 31 441
STRIP = IMAGE_PART + SEAL_OVERHEAD               # 27 898


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def sh(*args):
    p = subprocess.run([ADB, *args], capture_output=True)
    return p.stdout.decode("utf-8", "replace").replace("\r", "")


def attachments():
    """附件目录里每个密文的字节数。"""
    raw = sh("shell", "run-as", PKG, "ls", "-l", "files/attachments")
    sizes = {}
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) >= 8 and re.fullmatch(r"[0-9a-f]{32}", parts[-1]):
            sizes[parts[-1]] = int(parts[4])
    return sizes


def main():
    os.makedirs(OUT, exist_ok=True)
    # walkthrough-v105 在顶层就把 walkthrough.py 载入 sys.modules["walkthrough"]，
    # 复用同一个实例，避免出现两份模块各自的 OUT。
    V = load(rf"{BASE}\tools\walkthrough-v105.py", "V")
    W = sys.modules["walkthrough"]
    W.OUT = OUT
    W.SHOT_INDEX = 0

    # ---------- 相册里只留下那一张实况照片，让选图器的格子是确定的 ----------
    # 别的测试图先搬去 /data/local/tmp（不参与媒体扫描），不删，随时可搬回来。
    W.log("把相册里其它测试图搬走，只留实况照片")
    sh("shell", "mkdir", "-p", "/data/local/tmp/gallery-hold")
    for f in sh("shell", "ls", "/sdcard/Pictures").split():
        if f.endswith((".png", ".jpg")) and f != "motion-photo.jpg":
            sh("shell", "mv", f"/sdcard/Pictures/{f}", "/data/local/tmp/gallery-hold/")
            W.log(f"    搬走 {f}")
    sh("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
       "-d", "file:///sdcard/Pictures/motion-photo.jpg")
    time.sleep(2)
    W.log("    媒体库里现在的图片：")
    for line in sh("shell", "content", "query", "--uri",
                   "content://media/external/images/media",
                   "--projection", "_id:_display_name:_size").splitlines():
        W.log(f"      {line}")

    # ---------- 装 debug 包（release 包非 debuggable，run-as 取不到私有文件）----------
    W.log("安装 debug 包（会清空应用数据）")
    sh("uninstall", PKG)
    out = sh("install", APK_DEBUG)
    W.log(f"    {out.strip()}")
    assert "Success" in out, f"安装失败：{out}"

    # ---------- 走引导 ----------
    V.onboarding()

    # ---------- 进入照片库并导入 ----------
    W.log("进入照片库")
    hit = W.find("照片库")
    assert hit is not None, "主页顶栏找不到照片库入口"
    W.tap(hit["cx"], hit["cy"])
    W.wait_text("原图加密存储", timeout=25, what="照片库页")
    before = library_count(W)

    W.log("打开选图器")
    fab = next((n for n in W.nodes()
                if n["clickable"] and not (n["text"] or n["desc"]) and n["cy"] > 1900), None)
    assert fab is not None, "照片库里找不到「添加照片」按钮"
    W.tap(fab["cx"], fab["cy"])

    deadline = time.time() + 45
    cells = []
    while time.time() < deadline:
        cells = [n for n in W.nodes() if n["clickable"]
                 and not (n["text"] or n["desc"]) and 900 < n["cy"] < 1800]
        if cells:
            break
        d = W.find("Dismiss")
        if d:
            W.tap(d["cx"], d["cy"])
        time.sleep(1.5)
    assert cells, "选图器没有出现照片网格"
    W.log(f"    选图器里看到 {len(cells)} 个格子")

    cell = min(cells, key=lambda n: (n["cy"], n["cx"]))
    W.tap(cell["cx"], cell["cy"])
    time.sleep(1.5)
    done = W.find("Done") or W.find("完成")
    assert done is not None, "多选后没有出现 Done 按钮"
    W.tap(done["cx"], done["cy"])

    deadline = time.time() + 180
    last = None
    while time.time() < deadline:
        try:
            last = library_count(W)
        except RuntimeError:
            last = None
        if last is not None and (before is None or last > before):
            W.log(f"    页头：原图加密存储 · {last} 张（导入前 {before} 张）")
            break
        time.sleep(1.5)
    else:
        raise RuntimeError(f"照片库张数始终没变（导入前 {before}，最后读到 {last}）")
    time.sleep(2.0)
    W.shot("导入后-照片库")

    # ---------- 判据 ----------
    sizes = attachments()
    W.log(f"附件目录里有 {len(sizes)} 份密文")
    for blob, size in sorted(sizes.items(), key=lambda kv: -kv[1]):
        W.log(f"    {blob}  {size:>8,} B")

    kept = [b for b, s in sizes.items() if s == KEEP]
    stripped = [b for b, s in sizes.items() if s == STRIP]

    print()
    print("=" * 68)
    print(f"  源文件：图片部分 {IMAGE_PART:,} B + 影片部分 {VIDEO_PART:,} B = 31 413 B")
    print(f"  保留影片段 → 期望密文 {KEEP:,} B")
    print(f"  摘掉影片段 → 期望密文 {STRIP:,} B")
    print("-" * 68)
    got = sorted(sizes.values(), reverse=True)[0] if sizes else 0
    if kept:
        print(f"  实测密文 {got:,} B  →  ★ 影片段完整保留（{kept[0]}）")
    elif stripped:
        print(f"  实测密文 {got:,} B  →  ✗ 影片段被摘掉了（{stripped[0]}）")
    else:
        print(f"  实测密文 {got:,} B  →  ? 与两种预期都不符，要看实际字节")
    print("=" * 68)
    return 0


def library_count(W):
    hit = W.find("原图加密存储 · ")
    if not hit:
        return None
    m = re.search(r"(\d+)\s*张", hit["text"])
    return int(m.group(1)) if m else None


if __name__ == "__main__":
    sys.exit(main())
