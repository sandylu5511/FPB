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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.fpb.vault.vault.MotionPhoto
import kotlinx.coroutines.Job
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
 * （见 [com.fpb.vault.vault.MotionPhoto]），所以这里只要在打开时认出它、并给一个播放入口。
 * 播放**不落任何临时文件**：影片段解密后直接以 [MediaDataSource] 喂给 [MediaPlayer]，
 * 明文只在内存里存在，播完即弃 —— 一个把"明文绝不落盘"当卖点的应用，
 * 不该为了让系统播放器方便就先写一个 mp4 到磁盘上。
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

    var motion by remember { mutableStateOf<MotionPhoto.Motion?>(null) }
    var video by remember { mutableStateOf<ByteArray?>(null) }
    var loadingVideo by remember { mutableStateOf(false) }

    // 翻到哪一张就判哪一张的形态。通常 ZoomableImage 那份大图已经把结果算好放进内存了
    // （同一份字节，不会多解密一次）；万一还没轮到它，ensureMotion 会自己读一次。
    LaunchedEffect(currentBlobId) {
        motion = currentBlobId?.let { state.ensureMotion(it) }
        // 换页即停止播放：影片属于上一张，跟着翻页留在屏幕上会让人以为这是新那张的实况。
        video = null
        loadingVideo = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            ZoomableImage(state = state, blobId = route.blobIds[page])
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
                ViewerPill(text = "停止", onClick = { video = null })
            } else {
                Text(
                    text = if (route.blobIds.size > 1) {
                        "左右滑动切换 · 双击放大"
                    } else {
                        "双击放大或还原"
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

/** 播放器的持有者。用普通对象而不是 Compose 状态：换一个 MediaPlayer 不该引起重组。 */
private class PlayerHolder {
    var player: MediaPlayer? = null
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

    DisposableEffect(bytes) {
        onDispose {
            playback.player?.let { runCatching { it.reset() }; runCatching { it.release() } }
            playback.player = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                getHolder().addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
                            val player = MediaPlayer()
                            val started = runCatching {
                                player.setDataSource(BytesMediaSource(bytes))
                                player.setSurface(surfaceHolder.surface)
                                // 显式 FIT：默认行为取决于实现，而"这段影片被拉满全屏"
                                // 与上面那张按原比例显示的静图对不上，看着像播错了内容。
                                player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                                player.isLooping = false
                                player.setOnCompletionListener { finish() }
                                player.setOnErrorListener { _, _, _ ->
                                    // 影片段坏了（例如元数据指错了位置）不该把界面卡在播放态
                                    finish()
                                    true
                                }
                                player.prepare()
                                player.start()
                            }.isSuccess

                            if (started) {
                                playback.player = player
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
                        ) = Unit

                        override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
                            playback.player?.let { runCatching { it.release() } }
                            playback.player = null
                        }
                    },
                )
            }
        },
    )
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
