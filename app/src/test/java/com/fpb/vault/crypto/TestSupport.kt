package com.fpb.vault.crypto

import org.junit.Assert.assertTrue

/** 断言解锁成功，并把结果收窄为 [UnlockOutcome.Unlocked]。失败时给出可读信息。 */
internal fun UnlockOutcome.unlockedOrFail(): UnlockOutcome.Unlocked {
    assertTrue("期望解锁成功，实际结果是 $this", this is UnlockOutcome.Unlocked)
    return this as UnlockOutcome.Unlocked
}

/** 断言解锁被拒绝。 */
internal fun UnlockOutcome.assertRejected(message: String) {
    assertTrue(message, this is UnlockOutcome.Rejected)
}

/** 断言某个操作抛出 IllegalArgumentException。 */
internal fun assertRejectsIllegalArgument(message: String, block: () -> Unit) {
    var thrown = false
    try {
        block()
    } catch (e: IllegalArgumentException) {
        thrown = true
    }
    assertTrue(message, thrown)
}
