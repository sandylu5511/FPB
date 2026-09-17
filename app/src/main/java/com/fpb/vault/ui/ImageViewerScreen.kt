package com.fpb.vault.ui

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import com.fpb.vault.data.BlobReader
import com.fpb.vault.model.NotePayload
import com.fpb.vault.model.NoteType
import com.fpb.vault.ui.components.VideoPlayGlyph
import com.fpb.vault.vault.BlobMediaSource
import com.fpb.vault.vault.MotionPhoto
import com.fpb.vault.vault.MotionPlayback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 全屏看图。左右翻页 + 双指缩放 + 拖动 + 双击在"适应屏幕 / 实际大小"之间切换。
 *
 * ## 为什么不是 Dialog（这条是修过的）
 *
 * 早先的版本把大图放进 `Dialog`，注释里给的理由是"让看图始终盖在列表之上"。
 * 那个理由其实站不住：`UnlockedHost` 只渲染导航栈顶那一层，推入大图时列表本来就已经卸载了，
 * 并没有什么"底层"需要盖住。而 Dialog 的代价很实在 —— 它是一扇**独立的窗口**：
 * 高度按内容测量、位置还要避开系统栏。实测（1080×2400 的模拟器）
 * 图片容器整体比屏幕中心下移了约 135px，于是"和屏幕一样高"的竖图（手机截图）底部会被切掉一截，
 * 看起来就像"大图被限定在一个框里"。
 *
 * 现在它就是一个铺满窗口的普通覆盖层：容器 = 真正的可视区，
 * 图片一律 [ContentScale.Fit] 按**原图比例**居中显示，既不会被裁、也不会被拉伸。
 *
 * ## 双击的语义
 *
 * 双击是"看图"这个动作里最缺的一环：手指放大之后没有任何办法一键回到原位。
 * 现在双击的判定是：
 * - 当前已放大（含正在复位的过程中）→ 回到 [FIT_TRANSFORM]：按原比例适应整屏、居中；
 * - 当前就在适应屏幕状态 → 放大到**实际大小**（1 个图片像素 = 1 个屏幕像素），
 *   并以双击的位置为中心 —— 双击哪块细节，那块细节就留在原地。图片本身比屏幕小则不动。
 *
 * ## 实况照片
 *
 * 安卓相机拍的"实况照片"就是一张尾部接了段 MP4 的 JPEG。字节本来就完整地在库里
 * （见 [com.fpb.vault.vault.MotionPhoto]），所以这里只要在打开时认出它、并**自动**播起来。
 * 播放**不落任何临时文件**：影片段解密后直接以 [MediaDataSource] 喂给 [MediaPlayer]，
 * 明文只在内存里存在，播完即弃 —— 一个把"明文绝不落盘"当卖点的应用，
 * 不该为了让系统播放器方便就先写一个 mp4 到磁盘上。
 *
 * 两条都修过，各自对应一个用户一眼能看出的体验问题：
 *
 * - **打开即播**（原来是"要用户点一下『实况』胶囊才播"）。判据在
 *   [com.fpb.vault.vault.MotionPlayback.shouldAutoPlay]：认得出是实况就自动播；
 *   用户按过"停止"的那一张不再自动开播，翻页则重新判定。
 * - **画面不拉伸**（原来是纵向拉开变形）。根因不在缩放模式，而在 Surface 的尺寸：
 *   见 [PlayerHolder.startIfReady] 的长注释。
 *
 * ## 视频
 *
 * 进来的 `blobIds` 是一条**跨记录的媒体序列**（照片和视频混在一起，见媒体库），
 * 所以每一页都要先问一句"这一页该当图看还是当视频放" —— 判据是记录内容，
 * 由 [VaultAppState.blobKinds] 一次性给出。视频页交给 [VideoPage]，
 * 照片页仍是 [ZoomableImage]。
 *
 * 与实况照片那条路的三处关键差别：
 *
 * 1. **不必先读进内存。** 视频是分块加密的（见 [com.fpb.vault.data.ChunkedBlobFormat]），
 *    交给播放器的是一个按需解密的随机访问读取器（[BlobMediaSource]）——
 *    所以视频能到 2 GiB，而实况影片只能整段进堆。
 * 2. **比例一开始就已知。** 视频的宽高在入库时就探测好并写在记录里
 *    （见 [com.fpb.vault.vault.VideoPipeline.probe]），不必等播放器的尺寸回调，
 *    Surface 从第一次布局起就是对的。实况照片那条路恰恰相反，只能等回调。
 * 3. **有控制条。** 实况是"看一眼就完了"，自动播完即止；视频需要暂停、拖进度。
 */
@Composable
fun ImageViewerScreen(state: VaultAppState, route: Route.Viewer) {
    if (route.blobIds.isEmpty()) {
        // 空列表立刻退出。放在 LaunchedEffect 里而不是组合期直接 pop()：
        // 组合期间写状态会引发一次额外的重组，而这里改的是导航栈 —— 最容易出乱序的组合。
        LaunchedEffect(Unit) { state.pop() }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = route.startIndex.coerceIn(0, route.blobIds.lastIndex),
        pageCount = { route.blobIds.size },
    )
    val scope = rememberCoroutineScope()
    val currentBlobId = route.blobIds.getOrNull(pagerState.currentPage)

    /**
     * 每个附件的类型（视频另带摆正后的宽高）。
     *
     * 一次算好给整条序列用，而不是让每一页自己去查：滑动时 `HorizontalPager` 会同时
     * 组合相邻的页，每页各查一次就得把整个库的记录摊平好几遍。
     */
    val kinds = remember(state.notes) { state.blobKinds() }
    val currentKind = currentBlobId?.let { kinds[it] }
    val currentIsVideo = currentKind?.type == NoteType.VIDEO

    var motion by remember { mutableStateOf<MotionPhoto.Motion?>(null) }
    var video by remember { mutableStateOf<ByteArray?>(null) }
    var loadingVideo by remember { mutableStateOf(false) }

    /**
     * 被用户按过"停止"的那个 blobId。
     *
     * "打开即播"是新行为，但它不能变成"用户关不掉"：在一张图上按过停止之后，
     * 同一张图就不再自动开播（手动点"实况"仍然可以重播），翻到别的照片照常自动播。
     */
    var stoppedByUser by remember { mutableStateOf<String?>(null) }

    // 翻到哪一张就判哪一张的形态，认出是实况就**直接播**。
    // 通常 ZoomableImage 那份大图已经把结果算好放进内存了（同一份字节，不会多解密一次）；
    // 万一还没轮到它，ensureMotion 会自己读一次。
    LaunchedEffect(currentBlobId) {
        val id = currentBlobId
        // 换页即停止播放：影片属于上一张，跟着翻页留在屏幕上会让人以为这是新那张的实况。
        motion = null
        video = null
        loadingVideo = false
        if (id == null) return@LaunchedEffect

        // 视频页不做实况探测：这条路径会把**整份附件**读进堆（见 VaultSession.image
        // 的注释），而它对应的可能是 2 GiB 的视频 —— 那不是"白跑一次"，是一次 OOM。
        if (currentIsVideo) return@LaunchedEffect

        val detected = state.ensureMotion(id)
        motion = detected
        if (!MotionPlayback.shouldAutoPlay(detected, id, stoppedByUser)) return@LaunchedEffect

        loadingVideo = true
        val bytes = state.motionVideo(id)
        loadingVideo = false
        if (bytes == null) {
            // 说明清楚"坏的是影片、照片没事"，否则用户会以为整张图废了
            state.setMessage("这张实况的影片播不出来，照片本身是好的")
        } else {
            video = bytes
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val pageBlobId = route.blobIds[page]
            val kind = kinds[pageBlobId]
            if (kind?.type == NoteType.VIDEO) {
                VideoPage(
                    state = state,
                    blobId = pageBlobId,
                    kind = kind,
                    // 只有"当前这一页"才真的建播放器：滑动时相邻页也会被组合，
                    // 而每建一个播放器就占掉一个硬解码器。
                    active = page == pagerState.currentPage,
                )
            } else {
                ZoomableImage(state = state, blobId = pageBlobId)
            }
        }

        // 播放层压在图片之上、顶栏之下：顶栏里的"停止"必须始终够得着。
        video?.let { bytes ->
            MotionPlayer(
                bytes = bytes,
                modifier = Modifier.fillMaxSize(),
                onFinished = { video = null },
            )
        }

        // 顶栏那一行是白字，直接压在浅色照片（截图、白底图）上几乎看不见 ——
        // 而「实况」入口正好长在这一行里，看不见就等于这个功能不存在。
        // 一层自上而下的黑色渐变兜底，深色照片上只是稍微暗一点，浅色照片上才救得回来。
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(TOP_SCRIM_HEIGHT)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.pop() }) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
            }
            Text(
                text = "${pagerState.currentPage + 1} / ${route.blobIds.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.width(12.dp))

            if (video != null) {
                Text(
                    text = "正在播放实况",
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(10.dp))
                ViewerPill(
                    text = "停止",
                    onClick = {
                        // 记下"用户在**这一张**上按过停止"，否则这一页会立刻又自动播起来。
                        stoppedByUser = currentBlobId
                        video = null
                    },
                )
            } else {
                Text(
                    // 视频页要换一套提示：那一页上双击放大并不存在，
                    // 而"控制条会自己藏起来"是用户得预先知道的事 ——
                    // 否则按钮一消失就会被当成"播放器坏了"。
                    text = when {
                        currentIsVideo && route.blobIds.size > 1 -> "左右滑动切换 · 轻点画面显示控制条"
                        currentIsVideo -> "轻点画面显示控制条"
                        route.blobIds.size > 1 -> "左右滑动切换 · 双击放大"
                        else -> "双击放大或还原"
                    },
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelSmall,
                )
                if (motion != null) {
                    Spacer(Modifier.width(10.dp))
                    ViewerPill(
                        text = "实况",
                        loading = loadingVideo,
                        onClick = {
                            val id = currentBlobId ?: return@ViewerPill
                            if (loadingVideo) return@ViewerPill
                            loadingVideo = true
                            scope.launch {
                                val bytes = state.motionVideo(id)
                                loadingVideo = false
                                if (bytes == null) {
                                    // 说明清楚"坏的是影片、照片没事"，否则用户会以为整张图废了
                                    state.setMessage("这张实况的影片播不出来，照片本身是好的")
                                } else {
                                    video = bytes
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 全屏播放一段视频。
 *
 * ## 生命周期：不是当前页就什么都不做
 *
 * [active] 为 false 时直接不进入组合 —— 连读取器都不开。理由是 `HorizontalPager`
 * 在滑动期间会同时组合相邻的页，而每建一个播放器就占掉一个硬解码器；
 * 连着滑过几段视频很容易把解码器耗光。而解码器不够时 `MediaPlayer` 是**静默失败**的，
 * 表现成"来回滑了几次之后，有些视频怎么点都是黑屏" —— 极难归因。
 *
 * ## 为什么没有"首帧垫在黑屏下面"
 *
 * `SurfaceView` 的画面是**独立图层**，默认压在窗口底下。同一个 Box 里画在它之前的东西
 * 会被窗口的不透明像素盖住（画了也看不见），能看见的只有画在它**之后**的东西。
 * 所以这一页只做覆盖层：加载圈、控制条、中间那个播放键。
 * 中间那几百毫秒用一个转圈交代掉就够了（打开读取器只读 24 字节文件头）。
 *
 * ## 比例从记录里拿，不是从播放器的尺寸回调拿
 *
 * 实况照片那条路**必须**等 `setOnVideoSizeChangedListener`：照片的宽高与影片的宽高不一样，
 * 事先无从得知。视频这条路的处境正好相反 —— 宽高在入库时就探测好写进记录了
 * （见 `VideoPipeline.probe`，并且已经按旋转角摆正），一开始就准确。
 * 于是这里**故意不挂那个回调**：万一它给的是没摆正的帧尺寸（竖拍视频常见），
 * 反而会把窗口摆错。Surface 从第一次布局起就是对的。
 *
 * ## 为什么用 prepareAsync
 *
 * 这条路径上每一字节都得**现场解密**。`prepare()` 会同步读完容器头部再等解码器就绪，
 * 一段 4K 视频的头部解析足以让主线程掉帧；在真机上表现为"点开视频，界面先僵一下"。
 */
@Composable
private fun VideoPage(
    state: VaultAppState,
    blobId: String,
    kind: BlobKind,
    active: Boolean,
) {
    if (!active) return

    /** 播放窗口的比例。见上方"比例从记录里拿"。 */
    val aspect = remember(blobId) {
        kind.video?.let { MotionPlayback.aspectOf(it.width, it.height) } ?: 0f
    }

    val playback = remember(blobId) { VideoHolder() }

    /** 读取器。**打开它本身就是"能不能播"的判定** —— 拿不到就没有播放器可建。 */
    var reader by remember(blobId) { mutableStateOf<BlobReader?>(null) }
    var failed by remember(blobId) { mutableStateOf(false) }

    /** `prepare` 完成。在它之前既没有画面，也没有真实时长。 */
    var ready by remember(blobId) { mutableStateOf(false) }
    var playing by remember(blobId) { mutableStateOf(false) }
    var durationMs by remember(blobId) { mutableStateOf(kind.video?.durationMs ?: 0L) }
    var positionMs by remember(blobId) { mutableStateOf(0L) }
    var controls by remember(blobId) { mutableStateOf(true) }
    var scrubbing by remember(blobId) { mutableStateOf(false) }
    var scrubFraction by remember(blobId) { mutableStateOf(0f) }

    /**
     * 开播。
     *
     * 只有"上一遍已经播完"才先定位回开头：`MediaPlayer` 在 PlaybackCompleted 状态下
     * 调 `start()` 的行为在不同版本上并不一致（有的从头播、有的原地停），
     * 显式 seek 一次最省心。
     */
    fun play() {
        val player = playback.player ?: return
        if (playback.completed) {
            runCatching { player.seekTo(0) }
            playback.completed = false
            positionMs = 0L
        }
        playback.wantsPlay = true
        if (runCatching { player.start() }.isSuccess) playing = true
    }

    fun pause() {
        playback.wantsPlay = false
        playback.player?.let { runCatching { it.pause() } }
        playing = false
    }

    LaunchedEffect(blobId) {
        val opened = state.openReader(blobId)
        if (opened == null) failed = true else reader = opened
    }

    // 读取器与播放器同生共死，且**先关播放器再关读取器**：反过来的话，
    // 播放器的解码线程可能正好读在一个已经关掉的读取器上。
    DisposableEffect(reader) {
        val opened = reader
        onDispose {
            playback.close()
            opened?.let { runCatching { it.close() } }
        }
    }

    // 位置轮询：只在"正在播、且没人在拖进度条"时跑。拖的时候必须停 ——
    // 否则每 250ms 一次的回写会把用户刚拖到的位置拽回去，手感就成了"跟人抢"。
    LaunchedEffect(blobId, playing, scrubbing) {
        if (!playing || scrubbing) return@LaunchedEffect
        while (true) {
            val player = playback.player ?: break
            positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(positionMs)
            delay(POSITION_POLL_MS)
        }
    }

    // 控制条自动隐藏：只在**正在播**的时候隐藏。暂停时留着 ——
    // 那个播放键是用户唯一能继续的路，把它藏起来等于把播放器锁死。
    LaunchedEffect(blobId, controls, playing, scrubbing) {
        if (!controls || !playing || scrubbing) return@LaunchedEffect
        delay(CONTROLS_TIMEOUT_MS)
        controls = false
    }

    // 兜底：某台设备不发 Surface 尺寸回调时，也别让用户对着黑屏干等。
    // 它**不会**违背用户按过的暂停（见 [VideoHolder.startIfReady]）。
    LaunchedEffect(blobId) {
        delay(START_GRACE_MS)
        if (playback.startIfReady(force = true)) playing = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 轻点画面显示/隐藏控制条。水平拖动**不**消费，交给外层 Pager 翻页 ——
            // 与 ZoomableImage 同一个道理：点击类识别器只在"确实是点击"时才成立
            // （列表里按钮与列表滚动一直是可以并存的）。
            .pointerInput(blobId) {
                detectTapGestures(onTap = { controls = !controls })
            },
        contentAlignment = Alignment.Center,
    ) {
        val opened = reader
        if (opened != null && !failed) {
            AndroidView(
                // Surface 的尺寸**就是**画面的目标尺寸，所以"不拉伸"是靠这个比例做出来的。
                // 比例已知（见函数注释）：万一记录里没有，退回整屏，由下面的兜底超时照播。
                modifier = if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).apply {
                        getHolder().addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
                                    val player = MediaPlayer()
                                    val ok = runCatching {
                                        player.setDataSource(BlobMediaSource(opened))
                                        player.setSurface(surfaceHolder.surface)
                                        // 比例已经对齐，这一句只是把默认值写明（对变形无贡献）。
                                        player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                                        player.setOnPreparedListener {
                                            playback.prepared = true
                                            ready = true
                                            if (it.duration > 0) durationMs = it.duration.toLong()
                                            playback.applyPendingSeek()
                                            if (playback.startIfReady()) playing = true
                                        }
                                        player.setOnCompletionListener {
                                            playback.completed = true
                                            playing = false
                                            positionMs = durationMs
                                            // 播完把控制条叫回来，否则想重播还得先点一下屏幕
                                            controls = true
                                        }
                                        player.setOnErrorListener { _, _, _ ->
                                            // 密文损坏、编码不认识都走这里。**如实说出来**，
                                            // 而不是留一个永远转圈的黑屏。
                                            runCatching { player.release() }
                                            if (playback.player === player) playback.player = null
                                            failed = true
                                            true
                                        }
                                        player.prepareAsync()
                                    }.isSuccess

                                    if (ok) {
                                        playback.player = player
                                        // 换过播放器就重新计一遍：切后台再回来时 Surface 会重建，
                                        // 旧标记留着会让视频再也不播。
                                        playback.prepared = false
                                        playback.started = false
                                        playback.completed = false
                                        playback.videoAspect = aspect
                                        // 已经 prepared 过就不必等（重建场景下 prepare 可能已完成）
                                        if (playback.startIfReady()) playing = true
                                    } else {
                                        runCatching { player.release() }
                                        failed = true
                                    }
                                }

                                override fun surfaceChanged(
                                    surfaceHolder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    playback.surfaceWidth = width
                                    playback.surfaceHeight = height
                                    if (playback.startIfReady()) playing = true
                                }

                                override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
                                    playback.player?.let { runCatching { it.release() } }
                                    playback.player = null
                                    playback.prepared = false
                                    playback.started = false
                                    playback.completed = false
                                    playing = false
                                }
                            },
                        )
                    }
                },
            )
        }

        when {
            failed -> Text(
                text = "这段视频读不出来，文件可能已经损坏",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
            )

            !ready -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)

            !playing -> VideoPlayGlyph(diameter = 64.dp, onClick = { play() })
        }

        if (ready && controls) {
            VideoControls(
                playing = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                scrubbing = scrubbing,
                scrubFraction = scrubFraction,
                onTogglePlay = { if (playing) pause() else play() },
                onScrub = { scrubbing = true; scrubFraction = it },
                onSeek = {
                    val target = if (durationMs > 0) (it * durationMs).toLong() else 0L
                    // 落点必须清掉"已播完"标记，否则再点播放会先被打回 0。
                    playback.completed = false
                    val player = playback.player
                    if (player != null) {
                        runCatching { player.seekTo(target.toInt()) }
                    } else {
                        // 还没 prepare：seekTo 会被丢掉，先记下来（见 applyPendingSeek）
                        playback.pendingSeekMs = target
                    }
                    positionMs = target
                    scrubbing = false
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * 视频播放器的持有者。
 *
 * 与 [PlayerHolder] 分开，是因为这里要多管一件事：**用户意图**。
 * 实况照片那条路是"打开即播、没有控制条"，`startIfReady` 里没有任何意图要判断；
 * 而视频这条路上，Surface 重建（切后台再回来、锁屏解锁）会重新走一遍 `surfaceCreated`，
 * [wantsPlay] 就是"别把用户暂停过的视频又自动播起来"的那一个字段。
 */
private class VideoHolder {
    var player: MediaPlayer? = null

    /** `prepare` 完成。没完成时 `start()` 会抛 IllegalStateException。 */
    var prepared = false

    /** 已经启动过。同一个播放器只允许 `start()` 一次 —— 之后就由用户的控制条接管。 */
    var started = false

    /** 影片显示比例（宽 / 高）。0 表示"还不知道" —— 那时门禁恒不通过，靠兜底超时开播。 */
    var videoAspect = 0f
    var surfaceWidth = 0
    var surfaceHeight = 0

    /** 用户的意图：true = 该在播。 */
    var wantsPlay = true

    /** 已经播完（PlaybackCompleted）。用来决定"再点播放"要不要先回到开头。 */
    var completed = false

    /** 还没生效的定位（毫秒）；负数表示没有。`prepare` 之前 `seekTo` 是无效的。 */
    var pendingSeekMs = -1L

    /**
     * 能不能开播：要**已经 prepare**、用户没按过暂停、还没启动过，
     * 且（除非 [force]）Surface 的尺寸已经与影片比例对齐。
     *
     * 比例对齐这条门禁在视频这条路上的意义与实况不同：SurfaceView 从一开始就按记录的
     * 比例摆好了，所以正常情况下这个条件在第一次回调时就成立。留着它是为了让
     * "启动时机"这件事只有一处定义 —— 而不是因为这里也会遇上变形。[force] 是兜底：
     * 某台设备不发尺寸回调时，到点就照播，宁可首帧略变形也不能让用户对着黑屏干等。
     *
     * @return 真的启动了才返回 true（调用方据此同步界面上的播放状态）。
     */
    fun startIfReady(force: Boolean = false): Boolean {
        val player = player ?: return false
        if (started || !prepared || !wantsPlay || completed) return false
        if (!force && !MotionPlayback.surfaceMatchesVideo(surfaceWidth, surfaceHeight, videoAspect)) {
            return false
        }
        started = runCatching { player.start() }.isSuccess
        return started
    }

    fun applyPendingSeek() {
        val player = player ?: return
        val target = pendingSeekMs
        if (target < 0) return
        pendingSeekMs = -1
        runCatching { player.seekTo(target.toInt()) }
    }

    /** 释放播放器与读取器。可重复调用。 */
    fun close() {
        player?.let {
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        player = null
        prepared = false
        started = false
        completed = false
    }
}

/**
 * 播放控制条：播放/暂停 + 进度 + 时间。
 *
 * ## 为什么时间是两个 Text，而不是一个 `0:12 / 1:05`
 *
 * 进度条要吃掉中间那一整行，而左侧的"当前时间"必须**跟着进度走** ——
 * 拖动时显示拖到的位置，松手后显示播放位置。拆成左右两个标签，
 * 中间的进度条才能用 `weight(1f)` 去占满剩余空间。
 *
 * ## 配色为什么全是写死的白/半透明白
 *
 * 这一页永远是黑底（全屏看片没有"浅色主题"这回事）。用 `MaterialTheme.colorScheme`
 * 里那套会得到"浅色主题下深色滑轨压在黑色影片上"这种根本看不见的组合。
 *
 * ## 时长未知时不显示 `0:00`
 *
 * `0:00` 会被读成"这段视频是空的/坏了"，而真相只是容器里没写时长。
 * 与网格角标同一条纪律（见 [com.fpb.vault.ui.components.VideoBadge]）。
 */
@Composable
private fun VideoControls(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    scrubbing: Boolean,
    scrubFraction: Float,
    onTogglePlay: () -> Unit,
    onScrub: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val known = durationMs > 0
    val shownMs = if (scrubbing && known) (scrubFraction * durationMs).toLong() else positionMs
    val fraction = when {
        scrubbing -> scrubFraction
        known -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }

    // 外层负责那层渐变，内层负责避让系统手势条 —— 这样渐变一直铺到屏幕底边，
    // 而按钮不会被手势条压住（全屏看图这一页没有别的地方放 inset）。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onTogglePlay) {
                Icon(
                    imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = Color.White,
                )
            }
            Text(
                text = NotePayload.formatDuration(shownMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
            Slider(
                value = fraction,
                onValueChange = onScrub,
                onValueChangeFinished = { onSeek(fraction) },
                enabled = known,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            )
            Text(
                text = if (known) NotePayload.formatDuration(durationMs) else "--:--",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * 顶栏那行小胶囊。
 *
 * 底色用**半透明黑**而不是半透明白：白底胶囊配白字，压在浅色照片上整个按钮就消失了
 * （真机验收时那张恢复码截图正好是白底，胶囊在截图上完全看不到），
 * 而这正是"实况"这种只在这里出现的入口最要命的失效方式。
 */
@Composable
private fun ViewerPill(
    text: String,
    onClick: () -> Unit,
    loading: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(13.dp),
            )
        } else {
            Text(text = text, color = Color.White, fontSize = 12.sp)
        }
    }
}

/**
 * 播放器的持有者。用普通对象而不是 Compose 状态：换一个 MediaPlayer 不该引起重组。
 *
 * 它同时负责一件容易被忽略的事：**什么时候才允许开播**。见 [startIfReady]。
 */
private class PlayerHolder {
    var player: MediaPlayer? = null

    /** 影片的显示比例（宽 / 高）。0 表示还不知道 —— 由播放器的尺寸回调给出。 */
    var videoAspect = 0f

    /** Surface 当前的像素尺寸，用来判断它有没有按影片比例摆好。 */
    var surfaceWidth = 0
    var surfaceHeight = 0

    /** 已经 start() 过。同一个播放器只允许启动一次。 */
    var started = false

    /**
     * Surface 的尺寸与影片比例对齐之后才开播。
     *
     * ## 为什么必须等（这里就是"实况会拉伸变形"的根因）
     *
     * 早先的版本把 `SurfaceView` 铺满整屏，只靠一句
     * `setVideoScalingMode(VIDEO_SCALING_MODE_SCALE_TO_FIT)` 指望"按比例适应"。
     * 实测**不生效**：把解码帧交给一个**自定义 `Surface`** 时，Surface 的尺寸就是画面的目标尺寸 ——
     * 1080×2400 的整屏上放一段 1080×1920 的实况影片，画面被纵向拉长了 25%，看着就是"照片变形了"。
     * 缩放模式只在播放器自己管显示的时候才有意义，这条路径上它管不着。
     *
     * 所以唯一可靠的做法是让 Surface 自己就是影片的比例：外层按
     * [MotionPlayback.aspectOf] 算出的比例给 `SurfaceView` 定尺寸（见 [MotionPlayer]），
     * 等它在 `surfaceChanged` 里真的变成那个尺寸，再开播。
     *
     * 为什么不"先播着、尺寸到了再改"：播放器一 `start()` 就立刻往 Surface 上画帧，
     * 而新尺寸要到下一帧布局才生效 —— 不等的话开头那几帧依旧是变形的。
     *
     * [force] 是兜底：万一某台设备/某个实现根本不发尺寸回调，到点就照播，
     * 宁可首帧略变形，也不能让用户对着黑屏干等。
     */
    fun startIfReady(force: Boolean = false) {
        val player = player ?: return
        if (started) return
        if (!force && !MotionPlayback.surfaceMatchesVideo(surfaceWidth, surfaceHeight, videoAspect)) return
        started = runCatching { player.start() }.isSuccess
    }
}

/**
 * 把一段**内存里的** MP4 放出来。
 *
 * 关键是 [BytesMediaSource]：`MediaPlayer` 常规只认路径或文件描述符，
 * 那两个都会把明文写到磁盘上。走 `MediaDataSource` 就没有这个问题 ——
 * 播放器每要一段数据，我们就从内存里给它一段。
 */
@Composable
private fun MotionPlayer(
    bytes: ByteArray,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit,
) {
    // 回调会被存进 MediaPlayer，用 rememberUpdatedState 保证拿到的是最新那个闭包，
    // 否则重新组合之后播放器仍在调用旧闭包（它捕获着已经被替换掉的状态）。
    val finish by rememberUpdatedState(onFinished)
    // 名字刻意不叫 holder：SurfaceView 自己也有个 `holder` 成员，
    // 局部变量会把成员遮蔽掉，`holder.addCallback(...)` 于是变成在 PlayerHolder 上找这个方法。
    val playback = remember { PlayerHolder() }

    /**
     * 影片的真实比例。拿到之前 `SurfaceView` 先按整屏摆 —— 此时一帧都还没画出来，看不到东西；
     * 开播的时机由 [PlayerHolder.startIfReady] 把关。
     */
    var videoAspect by remember(bytes) { mutableStateOf(0f) }

    DisposableEffect(bytes) {
        onDispose {
            playback.player?.let { runCatching { it.reset() }; runCatching { it.release() } }
            playback.player = null
        }
    }

    // 兜底：万一设备不发影片尺寸回调，也不能让用户对着黑屏干等。
    LaunchedEffect(bytes) {
        delay(START_GRACE_MS)
        playback.startIfReady(force = true)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            // Surface 的尺寸**就是**画面的目标尺寸，所以"不拉伸"是靠这个比例做出来的：
            // 让 SurfaceView 自己就是影片的比例，播放器往上贴帧时就没有可拉伸的余地。
            modifier = if (videoAspect > 0f) {
                Modifier.aspectRatio(videoAspect)
            } else {
                Modifier.fillMaxSize()
            },
            factory = { context ->
                SurfaceView(context).apply {
                    getHolder().addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
                                val player = MediaPlayer()
                                val ready = runCatching {
                                    player.setDataSource(BytesMediaSource(bytes))
                                    player.setSurface(surfaceHolder.surface)
                                    // 比例已经对齐，这一句只是把默认值写明；真正消除拉伸的是外层那个比例。
                                    player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                                    player.setOnVideoSizeChangedListener { _, width, height ->
                                        val aspect = MotionPlayback.aspectOf(width, height)
                                        playback.videoAspect = aspect
                                        videoAspect = aspect
                                    }
                                    player.isLooping = false
                                    player.setOnCompletionListener { finish() }
                                    player.setOnErrorListener { _, _, _ ->
                                        // 影片段坏了（例如元数据指错了位置）不该把界面卡在播放态
                                        finish()
                                        true
                                    }
                                    player.prepare()
                                }.isSuccess

                                if (ready) {
                                    playback.player = player
                                    // 换过播放器就重新计一次"启动与否"：切后台再回来时 Surface 会重建，
                                    // 旧标记留着会让影片再也不播。
                                    playback.started = false
                                } else {
                                    runCatching { player.release() }
                                    finish()
                                }
                            }

                            override fun surfaceChanged(
                                surfaceHolder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {
                                playback.surfaceWidth = width
                                playback.surfaceHeight = height
                                playback.startIfReady()
                            }

                            override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
                                playback.player?.let { runCatching { it.release() } }
                                playback.player = null
                                playback.started = false
                            }
                        },
                    )
                }
            },
        )
    }
}

/**
 * 让 [MediaPlayer] 从内存里读。
 *
 * `readAt` 必须**恰好**返回读到的字节数，读到结尾返回 -1 —— 返回 0 会被当成"暂时没数据"
 * 而让播放器空转。位置越界时直接 -1，不能返回 0：那会让它一直等一段永远不来的数据。
 */
private class BytesMediaSource(private val data: ByteArray) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= data.size) return -1
        val count = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() = Unit
}

/**
 * 一张可缩放的大图。
 *
 * 缩放与平移合成在一个 [ZoomTransform] 里，因为二者必须一起约束：
 * 平移的上限取决于当前的缩放倍数，分两个状态存迟早会出现"先动了一个、另一个还是旧值"的中间帧。
 */
@Composable
private fun ZoomableImage(state: VaultAppState, blobId: String) {
    var bitmap by remember(blobId) { mutableStateOf<Bitmap?>(null) }
    var viewport by remember(blobId) { mutableStateOf(IntSize.Zero) }
    var transform by remember(blobId) { mutableStateOf(FIT_TRANSFORM) }
    var animation by remember(blobId) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(blobId) {
        bitmap = state.fullImage(blobId)
    }

    /**
     * 动画与手势是互斥的：谁后动手谁说了算。
     *
     * 调用点只有两处 —— 双击切换自己（先停旧的再开新的），以及**确实开始缩放/拖动**的那一刻。
     * 注意不要把它挪到"手势一按下"就执行：那样会误杀双击刚创建的动画（详见手势识别器里的长注释）。
     */
    fun stopAnimation() {
        animation?.cancel()
        animation = null
    }

    /** 用一个 220ms 的补间从当前状态滑到 [target]。 */
    fun animateTo(target: ZoomTransform) {
        val start = transform
        if (start == target) return
        animation?.cancel()
        animation = scope.launch {
            animate(initialValue = 0f, targetValue = 1f, animationSpec = tween(220)) { fraction, _ ->
                transform = ZoomTransform(
                    scale = lerp(start.scale, target.scale, fraction),
                    offset = lerp(start.offset, target.offset, fraction),
                )
            }
        }
    }

    /**
     * "实际大小"换算成相对 Fit 的倍数。
     *
     * Fit 状态下整张图被缩放了 [fitScale] 倍，所以 1:1 需要 1/fitScale 倍。
     * 图片比屏幕还小（fitScale > 1）时返回 1 —— 那说明它已经不小于实际大小了，双击不动。
     */
    fun actualSizeScale(): Float {
        val current = bitmap ?: return 1f
        val fit = fitScale(current, viewport)
        if (fit <= 0f) return 1f
        return (1f / fit).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun handleDoubleTap(point: Offset) {
        stopAnimation()
        // 已经在放大（哪怕还在复位动画里）→ 回适应屏幕；否则 → 放大到实际大小。
        val zoomedIn = transform.scale > MIN_SCALE
        if (zoomedIn) {
            animateTo(FIT_TRANSFORM)
            return
        }
        val target = actualSizeScale()
        if (target <= MIN_SCALE) {
            animateTo(FIT_TRANSFORM)
            return
        }
        val current = bitmap ?: run { animateTo(FIT_TRANSFORM); return }
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        // 让双击那一点在缩放前后停在屏幕上的同一个位置：
        // 它在图片局部坐标里的位置 q = (point - center - offset) / scale，
        // 换到新倍数后要维持 point = center + q * newScale + newOffset。
        val local = (point - center - transform.offset) / transform.scale
        val rawOffset = point - center - local * target
        animateTo(
            ZoomTransform(
                scale = target,
                offset = clampOffset(rawOffset, target, current, viewport),
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(blobId) {
                // 这里**不能**用 detectTransformGestures。
                //
                // 它会无条件消费每一次手指移动（无论有没有真的缩放），外层 HorizontalPager
                // 因此永远收不到水平拖动 —— 表现就是"大图预览无法左右滑动看下一张"。
                //
                // 所以改成手写判定：只有"双指缩放"或"已经放大后的拖动"才消费事件，
                // 未放大时的单指滑动一律放行，交给 Pager 翻页。
                // 两侧互不打架：Pager 在父层，收到的是未被消费的事件才会翻页。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoomChange != 1f || transform.scale > MIN_SCALE) {
                            // 打断动画的判据是"手指真的在缩放或拖动"，不是"手指碰了屏幕"。
                            // 这条判据踩过一次大坑，两次都修在这里：
                            //
                            // 第一版把 stopAnimation() 挂在手势开头（awaitEachGesture 第一行）。
                            // 双击的第二次抬手是在**同一次事件分发**里既交给本识别器、又交给下面那个
                            // detectTapGestures 的：detectTapGestures 在链上更靠内、Main 阶段先执行，
                            // 于是 onDoubleTap 先跑、animateTo 里的 scope.launch 先建出 Job；
                            // 紧接着这次抬手让 do-while 退出、awaitEachGesture 开启下一轮迭代 ——
                            // 开头那一刀正好砍在刚建好、还没轮到被调度执行的动画上。
                            // 现象极具迷惑性：onDoubleTap 打了、目标值算对了，界面纹丝不动。
                            //
                            // 第二版挪进这个分支里，但判据仍是"已放大" —— 还原方向又会中招：
                            // 已经放大的图双击还原时 transform.scale > MIN_SCALE 恒成立，
                            // 照样把刚建好的还原动画砍掉。（此前"双击复位通过"是假象：
                            // 放大从来没生效过，倍数一直是 1，复位当然"成立"。）
                            //
                            // 现在只有 zoomChange 变化（双指捏）或 panChange 非零（真在拖）才夺权。
                            // 双击期间两个量都是零/一，动画不会被误杀。
                            if (zoomChange != 1f || panChange != Offset.Zero) stopAnimation()
                            val next = (transform.scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                            val current = bitmap
                            transform = ZoomTransform(
                                scale = next,
                                offset = if (next <= MIN_SCALE || current == null) {
                                    Offset.Zero
                                } else {
                                    clampOffset(
                                        transform.offset + panChange,
                                        next,
                                        current,
                                        viewport,
                                    )
                                },
                            )
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // 双击单独挂一个识别器。它与上面那个不冲突：上面的只在
            // "缩放/已放大"时消费事件，单击与双击都不会被它吃掉。
            // （子节点消费 down 事件不会影响父层 Pager 的翻页 —— clickable 类控件
            //   同样会消费 down，而列表里的按钮与列表滚动一直是可以并存的。）
            //
            // 不传 onTap：单击本来就没有动作，而 detectTapGestures 的判定流程与
            // onTap 是否为空无关（它靠 onDoubleTap 非空来决定"要不要等第二次按下"）。
            .pointerInput(blobId) {
                detectTapGestures(onDoubleTap = { point -> handleDoubleTap(point) })
            },
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        } else {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = transform.scale,
                        scaleY = transform.scale,
                        translationX = transform.offset.x,
                        translationY = transform.offset.y,
                    ),
            )
        }
    }
}

/** 缩放与平移的合成状态。[FIT_TRANSFORM] 即"按原比例适应整屏、居中"。 */
private data class ZoomTransform(
    val scale: Float = MIN_SCALE,
    val offset: Offset = Offset.Zero,
)

private val FIT_TRANSFORM = ZoomTransform()

/**
 * 图片按 [ContentScale.Fit] 铺进 [viewport] 时的缩放系数。
 *
 * 有了它才能算出"实际大小"要放大多少倍，也才能知道一张图在屏幕上实际占多大 ——
 * 平移的上限必须按**图片实际占的尺寸**算，而不是按视口尺寸算。
 */
private fun fitScale(bitmap: Bitmap, viewport: IntSize): Float {
    if (bitmap.width <= 0 || bitmap.height <= 0) return 1f
    if (viewport.width <= 0 || viewport.height <= 0) return 1f
    return min(
        viewport.width.toFloat() / bitmap.width,
        viewport.height.toFloat() / bitmap.height,
    )
}

/**
 * 把平移量限制在"图片边缘不越过视口边缘"的范围内。
 *
 * 上限是"图片比视口多出来的那一半"，两侧各一份，所以除以 2；
 * 图片在该方向上还没铺满视口时上限为 0（不许把图拖出屏幕，否则松手后无从找回）。
 *
 * 早先的版本直接用视口尺寸乘 (scale - 1)，那隐含假设了"图片铺满视口" ——
 * 对一张 3:4 的竖图在 9:20 的屏幕上（上下本来就留黑边）会算出一个正的纵向余量，
 * 于是能把图拖出黑边之外。这里按图片实际尺寸算，那个余量自然就是 0。
 */
private fun clampOffset(raw: Offset, scale: Float, bitmap: Bitmap, viewport: IntSize): Offset {
    val fit = fitScale(bitmap, viewport)
    val displayedWidth = bitmap.width * fit * scale
    val displayedHeight = bitmap.height * fit * scale
    val maxX = max(0f, (displayedWidth - viewport.width) / 2f)
    val maxY = max(0f, (displayedHeight - viewport.height) / 2f)
    return Offset(
        x = raw.x.coerceIn(-maxX, maxX),
        y = raw.y.coerceIn(-maxY, maxY),
    )
}

/** 适应屏幕状态（相对倍数 1）。 */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f

/** 顶栏底下那层黑色渐变的高度。够盖住状态栏 + 顶栏那一行即可。 */
private val TOP_SCRIM_HEIGHT = 96.dp

/**
 * 等影片尺寸回调的宽限时间。
 *
 * 正常情况下 `prepare()` 期间就会给出尺寸，这个兜底永远不会触发；
 * 它的存在只是为了"某台设备不发这个回调"时不会把影片永远卡在未开播状态。
 */
private const val START_GRACE_MS = 700L

/**
 * 播放中刷新进度的时间间隔。
 *
 * 250ms 是"看起来是连续的"与"每 4 秒唤醒一次主线程"之间的折中：人眼对进度条的
 * 抖动不敏感（尤其这一条只有几百像素宽），但每秒 4 次读系统时钟确实便宜。
 */
private const val POSITION_POLL_MS = 250L

/**
 * 控制条在播放中自动隐藏前的停留时间。
 *
 * 3.5 秒是播放器类的通例：够看清一次进度，又不至于长时间压着画面。
 * **暂停时不隐藏**（见 [VideoPage] 里那个 effect）—— 那时候它是唯一的操作入口。
 */
private const val CONTROLS_TIMEOUT_MS = 3_500L
