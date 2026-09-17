package com.fpb.vault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fpb.vault.MainActivity
import com.fpb.vault.ui.brand.FpbWordmark
import com.fpb.vault.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull

/**
 * 应用根节点：决定"现在该显示哪一屏"。
 *
 * 三件事必须收在这里做，因为它们跨越所有界面：
 * 1. **自动锁定计时**（[VaultAppState.tick]）—— 切后台期间 DEK 不能一直躺在内存里，
 *    只在回到前台时判断超时是不够的
 * 2. **截屏开关的即时生效** —— 用户改了设置要马上起作用，不必重启
 * 3. **一次性提示的展示** —— 状态里只放一条文本，展示完立刻清空，
 *    否则重组会让同一条提示反复弹出来
 */
@Composable
fun AppRoot(state: VaultAppState) {
    LaunchedEffect(state) {
        while (true) {
            delay(TICK_INTERVAL_MILLIS)
            state.tick()
        }
    }

    val activity = LocalContext.current as? MainActivity
    // 读状态里的镜像值，而不是直接读 settings：SharedPreferences 写完了不会触发重组，
    // 用户在设置页拨动开关后界面不会跟着变。
    val blockScreenshots = state.blockScreenshots
    LaunchedEffect(blockScreenshots) {
        // 默认值来自 SettingsStore：调试包默认允许截屏（真机验收要能截图），
        // 发布包默认禁止。用户改过之后就以用户的选择为准。
        activity?.applyScreenSecurity(blockScreenshots)
    }

    // 状态栏图标颜色与窗口底色由 Window 决定，Compose 的配色管不到它们，
    // 必须在配色变化时自己同步一次 —— 否则"系统浅色 + 应用深色"下，
    // 浅色状态栏上会画浅色图标（时间和电量看不见）。
    val isDark = LocalIsDarkTheme.current
    LaunchedEffect(isDark) { activity?.applyWindowChrome(isDark) }

    val hostState = remember { SnackbarHostState() }

    // 一次性提示的消费方式**不能**写成：
    //
    //     LaunchedEffect(state.notice) {
    //         val text = state.notice ?: return@LaunchedEffect
    //         state.notice = null            // ← 这里改了 LaunchedEffect 自己的 key
    //         hostState.showSnackbar(text)
    //     }
    //
    // 那样写会自己把自己的协程取消掉：effect 体内把 notice 置空，key 就从"文案"
    // 变成 null，Compose 按语义**取消并重启**这个协程；而 showSnackbar 一旦被取消，
    // Snackbar 会被立刻撤下（它的 finally 会清 currentSnackbarData）。
    //
    // 这不是理论推导，是实测：加日志后点一次「清理无用图片」，得到的是
    //     03:59:12.239  effect 启动 text=没有发现无用图片
    //     03:59:12.276  showSnackbar 被中断：LeftCompositionCancellationException
    // 37 毫秒。也就是说全应用的**操作反馈与错误提示**（"已导出 12 条记录"、
    // "保存失败：…"、"导入失败：…"）都等于没有显示，只是闪了一下。
    // 修复后同一台设备上再点一次，日志变成
    //     04:19:22.192  showSnackbar 正常结束 text=没有发现无用图片
    // （两次都跑完整展示时长才返回）。这两行日志是当时的临时诊断代码留下的，
    // 验证通过后已移除 —— 日志里会带用户可见文案，没有必要长期留在发布包里。
    //
    // 正解是把 effect 固定只挂一次（key 用 Unit），用 snapshotFlow 去接后续到达的值：
    // 在 collect 里改写 notice 不会重启这个 effect。
    //
    // 用 collect 而不是 collectLatest：showSnackbar 内部有互斥锁，串行消费意味着
    // 短时间内连续两条提示会依次显示（第二条等第一条走完），而不是把前一条掐掉 ——
    // 对"保存失败"这类提示，丢失比延迟严重得多。
    LaunchedEffect(Unit) {
        snapshotFlow { state.notice }
            .filterNotNull()
            .collect { text ->
                state.notice = null
                hostState.showSnackbar(text)
            }
    }

    val canGoBack = state.phase == VaultAppState.Phase.UNLOCKED && state.routes.size > 1
    BackHandler(enabled = canGoBack) { state.pop() }

    // 必须是 Surface 而不是 Box + background。
    //
    // Box 只画底色，不设 contentColor，于是整棵树里的 Text/Icon 会继承 Compose 的
    // 默认 LocalContentColor（纯黑）。浅色主题下碰巧就是想要的效果，深色主题下
    // 就变成"黑底黑字"，按钮和标题直接看不见 —— 这个 bug 藏了很久正是因为浅色看不出。
    // Surface 会按 color 自动推出配对的 contentColor（background → onBackground），
    // 一处修好，全应用所有没写颜色的文字/图标都跟着正确。
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(Modifier.fillMaxSize()) {
            when (state.phase) {
                VaultAppState.Phase.LOADING -> BootScreen()
                VaultAppState.Phase.ONBOARDING -> OnboardingScreen(state)
                VaultAppState.Phase.LOCKED -> UnlockScreen(state)
                VaultAppState.Phase.UNLOCKED -> UnlockedHost(state)
            }

            SnackbarHost(
                hostState = hostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun UnlockedHost(state: VaultAppState) {
    when (val route = state.routes.last()) {
        is Route.Home -> HomeScreen(state)
        is Route.Editor -> NoteEditorScreen(state, route)
        is Route.View -> NoteViewScreen(state, route)
        is Route.Viewer -> ImageViewerScreen(state, route)
        Route.Photos -> MediaLibraryScreen(state)
        Route.Settings -> SettingsScreen(state)
    }
}

/** 冷启动的一两秒（读密钥文件 + KDF 预热）里显示的东西。 */
@Composable
private fun BootScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FpbWordmark(
            modifier = Modifier
                .width(190.dp)
                .height(44.dp),
        )
        Column(Modifier.height(28.dp)) {}
        CircularProgressIndicator(
            modifier = Modifier.width(22.dp).height(22.dp),
            strokeWidth = 2.dp,
        )
        Column(Modifier.height(14.dp)) {}
        Text(
            text = "正在准备加密内核…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 自动锁定的轮询间隔。十几秒一次足够 —— 它只是"回到前台"之外的兜底。 */
private const val TICK_INTERVAL_MILLIS = 3_000L
