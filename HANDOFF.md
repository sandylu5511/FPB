# HANDOFF —— MixiaVault（仓库 `sandylu5511/FPB`）

> 交接基线：**v1.1.6（versionCode 17）**，2026-09-20
> 本文件记录"接手的人需要知道什么"。逐版发布细节见 `dist/FPB-v1.1.6-发布说明.md`；
> v1.1.0–v1.1.5 依次见 `dist/FPB-v1.1.{1,3,4,5}-发布说明.md`
> （**v1.1.0 与 v1.1.2 没有单独的发布说明**：前者记在本文件 §二，后者那段"同一个
> versionName 下发过两个内容不同的包"的经过写在 `dist/FPB-v1.1.3-发布说明.md` 开头）。

---

## 一、当前任务

### 最近一轮（v1.1.6）的核心目标 —— HMAC 密钥副本收口

起因是一条**被明确挂账**的缺口：v1.1.5 的自检脚本里躺着一条 INFO ——
"HMAC 密钥副本仍用 `SecretKeySpec`"。

`SecretKeySpec` 在 **Android 上没有可用的 `Destroyable.destroy()`**：
Java 8 起 `destroy()` 是 `Destroyable` 的 default 实现（直接抛 `DestroyFailedException`），
Android 的 `SecretKeySpec` **没有覆盖它**，只有一个**包级私有**的 `void clear()`
（`javap` 实测）——应用侧调 `destroy()` 一个字节也清不掉。而 OpenJDK **覆盖了**它，
所以**同一段代码在 JVM 与 Android 上结论相反**，本机单测根本抓不到。

后果：`derivedRowIdFrom` 每派生一次系统行 id（清单、真库账本、审计槽、诱饵账本），
堆上就多一份**够不到、清不掉**的 32 字节明文密钥副本。

**"要不要一并收口"是用户拍板的事**，v1.1.5 没有替用户宣布决定，所以那时只写 INFO；
v1.1.6 用户决定单独收口，它就成了正式判据。

| # | 这轮做了什么 | 为什么这么做 |
|---|---|---|
| ① | `TransientAesKey` 泛化为 `TransientSecretKey(material, algorithm)` | 不新写一个 `TransientHmacKey` —— 两份逐行相同的清零实现迟早走样 |
| ② | `VaultSession.derivedRowIdFrom` 改用自持副本 | 与 `AeadCipher` 同一个道理：`SecretKeySpec` 那份副本够不到 |
| ③ | 计数器**拆成两个**：`scrubbedAesKeyCount` / `scrubbedHmacKeyCount` | 混合计数会**假绿**：AES 销毁成功、HMAC 忘了销毁，总数照样涨 |
| ④ | A 层单测补 11 条；B 层仪器测试从"诊断"改写成"回归判据 + 平台哨兵" | 公共行为共用一组，**差异行为各钉各的**（不合并成参数化） |
| ⑤ | 自检脚本两条 INFO 升 FAIL；版本 1.1.5→1.1.6、code 16→17 | 挂账的东西要么做掉、要么留一条能被看见的口子 |

**关键判据（两条，都在 `tools/security-selfcheck.py`）：**

- **`VaultSession` 不得再出现 `SecretKeySpec`** —— **先剥注释再匹配**，且匹配**整个文件**。
  只钉 `mac.init` 那一处的话，在别处新加一个不会红，而"再多一处"正是缺陷回归的形状；
  不剥注释则会把"解释为什么不能用它"的注释读成"使用"，判据当场变成永远 FAIL。
  豁免表为空，且**豁免条目必须命中**（登记了却命中不了 = 它保护的是一个已不存在的调用点）。
- **每个 HMAC 密钥副本都被销毁过** —— 构造处/销毁处数量对上，**且必须落在 `finally` 内**。
  多钉"在 `finally` 里"不是对称强迫症：`Mac.getInstance` 与 `mac.init` 都会抛，
  把 `destroy()` 挪到 `try` 体末尾后**数量仍然对得上**，只有异常路径不再清
  —— 而**派生失败往往正是反复试错的时刻**。

**唯一新增的那条单测**（`生产形状的销毁会计入HMAC探针`）不是凑数：自检判据钉的是
"代码里有没有 `destroy()`"，钉不住"探针本身有没有坏"。它补上后一部分。

**证据**：单测 **372 / 0 失败 / 0 跳过**（28 个测试类）；仪器测试 **19 / 0 失败 / 0 跳过**；
自检 **48 条**（38 PASS / 1 FAIL / 2 N/A / 7 INFO，唯一 FAIL 是已暂缓的 ⑥）；
release 包验收 **49 通过 / 0 未通过 / 5 不适用**；对照实验 **10 组**。
详见 `dist/FPB-v1.1.6-发布说明.md` §3。

### 上一轮（v1.1.5）—— 安全加固六条

一版**不加功能、只加锁**。来自一次对照「安全加固建议清单」的逐条核对，当时列出六条缺口：

| 编号 | 是什么 | 处置 |
|---|---|---|
| ① | 用户输入的密码 / 恢复码转成 `CharArray` 之后**从不清零** | ✅ v1.1.5 修 |
| ② | `AeadCipher` 每次都复制一份 DEK 明文进堆、**从不销毁** | ✅ v1.1.5 修 |
| ③ | 没有要求 StrongBox（专用安全芯片） | ✅ v1.1.5 做 |
| ④ | 没有要求 `setUnlockedDeviceRequired` | ✅ v1.1.5 做 |
| ⑤ | StrongBox 能力探测（③④ 的前提，必须成对做） | ✅ v1.1.5 做 |
| ⑥ | 反调试 / 运行环境完整性校验 | ❌ **用户 2026-09-18 明确决定不做**（见 §三） |

③④⑤ 的代价：**用户升级后需要重新开启一次生物识别**（DEK 不受影响，库里的内容不动）。
另有一条**同源但当时够不到**的缺口 —— HMAC 密钥副本，当时只写成 INFO，
**由 v1.1.6 收口**（见上一节）。

### 更早几轮

| 版本 | 一句话 | 细节 |
|---|---|---|
| v1.1.4 | 新增**登录记录**（设置 → 安全 → 登录记录；诱饵库里不显示这一项） | `dist/FPB-v1.1.4-发布说明.md` |
| v1.1.3 | **没有新增功能**，全是代码审核结果：第六轮十条 + 第九轮补修一条 | `dist/FPB-v1.1.3-发布说明.md` |
| v1.1.2 | **同一个 `versionName` 下发过两个内容不同的包** —— 教训见 §八 | `dist/FPB-v1.1.3-发布说明.md` 开头 |
| v1.1.1 | 修"切后台再回来，视频从头开始了" | `dist/FPB-v1.1.1-发布说明.md` |
| v1.1.0 | 图库支持**视频**：分块加密存储 + 媒体库 + 播放器 | 见 §二 里程碑 |
| v1.0.9 | 实况照片：拉伸变形 + 打开即播 | 见下面"历史（v1.0.9）" |
| v1.0.8 | 实况照片标识与可播放、批量删除、空壳记录回收 | `dist/FPB-v1.0.8-发布说明.md` |

### 历史（v1.0.9）—— 实况照片的播放体验

> 保留这一节不是因为它是当前任务，而是**它的判据形态后来被反复参照**：
> 把"看起来不对"变成两个可断言的数字、以及 A/B 对照轮的搭法，后面几轮一直在用。

修用户实测提出的 **2 条体验缺陷**：

| # | 用户原话 | 实质 |
|---|---|---|
| ① | 实况的照片播放起来会拉伸，导致照片变形 | 把解码帧交给**自定义 `Surface`** 时，Surface 的尺寸**就是**画面目标尺寸，`setVideoScalingMode(SCALE_TO_FIT)` 在这条路径上**不生效** |
| ② | 实况不是自动在大图播放，需要点击实况后另外播放，用户体感不好 | 大图页没有"打开即播"，实况要手动点胶囊 |

**修法**：`Modifier.aspectRatio(videoAspect)` 按影片比例给 `SurfaceView` 定尺寸；并在 `surfaceChanged` 报出**匹配尺寸之后**才 `start()`（`START_GRACE_MS = 700`）；大图页"打开即播"。

**判据（把"看起来变形"变成可断言的两个数）**：

| 量 | 期望（源 320×180，铺进 1080 宽） | 缺陷版实测 | 修复版实测 |
|---|---|---|---|
| 影片条带高度 | **607.5 px** | **2120 px**（铺满整屏） | **608 px** ✅ |
| 条带内中央 60×60 正方形的渲染高宽比 | **1.000** | **0.255**（纵向拉约 4 倍） | **1.000** ✅ |

**A/B 对照轮**（同一台设备、同一份素材、同一份量代码，只有 APK 不同）：

| 观察项 | A = `dist/FPB-v1.0.8.apk`（缺陷版） | B = v1.0.9 |
|---|---|---|
| 打开大图是否自动播 | **3 次尝试共 36 帧，0 帧**出现影片条带（顶栏有「实况」胶囊，要手动点） | **12 帧里 6 帧**出现条带，全程未点任何东西 |
| 条带几何 | `x[0,1079] y[280,2399]` = **1080×2120** | `x[0,1079] y[896,1503]` = **1080×608** |
| 正方形 | 204×799 → 比例 **0.255** | 204×204 → 比例 **1.000** |

### 历史（v1.0.8）的核心目标

修复用户实测提出的 **4 条缺陷**：

| # | 用户原话 | 实质 |
|---|---|---|
| ① | 实况照片导入后丢失实况的状态，变成普通照片 | 出库侧完全不认识"实况照片"这个概念 |
| ② | 图库增加照片会自动创建照片列表，但删除照片没有同步删列表；列表内容照片被全部删除时应同步删列表 | 空壳记录回收判据从未成立 |
| ③ | 图库无法多选删除图片/照片 | 缺批量操作 |
| ④ | 在列表页面删除列表需要点进详情页，无法批量选择删除 | 缺批量操作 |

### 历史（v1.0.8）的范围与边界

- **不改存储格式、不加字段。** 实况照片靠"读字节现判形态"，所以**已导入的老照片立刻获得标识，不必重新导入**。
- **影片明文绝不落盘。** 只在内存里解密后经 `MediaDataSource` 喂给 `MediaPlayer`。
- 同时顺手修掉两处被诊断带出来的真实缺陷（详见 §八 踩坑记录）。

### 历史（v1.0.8）的任务起源与诊断结论（避免重复怀疑）

用户报"实况照片丢了"。诊断结论与报告**相反**：

```
源文件：图片段 27,870 B + 影片段 3,543 B = 31,413 B
密文 = 明文 + 12 B nonce + 16 B GCM tag
  保留影片段 → 期望 31,441 B
  摘掉影片段 → 期望 27,898 B
  实测密文      31,441 B   ← 影片段完整保留
```

**影片段一个字节都没丢过。** 真问题在出库侧：应用没有一处读那段 XMP，所以图库没标识、大图页没有播放入口，用户看到的就是"一张普通照片"。这条结论已向用户汇报并确认。

---

## 二、已完成内容

### 交付物

| 交付物 | 路径 | 状态 |
|---|---|---|
| 正式签名包（当前） | `dist/FPB-v1.1.6.apk` | ✅ 3,339,353 B，versionCode 17 / versionName 1.1.6 |
| APK SHA-256 | — | `d94266230cb763ca61f7c7ffb051a45f1a92715efa67b24b6151909ae651fe80` |
| 签名 | — | v2 + v3 通过，v1 关闭；证书 SHA-256 `7fe41590…acacbd`（与 v1.0.6 起逐字符一致） |
| 发布说明（当前） | `dist/FPB-v1.1.6-发布说明.md` | ✅ 501 行 |
| 上一版正式包 | `dist/FPB-v1.1.5.apk` | ✅ **同尺寸** 3,339,353 B，SHA-256 `bfc66ecb…34e891c` |
| 安全自检脚本 | `tools/security-selfcheck.py` | ✅ 48 条判据（38 PASS / 1 FAIL / 2 N/A / 7 INFO），可重跑 |
| release 包验收脚本 | `tools/v110-release-acceptance.py` | ✅ 15 条判据：49 通过 / 0 未通过 / 5 不适用 |
| 回归测试（单测） | `./gradlew.bat :app:testDebugUnitTest` | ✅ **372 项，0 失败 0 跳过**（28 个测试类） |
| 仪器测试 | `./gradlew.bat :app:connectedDebugAndroidTest` | ✅ **19 项，0 失败 0 跳过**（BiometricGate 8 + HMAC 11） |
| 对照组实验 | `tools/_v116_control_experiment.py` | ✅ 10 组（U1–U7 单测侧、S1–S3 自检侧） |
| 验收证据（当前） | `dist/evidence/v116-release/`、`…/v116-androidtest/`、`…/v116-control/` | ✅ |
| 历史脚本（仍可重跑） | `tools/v109-release-acceptance.py`、`tools/v108-*.py`、`tools/walkthrough-v109-motion.py` | ✅ 回归用 |
| debug 包 | `app/build/outputs/apk/debug/` | ✅（本地构建产物，不入库） |

> ⚠️ **v1.1.4 / v1.1.5 / v1.1.6 三个包的字节数完全相同（3,339,353 B），内容却不同。**
> 差异（`classes.dex` 的几十字节）被包尾对齐填充吸收掉了。
> **不要用文件大小判断"包有没有换"** —— 判据只有 SHA-256（或 `aapt2 dump badging` 看 versionCode）。
> 这条在 v1.0.8 ↔ v1.0.9 上吃过一次（那时是 3,290,205 B），v1.1.4 ↔ v1.1.6 又原样复现了一遍。

### 里程碑

**v1.1.6**

- **HMAC 密钥副本收口**（§一）：`TransientSecretKey` 泛化 + `derivedRowIdFrom` 改自持副本 + 两个计数器拆开。
- **单元测试 365 → 372 项**（新增 `TransientSecretKeyTest` 11 项；4 条封装测试从 `Audit8RegressionTest` **移出**，不是删掉）。
- **仪器测试 8 → 19 项**（新增 `HmacKeyLifecycleInstrumentedTest` 11 项），两轮都是 **0 跳过**。
- 自检脚本两条 INFO 升 FAIL：**48 条（38 PASS / 1 FAIL / 2 N/A / 7 INFO）**。
- **release 包验收 49 通过 / 0 未通过 / 5 不适用**。

**v1.1.5**

- 安全加固六条：①②修复，③④⑤实现（StrongBox + `setUnlockedDeviceRequired` + 能力探测，必须成对做），⑥用户决定不做。
- **单元测试 349 → 365 项**（新增 `Audit8RegressionTest` 16 项）。
- **新增仪器测试这条通道**：`BiometricGateInstrumentedTest` 8 项，跑在 AVD `Pixel_7` / API 37。
- 修掉两个**在 1.1.5 开发过程中新引入、被自己抓住**的缺陷，都在发货之前，没进过任何发布包。
- 用户可感知的副作用：升级后**需要重新开启一次生物识别**（DEK 不受影响）。

**v1.1.4**

- 新增**登录记录**（`session/LoginLog.kt` + `ui/LoginLogScreen.kt`）；诱饵库里不显示这一项。
- **单元测试 333 → 349 项**（新增 `Audit7RegressionTest` 16 项）。

**v1.1.3**

- **没有新增功能**，全部是代码审核结果：第六轮十条 + 第九轮补修一条。
- **单元测试 321 → 333 项**（新增 `Audit6RegressionTest` 12 项）。
- 开头完整记录 v1.1.2 那次"同一个 `versionName` 下发两个内容不同的包"的经过。

**v1.1.2**

- 只改 `versionCode` / `versionName` 两行。**它存在的意义就是被 v1.1.3 追认**：两个包同名不同内容，只能靠哈希区分。

**v1.1.1**

- 修"我切出去回个消息，回来视频从头开始了"（`SurfaceView` 销毁重建时把播放位置带过去）。
- **单元测试 300 项**。
- 新增"位置保持"判据：量**画面**（白块位置反推秒数），不是控制条上那个 `m:ss` —— 那是应用自己的说法。

**v1.1.0**

- 图库支持**视频**：分块加密存储（`ChunkedBlobFormat` / `BlobSink` / `FileBlobStore`）+ 媒体库 + 播放器。
- 这一步在 git 上留有一条提交（`0f0e222`，2026-09-17，"图库支持视频：分块加密存储 + 媒体库 + 播放器"）。

**v1.0.9**（历史）

- **①② 全部实现**，且**缺陷先在 A 端复现**再证明 B 端修好（见 §一对照表）。
- **单元测试 216 → 229 项，0 失败**（新增 `MotionPlaybackTest` 13 项）。
- **release 包验收 19/19 通过**（`tools/v109-release-acceptance.py`）。**release 必须单独验**，理由见 §四.4。
- 顺带把「跑一次走查看截图觉得没问题」升级为**可断言的两个像素数字**（条带高、正方形比）。
- `.gitignore` 补回 `.kotlin/`（2.x 起 Kotlin 构建产物独立于 `.gradle/`，不排会被 `git add -A` 带进仓库）。

**v1.0.8**（历史）

- **①②③④ 全部实现。**
- **单元测试 181 → 216 项，0 失败**（新增 `MotionPhotoTest` 14 项、`PhotoRecordTest` 21 项）。
- **debug 包真机验收 22/22 通过**（`tools/v108-acceptance.py`）。
- **release 包真机验收 28/28 通过**（`tools/v108-release-acceptance.py`）—— 这是**必做**的一轮，理由见 §四。
- 修复两处诊断带出的真实缺陷（`BitmapCache` 并发、`motionVideo` 双重解密）。
- 修复验收截图暴露出的真实可用性问题：「实况」胶囊浅底白字在浅色照片上不可见。

### 关键证据链

| 判据 | 证据 |
|---|---|
| **v1.1.6** HMAC 副本真的被清零了 | 单测 `TransientSecretKeyTest` 11 项 + 仪器测试 19 项（在 ART 上复验探针确实会加一）；对照实验 S1–S3 证明两条自检判据**有鉴别力**（删掉 `finally` 里那一行 `destroy()`，判据二变红） |
| **v1.1.6** 换实现没改结果 | `derivedRowIdFrom` 换自持副本后，新旧路径派生的行 id **逐字节一致** —— 不然升级后的库会**找不到自己的行**，而且不报错 |
| **v1.1.6** `finally` 那个位置是安全的 | 实测 `Mac` 只在 `init` 读一次密钥（`getEncoded()` 调用次数：`init` 后 = 1、`doFinal` 后仍是 1）→ `init` 之后销毁自持副本不影响 `doFinal` |
| **v1.1.6** 自检脚本验的是**本包** | `…/security-selfcheck-2026-09-20-v116-on-116apk.log` 第 5 行印着被测对象 `FPB-v1.1.6.apk`（`sha256=d94266230cb763ca…`）—— 首轮那条验的是 1.1.5 的包，见 §八 |
| **v1.1.5** 凭据用完被清零 | `SecretChars.kt` 自持 `CharArray` 副本；自检里有对应判据（构造/销毁数量对上，与后来 HMAC 那条同款机制） |
| **v1.1.5** StrongBox / 解锁设备要求 | 自检判据 ③④⑤；AVD 上实测 `strongbox=False`、`keystore_level=400` —— **能力探测按实际降级，不硬失败** |
| **v1.1.4** 登录记录认得出"有人用假密码进去过" | 记录文案：*用假密码打开 —— 进去的是那个诱饵库*（诱饵库里不显示这一项） |
| **v1.1.1** 位置保持 | 切走前 **38.1 s** → 回来后第一批帧 **43.7 / 43.7 / 44.3 / 44.5 s**（由画面白块位置反推）；对照 v1.1.0：切走前 40.5 s → 回来 **0.0 / 0.0 / 0.0 s**（FAIL） |
| **v1.0.9** 播放不再变形 | 条带高度 **1080×608**（16:9 应得 607.5）；条带内 60×60 正方形渲染 204×204 → 比例 **1.000**（源比 1.000） |
| **v1.0.9** 打开即播 | 点开大图后 **未点任何东西**，12 帧连拍里 **6 帧**出现影片条带（logcat `MEDIA_INFO_VIDEO_RENDERING_START` 同期） |
| **v1.0.9** 缺陷确实存在过（对照） | A 端 `dist/FPB-v1.0.8.apk`：3 次尝试 36 帧 **0 帧**有条带；手动点「实况」后条带 **1080×2120**、正方形比例 **0.255** |
| **v1.0.9** 判据本身可信 | `tools/motion/selftest_measure.py` 用同一个量代码量 4 张手工合成的图，正确/被拉伸两组期望值差 4 倍，**判据不可能同时通过** |
| **v1.0.9** 正式包也已修复 | release 包上自动播 **7/12 帧** + 条带 **1080×608** + 比例 **1.000**（5,000 B 级证据见 `dist/evidence/v109-release/`） |
| **v1.0.9** 覆盖升级不丢数据 | `install -r` 后照片仍在、仍识别为实况、偏好保留（亮度 236.75/255） |
| **v1.0.8** 老照片免迁移即有标识 | 图库出现「实况」角标，**而这张照片是 v1.0.7 导入的、标题是旧版本"落盘"的** |
| **v1.0.8** 真的能播 | logcat：`NuPlayerDriver created` → `MediaCodec [c2.goldfish.h264.decoder]` → `MediaPlayerNative: info/warning (3, 0)`（`MEDIA_INFO_VIDEO_RENDERING_START`） |
| **v1.0.8** 老记录也回收 | 照片删光后主页变「本机加密 · 0 条」，那条 `照片 · 9月16日 06:27` 一并消失 |
| **v1.0.8** 批量删除 | 长按进多选 → 全选（图库 1/1、列表 3/3）→ 确认框 → 批量删除；**附件目录同时变空**（不只界面少一行） |
| release 包默认禁止截屏 | 窗口带 `SECURE`，整屏亮像素 **0.00%**；关掉后 **99.52%**（正反对照） |

---

## 三、未完成事项

### P1 —— 必须尽快做

| 事项 | 阻塞原因 / 说明 |
|---|---|
| **离线备份签名凭据** | `keys/` 整目录刻意不入库（含明文口令文档，远端是 public）。`keys/README-签名说明.md` 里那份**旧密钥 `mixia-release.jks` 的口令只存在于这一个文件里**，丢了就永久丢失。文档自己也要求"复制到至少两处离线位置"。 |
| **轮换本次用过的 GitHub PAT** | 该令牌已在对话中明文出现，且有效期至 **2026-10-16**。用完即撤销重发。 |

### P2 —— 应该做

| 事项 | 阻塞原因 |
|---|---|
| 恢复 lint 作为发布门禁 | `app/build.gradle.kts` 里 `checkReleaseBuilds = false`。根因：本机 Gradle 跑在 Android Studio 自带 **JDK 25**，AGP 8.7.3 的 Lint 解析版本号时抛 `IllegalArgumentException: 25.0.2` 崩溃。**装 JDK 21 或升级 AGP 后应重新开启**（代码里有注释标记）。 |
| 梳理两把签名密钥的关系 | `keys/` 下同时存在 `mixia-release.jks`（旧，文档记载完整）与 `fpb-release.jks`（现用，`keystore.properties` 指向它）。文档与现状**不一致**：README 说"本文件与 mixia-release.jks、keystore.properties 是同一套凭据"，实际已换。新接手的人容易被误导。 |
| 建立 CI | 目前全部验证靠本机脚本。至少应在 CI 跑 `:app:testDebugUnitTest`。 |
| **把验收搬回真机** | v1.0.8 / v1.0.9 的验收明确记着"**真机**"，v1.1.4 起明确记着"**模拟器**"。而模拟器的宿主机侧解码器会失能（§六.6）——**"模拟器全绿"推不出"真机没问题"**。真机验收目前**一次都没做过**。 |

### P3 —— 可选

| 事项 | 说明 |
|---|---|
| 用 GitHub Releases 分发 APK | 目前 APK 只在本机 `dist/`。仓库刻意不收 APK（154.6 MB 二进制）。 |
| 存量包名痕迹 | 包名已从 `com.mixia.app` 改为 `com.fpb.vault`，但 `dist/evidence/audit-20260914/` 下的旧测试报告 XML 仍用旧包名。属历史证据，无需改。 |
| 苹果实况照片（`.HEIC` + 独立 `.MOV`） | 当前**不支持**，见 §六。 |
| Argon2 换 native 实现 | 现在是纯 Java（BouncyCastle），标准档 64 MiB 工作内存**全落在 Java 堆里**，GC 不清零。换 native 能显著缩小暴露面，代价是**失去 JVM 单元测试能力**。详细取舍见 `dist/FPB-v1.1.6-发布说明.md` §5.4。 |
| ⑥ 反调试 / 运行环境完整性校验 | **用户 2026-09-18 已明确决定不做**（会误伤调试与自动化，而收益是可被绕过的）。自检脚本里**刻意保留一条 FAIL** 让它留在视野里，不是疏漏。 |

**当前无阻塞项**，所有 P1/P2/P3 都是"还没做"或"已决定不做"，不是"做不下去"。

---

## 四、关键决策及原因

### 0.（v1.0.9）拉伸变形的根因：**Surface 尺寸 = 画面尺寸**，不是缩放模式没设

- 直觉会去查 `setVideoScalingMode`。查了，也是这么设的，但**没用**。
- 真正原因：把解码帧交给**自定义 `Surface`** 时，输出画面**按该 Surface 的尺寸**渲染 —— 也就是说 **Surface 多大，画面就被拉成多大**，`SCALE_TO_FIT` 在这条路径上**根本不参与**。
- 正确修法是**按影片比例定 Surface 尺寸**，而不是让 Surface 去适应画面：`Modifier.aspectRatio(videoAspect)`。
- 配套一条硬约束：**`start()` 必须等 `surfaceChanged` 报出匹配尺寸之后**。提前 `start()`，播放器会按旧（不匹配）尺寸开画 —— 表现就是"偶发变形"。`START_GRACE_MS = 700` 是这条等待的上限。
- 三个判据抽成**纯函数**（`MotionPlayback.kt`），因此可以在 JVM 里单测：`shouldAutoPlay(motion, blobId, stoppedByUser)` / `aspectOf(width, height)` / `surfaceMatchesVideo(surfaceWidth, surfaceHeight, videoAspect)`，容差 `ASPECT_TOLERANCE = 0.02f`。

### 0.1（v1.0.9）"打开即播"要能被**用户偏好**打断

- `shouldAutoPlay` 显式带 `stoppedByUser`：用户手动暂停过就**不再自动播**。否则每次回到大图页都强行播一遍，比"要手动点"更烦。
- 纯函数形态让这条规则可单测，不必依赖 UI 走查。

### 1. 实况照片做到「标识 + 可播放」，而不是"真正保留实况语义"

- **用户拍板**（在"标识 + 可播放"与"仅标识"之间选了前者）。
- 影片只在内存里解密后喂播放器，**不落任何临时文件** —— 这个应用的整个前提是"明文不落盘"，写临时文件等于自己破坏前提。

### 2. 实况识别走**纯增量**，不改存储格式

- `MotionPhoto.detect()` 是纯字节函数（不碰 `android.*`，可 JVM 单测），出库时现读现判。
- **这是"老照片不必重新导入"的唯一实现方式** —— 也顺带避免了数据迁移和迁移失败的处置。
- 三条判据：新格式 `Container:Directory` 累加段长 → 老格式 `GCamera:MicroVideoOffset`（尾部字节数）→ 兜底扫 `ftyp` 盒。

### 3. 空壳判定按「保留记录」

- **用户拍板**（在"全删"与"保留用户写过内容的记录"之间选了后者）。
- 判据 = **"用户没在这条记录上留下任何东西"**：标题/备注/标签/收藏任意一样非空就保留。
- **配套的关键改动：自动标题不再落盘，改为显示时按 `createdAt` 派生。**
  原因：旧代码导入照片时把 `照片 · 9月16日 06:27` 写进了加密载荷，于是 `title.isBlank()` 恒为 false，空壳判据**一次都没成立过**——这才是需求②的真正根因。
- 再配 `isAutoGeneratedTitle()`（拿标题与 `createdAt` 的格式化结果比对）**认领旧版本已落盘的标题**，老用户不用重新导入。
- 格式固定 `Locale.CHINA`、`M月d日 HH:mm`（**小时零填充**）。**改这个格式会让所有老记录集体认不出来**，必须同步改 `PhotoRecordTest`。

### 4. `BytesMediaSource` 必须保留类名与方法名（R8）

- `MediaPlayer` 把 `MediaDataSource` 交给 `libmedia_jni`，**native 侧按名字回调** `readAt/getSize/close`，字节码里看不到这个调用点。
- `proguard-rules.pro` 里原本写着"整个应用没有一处反射"——**这句话从本轮起不成立**。已补：
  ```proguard
  -keep class * extends android.media.MediaDataSource { *; }
  ```
- **由此推出一条硬结论：debug 包的验收通过完全不能推出 release 包也能播。** 这是必须出正式包后重验的原因。

### 5. 验收**不清数据**，用 `install -r` 覆盖安装

- 设备上原本装着 v1.0.7，库里有它导入的照片记录（标题是旧版本**落盘**的）——这正是需求②**最难的一档**。
- 脚本开局先断言"旧记录确实在"，**不在就立刻退出**（否则后面全绿也是假的）。

### 6. 判据用「密文尺寸反推明文尺寸」，不依赖任何自报数据

- `AeadCipher.seal` 输出 = 12 B nonce + 明文 + 16 B GCM tag，所以**不需要主密码**就能从 `files/attachments/<blobId>` 的大小反推明文大小。
- 这是本轮诊断最硬的一条判据（31,441 B 的由来）。
- **限制**：只在 debuggable 包上可用（`run-as` 需要）。release 包上这条用不了，见 §六。

### 7. 一次性合并实现，不拆成多次提交

- 需求 ②③④ 都要动 `PhotoLibraryScreen.kt` 与 `HomeScreen.kt`。**串行编辑同一文件**是本项目反复踩过的坑（并行编辑互相覆盖），所以刻意合并。

### 8. 签名凭据整目录不入库

- 理由与代价见 `.gitignore` 内注释。要点：`keys/README-签名说明.md` 把口令**以明文写在正文与示例命令里共 4 处**，而远端是 **public** 仓库。

### 9.（v1.1.6）要清得掉，副本就必须**自己持有**

- **算法参数显式传入，不从 material 推断。** `getAlgorithm()` 必须返回构造时传入的那个值 ——
  推断出来的算法名，会让"这条副本属于哪条路"变成猜的，而计数器正是按算法分流的。
- **计数器按算法拆开，不能共用。** 一个总数看不出"是哪条路漏了"：
  AES 销毁成功、HMAC 忘了销毁，总数照样涨 → **两条判据同时绿，而其中一条路一个字节也没清**。
  未知算法走 `else -> Unit` **不计数** —— 宁可它因数量对不上变红，也不要它假绿。
- **`getEncoded()` 交出内部数组本身，不多复制一份。** 多复制一次等于又造一份够不到的明文。
- **只加 `finally`，不加 `catch`。** 异常类型分布是：`Mac.getInstance` → `NoSuchAlgorithmException`、
  `mac.init` → `InvalidKeyException`（都是 `GeneralSecurityException`）；
  `mac.doFinal` → `IllegalStateException`（`RuntimeException`）。要一并吞只能 `catch (Exception)`，
  代价是把"派生出一个错误 id"变成**静默失败** —— 而派生出的行 id 是数据库里系统行的 id，
  换实现改了结果**不会报错**，只会让升级后的库**找不到自己的行**。
- **一段 `javap` 实测留在 KDoc 里，不许当装饰删掉**（`TransientSecretKey.kt` 贴着类声明那段）：
  Android 的 `SecretKeySpec` 只有一个**包级私有**的 `void clear()`，`Destroyable.destroy()` 是
  Java 8 的 default 实现、直接抛异常 —— 而 OpenJDK 覆盖了它，所以**同一段代码 JVM 与 Android
  结论相反**。**这段输出就是这个类存在的全部理由。**

### 10.（v1.1.5 起）清零边界：**会话级**与**一次调用级**不是一回事

- `dek.expose()` / `auditKey.expose()` 交出来的是 `SecureBytes` 的**内部数组**，
  生命周期是**整个会话**，归 `close()` 管。
- `Cipher` / `Mac` 需要的那份密钥副本是**一次调用级**的，归 `finally` 管。
- **两者混着管会同时出错**：把会话级的提前清了，会话还没结束；把调用级的留着，每调一次就多一份明文。
- 顺带一条**诚实边界**（和 v1.1.5 的 `TransientAesKey` 一样）：provider 内部的 native 副本
  应用侧**碰不到**。我们只保证"自己造的那一份"被清零，
  **不宣称"内存里再也找不到密钥"** —— 写文档时不要把这两句混起来说。

---

## 五、修改过的重要文件

> **判据：`git status` 里"未跟踪"= 这批工作新加、从未进过 git**（仓库只有一条提交 `0f0e222`，
> 它大致对应 v1.1.0）。版本归属只写能从发布说明确证的；标 `v1.1.x` 的表示"这批里加的、
> 具体哪一轮没单独记"。**下面第一块是 v1.1.0 – v1.1.6（当前基线）**，
> 再往下的四张表是 v1.0.8 / v1.0.9 那一批 —— 保留是因为它们仍在被引用
> （`MotionPlayback` 的三个纯函数、`PhotoRecord` 的标题格式等）。

### v1.1.0 – v1.1.6（当前基线）

**新增（生产代码）**

| 文件 | 职责 | 引入 |
|---|---|---|
| `crypto/TransientSecretKey.kt` | **自持密钥副本**：`(material, algorithm)`，构造时 `copyOf()` 与调用方解耦；`getEncoded()` 交出内部数组本身；`destroy()` 填零 + 置空 + 幂等，**按算法分流计数**。取代 v1.1.5 的 `TransientAesKey`（单算法版，已删；原文留档 `dist/evidence/v116-refactor/TransientAesKey.kt.bak.txt` —— 那个 `.bak` 是**未跟踪**文件，删了无法从 git 恢复） | v1.1.6 |
| `crypto/SecretChars.kt` | 用户输入的密码 / 恢复码：自持 `CharArray` 副本 + 用完清零（之前**从不清零**） | v1.1.5 |
| `session/LoginLog.kt` | 登录记录：真密码 / 假密码 / 恢复码 / 生物识别 / 连续输错 | v1.1.4 |
| `ui/LoginLogScreen.kt` | 登录记录界面（**诱饵库里不显示这一项**） | v1.1.4 |
| `vault/MediaImportRoute.kt` | 媒体导入路由 | v1.1.x |
| `session/AuditKey.kt` | 审计槽的钥匙 | v1.1.x |
| `ui/ListAdd.kt` | 列表新增入口 | v1.1.x |

**新增（测试）**

| 文件 | 项数 | 覆盖 | 引入 |
|---|---|---|---|
| `test/crypto/TransientSecretKeyTest.kt` | **11** | 公共组（复制解耦、销毁置零、幂等、`toString` 带算法名、`assertSame` 验"内部数组本身"）+ **AES / HMAC 两组差异断言各自钉**（算法名、计数器）+ 未知算法不计入任何探针 | v1.1.6 |
| `androidTest/crypto/HmacKeyLifecycleInstrumentedTest.kt` | **11** | HMAC 密钥生命周期。KDoc 分两节：**平台的事实（哨兵）** vs **我们的不变式** —— 哨兵红了意味着**平台变了、要重估修法**，**不等于代码有缺陷** | v1.1.6 |
| `androidTest/vault/BiometricGateInstrumentedTest.kt` | 8 | 生物识别门（AVD `Pixel_7` / API 37） | v1.1.5 |
| `test/audit/Audit8RegressionTest.kt` | 16 | 安全加固那一轮的回归判据 | v1.1.5 |
| `test/audit/Audit7RegressionTest.kt` | 16 | 登录记录那一轮的回归判据 | v1.1.4 |
| `test/audit/Audit6RegressionTest.kt` | 12 | 代码审核（第六轮十条 + 第九轮一条）的回归判据 | v1.1.3 |
| `test/audit/Audit5RegressionTest.kt`、`Audit4RegressionTest.kt` | — | 更早两轮的回归判据 | v1.1.x |
| `test/vault/SizeDescriptionTest.kt` | — | 体积描述的格式化 | v1.1.x |

**修改（生产代码）**

| 文件 | 改动 | 版本 |
|---|---|---|
| `session/VaultSession.kt` | `derivedRowIdFrom` 改用自持副本（**去掉 `SecretKeySpec`**），加长 KDoc 讲清 material 生命周期与 native 边界；`HMAC_ALGORITHM` 改为引用 `TransientSecretKey.HMAC_SHA256`，**保证两处同源** | v1.1.6 |
| `crypto/AeadCipher.kt` | 每次调用的密钥副本改为**自持 + `finally { destroy() }`**（之前复制一份 DEK 明文进堆、**从不销毁**）；v1.1.6 又改用 `TransientSecretKey(key, AES)` | v1.1.5 / v1.1.6 |
| `vault/BiometricGate.kt` | StrongBox 与 `setUnlockedDeviceRequired` 要求 + **能力探测**（探测是前两条的前提，必须成对做） | v1.1.5 |
| `ui/ImageViewerScreen.kt` | 视频播放器（`VideoHolder`）；`surfaceDestroyed` 记现场 → `surfaceCreated` 带过去 | v1.1.0 / v1.1.1 |
| `app/build.gradle.kts` | versionCode 11 → … → **17**、versionName 1.1.0 → … → **1.1.6** | 每版 |
| `diagnostics/CryptoSelfTest.kt` | **已删除**（诊断用，安全加固有正式判据后不再需要） | v1.1.x |

**新增（工具 / 证据）**

| 文件 | 用途 | 引入 |
|---|---|---|
| `tools/security-selfcheck.py` | **48 条判据**。v1.1.6 把两条 INFO 升成 FAIL：① `VaultSession` 不得再用 `SecretKeySpec`（**先剥注释再匹配整个文件**，豁免表为空且豁免须命中）；② 每个 HMAC 密钥副本都被销毁过（构造/销毁数量对上，**且必须落在 `finally` 内**） | v1.1.5 建 / v1.1.6 升级 |
| `tools/v110-release-acceptance.py` | release 包验收：15 条判据 / 49 通过 / 0 未通过 / 5 不适用（**当前版本**） | v1.1.0 起 |
| `tools/_v116_control_experiment.py` | **10 组对照实验**（U1–U7 单测侧、S1–S3 自检侧）：自己打补丁 → 跑 → 原样还原 | v1.1.6 |
| `tools/_audit8_control_experiment.py` | v1.1.5 的对照实验（原来还有一组钉 `TransientAesKey` 的，已搬到 `_v116_control_experiment.py` 的 U1/U2） | v1.1.5 |
| `tools/motion/selftest_position_keep.py` | **读归档的连拍帧**把"位置丢失"的旧行为判红，并钉住取样点 | v1.1.1 |
| `tools/motion/selftest_motion_settled.py`、`selftest_threshold.py` | 另外两组判据自测 | v1.1.x |
| `tools/_probe_*.py`、`tools/_apply_v113_*.py`、`tools/_audit4_*.py` | 一次性探针与补丁脚本（**按需重跑，不要当门禁**） | v1.1.x |
| `dist/FPB-v1.1.6-发布说明.md` | 发布说明（**当前**，501 行） | v1.1.6 |
| `dist/FPB-v1.1.{1,3,4,5}-发布说明.md` | 各版发布说明（历史） | — |
| `dist/evidence/v116-*` | 本轮全部证据：`v116-release/`（验收）、`v116-androidtest/`（仪器）、`v116-control/`（对照）、`v116-refactor/`（改名留档）、两份自检日志 | v1.1.6 |

### 历史 · 新增（生产代码）

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/fpb/vault/vault/MotionPhoto.kt` | 实况照片识别。纯字节函数，三条判据，可 JVM 单测 |
| `app/src/main/java/com/fpb/vault/vault/PhotoRecord.kt` | 派生自动标题 + 空壳判定（`isShell` / `isAutoGeneratedTitle`） |
| `app/src/main/java/com/fpb/vault/vault/MotionPlayback.kt` | **v1.0.9** 播放判据三纯函数：`shouldAutoPlay` / `aspectOf` / `surfaceMatchesVideo`，容差 `ASPECT_TOLERANCE = 0.02f` |

### 历史 · 新增（测试）

| 文件 | 项数 | 覆盖 |
|---|---|---|
| `app/src/test/java/com/fpb/vault/vault/MotionPhotoTest.kt` | 14 | 新/老格式、单引号属性、三星写法、6 类必须判 null、越界 |
| `app/src/test/java/com/fpb/vault/vault/PhotoRecordTest.kt` | 21 | 空壳判定的每条分支 + **认领两个已出货版本的真实标题原文** |
| `app/src/test/java/com/fpb/vault/vault/MotionPlaybackTest.kt` | 13 | **v1.0.9** 自动播四种情形（含 `stoppedByUser`）、比例换算、Surface 匹配与容差边界 |

### 历史 · 修改（生产代码）

| 文件 | 改动 |
|---|---|
| `model/NotePayload.kt` | 加 `IMAGE_TITLE_WORD` 常量；`searchableText()` 补回派生标题的词 |
| `vault/BitmapCache.kt` | 加实况形态缓存；`motions` **改成并发结构**（原为普通 `HashMap`，见 §八） |
| `ui/VaultAppState.kt` | 加 `PhotoRef`；`removePhoto` 改薄封装；新增 `removePhotos`（批量、含空壳回收）、`deleteNotes`（批量删记录）、`motionOf` / `ensureMotion` / `motionVideo`；`updateNote` 保存后也做空壳回收 |
| `ui/HomeScreen.kt` | 列表页多选（长按进选、全选、确认框）；`displayTitle()` 改为派生标题 |
| `ui/PhotoLibraryScreen.kt` | **重写**：多选删除、实况角标、导入时 `title = ""` |
| `ui/ImageViewerScreen.kt` | 实况播放入口（三态顶栏）、`BytesMediaSource`、顶栏渐变遮罩、`SCALE_TO_FIT`；**v1.0.9**：打开即播、`Modifier.aspectRatio(videoAspect)`、`PlayerHolder.startIfReady(force)`、等 `surfaceChanged` 匹配后再 `start()`（`START_GRACE_MS = 700L`） |
| `ui/NoteViewScreen.kt` | 详情页缩略图加实况角标 |
| `ui/components/Common.kt` | `FpbTopBar` 加 `backIcon` / `backDescription` 参数 |
| `app/proguard-rules.pro` | 补 `MediaDataSource` 子类的 keep 规则 |
| `app/build.gradle.kts` | versionCode 8→9、1.0.7→1.0.8；**v1.0.9** 再 9→10、1.0.8→1.0.9 |
| `.gitignore` | `/keys/` 整目录、`.kotlin/`、`*.apk` 排除（**v1.0.9 把 `.kotlin/` 补回**） |

### 历史 · 新增（工具 / 证据）

| 文件 | 用途 |
|---|---|
| `tools/motion/make_motion_photo.py` | 合成真·实况照片（含 `Item:Length` 的不动点迭代） |
| `tools/motion/make_probe_assets.py` | **v1.0.9** 合成"带几何形状"的测试素材：1080×2400 底图 + 320×180 影片（红底 + 中央 60×60 白方框 + 顶部移动白块），并落一帧 `probe-preview.png` |
| `tools/motion/selftest_measure.py` | **v1.0.9** 判据自测：4 组手工合成图，验证量代码不可能同时得逞 |
| `tools/motion/diagnose.py` | 诊断脚本：密文尺寸反推明文尺寸 |
| `tools/walkthrough-v109-motion.py` | **v1.0.9** A/B 走查（`band_of` / `square_of` / `enter_and_burst`） |
| `tools/v109-release-acceptance.py` | **v1.0.9** release 包验收（19 项） |
| `tools/v108-acceptance.py` | debug 包验收（22 项） |
| `tools/v108-release-acceptance.py` | release 包验收（28 项） |
| `dist/FPB-v1.0.9-发布说明.md` | 发布说明（v1.0.9，**历史**） |
| `dist/FPB-v1.0.8-发布说明.md` | 发布说明（v1.0.8，**历史**） |

---

## 六、当前问题

### 1. 签名口令文档曾差点入库（已拦截）

- `keys/README-签名说明.md` 明文写了 `mixia-release.jks` 的口令（第 12/13/58/72 行），而 `.gitignore` 原本**只排除 `keystore.properties`**，这份 md 会被提交到 public 仓库。
- **已处理**：`.gitignore` 改为排除整个 `/keys/`。
- **副作用**：签名说明文档不随仓库分发。克隆者看不到签名流程，这是刻意的取舍。
- **待确认**：`keys/README-签名说明.md` 同时是那份**旧**密钥口令在本机的唯一记录，用户需自行离线备份。

### 2. 两把签名密钥并存，文档与现状不一致

- `mixia-release.jks`（旧，证书指纹 `A6:D0:78:53:…`），`fpb-release.jks`（现用，`keystore.properties` 指向它）。
- README-签名说明.md 声称两者与 `keystore.properties` 是"同一套凭据"，**实际已换密钥**。新接手的人会按旧文档去用旧密钥，导致签出无法覆盖安装的包。

### 3. release 包的两个取证限制

| 限制 | 后果 |
|---|---|
| 非 debuggable → `run-as` 与 `adb root` 都不可用 | 读不到 `files/attachments`，**"密文尺寸反推"这条最硬的判据在 release 包上失效** |
| 默认开 `FLAG_SECURE` | 截图全黑。脚本先按默认值验一次黑屏、再关掉换回视觉证据 |

### 4. 开发环境

- **JDK 25 + AGP 8.7.3 的 Lint 崩溃** → `checkReleaseBuilds = false`，发布包目前**没有 lint 门禁**。
- 构建必须显式指定 JDK：`export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`。
- SDK 路径 `D:/AndroidSdk`（`local.properties` 里，不入库）。
- 模拟器 AVD `Pixel_7`（API 37）。**启动日志的名字是从 v1.1.4 那轮沿用的**
  （`dist/evidence/emulator-v114-run2.log`），它实际覆盖了 v114 → v115 → v116 三轮 ——
  看到这个名字不要以为里面只有 v1.1.4 的证据。

### 5. 仓库此前没有 git 历史

- 本项目**原本不是 git 仓库**（只有 `.gitignore`，没有 `.git`）。本次是**首次入库**，无法追溯旧版本。
- 实际影响：当时为确认"旧版本往记录里写了什么标题"，只能去翻**已出货版本的真机截图**（`dist/evidence/v103/07-07-主页-照片记录可见.png` 读出 `照片 · 9月15日 09:10`，`dist/evidence/upgrade-v107/` 读出 `照片 · 9月16日 04:58`）。
- 顺带发现**小时是零填充的**——差这一个字符，老记录认领逻辑就整体失效。

### 6. 模拟器宿主机侧解码器会失能（做过一次取证，仍未定性）

- v1.1.0 的一次验收里出现过「进度条在走、画面不动」。**3 轮受控复现都没复现出来**，
  因此**没有改任何播放器代码** —— 机制没定性时改 Surface / MediaPlayer 生命周期是"照着猜改"。
- 多了一条 **guest 之外**的观测通道：模拟器进程自己的日志里混着**宿主机侧** FFmpeg 的
  H.264 解码器，它打 `no frame!` 就是"这段比特流没解出画面"——
  **这条在 logcat 里看不到**（当时查遍 logcat 只见 `MEDIA_INFO_VIDEO_RENDERING_START`、毫无错误）。

| 日志 | 那一轮 | `no frame!` 次数 |
|---|---|---|
| `build/_emu.log` | 崩坏那一轮（12 分钟里退化到 `screencap` 单帧 5.5 s） | **232** |
| `build/_emu2.log` | 健康那一轮（含完整一遍 1.1.1 验收） | **0** |

- 结论：**仍未能定性**，不能认定是应用缺陷；但有一条可量化的旁证指向**模拟器宿主机侧
  解码器失能**，而不是"应用没把画面贴上去"。
- 下一步（未做）：把 `-gpu swiftshader_indirect` 换成 `-gpu host` 再跑同样几轮 ——
  若冻结随之消失，就可以归到"模拟器的软解码 / 软渲染配置"，真机不走这条路。
  详细取证见 `dist/evidence/audit4/README.md` §10.6。

---

## 七、下一步计划

1. **P1 · 离线备份 `keys/`**（尤其那份旧密钥口令文档）。
2. **P1 · 轮换已暴露的 GitHub PAT**（有效期至 2026-10-16）。
3. **P2 · 统一签名密钥叙事**：在 README 或专门的说明里写清"哪个是现用密钥、哪个是历史密钥"，并补齐 `fpb-release.jks` 的证书指纹与主体信息。
4. **P2 · 恢复 lint 门禁**：装 JDK 21 或升级 AGP，然后删掉 `checkReleaseBuilds = false`。
5. **P2 · 接 CI**：至少 `:app:testDebugUnitTest`；有设备时补 `tools/v110-release-acceptance.py`（当前版本）、`tools/v109-release-acceptance.py` 与 `v108-*.py`（回归）。
6. **P2 · 把验收搬回真机**（§三）：模拟器全绿推不出真机结论。
7. **P3 · 用 GitHub Releases 分发 APK**，把 `dist/*.apk` 从本机搬到 Release 附件。
8. **P3 · 若要做苹果实况照片**：需要选图器允许多选 `.MOV` 并与 `.HEIC` 配对，涉及权限与配对规则的重新设计。
9. **P3 · Argon2 换 native**（§三）：代价是**失去 JVM 单测能力**，动之前要先想清楚测试怎么补。
10. **回归提醒（实况照片）**：`PhotoRecord` 的标题格式（`M月d日 HH:mm`、`Locale.CHINA`、小时零填充）与旧记录认领强耦合，**任何格式变更都必须同步更新 `PhotoRecordTest` 里的真实标题基准**。
11. **回归提醒（v1.1.6）**：`TransientSecretKey` 的**两个计数器、以及它们只在 `destroy()` 内部递增**这件事，属于"少写一行也不会有任何症状"的代码。动这一带之前先跑 `tools/_v116_control_experiment.py` —— 它会临时把 `destroy()` 那一行删掉，确认判据**确实会变红**（不会红 = 判据没鉴别力）。

---

## 八、踩坑记录

### 构建 / 语言

| 坑 | 原因 | 解法 |
|---|---|---|
| `Unresolved reference 'addCallback'` | `SurfaceView` 自己有个 `holder` 成员，局部变量 `holder`（自建 `PlayerHolder`）把它**遮蔽**了 | 局部变量改名 `playback` |
| 一次几十行 `Expecting a top level declaration` | 新增的 KDoc 段落前面那个 `*/` **提前关掉了注释块**，新内容落到了注释外 | 合并进同一个 KDoc 块 |
| 测试辅助函数造出的 `ftyp` 盒长是天文数字 | 只写了盒长低字节 `video[3] = 24`，高三位还是填充值 `0x11` | 用大端完整写入 4 字节 |
| Windows 下 `java -jar` 不认 MSYS 路径 | `/d/AndroidSdk/...` 不是合法 Windows 路径 | 用 `D:/AndroidSdk/...`；并显式指定 `JAVA_HOME` |
| **（v1.1.6）同一文件的多个 `Edit` 不能并行发** | 并行发出的编辑**只有最后一个落盘**，前面几个静默丢失 —— 而工具**每次都回"成功"**。本轮在 `AeadCipher.kt` 上同时发 4 处编辑，事后 grep 发现第 55 行仍是旧内容；`Audit8RegressionTest.kt` 的 import 也没删掉 | **逐个串行发**，每轮改完 `grep` 复核。**"工具说成功" ≠ "落盘"** |
| **（v1.1.6）`androidTest` 的方法名不能含空格** | DEX 038 / minSdk 26 直接拒；而 **D8 一次只报一个名字** —— 有几个就得报几轮，很容易误以为"只有一个" | 方法名用下划线连接；改完先 `grep` 一遍方法名 |

### 数据与并发

| 坑 | 原因 | 解法 |
|---|---|---|
| `BitmapCache.motions` 原为普通 `HashMap` | 写在 IO 线程、读在主线程、`clear()` 在锁定时的主线程。**一次 `put` 与 `clear` 交错会丢掉那次清理**，正好破坏这个类唯一的承诺（"锁定时不留下任何由明文派生的东西"） | 改成 `ConcurrentHashMap` 包一层（CHM 不收 null，而又必须能存"不是实况"这个结论，所以用 `Known` 包裹区分"没看过"与"不是"） |
| `motionVideo` 一开始解密两遍 | 先 `ensureMotion` 再读一遍字节，一张实况照片十几 MB | 读一次字节、就地判形态 |
| **（v1.1.6）`SecretKeySpec` 在 Android 上根本清不掉** | Android 的 `SecretKeySpec` **没有覆盖** `Destroyable.destroy()`（Java 8 的 default 实现直接抛 `DestroyFailedException`），只有一个**包级私有**的 `void clear()`；而 **OpenJDK 覆盖了**它 —— **同一段代码在 JVM 与 Android 上结论相反**，本机单测完全抓不到 | 副本必须**自己持有**（`TransientSecretKey`）。判断依据不是读源码，是 `javap` 实测 |
| **（v1.1.6）销毁位置能不能放在 `init` 之后（靠实测，不靠读文档）** | `Mac` 会不会在后面还要读密钥？猜不得 —— 猜错就是"偶发解密失败" | 实测 `getEncoded()` 调用次数：`init` 后 = 1、`doFinal` 后**仍是 1** → `Mac` 只在 `init` 读一次，`finally { destroy() }` 安全。但 **provider 的 native 副本应用侧碰不到**，别把这条说成"内存里再也找不到密钥" |

### 验收脚本（最容易骗自己的地方）

| 坑 | 原因 | 解法 |
|---|---|---|
| `W.find()` 是**子串**匹配，连坑两次 | `find("全选")` 命中「取消全选」（长按进来时已选=总数，按钮文字本来就是"取消全选"）；`wait_text("删除选中的")` 命中删除图标的 `content-desc="删除选中的照片"`，于是"确认框出现了"这个前提**根本没被验证** | 改精确匹配 + 点完回读计数 |
| 挑照片格子挑错两次，报错却一模一样 | Compose 是两层节点，`content-desc` 常只挂里层，所以"clickable 且无 text/desc"会把**顶栏返回按钮**和 **FAB** 都捞进来。按 `cy` 最大挑 → 中 FAB（弹出选图器）；按 `cy` 最小挑 → 中返回按钮（退回主页）。两次都报"大图页没出现"，原因却不同 | **按 y 区间分段**（顶栏 <280、FAB >1900） |
| 播放态轮询永远抓不到 | 影片只有 3,543 B、约 1.2 秒，而一次 `uiautomator dump` 就要 ~1 秒，采样周期比被测窗口还长 | 改用 **logcat 当判据**（`MediaPlayerNative: info/warning (3, 0)`） |
| `run-as` 判据反了 | 拒绝信息走 **stderr**，而封装的 `sh()` 只读了 stdout | 同时捕获 stderr |
| 3 条判据全红，功能其实完全正常 | `dumpsys window windows` 在 Android 37 上把 flags 打印成**符号名列表**（`fl=LAYOUT_IN_SCREEN …`），不再是十六进制 `fl=#81810200`，只认十六进制的解析器**悄悄返回 `None`** | 两种格式都认；并留一条不依赖文本解析的退路（像素亮度 0.00% ↔ 99.52%） |
| **（v1.0.9）连拍永远拍不到播放** | **采样点错位**：影片仅 4 秒，`screencap` 约 0.5 s/张。脚本先"等大图页页码出现"（1~3 s）**再**连拍 → 连拍起点永远晚于播放结束。logcat 铁证：播放发生在 `09:36:36.491~09:36:40.747`，而连拍 `17:36:42` 才开始 —— **把"没抓到"错读成"没播"** | **点开那一瞬就开始连拍**（`tap_thumbnail()` 只点不等）→ 立刻 `burst()`；没抓到就退出大图页**再试，共三次**；`BURST` 16→12；并在 `run()` 开头 `logcat -c` 清缓冲（否则上一轮的记录会算进这一轮） |
| **（v1.0.9）正方形量出 0.182 而非 0.255** | **系统手势条**的白色胶囊落在影片条带内，把白像素包围盒从 204×204 撑到 **284×1564**（假比例 0.182） | `square_of()` 显式排掉 `GESTURE_Y=2350 / X0=350 / X1=730`；并把这条**写进 `selftest_measure.py` 的第 4 组场景**钉死 |
| **（v1.0.9）自测量出正方形 5.268（期望 1.000）** | 自测读的是**陈旧的** `_preview-frame.png`（还带那条早先版本画的横贯全宽白线）。**素材变了，基准没变** | 预览帧改为**随素材一起生成**（`make_probe_assets.py` 落 `probe-preview.png`），并删掉旧文件 |
| **（v1.0.9）release 轮证据落错目录** | 复用了走查模块的 `OUT`，但 **`M.OUT`（模块级）与 `W.OUT`（walkthrough 的）是两个不同的全局**。只改了 `W.OUT`，`M.grab()`/`M.burst()` 仍往旧目录写 | **两个都设**（脚本里留了注释）。删掉落错的文件重跑 |
| **（v1.0.9）对照轮误报"找不到实况胶囊"** | 选图器按"最暗的一格"挑，挑中纯黑 `(0,0,0)` 的**坏格子**（MediaProvider 残留行），导错图 | 改成按"与静止图均值色的**距离**"最近来挑（阈值 < 50），并在导入后加一条"照片库有「实况」角标"自证 |
| **教训** | **"解析不到" ≠ "没生效"**。判据撒谎比功能出错更危险 —— 像素证据本来已经说明功能正确 | 关键结论要有一条不经过文本解析的证据 |
| **教训（v1.0.9）** | **别让判据自己说了算。** 判据要能"证伪自己"：用同一份量代码去量**手工合成**的"正确/被拉伸"两张图，期望值必须**差 4 倍**（1.000 vs 0.256），才说明这套量法真的在分辨差异 | `tools/motion/selftest_measure.py` —— 4 组场景全过才算判据可用 |
| **教训（v1.0.9）** | **"修好了"要有力度，必须先让缺陷重现。** 只说 B 端通过，无法排除"这套量法量什么都通过" | **A/B 对照轮**：同设备、同素材、同量代码，A=`dist/FPB-v1.0.8.apk`（缺陷版）→ 复现 1080×2120 / 0.255；B=v1.0.9 → 1080×608 / 1.000 |
| **（v1.1.6）自检脚本会验错对象，而且不报错** | `apk_path()` 取的是"dist 下**版本号最大**的包"。首轮自检 14:42 跑、`FPB-v1.1.6.apk` 14:43 才构建出来 → 三条**产物级**判据（无 INTERNET 权限 / 不可调试 / 禁止系统备份）实测的是 **1.1.5 的包**，而 1.1.6 在"可不可调试"上**一条实测都没有**。它没骗过人的唯一原因是：日志第 5 行把被测对象与 SHA-256 印了出来 | **顺序摆正：构建 → 自检 → 验收**。补跑后与首轮 `diff`，**应恰好只有 4 行不同**（被测对象行 + 三条判据标签）；不同就不止是"换了个包"。**首轮那份日志留着** —— 它是"被测对象写进日志"这个习惯的受益者 |
| **（v1.1.6）仪器测试的 `println` 不进 XML** | JUnit XML 的 `system-out` 里没有它 —— 从报告里找诊断输出会以为"没打印" | 从 `adb logcat -d -s System.out` 读。本轮改法是去掉**全部** `println`（**断言一条不删**），落档旧 logcat，并在新日志上验证 `grep -ac "HMAC实测"` = **0** |
| **（v1.1.6）对照实验的期望不是"永远恰好 1 条红"** | 一个改动可能同时破坏多个不变式：U2（销毁不置空引用）红 **4** 条、U3（去掉计数）红 **5** 条、U4 红 2 条、S1 红 2 条 | 要的是"**每条红都能说出理由**"。真正危险的信号是反过来：**改了一处、一条都没红** = 这组判据没有鉴别力 |
| **（v1.1.6）"数量对得上"也可能在骗人** | S3 把 `destroy()` 从 `finally` 里挪到 `try` 体末尾：**构造/销毁数量仍然对得上**，只有"**是否落在 `finally` 内**"那半条能抓住它 —— 而泄漏的正是"异常路径"（派生失败往往正是反复试错的时刻） | 判据里那半条不是对称强迫症。写"数量对上"的判据时，同步问一句：**位置对不对？** |

### UI / 产品

| 坑 | 原因 | 解法 |
|---|---|---|
| 「实况」胶囊在浅色照片上完全看不见 | 原来是浅底白字；验收截图里那张恢复码截图正好是白底才暴露出来 | 改半透明黑底，并给顶栏加自上而下的黑色渐变遮罩（那行白字本来也看不见） |
| 影片会被拉满全屏 | 没设缩放模式，320×240 的片段按 surface 拉伸 | `setVideoScalingMode(SCALE_TO_FIT)` |
| 缩略图"看起来像解码错了" | 其实是 `ContentScale.Crop` 把 320×712 的源图按填满裁切，看到的正是中段 | 抽图片段单独看，确认解码正常。**先验证再改代码** |

### 环境 / 工具

| 坑 | 解法 |
|---|---|
| 本机没有 ffmpeg，造不出 MP4 | 用 `adb shell screenrecord --size 320x240 --bit-rate 400000 --time-limit 2` 录一段真 MP4 再 `adb pull` |
| `content query --projection is_motion_photo` 报 `Invalid column` | **shell 的投影白名单里没有这列，不能据此以为合成失败**。改用 `adb exec-out content read` 读出 31,413 B 与源文件逐字节比对（`exec-out` 免 CRLF 污染） |
| 托管 Python 没有 PIL | 诊断脚本改为只用不依赖 PIL 的模块；算像素亮度用 raw `screencap`（不加 `-p`）自己解码 |
| release 包无法覆盖安装 debug 包 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不同）。**卸载不可逆**，必须先向用户列清代价并确认 |
| **（v1.0.9）要造"带几何形状"的影片，本机没 ffmpeg** | 用 **PyAV** 合成：`pip install av`（清华镜像 **403**，改阿里云 `https://mirrors.aliyun.com/pypi/simple/` 成功，18.1.0）。**`av.Rational` 不存在**，时间基用 `fractions.Fraction(1, FPS)`。编码参数 `{"crf":"18","preset":"ultrafast","profile":"baseline","g":"10"}` |
| **（v1.0.9）MediaProvider 残留行清不掉** | 坏格子来自媒体库里指向已删文件的残留行。`mv` 不删行；无 root，`pm clear` 无效，对已删路径重发扫描广播也无效 | 只能**按缩略图颜色挑格子**绕开（本项无解的根因，不是脚本 bug） |
| **（v1.0.9）`_preview-frame.png` 是"旧基准"** | 素材改版后自测仍读旧预览帧 → 判据基准与实际素材不一致 | 预览帧必须**由素材生成脚本一起产出**，不能手工维护 |
| **（v1.0.9）Kotlin 2.x 的 `.kotlin/` 目录** | 构建产物不再落在 `.gradle/` 下，`.gitignore` 不排它，`git add -A` 会把整个目录带进仓库 | `.gitignore` 补回 `.kotlin/` |
| **（v1.1.6）类改名要连带 grep 整个仓库，包括 `tools/` 下的脚本** | `read()` 对不上文件名会返回**空串**，而空串在不同判据下**可能判 PASS 也可能判 FAIL** —— 两种都不是"文件被改名了"这个事实。本轮 `_audit8_control_experiment.py` 就写死了 `TransientAesKey.kt` 的路径与补丁串。→ 改名后 `grep -rn "旧类名" app/ tools/`，并把写死旧路径的那组实验删掉、**注明搬去了哪个新脚本** |
| **（v1.1.6）后台启动的模拟器是个长命进程** | 它的启动日志名是**启动那一轮**的名字（`emulator-v114-run2.log`），却横跨了 v114 → v115 → v116 三轮 —— 看到这个名字会以为里面只有 v1.1.4 的证据。→ 换一轮就换个日志名，每次先 `adb devices` 确认设备在不在（本轮结束时它就退出了，日志尾部是正常拆卸序列） |
| **（v1.1.6）Windows 上跑脚本，PATH 会偶尔没加载起来** | 报 `dirname: command not found` / `ls: command not found` / `git: command not found`，看起来像"命令不存在"，其实只是环境没起来。→ 显式补 PATH 再跑（`export PATH="/usr/bin:/bin:$PATH"`；git 在 `.workbuddy/binaries/PortableGit/versions/*/cmd/`），**别据此判断"环境坏了"** |

### 签名

| 坑 | 说明 |
|---|---|
| `.gitignore` 排除了 `keystore.properties`，却漏了写明文口令的 `README-签名说明.md` | 已改为排除整个 `/keys/`。教训：**排除"文件"不如排除"目录"** —— 凭据目录里任何新增文件都该默认不入库 |
| **（v1.0.9）用文件大小判断"包换没换"会骗自己** | v1.0.8 与 v1.0.9 的 APK **字节数完全相同**（3,290,205 B），内容却不同。判据要用 SHA-256 或 `aapt2 dump badging` 看 `versionCode` |
| **（v1.1.6）"体积一致"更不能当判据了** | v1.1.4 / v1.1.5 / v1.1.6 三个包**字节数完全相同**（3,339,353 B），内容却不同 —— 几十字节的差异被**包尾对齐填充**吸收；1.1.2 那次更极端，同一个 `versionName` 下发过两个内容不同的包（`e19cc5c9…` / `722818fc…`）。**判据只有 SHA-256**（发布说明 §1 把三个包的哈希并排列出） |

---

## 九、接手须知（最短路径）

```bash
# 1. 环境
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
# local.properties 需含 sdk.dir=D:/AndroidSdk（不入库，需自建）
adb devices                                 # 先确认设备在不在（模拟器是长命进程，会自己退出）

# 2. 跑测试（无需设备）
./gradlew.bat :app:testDebugUnitTest        # 应为 372 项，0 失败 0 跳过（28 个测试类）

# 3. 出包
./gradlew.bat :app:assembleDebug            # 日常验证
./gradlew.bat :app:assembleRelease          # 需要 keys/keystore.properties 存在

# 4. 安全自检（只读 + aapt2/adb，不跑 gradle，很快）
python tools/security-selfcheck.py --device # 48 条：38 PASS / 1 FAIL(⑥，已决定不做) / 2 N/A / 7 INFO

# 5. 仪器测试（需要设备）
./gradlew.bat :app:connectedDebugAndroidTest  # 应为 19 项，0 失败 0 跳过

# 6. release 包验收（需要设备 + 已装对应包；约 13 分钟）
python tools/v110-release-acceptance.py     # 当前版本，15 条判据：49 通过 / 0 未通过 / 5 不适用

# 7. 历史回归（可选，仍需设备）
python tools/v109-release-acceptance.py     # v1.0.9 的 19 项 + FLAG_SECURE 正反对照
python tools/v108-acceptance.py             # debug 包，22 项（依赖设备上的老数据，见下）
python tools/v108-release-acceptance.py     # release 包，28 项

# 8. 判据自测：动"量像素"这套量法之前先跑（素材要先造出来）
pip install av                              # 清华镜像 403，用 https://mirrors.aliyun.com/pypi/simple/
python tools/motion/make_probe_assets.py
python tools/motion/selftest_measure.py     # 4 组场景全过，才说明这套量法可信
python tools/motion/selftest_position_keep.py

# 9. 动 v1.1.6 那一带（密钥副本 / 计数器）之前，先证明判据还有鉴别力
python tools/_v116_control_experiment.py    # 10 组：自己打补丁 → 跑 → 原样还原
```

**注意**：**顺序是「构建 → 自检 → 验收」。** 自检脚本自动挑"dist 下版本号最大的包"，
包还没出来就跑，它会**安静地验上一个包**（§八 有完整记录）。跑完看一眼日志第 5 行的
被测对象与 SHA-256。

**注意**：`v108-acceptance.py` 依赖设备上的**老数据**（v1.0.7 导入的记录）。换机或清了数据后，"老记录回归"那一项会被脚本主动判为不可验并退出 —— 这是设计如此，不是故障。

**注意**：`v109-release-acceptance.py` 会 `import` `tools/walkthrough-v109-motion.py` 复用其量法，且**必须同时设 `W.OUT` 与 `M.OUT`**（两个不同的全局），否则证据会落进走查目录。

**动手前先读**：`dist/FPB-v1.1.6-发布说明.md`（本版细节，尤其 §3 证据与 §5 未决）、
`dist/FPB-硬限制清单.md`、`keys/README-签名说明.md`（本地，签名凭据，**含明文口令，不要外传**）。
