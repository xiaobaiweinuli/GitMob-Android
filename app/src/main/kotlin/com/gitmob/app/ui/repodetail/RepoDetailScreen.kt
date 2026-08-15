package com.gitmob.app.ui.repodetail

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gitmob.app.data.model.RepoDetail
import com.gitmob.app.ui.common.MarkdownWebView
import com.gitmob.app.ui.common.RepositoryTopicsRow
import com.gitmob.app.ui.common.StatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    owner: String,
    name: String,
    onBack: () -> Unit,
    onOwnerClick: (login: String) -> Unit,
    onForkSourceClick: (owner: String, name: String) -> Unit,
    onNavigateBranches: (currentRef: String, canManageBranchProtection: Boolean) -> Unit,
    onNavigateCode: (ref: String) -> Unit,
    onNavigateCommits: (ref: String) -> Unit,
    onNavigateWatchers: () -> Unit,
    onNavigateIssues: (permission: com.gitmob.app.core.permission.RepoPermission, viewerCanCreateIssues: Boolean) -> Unit,
    onNavigatePlaceholder: (label: String) -> Unit,
    viewModel: RepoDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.isLoading && state.detail == null -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.loadFailed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("加载失败", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = viewModel::retry, modifier = Modifier.padding(top = 12.dp)) { Text("重试") }
                    }
                }
                state.detail != null -> {
                    val detail = state.detail!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        RepoHeader(
                            detail = detail,
                            onStarClick = viewModel::toggleStar,
                            onOwnerClick = { onOwnerClick(detail.ownerLogin) },
                            onForkSourceClick = {
                                val forkOwner = detail.forkedFromOwner
                                val forkName = detail.forkedFromName
                                if (forkOwner != null && forkName != null) onForkSourceClick(forkOwner, forkName)
                            },
                        )
                        RepoMenu(
                            detail = detail,
                            currentRef = state.currentRef ?: detail.defaultBranchName ?: "main",
                            onNavigateBranches = {
                                val ref = state.currentRef ?: detail.defaultBranchName ?: "main"
                                onNavigateBranches(ref, detail.capabilities.canManageBranchProtection)
                            },
                            onNavigateCode = onNavigateCode,
                            onNavigateCommits = onNavigateCommits,
                            onNavigateWatchers = onNavigateWatchers,
                            onNavigateIssues = { onNavigateIssues(detail.permission, detail.viewerCanCreateIssues) },
                            onNavigatePlaceholder = onNavigatePlaceholder,
                        )
                        val readmeHtml = state.readmeHtml
                        if (readmeHtml != null) {
                            if (state.readmeTruncated) {
                                Text(
                                    "README 内容过长，仅显示部分",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            MarkdownWebView(bodyHtml = readmeHtml)
                        } else if (state.isLoadingReadme) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            }
                        }
                        // Push route 底部：navigationBars + captionBar 高度
                        Spacer(
                            Modifier.height(
                                WindowInsets.navigationBars.asPaddingValues()
                                    .calculateBottomPadding() +
                                    WindowInsets.captionBar.asPaddingValues()
                                        .calculateBottomPadding(),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoHeader(
    detail: RepoDetail,
    onStarClick: () -> Unit,
    onOwnerClick: () -> Unit,
    onForkSourceClick: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onOwnerClick),
        ) {
            AsyncImage(
                model = detail.ownerAvatarUrl,
                contentDescription = null,
                modifier = Modifier.size(24.dp).clip(CircleShape),
            )
            Text(detail.ownerLogin, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
        }
        Text(detail.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp))

        if (detail.isFork && detail.forkedFromOwner != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable(onClick = onForkSourceClick),
            ) {
                Icon(Icons.AutoMirrored.Default.CallSplit, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(
                    " 复刻自 ${detail.forkedFromOwner}/${detail.forkedFromName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, // 用主色提示"这个可以点"，和纯说明性文字区分开
                )
            }
        }

        detail.description?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }

        // 状态标签行：模板 / 私有 / 已归档
        Row(modifier = Modifier.padding(top = 8.dp)) {
            if (detail.isTemplate) StatusChip("模板")
            if (detail.isPrivate) StatusChip("私有")
            if (detail.isArchived) StatusChip("已归档")
        }

        // 语言信息独占一行，颜色圆点和语言名称保持对齐。
        detail.languageName?.let { lang ->
            val dotColor = detail.languageColor
                ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                ?: MaterialTheme.colorScheme.outline
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = dotColor, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
                Text(
                    text = lang,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        // Topics 位于语言下方，并在自己的区域内独立左右滑动。
        RepositoryTopicsRow(
            topics = detail.topics,
            modifier = Modifier.padding(top = if (detail.languageName != null) 6.dp else 8.dp),
        )

        // 操作按钮：标星 / Fork（Watch 放进菜单里的关注者行，简化处理）
        Row(modifier = Modifier.padding(top = 12.dp)) {
            if (detail.viewerHasStarred) {
                OutlinedButton(onClick = onStarClick) {
                    Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" 已标星 ${detail.stargazerCount}", modifier = Modifier.padding(start = 4.dp))
                }
            } else {
                Button(onClick = onStarClick) {
                    Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" 标星 ${detail.stargazerCount}", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun RepoMenu(
    detail: RepoDetail,
    currentRef: String,
    onNavigateBranches: () -> Unit,
    onNavigateCode: (ref: String) -> Unit,
    onNavigateCommits: (ref: String) -> Unit,
    onNavigateWatchers: () -> Unit,
    onNavigateIssues: () -> Unit,
    onNavigatePlaceholder: (label: String) -> Unit,
) {
    Column {
        MenuRow(Icons.Default.Adjust, "议题", detail.openIssueCount, onClick = onNavigateIssues)
        MenuRow(Icons.AutoMirrored.Default.CallSplit, "拉取请求", detail.openPrCount, onClick = { onNavigatePlaceholder("拉取请求") })
        MenuRow(Icons.Default.PlayCircle, "操作", null, onClick = { onNavigatePlaceholder("操作") })
        MenuRow(
            Icons.Default.Sell, "发行版", detail.releaseCount,
            subtitle = detail.latestReleaseTag,
            onClick = { onNavigatePlaceholder("发行版") },
        )
        MenuRow(Icons.Default.ChatBubble, "讨论", null, onClick = { onNavigatePlaceholder("讨论") })
        MenuRow(Icons.Default.Groups, "贡献者", null, onClick = { onNavigatePlaceholder("贡献者") })
        MenuRow(Icons.Default.Visibility, "关注者", detail.watcherCount, onClick = onNavigateWatchers)
        detail.licenseName?.let {
            MenuRow(Icons.Default.Balance, "许可证", null, subtitle = it, onClick = { onNavigatePlaceholder("许可证") })
        }
        MenuRow(
            Icons.Default.AccountTree, "分支", detail.branchCount,
            subtitle = "当前：$currentRef",
            onClick = onNavigateBranches,
        )
        MenuRow(Icons.Default.Code, "代码", null, onClick = { onNavigateCode(currentRef) })
        MenuRow(Icons.AutoMirrored.Default.Label, "提交", null, onClick = { onNavigateCommits(currentRef) })
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    count: Int?,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            Text(label)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        count?.let { Text("$it", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) }
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}
