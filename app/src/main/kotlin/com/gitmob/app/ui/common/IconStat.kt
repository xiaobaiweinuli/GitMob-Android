package com.gitmob.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.gitmob.app.ui.icons.Octicon
import com.gitmob.app.ui.icons.OcticonName

sealed interface StatIcon {
    data class Octicon(val name: OcticonName) : StatIcon
    data class Material(val imageVector: ImageVector) : StatIcon
}

/**
 * 单个统计项（仓库/星标/Gist/组织/关注者/关注/成员/Watchers）。
 *
 * 抽自 HomeScreen.kt 与 ProfileScreen.kt 中**逐行完全相同**的私有 IconStat 函数，
 * 用 OcticonName 语义枚举替代 ImageVector（Material Icons），强制走项目统一的
 * GitHub 官方 Octicons 图标体系（见 ui/icons/ 三文件隔离层 + skills 文档 octicons-and-icons.md）。
 *
 * 列结构：Octicon 图标 → 数量 → 文字标签，垂直居中对齐整列。
 * onClick 非 null 时整列可点击（统计行跳转对应列表用）。
 *
 * @param icon OcticonName 或 Material ImageVector；Gist 使用 Material Code，其余资料统计使用 Octicons
 * @param count 数量（0 时也显示 0，不隐藏占位，保持统计行视觉对齐）
 * @param label 中文标签（"仓库""星标""Gist""组织""关注者""关注""成员"等）
 * @param onClick 点击回调；传 null 表示不可点击（预览场景用）
 */
@Composable
fun IconStat(
    icon: StatIcon,
    count: Int,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 与原 Material Icons 默认 24dp 对齐，保持统计行原有视觉高度（Octicons 默认 16dp 偏小）
        when (icon) {
            is StatIcon.Octicon -> Octicon(
                name = icon.name,
                contentDescription = label,
                size = 24.dp,
                tint = MaterialTheme.colorScheme.primary,
            )
            is StatIcon.Material -> androidx.compose.material3.Icon(
                imageVector = icon.imageVector,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text("$count", style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 一个统计项的数据模型：IconStat 的 4 参数打包成 List 元素，方便批量传参。
 *
 * @param icon 见 IconStat.icon
 * @param count 见 IconStat.count
 * @param label 见 IconStat.label
 * @param onClick 见 IconStat.onClick
 */
data class StatItem(
    val icon: StatIcon,
    val count: Int,
    val label: String,
    val onClick: (() -> Unit)? = null,
) {
    constructor(
        icon: OcticonName,
        count: Int,
        label: String,
        onClick: (() -> Unit)? = null,
    ) : this(StatIcon.Octicon(icon), count, label, onClick)

    constructor(
        icon: ImageVector,
        count: Int,
        label: String,
        onClick: (() -> Unit)? = null,
    ) : this(StatIcon.Material(icon), count, label, onClick)
}

/**
 * 可变列数的等距统计行（"组合优于继承"的典型体现）。
 *
 * 个人 ProfileScreen / HomeScreen 传 4 个 StatItem（仓库 / 组织 / 星标 / Gist）。
 * 组织 Org ProfileContent 传 2 个 StatItem（仓库 / 成员）。
 * 两边样式完全一致：`SpaceEvenly` 等距分发 + fillMaxWidth，
 * 以后改一处样式（比如 icon 尺寸、字体、点击高亮）两页自动同步，
 * 彻底解决"HomeScreen 加了功能 ProfileScreen 没跟上"的 UI 漂移问题。
 */
@Composable
fun ProfileStatsRow(
    stats: List<StatItem>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        stats.forEach { stat ->
            IconStat(
                icon = stat.icon,
                count = stat.count,
                label = stat.label,
                onClick = stat.onClick,
            )
        }
    }
}

/**
 * 私有/公开/归档/Fork 等仓库状态小圆角胶囊标签。
 *
 * 抽自 ReposScreen.kt / StarsScreen.kt / UserReposScreen.kt / UserStarsScreen.kt /
 * RepoDetailScreen.kt 中 5 份**逐行完全相同**的私有 StatusChip 副本（签名都是
 * (text: String, bg: Color, fg: Color, modifier: Modifier)，差异仅在一份没有 bg/fg 参数
 * 的 RepoDetailScreen，那份直接调用下面的双参重载即可）。
 *
 * 统一后：改圆角、改 padding、改字体只需要动一处，不再出现"一个地方改了圆角
 * 另四个地方还是旧 4dp"的 UI 漂移。
 *
 * @param text 胶囊内文字（"Private""Public""Archived""Fork"等）
 * @param bg 胶囊背景色（一般传 MaterialTheme.colorScheme.xxxContainer，与 Material3 建议一致）
 * @param fg 胶囊文字颜色（一般传 onXxxContainer，和背景色自动匹配对比度）
 */
@Composable
fun StatusChip(
    text: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * RepoDetailScreen 用的无配色简化重载（直接默认用 outlineContainer / onOutlineContainer），
 * 保持原有 API 不破坏旧调用，新代码建议传明确的 bg/fg 语义配色。
 */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier) {
    StatusChip(
        text = text,
        bg = MaterialTheme.colorScheme.surfaceContainerHighest,
        fg = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
