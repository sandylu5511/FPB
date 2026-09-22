package com.fpb.vault.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fpb.vault.ui.theme.BrandGradient
import com.fpb.vault.ui.theme.LocalIsDarkTheme

/**
 * 顶栏下方那条渐变细线。
 *
 * 品牌渐变在高饱和状态下**不适合承载文字**（柠檬绿对白底的对比度只有 1.2:1），
 * 因此在这套配色里它只做装饰：细线、进度条、图标底衬。
 * 凡是"用户要读的字"，一律走 [MaterialTheme.colorScheme] 里那几档加深色。
 */
@Composable
fun BrandAccentBar(
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(BrandGradient),
    )
}

/**
 * 顶栏，iOS 导航栏式样：标题居中、返回在左、动作在右。
 *
 * 居中标题是刻意的 iOS 习惯（Material 默认左对齐）：这个应用一屏只讲一件事，
 * 居中标题让"现在在哪"始终在视觉焦点上；左右两端的按钮天然对称，不需要数格子对齐。
 * 自带状态栏内边距（应用是 edge-to-edge 的，不自己加会被状态栏压住）。
 */
@Composable
fun FpbTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    /**
     * 左上角那个按钮的图标。默认是返回箭头；多选模式下传 [Icons.Outlined.Close] ——
     * "取消选择"与"退出去"是两件事，用同一个箭头会让人以为会离开当前页。
     */
    backIcon: ImageVector = Icons.AutoMirrored.Outlined.ArrowBack,
    backDescription: String = "返回",
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(52.dp),
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp),
                ) {
                    Icon(
                        imageVector = backIcon,
                        contentDescription = backDescription,
                    )
                }
            }

            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
                Spacer(Modifier.width(4.dp))
            }
        }
        BrandAccentBar(height = 2.dp)
    }
}

/** 空列表时显示。刻意不显示"0 条"这种冷冰冰的数字。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 带标题的区块，设置页与详情页共用。
 *
 * iOS 分组列表的页眉样式：小号、灰色、上提 —— 它只负责"下面这组是什么"，
 * 视觉重量不能压过组内的行。
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.5.sp,
        modifier = modifier.padding(start = 28.dp, top = 22.dp, bottom = 7.dp),
    )
}

/**
 * iOS「设置」风格的分组卡片：白底、16dp 圆角，行与行之间由调用方插 [GroupDivider]。
 * 卡片左右各留 16dp 页边距（由调用方以 padding 修饰符提供），这里只负责形状与底色。
 */
@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            content()
        }
    }
}

/** 分组卡片里的行间分隔线。缩进到与文字对齐（跳过行首的图标列）。 */
@Composable
fun GroupDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(start = 62.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 内联告警条（解锁后发现坏条目、备份到期提醒等）。 */
@Composable
fun InlineNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.WARNING,
    onClick: (() -> Unit)? = null,
) {
    val (background, foreground) = when (tone) {
        NoticeTone.WARNING -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        NoticeTone.INFO -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            // 可点必须排在 clip/background **之后**：涟漪由 clickable 生成，
            // 放在前面的话它会画在圆角之外，点一下看到的是一个方角的水波纹。
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = foreground,
        )
    }
}

enum class NoticeTone { WARNING, INFO }

/**
 * 语义色：成功。
 *
 * 它**必须跟着主题走**。固定色在浅色下没问题，但放到深色主题的近黑底（#0A0D12）上，
 * `#1F9D62` 的对比度只剩 2.9:1 —— 属于"能看见但读不清"，正是深色模式那批问题的同类。
 * 深色档取同一色相、只提明度，让语义在两套主题里保持一致。
 *
 * 判据是 [LocalIsDarkTheme]（应用实际在用的那套配色），**不是** `isSystemInDarkTheme()`：
 * 用户在系统浅色下把应用切成深色时，后者仍是 false，这里就会取错档。
 *
 * ## "危险"那一档为什么不在这里
 *
 * 这里原先还有一个 `DangerRed`，与配色方案里的 `error` 是**同一色相的两次实现**
 * （浅色值一字不差，深色值略有出入），而全工程十几处"危险/删除"文案用的都是
 * `MaterialTheme.colorScheme.error`。于是那个常量既没人读，又是一份会随主题
 * 慢慢漂开的第二事实 —— 已删除，危险色直接用 `colorScheme.error`：
 * 它在 [com.fpb.vault.ui.theme.LightColors] / `DarkColors` 里各自取过档，
 * 同样跟着主题走，不需要在组件层再判一次明暗。
 */
val SuccessGreen: Color
    @Composable get() = if (LocalIsDarkTheme.current) Color(0xFF3DD68C) else Color(0xFF1F9D62)
