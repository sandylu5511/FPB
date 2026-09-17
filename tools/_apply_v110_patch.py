#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 walkthrough-v110-video.py 改到"先暂停、再读数"那一版。

## 为什么要写成一个脚本

上一轮用 bash heredoc 直接在命令行里跑这段替换，heredoc 里嵌的引号把 bash 解析
搞崩了（`unexpected EOF while looking for matching '`）—— **整条命令没执行**，
而当时以为改完了。写成文件用 Write 工具落盘，就绕开了 shell 这一层。

## 为什么大段改写用"起止标记"而不是逐字照抄

第 5 段有 120 行。逐字照抄一遍再匹配，等于把"读的时候有没有抄错"变成新的失败源，
而失败信息还长得像"格式对不上"。改成按标记切段替换：只要标记找得到、
且找得到唯一一处，替换的就是那一整段。

任一替换不命中 → **整体不写盘**并退出 1（原子性）。宁可重来一次，也不能落一个
改了一半的脚本 —— 那种状态最难查。
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


def replace_between(start_marker, end_marker, new, tag):
    """把 [start_marker, end_marker) 之间的全部内容换成 new（两个标记都保留）。"""
    global src
    if src.count(start_marker) != 1:
        sys.exit(f"[{tag}] 起点标记命中 {src.count(start_marker)} 处 —— 未写盘")
    if src.count(end_marker) != 1:
        sys.exit(f"[{tag}] 终点标记命中 {src.count(end_marker)} 处 —— 未写盘")
    a = src.index(start_marker)
    b = src.index(end_marker)
    if b <= a:
        sys.exit(f"[{tag}] 终点在起点之前 —— 未写盘")
    src = src[:a] + new + src[b:]
    report.append(f"OK  {tag}（替换 {b - a} 字符）")


# ---------- 1. 文档：素材时长 + 新增"先暂停再读数"这一节 ----------

replace_once(
    "  · `landscape.mp4` 320×180 10 秒（16:9）—— 验常规路径\n",
    "  · `landscape.mp4` 320×180 60 秒（16:9）—— 验常规路径（长度是被第 5 段逼出来的，见下）\n",
    "文档/素材时长",
)

replace_once(
    "## 两轮各验什么\n",
    """## 控制条为什么必须"先停下、再读数"（这一轮踩出来的）

三条实测约束叠在一起，**播放态下根本读不到控制条**：

  · 轻点画面是 `controls = !controls` —— 它是个 **toggle**，不是"显示"。
    控制条已经在的时候再点一下，是把它**收起来**；
  · 控制条在**播放中 3.5 秒**后自动隐藏（暂停时不隐藏）；
  · 一次 `uiautomator dump` 要 1~3 秒。

于是"先点一下画面把控制条叫出来，再从容地找按钮"这个写法必然赛跑失败：
日志里出现过"控制条一个节点都没有"（`times = []`、`slider = None`），
几秒后又拿到了 `desc=['暂停']` —— 能不能读到完全看运气。

所以第 5 段整体改成**先把影片停下**：`tap_pause()` 每一轮只 dump 一次，
拿到「暂停」就立刻点，中间不插入任何别的读写；暂停后控制条常驻，
时间 / 总时长 / 进度条 / 拖动这些读数全部挪到那之后再取。

横屏素材从 30 秒加到 60 秒，也是被这一段逼的：打开 → 连拍取"在播" →
暂停 → 读数 → 拖到 30% → 恢复播放 → **切后台再回来**（这一段必须在"仍在播"
的状态下做）→ 再连拍，全程四十多秒。片子短了就会撞上"刚好播完"，
而"播完"会让按钮状态那一组断言**变成自己满足自己**。

## 两轮各验什么
""",
    "文档/新增控制条章节",
)

# ---------- 2. 素材预期：横屏 60 秒 ----------

replace_once(
    '        "duration_ms": 30_000,\n        "label": "0:30",\n',
    '        "duration_ms": 60_000,\n        "label": "1:00",\n',
    "CLIPS/横屏 60 秒",
)

# ---------- 3. 块大小提成常量 ----------

replace_once(
    'CHUNK_MAGIC = b"FPBCHK\\x01\\x00"\nCHUNK_HEADER_BYTES = 24\n',
    'CHUNK_MAGIC = b"FPBCHK\\x01\\x00"\nCHUNK_HEADER_BYTES = 24\n'
    "# 默认块大小（app 侧 `ChunkedBlobFormat.DEFAULT_CHUNK_BYTES = 1 shl 20`）。\n"
    "# 提成常量是因为它同时出现在\"断言头部字段\"与\"按体积认领素材\"两处 ——\n"
    "# 各写一个 `1 << 20` 的话，改一处忘一处就是一条说不清的失败。\n"
    "CHUNK_SIZE = 1 << 20\n",
    "常量/CHUNK_SIZE",
)

replace_once(
    '    check(f"{spec[\'tag\']}：chunkSize 是 1 MiB（与默认块大小一致）",\n'
    "          chunk_size == 1 << 20, f\"{chunk_size}\")\n",
    '    check(f"{spec[\'tag\']}：chunkSize 与默认块大小一致（{CHUNK_SIZE} = 1 MiB）",\n'
    '          chunk_size == CHUNK_SIZE, f"{chunk_size}")\n',
    "判据/chunkSize 用常量",
)

replace_once(
    '    note(f"{spec[\'tag\']}：明文/密文体积比",\n'
    '         f"{plain_size} → {len(data)}（+{len(data) - plain_size} 字节，"\n'
    '         f"即头 24 + nonce 12 + tag 16 = 52，单块）")\n',
    '    note(f"{spec[\'tag\']}：明文/密文体积比",\n'
    '         f"{plain_size} → {len(data)}（+{len(data) - plain_size} 字节 = "\n'
    '         f"头 {CHUNK_HEADER_BYTES} + {chunk_count} 块 × (nonce 12 + tag 16)）")\n',
    "记录/体积比不再写死单块",
)

# ---------- 4. 新增 spec_for_size ----------

replace_once(
    'def expected_stored(plain_size, chunk_size):\n'
    '    """分块格式落盘后的字节数：24 字节明文头 + 每块 (12 nonce + 密文 + 16 tag)。"""\n'
    "    count = (plain_size + chunk_size - 1) // chunk_size\n"
    "    return CHUNK_HEADER_BYTES + count * (12 + chunk_size + 16)\n",
    'def expected_stored(plain_size, chunk_size):\n'
    '    """分块格式落盘后的字节数：24 字节明文头 + 每块 (12 nonce + 密文 + 16 tag)。"""\n'
    "    count = (plain_size + chunk_size - 1) // chunk_size\n"
    "    return CHUNK_HEADER_BYTES + count * (12 + chunk_size + 16)\n"
    "\n"
    "\n"
    "def spec_for_size(size_on_disk):\n"
    '    """按**落盘体积**认出这是哪一段素材；认不出来返回 None。\n'
    "\n"
    "    不能按\"体积小的当横屏、大的当竖屏\"排序配对 —— 那只是旧素材的巧合\n"
    "    （横屏 10 秒确实比竖屏 8 秒短）。横屏加到 60 秒之后大小关系反转，\n"
    "    于是横屏那份密文被拿竖屏的期望值去验，报出来的是\n"
    "    \"plainSize 不符 + 文件总长不符\"：看着像加密写错了，其实是配错了对象。\n"
    "\n"
    "    两段素材的明文长度不同，各自的落盘体积也就不同，可以唯一认领。\n"
    "    注意这只是**用来选对象**：认领之后头部里的 plainSize / chunkCount /\n"
    "    总长仍然逐条对着本地源文件核，不是自证。\n"
    '    """\n'
    "    for spec in CLIPS.values():\n"
    '        if spec["plain_bytes"] > 0 \\\n'
    '                and expected_stored(spec["plain_bytes"], CHUNK_SIZE) == size_on_disk:\n'
    "            return spec\n"
    "    return None\n",
    "新增 spec_for_size",
)

# ---------- 5. ensure_playing / show_controls → tap_pause / current_page ----------

replace_between(
    "def ensure_playing():\n",
    "def open_cell_by_label(label):\n",
    '''def tap_pause(rounds=6):
    """把影片点成**暂停**，返回 (暂停后的节点树, 这次是否真的点了「暂停」)。

    ## 为什么不"先把控制条叫出来，再从容地找按钮"

    三条实测约束叠在一起，播放态下读控制条是**做不到**的：

      · 轻点画面是 `controls = !controls` —— 它是个 **toggle**。控制条已经在的时候
        再点一下，是把它**收起来**，不是"显示"；
      · 控制条在播放中 3.5 秒后自动隐藏（暂停时不隐藏）；
      · 一次 `rnodes()` 要 1~3 秒。

    上一版就是这么写的：先 `show_controls()` 再 dump 再找按钮。日志实证
    "控制条一个节点都没有"（`times = []`、`slider = None`），而几秒后又拿到了
    `desc=['暂停']` —— 读数窗口完全取决于运气。

    ## 现在的写法：每轮只 dump 一次，拿到「暂停」就立刻点

    中间不插入任何别的读写。控制条被收起来时轻点画面把它叫回来，落点刻意取
    `VIEW_H // 4` —— **避开画面正中那个播放键**：暂停态下那个键就在正中，
    点在它身上会把影片又播起来（那就成了"点暂停反而开始播"）。

    返回的第二项是"这次真的执行了暂停动作"。下面那条"点暂停之前确实处于播放态"
    的判据直接用它 —— 否则影片若已播完，按钮本来就是「播放」，
    "点完变成播放"无论点不点都成立，断言会**自己满足自己**。
    """
    for i in range(rounds):
        ns = rnodes()
        if any(n["desc"] == "暂停" for n in ns):
            hit = next(n for n in ns if n["desc"] == "暂停")
            W.log(f"    第 {i + 1} 轮读到「暂停」，点它（{hit['cx']},{hit['cy']}）")
            W.tap(hit["cx"], hit["cy"])
            time.sleep(0.8)
            return rnodes(), True
        if any(n["desc"] == "播放" for n in ns):
            W.log(f"    第 {i + 1} 轮读到「播放」—— 影片已经是停下状态")
            return ns, False
        W.log(f"    第 {i + 1} 轮没读到控制条，轻点画面把它叫回来")
        W.adb("shell", "input", "tap", str(VIEW_W // 2), str(VIEW_H // 4))
        time.sleep(0.6)
    W.log("    ⚠ 多轮都没能读到控制条")
    return rnodes(), False


def current_page():
    """大图页顶栏那个「n / m」页码；读不到返回 None。

    用它来分辨两种长得很像的失败：进度条那条**横向**滑动如果被外层
    `HorizontalPager` 抢走，表现是"翻到下一张"，而不是"拖不动"。
    """
    return next((n["text"].strip() for n in rnodes()
                 if re.fullmatch(r"\\d+ / \\d+", n["text"].strip())), None)


''',
    "替换 ensure_playing → tap_pause/current_page",
)

replace_between(
    "def show_controls():\n",
    "def grant_screenshots():\n",
    "",
    "删除 show_controls",
)

# ---------- 6. 角标定位的文档 + 段 4 的读数 ----------

replace_once(
    '而时长是从容器里读出来的**事实** —— 10 秒的那段就是横屏那一段。\n',
    '而时长是从容器里读出来的**事实** —— 1:00 的那段就是横屏那一段。\n',
    "文档/open_cell_by_label",
)

replace_once(
    '    check("两个格子上都有时长角标，数值来自容器而不是猜的（0:30 与 0:08）",\n'
    '          labels == {"0:30", "0:08"}, f"实测角标 {sorted(labels)}")\n',
    '    check("两个格子上都有时长角标，数值来自容器而不是猜的"\n'
    "          f\"（{CLIPS['landscape.mp4']['label']} 与 {CLIPS['portrait.mp4']['label']}）\",\n"
    '          labels == {CLIPS["landscape.mp4"]["label"], CLIPS["portrait.mp4"]["label"]},\n'
    '          f"实测角标 {sorted(labels)}")\n',
    "判据/时长角标读 CLIPS",
)

# ---------- 7. 段 3：按落盘体积认领 ----------

replace_between(
    "    # ---- 3. 存储层：落盘格式 ----\n",
    "    # ---- 4. 播放：几何 + 在播 ----\n",
    '''    # ---- 3. 存储层：落盘格式 ----
    files = attachments()
    W.log(f"沙箱 files/attachments/ 下有 {len(files)} 个文件："
          + "，".join(f"{k[:8]}…={v}B" for k, v in files.items()))
    check("沙箱里确实落盘了两个附件密文", len(files) == 2, f"{len(files)} 个")

    # 把"哪个 blob 是哪段影片"对上：**按落盘体积认领**，不按大小排序配对
    # （原因见 spec_for_size：横屏加长之后大小关系会反转，配对就错了）。
    claimed = {}
    for blob_id, size in files.items():
        spec = spec_for_size(size)
        if spec is None:
            want = " / ".join(
                f"{n}={expected_stored(s['plain_bytes'], CHUNK_SIZE)}"
                for n, s in CLIPS.items())
            check("落盘体积能对上某一段素材", False, f"实测 {size}，两段应得 {want}")
            continue
        claimed[spec["tag"]] = blob_id
        verify_stored_video(blob_id, size, spec)
    check("两段影片各自对上自己的素材（不是互相配错）",
          len(claimed) == 2, f"认领到 {sorted(claimed)}")

''',
    "段 3/按体积认领",
)

# ---------- 8. 段 4：角标改成从 CLIPS 取 ----------

replace_once(
    '    open_cell_by_label("0:30")\n',
    '    open_cell_by_label(CLIPS["landscape.mp4"]["label"])\n',
    "段 4/打开横屏那格",
)

# ---------- 9. 段 5：整体重写为"先停下、再读数" ----------

replace_between(
    "    # ---- 5. 控制条：暂停 / 播放 ----\n",
    "    # ---- 5.5 切后台再回来：Surface 会重建 ----\n",
    '''    # ---- 5. 控制条：先把影片停下，再读数 ----
    #
    # 这一段整体是"暂停优先"的（原因见 tap_pause 的注释）：控制条在播放中 3.5 秒
    # 就自己收起来，而轻点画面是 toggle —— 播放态下读数只能靠运气。
    # 停下之后控制条常驻，时间、总时长、进度条、拖动才都是可重复的读数。
    ns, did_pause = tap_pause()

    # 这一条是下面那一组的**前提**：影片若已播完，按钮本来就是「播放」，
    # 于是"点完暂停后按钮变成播放"无论点不点都成立。前提不成立时要响，不要绿。
    check("点暂停之前确实处于播放态（否则这一组判据会被「播完」顶成假通过）",
          did_pause,
          "读到「暂停」并点了下去" if did_pause
          else f"读到的是 {[n['desc'] for n in ns if n['desc'] in ('播放', '暂停')]}")

    paused = next((n for n in ns if n["desc"] == "播放"), None)
    check("点暂停后按钮变成「播放」（状态真的切了）", paused is not None,
          f"节点 desc {[n['desc'] for n in ns if n['desc'] in ('播放', '暂停')]}")

    # 画面里的白块必须真的停住：按钮变成「播放」只说明 Compose 的状态翻了，
    # **解码器有没有停**才是画面上的事实。
    rows = burst("B-横屏-暂停后", BURST_PAUSE)
    xs = []
    for p, a in rows:
        if a is None:
            continue
        b = band_of(a)
        if not b:
            continue
        blk = block_x(a, b)
        if blk:
            xs.append((os.path.basename(p), round(blk["cx"], 1)))
    W.log(f"    暂停后各帧白块质心：{xs}")
    if len(xs) >= 2:
        spread = max(x[1] for x in xs) - min(x[1] for x in xs)
        check("暂停后画面停住（白块位置不再变化）", spread <= 3.0,
              f"{len(xs)} 个读数，极差 {spread:.1f}px")
    else:
        check("暂停后画面停住", False, f"只量到 {len(xs)} 个读数")
    W.shot("横屏-已暂停")

    # 暂停态把控制条一次读全（此时它常驻，不必跟 3.5 秒赛跑）
    spec_l = CLIPS["landscape.mp4"]
    times = [n["text"].strip() for n in ns if re.fullmatch(r"\\d+:\\d\\d", n["text"].strip())]
    check("控制条上显示了当前时间与总时长", len(times) >= 2, f"读到 {times}")
    check(f"总时长读出来是 {spec_l['label']}"
          f"（容器时长 {spec_l['duration_ms'] // 1000} 秒，不是猜的）",
          spec_l["label"] in times, f"读到 {times}")

    slider = next((n for n in ns if n["cls"] == "SeekBar"), None)
    if slider is None:
        slider = next((n for n in ns
                       if n["clickable"] and n["w"] > 500 and n["cy"] > 2100), None)
    W.log(f"    进度条节点："
          f"{slider if slider is None else {k: slider[k] for k in ('cls', 'x1', 'y1', 'x2', 'y2')}}")
    check("控制条上有可拖的进度条", slider is not None, "找不到 SeekBar 形状的节点")

    # 拖之前先记下页码：进度条是**横向**拖动，而外层是 HorizontalPager ——
    # 万一这条滑动被 Pager 抢走，表现就是"翻到下一张"，而不是"拖不动"。
    # 记下来才能一眼分辨这两种失败。
    page_before = current_page()

    # 真的拖一把。Compose 的 Slider 按下即跳到触点，起点只要落在轨迹上就行，
    # 不必正好压在滑块上。落点取 30% 而不是 70%：紧接着还有
    # "恢复播放 → 切后台再回来 → 再验一次在播"，那一段要留足剩余片长
    # （60 秒的 30% 处还剩 42 秒）。
    seek_ok = False
    if slider is not None:
        y = slider["cy"]
        x_from = slider["x1"] + int(0.05 * slider["w"])
        x_to = slider["x1"] + int(0.30 * slider["w"])
        W.log(f"    拖进度条：({x_from},{y}) → ({x_to},{y})")
        W.adb("shell", "input", "swipe", str(x_from), str(y), str(x_to), str(y), "700")
        time.sleep(1.0)
        after = rnodes()
        left = [n["text"].strip() for n in after
                if re.fullmatch(r"\\d+:\\d\\d", n["text"].strip())]
        W.log(f"    拖动后控制条时间：{left}")
        page_after = current_page()
        if page_after != page_before:
            W.log(f"    ⚠ 页码从 {page_before} 变成了 {page_after} —— "
                  f"这条横滑被外层 Pager 抢走了，不是进度条没生效")

        # 目标位置按素材时长算，不写死秒数：拖到 30% 处，允许 ±3 秒的落点误差。
        def _secs(t):
            m, sec = t.split(":")
            return int(m) * 60 + int(sec)

        target = spec_l["duration_ms"] / 1000.0 * 0.30
        seek_ok = any(abs(_secs(t) - target) <= 3 for t in left)
        check(f"拖动进度条能跳到目标位置（拖到 30% 后当前时间应落在"
              f" {target - 3:.0f}~{target + 3:.0f} 秒）",
              seek_ok, f"拖动后读到 {left}")
        check("拖进度条没有被外层翻页手势抢走（页码不变）",
              page_after == page_before, f"{page_before} → {page_after}")
        W.shot("横屏-拖到30%")

    # 再点播放：白块重新动起来。**优先落在控制条那个按钮上** —— 暂停态下画面正中
    # 还有一个同样叫「播放」的大按钮；点它虽然也能播起来，但那是"点画面"，
    # 验不到控制条那一侧的联动。用 cy 区分（控制条贴屏幕底边）。
    ns = rnodes()
    play_btn = next((n for n in ns if n["desc"] == "播放" and n["cy"] > 2100), None)
    if play_btn is None:
        play_btn = next((n for n in ns if n["desc"] == "播放"), None)
        W.log("    ⚠ 控制条上的播放键没读到，退而点画面正中那个")
    if play_btn is not None:
        W.tap(play_btn["cx"], play_btn["cy"])
        time.sleep(0.5)
    rows = burst("B-横屏-再播", BURST_PAUSE + 2)
    xs = []
    for p, a in rows:
        if a is None:
            continue
        b = band_of(a)
        if not b:
            continue
        blk = block_x(a, b)
        if blk:
            xs.append((os.path.basename(p), round(blk["cx"], 1)))
    W.log(f"    恢复播放后各帧白块质心：{xs}")
    # 这一项还要给 5.5 段当前提：Surface 重建后播放器只在**播放中**才重绘，
    # 暂停态重建本来就该是黑的（那是播放器的正常行为，不是缺陷）。
    resumed = len(xs) >= 2 and (max(x[1] for x in xs) - min(x[1] for x in xs)) > 20
    if len(xs) >= 2:
        spread = max(x[1] for x in xs) - min(x[1] for x in xs)
        check("点「播放」后画面重新动起来（可暂停、可恢复）", spread > 20,
              f"{len(xs)} 个读数，极差 {spread:.1f}px")
    else:
        check("点「播放」后画面重新动起来", False, f"只量到 {len(xs)} 个读数")

''',
    "段 5/重写为暂停优先",
)

# ---------- 10. 段 5.5：加页码判据 + 前置条件 + 续播分支 ----------

replace_between(
    "    # ---- 5.5 切后台再回来：Surface 会重建 ----\n",
    '    check("解码器被创建过（logcat）"',
    '''    # ---- 5.5 切后台再回来：Surface 会重建 ----
    #
    # 这一条验的是一处**只在"切出去看一眼再回来"时才发作**的隐患，而它的成因很反直觉：
    # SurfaceView 被销毁时我们释放播放器，而 `MediaPlayer.release()` 会顺着 JNI
    # 回调 `MediaDataSource.close()`；如果那条 close 顺手把**读取器**也关了，
    # 那么回来时新建的播放器就会拿着一个已关闭的文件句柄去 setDataSource →
    # 抛异常 → 界面显示「这段视频读不出来」。
    #
    # 用户看到的现象是"我切出去回个消息，回来视频就坏了，重启才恢复" ——
    # 这类问题在主动走查里**永远不会出现**（走查不会去按 HOME），必须专门造出这个场景。
    #
    # 前提是**此刻在播**：Surface 重建后播放器只在播放时才重绘，暂停态重建本来
    # 就该是黑的。上一版就在这儿白追了一轮"读取器被误关"—— 真实原因是前一步的
    # "恢复播放"根本没执行到（控制条读不到），影片一直停在暂停态。
    check("切后台之前影片确实在播（上一条连拍已证明画面在动）",
          resumed, "播放中" if resumed else "上一步没量到运动，这一段的结论无效")

    page_before_home = current_page()
    W.log("按 HOME 切到后台，再切回来（Surface 会走一遍 destroyed → created）")
    W.adb("shell", "input", "keyevent", "3")   # KEYCODE_HOME
    time.sleep(2.5)
    W.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(3.0)
    rows = burst("B-横屏-后台返回", 4)
    back = [(p, a, band_of(a)) for p, a in rows if a is not None]
    alive = [t for t in back if t[2]]
    check("切后台再回来后画面上还有影片（播放器重建时读取器没被误关）",
          len(alive) > 0, f"{len(back)} 帧里 {len(alive)} 帧有影片画面")
    # 切出去看一眼不该让用户"回来发现自己翻到别的影片了"。页数只有 2 页，
    # 而这一条盯的正是"退到后台/回前台"这条路会不会把 Pager 的位置弄丢。
    page_after_home = current_page()
    check("切后台再回来仍停在原来那一页（没被翻到别的影片）",
          page_after_home == page_before_home,
          f"{page_before_home} → {page_after_home}")
    check("没有出现「这段视频读不出来」（那正是读取器被误关的表现）",
          W.find("读不出来") is None,
          f"界面文字：{[n['text'] for n in rnodes() if n['text']][:8]}")

    # 返回后**真的再播一次**：只是"画面还在"不足以证明读取器可用（末帧是静态的），
    # 必须让解码器重新去读一遍文件。这一步是这条判据的核心。
    ns = rnodes()
    if any(n["desc"] == "暂停" for n in ns):
        note("切回来时的状态", "已经在播（Surface 重建后按用户意图自动续播）")
    else:
        glyph = (next((n for n in ns if n["desc"] == "播放" and n["cy"] < 2100), None)
                 or next((n for n in ns if n["desc"] == "播放"), None))
        assert glyph is not None, "切回来后既不在播、也找不到播放键"
        W.log(f"    切回来是暂停态，点播放键（{glyph['cx']},{glyph['cy']}）重新播一遍")
        W.tap(glyph["cx"], glyph["cy"])
        time.sleep(0.6)
    rows = burst("B-横屏-返回后重播", 5)
    xs = []
    for p, a in rows:
        if a is None:
            continue
        b = band_of(a)
        if not b:
            continue
        blk = block_x(a, b)
        if blk:
            xs.append((os.path.basename(p), round(blk["cx"], 1)))
    W.log(f"    返回后重播各帧白块质心：{xs}")
    if len(xs) >= 2:
        spread = max(x[1] for x in xs) - min(x[1] for x in xs)
        check("返回后还能重新播放（画面真的在动，说明读取器仍可用）", spread > 20,
              f"{len(xs)} 个读数，极差 {spread:.1f}px")
    else:
        check("返回后还能重新播放", False, f"只量到 {len(xs)} 个读数")

''',
    "段 5.5/加页码与前提",
)

# ---------- 落盘 ----------

# 守卫要盯的是**实现与调用**，不是文档里的提及：
# 新增的注释里刻意写了"上一版是 `show_controls()` 这么写的，所以才要改"——
# 那是要留下的教训，不该被守卫当成残留。
residue = [p for p in ("def show_controls()", "def ensure_playing()",
                       "\n    show_controls()", "\n    ensure_playing()")
           if p in src]
if residue:
    sys.exit(f"还有残留的实现/调用 {residue} —— 未写盘")
if "0:30" in src:
    sys.exit("还有残留的 0:30 —— 未写盘")
if "1:00" not in src or "60_000" not in src:
    sys.exit("横屏素材的 60 秒没落上 —— 未写盘")

io.open(TARGET, "w", encoding="utf-8", newline="\n").write(src)
print("\n".join(report))
print(f"\n写入 {TARGET}：{len(orig)} → {len(src)} 字符")
