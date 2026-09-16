# R8/ProGuard 规则。
#
# 这里**刻意保持精简**：数据层是手写 SQLite，加解密是直接调用 BouncyCastle 与平台 JCE，
# 页面是 Compose —— 业务代码里没有一处反射，因此不需要 -keep 保留任何业务类；
# 反过来，类名被混淆掉更好：从反编译结果里看不出 KDF 用的是哪个实现、哪些类在处理密钥。
#
# 唯一的例外见下面"给 native 侧回调用"一节。

# 保留行号信息，便于定位崩溃（不保留源码文件名）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 给 native 侧回调用 ----
# MediaPlayer 会把 MediaDataSource 的对象交给 libmedia_jni，native 侧按**名字**回调
# readAt / getSize / close —— 字节码里看不到这个调用点（实况照片的播放入口就建在这上面）。
#
# 诚实说：按 R8 的既定行为，实现库抽象方法的方法名本来就会保留（否则多态直接破掉），
# 所以这条规则严格讲是**防御性**的、当前并非必需。加它的代价是零，而它防的是最难查的
# 那一类缺陷：**只有 release 包播不出来、debug 包一切正常** —— 开发期几乎不可能发现。
# 出正式包前留着它，比省下这一行更划算。
-keep class * extends android.media.MediaDataSource { *; }

# ---- BouncyCastle ----
# 我们只直接调用它的 Argon2id 实现，全程没有反射，因此不需要 -keep 保留类名；
# 让 R8 正常混淆反而更好（从类名看不出用了哪个 KDF）。
# 压掉警告是因为 BC 内部带了大量 JCE Provider 注册、JNDI 相关代码，
# 在 Android 上这些引用不可用，R8 会报一堆 missing class 噪音。
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
