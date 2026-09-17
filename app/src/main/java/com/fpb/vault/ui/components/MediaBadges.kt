package com.fpb.vault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fpb.vault.model.NotePayload

/**
 * 媒体格上的角标。
 *
 * ## 为什么用文字而不是纯图标
 *
 * 「实况」这两个字是中文用户在相册里认的东西，而这套图标集里没有一眼能认出它的形状。
 * 时长同理：`0:42` 是所有人都读得懂的，一个抽象的"时间"图标不是。
 *
 * ## 为什么底色是半透明黑
 *
 * 白底胶囊配白字，压在浅色的照片（截图、白底图）上整个角标就消失了 ——
 * 而这些角标是"这一格能播"的唯一提示，看不见就等于这个能力不存在。
 * 深色底在深色画面上只是稍微暗一点，在浅色画面上才救得回来。
 */
@Composable
fun MediaBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 12.sp,
        )
    }
}

/** 「实况」角标。 */
@Composable
fun MotionBadge(modifier: Modifier = Modifier) = MediaBadge("实况", modifier)

/**
 * 视频格上的「时长 + 播放三角」。
 *
 * 时长取不到（0）时只显示播放三角 —— **不显示 `0:00`**：
 * 那会让用户以为这段视频是空的或者坏了，而真相只是元数据里没写时长。
 */
@Composable
fun VideoBadge(durationMs: Long, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(11.dp),
        )
        if (durationMs > 0) {
            Text(
                text = NotePayload.formatDuration(durationMs),
                color = Color.White,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/**
 * 视频那个大播放三角。
 *
 * 网格上那一圈和右下角的时长角标**不是重复**：3 列的网格里，右下角那点东西在快速
 * 滚动中基本看不见，而"这一格是视频"恰恰是最需要一眼分辨的信息。
 *
 * 两处在用，尺寸差得不小（网格角标 34dp、全屏播放页 64dp），所以直径是参数：
 * 圆角与图标都按直径**等比**算出来，而不是各写一套常量 ——
 * 写死的那套一旦被用在另一个尺寸上，得到的是一个"大黑方块配小三角"。
 *
 * [onClick] 非空时它才是一个按钮（此时也会带上无障碍标签）。全屏页需要它可点，
 * 网格上它只是一块角标，点击会穿透给格子的点击区域。
 */
@Composable
fun VideoPlayGlyph(
    modifier: Modifier = Modifier,
    diameter: Dp = 34.dp,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .size(diameter)
            .clip(RoundedCornerShape(diameter / 2))
            .background(Color.Black.copy(alpha = 0.42f))
            // clickable 放在 clip 之后：涟漪才会被圆裁掉，而不是溢成一块方块。
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = if (onClick != null) "播放" else null,
            tint = Color.White,
            modifier = Modifier.size(diameter * ICON_RATIO),
        )
    }
}

/** 三角图标与圆的直径比。取自网格角标那一版（20 / 34），换个尺寸也只是等比放大。 */
private const val ICON_RATIO = 0.588f
