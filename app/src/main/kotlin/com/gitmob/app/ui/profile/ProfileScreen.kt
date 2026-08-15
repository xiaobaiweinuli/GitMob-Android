package com.gitmob.app.ui.profile

import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gitmob.app.data.model.ProfileOwner
import com.gitmob.app.ui.common.OrganizationsBottomSheet
import com.gitmob.app.ui.common.PinnedReposSection
import com.gitmob.app.ui.common.ProfilePersonHeader
import com.gitmob.app.ui.common.ProfileStatsRow
import com.gitmob.app.ui.common.StatItem
import com.gitmob.app.ui.icons.OcticonName
import com.gitmob.app.ui.userlist.OrgMembersScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    login: String,
    onBack: () -> Unit,
    onFollowersClick: (login: String) -> Unit = {},
    onFollowingClick: (login: String) -> Unit = {},
    onReposClick: (login: String) -> Unit = {},
    onStarredClick: (login: String) -> Unit = {},
    onGistClick: (login: String?) -> Unit = {},
    onMembersClick: (login: String) -> Unit = {},
    onOrgClick: (login: String) -> Unit = {},
    onPinnedRepoClick: (owner: String, name: String) -> Unit = { _, _ -> },
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    LaunchedEffect(login) { viewModel.init(login) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("@$login") },
                // ★ safeDrawing(Top+Horizontal)，不用 WindowInsets(0)
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
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
                state.isLoading && state.owner == null -> {
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
                        Text(
                            "检查网络连接后重试",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                        )
                        // 修复：之前是个空壳 lambda，现在走 viewModel.retry()
                        // （内部会复位 initialized 标志 + 重新触发 load，与 HomeScreen 行为一致）
                        Button(onClick = viewModel::retry) { Text("重试") }
                    }
                }
                state.owner != null -> when (val owner = state.owner!!) {
                    is ProfileOwner.Person -> PersonProfileContent(
                        owner = owner,
                        onFollowClick = viewModel::toggleFollow,
                        onFollowersClick = { onFollowersClick(owner.login) },
                        onFollowingClick = { onFollowingClick(owner.login) },
                        onReposClick = { onReposClick(owner.login) },
                        onOrganizationsClick = viewModel::openOrganizations,
                        onStarredClick = { onStarredClick(owner.login) },
                        onGistClick = { onGistClick(owner.login.takeUnless { owner.isViewer }) },
                        onPinnedRepoClick = onPinnedRepoClick,
                    )
                    is ProfileOwner.Org -> OrgProfileContent(
                        owner = owner,
                        onFollowClick = viewModel::toggleFollow,
                        onReposClick = { onReposClick(owner.login) },
                        onMembersClick = { onMembersClick(owner.login) },
                        onPinnedRepoClick = onPinnedRepoClick,
                    )
                }
            }
        }
    }

    // ---- 选择组织底部弹窗（与 HomeScreen 同款，走 common/OrganizationsBottomSheet）
    // 只有 Person 类型才能拥有组织，Org 类型 viewModel.openOrganizations() 内部已经做了 is 防御检查
    if (state.showOrgSheet) {
        OrganizationsBottomSheet(
            organizations = state.organizations,
            isLoading = state.isLoadingOrgs,
            onDismiss = viewModel::dismissOrganizations,
            onOrgClick = { orgLogin ->
                viewModel.dismissOrganizations()
                onOrgClick(orgLogin)
            },
        )
    }
}

@Composable
private fun PersonProfileContent(
    owner: ProfileOwner.Person,
    onFollowClick: () -> Unit,
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit,
    onReposClick: () -> Unit,
    onOrganizationsClick: () -> Unit,
    onStarredClick: () -> Unit,
    onGistClick: () -> Unit,
    onPinnedRepoClick: (owner: String, name: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // ---- 个人资料头部：公共 ProfilePersonHeader 统一渲染（与 HomeScreen 视觉完全对齐）
        // 公共组件已内置：头像 + 姓名/@login/代词 + 关注者/关注 + 关注按钮 + 状态气泡 + 简介
        //                + 位置/公司 + 网站/邮箱/社交账号 + 徽章行（4 个真实徽章，非空才渲染）
        // ProfileOwner.Person 与当前用户主页共用同一套资料字段；公共组件会对 null/空列表自动隐藏。
        ProfilePersonHeader(
            avatarUrl = owner.avatarUrl,
            name = owner.name,
            login = owner.login,
            pronouns = owner.pronouns,
            followersCount = owner.followersCount,
            followingCount = owner.followingCount,
            onFollowersClick = onFollowersClick,
            onFollowingClick = onFollowingClick,
            followState = owner.followState,
            onFollowClick = onFollowClick,
            status = owner.status,
            bio = owner.bio,
            location = owner.location,
            company = owner.company,
            websiteUrl = owner.websiteUrl,
            email = owner.email,
            socialAccounts = owner.socialAccounts,
            isDeveloperProgramMember = owner.isDeveloperProgramMember,
            isBountyHunter = owner.isBountyHunter,
            isCampusExpert = owner.isCampusExpert,
            isGitHubStar = owner.isGitHubStar,
        )

        // ---- 统计行：仓库 / 组织 / 星标 / Gist，与 HomeScreen 视觉完全对齐 ----
        ProfileStatsRow(
            stats = listOf(
                StatItem(OcticonName.REPO, owner.repoCount, "仓库", onClick = onReposClick),
                StatItem(OcticonName.ORGANIZATION, owner.organizationsCount, "组织", onClick = onOrganizationsClick),
                StatItem(OcticonName.STAR, owner.starredCount, "星标", onClick = onStarredClick),
                StatItem(Icons.Default.Code, owner.gistCount, "Gist", onClick = onGistClick),
            ),
            modifier = Modifier.padding(top = 16.dp),
        )

        PinnedReposSection(
            repos = owner.pinnedRepos,
            onRepoClick = onPinnedRepoClick,
            modifier = Modifier.padding(top = 20.dp),
        )

        // Push route 底部：navigationBars + captionBar 高度补偿
        Spacer(
            Modifier.height(
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding(),
            ),
        )
    }
}

/**
 * 组织主页内容组件
 *
 * @param owner 组织资料信息
 * @param onFollowClick 关注/取消关注按钮点击回调
 * @param onReposClick 仓库统计项点击回调
 * @param onMembersClick 成员统计项点击回调
 */
@Composable
private fun OrgProfileContent(
    owner: ProfileOwner.Org,
    onFollowClick: () -> Unit,
    onReposClick: () -> Unit,
    onMembersClick: () -> Unit,
    onPinnedRepoClick: (owner: String, name: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // ---- 组织头部：头像 + 名称（含验证徽章）+ @login ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = owner.avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape),
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(owner.name ?: owner.login, style = MaterialTheme.typography.titleLarge)
                    if (owner.isVerified) {
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = "已验证",
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text("@${owner.login}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ---- 关注按钮（对齐个人页，@login 下方）----
        Box(modifier = Modifier.padding(top = 12.dp)) {
            if (owner.viewerIsFollowing) {
                OutlinedButton(onClick = onFollowClick) {
                    Text("已关注")
                }
            } else {
                Button(onClick = onFollowClick) {
                    Text("关注")
                }
            }
        }

        // 组织简介与个人 Bio 保持相同的信息层级：位于资料头部之后、统计和置顶仓库之前。
        owner.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // ---- 组织统计行：仓库 / 成员，二列，公共 ProfileStatsRow 统一渲染
        // （和个人 Profile 的四列统计行保持相同的 SpaceEvenly 等距样式，只是元素数量为 2）
        ProfileStatsRow(
            stats = listOf(
                StatItem(OcticonName.REPO, owner.repoCount, "仓库", onClick = onReposClick),
                StatItem(OcticonName.PEOPLE, owner.membersCount, "成员", onClick = onMembersClick),
            ),
            modifier = Modifier.padding(top = 12.dp),
        )

        PinnedReposSection(
            repos = owner.pinnedRepos,
            onRepoClick = onPinnedRepoClick,
            modifier = Modifier.padding(top = 20.dp),
        )

        // Push route 底部：navigationBars + captionBar 高度补偿
        Spacer(
            Modifier.height(
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding(),
            ),
        )
    }
}
