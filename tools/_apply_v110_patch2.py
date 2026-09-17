#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二轮补丁：把"按体积认领素材"改成**不允许猜**，并把多块这一事实变成判据。

## 为什么必须改

`spec_for_size` 第一版遇到多个候选时**返回了第一个**。而两段素材只要都小于
1 MiB，分块落盘体积就完全一样（`24 + 1×(12+1MiB+16)`）—— 于是横屏那份密文
被拿竖屏的期望值去验，报出来的是"plainSize 不符 + 文件总长不符"，
**看起来像加密写错了**。这种"错得体面"的失败最费时间。

现在：候选数 ≠ 1 就直接失败，并在信息里点明"两段素材落在同一个块数档位、
素材要重做"。同时补一条判据，要求**至少有一段素材在设备上是多块的** ——
否则跨块偏移、块号参与 AAD、末块标记这一整套逻辑在这一轮里根本没被走过。
"""

import io
import sys

TARGET = r"D:\MixiaVault\tools\walkthrough-v110-video.py"

src = io.open(TARGET, encoding="utf-8").read()
orig = src
report = []


def replace_once(old, new, tag):
    global src
    n = src.count(old)
    if n != 1:
        sys.exit(f"[{tag}] 期望命中 1 处，实测 {n} 处 —— 未写盘")
    src = src.replace(old, new, 1)
    report.append(f"OK  {tag}")


# ---------- 1. spec_for_size → specs_for_size（返回全部候选） ----------

replace_once(
    'def spec_for_size(size_on_disk):\n'
    '    """按**落盘体积**认出这是哪一段素材；认不出来返回 None。\n',
    'def specs_for_size(size_on_disk):\n'
    '    """按**落盘体积**列出所有可能的素材；正常应当**恰好一个**。\n'
    '\n'
    '    返回列表而不是"第一个匹配"：返回单个值时，两段素材只要落在同一个\n'
    '    块数档位，就会静默地认领到错的那一段，而失败信息（plainSize 不符、\n'
    '    文件总长不符）看起来像"加密写错了"。宁可在认领这一步就响亮地失败。\n',
    "specs_for_size/文档",
)

replace_once(
    "    for spec in CLIPS.values():\n"
    '        if spec["plain_bytes"] > 0 \\\n'
    '                and expected_stored(spec["plain_bytes"], CHUNK_SIZE) == size_on_disk:\n'
    "            return spec\n"
    "    return None\n",
    "    return [spec for spec in CLIPS.values()\n"
    '            if spec["plain_bytes"] > 0\n'
    '            and expected_stored(spec["plain_bytes"], CHUNK_SIZE) == size_on_disk]\n'
    "\n"
    "\n"
    "def blob_chunks(plain_bytes):\n"
    '    """这份明文会被切成几块 —— 用来判断两条素材是否落在同一个块数档位。"""\n'
    '    return (plain_bytes + CHUNK_SIZE - 1) // CHUNK_SIZE\n',
    "specs_for_size/实现",
)

# ---------- 2. verify_stored_video 返回块数 ----------

replace_once(
    'def verify_stored_video(blob_id, size_on_disk, spec):\n'
    '    """对一个视频 blob 做全套存储层判据。"""\n',
    'def verify_stored_video(blob_id, size_on_disk, spec):\n'
    '    """对一个视频 blob 做全套存储层判据，返回它的块数（量不到时返回 None）。"""\n',
    "verify_stored_video/文档",
)

replace_once(
    '    note(f"{spec[\'tag\']}：明文/密文体积比",\n'
    '         f"{plain_size} → {len(data)}（+{len(data) - plain_size} 字节 = "\n'
    '         f"头 {CHUNK_HEADER_BYTES} + {chunk_count} 块 × (nonce 12 + tag 16)）")\n',
    '    note(f"{spec[\'tag\']}：明文/密文体积比",\n'
    '         f"{plain_size} → {len(data)}（+{len(data) - plain_size} 字节 = "\n'
    '         f"头 {CHUNK_HEADER_BYTES} + {chunk_count} 块 × (nonce 12 + tag 16)）")\n'
    "    return chunk_count\n",
    "verify_stored_video/返回块数",
)

# ---------- 3. 段 3：认领不许猜 + 多块判据 ----------

replace_once(
    "    claimed = {}\n"
    "    for blob_id, size in files.items():\n"
    "        spec = spec_for_size(size)\n"
    "        if spec is None:\n"
    '            want = " / ".join(\n'
    "                f\"{n}={expected_stored(s['plain_bytes'], CHUNK_SIZE)}\"\n"
    "                for n, s in CLIPS.items())\n"
    '            check("落盘体积能对上某一段素材", False, f"实测 {size}，两段应得 {want}")\n'
    "            continue\n"
    '        claimed[spec["tag"]] = blob_id\n'
    "        verify_stored_video(blob_id, size, spec)\n"
    '    check("两段影片各自对上自己的素材（不是互相配错）",\n'
    '          len(claimed) == 2, f"认领到 {sorted(claimed)}")\n',
    "    claimed, counts = {}, {}\n"
    "    for blob_id, size in files.items():\n"
    "        cands = specs_for_size(size)\n"
    "        if len(cands) != 1:\n"
    "            # 0 个 = 体积对不上任何素材；2 个 = 两条素材落在**同一个块数档位**\n"
    "            # （都小于 1 MiB 时必然如此），这时认领是猜的，必须停下来。\n"
    "            hint = (\n"
    '                " —— 两段素材的块数相同（"\n'
    '                + "、".join(f"{s[\'tag\']}={blob_chunks(s[\'plain_bytes\'])} 块"\n'
    "                            for s in CLIPS.values())\n"
    '                + "），落盘体积因此无法区分，素材需要重做" if len(cands) > 1 else "")\n'
    '            check("每个附件的落盘体积都能唯一对上某一段素材", False,\n'
    '                  f"{blob_id[:8]}… 是 {size}B，对上 {[c[\'tag\'] for c in cands]}"\n'
    '                  f"（应为 1 个）{hint}")\n'
    "            continue\n"
    '        spec = cands[0]\n'
    '        claimed[spec["tag"]] = blob_id\n'
    "        n = verify_stored_video(blob_id, size, spec)\n"
    "        if n is not None:\n"
    '            counts[spec["tag"]] = n\n'
    '    check("两段影片各自对上自己的素材（不是互相配错）",\n'
    '          len(claimed) == 2, f"认领到 {sorted(claimed)}")\n'
    "    # 一段素材如果只有一块，随机读取永远落在第 0 块里 —— 跨块边界的偏移算术、\n"
    "    # 块号参与 AAD、末块标记，这三样**一个都不会被走到**。所以这条不是凑数判据。\n"
    '    check("至少有一段素材在设备上落成多块（跨块读取才真的被走过）",\n'
    "          len(counts) == 2 and max(counts.values()) >= 2, f\"各段块数 {counts}\")\n",
    "段 3/认领不许猜 + 多块判据",
)

if "spec_for_size(" in src:
    sys.exit("还有对 spec_for_size 的调用 —— 未写盘")

io.open(TARGET, "w", encoding="utf-8", newline="\n").write(src)
print("\n".join(report))
print(f"\n写入 {TARGET}：{len(orig)} → {len(src)} 字符")
