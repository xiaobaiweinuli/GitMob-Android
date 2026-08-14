package com.gitmob.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.data.model.ViewerProfile
import com.gitmob.app.ui.common.OrganizationsBottomSheet
import com.gitmob.app.ui.common.PinnedReposSection
import com.gitmob.app.ui.common.ProfilePersonHeader
import com.gitmob.app.ui.common.ProfileStatsRow
import com.gitmob.app.ui.common.StatItem
import com.gitmob.app.ui.icons.OcticonName

/**
 * 底部 Tab「主页」——当前登录用户自己的个人资料页。
 *
 * 无 TopAppBar，整页下拉刷新。
 * Scaffold 只负责给 safeDrawing(Top+Horizontal) 生成顶部 statusBar 内边距，
 * 避免内容压在状态栏上；底部 NavigationBar 高度由外层 NavDisplay 的
 * Modifier.padding(bottom = navBarBottom) 统一处理，不需要 Screen 内部再挂 Spacer。
 */
@Composable
fun HomeScreen(
    onFollowersClick: (login: String) -> Unit = {},
    onFollowingClick: (login: String) -> Unit = {},
    onReposClick: (login: String) -> Unit = {},
    onOrgClick: (login: String) -> Unit = {},
    onStarredClick: (login: String) -> Unit = {},
    onGistClick: () -> Unit = {},
    onWorkIssuesClick: () -> Unit = {},
    onWorkPullRequestsClick: () -> Unit = {},
    onWorkDiscussionsClick: () -> Unit = {},
    onInboxClick: () -> Unit = {},
    onPinnedRepoClick: (owner: String, name: String) -> Unit = { _, _ -> },
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        // 无 topBar → innerPadding.top = statusBar 高度，不含 AppBar；
        // bottom / vertical 不让 Scaffold 处理（避免跟外层 MainTabHost 的 NavigationBar 叠加）
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val statusBarTop = innerPadding.calculateTopPadding()

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading && state.profile == null -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.loadFailed -> {
                    RetryContent(
                        onRetry = viewModel::retry,
                        topPadding = statusBarTop,
                    )
                }
                state.profile != null -> {
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        ProfileContent(
                            profile = state.profile!!,
                            topPadding = statusBarTop,
                            onFollowClick = viewModel::toggleFollow,
                            onFollowersClick = { onFollowersClick(state.profile!!.user.login) },
                            onFollowingClick = { onFollowingClick(state.profile!!.user.login) },
                            onReposClick = { onReposClick(state.profile!!.user.login) },
                            onOrganizationsClick = viewModel::openOrganizations,
                            onStarredClick = { onStarredClick(state.profile!!.user.login) },
                            onGistClick = onGistClick,
                            onOrgClick = onOrgClick,
                            onWorkIssuesClick = onWorkIssuesClick,
                            onWorkPullRequestsClick = onWorkPullRequestsClick,
                            onWorkDiscussionsClick = onWorkDiscussionsClick,
                            onInboxClick = onInboxClick,
                            onPinnedRepoClick = onPinnedRepoClick,
                        )
                    }
                }
            }
        }
    }

    if (state.showOrgSheet) {
        OrganizationsBottomSheet(
            organizations = state.organizations,
            isLoading = state.isLoadingOrgs,
            onDismiss = viewModel::dismissOrganizations,
            onOrgClick = { login ->
                viewModel.dismissOrganizations()
                onOrgClick(login)
            },
        )
    }
}

/**
 * 加载失败重试面板。
 *
 * @param onRetry 重试回调（走 HomeViewModel.retry()）
 * @param topPadding 状态栏高度，防止重试面板贴到状态栏后面被刘海/滴水遮挡
 */
@Composable
private fun RetryContent(
    onRetry: () -> Unit,
    topPadding: Dp = 0.dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPadding)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("加载失败", style = MaterialTheme.typography.titleMedium)
        Text(
            "检查网络连接后重试",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Button(onClick = onRetry) { Text("重试") }
    }
}

/**
 * 主页（个人资料 + 置顶仓库 + 事务入口）的滚动内容。
 *
 * @param profile      当前登录用户的资料聚合数据
 * @param topPadding   状态栏高度（Scaffold innerPadding.top），避免内容贴在状态栏后面
 */
@Composable
private fun ProfileContent(
    profile: ViewerProfile,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onFollowClick: () -> Unit,
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit,
    onReposClick: () -> Unit,
    onOrganizationsClick: () -> Unit,
    onStarredClick: () -> Unit,
    onGistClick: () -> Unit,
    onOrgClick: (login: String) -> Unit,
    onWorkIssuesClick: () -> Unit,
    onWorkPullRequestsClick: () -> Unit,
    onWorkDiscussionsClick: () -> Unit,
    onInboxClick: () -> Unit,
    onPinnedRepoClick: (owner: String, name: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = topPadding)
            .padding(16.dp),
    ) {
        // ---- 个人资料头部：公共 ProfilePersonHeader 统一渲染（头像+姓名+关注者/关注+状态+简介+信息行+徽章行）
        // 这里不再保留私有副本，以后改头部样式只改 ui/common/ProfilePersonHeader.kt 一处即可 ----
        ProfilePersonHeader(
            avatarUrl = profile.user.avatarUrl,
            name = profile.user.name,
            login = profile.user.login,
            pronouns = profile.extra?.pronouns,
            followersCount = profile.user.followers,
            followingCount = profile.user.following,
            onFollowersClick = onFollowersClick,
            onFollowingClick = onFollowingClick,
            followState = profile.followState,
            onFollowClick = onFollowClick,
            status = profile.extra?.status,
            bio = profile.user.bio,
            location = profile.user.location,
            company = profile.user.company,
            websiteUrl = profile.user.websiteUrl,
            email = profile.user.email,
            socialAccounts = profile.extra?.socialAccounts,
            isDeveloperProgramMember = profile.user.isDeveloperProgramMember,
            isBountyHunter = profile.user.isBountyHunter,
            isCampusExpert = profile.user.isCampusExpert,
            isGitHubStar = profile.user.isGitHubStar,
        )

        // ---- 统计行：仓库 / 组织 / 星标 / Gist，公共 ProfileStatsRow 统一渲染 ----
        ProfileStatsRow(
            stats = listOf(
                StatItem(OcticonName.REPO, profile.repoCount, "仓库", onClick = onReposClick),
                StatItem(OcticonName.ORGANIZATION, profile.user.organizationsCount, "组织", onClick = onOrganizationsClick),
                StatItem(OcticonName.STAR, profile.starredCount, "星标", onClick = onStarredClick),
                StatItem(Icons.Default.Code, profile.gistCount, "Gist", onClick = onGistClick),
            ),
            modifier = Modifier.padding(top = 16.dp),
        )

        PinnedReposSection(
            repos = profile.pinnedRepos,
            onRepoClick = onPinnedRepoClick,
            modifier = Modifier.padding(top = 20.dp),
        )

        // ---- 事务入口：议题/PR/讨论带数字（involves:@me 聚合，只对当前登录用户有意义），
        // 收件箱纯入口不带数字（REST 通知接口没有现成的 count 端点，不强求精确数字） ----
        HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
        WorkEntryRow(Icons.Default.Adjust, "议题", profile.involvedIssueCount, onWorkIssuesClick)
        WorkEntryRow(Icons.AutoMirrored.Filled.CallSplit, "拉取请求", profile.involvedPrCount, onWorkPullRequestsClick)
        WorkEntryRow(Icons.Default.ChatBubble, "讨论", profile.involvedDiscussionCount, onWorkDiscussionsClick)
        WorkEntryRow(Icons.Default.Inbox, "收件箱", count = null, onInboxClick)
    }
}

@Composable
private fun WorkEntryRow(icon: ImageVector, label: String, count: Int?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, modifier = Modifier
            .padding(start = 16.dp)
            .weight(1f))
        count?.let { Text("$it", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
