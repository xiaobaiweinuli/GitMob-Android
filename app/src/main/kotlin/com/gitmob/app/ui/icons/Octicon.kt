package com.gitmob.app.ui.icons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一的 GitHub Octicons 渲染组件（UI 层唯一入口）。
 *
 * 所有业务 Screen 中使用 Octicons 的地方必须调用本函数，而不要直接写
 * Icon(painterResource(R.drawable.oct_xxx_16))。这样做的好处：
 * 1) 默认尺寸 16.dp 与 Octicons-16 系列 VectorDrawable 的 viewport 1:1 对齐，
 *    不会产生额外缩放；
 * 2) 默认 tint 跟随父级 LocalContentColor.current，与 Material Icons 的行为一致；
 * 3) 后续需要统一改尺寸/换资源/加描边/加动效，收敛到一个函数即可；
 * 4) 图标选择逻辑与 UI 层解耦，映射问题只改 [OcticonPainterProvider]。
 *
 * @param name Octicons 语义枚举名称
 * @param contentDescription 无障碍描述，装饰性图标传 null 即可
 * @param modifier Compose Modifier
 * @param size 图标尺寸，默认 16.dp，与 Octicons-16 系列 viewport 一致
 * @param tint 着色，默认跟随父级 LocalContentColor
 */
@Composable
fun Octicon(
    name: OcticonName,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = LocalContentColor.current,
) {
    val painter: Painter = OcticonPainterProvider.rememberOcticonPainter(name)
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/**
 * 带文字标签 + Tooltip 悬停提示的徽章版 Octicon。
 *
 * 专门用于 GitHub 个人资料徽章（Developer Program / Bounty Hunter /
 * Campus Expert / GitHub Star）这类"图标旁边需要显示中文名称，
 * 同时长按/悬停还要有 Tooltip 辅助提示"的场景。
 *
 * 显示结构：Row(水平居中对齐) { Octicon() + 4.dp 间距 + Text() }
 * Tooltip 作为无障碍/移动端长按的额外提示层（保留），文字标签始终可见，
 * 让用户不需要长按就能知道当前徽章的具体名称。
 *
 * @param name Octicons 语义枚举名称
 * @param tooltipText 徽章中文名称（同时用于 Tooltip 气泡与旁边的可见文字标签）
 * @param modifier Compose Modifier
 * @param size 图标尺寸，默认 16.dp
 * @param tint 图标着色，默认 LocalContentColor
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcticonBadge(
    name: OcticonName,
    tooltipText: String,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(
                    text = tooltipText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        state = tooltipState,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = modifier,
        ) {
            Octicon(
                name = name,
                contentDescription = tooltipText,
                size = size,
                tint = tint,
            )
            Text(
                text = tooltipText,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}
