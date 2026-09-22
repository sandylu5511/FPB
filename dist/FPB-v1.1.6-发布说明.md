# FPB v1.1.6 正式发布说明

> **本文件是新写的**，`dist/FPB-v1.1.5-发布说明.md` **未被改动**。
> 那份文件当时把「HMAC 密钥副本仍用 `SecretKeySpec`」记在 §5.3「已知未决」里 ——
> 在那一刻那是准确的，不该被回头改写。本版把那笔挂账收口，记在这里（§2.2）。
>
> 上一版引入的 `TransientAesKey` 在本版**泛化并改名**为 `TransientSecretKey`（§2.1），
> 所以本说明里提到旧名字时，指的是同一个机制在 1.1.5 时的形态。

## 1. 版本信息

| 项 | 值 |
|---|---|
| 包 | `dist/FPB-v1.1.6.apk` |
| versionCode / versionName | `17` / `1.1.6` |
| SHA-256 | `d94266230cb763ca61f7c7ffb051a45f1a92715efa67b24b6151909ae651fe80` |
| 体积 | 3,339,353 B |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| 签名方案 | v1 无、**v2 有**、**v3 有**（用 `apksigner verify`） |
| 证书 SHA-256 | `7fe41590e2f4429b388a5f889fe9a35e92a28c21a21637ba11d0820fc7acacbd` |
| 与前序包的证书指纹 | **一致**（`7fe41590…acacbd`，与 1.1.4 / 1.1.5 相同 → 可覆盖升级） |
| 应用标签 / 包名 | `FPB` / `com.fpb.vault` |

### ⚠️ 1.1.4 / 1.1.5 / 1.1.6 **三个包的字节数完全相同**（都是 3 339 353 B）

这是本工程第二次撞上这件事（1.1.4 vs 1.1.5 是第一次），而这次是三个：

| 包 | 体积 | SHA-256（前 16 字节） |
|---|---|---|
| `FPB-v1.1.4.apk` | 3,339,353 B | `c340824fd457cc77…` |
| `FPB-v1.1.5.apk` | 3,339,353 B | `bfc66ecb3ae1f14d…` |
| `FPB-v1.1.6.apk` | 3,339,353 B | `d94266230cb763ca…` |

**它们内容各不相同，体积却一模一样。** 原因是包尾的对齐填充把差异吸收掉了：

| 项 | 1.1.5 | 1.1.6 | 差 |
|---|---|---|---|
| 条目数 | 477 | 477 | 0 |
| 条目未压缩合计 | 4,949,528 B | 4,949,642 B | **+114** |
| 条目压缩后合计 | 3,243,452 B | 3,243,677 B | **+225** |
| `classes.dex` 未压缩 | 2,934,452 B | 2,934,564 B | **+112** |
| `classes.dex` 压缩后 | 1,428,232 B | 1,428,455 B | **+223** |
| `AndroidManifest.xml` 压缩后 | 1,881 B | 1,881 B | 0 |

**所以：判断"包有没有换过"只能看 SHA-256。** 体积、`ls -l`、甚至
`aapt2 dump badging` 里除了那两个版本数字之外的任何东西，都区分不出这三个包。
本工程的验收脚本因此有一条硬判据——它先断言设备上装的是目标版本号**加证书指纹**，
再见装，而不是"看着像就上"。

## 2. 这一版改了什么

一句话：**把 1.1.5 挂账的那条同类缺陷（HMAC 密钥副本）收口**，
顺带把 1.1.5 引入的 `TransientAesKey` 泛化成两个用途共用的 `TransientSecretKey`。

一共动了 3 个生产文件、2 个测试文件、3 个脚本，删了 1 个文件：

| 文件 | 动作 |
|---|---|
| `crypto/TransientSecretKey.kt` | **新建**（取代 `crypto/TransientAesKey.kt`，后者已删） |
| `crypto/AeadCipher.kt` | 改用 `TransientSecretKey(key, AES)` |
| `session/VaultSession.kt` | `derivedRowIdFrom` 去掉 `SecretKeySpec`，改用自持副本 |
| `test/.../crypto/TransientSecretKeyTest.kt` | **新建**（11 条） |
| `test/.../audit/Audit8RegressionTest.kt` | 移出 4 条封装测试（16 → 12 条） |
| `androidTest/.../crypto/HmacKeyLifecycleInstrumentedTest.kt` | 从"诊断探针"改写为"回归判据" |
| `tools/security-selfcheck.py` | 两条 INFO 升 FAIL + 判据改名 |
| `tools/_v116_control_experiment.py` | **新建**（对照实验） |
| `tools/_audit8_control_experiment.py` | 删掉一组已搬走的实验（见 §2.6） |
| `app/build.gradle.kts`、`tools/v110-release-acceptance.py` | versionCode 16 → 17、versionName 1.1.5 → 1.1.6 |

> `TransientAesKey.kt` 是**未被 git 跟踪**的文件（本仓库有大量未提交改动），
> 删掉就无法从版本库恢复。所以它的原文留档在
> `dist/evidence/v116-refactor/TransientAesKey.kt.bak.txt`。

### 2.1 泛化：`TransientAesKey` → `TransientSecretKey`

`TransientAesKey`（1.1.5 新增）与 `VaultSession` 里需要的 HMAC 版本，
除了一句算法常量之外**逐行相同**。两份逐行相同的清零实现，迟早会有一份走样——
所以泛化成一个类，算法名从构造参数传入。

泛化时**三件事不能丢**，本版逐条守住了：

1. **`javap` 实测那段 KDoc 原样保留，而且贴着类声明。**

   那段输出（含 `void clear();  // ← 包级私有，应用侧碰不到` 那一行）不是装饰：
   它是这个类存在的**全部理由**。把它挪进"通用说明"之后，后来的人就不知道为什么
   不能直接用平台自带的销毁——然后很可能把 `SecretKeySpec` 加一句 `destroy()` 就用回去，
   而那正是被实测否掉的路。所以它现在仍然紧贴 `class TransientSecretKey` 上方。

2. **算法名是构造参数，不从 `material` 推断，也不写死。**

   ```kotlin
   internal class TransientSecretKey(
       material: ByteArray,
       private val algorithm: String,
   ) : SecretKey {
       override fun getAlgorithm(): String = algorithm
       override fun getFormat(): String = FORMAT   // "RAW"，AES 与 HMAC 共用
   ```

   `getAlgorithm()` 返回**构造时传入的那一个**。写死的后果不是报错，
   而是两类实例在日志、崩溃报告、测试失败信息里长得一模一样——
   排查时会把 `VaultSession` 的派生路径看成本地加解密。
   `toString()` 也带上了算法名：`TransientSecretKey(HmacSHA256, destroyed=false)`。

3. **计数器拆成两个。**

   ```kotlin
   internal val scrubbedAesKeyCount = AtomicInteger()
   internal val scrubbedHmacKeyCount = AtomicInteger()
   ```

   这是最容易图省事的地方：**"都是密钥副本，一个计数器就够了"**。
   那会直接毁掉两条判据的鉴别力——AES 那条路清到位了、HMAC 那条一个字节也没清，
   而总数照样在涨，两条判据同时通过。这和"把探针写在调用点"是同一类错误：
   它测的不是它声称在测的东西。

   分流放在 `destroy()` 内部，且**未知算法一个都不计**：

   ```kotlin
   override fun destroy() {
       val current = material ?: return
       current.fill(0)
       material = null
       when (algorithm) {
           AES -> scrubbedAesKeyCount.incrementAndGet()
           HMAC_SHA256 -> scrubbedHmacKeyCount.incrementAndGet()
           else -> Unit        // 未知算法不计数：宁可它变红，也不要它假绿
       }
   }
   ```

   `else -> Unit` 而不是"归到 HMAC"：将来有人把派生路径换成 `HmacSHA512` 时，
   HMAC 那条判据会因为"数量对不上"变红，逼人去显式归类；
   若归进 HMAC，它会**继续显示正常**，而它证明的已经不是它声称的事了。

### 2.2 `VaultSession.derivedRowIdFrom`：派生路径也改用自持副本

这就是 1.1.5 §5.3 那笔挂账。改动本身只有一行（`SecretKeySpec` → `TransientSecretKey`），
但**先实测、后改**，而且 KDoc 要写清三件事：

```kotlin
private fun derivedRowIdFrom(material: ByteArray, tag: ByteArray): String {
    val mac = Mac.getInstance(HMAC_ALGORITHM)
    val secret = TransientSecretKey(material, HMAC_ALGORITHM)
    return try {
        mac.init(secret)
        mac.doFinal(tag).copyOf(MANIFEST_ID_BYTES).toHex()
    } finally {
        secret.destroy()
    }
}
```

**（1）material 的生命周期 —— 这条最容易读错。**

传进来的是 `dek.expose()` / `auditKey.expose()` 交出来的 **`SecureBytes` 内部数组本身**
（一路 `return bytes`，不复制），所以它是**会话级**的：归那两个对象所有，
由它们的 `close()` 清零。而这里构造的密钥副本是**一次调用级**的，
`copyOf()` 已经把两者彻底解耦。

于是职责是清楚的：**调用方那份不归这里管，这里这份归 `finally` 管**。
不要因为"我们也会清"就省掉 `SecureBytes.close()`——那是另一件事。
反过来也一样：这里清掉的不是你传进来的那个数组，本次返回之后调用方仍可继续用它。

**（2）能清到哪、清不到哪 —— 实测，不是推断。**

2026-09-20 在 Pixel_7 AVD（SDK 37 / provider `AndroidOpenSSL` 1.0）上实测：
`getEncoded()` 的调用次数在 `init` 之后是 **1**、`doFinal` 之后**仍是 1** ——
`Mac` 在 `init` 时就把密钥复制进了自己的上下文，此后不再回读 Java 端。于是有两条结论：

- `init` 之后销毁自持副本**不影响** `doFinal`（派生结果与旧路径逐字节一致），
  这就是 `finally` 可以放在这个位置的理由；
- 但 native 上下文里那份**够不到**。这与 `TransientSecretKey` 的诚实边界是同一条：
  能清的只是"我们自己持有的那份"。**不能让「我们清了」被读成「内存里没有了」。**

**（3）为什么这里不像 `AeadCipher.open` 那样吞异常。**

`AeadCipher.open` 把 `GeneralSecurityException` 吞成 `null`，是因为调用方要区分
"密码不对"和"密文被篡改"，两者会走到同一个拒绝分支。这里的失败
（算法名写错、密钥长度非法）是**程序性错误**，而它会抛出的类型分散在两处：

- `Mac.getInstance` / `mac.init` → `NoSuchAlgorithmException` / `InvalidKeyException`
  （都是 `GeneralSecurityException`）
- `mac.doFinal` → `IllegalStateException`（`RuntimeException`，Mac 未初始化时）

要一并吞掉只能 `catch (Exception)`，那会把"派生出一个错误的 id"变成更难查的静默失败。
所以这里**只加 `finally`、不加 `catch`**：改的是内存，不是异常语义。

**（4）落盘契约不变 —— 这是整个改动唯一真正重要的判据。**

派生出来的 id 就是数据库里那些系统行（清单、真库账本、审计槽、诱饵账本）的**行 id**。
换实现如果改了结果，不会报错，只会**让升级后的库找不到自己的行**——
用户看到的是"升级完库里空了"。所以有一条设备侧断言把它钉死：
新旧两条路径算出的 id **逐字节一致**（测试值 `7fa11567abfd0dd0`）。

### 2.3 A 层单测：公共行为共用，差异行为各写各的

新建 `app/src/test/java/com/fpb/vault/crypto/TransientSecretKeyTest.kt`（11 条）：

- **公共组**（AES 与 HMAC 共用）：构造时复制入参、销毁后副本是零且复用是显式失败、
  销毁幂等、`toString` 不泄漏且带算法名、`getEncoded()` 交的是内部数组本身、`getFormat` 恒为 `RAW`
- **AES 差异组**：`getAlgorithm()` 来自构造参数、销毁**只动 AES 探针**且重复销毁不重复计数
- **HMAC 差异组**：`getAlgorithm()` 与 AES 实例**不同**、销毁**只动 HMAC 探针**且重复销毁不重复计数
- **鉴别力**：未知算法不计入任何一条探针

**为什么不写成 `@RunWith(Parameterized::class)` 的两组参数**（这是泛化时最省事的一步）：
参数化之后失败信息只会说"某一组挂了"，而这两组的失败含义完全不同——
AES 那条挂了要去查 `AeadCipher`，HMAC 那条挂了要去查 `VaultSession` 的派生路径。
公共行为的测试共用一份代码没问题，**差异行为必须各写各的**，让红的用例自己说出是哪条路。

同时 `Audit8RegressionTest` 从 16 条变成 12 条：那 4 条**封装本身**的测试移到了新文件
（断言一字未改），留下的 12 条只问一件事——**生产路径有没有真的用上它**。
"零件坏"与"接线断"分在两个文件里，将来红了不必先判断是哪一类。

### 2.4 B 层仪器测试：从"诊断"改写为"回归判据 + 平台哨兵"

`HmacKeyLifecycleInstrumentedTest` 原来是**评估用例**：一堆带 `[HMAC实测]` 前缀的 `println`，
去问设备"平台在这一步到底做了什么"。评估做完，这个文件的价值就变了。

本版做了三件事：

1. **去掉全部 `println`**，断言**一条都没删**。诊断输出已经在
   `dist/evidence/hmac-key-lifecycle/logcat-System.out.txt` 里；
   而"去没去掉"本身是可核对的——
   本版跑完之后 `adb logcat -d -s System.out | grep HMAC实测` 的结果是 **0 行**。
2. **明确区分两类断言**，并在 KDoc 里列表说明：
   - **平台的事实（哨兵）**：`SecretKeySpec` 构造时复制入参、`getEncoded()` 每次新建副本、
     `destroy()` 一个字节也清不掉、`Mac` 在 `init` 时取走密钥、反射调不到那个包级私有的 `clear()`。
     这些**变红不等于代码有缺陷**，而是"我们赖以设计的那条平台行为变了"。
     没有这类哨兵，平台一升级，旧修法就从"合适"悄悄变成"不合适"，而所有测试照样全绿。
   - **我们的不变式**：派生 id 逐字节一致（落盘契约）、探针在 ART 上同样工作且两条不串台。
3. **删掉一条纯诊断用例**（`环境探针_…`，它唯一的价值是把被测对象打印出来）、
   **新增一条**（`生产形状的销毁会计入HMAC探针`）。净条数不变：**11 条**。

那条新增用例有一个非它不可的理由：`tools/security-selfcheck.py` 的两条判据
是在**源码上**认定"探针会被触发"的。探针机制在 JVM 上工作，不代表在 ART 上也工作
（`AtomicInteger`、内联、调用点裁剪都可能不一样）——所以要在真实运行时把它确认一遍。

### 2.5 自检脚本：两条 INFO 升 FAIL

1.1.5 里这一条是 `record("INFO", …, "**同类缺陷（已单独实测评估，尚未收口）**…")`——
报 INFO 而不是 FAIL 是**对的**：当时"是否一并收口"是用户还没做的决定，
把它写成 FAIL 等于替用户宣布了一个他没做过的决定。本版用户决定收口，于是它升成两条判据：

| 判据 | 钉住的 |
|---|---|
| `VaultSession 不得再用 SecretKeySpec` | 匹配**整个文件**里所有 `SecretKeySpec(`，不只是 `mac.init` 那一处 |
| `每个 HMAC 密钥副本都被销毁过` | `derivedRowIdFrom` 里构造处/销毁处数量对上，**且落在 `finally` 内** |

**这两条判据的写法本身有两个坑，都踩过：**

- **必须先剥注释。** 新代码的注释里到处是 `SecretKeySpec` —— 解释"为什么不能用它"。
  不剥注释就会把"解释"读成"使用"，判据当场变成永远 FAIL，然后下一个人会把这条 FAIL
  当噪声删掉。本工程在建议 1、2 上吃过同款亏（KDoc 里"为何刻意不设 X"被读成"代码里设了 X"）。
  豁免机制也照搬了凭据那条：`SECRETKEYSPEC_EXEMPT` 当前**为空**，
  且"登记了却命中不了"会被校验出来——留一张空表是机制问题，
  没有机制，下一个人只会在判据里加一句 `if "某理由" not in code`，那条判据就开始退化成装饰品。
- **"在 `finally` 里"这半条不能省。** 这一条比 AES 那条多钉了一半，理由不是对称：
  `Mac.getInstance` 与 `mac.init` 都会抛，把 `destroy()` 挪到 `try` 体末尾之后
  **数量仍然对得上**，只有异常路径不再清——而那恰恰是最容易漏、也最该清的时刻。
  这半条有独立的对照实验（下面 §3.3 的 **S3**），它确实会红。

判据总数因此从 47 变成 **48**（+2 条 FAIL 级判据、−1 条 INFO）。

### 2.6 顺带修掉的一处旧脚本隐患

`tools/_audit8_control_experiment.py`（1.1.5 的对照实验脚本）里有一组 C 实验，
钉的是 `TransientAesKey.destroy()`。类改名之后那一组会**找不到目标**——
留下的是一句 `assert 补丁未命中`，不会静默通过，但脚本已经跑不完整了。
本版把那一组搬到了新脚本（`_v116_control_experiment.py` 的 U1/U2），
原文件保留 A/B 两组并注明搬去了哪里。

顺带说明一个**不能靠"看一眼"解决的**问题：类改名要连带 **grep 整个仓库**，
包括脚本。脚本里写死的文件名一旦对不上，`read()` 会返回空串——
而空串在不同判据下可能判 PASS 也可能判 FAIL，两种都不是"文件被改名了"这个事实。
本版对 `tools/` 全目录做了 grep 复核，只剩历史说明性文字。

## 3. 怎么证明它是对的

### 3.1 单测（372 项，0 失败）

```
./gradlew testDebugUnitTest   # BUILD SUCCESSFUL → 372 条 / 失败 0 / 错误 0 / 跳过 0（28 个测试类）
```

条数变化（**"+7"不是"新增 7 条"那么简单**）：

| 变化 | 条数 |
|---|---|
| 1.1.5 的基线 | 365 |
| 新建 `TransientSecretKeyTest`（§2.3） | **+11** |
| `Audit8RegressionTest` 移出 4 条封装测试（搬进上面那个文件） | **−4** |
| 1.1.6 合计 | **372** |

移走的那 4 条断言**一字未改**，只是换了家。

### 3.2 仪器测试（19 项，0 失败 0 跳过）

```
./gradlew connectedDebugAndroidTest   # BUILD SUCCESSFUL → 19 条 / 失败 0 / 错误 0 / 跳过 0
```

19 = `BiometricGateInstrumentedTest` 8（1.1.5 的，本版未动）+ `HmacKeyLifecycleInstrumentedTest` 11。

**"0 跳过"本身是证据**，所以单独列出来：1.1.5 那一轮出过"核心断言永远跳过、
理由却写成环境限制"的事。本版两份文件都没有用 `assumeTrue` 掩盖失败。

去掉 `println` 这件事也在这里复核：本版跑完后的 logcat 里
`grep -ac "HMAC实测"` 的结果是 **0**（证据在 `dist/evidence/v116-androidtest/logcat-System.out.txt`）。

### 3.3 对照实验（10 组）

脚本 `tools/_v116_control_experiment.py`（自己打补丁、跑、再**原样还原**，异常路径也还原）。
日志 `dist/evidence/v116-control-experiment.log`。

**这不是测试的一部分，是一次性取证**——"这些断言能发现问题"这件事，
只能通过让它们失败来证明。

| 组 | 放回去的缺陷 | 变红 |
|---|---|---|
| U1 | `TransientSecretKey.destroy()` 去掉 `fill(0)` | 1 |
| U2 | 去掉 `material = null` | 4 |
| U3 | 去掉整段计数分派 | 5 |
| U4 | `getEncoded()` 改成每次复制 | 2 |
| U5 | `getAlgorithm()` 写死 `AES` | 1 |
| U6 | 未知算法分支改成喂 HMAC 探针 | 1 |
| U7 | `AeadCipher.seal` 去掉 `finally { destroy() }` | 1 |
| S1 | `derivedRowIdFrom` 走回 `SecretKeySpec` | 2 |
| S2 | `derivedRowIdFrom` 去掉 `finally { secret.destroy() }` | 1 |
| S3 | `destroy()` 从 `finally` 挪到 `try` 末尾 | 1 |

**U2 / U3 / U4 / S1 多于 1 条是正常的，而且逐条核对过**——一个改动会同时破坏多个不变式：

- **U2**（不置空引用）：`isDestroyed` 开始撒谎（"销毁之后副本是零"红）；
  `getEncoded()` 仍可复用（幂等失去前提）；第二次 `destroy()` 又清一次 →
  计数被重复自增（两条计数器用例红）。
- **U3**（不计数）：两条计数器用例 + 三条 AES 生产路径用例（`seal` / `open` / `open` 失败）
  都读 `scrubbedAesKeyCount`，一起红。
- **U4**（`getEncoded()` 复制）：`assertSame` 红；同时**被复制出去的那份**没被清零 →
  "销毁之后副本是零"红。
- **S1**（走回 `SecretKeySpec`）：判据一红；同时 `derivedRowIdFrom` 里再也找不到
  `TransientSecretKey`，判据二以"这条判据失去了对象"红。

要的是"**每一条红都能说出理由**"，而不是数字等于 1。
真正危险的信号是反过来——**改了一处、一条都没红**：那说明这组断言没有鉴别力。

**S3 那一组单独值得说**：它把 `destroy()` 从 `finally` 挪到 `try` 体末尾，
"构造处 / 销毁处数量"**完全没变**，只有"落在 `finally` 内"那半条判据抓住了它 ——
而它对应的真实后果是"派生失败的那条路径上，一份明文密钥副本留在堆上"。

基线（还原之后）：单测 0 失败；自检只有 1 条 FAIL（反调试，已明确暂缓）。

### 3.4 安全自检脚本（判据 48 条）

```
python tools/security-selfcheck.py --device   # 退出码 0
判据 48 条 | PASS 38 | FAIL 1 | N/A 2 | INFO 7
```

与 1.1.5 的 `47 / 36 / 1 / 2 / 8` 相比：**+2 条 FAIL 级判据、−1 条 INFO**（§2.5）。

唯一那条 FAIL 仍然是 **⑥ 反调试 / 运行环境完整性校验**——
用户 2026-09-18 已明确决定本轮不做，它的文案里写着这一点，
**不留一条"看起来像疏漏"的红**。

证据：`dist/evidence/security-selfcheck-2026-09-20-v116-on-116apk.log`；
三组自检对照实验的输出在 `dist/evidence/v116-control/selfcheck-S{1,2,3}.log`。

**这条证据补过一次。** 首轮自检 14:42 跑，`FPB-v1.1.6.apk` 14:43 才构建出来。
后果是：三条产物级判据（无 INTERNET 权限 / 不可调试 / 禁止系统备份）
**实测的是 1.1.5 的包**——1.1.6 的包在"会不会带上 INTERNET 权限、可不可调试"这件事上
一条实测都没有，而脚本不会因此报错：它只是在另一个对象上通过了。
（这条失效模式刻在 `apk_path()` 自己的 docstring 里：
"选错包不会报错，它只会让所有断言在另一个对象上通过。"）
好在日志第 5 行把被测对象与它的 SHA-256 明明白白印了出来，不是悄悄通过的。

包构建完成后重跑同一支脚本：

```
python tools/security-selfcheck.py --device   # 退出码 0，被测对象已是 1.1.6 包
产物级校验对象：FPB-v1.1.6.apk（3339353 B，sha256=d94266230cb763ca…）
判据 48 条 | PASS 38 | FAIL 1 | N/A 2 | INFO 7
```

第二份日志与第一份 `diff` 之后**只有 4 行不同**（被测对象那一行 + 三条产物级判据的标签），
其余 44 条逐字节一致。两个包**字节数相同**（都是 3,339,353 B）、
SHA-256 不同（`bfc66ecb…` vs `d9426623…`）——这几行正好把这个事实并排钉在一起：
**"体积一致"在这里什么都证明不了，判据只有哈希。**

留档：`dist/evidence/security-selfcheck-2026-09-20-v116.log`（首轮，被测对象为 1.1.5 包）
与 `dist/evidence/security-selfcheck-2026-09-20-v116-on-116apk.log`（重跑，被测对象为 1.1.6 包）
两份都留着——首轮那份是"被测对象写进了日志"这个习惯的受益者，不该删。

### 3.5 release 包验收（模拟器）

```
python tools/v110-release-acceptance.py   # 退出码 0，13 分 02 秒（14:43:46 → 14:56:48）
49 通过 / 0 未通过 / 5 不适用
[14:56:48] 共 15 条判据，未通过 0 条
[14:56:48] v1.1.6 正式包验收全部通过
```

脚本**先断言包身份、再见装**（`versionCode / versionName = 17 / 1.1.6` + 证书指纹与前序包一致），
所以"跑的是哪个包"不靠人肉确认。分四段：

| 段 | 内容 | 结果 |
|---|---|---|
| 0 | 包身份：版本号 / v2+v3 签名 / 证书指纹一致 / 体积量级 | 5 项全 PASS |
| 1 | 全新安装：release 包**开箱即禁截屏** | PASS，且那一帧平均亮度 **0.00/255**（不是"标志位写了就算"） |
| 2 | 装 v1.0.9、造出「一条记录 + 一张照片」作为升级前基线 | 走完引导，截图 3 张 |
| 3 | `install -r` 覆盖升级、**不清数据** | 5 项 PASS：版本变成 1.1.6、照片还在、**仍能打开**、偏好保留 |
| 4 | R8 之后整条**视频链路**重跑（复用 v110 走查的 B 轮，判据一条不减） | 「未通过 0 条」 |

**49 / 0 / 5 与 1.1.4、1.1.5 两轮逐项一致。**

5 条"不适用"是**沙箱类**判据：release 包 `debuggable=false`，`run-as` 被拒，读不到应用沙箱
（例如"删除记录后视频密文也一起消失"）。它们不是"跳过"——日志里逐条写明了为什么不适用，
并注明"这一条在 debug 包那轮验过"。

现场证据：`dist/evidence/v116-release/` 下 62 个文件（22 张截图 + 窗口 dump + 像素读数）。

### 3.6 构建

```
./gradlew testDebugUnitTest                   # BUILD SUCCESSFUL → 372 / 0
./gradlew :app:dexBuilderDebugAndroidTest     # BUILD SUCCESSFUL（DEX 关）
./gradlew connectedDebugAndroidTest           # BUILD SUCCESSFUL → 19 / 0 / 0
./gradlew assembleRelease                     # BUILD SUCCESSFUL（R8 已跑）
python tools/security-selfcheck.py --device   # 48 条：38 / 1 / 2 / 7，退出码 0
python tools/v110-release-acceptance.py       # 49 / 0 / 5，退出码 0
```

R8（`minifyReleaseWithR8`）确实跑了，而本版改的两处都是**编译器看不见的契约**：

- **`destroy()` 有没有真的被调用**（每次派生系统行 id 都会走到它）：漏掉不报错，
  只是"堆上多留一份明文"。
- **派生出的行 id 有没有变**（落盘契约）：变了不报错，只是**升级后找不到自己的行**。

两者都靠测试、判据与对照实验钉，而不是靠"打开看一眼能用"。

## 4. 升级说明

### 4.1 用户需要做什么：**什么都不用做**

**这一版不动密钥规格、不动别名、不动任何落盘格式。** 它是 1.1.5 那次换代的后续收尾，
不是又一次换代：

| 会不会影响用户 | 这一版 |
|---|---|
| 需要**重新启用一次生物识别** | **不需要**（1.1.5 需要，本版不需要） |
| 已存的数据（笔记 / 图片 / 视频 / 登录账本） | 不动。行 id 的派生结果**逐字节一致**（§2.2） |
| 界面文字 / 交互 | 无变化 |
| 需要重新登录 / 重设主密码 | 不需要 |

唯一可感知的变化是包本身：版本号 1.1.6、体积与上一版**完全相同**（§1 那个提醒），
以及"打开笔记时少留一份密钥副本"——后者用户看不见，也不该看见。

### 4.2 兼容性

- `minSdk 26` / `targetSdk 35` 都不变。
- 签名证书与前序包**一致**（`7fe41590…acacbd`）→ **可覆盖升级**，不需要卸载重装。
- 覆盖升级后数据保留由验收脚本实测（§3.5 第 3 段：先装 1.0.9 造出一条记录与一张照片，
  再 `install -r` 本包、**不清数据**，断言照片还在**且还能打开**——
  "能不能解密"才是数据在不在的判据）。

## 5. 已知未决

### 5.1 1.1.5 的 §5.3（HMAC 密钥副本）**已在本版处置**

`dist/FPB-v1.1.5-发布说明.md` 的 §5.3 记着「同类缺陷：HMAC 密钥副本仍用 `SecretKeySpec`」，
并说明"自检脚本把这条列为 INFO 显示，不会因为'没列出来'而被忘掉"。

**那份文件没有被改动**——它记的是当时的状态。本版把那笔挂账收口（§2.2），并且：

- 那条 INFO 不再是 INFO，而是两条 FAIL 级判据（§2.5）；
- 评估阶段产生的文档与证据**全部保留**：`dist/HMAC密钥副本-独立评估.md`、
  `dist/evidence/hmac-key-lifecycle/`。它们现在是"为什么这么做"的出处，不是过期材料。

### 5.2 验收跑在**模拟器**上，不是真机

所有设备侧证据（验收、仪器测试、对照实验里的设备部分）都来自 `Pixel_7` AVD
（`sdk_gphone16k_x86_64`，API 37）。模拟器能证明"逻辑与契约没坏"，
证明不了"某台真机的 StrongBox / 生物识别行为"——后者只能靠真机现场测。

### 5.3 ⑥ 反调试 / 运行环境完整性校验：仍不做

同 1.1.5 §5.2。这是用户 2026-09-18 明确决定的（技术红线之外的**产品取舍**：
它会误伤调试与自动化，而收益是可被绕过的）。自检脚本里那条 FAIL 是**已知缺口**，
不是新发现——它每次都会出现在结论里，直到有人决定处置它。

### 5.4 Argon2 是纯 Java 实现（BouncyCastle），不是 native

标准档 64 MiB 工作内存全部落在 Java 堆里，GC 不清零；BC 内部还会把 `char[] password`
转成一份 `byte[]` 副本。这两块都不受 `SecureBytes` 管。
换 native（libsodium / 官方 argon2 C 库）能显著缩小暴露面，代价是失去 JVM 单元测试能力——
这条取舍写在 `Argon2Kdf` 的 KDoc 里，与 1.1.5 的结论一致。

### 5.5 沿用历史版本的未决项

`README.md` / `HANDOFF.md` 里的交接基线仍指向 `v1.0.9`（落后多个版本），
是否随某一版一并更新尚未决定——本版按"只做点名那件事"的边界规则**未改**。
