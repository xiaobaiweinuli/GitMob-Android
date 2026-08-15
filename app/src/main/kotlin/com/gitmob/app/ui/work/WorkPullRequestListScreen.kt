package com.gitmob.app.ui.work

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.data.model.WorkIssueItem
import com.gitmob.app.data.model.UserPullRequestRelationFilter
import com.gitmob.app.data.model.UserPullRequestSortFilter
import com.gitmob.app.data.model.UserPullRequestStateFilter
import com.gitmob.app.data.model.UserPullRequestVisibilityFilter
import com.gitmob.app.ui.common.GitHubStateChip
import com.gitmob.app.ui.common.PullRequestStateIcon
import com.gitmob.app.ui.common.pullRequestStateVisual

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkPullRequestListScreen(
    onBack: () -> Unit,
    onItemClick: (owner: String, name: String, number: Int) -> Unit,
    viewModel: WorkPullRequestListViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拉取请求") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            PullRequestFilterControls(
                state = state.filter.state,
                relation = state.filter.relation,
                visibility = state.filter.visibility,
                sort = state.filter.sort,
                totalCount = state.totalCount,
                onStateSelected = viewModel::setStateFilter,
                onRelationSelected = viewModel::setRelationFilter,
                onVisibilitySelected = viewModel::setVisibilityFilter,
                onSortSelected = viewModel::setSortFilter,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.isLoading && state.items.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                state.loadFailed && state.items.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("加载失败")
                        Button(onClick = viewModel::retry, modifier = Modifier.padding(top = 12.dp)) {
                            Text("重试")
                        }
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.items) { item ->
                            WorkPullRequestRow(
                                item = item,
                                onClick = { onItemClick(item.repoOwner, item.repoName, item.number) },
                            )
                        }
                        if (state.hasNextPage) {
                            item {
                                LaunchedEffect(state.items.size) { viewModel.loadMore() }
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(Modifier.size(20.dp))
                                }
                            }
                        }
                        item(key = "bottom_spacer") {
                            Spacer(
                                Modifier.height(
                                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                        WindowInsets.captionBar.asPaddingValues().calculateBottomPadding(),
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

@Composable
private fun PullRequestFilterControls(
    state: UserPullRequestStateFilter,
    relation: UserPullRequestRelationFilter,
    visibility: UserPullRequestVisibilityFilter,
    sort: UserPullRequestSortFilter,
    totalCount: Int,
    onStateSelected: (UserPullRequestStateFilter) -> Unit,
    onRelationSelected: (UserPullRequestRelationFilter) -> Unit,
    onVisibilitySelected: (UserPullRequestVisibilityFilter) -> Unit,
    onSortSelected: (UserPullRequestSortFilter) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            PullRequestFilterMenu(
                label = "状态",
                selected = state,
                options = UserPullRequestStateFilter.entries,
                optionLabel = { it.label },
                onSelected = onStateSelected,
                modifier = Modifier.weight(1f),
            )
            PullRequestFilterMenu(
                label = "关系",
                selected = relation,
                options = UserPullRequestRelationFilter.entries,
                optionLabel = { it.label },
                onSelected = onRelationSelected,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            PullRequestFilterMenu(
                label = "可见性",
                selected = visibility,
                options = UserPullRequestVisibilityFilter.entries,
                optionLabel = { it.label },
                onSelected = onVisibilitySelected,
                modifier = Modifier.weight(1f),
            )
            PullRequestFilterMenu(
                label = "排序",
                selected = sort,
                options = UserPullRequestSortFilter.entries,
                optionLabel = { it.label },
                onSelected = onSortSelected,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "$totalCount 条",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider()
    }
}

@Composable
private fun <T> PullRequestFilterMenu(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(optionLabel(selected), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = "选择$label")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                    leadingIcon = {
                        if (option == selected) Icon(Icons.Default.Check, contentDescription = null)
                        else Spacer(Modifier.size(24.dp))
                    },
                )
            }
        }
    }
}

private val UserPullRequestStateFilter.label: String
    get() = when (this) {
        UserPullRequestStateFilter.OPEN -> "打开"
        UserPullRequestStateFilter.MERGED -> "已合并"
        UserPullRequestStateFilter.CLOSED_UNMERGED -> "已关闭"
        UserPullRequestStateFilter.ALL -> "全部"
    }

private val UserPullRequestRelationFilter.label: String
    get() = when (this) {
        UserPullRequestRelationFilter.INVOLVED -> "所有参与"
        UserPullRequestRelationFilter.AUTHORED -> "我创建的"
        UserPullRequestRelationFilter.ASSIGNED -> "分配给我"
        UserPullRequestRelationFilter.REVIEW_REQUESTED -> "请求我审查"
        UserPullRequestRelationFilter.COMMENTED -> "我评论过"
    }

private val UserPullRequestVisibilityFilter.label: String
    get() = when (this) {
        UserPullRequestVisibilityFilter.ALL -> "全部"
        UserPullRequestVisibilityFilter.PUBLIC -> "公开"
        UserPullRequestVisibilityFilter.PRIVATE -> "私有"
        UserPullRequestVisibilityFilter.INTERNAL -> "内部"
    }

private val UserPullRequestSortFilter.label: String
    get() = when (this) {
        UserPullRequestSortFilter.CREATED_DESC -> "最新创建"
        UserPullRequestSortFilter.CREATED_ASC -> "最早创建"
        UserPullRequestSortFilter.COMMENTS_DESC -> "最多评论"
        UserPullRequestSortFilter.COMMENTS_ASC -> "最少评论"
        UserPullRequestSortFilter.UPDATED_DESC -> "最近更新"
        UserPullRequestSortFilter.UPDATED_ASC -> "最早更新"
    }

@Composable
private fun WorkPullRequestRow(item: WorkIssueItem, onClick: () -> Unit) {
    val state = checkNotNull(item.pullRequestState)
    val visual = pullRequestStateVisual(state, item.isDraft, item.locked)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PullRequestStateIcon(state = state, isDraft = item.isDraft, locked = item.locked)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text(
                "${item.repoOwner}/${item.repoName} · #${item.number}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GitHubStateChip(visual = visual)
    }
}
