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
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoListItem

/**
 * 仓库卡片（"仓库"Tab、用户仓库列表通用）。
 *
 * 抽自 ReposScreen.kt / UserReposScreen.kt 中两份结构完全相同的私有 RepoCard，
 * 统一使用 ui/common/StatusChip.kt 的公共胶囊标签组件，避免 UI 样式漂移。
 *
 * 卡片结构（自上而下）：
 *   1. 仓库名（粗体标题）
 *   2. Fork 来源行（isFork 才显示：复刻自 owner/name）
 *   3. 简介（最多 2 行截断）
 *   4. 主页链接（homepageUrl 非空才显示）
 *   5. 统计行：左侧（语言点/星标/fork/Issues）可横向滚动；右侧（私有/归档/分支）固定
 *   6. Topics 行：独立横向滚动
 *
 * @param repo 仓库数据
 * @param onClick 点击整张卡片的回调（一般跳仓库详情页）
 * @param onForkSourceClick 点击复刻来源时打开源仓库
 * @param onHomepageClick 点击项目主页链接时打开外部 URL
 * @param modifier Modifier
 */
@Composable
fun RepoCard(
    repo: RepoListItem,
    onClick: () -> Unit,
    onForkSourceClick: (owner: String, name: String) -> Unit,
    onHomepageClick: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ---- 1. 仓库名 ----
            Text(
                repo.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            // ---- 2. Fork 来源（复刻自）----
            val forkOwner = repo.forkedFromOwner
            val forkName = repo.forkedFromName
            if (repo.isFork && forkOwner != null && forkName != null) {
                RepoMetadataLinkRow(
                    icon = Icons.AutoMirrored.Default.CallSplit,
                    text = stringResource(R.string.common_forked_from, forkOwner, forkName),
                    contentDescription = stringResource(R.string.common_open_source_repo),
                    onClick = { onForkSourceClick(forkOwner, forkName) },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // ---- 3. 简介 ----
            repo.description?.let {
                GitHubEmojiText(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // ---- 4. 主页链接 ----
            repo.homepageUrl?.takeIf { it.isNotBlank() }?.let { homepageUrl ->
                RepoMetadataLinkRow(
                    icon = Icons.Default.Link,
                    text = homepageUrl,
                    contentDescription = stringResource(R.string.common_open_homepage),
                    onClick = { onHomepageClick(homepageUrl) },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // ---- 5. 统计行：左侧滚动 + 右侧固定 ----
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
                            stringResource(R.string.common_private),
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    if (repo.isArchived) {
                        StatusChip(
                            stringResource(R.string.common_archived),
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

            // ---- 6. Topics 行（主题色 + 独立横向滚动）----
            RepositoryTopicsRow(
                topics = repo.topics,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
