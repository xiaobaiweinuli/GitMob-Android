package com.gitmob.app.ui.userlist

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gitmob.app.R
import com.gitmob.app.data.model.SimpleUser

/**
 * 关注者/关注共用这一个 Screen，靠 mode 参数区分标题和数据来源（ViewModel 内部已经分流）。
 * login/mode 从 Nav3 的 entry<FollowersRoute>{route -> ...} 直接拿 route 里的字段传入，
 * 不走 SavedStateHandle，见 UserListViewModel 顶部注释。
 *
 * 标题映射：FOLLOWERS → "关注者"，FOLLOWING → "关注"，ORG_MEMBERS → "成员"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListScreen(
    login: String,
    mode: UserListMode,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: UserListViewModel = hiltViewModel(),
) {
    LaunchedEffect(login, mode) { viewModel.init(login, mode) }
    val title = stringResource(
        when (mode) {
            UserListMode.FOLLOWERS -> R.string.userlist_title_followers
            UserListMode.FOLLOWING -> R.string.userlist_title_following
            UserListMode.ORG_MEMBERS -> R.string.common_members
            // WATCHERS 走 RepoWatchersScreen，一般不会到这里；兜底保持一致（仓库语境用 common_watchers）
            UserListMode.WATCHERS -> R.string.common_watchers
        },
    )
    UserListBody(
        title = title,
        viewModel = viewModel,
        onUserClick = onUserClick,
        onBack = onBack,
    )
}

/** 仓库关注者（Watchers）——仓库维度，不是用户维度，走单独的入口调用 initForRepoWatchers */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoWatchersScreen(
    owner: String,
    name: String,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: UserListViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name) { viewModel.initForRepoWatchers(owner, name) }
    UserListBody(title = stringResource(R.string.common_watchers), viewModel = viewModel, onUserClick = onUserClick, onBack = onBack)
}

/**
 * 组织成员（ORG_MEMBERS）——组织维度，零新文件复用 UserList 架构。
 * 与 RepoWatchersScreen 同构：单参数 + initForOrgMembers + 统一 UserListBody 渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrgMembersScreen(
    orgLogin: String,
    onBack: () -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: UserListViewModel = hiltViewModel(),
) {
    LaunchedEffect(orgLogin) { viewModel.initForOrgMembers(orgLogin) }
    UserListBody(title = stringResource(R.string.common_members), viewModel = viewModel, onUserClick = onUserClick, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserListBody(
    title: String,
    viewModel: UserListViewModel,
    onUserClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        when {
            state.isLoading && state.users.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.loadFailed && state.users.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.common_load_failed))
                    Button(onClick = viewModel::retry, modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    items(state.users) { user ->
                        UserRow(user, onClick = { onUserClick(user.login) })
                    }
                    if (state.hasNextPage) {
                        item {
                            LaunchedEffect(Unit) { viewModel.loadMore() }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    // Push route 底部：navigationBars + captionBar 高度
                    item(key = "bottom_spacer") {
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
private fun UserRow(user: SimpleUser, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = user.avatarUrl,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(user.name ?: user.login, style = MaterialTheme.typography.titleSmall)
            Text("@${user.login}", style = MaterialTheme.typography.bodySmall)
            user.bio?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}
