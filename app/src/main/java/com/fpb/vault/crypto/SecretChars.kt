package com.fpb.vault.crypto

/**
 * 用户凭据（主密码、假密码、恢复码）的字符数组清零纪律。
 *
 * ## 它补的是哪一处漏
 *
 * [Argon2Kdf.derive] 收 `CharArray` 而不是 `String`，理由写在那份文件的 KDoc 里：
 * String 不可变、无法用后清零。但**光有签名不够** —— 调用方每次
 * `someString.toCharArray()` 都会在堆上多留一份密码副本，而它要等 GC 才离开内存。
 *
 * 对一个把"别人拿到手机也打不开"当卖点的应用，这段时间窗是有意义的：
 * 有 root 或被注入（Frida）的设备上，攻击者可以直接从进程内存里把主密码读出来，
 * 而主密码能解开真库 —— 绕过整套密码学设计，不需要碰任何密文。
 *
 * 修改前的实情是：全工程 10 处把用户输入转成 `CharArray`，**清零 0 处**。
 * 更刺眼的是 [Argon2Kdf] 的 KDoc 专门承诺了"用 CharArray 便于用后清零" ——
 * 那句话所描述的机制，当时没有任何调用者执行。
 *
 * ## 约定
 *
 * **凡是把用户输入转成 CharArray 的地方，都必须用 [wiping] 包起来。**
 * 它在 `finally` 里清零，因此"密码错了""Argon2 抛异常""协程被取消"这些路径
 * 一样会清到位 —— 而这恰恰是最容易被漏掉的情形。只在成功路径上清，
 * 等于在最需要清的那条路径上不清。
 *
 * `tools/security-selfcheck.py` 会扫描这个约定，漏掉一处就报 FAIL（见那里的豁免清单）。
 *
 * ## 必须说清楚的边界
 *
 * 能清的只有**这一份副本**。`OutlinedTextField` 里那个 `String` 才是密码的原始载体，
 * 它不可变、我们也改不了；`RecoveryCode.canonicalize` 这类中间 String 同理。
 * 所以这条措施的意义是"少留一份、早清一份"，不是"清干净了" ——
 * [SecureBytes] 的 KDoc 对这类不可能性有同样的说明。
 */
internal fun CharArray.wipe() {
    if (isNotEmpty()) fill('\u0000')
}

/**
 * 用完（或中途抛异常）就清零这段字符数组，并把它交回调用方使用。
 *
 * 声明成 `inline` 不是为了性能，而是为了让它**能包住 suspend 调用**：
 * 解锁、改密码、开启假密码这些入口都在协程里，`try/finally` 必须被内联进协程体。
 * 否则只能"先把数组交给协程、再回头清零"，那就变成了在并发时间点上清零 ——
 * 既可能清早了（协程还没用），也可能清晚了（协程已用完但副本还在堆上）。
 *
 * 形如：
 * ```
 * val outcome = password.toCharArray().wiping { state.unlockWithPassword(it) }
 * ```
 * `wiping` 返回 `block` 的返回值，因此可以像普通表达式一样嵌在 `when` / 赋值里。
 */
internal inline fun <T> CharArray.wiping(block: (CharArray) -> T): T =
    try {
        block(this)
    } finally {
        wipe()
    }
