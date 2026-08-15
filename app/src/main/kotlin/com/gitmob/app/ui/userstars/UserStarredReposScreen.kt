package com.gitmob.app.ui.userstars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.ui.common.StarredRepoCard

/**
 * 他人星标仓库列表页（只读、轻量版）。
 *
 * 与底部 Tab「星标」(StarsScreen) 的区别：
 * - StarsScreen：当前登录用户自己的星标，带建/改/删列表、加入列表 BottomSheet、取消星标等完整管理交互
 * - 本 Screen：任意 login 用户的公开星标，只展示**只读**卡片，不显示 viewer 专属操作按钮
 *   （你无法管理别人的收藏夹，也无法替别人取消星标）
 *
 * 卡片复用公共 StarredRepoCard(showViewerActions=false)，右侧的"添加到列表 + 取消星标"按钮会被隐藏。
 * ViewModel 复用轻量 UserStarredReposViewModel（没有列表管理的状态机，只有基础分页加载）。
 *
 * @param login 要查看的目标用户 login（非空，因为是从他人资料页跳转进来的）
 * @param onBack 返回上一页回调（Nav3 backStack.removeLastOrNull()）
 * @param onRepoClick 点击某张仓库卡片跳详情，传 ownerLogin + repoName
 * @param viewModel 轻量只读 ViewModel（Hilt 注入）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserStarredReposScreen(
    login: String,
    onBack: () -> Unit,
    onRepoClick: (owner: String, name: String) -> Unit = { _, _ -> },
    onForkSourceClick: (owner: String, name: String) -> Unit = { _, _ -> },
    onHomepageClick: (url: String) -> Unit = {},
    viewModel: UserStarredReposViewModel = hiltViewModel(),
) {
    LaunchedEffect(login) { viewModel.init(login) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.userstars_title, login)) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.isLoading && state.repos.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.loadFailed && state.repos.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(R.string.common_load_failed), style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = viewModel::retry,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.repos) { repo ->
                                // 查看他人星标：隐藏 viewer 专属操作（添加到列表 + 取消星标）
                                StarredRepoCard(
                                    repo = repo,
                                    onClick = { onRepoClick(repo.ownerLogin, repo.name) },
                                    onForkSourceClick = onForkSourceClick,
                                    onHomepageClick = onHomepageClick,
                                    showViewerActions = false,
                                )
                            }
                            if (state.hasNextPage) {
                                item {
                                    LaunchedEffect(state.repos.size) { viewModel.loadMore() }
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
    }
}
