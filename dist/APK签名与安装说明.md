# 密匣 MixiaVault —— APK 签名与安装说明

生成时间：2026-09-15 09:25
工程路径：`D:\MixiaVault`

---

## 一、你遇到的问题与根因

**现象**：APK 拷到手机后安装失败，提示缺少签名文件。

**根因**：`app/build.gradle.kts` 里原本**没有 `signingConfigs` 配置**。

- `debug` 变体：AGP 用 Android Studio 首次运行时自动生成的 `~/.android/debug.keystore` 兜底签名 → **能装**
- `release` 变体：没有指定签名密钥 → AGP 直接产出**完全未签名**的 `app-release-unsigned.apk` → **装不上**

所以问题不在"签名文件丢失"，而在"release 从未配过签名"。另外你可能取用了体积更小、看起来更像正式版的那个 release 包，它的文件名里其实写着 `unsigned`。

---

## 二、本次交付的 APK

| 文件 | 大小 | 签名 | 能否安装 | 用途 |
|---|---|---|---|---|
| `mixia-v0.1.0-m2-audited2-release-signed.apk` | 2.1 MB | ✅ 发布证书（CN=Mixia Vault） | **能装** | **日常使用，推荐** |
| `mixia-v0.1.0-m2-audited2-debug.apk` | 19.6 MB | ✅ Android Debug 证书 | 能装 | 排查问题（含调试信息） |

原来的 `*-release-unsigned.apk` 已删除，避免再次误用。

---

## 三、签名验证证据（apksigner 独立验证，非程序自报）

```
$ java -jar apksigner.jar verify --verbose --print-certs app-release.apk

Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: CN=Mixia Vault, OU=Personal, O=Mixia, L=Shenzhen, ST=Guangdong, C=CN
Signer #1 certificate SHA-256 digest: a6d078537b8bfd5cbe08b4ed5f16725f733f23cd5434e39bb32887988f0a5d3f
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 4096
```

该指纹与 `keys/mixia-release.jks` 中证书的 SHA-256 完全一致，确认是同一把密钥。

其他独立复核：

| 复核项 | 方法 | 结果 |
|---|---|---|
| 无联网能力 | `aapt2 dump permissions app-release.apk` | 仅 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，**无 INTERNET** |
| 确为正式构建 | `dumpsys package com.mixia.app` | `flags` 中**无 `DEBUGGABLE`** |
| 设备识别到签名 | `dumpsys package` | `apkSigningVersion=2`、`signatures=[a0a593f3]` |
| 功能未被混淆破坏 | 真机自检 logcat | **19/19 passed**（见 `evidence/signing-20260915/`） |

---

## 四、安装步骤

```bash
# 模拟器/已连数据线的设备
adb install -r 'D:\MixiaVault\dist\mixia-v0.1.0-m2-audited2-release-signed.apk'
```

或直接把 APK 文件传到手机，用文件管理器点击安装（需在系统设置里允许"安装未知来源应用"）。

---

## 五、⚠️ 两个必须知道的风险

### 1. 别的版本装不上去 —— 这是正常的

设备上已装 release 签名版时，再装 debug 版会报：

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.mixia.app
signatures do not match newer version; ignoring!
```

这是**本次实测拿到的真实报错**（不是推测）。Android 用「包名 + 签名证书」判定应用身份，签名不同即视为两个应用。

**关键后果**：要换装另一签名的包，**必须先卸载**；而卸载会连同 `/data/data/com.mixia.app` 下的密钥文件与加密库一并抹除。密匣的设计前提是"数据只在本机、无云端恢复"，因此**卸载 = 所有备忘录永久无法解密**。

→ 日常固定用 release 版，不要来回换装。调试需要重装前，先把内容导出备份。

### 2. 发布密钥丢失 = 无法再发升级包

`keys/mixia-release.jks` 及同目录 `keystore.properties`（含明文口令）必须**离线备份到至少两处**。

一旦丢失，日后只能生成新密钥 → 新包签名不同 → 用户必须卸载重装 → 数据全丢。

凭据详情与备份清单见 `keys/README-签名说明.md`。

---

## 六、工程侧改动

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts` | 新增 `signingConfigs.release`，从 `keys/keystore.properties` 读取凭据；release 变体绑定该签名配置。文件缺失时自动退回未签名产物，保证无凭据的机器仍能编译 |
| `.gitignore` | 新增 `keys/keystore.properties`（含明文口令，禁止入库） |
| `keys/mixia-release.jks` | 新建发布密钥库（PKCS12, RSA 4096, 有效期 30 年） |
| `keys/keystore.properties` | 新建凭据文件（本地，不入库） |
| `keys/README-签名说明.md` | 新建凭据说明与备份指引 |

---

## 七、遗留事项

- **JDK 25 与 AGP 8.7.3 的 Lint 崩溃**：`lint { checkReleaseBuilds = false }` 仍处于关闭状态（`IllegalArgumentException: 25.0.2`）。这**不影响签名与安装**，但 release 打包缺少 Lint 门禁。建议 M7 阶段装 JDK 21 或升级 AGP 后重新开启。
- **release 包也会运行一次启动自检**（当前 M2 阶段无 UI，自检是唯一功能入口，故保留）。M3 实现界面后应改为仅 debug 构建运行，避免在正式包的生产环境里写入 `selftest_vault.db` 与自检附件。
