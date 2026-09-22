# FPB v1.1.1 正式发布说明

> 这是**可覆盖升级**的正式包。装到已经装了旧版的设备上，数据不会被清掉（依据见 §4）。
> 前序正式包：v1.0.2 / v1.0.3 / v1.0.4 / v1.0.5 / v1.0.6 / v1.0.7 / v1.0.8 / v1.0.9 / v1.1.0。

---

## 1. 版本信息

| 项 | 值 |
|---|---|
| 包名 | `com.fpb.vault` |
| versionName | **1.1.1** |
| versionCode | **12**（上一版 11） |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| 文件 | `dist/FPB-v1.1.1.apk`，3 322 969 B（3.17 MB） |
| APK SHA-256 | `97ee7c6824e948834a4417a422ffce6861d97f6298b4706458a8ab0d908632bf` |
| 签名方案 | v2 + v3（v1 关闭；minSdk 26 起 v1 只会多一份可被篡改的清单） |
| 签名证书 SHA-256 | `7FE41590E2F4429B388A5F889FE9A35E92A28C21A21637BA11D0820FC7ACACBD` |
| 证书指纹是否与前序包一致 | ✅ 与 v1.0.9 / v1.1.0 **实测比对一致** —— 这是"能覆盖安装"的前提 |
| 单元测试 | **300 项，0 失败 0 错误**（与 v1.0.9 起的基线一致，无回归） |
| release 包验收 | **15 条判据全部通过**（48 PASS / 0 FAIL / 5 不适用，见 §3） |

签名自检：

```bash
java -jar build-tools/36.0.0/lib/apksigner.jar verify --verbose --print-certs dist/FPB-v1.1.1.apk
# Verified using v1 scheme (JAR signing): false
# Verified using v2 scheme (APK Signature Scheme v2): true
# Verified using v3 scheme (APK Signature Scheme v3): true
# Signer #1 certificate SHA-256 digest: 7fe41590…acacbd
```

> ⚠️ 发布密钥（`keys/fpb-release.jks` + `keys/keystore.properties`）必须离线多备份几处。
> 它一旦丢失，就再也发不出能覆盖升级的包 —— 后续版本只能让所有用户卸载重装，
> 而卸载等于数据全丢。**密钥与口令都不在该 git 仓库里**（`/keys/` 整目录被排除）。

---

## 2. 这一版修的问题：切后台再回来，视频从头开始了

v1.1.0 的 release 验收在设备上抓到一条真缺陷。用户感知：

> **"我切出去回个消息，回来视频从头开始了。"**

机制在代码上是确定的：`SurfaceView` 在应用退到后台时会走一遍
`surfaceDestroyed → surfaceCreated`，而旧的 `surfaceCreated` 会**新建一个
MediaPlayer 并把它的原点放在 0** —— 没有任何地方把"刚才播到哪"带过去。
`VideoHolder.pendingSeekMs` 本来就存在（用于"播放器还没 prepare、seekTo 会被丢掉"），
但它**只在用户拖进度条、且当时没有播放器**时才会被写，重建这条路上它恒为 -1。

修法（`app/src/main/java/com/fpb/vault/ui/ImageViewerScreen.kt`）：

```kotlin
// ① surfaceDestroyed：先把"现场"记下来 —— 这一刻播放器还活着，位置读得到
val live = playback.player?.let { p ->
    runCatching { p.currentPosition.toLong() }.getOrNull()
}
playback.resumePositionMs = live ?: -1L

// ② surfaceCreated：把现场交给新播放器，由 onPrepared 里的 applyPendingSeek() 落地
if (playback.resumePositionMs >= 0) {
    playback.pendingSeekMs = playback.resumePositionMs
    playback.resumePositionMs = -1L          // 只接一次茬
    positionMs = playback.pendingSeekMs      // 界面读数跟着回来
}
```

两处刻意的设计，写在这里免得后人当成冗余：

- **位置从 `surfaceDestroyed` 时的播放器读，不读界面上的 `positionMs`。**
  那个状态是 250ms 轮询来的，而轮询的条件是 `if (!playing || scrubbing) return`
  —— 用户**暂停后拖动进度条**时它根本不跟，照它恢复会带出一个旧位置。
- **位置无条件恢复，不区分"当时在播还是暂停"。** 暂停着切后台再回来，
  也应该停在原地（而不是跳回第一帧、一按播放又从头）。
  "要不要自动播"是另一件事，由本来就在管这件事的 `wantsPlay` / `startIfReady()` 决定。

**影响面**：只有视频播放页这一条路。实况照片走 `MotionPlayer`，不受影响。

---

## 3. 怎么证明它修好了（这一条判据是新增的）

**关键点：原来的判据抓不到这个缺陷。** B 轮里与它相关的那条叫
「返回后还能重新播放（画面真的在动，说明读取器仍可用）」—— 它在 v1.1.0 的
一次验收里是 **PASS** 的，而那一次返回后画面其实是从 **0.9 秒**重新开始的。
「画面在动」和「从我离开的地方接着播」是两件事。

所以新增了一条判据，并且**先把旧行为钉死**：量的是**画面**（白块位置反推秒数），
不是控制条上那个 `m:ss`（那是应用自己的说法）。同一个夹具、同一条判据：

| 轮 | 包 | 切走前 | 回来后第一批帧 | 判定 |
|---|---|---|---|---|
| `v110-release.run1` | 1.1.0 | 62.8 s | 0.9 / 5.7 / 9.8 / 13.8 s | FAIL |
| `v110-release.run3-position-lost` | 1.1.0 | 40.5 s | 0.0 / 0.0 / 0.0 s（md5 逐字节相同） | FAIL |
| `v111-release` | **1.1.1** | **38.1 s** | **43.7 / 43.7 / 44.3 / 44.5 s** | **PASS** |

release 验收整体：**48 PASS / 0 FAIL / 5 不适用**，汇总行 `共 15 条判据，未通过 0 条`
（日志 `dist/evidence/v111-release/acceptance.log`）。其中第 0 段验包的身份与签名、
第 1 段验全新安装默认禁止截屏、第 2–3 段验`install -r` 覆盖升级后数据仍在**且还能解密**、
第 4 段把整条视频链路在 R8 之后重跑一遍。

离线自测 `tools/motion/selftest_position_keep.py` **全绿**，它直接读上面那两轮归档
下来的连拍帧把旧行为判红，另外还钉住了**取样点**：如果哪天有人把判据挪到"晚 45 秒
才拍的那批帧"上去，它会错误地判 PASS（因为画面从 0 重播几十秒后反超了原位置）
—— 自测里专门有一段复现这个错法，挪回去就红。

---

## 4. 升级说明

- 从 v1.0.2 起的任何一版都可以 `adb install -r dist/FPB-v1.1.1.apk` 直接覆盖，**数据不清**。
- 证书指纹与前序包一致（见 §1），这是能覆盖安装的前提。
- 本版**没有**改数据格式、没有改数据库结构，不涉及迁移。

---

## 5. 已知未决：画面冻在首帧（**未能复现，未改代码**）

v1.1.0 的那次验收里还出现过一次「进度条在走、画面不动」。本轮把它当作独立问题
去受控复现，**3 轮都没复现出来**，因此**没有改任何播放器代码** ——
机制没定性时改 Surface/MediaPlayer 生命周期是"照着猜改"。

不过这次多了一条 guest 之外的观测通道：模拟器进程自己的日志里混着**宿主机侧**
FFmpeg 的 H.264 解码器，它打 `no frame!` 就是"这段比特流没解出画面"，这条
**在 logcat 里看不到**（上一轮查遍 logcat 只见 `MEDIA_INFO_VIDEO_RENDERING_START`、
毫无错误，缺的正是这个视角）。

| 日志 | 那次 | `no frame!` 次数 |
|---|---|---|
| `build/_emu.log` | 崩坏那一轮（12 分钟里退化到 `screencap` 单帧 5.5 s） | **232** |
| `build/_emu2.log` | 健康这一轮（含完整一遍 1.1.1 验收） | **0** |

结论：**仍未能定性**，不能认定是应用缺陷；但有一条可量化的旁证指向**模拟器宿主机侧
解码器失能**，而不是"应用没把画面贴上去"。下一步（未做）是把 `-gpu swiftshader_indirect`
换成 `-gpu host` 再跑同样几轮 —— 若冻结随之消失，(b) 就可以归到"模拟器的软解码/软渲染
配置"，真机不会走这条路。详细取证见 `dist/evidence/audit4/README.md` §10.6。
