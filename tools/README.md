# tools/ —— 开发期辅助工具

这个目录位于 `app` 模块之外，Gradle 不会把这里的任何内容打包进 APK。

## Argon2Bench.java

Argon2id 性能基准，用来确定 KDF 参数档位 —— **不靠猜**。

### 为什么需要它

密码哈希的内存开销（m）和迭代次数（t）同时决定两件事：

- 攻击者离线暴力破解的成本
- 用户每次解锁要等多久

两者此消彼长，所以必须先在目标硬件上量出耗时曲线，再挑一个
"破解成本足够高、用户又感觉不到"的点。拍脑袋定参数的结果通常是
要么形同虚设（m=8 MiB），要么每次解锁卡三秒被用户骂。

### 运行方式

本机只有一个 JDK（Android Studio 自带的 JBR 25），BouncyCastle 来自 Gradle 缓存。

Git Bash：

```bash
JAVA="/c/Program Files/Android/Android Studio/jbr/bin/java.exe"
BC='C:\Users\<用户名>\.gradle\caches\modules-2\files-2.1\org.bouncycastle\bcprov-jdk18on\1.77\<hash>\bcprov-jdk18on-1.77.jar'
"$JAVA" -Xmx2g -cp "$BC" 'D:\MixiaVault\tools\Argon2Bench.java'
```

`-Xmx2g` 不能省：256 MiB 档位的 Argon2 会在堆上分配同等大小的内存块，
默认堆上限可能不够（JDK 25 默认堆 = 物理内存 / 4，通常够，但低内存机器会踩坑）。

### 实测数据（2026-09-14，PC，JDK 25，BouncyCastle 1.77 纯 Java 实现）

Java 单次派生 32 字节，预热后取 3 次平均：

| 参数档位 | 平均 | 最快 |
|---|---|---|
| 32 MiB / t2 / p1 | 44 ms | 40 ms |
| **64 MiB / t3 / p2** | **155 ms** | 144 ms |
| 64 MiB / t3 / p4 | 146 ms | 145 ms |
| 128 MiB / t4 / p2 | 479 ms | 432 ms |
| 256 MiB / t4 / p2 | 1002 ms | 989 ms |

### 由此得出的两个结论

**1. 默认档定为 64 MiB / t3 / p2**（对应 `KdfParams.standard()`）：

- PC 上 155 ms，按手机端 3~5 倍劣化估算约 0.5~0.8 秒 —— 用户可感知但不烦
- 是 OWASP 对 Argon2id 推荐下限（19 MiB）的三倍多
- 对比：如果用户选了 256 MiB 档，手机上要 3~5 秒，每次解锁都像卡死

**2. 不需要引入 native 实现**（如 argon2kt）：

纯 Java 已经够快，而 native 库有两个实际代价 —— 单元测试无法覆盖生产代码
（`.so` 在 JVM 测试环境加载不了，只能靠仪器测试），以及多一份 ABI 打包负担。

### 真机数据从哪里来

**目前没有应用内的读出口。** 原先输出标准档实际耗时的"加密内核自检"
（`diagnostics/CryptoSelfTest.kt`，整包 545 行）在 2026-09-17 的零引用清理里被整体删除
（依据见 `dist/evidence/audit4/README.md` 6.1）。生产代码里**没有任何 KDF 计时打点**，
所以上面这些数字目前只有 PC 侧一份，真机耗时只能从解锁手感间接判断。

若确实需要真机数字，最小做法是在解锁路径上临时打点、用完撤掉；
**不要把自检包请回来** —— 它当初被删，正是因为零引用。

---

## 走查 / 验收脚本

`walkthrough.py` 是公共底座（装包、点击、输入、`uiautomator` 取层级、截图、断言的封装），
`uitest.py` 是最早的单体脚本。其余按版本号成对出现，**每一版的脚本都原样保留**，
因为"当时是怎么判的"本身就是要留档的东西。

| 脚本 | 覆盖 |
|---|---|
| `walkthrough-v102*.py` ~ `v104.py` | 早期里程碑走查 |
| `walkthrough-v105.py` / `-part2.py` | 深色模式可读性；大图左右滑动 |
| `walkthrough-v106.py` | 外观模式三档（16 项断言）：浅色/深色/跟随系统、持久化、显式设置优先于系统 |
| `walkthrough-v106-part2.py` | 大图按原图比例 + 双击「适应屏幕 ↔ 实际大小」（10 项断言） |
| `upgrade-v107.py` | v1.0.6 → v1.0.7 覆盖升级保留数据（含私有文件指纹比对） |
| `v108-acceptance.py` | v1.0.8 debug 包验收（22 项）：实况标识与播放、空壳回收、批量删除 |
| `v108-release-acceptance.py` | v1.0.8 release 包验收（28 项）：含 `FLAG_SECURE` 正反对照 |
| `walkthrough-v109-motion.py` | v1.0.9 **A/B 对照轮**：同设备、同素材、同量代码，A = 缺陷版 / B = 修复版；量影片条带高度与中央正方形比例 |
| `v109-release-acceptance.py` | v1.0.9 release 包验收（19 项）：版本与签名、默认禁截屏、覆盖升级不丢数据、release 上打开即播 |
| `walkthrough-v110-video.py A\|B` | **图库视频** A/B 对照轮。A = 出货版 v1.0.9（选图器里视频一格都看不见）/ B = 本次实现版。三层取证：交互层（选图器/网格/角标）→ 像素层（几何 + 白块在动/停住，判据用 **px/s** 不写死像素）→ **存储层**（`run-as` 直接读密文：头部 `FPBCHK`、体积符合分块公式、正文搜不到 `ftyp/moov/mdat/avc1`、删记录后密文真的消失）。B 轮 48 项 / A 轮 3 项，证据在 `dist/evidence/v110-video/` |
| `_mkvideos.py` | 合成图库视频的实测素材：两段**纯红底**影片（320×180 / 60 s、480×854 / 8 s），中央白正方形 = 拉伸读数、顶部移动白块 = 在播读数、**底部红噪声**把横屏顶到 5 块（块数分档 + 覆盖跨块读取）。生成时自己断言"噪声不压到正方形""块数 ≥ 2" |
| `_check_clip_geometry.py` | **碰模拟器之前**用走查自己的 `band_of` / `square_of` / `block_x` 体检素材：把解码帧按播放器摆法合成到 1080×2400，逐采样点验条带高、横向铺满、正方形比例、噪声区 0 个白像素、跨帧位移。需要 PyAV |
| `_apply_v110_patch*.py` | 给上面那个走查脚本打补丁的一次性脚本（原子批量替换，**任一锚点不命中就整体不写盘**）。已完成使命，可删 |

跑法（两个部分要**连跑**：第 1 部分会 `pm clear` 并走完引导，第 2 部分才有干净的库可导入）：

```bash
cd /d/MixiaVault
export ADB="D:/AndroidSdk/platform-tools/adb.exe"
PY=/c/Users/fa_12/.workbuddy/binaries/python/envs/default/Scripts/python.exe
"$PY" -u tools/walkthrough-v106.py && "$PY" -u tools/walkthrough-v106-part2.py
```

（`default` 这个 venv 里有 PIL —— 截图量像素要靠它。用系统 Python 会缺 Pillow。）

### `tools/motion/` —— 造实测素材 + 验判据本身

这一组是 v1.0.9 为"证明画面不再变形"建的工具链，**顺序不能颠倒：先造素材 → 再验判据 → 最后才跑走查**。

| 脚本 | 用途 |
|---|---|
| `make_motion_photo.py` | 合成真·实况照片（JPEG 尾部接 MP4，靠 XMP 声明；`Item:Length` 含 XMP 自身长度，用**不动点迭代**算准，差 1 字节系统就不认） |
| `make_probe_assets.py` | 合成"带几何形状"的测试素材：1080×2400 底图 + 320×180 影片（红底 + **中央 60×60 正方形** + 顶部横向移动白块），并落一帧 `probe-preview.png` 供自测 |
| `selftest_measure.py` | **判据自测**：用手工合成的"正确 / 被拉伸 / 静止图 / 被拉伸且系统手势条在场"四张图，验证同一份量代码**会失败** |
| `diagnose.py` | 诊断：从密文尺寸反推明文尺寸（`seal` 输出 = 12 B nonce + 明文 + 16 B GCM tag） |

```bash
PY=/c/Users/fa_12/.workbuddy/binaries/python/envs/default/Scripts/python.exe  # 需要 PIL + PyAV(av 18.1.0)

"$PY" tools/motion/make_probe_assets.py    # 1) 造素材（含预览帧，必须是它产出的，别手工维护）
"$PY" tools/motion/selftest_measure.py     # 2) 验判据本身可信
"$PY" tools/walkthrough-v109-motion.py     # 3) 才跑 A/B 走查
```

**为什么中间那步不能省**：影片源 320×180（16:9）铺进 1080 宽应得 **607.5 px** 高，
被拉伸成整屏（1080×2400）则是 **2120 px**；影片正中央那个正方形的渲染高宽比，
正确 **1.000**、被拉伸 **0.255** —— 两个期望值差 4 倍，判据不可能同时通过。
判据自己先能证伪自己，跑出来的数字才有资格当结论。

**两条素材设计约束**（踩过才知道）：

- **不要画横贯全宽的分隔线** —— 会让"白像素包围盒"等于整幅宽度，几何判据当场失效。
- **预览帧必须由素材脚本一起生成**。曾手工放成常量文件，素材改版后自测仍读**陈旧基准**，
  量出正方形比例 5.268 这种荒唐数字。

### 三条与"模拟器状态"有关的经验，别再从零踩一遍

1. **不要一边跑验收一边跑 Gradle。** 模拟器与 Gradle 抢宿主 CPU，
   应用的冷启动时间会从二十几秒涨到一分半以上，`uiautomator dump` 的失效窗口同步变长。
   这一条造成过一次整轮的假失败。
2. **模拟器跑久了 UiAutomation 会劣化**（`dump` 打印成功却不写文件、`null root node` 频发）。
   `adb reboot` 就能拉回来，实测 load average 从 11 降到 0.3，不用重建 AVD。
3. **双击要用 `sleep 0.12` 隔开两次 `input tap`**（详见 `walkthrough-v106-part2.py`
   里 `double_tap()` 的注释）：Compose 的双击下界是 40ms，而空闲模拟器上两次 `input tap`
   只隔 ~35ms，**点太快反而不算双击**。

### `probe-*.py` 是什么

`probe-theme-dialog*.py`、`probe-double-tap*.py`、`probe-animation.py` 是排查具体
BUG 时写的一次性探针：在特定界面反复 dump 层级 / 量像素 / 捞 logcat，用来说明
"到底看见了什么"。它们**不是验收门禁**，没有断言，留着是为了在同类问题复发时能直接复用
（`probe-animation.py` 就完整复现过"双击动画被自己取消"那个坑）。
`probe-double-tap*.py` 留下的测量数据（两次 `input tap` 间隔 ≈35ms~200ms 随设备负载漂移、
`motionevent` 连击不可用）是 `double_tap()` 里 `sleep 0.12` 的依据。
