"""一次性探针：把「点暂停」这件事的现场量清楚。

**不是走查的一部分**，也不产出证据 —— 它只回答三个问题：

  1. 那个按钮节点的**真实 bounds** 是多少？点它的圆心能不能中？
  2. 控制条可见时，「点下去」到「按钮翻成另一个字」之间有多长？
  3. `motion_probe` 读出 `0.0 px/s` 时，画面**到底停没停**？

第 3 个问题是关键：走查在 2026-09-18 的 B 轮里，attempt 2 的像素读数是 0.0，
而 3 秒后的正式连拍是全速 8.5 px/s。同一个测量在 3 秒里给出两个相反的结论，
说明**它有一个方向是假的**，而假在"停住"那一侧就会伪造通过。

用法（每步独立，方便按现场反应改下一步）：

    python tools/_probe_pause.py dump
    python tools/_probe_pause.py tap 79 2263
    python tools/_probe_pause.py key 4
    python tools/_probe_pause.py probe 4
"""
import importlib.util
import os
import sys
import time

BASE = r"D:\MixiaVault"


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


V = load(os.path.join(BASE, "tools", "walkthrough-v110-video.py"), "v110probe")
# 模块默认的 PHASE_TAG 是 "?"，而它会被拼进证据文件名（选图器判定帧）——
# "?" 在 Windows 上不是合法文件名字符，`os.replace` 会当场抛 WinError 123。
# 探针不产出证据，但走的还是那些函数，所以给个合法的短名。
V.PHASE_TAG = "probe"


def show_dump():
    ns = V.rnodes()
    print(f"节点总数 {len(ns)}")
    for n in ns:
        tag = []
        if n["text"]:
            tag.append(f'text={n["text"]!r}')
        if n["desc"]:
            tag.append(f'desc={n["desc"]!r}')
        if n["clickable"]:
            tag.append("clickable")
        print(f'  [{n["x1"]},{n["y1"]}][{n["x2"]},{n["y2"]}] '
              f'cx={n["cx"]} cy={n["cy"]} w={n["w"]} h={n["h"]} '
              f'cls={n["cls"].split(".")[-1]} ' + " ".join(tag))
    return ns


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "dump"
    if cmd == "dump":
        show_dump()
    elif cmd == "unlock":
        # 冷启动后解锁。**必须用走查那个带落字校验的 fill_field**：
        # 手写"点一下 + input text"会静默失败（2026-09-18 实测：框没聚焦、
        # 字打在空气里，界面回「密码不正确」—— 看着像密码错，其实是没填进去）。
        V.W.wait_text("主密码", timeout=90, what="解锁页")
        V.W.fill_field(0, V.W.MASTER)
        V.W.tap_text("解锁")
        V.W.wait_text("本机加密", timeout=90, what="解锁后的主页")
        print("已解锁")
    elif cmd == "tap":
        x, y = int(sys.argv[2]), int(sys.argv[3])
        V.W.tap(x, y)
        print(f"已点 ({x}, {y})")
    elif cmd == "key":
        V.W.adb("shell", "input", "keyevent", sys.argv[2])
        print(f"已发按键 {sys.argv[2]}")
    elif cmd == "probe":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 4
        st = V.motion_probe(n)
        print(f"运动读数：{V.motion_text(st)}")
        print(f"原始：{st}")
    elif cmd == "still":
        ns = V.rnodes()
        wants = [n for n in ns if n["desc"] in ("播放", "暂停")]
        print(f"播放/暂停按钮：{[(n['desc'], n['cx'], n['cy']) for n in wants]}")
        times = [n["text"].strip() for n in ns if n["text"].strip().count(":") == 1]
        print(f"时间类文本：{times}")
    elif cmd == "import":
        # 复用走查自己的导入路径 —— 自己另写一条的话，复现的就不是同一条路
        cells, blobs, im = V.await_picker()
        V.tap_picker_cells(cells, im)
        time.sleep(3)
        print(f"导入后页头项数：{V.media_count()}")
    elif cmd == "open":
        label = sys.argv[2] if len(sys.argv) > 2 else "2:00"
        V.open_cell_by_label(label)
        ok = V.wait_viewer(timeout=30)
        print(f"大图页起来了吗：{ok}")
    elif cmd == "reveal":
        ns = V.reveal_controls()
        wants = [(n["desc"], n["x1"], n["y1"], n["x2"], n["y2"])
                 for n in ns if n["desc"] in ("播放", "暂停")]
        print(f"reveal_controls 拿到的按钮 bounds：{wants}")
    elif cmd == "trial":
        # 走查原来的姿势：dump 找到按钮 → 立刻点 → 0.5s 后 dump 看按钮
        ns = V.reveal_controls()
        hit = next((n for n in ns if n["desc"] == "暂停"), None)
        if hit is None:
            print("没找到「暂停」按钮，本次试点作废")
            return
        print(f"「暂停」按钮 bounds=[{hit['x1']},{hit['y1']}][{hit['x2']},{hit['y2']}] "
              f"圆心=({hit['cx']},{hit['cy']})")
        t0 = time.time()
        V.W.tap(hit["cx"], hit["cy"])
        time.sleep(0.5)
        st = V.motion_probe(2)
        print(f"  点后 {time.time() - t0:.1f}s 像素：{V.motion_text(st)}")
        ns2 = V.rnodes()
        print(f"  点后 {time.time() - t0:.1f}s 按钮："
              f"{[(n['desc'], n['cx'], n['cy']) for n in ns2 if n['desc'] in ('播放', '暂停')]}")
    elif cmd == "blind":
        # 控制条亮着的时候，像素读数会不会被它遮成 0？
        # 现在应该处于"暂停且控制条常驻"。先恢复播放，再分两种状态各测一次。
        print("恢复播放（点画面正中的播放键 540,1200）")
        V.W.tap(540, 1200)
        time.sleep(0.4)
        for tag, wait in (("控制条亮着", 0.0), ("等它自己收起", 4.0)):
            time.sleep(wait)
            st = V.motion_probe(4)
            ns = V.rnodes()
            btns = [n["desc"] for n in ns if n["desc"] in ("播放", "暂停")]
            print(f"  [{tag}] 像素：{V.motion_text(st)}")
            print(f"  [{tag}] 按钮：{btns}")
    elif cmd == "autoresume":
        # 关键一问：暂停之后，应用会不会**自己**把播放恢复起来？
        # 如果会，那 audit5 那次「先读到 0.0、3 秒后又是全速」就说得通了，
        # 而且这是一条真缺陷（用户按了暂停，过一会儿影片自己又动了）。
        print("第 1 步：短间隔暂停")
        V.W.tap(540, 600)
        time.sleep(0.6)
        V.W.tap(79, 2263)
        time.sleep(0.5)
        t0 = time.time()
        for i in range(7):
            st = V.motion_probe(3)
            print(f"  +{time.time() - t0:5.1f}s  {V.motion_text(st)}")
            time.sleep(0.6)
        ns = V.rnodes()
        print("结束时的按钮："
              f"{[(n['desc'], n['cx'], n['cy']) for n in ns if n['desc'] in ('播放', '暂停')]}")
    elif cmd == "pause":
        # 直接跑走查里那个函数本体 —— 验的是"改完之后的它"，不是探针自己的复刻。
        print("先把影片退出再打开，回到「从头开始播」的干净状态")
        V.leave_viewer()
        time.sleep(1.0)
        V.open_cell_by_label("2:00")
        print(f"  大图页起来了吗：{V.wait_viewer(timeout=30)}")
        time.sleep(2.0)
        for i in range(1, 4):
            ns, did = V.tap_pause()
            descs = [n["desc"] for n in ns if n["desc"] in ("播放", "暂停")]
            times = [n["text"].strip() for n in ns if n["text"].strip().count(":") == 1]
            print(f"第 {i} 次 tap_pause → did_pause={did} 按钮={descs} 时间={times}")
            if i < 3:
                print("  再点画面正中的播放键恢复播放，好验下一轮")
                V.W.tap(540, 1200)
                time.sleep(1.0)
    elif cmd == "track":
        # 关键一问：应用报的播放位置和**画面上的白块位置**对不对得上？
        # 夹具里白块是线性单向移动、不绕回，所以屏幕质心可以换算成片内秒数：
        #     秒数 = 质心 / (条带宽 − 块宽) × 片长
        # 两个数应该是同步推进的。若"时钟在走、白块不动"，那是画面冻住了。
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 6
        print("点画面正中恢复播放（若本来就是播的，这一下会把它暂停 —— 先看一眼按钮）")
        ns = V.rnodes()
        descs = [d for d in (n_["desc"] for n_ in ns) if d in ("播放", "暂停")]
        print(f"  点击前按钮：{descs}")
        if not any(n_["desc"] == "暂停" for n_ in ns):
            V.W.tap(540, 1200)
            time.sleep(0.8)
        for i in range(n):
            rows = V.burst("motion", 4, out_dir=V.PROBE_DIR)
            xs = V.centroids(rows)
            st = V.motion_stats(xs, out_dir=V.PROBE_DIR)
            cxs = [c for _, c in xs["points"]]
            ns2 = V.rnodes()
            times = [n_["text"].strip() for n_ in ns2
                     if n_["text"].strip().count(":") == 1]
            descs2 = [d for d in (n_["desc"] for n_ in ns2) if d in ("播放", "暂停")]
            secs = V.implied_secs(cxs[0], xs["band_w"] or 1080, xs["block_w"] or 79) \
                if cxs and xs["band_w"] and xs["block_w"] else None
            secs_txt = f"{secs:.1f}s" if secs is not None else "量不到（这批帧里没有影片画面）"
            print(f"  #{i} 质心 {cxs} → 画面约 {secs_txt} ｜ 应用时钟 {times} ｜ 按钮 {descs2}")
            if st is not None:
                print(f"       {V.motion_text(st)}")
    elif cmd == "duel":
        # 决定性一问：**画面停住的那段时间里，应用自己报的播放位置在不在走？**
        #
        # 旧的 `track` 每轮只 dump 一次 `rnodes()` —— 而控制条在播放中 3.5 秒就
        # 自己收起，那一 dump 经常落在"已收起"上，于是时钟那一栏永远是 `[]`，
        # 整件事定不了案（2026-09-18 真机跑就是这样）。
        # 这里改成先 `reveal_controls()`（它落在 (540,600)，**避开画面正中那个
        # 播放键**，不会把影片又播起来），再拿它返回的那棵树读时钟 —— 一次 dump
        # 同时管"把控制条叫回来"和"读位置"，不会再撞上自动收起。
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 8
        # 先确保**在播**：读不到「暂停」就轻点画面正中 ——
        # 播放态下这一下只是切换控制条，暂停态下它正中那个大播放键会把影片续上。
        ns = V.rnodes()
        print(f"  开始前按钮：{[x['desc'] for x in ns if x['desc'] in ('播放', '暂停')]}")
        if not any(x["desc"] == "暂停" for x in ns):
            V.W.tap(540, 1200)
            time.sleep(1.0)
            ns = V.rnodes()
            print(f"  轻点正中之后：{[x['desc'] for x in ns if x['desc'] in ('播放', '暂停')]}")
        for i in range(n):
            t0 = time.time()
            c = V.clock_of(V.reveal_controls())
            rows = V.burst("motion", 4, out_dir=V.PROBE_DIR)
            xs = V.centroids(rows)
            cxs = [x for _, x in xs["points"]]
            bars = sum(1 for _, a in rows if a is not None and V.controls_visible(a))
            secs = V.implied_secs(cxs[0], xs["band_w"] or 1080, xs["block_w"] or 79) \
                if cxs else None
            secs_txt = f"{secs:.1f}s" if secs is not None else "量不到"
            print(f"  #{i} 应用时钟 {c or '读不到'} ｜ 画面 {secs_txt}"
                  f" ｜ 质心 {cxs} ｜ 控制条 {bars}/{len(rows)} 帧"
                  f" ｜ {time.time() - t0:.1f}s")
    elif cmd == "stress":
        # 探针本身可不可信：影片**确定在播**的时候连测 N 次，
        # 看会不会出现"4 帧质心完全相同"这种不可能的读数。
        # 如果会，那么 audit5 那次「先 0.0、3 秒后全速」就有了第二个解释，
        # 而且这条解释覆盖的不只是暂停那一处 —— 走查里所有运动判据都用它。
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 10
        V.leave_viewer()
        time.sleep(1.0)
        V.open_cell_by_label("2:00")
        print(f"重新打开影片：{V.wait_viewer(timeout=30)}")
        time.sleep(1.5)
        zero = 0
        for i in range(n):
            rows = V.burst("motion", 4, out_dir=V.PROBE_DIR)
            xs = V.centroids(rows)
            st = V.motion_stats(xs, out_dir=V.PROBE_DIR)
            cxs = [c for _, c in xs["points"]]
            flag = ""
            if st and st["ratio"] is not None and st["ratio"] <= V.STILL_RATIO:
                zero += 1
                flag = "  ← 读成「停住」"
            print(f"  #{i:02d} 质心 {cxs}")
            print(f"       {V.motion_text(st)}{flag}")
        print(f"共 {n} 次里读成「停住」的有 {zero} 次")
    elif cmd == "exp":
        # 短间隔姿势：先用坐标把控制条叫出来，**不插 dump**，紧接着点按钮。
        # 目的是把「坐标对不对」与「dump 太慢导致落点过期」这两件事分开。
        cx = int(sys.argv[2]) if len(sys.argv) > 2 else 79
        cy = int(sys.argv[3]) if len(sys.argv) > 3 else 2263
        print("第一步：轻点画面（540,600）把控制条叫出来")
        V.W.tap(540, 600)
        time.sleep(0.6)
        print(f"第二步：紧接着点按钮 ({cx},{cy})")
        t0 = time.time()
        V.W.tap(cx, cy)
        time.sleep(0.5)
        st = V.motion_probe(2)
        print(f"  点后 {time.time() - t0:.1f}s 像素：{V.motion_text(st)}")
        ns2 = V.rnodes()
        print(f"  点后 {time.time() - t0:.1f}s 按钮："
              f"{[(n['desc'], n['cx'], n['cy']) for n in ns2 if n['desc'] in ('播放', '暂停')]}")
    else:
        print(f"未知命令 {cmd}")


if __name__ == "__main__":
    main()
