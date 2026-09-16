# FPB v1.0.7 正式发布说明

> 这是**可覆盖升级**的正式包。装到已经装了旧版的设备上，数据不会被清掉（依据见 §5）。
> 前序正式包：v1.0.2 / v1.0.3 / v1.0.4 / v1.0.5 / v1.0.6。

---

## 1. 版本信息

| 项 | 值 |
|---|---|
| 包名 | `com.fpb.vault` |
| versionName | **1.0.7** |
| versionCode | **8**（上一版 7） |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| 文件 | `dist/FPB-v1.0.7.apk`，3 257 437 B |
| 签名方案 | v2 + v3（v1 关闭；minSdk 26 起 v1 只会多一份可被篡改的清单） |
| 签名证书 SHA-256 | `7FE41590E2F4429B388A5F889FE9A35E92A28C21A21637BA11D0820FC7ACACBD` |
| 证书指纹是否与前序包一致 | ✅ 与 v1.0.5 / v1.0.6 实测比对一致 —— 这是"能覆盖安装"的前提 |
| 单元测试 | **181 项，0 失败 0 错误** |

签名自检：

```bash
java -jar build-tools/36.0.0/lib/apksigner.jar verify --verbose --print-certs dist/FPB-v1.0.7.apk
# Verified using v2 scheme: true
# Verified using v3 scheme: true
# Signer #1 certificate SHA-256 digest: 7fe41590…acacbd
```

> ⚠️ 发布密钥（`keys/fpb-release.jks` + `keys/keystore.properties`）必须离线多备份几处。
> 它一旦丢失，就再也发不出能覆盖升级的包 —— 后续版本只能让所有用户卸载重装，而卸载等于数据全丢。

---

## 2. 本次修掉的 5 处缺陷（按用户可感知程度排序）

| # | 级别 | 用户原来会遇到什么 | 现在 | 修在哪儿 |
|---|---|---|---|---|
| 1 | **P1** | **所有操作反馈都等于没显示**。"已保存""已导出 N 条记录""导入失败：…"这类提示一闪就没了，用户以为操作没生效 | 提示条正常停留（约 4 秒） | `ui/AppRoot.kt` |
| 2 | **P1** | **"该导出备份了"的提醒从未出现过**。代码里有时间戳、四处注释都承诺这个提醒，但读取端根本不存在 —— 用户可能几个月不导出，直到手机坏了才发现没有副本 | 首页顶部出现催促条，点它直达设置页；空库不提醒 | 新增 `vault/BackupReminder.kt`，接进 `ui/HomeScreen.kt` |
| 3 | **P1** | **导入超大图片时应用被系统直接杀掉**（内存被榨干），而且"图片太大"和"图片损坏"给的是同一句含糊提示 | 边读边封顶，越界立刻放弃；明确提示"图片超过单张 N MB 的上限，请先裁剪或缩小" | 新增 `vault/Streams.kt`，改 `ImagePipeline` / `BackupManager` |
| 4 | P2 | 桌面伪装图标（备忘录 / 计算器）一旦被系统重置，**自己再也修不回来**，用户只能重装（而重装等于数据全丢） | 每次启动自检，坏了自动修回 | `VaultAppState.repairLauncherAlias()` |
| 5 | P2 | 全新安装时，系统给出的"默认"组件状态会被误判成"已被禁用"，导致多跑一次不必要的修复动作 | 按系统真实语义判断（四种 DISABLED 变体才是否） | `LauncherIcon.isEnabled` |

另外删掉 3 处**定义了却没有任何引用**的常量（`BACKUP_REMINDER_INTERVAL`、`FPB_WORDMARK_ASPECT`、`Base32Crockford.SIZE` 及其配套的 `ALPHABET_SIZE`）—— 保留它们会让人误以为相应的校验已经做了。

### 第 1 条为什么影响面最大

```kotlin
// 旧写法：effect 体内改写了自己的 key
LaunchedEffect(state.notice) {
    state.notice = null          // ← 改掉自己的 key
    hostState.showSnackbar(text) // ← 协程已被取消，这行等于没执行
}
```

实测证据（修复前）：

```
03:59:12.239 I FPB-SNACK: effect 启动 text=没有发现无用图片
03:59:12.276 I FPB-SNACK: showSnackbar 被中断：LeftCompositionCancellationException
```

**37 毫秒**。也就是说全应用的反馈都是这个下场。修复后：

```
04:19:22.192 I FPB-SNACK: showSnackbar 正常结束 text=没有发现无用图片
```

写成 `snapshotFlow { state.notice }.filterNotNull().collect { … }` 之后，
"清空 notice"与"effect 的 key"再无关系。注意用的是 `collect` 而不是 `collectLatest` ——
后者会把上一条提示取消掉，同一个毛病换个地方复发。

---

## 3. 本次重构：落盘名称与偏好键的单一来源

这一项不是修 bug，而是**为"以后还敢发升级包"做的准备**。

### 问题

磁盘文件名、偏好键名、备份包条目名这些字符串原先散落在 5 个文件里，共 15 个。
它们看着只是常量，实际是"已经发出去的那个包"与"接下来要发的包"之间的接口。
改错一个的失败方式**不像失败**：

| 改错了什么 | 用户的感受 |
|---|---|
| `fpb.key` / `attachments` / `vault.db` | 密文和附件都在，但没有密钥能解开 → 应用按"全新的库"重新引导，用户看到的是"我的东西全没了" |
| 任一偏好键 | 配色、自动锁定、桌面图标被**静默重置**，用户能察觉不对劲却无从排查 |
| 备份包条目名或清单字段名 | **用户以前导出的备份包再也导不回来** —— 而那是他手里唯一的副本，且已经躺在网盘里了，我们没有任何机会事后补救 |

### 做法

- 新建 `vault/StorageNames.kt`，把 15 个名字收拢到一处，每个都写明"改错了用户会怎样"。
- 原来的定义点（`SettingsStore` / `VaultRepository` / `BiometricGate` / `BackupManager` / `SqliteRowStore`）改为引用它，字面量不再出现第二遍。
- 新增 `StorageNamesTest`（**12 项**）钉住每一个字面量。它失败时**不要改测试** ——
  那意味着你正在动一个已经发出去的契约，正确做法是补"读到旧名字就迁移"的逻辑。
- 顺带保留了一处刻意的不一致：`lastBackupReminderAt` 在代码里改名成了 `lastExportedAt`，
  但**偏好键 `backup_reminder_at` 一个字都没动**。改了键名会让所有存量用户的时间戳被静默清零，
  于是他们升级后第一次打开就会看到一个假的"还没有导出过备份"。这一点有真机实测（§5.3）。

### 顺手补的一个可测性缺口

重构过程中碰了备份清单的**写入端**，才发现它一直没有测试守着 ——
拼装清单的那段 `buildString` 内联在 `BackupManager.export()` 里，而 `export()` 需要
一整个仓库与会话，JVM 单测跑不起来。于是"清单格式对不对"只能靠读一遍代码。

清单是整个备份包里唯一明文的东西，也是**读取端用来判格式的唯一依据**：
字段名或行尾换行改一个字，用户以前导出的包就会退化成"清单异常，无法确认格式"。
所以把拼装抽成 `internal fun buildManifest(createdAt, noteRows, attachments)`，
新增 `BackupManifestFormatTest`（5 项）：

- 整段文本**逐字**比对（只断言"包含某字段"对"少一个换行"这类错误是绿的）；
- **写入端 → 读取端往返**：`buildManifest` 产出的清单必须能被 `inspect` 原样解析回来 ——
  这一条把两端绑在一起，防的是"两端各自改了却都自认为对"；
- 恰好 5 行、行尾都有换行、字段名可枚举且不重复；
- 清单只含元信息（不夹带任何内容字段）。

验证 R8 没把新代码搞坏：

```
dex 里各字面量出现次数：fpb.key=1  vault.db=1  fpb_settings=1  backup_reminder_at=1
                        fpb-backup.txt=1  bio_wrap.bin=1  fpb.biometric.v1=1 …
StorageNames 类在 mapping 与 dex 中的残留：0（全部 const 被内联，零运行时足迹）
```

---

## 4. 单测

```
tests=181  failures=0  errors=0  skipped=0
```

| 测试类 | 项数 | 说明 |
|---|---|---|
| `AuditRegressionTest` | 20 | 第一轮审核 |
| `Audit2RegressionTest` | 11 | 第二轮审核 |
| `Audit3RegressionTest` | **13** | 本轮审核新增（提示条 / 备份提醒 / 读取封顶） |
| `StorageNamesTest` | **12** | 本次重构新增（落盘名称与偏好键的契约） |
| `BackupManifestFormatTest` | **5** | 本次重构新增（备份清单写入端格式 + 往返） |
| `VaultKeyringTest` / `VaultSessionTest` / `NoteCodecTest` / `VaultKeyFileTest` … | 120 | 加密内核与编解码 |

第三轮那 13 项做过**对照实验**：把修复逐条退回工程副本后重跑，
恰好红 3 条（`超限后仍读走了 8388608 字节`、`清单被读走了 4194703 字节`、
`expected:<NEVER_EXPORTED> but was:<NONE>`），其余 9 条仍绿 —— 先红后绿，不是自证。

---

## 5. 覆盖升级验证

### 5.1 release 包能覆盖安装（签名一致）

```
adb uninstall com.fpb.vault
adb install dist/FPB-v1.0.6.apk      → Success
adb install -r dist/FPB-v1.0.7.apk   → Success（未被 INSTALL_FAILED_UPDATE_INCOMPATIBLE 拦住）
firstInstallTime 未变 → 确实是覆盖安装，没有走"卸载重装"
```

### 5.2 私有目录逐字节不变（文件级证据）

debug 包上跑：v1.0.6 造数据（1 条文字 + 2 张图片）→ 取 md5 基线 → `install -r` v1.0.7
→ **立刻**再取一次 md5 比对（在启动应用之前，避免"打开数据库"本身引入变化）。

```
覆盖安装前（v1.0.6 / versionCode 7）：
  1a33570671eb684a42a2ade93ce88d3c  databases/vault.db
  d41d8cd98f00b204e9800998ecf8427e  databases/vault.db-journal   ← 0 字节占位，见下
  17001c35f7774b07dfb3b34895ea0576  files/attachments/1a7892322192bc717c35447753e7b172
  8ba1309435f64c0947bcda16efdd18c2  files/attachments/561c9565e0a0a1fec2c1bcd43e475494
  9c180869e08e62d911210940e02d779c  files/fpb.key
  7f6bbcbee9848eb0e80fcaaa7213a9b2  files/profileInstalled
  2f6ca110bd40e4ca4c3594833caa8309  shared_prefs/fpb_settings.xml

>>> adb install -r app-debug.apk
Performing Streamed Install
Success

覆盖安装后（v1.0.7 / versionCode 8）：7 个文件全部 OK —— 内容一字未改
```

两处**看起来像问题、其实必须这样理解**的地方：

- `vault.db-journal` 是 **0 字节**的占位文件，不是"有未提交事务"。SQLite 提交之后会留下它，
  所以它一直在是正常的；判据必须看**大小**而不是"文件是否存在"。
- 取基线之前必须让应用**正常开一次库**。若 `am force-stop` 恰好砍在事务中间，
  会留下**非空**的日志，SQLite 下次打开就会回滚它 —— 于是 `vault.db` 的 md5 变了，
  看起来像"升级改了数据"，实际是**升级之前就欠下的账**。

原始日志：`dist/evidence/upgrade-v107/debug-upgrade-verify.txt`（15 项断言，0 失败）。

### 5.3 老用户的偏好不会被重置

模拟"刚刚导出过备份"的状态（`backup_reminder_at` = 当前时间）→ 升级 → 断言：

- ✓ 键值仍是升级前那个数字（`1789534724549`，逐字相同）—— 键名没被改掉
- ✓ 首页**没有**出现"还没有导出过备份"（说明读取端真的读到了旧值）
- ✓ 把时间戳改到 8 天前并冷启动 → "上次导出备份已经超过 7 天"如期出现（新功能真的会响）
- ✓ 催促条出现时不会再同时提示"从未导出"

### 5.4 升级后数据仍然听得懂

md5 只能证明"字节没变"，不能证明"还解得开"。所以升级后用旧主密码解锁，断言：

- ✓ `本机加密 · 2 条` —— 记录数不变，升级前写的那条标题 `UPGRADE_MARK_070` 还在
- ✓ 照片库仍是 2 张，缩略图能显示（说明密钥文件 + 数据库 + 附件三者仍然匹配）

原始记录见 `dist/evidence/upgrade-v107/`：`baseline.json`、`debug-upgrade-verify.txt`、
`release-upgrade.txt` 与 6 张截图。

复跑方式（`tools/upgrade-v107.py` 三种模式）：

```bash
adb uninstall com.fpb.vault
adb install dist/FPB-v1.0.6-audit3-debug.apk    # 基线包，versionCode 7
python tools/upgrade-v107.py seed               # 造数据 + 存 md5 基线
adb install -r app/build/outputs/apk/debug/app-debug.apk   # ← 中间只能有这一步
python tools/upgrade-v107.py verify             # 核对
python tools/upgrade-v107.py release            # release 包覆盖升级冒烟
```

> `seed` 里有一条"基线包必须是 versionCode 7"的断言，别删。第一次复跑时忘了把设备降回
> v1.0.6，于是"v7→v8 升级"实际跑成了"v8→v8 重装"—— 数字看着一样，结论完全不同，
> 是这条断言把它标出来的。

---

## 6. 升级指引（可直接转给用户）

```
同一签名的新版本（本包）：直接覆盖安装 → 解锁 → 完，不需要先导出
换签名 / 换手机：        旧机导出备份 → 传包 + 带上恢复码 → 新机装 → 导入 → 用旧主密码解锁 → 重开指纹
```

完整的链路说明、会丢数据的四种情况、备份包里有什么没有什么、日常备份纪律，
见同目录下的 **《升级与备份说明.md》**。

---

## 7. 本次未修改但需要知晓的项

审核第三轮列出的这些项**本轮没有改动**，理由是"无法复现"或"改动收益小于风险"。
它们不是遗漏，是刻意留下的：

| 项 | 为什么没动 |
|---|---|
| 编辑器底部保存按钮可能被键盘遮挡 | 本机模拟器渲染不出可用键盘（`mInputShown` / `mIsInputViewShown` / 窗口 `isVisible` 全为 true，但屏幕上只有 Gboard 一条浮动工具条；用系统设置的搜索框复现同一现象 → 环境问题）。**无法复现就不做无证据的改动**。备查改法：`Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))` |
| `ImagePipeline.decode` 显示侧仍有 OOM 可能 | 已有采样兜底，且需要构造极端图片才能触发 |
| `BitmapCache` 不响应 `onTrimMemory` | 是优化项，不是正确性问题 |
| `OnboardingScreen` 落盘顺序不一致 | 现有顺序的最坏后果只是"多看到一次引导"，不是数据损失 |
| `manifestUnreadable` 提示不够准确 | 纯文案问题 |
| `popToHome` / `sizeBytes` / `toChars` / `isPlausiblePartialInput` 四个零引用成员函数 | 属于待清理的死代码，删之前要先确认不是"计划中要用的" |
