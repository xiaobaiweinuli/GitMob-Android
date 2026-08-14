package com.gitmob.app.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gitmob.app.data.model.StarredRepo

/**
 * 星标仓库卡片（"星标"Tab 自己的星标列表 + 他人星标列表通用）。
 *
 * 抽自 StarsScreen.kt / UserStarsScreen.kt 中结构几乎完全相同的私有 StarredRepoCard，
 * 统一使用 ui/common/StatusChip.kt 公共胶囊标签，避免样式漂移。
 * 与 RepoCard 的核心区别：
 *   - 顶部多了一行 owner 头像 + login（星标通常是别人的仓库，需要显示归属）
 *   - 标题行最右边有 viewer 专属操作按钮（添加到列表 + 取消星标），用 [showViewerActions] 控制显隐
 *
 * 卡片结构（自上而下）：
 *   1. 归属行：owner 头像 + login（星标通常是别人的仓库）
 *   2. Fork 来源行（isFork 时显示并可点击）
 *   3. 标题行：左侧仓库名 + 简介；右侧 [showViewerActions] 时显示添加到列表 + 取消星标
 *   4. 主页链接（homepageUrl 非空时显示并可点击）
 *   5. 统计行：左侧（语言点/星标/fork/Issues）滚动；右侧（私有/归档/分支）固定
 *   6. Topics 行：独立横向滚动
 *
 * @param repo 星标仓库数据
 * @param onClick 点击整张卡片的回调（跳仓库详情页）
 * @param onForkSourceClick 点击复刻来源时打开源仓库
 * @param onHomepageClick 点击项目主页链接时打开外部 URL
 * @param showViewerActions 是否显示 viewer 专属操作按钮（添加到列表 + 取消星标）；
 *                          查看他人星标时传 false（你无法管理别人的收藏夹），自己的星标 Tab 传 true
 * @param onAddToListClick "添加到列表"按钮回调；[showViewerActions] 为 true 时才会被调用
 * @param onUnstarClick "取消星标"按钮回调；[showViewerActions] 为 true 时才会被调用
 * @param modifier Modifier
 */
@Composable
fun StarredRepoCard(
    repo: StarredRepo,
    onClick: () -> Unit,
    onForkSourceClick: (owner: String, name: String) -> Unit,
    onHomepageClick: (url: String) -> Unit,
    showViewerActions: Boolean,
    modifier: Modifier = Modifier,
    onAddToListClick: (() -> Unit)? = null,
    onUnstarClick: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ---- 1. 归属行：owner 头像 + login ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = repo.ownerAvatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape),
                )
                Text(
                    repo.ownerLogin,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            val forkOwner = repo.forkedFromOwner
            val forkName = repo.forkedFromName
            if (repo.isFork && forkOwner != null && forkName != null) {
                RepoMetadataLinkRow(
                    icon = Icons.AutoMirrored.Default.CallSplit,
                    text = "复刻自: $forkOwner/$forkName",
                    contentDescription = "打开源仓库",
                    onClick = { onForkSourceClick(forkOwner, forkName) },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // ---- 2. 标题行：左侧仓库名 + 简介；右侧 viewer 操作按钮 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        repo.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    repo.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                // viewer 操作按钮：添加到列表 + 取消星标（仅 showViewerActions=true 时显示）
                if (showViewerActions) {
                    Row {
                        onUnstarClick?.let {
                            IconButton(onClick = it) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = "取消星标",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        onAddToListClick?.let {
                            IconButton(onClick = it) {
                                Icon(Icons.Default.BookmarkAdd, contentDescription = "添加到列表")
                            }
                        }
                    }
                }
            }

            // ---- 3. 主页链接 ----
            repo.homepageUrl?.takeIf { it.isNotBlank() }?.let { homepageUrl ->
                RepoMetadataLinkRow(
                    icon = Icons.Default.Link,
                    text = homepageUrl,
                    contentDescription = "打开项目主页",
                    onClick = { onHomepageClick(homepageUrl) },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // ---- 4. 统计行：左侧滚动 + 右侧固定 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧：语言点 / 星标 / fork / Issues → 可横向滚动
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repo.languageName?.let { lang ->
                        val dotColor = repo.languageColor
                            ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                            ?: MaterialTheme.colorScheme.outline
                        Surface(color = dotColor, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
                        Text(
                            lang,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
                        )
                    }
                    Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(
                        "${repo.stargazerCount}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 2.dp, end = 12.dp),
                    )
                    Icon(Icons.AutoMirrored.Default.CallSplit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(
                        "${repo.forkCount}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 2.dp, end = 12.dp),
                    )
                    if (repo.openIssueCount > 0) {
                        Icon(Icons.Default.Adjust, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(
                            "${repo.openIssueCount}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }

                // 右侧：私有 / 归档 / 分支胶囊 → 固定不滚动
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (repo.isPrivate) {
                        StatusChip(
                            "私有",
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    if (repo.isArchived) {
                        StatusChip(
                            "已归档",
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    repo.defaultBranchName?.let {
                        StatusChip(
                            it,
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            // ---- 5. Topics 行（独立横向滚动）----
            if (repo.topics.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repo.topics.forEach { topic ->
                        StatusChip(
                            topic,
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
