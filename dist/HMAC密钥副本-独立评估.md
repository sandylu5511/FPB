# HMAC 密钥副本（`SecretKeySpec`）独立评估

> 对应 `tools/security-selfcheck.py` 里那条 **INFO**：`同类缺陷（未在本轮范围）：HMAC 密钥副本仍用 SecretKeySpec`。
> 这份文档回答的是"**要不要修、怎么修、什么时候修**"，而不是"已经修好了"。
> **本轮不含任何生产代码改动。**

---

## 0. 一句话结论

| 问题 | 结论 |
|---|---|
| 要不要修 | **要**，但它是"缩小暴露窗口"，不是"堵住一个可利用的洞" —— 优先级低于 1.1.5 里改掉的 AES 那条 |
| 为什么可以先不修 | **调用频率低、副本寿命短**（每次派生一份，随方法返回即不可达），没有用户可感知的症状 |
| 修法是否可行 | **可行，且已被实测证明**：`Mac` 只在 `init` 时读一次密钥（`getEncoded()` 调用次数 = **1**），之后彻底不再回读 Java 端 → `init` 之后立刻清掉我们那份副本是**功能上安全的** |
| 修法形状 | 把 `TransientAesKey` 泛化成 `TransientSecretKey(material, algorithm)`，两处共用一份实现（**不要**再复制一份 `TransientHmacKey`） |
| 有个必须注意的坑 | 泛化之后**探针计数器必须分成两个** —— 共用一个会让 HMAC 路径的销毁去污染 `AeadCipher` 的断言（假绿） |
| 代价 | 无用户可感知代价（不像 1.1.5 那次要重新启用生物识别）；派生出的行 id 不变（已实测逐字节一致） |

---

## 1. 先说后果：它会导致什么结果

**直接回答：用户看不到任何症状。** 这正是这类问题最麻烦的地方，所以下面分开说"它是什么"和"它坏在哪"。

### 1.1 它是什么

`VaultSession.kt:1200-1202` 是派生系统行 id 的唯一实现（清单、真库账本、审计槽、诱饵账本共用它）：

```kotlin
private fun derivedRowIdFrom(material: ByteArray, tag: ByteArray): String {
    val mac = Mac.getInstance(HMAC_ALGORITHM)
    mac.init(SecretKeySpec(material, HMAC_ALGORITHM))   // ← 这里复制了一份 32 字节明文密钥
    return mac.doFinal(tag).copyOf(MANIFEST_ID_BYTES).toHex()
}
```

`SecretKeySpec` 的构造函数会**复制**入参（本次实测确认）。那份副本应用侧**拿不到引用**：

- 没有可用的 `destroy()`（实测：抛异常，且一个字节也没清）
- `getEncoded()` 每次返回**新的**副本，清它没用（实测）
- 反射那条 `clear()` 也走不通（实测：`NoSuchMethodException`）

于是那份 32 字节明文密钥只能**等 GC**。而 `material` 本身（`SecureBytes` 的内部数组）是会被 `close()` 正确清零的 —— **清对了那个，清不到这个**。

### 1.2 它坏在哪（不夸大）

**能防住的**：进程内存里"本可以立刻清掉、却因为够不到而多停留一段时间"的明文密钥副本。攻击者若能通过**别的漏洞**读到本进程的堆（内存转储、崩溃报告、被换出的页），这段窗口就是他多出来的机会。

**防不住的（必须写清楚）**：拿到 root 的攻击者。他能直接读 Keystore、读 native 内存，也能读这份副本 —— 这类加固从来不是为挡住他设计的。

### 1.3 为什么可以排期，而不是插队

和 1.1.5 里改掉的 AES 那条比，这一条的暴露面**明显更小**：

| 维度 | AES（`AeadCipher`，已在 1.1.5 修掉） | HMAC（本文，未修） |
|---|---|---|
| 调用频率 | 每次加解密（读写笔记字段、每次落盘） | 每次**派生行 id**（解锁、写登录记录） |
| 副本寿命 | 一次加解密 | 一次派生（同样短） |
| 材料来源 | 会话 DEK | 会话 DEK **或**审计钥匙 |
| 同一类问题的份数 | 2 处（`seal` / `open`） | **1 处** |

生产代码里 `SecretKeySpec` 与 `Mac.getInstance` **各只有这一处**（已全仓核对，见 §6 证据），所以这是一条**单点**任务，收口成本很低 —— 但正因为低，更不该赶在别的改动里顺手做掉：它需要自己的一次构建 + 一次设备验收。

---

## 2. 实测：平台到底怎么做的

**纪律：先实测，再决定修法。** 下面每个格子都是这台设备（Pixel_7 AVD，SDK 37）上跑出来的，
不是从 `javap` 或源码推断的 —— 上一轮就是靠"直接问 provider"才发现 `AndroidKeyStore` 根本没注册
`KeyFactory/AES`，在那之前一整条断言被当成"环境限制"跳过了一轮。

**测试类**：`app/src/androidTest/java/com/fpb/vault/crypto/HmacKeyLifecycleInstrumentedTest.kt`
**原始输出**：`dist/evidence/hmac-key-lifecycle/logcat-System.out.txt`（标记 `[HMAC实测]`）

### 2.1 环境（结论只对这句话成立）

```text
[HMAC实测] sdk=37 provider=AndroidOpenSSL provider 版本=1.0
[HMAC实测] Mac 实现类=javax.crypto.Mac 来源=null
[HMAC实测] SecretKeySpec 来源=null
[HMAC实测] Mac 算法=HmacSHA256 macLength=32
```

两点值得记下来：

- provider 在 SDK 37 上叫 **`AndroidOpenSSL`**（不是长久以来那个 `Conscrypt` 的名字）。
  **一切"平台会不会照做"的结论都绑在具体 provider 上** —— 换 provider 就是另一个实现，必须重测。
- `Mac.getInstance()` 返回的是框架类 `javax.crypto.Mac`，真正的实现在 SPI 里，应用侧看不到。
  所以 §2.3 那种"问它自己"的探针才有必要。

### 2.2 material 被复制了几次、清哪一份才有用

| 探针 | 实测输出 | 含义 |
|---|---|---|
| `SecretKeySpec` 内部数组与入参是同一个对象？ | **false** | 构造时**复制**了一份 |
| 清掉入参数组后，`getEncoded()` 是否仍为原值 | **true** | 清 `material` **与那份副本无关** |
| `getEncoded()` 两次返回同一对象？ | **false** | 每次**新建**副本 |
| 清掉返回的副本后 key 是否仍完好 | **true** | "清返回值"这种做法**无效** |

**这张表的用处是排除两条看起来很像修法的错路**：

1. 以为"反正 `SecureBytes.close()` 会清零"就够了 → 清的是**另一份**数组，副本原封不动。
2. 以为 `key.getEncoded().fill(0)` 能清掉 → 清的是**刚新建、马上被丢掉的**那一份，
   真正被 `Mac` 用过的副本一个字节都没动。**这种"修复"不会报错，也不会生效。**

### 2.3 `destroy()` 在 Android 上实际做了什么

```text
[HMAC实测] destroy() -> javax.security.auth.DestroyFailedException: null
[HMAC实测] isDestroyed() -> false
[HMAC实测] destroy() 之后 getEncoded() 是否仍为原值：true
```

这是本次实测**最硬的一条**：

- `destroy()` **抛异常**（`DestroyFailedException`，message 为 null）—— 走的是
  `javax.security.auth.Destroyable` 的默认实现，`SecretKeySpec` 没有覆盖它。
- `isDestroyed()` 返回 **false** —— 连"我已经销毁了"这个**声明**都没给。
- `getEncoded()` **仍返回原值** —— 内容一个字节也没变。

**三条合起来才是完整的坏消息**：不是"清得不够干净"，而是"**声称能清、实际没清、还说没清过**"。

> ⚠️ 这个坑在 JVM 上结论**相反**：OpenJDK 的 `SecretKeySpec` 覆盖了 `destroy()`，能真的清掉。
> 所以"在单测里验一下密钥能不能销毁"会得到一个**在 Android 上不成立**的结论。
> 这也是本条评估必须落在 `androidTest` 而不是 JVM 单测上的原因。

### 2.4 Mac 是 init 时取走密钥，还是每次运算回来读（**决定修法的那条**）

做法：用一把**自持字节**的密钥（形状与生产代码的 `TransientAesKey` 一致：
`getEncoded()` 交内部数组、不额外复制），于是应用侧唯一那份数组在我们手里，想清随时能清。

| 探针 | 实测输出 | 含义 |
|---|---|---|
| `getEncoded()` 调用次数：init 后 | **1** | `init` 取走一次 |
| `getEncoded()` 调用次数：doFinal 后 | **1**（没变） | **之后彻底不再回读 Java 端** |
| 自持数组真的被清零 | true | 清零点确实生效 |
| 清零后 `Mac` 是否仍算得出 | true | — |
| **清零前后输出是否一致** | **true** | 密钥已在 provider 自己的上下文里（native） |
| init 后**立刻**销毁副本，输出是否仍与参考值一致 | **true** | — |
| init 后 `destroy()`（`SecretKeySpec` 路径）是否影响 `doFinal` | **不受影响** | — |
| 自持副本与 `SecretKeySpec` 算出的派生结果是否一致 | **true**（两侧 id 都是 `7fa11567abfd0dd0`） | 换实现**不改落盘契约** |

**结论**：`Mac` 在 `init` 时就把密钥取走了。`init` 之后清掉/销毁我们那份副本，
对后续运算**没有任何影响** —— 所以修法的落点可以放在 `init` 之后的 `finally` 里，
与 `AeadCipher` 完全一致。

**同时必须写明的证据上限**：provider 那份副本在 **native 内存**里，应用侧
**没有任何手段触及**（需要 root 或内存离线分析）。我们做到的只是
"**堆上那份由我们持有的明文副本有了确定的终点**"，而不是"内存里没有密钥了"。
这句话要原样写进将来的 KDoc —— 否则"我们清了"很容易被读成"没有了"。

### 2.5 `Mac` 实例能否复用

| 探针 | 实测输出 | 含义 |
|---|---|---|
| 同一实例 A→B→A 三次 | A 的结果**稳定复现**，换 B 后输出不同 | 实例可复用，`init` 会重置状态 |
| `doFinal` 连算两次 | 结果一致 | `doFinal` 后自动回到初始态，无需手动 `reset()` |

**但"可复用"不等于"可以共享"**：`javax.crypto.Mac` **不是线程安全的**
（`init` / `update` 会改内部状态）。生产代码现在是**每次派生都新建一个实例**，
不存在共享，所以这一条**当前没有风险**；反过来说，
**将来若有人为了省一次 `getInstance` 而共享实例，跨请求就会串味** ——
而且串出来的还是**看起来完全正常的十六进制 id**，不报错、不崩溃，只是"找不到那一行"。

> 诚实标注证据等级：**"非线程安全"这一条不是本轮的实测结论**，
> 而是按 API 约定与实现形状的判断（竞态不可靠复现，把它写成断言只会制造一条
> 会随机变红的用例）。本轮实测支持的只有"**单线程下可复用**"。

### 2.6 顺带排除一条捷径：反射调 `clear()` 走不通（INFO）

`javap` 显示 AOSP 的 `SecretKeySpec` 有一个包级私有的 `void clear()`。
如果它能被反射调到，理论上存在一条"原地清掉内部副本"的捷径。实测：

```text
[HMAC实测] 反射 clear()：查找 clear() -> NoSuchMethodException: javax.crypto.spec.SecretKeySpec.clear []；
          调用 -> NoSuchMethodException: javax.crypto.spec.SecretKeySpec.clear []；
          之后 getEncoded() -> 32 字节 / 全零=false
```

`NoSuchMethodException` 有两种成因，应用侧**无法区分**：方法真的不在运行时类里，
或者是 hidden API 策略让 `getDeclaredMethod` 报"不存在"。两种成因的结论是一样的：

**这条路在应用侧不可用。** 记下来是为了避免以后有人再想一遍 ——
即使某天能调到，反射私有方法本身也是脆弱、会被系统更新打断的做法，
不该出现在加密路径上。

---

## 3. 逐条回答评估清单

### 3.1 `material` 的来源与生命周期

三条来源，全是 `SecureBytes` 的内部数组（`expose()` 一路 `return bytes`，**不复制**）：

| 调用点 | `material` 来自 | 生命周期 |
|---|---|---|
| `VaultSession.kt:1039` `derivedRowIdWith(own, …)` | 会话 DEK（`VaultDek.expose()`） | 与**解锁会话**同步：`close()` 时清零 |
| `VaultSession.kt:1095/1099` | 同上 | 同上 |
| `VaultSession.kt:1123` `derivedRowId(…)` | `requireUnlocked()` 的 DEK | 同上 |
| `VaultSession.kt:1166` `LogKey.Own` | 同上 | 同上 |
| `VaultSession.kt:1167` `LogKey.Audit` | **审计钥匙**（`AuditKey.expose()`） | 与**解锁会话**同步 |
| `VaultSession.kt:1177` | **审计钥匙**（诱饵账本行 id） | 同上 |

**关键点**：`material` 的生命周期是"**会话级**"，而 `SecretKeySpec` 那份副本的生命周期是
"**一次派生调用**"。**两者不是一回事** —— 所以"会话锁定时把 DEK 清掉"这个既有机制
**完全覆盖不到**那份副本。这正是本条问题成立的原因。

### 3.2 Mac 实例是否可复用、是否线程安全

见 §2.5。一句话：**单线程可复用，不可共享**；生产代码每次新建，当前无风险。

### 3.3 `destroy()` 在 `init` 后的实际行为（实测）

见 §2.3 / §2.4。一句话：**`destroy()` 是无效的（抛异常、不清零、`isDestroyed` 仍是 false）；
但 `init` 之后销毁密钥对象对 `doFinal` 没有影响** —— 所以问题不在"销毁会不会破坏运算"，
而在"**销毁根本没发生**"。

### 3.4 是否需要 `TransientHmacKey` 这类封装

**需要**，且实测已经证明可行（§2.4）。但**不建议新写一个 `TransientHmacKey`**：

两个类除了一句 `ALGORITHM` 常量（`"AES"` / `"HmacSHA256"`）之外**逐行相同**。
这个工程的注释里自己写着这句话（`VaultSession.kt:1184`）：

> 分成几份实现，迟早会有一份走样 —— 而走样之后不会报错，只会悄悄多出一条可被识别的线索。

清零逻辑比业务逻辑更符合这句话：**它没有症状**。一份走样的清零实现，
在测试里、在界面上、在日志里都不会有任何表现。

**建议做法**：把 `TransientAesKey` 泛化为 `TransientSecretKey(material, algorithm)`，
`AeadCipher` 与 `VaultSession` 两处共用。

**泛化时必须一起处理的坑**（漏了会让既有断言变成假绿）：

> `TransientAesKey` 里那个探针计数器 `scrubbedAesKeyCount` 是**假绿的克星** ——
> `Audit8RegressionTest` 用它证明"AES 密钥副本真的被销毁了"。
> 如果泛化后两处共用一个计数器，那么 **HMAC 路径的销毁会把 AES 那条断言的计数喂饱**：
> 就算有人把 `AeadCipher` 里的 `destroy()` 删掉，断言照样绿。
> → **两个算法必须各有一个计数器**（或一个按算法分键的 map），且断言只读自己那个。

### 3.5 对应的单测

分两层，因为**两层能证明的东西不一样**：

**A. JVM 单测（`app/src/test/`）—— 测封装本身**

| 用例 | 钉住的事实 |
|---|---|
| `destroy()` 之后底层数组全零 | 清零真的发生 |
| 探针计数写在 `destroy()` **内部**（不是调用点） | 防假绿：删掉调用点的 `destroy()`，计数必须不动 |
| `destroy()` 幂等、不重复计数 | 计数只代表"清掉了一份" |
| `getEncoded()` 交内部数组、不复制 | 不多留一份够不到的明文 |
| `destroy()` 之后再 `getEncoded()` 拿不到密钥 | 销毁后的误用要当场炸，而不是拿到全零密钥算出一个错 id |
| **HMAC 结果与 `SecretKeySpec` 一致** | 替换不改行为（JVM 上验 SunJCE 一路） |

**B. 仪器测试（`app/src/androidTest/`）—— 测平台**

| 用例 | 为什么必须上设备 |
|---|---|
| `init` 后清零/销毁不影响 `doFinal` | 取决于 provider（`AndroidOpenSSL`）的 `init` 语义 |
| **派生结果逐字节一致** | 这是**落盘契约**，且 provider 与 JVM 不同 |
| `destroy()` 在 Android 上无效 | JVM 结论相反，只能在设备上验 |

> **本轮交付的正是 B 层**（`HmacKeyLifecycleInstrumentedTest`，11 条）。
> A 层要等封装真正落地时再写 —— 现在封装还不存在，写它就是"先写测试再想需求"。

---

## 4. 修法选项对比

| 方案 | 做法 | 优点 | 代价 / 风险 |
|---|---|---|---|
| **A. 保持现状** | 只在自检脚本留 INFO | 零风险 | 问题一直在；INFO 文案已经写明"需要单独决定"，不会忘 |
| **B. 泛化 `TransientAesKey` → `TransientSecretKey`**（建议） | 一份实现两处共用 | 只写一份清零逻辑；改动集中在 `AeadCipher` + `VaultSession` | 要动既有类名/引用与既有测试；**计数器必须拆分**（见 §3.4） |
| **C. 新写 `TransientHmacKey`** | 照抄 AES 那份 | 改动最小、不动既有代码 | 留下两份逐行相同的实现，将来必有一份走样 |
| D. 用 `Mac` 的 `Provider` 私有接口清 native 副本 | — | — | **不可行**：native 上下文应用侧无接口 |

**不建议的方案（记录以免以后有人再想一遍）**：

- ❌ `key.getEncoded().fill(0)` —— 实测：清的是新建即弃的那份（§2.2）
- ❌ 反射调 `SecretKeySpec.clear()` —— 实测：`NoSuchMethodException`（§6）
- ❌ 靠 `SecureBytes.close()` 覆盖 —— 实测：`SecretKeySpec` 已复制，够不到（§2.2）

---

## 5. 建议的落地步骤（**单独排期、单独提交**）

1. 把 `TransientAesKey` 泛化为 `TransientSecretKey`（保留原 KDoc 里那段 `javap` 实测，
   它是这个类存在的全部理由），AES 与 HMAC 两个计数器拆开。
2. `VaultSession.derivedRowIdFrom` 改为自持副本 + `finally { destroy() }`，
   并**复用** `AeadCipher` 那种双层 try（内层销毁、外层兜住密码学异常）。
3. 补 A 层单测（§3.5）＋ 把本轮 B 层用例**从"评估探针"改写成"回归判据"**
   （去掉 `println` 诊断、把"平台事实"断言改成"我们的不变式"断言）。
4. 把自检脚本那条 INFO 升级成正式判据：`VaultSession` 不得再出现 `SecretKeySpec`，
   且每个 HMAC 密钥副本都被销毁过。
5. 版本号 +1，重建、跑一次完整设备验收（本工程的规矩：**包内容变了就必须升版本号**）。

**这一版不要顺手做**：它需要自己的构建与验收，混进别的改动里会让"到底是哪一处改动引入回归"说不清。

---

## 6. 证据与边界

### 能证明什么

- `SecretKeySpec` 会复制入参、`getEncoded()` 每次新建副本（**实测**，§2.2）
- `destroy()` 在 Android 上无效：抛异常、不清零、`isDestroyed` 为 false（**实测**，§2.3）
- `Mac` 只在 `init` 时读一次密钥，之后不再回读 Java 端（**实测**：调用次数 1 → 1，§2.4）
- `init` 之后清掉自持副本不影响 `doFinal`，且派生结果与旧路径**逐字节一致**（**实测**，§2.4）
- 生产代码里 `SecretKeySpec` / `Mac.getInstance` **各只有一处**（全仓 grep 核对）

### 不能证明什么（写在前面，别让结论被高估）

- **provider 的 native 副本我们碰不到**，也无法证明它何时被释放 —— 需要 root / 内存离线分析。
  所以这条修法的作用是"缩短**我们持有的**那份副本的寿命"，不是"内存里没有密钥"。
- **结论绑在 `AndroidOpenSSL`（SDK 37）这一个 provider 上**。换 provider 必须重测。
- **"`Mac` 非线程安全"是本轮的推断，不是实测**（见 §2.5 的说明）。
- 本机是模拟器（`sdk_gphone16k_x86_64`）。模拟器与真机在 provider 实现上一致（都是 libcore + Conscrypt 系），
  但**硬件相关的行为本机验不到** —— 本条不涉及硬件密钥，所以影响有限。

### 证据文件

| 文件 | 内容 |
|---|---|
| `dist/evidence/hmac-key-lifecycle/logcat-System.out.txt` | 全部 `[HMAC实测]` 原始输出 |
| `dist/evidence/hmac-key-lifecycle/gradle-connected.log` | 构建与执行记录 |
| `dist/evidence/hmac-key-lifecycle/uninstall.log` | 换包（release→debug）时的复核记录 |
| `app/build/outputs/androidTest-results/connected/debug/*.xml` | 11 条的逐条结果（**注意：`build/` 会被 clean 抹掉**） |
| `app/src/androidTest/java/com/fpb/vault/crypto/HmacKeyLifecycleInstrumentedTest.kt` | 用例本身 |
| `tools/security-selfcheck.py:683` | 那条 INFO（**本轮未改动**） |

---

## 7. 与自检脚本那条 INFO 的关系

用户已判定"**自检脚本列为 INFO 是对的**"，本轮**未改动它**。它的价值在于：

> 它保证这条不会被"没列出来"而忘掉，同时不阻塞当前轮次。

本文档在这条 INFO 之外补的是**可执行的证据**：下一个接手的人不必再问一遍
"`destroy()` 到底行不行""`Mac` 会不会回来读密钥" —— 跑一次
`HmacKeyLifecycleInstrumentedTest` 就有答案，而且是**这台设备**的答案。
