package com.gitmob.app.ui.repos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.ui.common.RepoCard

/**
 * 仓库列表页（底部 Tab「仓库」+ 他人主页 → 点击「仓库」统计项共用这一个 Screen）。
 *
 * 通过 [login] 参数区分两种场景：
 *   - `login = null`：底部 Tab「仓库」，查 viewer（当前登录用户）自己拥有的仓库
 *   - `login = "某用户"`：他人个人资料页点进去的仓库列表，查该用户公开的仓库
 *
 * 两种场景的 UI 完全一致（卡片样式、下拉刷新、分页、加载失败重试），
 * 只是 ReposViewModel 内部调用 RepoRepository.getRepos(login?, after) 时根字段不同（viewer vs user(login:)），
 * Repository 层已经统一封装，Screen 层感知不到差异。
 *
 * Scaffold 只处理 statusBar / horizontal 内边距，底部 NavigationBar 高度由外层
 * NavDisplay 统一处理（Tab 根页）或 Push 路由 Screen 自己处理。
 *
 * @param login 要查看的用户 login；`null` 表示当前登录用户自己（底部 Tab「仓库」）
 * @param onRepoClick 点击某张仓库卡片的回调，传 ownerLogin + repoName 跳仓库详情
 * @param viewModel ReposViewModel（两种模式共用同一个 Hilt ViewModel）
 */
@Composable
fun ReposScreen(
    login: String? = null,
    onRepoClick: (owner: String, name: String) -> Unit = { _, _ -> },
    onForkSourceClick: (owner: String, name: String) -> Unit = { _, _ -> },
    onHomepageClick: (url: String) -> Unit = {},
    viewModel: ReposViewModel = hiltViewModel(),
) {
    LaunchedEffect(login) { viewModel.init(login) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        // 无 TopAppBar → innerPadding.top = statusBar 高度
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.isLoading && state.repos.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.loadFailed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("加载失败", style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = viewModel::retry,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text("重试")
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
                                RepoCard(
                                    repo = repo,
                                    onClick = { onRepoClick(repo.ownerLogin, repo.name) },
                                    onForkSourceClick = onForkSourceClick,
                                    onHomepageClick = onHomepageClick,
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
                        }
                    }
                }
            }
        }
    }
}
