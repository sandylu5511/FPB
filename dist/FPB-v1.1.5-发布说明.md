# FPB v1.1.5 正式发布说明

> 这是**可覆盖升级**的正式包。装到已经装了旧版的设备上，数据不会被清掉（依据见 §3.4）。
> 前序正式包：v1.0.2 / v1.0.3 / v1.0.4 / v1.0.5 / v1.0.6 / v1.0.7 / v1.0.8 / v1.0.9 / v1.1.0 / v1.1.1 / v1.1.3 / v1.1.4。

> **为什么会有 v1.1.5（2026-09-20）**：这一版**不加功能，只加锁**。
>
> 它来自一次对照「安全加固建议清单」的逐条核对 —— 当时列出六条缺口，逐条拍板的结果是
> 「①② 两条真缺陷现在修；③④⑤ 做，可接受让用户重新启用一次生物识别；⑥ 暂不做」：
>
> | 编号 | 是什么 | 本版处置 |
> |---|---|---|
> | ① | 用户输入的密码 / 恢复码转成 `CharArray` 之后**从不清零** | ✅ 修 |
> | ② | `AeadCipher` 每次都复制一份 DEK 明文进堆、**从不销毁** | ✅ 修 |
> | ③ | 没有要求 StrongBox（专用安全芯片） | ✅ 做 |
> | ④ | 没有要求 `setUnlockedDeviceRequired` | ✅ 做 |
> | ⑤ | StrongBox 能力探测（③④ 的前提，必须成对做） | ✅ 做 |
> | ⑥ | 反调试 / 运行环境完整性校验 | ❌ 本轮不做（仍在缺口清单里，见 §5.2） |
>
> **用户在这台设备上能感知到的唯一变化**：如果原本开着指纹解锁，
> 升级后**需要重新开启一次**（原因见 §2.4，界面文案见 §4.1）。
> 库里的内容不受任何影响 —— DEK 同时还被主密码槽与恢复码槽包裹着。
>
> 这一版还修掉了**两个在 1.1.5 开发过程中新引入、并被自己抓住的缺陷**（§2.5）。
> 两个都发生在发货之前，**没有进过任何发布包**。

> 第十轮及更早那些修复的完整经过，仍然在 `dist/FPB-v1.1.4-发布说明.md` 里，
> **那一份保持原样、没有改动**。本文件只讲 1.1.5 相对 1.1.4 多了什么。

---

## 1. 版本信息

| 项 | 值 |
|---|---|
| 包名 | `com.fpb.vault` |
| versionName | **1.1.5** |
| versionCode | **16**（上一版 15） |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| 文件 | `dist/FPB-v1.1.5.apk`，3 339 353 B（3.18 MB） |
| APK SHA-256 | `bfc66ecb3ae1f14d586a3816c8d4f4853eeadd78cf52f77b459d4a9fc34e891c` |
| 签名方案 | v2 + v3（v1 关闭；v3.1 / v4 未启用） |
| 签名证书 SHA-256 | `7FE41590E2F4429B388A5F889FE9A35E92A28C21A21637BA11D0820FC7ACACBD` |
| 证书指纹是否与前序包一致 | ✅ 与 `dist/FPB-v1.1.4.apk` **实测比对一致**（逐字符相同）—— 这是"能覆盖安装"的前提 |
| 单元测试 | **365 项，0 失败 0 错误 0 跳过**（27 个测试类；比 1.1.4 的 349 项多 16 项，**全部在新增的 `Audit8RegressionTest`**） |
| 仪器测试（androidTest） | **8 项，0 失败 0 错误 0 跳过**（`BiometricGateInstrumentedTest`，跑在 AVD `Pixel_7` / API 37 上） |
| release 构建 | `BUILD SUCCESSFUL`，R8 已跑（`minifyReleaseWithR8`），日志 `dist/evidence/v115-assemble-release.log` |
| release 包验收 | **49 通过 / 0 未通过 / 5 不适用**（跑的是本包本体，见 §3.4；跑在**模拟器**而非真机，见 §5.1；脚本自报「共 15 条判据，未通过 0 条」＋「v1.1.5 正式包验收全部通过」） |
| 安全自检 | **判据 47 条：PASS 36 / FAIL 1 / N/A 2 / INFO 8**（见 §3.3；唯一 FAIL 是⑥，已拍板暂缓） |

签名自检：

```bash
java -jar build-tools/36.0.0/lib/apksigner.jar verify --verbose --print-certs dist/FPB-v1.1.5.apk
# Verified using v1 scheme (JAR signing): false
# Verified using v2 scheme (APK Signature Scheme v2): true
# Verified using v3 scheme (APK Signature Scheme v3): true
# Signer #1 certificate SHA-256 digest: 7fe41590…acacbd
```

包身份：

```bash
aapt2 dump badging dist/FPB-v1.1.5.apk | head -1
# package: name='com.fpb.vault' versionCode='16' versionName='1.1.5' …
```

### ⚠️ 这一版的体积与 1.1.4 **完全相同**，不能拿体积当判据

两个包都是 **3 339 353 B**。实测明细（`zipfile` 直接读包内条目）：

| | `classes.dex`（未压缩） | `classes.dex`（压缩后） | `resources.arsc` | 条目数 | 文件总字节 |
|---|---|---|---|---|---|
| v1.1.4 | 2 928 220 | 1 425 057 | 357 904 | 477 | 3 339 353 |
| v1.1.5 | **2 934 452** | **1 428 232** | 357 904 | 477 | **3 339 353** |
| 差 | **+6 232** | **+3 176** | 0 | 0 | **0** |

增长是真实发生的：本版新增了两个类（`SecretChars.kt` / `TransientAesKey.kt`）并改写了
`AeadCipher` 与 `BiometricGate`，`classes.dex` 大了 6 232 B、压缩后大了 3 176 B
（`AndroidManifest.xml` 的压缩后大小也从 1 880 变成 1 881，因为 versionName 换了）。
多出来的那 3 176 B 被包尾的对齐填充正好吃掉 —— 签名块与中央目录那一段从 **76 362 B** 缩到 **73 186 B**，
恰好 −3 176 B，于是总体积持平。

> 这里的"本版新增"是相对 **1.1.4 的包**说的（上面那张表就是拿 `dist/FPB-v1.1.4.apk`
> 的条目与字节数逐项比出来的）。**别用 `git status` 去数"本版加了哪些文件"** ——
> 本仓库有大量未提交改动，`app/src/main` 下躺着 **7 个**未跟踪文件，其中只有 2 个属于本版：
>
> | 未跟踪文件 | 文件时间 | 属于哪一版 |
> |---|---|---|
> | `crypto/SecretChars.kt` | 09-20 12:25 | **1.1.5（本版）** |
> | `crypto/TransientAesKey.kt` | 09-20 12:25 | **1.1.5（本版）** |
> | `session/AuditKey.kt` | 09-20 09:49 | 1.1.4（登录记录） |
> | `session/LoginLog.kt` | 09-20 09:21 | 1.1.4（登录记录） |
> | `ui/LoginLogScreen.kt` | 09-20 11:30 | 1.1.4 的文件，**本版又改过**（给它加了凭据清零） |
> | `ui/ListAdd.kt` | 09-18 14:03 | 1.1.3 期 |
> | `vault/MediaImportRoute.kt` | 09-17 14:12 | 1.1.0 期 |
>
> 两个本版新文件的时间（12:25）与 1.1.5 包的构建时间（12:30）对得上。
> 照 `git status` 去数，会把四个版本算成同一版。

**结论：判断"手里这个包是不是换过"只能看 SHA-256。** 本工程的验收脚本正是这么做的
（它读包内的 `versionCode` / `versionName`、比对证书指纹，并把 SHA-256 记进日志），
而不是"看体积跟上一版一不一样"。两个包放一起的话，
`aapt2 dump badging` 也分不出来 —— 那份输出里唯一不同的只有 versionCode / versionName 两个数字。

> ⚠️ 发布密钥（`keys/mixia-release.jks` + `keys/keystore.properties`）必须离线多备份几处。
> 它一旦丢失，就再也发不出能覆盖升级的包 —— 后续版本只能让所有用户卸载重装，
> 而卸载等于数据全丢。**密钥与口令都不在该 git 仓库里**（`/keys/` 整目录被排除）。

---

## 2. 这一版改了什么

### 2.1 ① 凭据字符数组：用完就清零

**改之前是什么样**：你在解锁页输入的密码、在引导页抄下的恢复码，都会被转成 `CharArray`
交给 KDF。那个数组在函数返回后**没有任何人清它** —— 它只是不再被引用，
然后**等 GC 什么时候愿意回收**。在这段不确定的时间里，它是纯明文，躺在进程堆里。

对一个以"别人拿到手机也打不开"为卖点的应用，"我输的密码什么时候从内存里消失"
是一个应该由我们回答、而不是由 GC 回答的问题。

**改之后**（新增 `app/src/main/java/com/fpb/vault/crypto/SecretChars.kt`）：

```kotlin
internal fun CharArray.wipe() {
    if (isNotEmpty()) fill('\u0000')
}

internal inline fun <T> CharArray.wiping(block: (CharArray) -> T): T =
    try { block(this) } finally { wipe() }
```

两个设计点，都不是风格问题：

1. **必须在 `finally` 里清。** 只在成功路径上清，等于**在最需要清的那条路径上不清** ——
   密码输错、Argon2 抛异常、协程被取消，这三条路都会走到 `finally`，
   而它们恰恰是"一个半途而废的凭据"最可能留下的场合。
2. **`wiping` 必须是 `inline`。** 调用点大多在 `suspend` 函数里。
   不让 `try/finally` 内联进协程体的话，清零点就会落在"已经把数组交给协程之后"，
   中间留出一个**别人还能读到它**的时间点。内联之后，清零点与使用点是同一段顺序代码。

**边界如实说明**：这清的是"我们手里那一份数组"。`String` 清不掉 ——
它在 JVM 里是不可变的，你只能等 GC。所以本版**没有**让"输入的密码永不留在内存里"这句成立；
它做到的是**我们主动持有的那份副本有了确定的终点**。

覆盖范围：**9 个凭据调用点**，由安全自检脚本逐个点名，不是"我记得都改了"：

```
VaultKeyring.kt:82       VaultKeyring.kt:288     LoginLogScreen.kt:426
OnboardingScreen.kt:152  OnboardingScreen.kt:154
SettingsScreen.kt:838    SettingsScreen.kt:839   SettingsScreen.kt:1240
UnlockScreen.kt:260
```

另有 **1 处备案豁免**：`SettingsScreen.kt` 的 `onConfirm(decoy.toCharArray())`（现于第 910 行）——
数组的**所有权移交给同步消费方**（对话框回调就地消费掉它）。
转换点自己不负责清理：在这里就地 `wiping` 反而会在消费方读到之前把数组清空。
这是全工程**唯一**的转换点豁免，脚本会校验"它必须命中"，避免豁免清单悄悄失效。

### 2.2 ② AES 加解密不再复制一份 DEK 明文进堆

**改之前是什么样**：`AeadCipher` 每次封/解都 `SecretKeySpec(key, "AES")`。
这个构造函数会**复制**一份密钥字节，而那份副本应用侧够不到：没有销毁手段，只能等 GC。
也就是说，**每做一次加解密，堆上就多留一份够不到的 DEK 明文**。

这一条里有个必须写下来的坑：**同一段代码，在 JVM 和 Android 上结论相反。**

`javax.crypto.SecretKey` 继承 `java.security.Destroyable`，所以 `key.destroy()`
"能清掉密钥"看起来是成立的。但 `Destroyable.destroy()` 自 Java 8 起是
**默认实现、直接抛 `DestroyFailedException`** —— 真能清掉与否，取决于实现类有没有覆盖它。
`javap` 打在 Android SDK 的真实 `android.jar` 上：

```
public class javax.crypto.spec.SecretKeySpec implements java.security.spec.KeySpec, javax.crypto.SecretKey {
  public javax.crypto.spec.SecretKeySpec(byte[], java.lang.String);
  public java.lang.String getAlgorithm();
  public java.lang.String getFormat();
  public byte[] getEncoded();
  public int hashCode();
  public boolean equals(java.lang.Object);
  void clear();            // ← 包级私有，应用侧碰不到
}
```

**Android 的 `SecretKeySpec` 没有覆盖 `destroy()`**：在 Android 上调它，
**一个字节也清不掉，还会抛异常**。而 OpenJDK 覆盖了它 —— 所以 JVM 单测里"能清掉"。
这正是这个坑的迷惑性所在：**按 JVM 的印象推断 Android，结论是错的**。

于是唯一可行的做法只剩一条：**要能清零，密钥副本就必须由我们自己持有。**

### 2.3 新增 `TransientAesKey`：一份"用完能自己清零"的密钥副本

```kotlin
internal val scrubbedAesKeyCount = AtomicInteger()

internal class TransientAesKey(material: ByteArray) : SecretKey {
    private var material: ByteArray? = material.copyOf()

    override fun getAlgorithm(): String = ALGORITHM
    override fun getFormat(): String = FORMAT
    override fun getEncoded(): ByteArray =
        material ?: error("密钥副本已销毁，不能再交给 Cipher")

    override fun destroy() {
        val current = material ?: return
        current.fill(0)
        material = null
        scrubbedAesKeyCount.incrementAndGet()
    }

    override fun isDestroyed(): Boolean = material == null
    override fun toString(): String = "TransientAesKey(destroyed=$isDestroyed)"
}
```

生命周期与不变式：

```
TransientAesKey(material)   复制一份，与调用方的数组彻底解耦
  getEncoded()              交出这份副本（Cipher 只在 init 时读一次）
  Cipher.init / updateAAD / doFinal
  destroy()                 副本填零，置为已销毁
```

**不变式：只能在密码学操作全部结束之后调用 `destroy()`。**
`AeadCipher` 把它放进 `finally`，所以异常路径同样清到位（`open` 是双层 try：
内层 `finally` 销毁、外层 `catch (GeneralSecurityException) { null }`）。

三个容易被忽略的细节：

- **`getEncoded()` 交出的是内部数组本身，不是每次新建的副本。** `Cipher` 只在 `init` 时读它，
  之后密钥已在底层上下文里。多复制一份只会**多留一份够不到的明文** —— 与这个类的目的正好相反。
  遵守 `SecureBytes.expose` 的同一条约定：调用方不得持有返回的引用。
- **`destroy()` 是幂等的**，且已销毁时不重复计数：计数只代表"真的清掉了一份"，
  重复调用让它虚增会让探针失去意义。
- **`toString()` 绝不能打出密钥内容** —— 这个类的实例会被顺手用在日志字符串里。

**诚实边界**：它**没有**让"内存里的密钥副本"消失。底层实现（Conscrypt / SunJCE）
在 `init` 时仍会把密钥读进自己的上下文，那块我们够不到。
它做到的是：**堆上那份由我们持有的明文副本有了确定的、立即的终点**，而不是"也许下次 GC 时"。

**探针计数写在 `destroy()` 内部，而不是调用点的 `finally` 里 —— 这是刻意的。**
第一版写在调用点旁边，那是个**假绿**：对照实验里把 `destroy()` 那一行删掉、只留计数器，
**所有断言照样通过**，因为计数器记的是"代码走到了这一行"，而不是"密钥真的被清零了"。
放进 `destroy()` 之后含义就唯一了：只有真正执行了清零才自增。

### 2.4 ③④⑤ 密钥规格阶梯，以及 v1 → v2 换代

**改之前**：Keystore 里那把 AES 密钥只提了最基本的三个要求
（`setUserAuthenticationRequired(true)`、`setInvalidatedByBiometricEnrollment(true)`、
auth-per-use）。它没有要求硬件保护等级。

**改之后**：按"最严 → 最松"逐档尝试，取第一份被平台接受的：

| 档位 | StrongBox | 设备未锁定要求 | 什么时候用得上 |
|---|---|---|---|
| `STRONGBOX` | 要 | 要 | 有独立安全芯片的机型（Pixel 3+、部分旗舰） |
| `TEE_HARDENED` | 不要 | 要 | 绝大多数有 TEE 的机型（API 28+） |
| `SOFT_REQUIREMENTS` | 不要 | 不要 | API 26/27，或前两档被平台拒绝时 |

**逐级降级不是偷懒。** `setIsStrongBoxBacked(true)` 在**没有该芯片**的设备上会抛
`StrongBoxUnavailableException`，`setUnlockedDeviceRequired(true)` 在一些厂商实现上会被
Keystore 直接拒掉。写死最严的那一档，结果是**指纹解锁在这些设备上直接不可用** ——
那比"用低一档的硬件保护"更糟。所以顺序是"先要最好的，要不到退一档，而不是整件事失败"。

每一档的失败原因**都记下来**（不是只留最后一条）：全部被拒时，"最后一档的原因"往往是最普通的那条，
而真正要知道的是"最严那两档为什么也不行"。只留最后一条，会把一个本来明确的问题变成一次猜测。

#### 为什么必须换一代别名（v1 → v2）

**密钥规格一旦生成就改不了。** `BiometricGate.secretKey()` 命中已有别名就直接复用旧密钥，
**新写的 spec 根本不会被执行**。所以上面这些加固对**存量用户**一个都用不上。

要让存量用户也拿到，唯一办法是换一代别名并把旧的那份作废：

- 当前代：`fpb.biometric.v2`（`StorageNames.BIOMETRIC_KEY_ALIAS`）
- 已退役：`fpb.biometric.v1`（`StorageNames.BIOMETRIC_KEY_ALIAS_RETIRED_V1`）——
  只为作废而保留，**绝不能再拿它生成密钥**

作废动作由 `BiometricGate.retireLegacyEnrollment()` 在**启动时**执行：
删掉 v1 别名、删掉 `bio_wrap.bin`、清掉档位记录。

判断依据是 **v1 别名是否存在于 Keystore**，而不是只看包裹文件 ——
只看文件会误伤已经用上 v2 的用户（他们的包裹文件就叫同一个名字）。

两处"必须一起清"的理由：

- **`bio_wrap.bin` 必须删**：它的 KEK 是用 v1 那把密钥加密的，删掉别名之后它就是一段
  永远解不开、也永远没用的密文；留着只会让 `hasEnrollment()` 继续报"已启用"。
- **档位记录必须清**：它记的是**上一代**那把密钥的规格，留着会让下一次 `enrollmentSpec()`
  拿旧档位去描述一把还不存在的新密钥。同理，`clear()`（用户主动关掉生物识别）也要清它 ——
  否则用户关掉之后设置页仍会报"密钥在独立安全芯片内"，**描述一把已经不存在的钥匙**。

#### 作废是「说了」还是「偷偷做了」

这一条是整版里离用户最近的地方，所以单列：

**没有提示的话，用户看到的现象是"指纹解锁的按钮凭空消失了"**
（`hasEnrollment()` 那时已经是 false），而设置页他又进不去 —— 因为他还锁在外面。
对一个用户来说，**设置自己变掉了、又完全查不出原因，比"提示我重新开一次"糟糕得多**。

所以 `Pref.BIOMETRIC_REENROLL`（`bio_reenroll_needed`）这个标志不是装饰，它驱动了三处文案：

| 位置 | 文案 | 为什么放在这 |
|---|---|---|
| **解锁页** | 「指纹解锁需要重新开启一次（本次升级加强了密钥保护）。用主密码进入后，到设置里重新打开即可。」 | 用户此刻正锁在外面，看不到设置页 —— 这句必须在解锁页上 |
| **设置页 安全段** | 「指纹解锁需要重新开启一次。为加固密钥保护，本版换了一把规格更严的密钥；而密钥规格一旦生成就不能就地修改，只能换新的。重新开启即可，库里的内容不会受任何影响。」 | 用户进来看时，把"为什么"一起说清 |
| 设置项副文案 | SOFTWARE 档：「用指纹代替输入主密码（**注意：无法确认密钥受安全硬件保护**）」 | 见 §2.5 缺陷 A |

两个"不该弹"的边界也处理了：

- **本来就关着指纹的用户不该收到这条提示** —— 对他来说没有发生任何变化，
  多一句话只会让人以为哪里出错了（只在 `wasEnabled` 时才置位）。
- **用户自己关掉生物识别时要立刻撤掉提示** —— 那是**换代**造成的通知，
  而他刚刚明确表示不要这个功能。撤晚了的后果是用户按提示做完、提示还挂在那里，看起来像没生效。

### 2.5 开发过程中新引入、并被自己抓住的两个缺陷

两个都值得写下来，因为它们的失败方式都是**不吭声**。

#### 缺陷 A：AES 密钥的属性**读不回来**，"保护等级"这条永远是空

原设计用 `KeyFactory.getKeySpec(aesKey, KeyInfo::class.java)` 把密钥属性读回来
（API 31+ 走 `getSecurityLevel()`，API 28–30 用 `isInsideSecureHardware()`），
据此显示"密钥在独立安全芯片内"或"本机密钥未被安全硬件保护"。
写得很周全，**问题是读不到**。实测问 provider 自己：

```
[provider] KeyFactory 已注册算法：EC, RSA, XDH, ED25519, ML-DSA, ML-DSA-65, ML-DSA-87
[provider] KeyFactory/AES -> NoSuchAlgorithmException
[provider] KeyFactory/EC -> OK
[provider] KeyFactory/RSA -> OK
```

**`AndroidKeyStore` 的 `KeyFactory` 不注册 AES。** 所以 `readKeyInfo()` 从来**只可能返回 null**
—— 而它的失败方式不是报错，是"读不回来"：

- 设置页里"密钥在独立安全芯片内"与"无法确认密钥受安全硬件保护"**两句话都不会出现**；
- 而后者恰恰是**最该被看见的那一句**；
- 同时仪器测试里那条核心断言会**永远以"环境限制"为由跳过**。

一句话：这段代码看起来在认真地读回规格，**实际什么都没读到，且不吭声**。

**它还骗过了一轮。** 第一反应是"模拟器没设锁屏 / 没录指纹"，实测 PIN 正常；
加逐档探针后又给 AVD 录了一枚指纹，`TEE_HARDENED -> OK` 了 —— **断言依然跳过**。
这才逼出真因。所以 `probeEnrollment()` 里刻意保留了那一步的位置并写明它为何不适用，
免得后来的人再沿着原设计把 `readKeyInfo()` 加回来。

**修法：保护等级改成"生成时记档"**，落盘在 `bio_key_tier`。
证据链是"**平台对不支持的要求抛异常、而不是静默忽略**"
（实测：没有录入生物识别时 `setUserAuthenticationRequired(true)` 直接抛
`InvalidAlgorithmParameterException`），所以"生成成功 = 这份规格被接受了"。

**唯一证不了的情形是"平台声称支持 StrongBox 却静默忽略"** —— 这一项本项无法证明，
已在 `BiometricGate` 的 KDoc 与自检脚本的说明里写明。

顺带纠正了一处语义：`KeyGuard.SOFTWARE` **不再是**"平台报告密钥是纯软件实现"，
而是"**我们没有要求到、也读不回**"。界面文案因此写成"**无法确认**密钥受安全硬件保护"。
写成"你的密钥没有硬件保护"是拿一句无法证实的话去吓用户 ——
API 26+ 的设备基本都有 TEE，兜底档生成的密钥大概率也在里面。

#### 缺陷 B：`androidTest` 的方法名不能带空格

`minSdk = 26` → DEX 版本 038，而**带空格的简单名要 DEX 040**。表现：

```
com.android.tools.r8.internal.jf: Space characters in SimpleName
'尚未生成密钥时规格为 null_而不是编一个默认值' are not allowed prior to DEX version 040
```

坑在于 **D8 一次只报一个名字**：改完第 120 行之后再跑一轮，才暴露第 193 行。
靠"报一个改一个"要跑 N 轮 —— 于是改成机器核对（正则扫全部反引号方法名、断言无空白字符），
7 个名字 0 命中。**这条只影响 `androidTest`；`app/src/test` 跑 JVM，方法名带空格没有限制**
（`Audit8RegressionTest` 里就有带空格的用例名）。

### 2.6 ⑤ 能力探测：让设备自己回答

新增两个诊断 API（不参与任何业务逻辑）：

- `probeKeySpecLadder()`：用**临时别名**逐档试生成，回传"这一档在本机上能不能用、被拒原因是什么"。
- `probeEnrollment()`：走一遍"生成 → `containsAlias` → 落盘档位 → `enrollmentSpec()`"，
  回传每一步的结果。

**两条纪律**：用临时别名生成、生成完立刻删（**绝不能碰用户正在用的那把密钥**）；
只做"生成 + 删除"，不做任何加解密，因此**不需要用户先通过生物识别**。

**为什么值得有这组 API**：加固是一条阶梯，同一份代码在不同设备上会落到不同档位。
用户在弱设备上反馈"指纹解锁不可用"时，唯一能问清的办法是**让设备自己回答**哪一档被拒了，
而不是靠"模拟器上跑通了"去推断。本轮就是靠它问出真因的（§2.5 缺陷 A）。

---

## 3. 怎么证明它是对的

### 3.1 单测（365 项，比 1.1.4 多 16 项）

新增的 16 条全部在 `app/src/test/java/com/fpb/vault/audit/Audit8RegressionTest.kt`。
它们不是"把代码再跑一遍"，每一条都钉住一个**将来有人改回旧写法就会红**的事实：

| 分组 | 用例 | 钉住的事实 |
|---|---|---|
| ① 凭据清零 | 字符数组在用完之后被清零_且返回值照常带出来 | 清零点在 `finally`，且不吞掉 block 的返回值 |
| | `block` 抛异常时同样清零 | 失败路径才是最关键的那条 |
| | 清零是幂等的_空数组不会抛异常 | 空数组不炸 |
| | 清零之后的字符数组不能再解锁 | 清了就是真不能用（不是"清了但其实读的是副本"） |
| 功能未受损 | 密码清零之后解出来的 DEK 仍然可用 | 清零发生在消费**之后**，不是之前 |
| | 恢复码路径的清理不影响功能_两次解锁结果一致 | 恢复码这条路同样 |
| | 带连字符与小写的恢复码照常可用_清理没有改变容错规则 | 顺手没把容错规则改坏 |
| ② 密钥副本 | 密钥交出的是独立副本_改动原数组不会影响它 | 构造时确实 `copyOf`，与调用方解耦 |
| | 销毁之后那份副本是零_且不可再用 | `destroy()` 真的填零，之后 `getEncoded()` 必须拒绝 |
| | 销毁是幂等的_且不会重复计数 | 计数不被虚增，探针才有鉴别力 |
| | 密钥的 `toString` 不泄漏内容 | 不会被顺手打进日志 |
| | `seal` 之后交给 Cipher 的密钥副本被清零 | 生产路径真的销毁了 |
| | `open` 之后交给 Cipher 的密钥副本被清零 | 同上，另一条路 |
| | `open` 解密失败时同样清零 | 异常路径同样清到位 |
| | 参数不合法时不会假装清零过一次 | 没走到清零就不许计数 |
| 行为不变 | 换密钥实现之后封解往返与 AAD 校验行为不变 | 换实现没换语义（含 AAD 不匹配必须失败） |

全量单测结果：**365 项 / 0 失败 / 0 错误 / 0 跳过**（27 个测试类），日志 `dist/evidence/v115-unit-tests.log`。

### 3.2 仪器测试（8 项，0 失败 0 跳过）

`app/src/androidTest/java/com/fpb/vault/vault/BiometricGateInstrumentedTest.kt`，
跑在 AVD `Pixel_7`（API 37，x86_64）上 —— 这是**唯一能证明平台真实行为**的通道：
JVM 单测里没有 `AndroidKeyStore`，Keystore 的每一条结论都必须在这里验。

| 用例 | 钉住的事实 |
|---|---|
| 新生成的密钥确实带上了加固规格 | 平台**接受了**这份规格，不是"我们请求了就当生效了" |
| 能力探测与设备实际特性一致 | `strongBoxSupported()` 与 `PackageManager` 的 feature 标志一致 |
| 逐档探针_打印平台对每一档的回答 | 诊断 API 能给出"哪一档被拒、原因是什么" |
| 尚未生成密钥时规格为空_而不是编一个默认值 | 不知道就说不知道 |
| 有上一代痕迹时_退役会删掉别名与包裹文件 | 换代迁移真的动了手 |
| 有上一代痕迹时也不碰当前这一代的绑定 | 迁移**不能误伤**已升级的设备 |
| 没有上一代痕迹时_退役什么都不做 | 无痕设备上不产生副作用 |
| 退役之后 `hasEnrollment` 转为 false_用户会看到需要重新启用 | 迁移后的状态与提示一致 |

**"0 跳过"本身就是证据。** 这条核心断言的上一版是"永远跳过" ——
见 §2.5 缺陷 A：它每次都以"环境限制"为由被跳过，而人看到的是"测试全绿"。

核心断言与诊断的**实测输出**（`println` 不进 XML `system-out`，从 logcat 读；
已从 `app/build/` 落档到 `dist/evidence/v115-androidtest-logcat/`，因为前者会被 `clean` 抹掉）：

```
[规格实测] guard=TEE authRequired=true authPerUse=true
           invalidatedByEnrollment=true unlockedDeviceRequiredRequested=true
           strongBoxSupported=false sdk=37

[能力探测] strongbox_feature=false, strongBoxSupported=false

[逐档探针] sdk=37 strongBoxSupported=false
  [逐档探针] TEE_HARDENED -> OK
  [逐档探针] SOFT_REQUIREMENTS -> OK
  [生产路径] 生成或取回密钥 -> OK
  [生产路径] KeyStore.containsAlias(当前代号) -> true
  [生产路径] 落盘的档位记录 -> TEE_HARDENED
  [生产路径] enrollmentSpec() -> EnrollmentSpec(guard=TEE, userAuthenticationRequired=true,
             invalidatedByBiometricEnrollment=true, authPerUse=true, unlockedDeviceRequired=true)
  [生产路径] KeyFactory 读回 AES 的 KeyInfo（原设计） -> 不适用：AndroidKeyStore
             未注册 KeyFactory/AES（EC/RSA/XDH/ED25519/ML-DSA 才有）
```

一句话读法：**这台模拟器没有 StrongBox**（所以阶梯只有 `TEE_HARDENED` / `SOFT_REQUIREMENTS` 两档），
`TEE_HARDENED` 被接受 → 落盘档位是 `TEE_HARDENED` → 读回来的 `guard` 就是 `TEE`，
四项要求（auth-required / auth-per-use / 新增指纹即作废 / 设备未锁定）都为真。
这条链的每一环都能被上面几行原文核对。

### 3.3 安全自检脚本（判据 47 条）

`tools/security-selfcheck.py`（可加 `--device` 打真机）。

这一轮判据**从 33 条涨到 47 条**。下面这份清单是**拿两份日志逐条对出来的**：
修复前 `dist/evidence/security-selfcheck-2026-09-20.log`（11:01）
vs 修复后 `dist/evidence/security-selfcheck-2026-09-20-post-fix.log`（12:35）。
之所以要逐条对而不是"数一下大概加了几个"：第一遍凭印象数的时候，
既漏了几条、又把两条**修复前就已经存在**的判据算成了本轮成果（见下面那段）。

净增 14 条 = **新增 15 条 − 替换掉 1 条旧判据**。

新增的 15 条（12 条判据 + 3 条 INFO 记录项），另加 1 条**改名并加强**（下表第一行的第二项）：

| 判据 | 组 | 钉住的事实 |
|---|---|---|
| StrongBox 能力探测 | 建议1 | 用 `FEATURE_STRONGBOX_KEYSTORE` 判，而不是猜 |
| ~~StrongBox 不可用时降级~~ → **逐级降级** | 建议1 | **不算新增**：原名就叫「…降级」，本轮加强为"每一档的失败原因都保留"并改名 |
| **不得用 KeyFactory 读 AES 密钥的 KeyInfo** | 建议1 | §2.5 缺陷 A 不许回来 |
| **保护等级来自生成时的档位记录** | 建议1 | 读档位，而不是"读回密钥属性" |
| `setUnlockedDeviceRequired` 的证据上限 | 建议2 | 这一项读不回来，必须把"证不了什么"写明（INFO） |
| `wiping` 是 inline | 建议3 | §2.1 第 2 点（才能包住 `suspend` 调用） |
| `wiping` 在 finally 里清零 | 建议3 | §2.1 第 1 点（失败路径同样清） |
| `wipe()` 真的填零 | 建议3 | 不是个空函数 |
| AeadCipher 不得再用 `SecretKeySpec` | 建议3 | §2.2 |
| 密钥副本由自己持有 | 建议3 | 能清零的前提 |
| `destroy()` 真的填零并断开引用 | 建议3 | 不是只置一个"已销毁"标志 |
| `getEncoded()` 不再多复制一份明文 | 建议3 | 不再制造第二份够不到的明文 |
| 探针计数落在 `destroy()` 内部 | 建议3 | 保证探针有鉴别力（§2.3） |
| 每个密钥副本都被销毁 | 建议3 | 构造 2 处 / 销毁 2 处，数量对上 |
| 凭据清零豁免（备案） | 建议3 | 那条**唯一**豁免必须命中（INFO） |
| **同类缺陷：HMAC 密钥副本仍用 `SecretKeySpec`** | 建议3 | §5.3 不许被忘掉（INFO） |

被**替换掉**的 1 条：旧的「每次加解密产生的密钥副本被销毁」——
它只数了个总数，被上面建议 3 那一组拆成了六条更细的（副本自持 / `destroy()` 填零 /
`getEncoded()` 不复制 / 探针计数位置 / 每个副本都销毁 / 不得再用 `SecretKeySpec`）。
**换掉它的理由和换掉旧判据是同一个**：只数"有没有这个动作"的判据，在密钥副本
换成一个"清不掉、但代码看起来一样"的实现时照样会绿（见下方对照实验里的 D1）。

**没被算进新增的两条**（它们修复前就在，别把它们当成本轮成果）：
「启用 StrongBox（专用安全芯片）」与「设备锁屏期间密钥不可用」——
修复前那份日志里它们已经是 PASS，因为③④⑤ 的规格阶梯当时已经写好了，
这一轮真正补上的是"读不回保护等级"这一半（§2.5 缺陷 A）。

建议 3 里"凭据清零"那条也从"扫一眼有没有 `wipe`"改成了**逐调用点判定**：
它现在会点名 9 个调用点，并按 (文件, 代码片段) 匹配那份唯一豁免 ——
按行号匹配在前一轮失效过一次（只改了上方一段注释、加了 5 行，豁免就指到别处去了）。

结果：**判据 47 条：PASS 36 / FAIL 1 / N/A 2 / INFO 8**，
日志 `dist/evidence/security-selfcheck-2026-09-20-post-fix.log`。

**唯一那条 FAIL 是⑥（反调试），是拍板暂缓的、不是新发现的** ——
脚本里已写明"用户已于 2026-09-18 明确决定本轮暂不处置"。
一条已被拍板暂缓的缺口若报得像首次暴露，会让读者重新去评估一件已经评估过的事。

#### 判据的对照实验：这张表是跑出来的，不是推出来的

判据本身也会写错（上面那条"按行号匹配的豁免"就是）。所以本轮**对每一条改动过的判据**
各做了一次对照实验：把缺陷放回去、看判据是否变红、再原样还原。

| # | 放回去的缺陷 | 结果 |
|---|---|---|
| D1 | 走回 `KeyFactory` 读 AES 的 `KeyInfo` | 恰好新增 1 条红 |
| D2 | 生成时不记档（`biometricKeyTier` 不写） | 恰好新增 1 条红 |
| D3 | 读取端不取档位 | 恰好新增 1 条红 |
| D4 | `wipe()` 不填零 | 恰好新增 1 条红 |
| D5 | `wiping` 去掉 `finally` | 恰好新增 1 条红 |
| D6 | `destroy()` 不填零 | 恰好新增 1 条红 |

**每组恰好新增 1 条红**，还原后回到基线（只剩⑥那条 FAIL）。
"恰好 1 条"是要紧的：多出来或少了都说明判据之间在互相顶替，
那样的绿是不作数的。生产代码里没有留任何对照实验的补丁
（`grep -rn "对照实验" app/src/main/java/` 只剩 `TransientAesKey.kt` 正文注释里那一处引用）。

### 3.4 release 包验收（模拟器）

| 项 | 值 |
|---|---|
| 通过 / 未通过 / 不适用 | **49 / 0 / 5** |
| 脚本自报 | 「共 15 条判据，未通过 0 条」＋「**v1.1.5 正式包验收全部通过**」，退出码 `0` |
| 跑的是哪个包 | `dist/FPB-v1.1.5.apk` 本体（脚本先断言 `16 / 1.1.5` + 证书指纹，再见装） |
| 日志 | `dist/evidence/v115-acceptance-console.log`（12:30:59 → 12:43:56，**12 分 57 秒**） |
| 现场截图 | `dist/evidence/v115-release/`（**62 张**） |
| 设备 | AVD `Pixel_7`，API 37，x86_64 |

覆盖到的四件事：

1. **包身份**（5 项全 PASS）：`versionCode 16 / versionName 1.1.5`、v2+v3 签名有效、v1 关闭、
   证书指纹与前序包一致、体积落在 release 包的合理量级。
2. **全新安装**（3 项 PASS）：release 包**开箱即禁止截屏**（窗口上真有 `FLAG_SECURE`），
   且那一帧的像素证据是平均亮度 **0.00/255**、亮像素占比 0.00% —— 不是"标志位写了就算"。
3. **可覆盖升级**（5 项 PASS）：先装 v1.0.9 导入**一张照片**（在库里就是 1 条记录，
   日志原文「库里 1 张」），再 `install -r` 本包、**不做 `pm clear`**，
   断言照片还在、偏好（禁止截屏）也保留、缩略图找得到、**照片还能打开**（密文与密钥都还在）。
4. **整条视频链路在 R8 之后重跑一遍**（判据一条不减）：控制条自动收起、进度条拖动
   （含落点重复性 `[36, 36, 36]`、极差 0 秒）、切后台再回来（Surface 走一遍
   destroyed → created，且**从我离开的地方接着播**：切走前第 38.5 秒、回来后第 45.0~46.2 秒）、
   竖屏不被摆成横的、长按多选删除，外加解码器
   （`NuPlayerDriver` / `c2.*.h264.decoder`）与首帧渲染
   （`MEDIA_INFO_VIDEO_RENDERING_START`）的 logcat 硬证据。

**49 / 0 / 5 与 v1.1.4 那一轮逐项一致**（v1.1.4 记的是同一组数字）。
49 与 5 的分法也一致：5 条「不适用」是**沙箱类判据**
（release 包 `debuggable=false`，`run-as` 被系统拒绝，读不到应用沙箱里的密文），
处置沿用 v1.1.0 起的口径 —— 改用**页头计数**（删除后 `· 0 项`）证明记录确实删了，
密文是否一并消失改由 debug 包那一轮验（数据层代码路径相同）。

### 3.5 构建

```
./gradlew compileDebugKotlin                  # BUILD SUCCESSFUL
./gradlew testDebugUnitTest                   # BUILD SUCCESSFUL → 365 项 / 0 失败
./gradlew connectedDebugAndroidTest           # BUILD SUCCESSFUL → 8 项 / 0 失败 / 0 跳过
./gradlew assembleRelease                     # BUILD SUCCESSFUL（R8 已跑）
python tools/security-selfcheck.py --device   # 47 条判据：36 / 1 / 2 / 8
python tools/v110-release-acceptance.py       # 49 / 0 / 5，退出码 0
```

R8（`minifyReleaseWithR8`）确实跑了。本版新增的代码里有两处**编译器看不见的契约**，
和 v1.0.9 / v1.1.4 是同一类：

- **`destroy()` 有没有真的被调用**：漏掉一次不报错，只是"堆上多留一份明文"。
- **落盘档位有没有真的写**：漏写不报错，只是"设置页里保护等级那句话永远不出现"。

两者都靠测试与自检脚本钉，而不是靠"打开看一眼能用"。

---

## 4. 升级说明

### 4.1 用户需要做什么

**只有一件事：如果原本开着指纹解锁，升级后到「设置 → 安全」里重新开启一次。**

原因写在提示文案里（解锁页与设置页都有）：

> 指纹解锁需要重新开启一次。为加固密钥保护，本版换了一把规格更严的密钥；
> 而密钥规格一旦生成就不能就地修改，只能换新的。重新开启即可，库里的内容不会受任何影响。

**不会丢任何数据。** 原因：DEK 不只被"硬件锁 + KEK"这一条路包着，
它同时还被**主密码槽**与**恢复码槽**包裹着。删掉旧的生物识别绑定，
影响的是"能不能用指纹解开 DEK"，不是"DEK 还在不在"。
验收里 `install -r` 覆盖升级那一段（§3.4 第 3 项）就是这条的现场证据：
升级后那张照片**仍能打开**。

本来就**没开**指纹解锁的用户**完全感知不到这一版有任何变化**
（程序不会给他弹提示，见 §2.4）。

### 4.2 兼容性

- 从 v1.0.2 起的任何一版都可以 `adb install -r dist/FPB-v1.1.5.apk` 直接覆盖，**数据不清**。
- 证书指纹与前序包一致（见 §1），这是能覆盖安装的前提。
- **本版没有改数据格式、没有改数据库结构**，不涉及迁移。
  唯一新增的落盘项是两个偏好键：`bio_reenroll_needed`（换代提示）
  与 `bio_key_tier`（档位记录）。两者都在旧的偏好文件里，
  新版本读不到时按"没有记录"处理 —— 即"不知道保护等级，就不显示那句话"，
  而不是编一个默认值。
- Keystore 侧的变更（v1 别名作废、新增 v2 别名）**只影响生物识别绑定本身**，不触碰其它密钥槽。

---

## 5. 已知未决

### 5.1 验收跑在**模拟器**上，不是真机

§3.4 那一轮 49/0/5 是在 AVD `Pixel_7`（API 37，x86_64，硬件加速）上跑的，
**没有在物理真机上验过**。模拟器能覆盖的是"包能不能装、能不能覆盖升级、数据在不在、
窗口标志对不对、R8 之后 native 回调那条路通不通"；
它覆盖不到的是**真实设备的 GPU/解码器差异** —— 而 v1.1.3 §5.1 那条
"画面冻在首帧"恰恰是**宿主渲染侧**的事。所以这一条不算被模拟器验掉了。

**本版还有一条模拟器验不到的、比以往更要紧的**：这台 AVD **没有 StrongBox**
（`[能力探测] strongbox_feature=false`），所以：

- 阶梯的**第一档 `STRONGBOX` 从未在任何设备上被执行到过** ——
  它的代码路径只有"被跳过"的实测记录，没有"被接受"的实测记录；
- `setUnlockedDeviceRequired(true)` 在**厂商实现差异**上的表现，模拟器给不出答案。

这两条只能等真机（尤其是带安全芯片的机型）反馈。诊断 API（§2.6）就是为这件事准备的：
真机上跑一次 `probeKeySpecLadder()` 就能看到每一档的原文回答。

### 5.2 ⑥ 反调试 / 运行环境完整性校验：本轮明确不做

这是唯一的 FAIL 判据，且是**已经评估过、已被明确决定暂缓**的一条，不是新发现。
它的实际影响与效力边界都写在这里，供下一次决策直接使用：

- **对"已解密内容"的防护有真实影响**：Frida / Xposed 可以 hook `AeadCipher.open` 的返回值
  直接拿到笔记明文。反调试是把这条路从"开箱即用"抬到"需要绕过工作"的**唯一手段**。
- **效力边界要说实话**：反调试 / 反 root 挡得住自动化工具与脚本小子，
  对**定向攻击只是延时**，且会带来假阳性（定制 ROM 被误判 → 正常用户用不了）。

### 5.3 同类缺陷：HMAC 密钥副本仍用 `SecretKeySpec`

`VaultSession.kt:1201` 的 `mac.init(SecretKeySpec(material, HMAC_ALGORITHM))` 是**与②完全同类**的问题
（副本够不到、清不掉，理由见 §2.2 的 `javap` 实测），只是对象是 HMAC 密钥而不是 DEK。

**本轮只改了 `AeadCipher`。** 是否一并收口需要单独决定 —— 修法已经现成
（`TransientAesKey` 的同一套做法），但它是**另一条密钥路径**，改动面与影响面都要单独评估。
自检脚本把这条列为 INFO 显示，不会因为"没列出来"而被忘掉。

### 5.4 Argon2 是纯 Java 实现（BouncyCastle），不是 native

标准档 64 MiB 工作内存全部落在 Java 堆里，GC 不清零；
且 BC 内部还会把 `char[] password` 转成一份 `byte[]` 副本 ——
**这两块都不受 `SecureBytes` 与 §2.1 的清零管**。

换成 native（libsodium / 官方 argon2 C 库）能显著缩小暴露面，
代价是**失去 JVM 单元测试能力**（`Argon2Kdf` 的 KDoc 已把这条取舍写明）。
本版没有动它。

### 5.5 沿用 v1.1.3 / v1.1.4 的未决项

下面这些与本版改动无关，仍然挂着，详见 `dist/FPB-v1.1.3-发布说明.md` §5
与 `dist/FPB-v1.1.4-发布说明.md` §5：

- **画面冻在首帧 / "进度条在走、画面不动"** —— 已定性为**显示这一侧**的事，
  不是应用的读取器，应用侧未改代码。
- 走查量具本身修过的两个问题（"一帧没换"被读成"画面停住"、暂停路径上的结构性竞态）。
- v1.1.4 的登录记录相关未决项。
