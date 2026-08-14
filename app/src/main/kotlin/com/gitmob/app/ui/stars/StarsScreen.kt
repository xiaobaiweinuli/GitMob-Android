package com.gitmob.app.ui.stars

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.data.model.StarFilter
import com.gitmob.app.data.model.UserListSummary
import com.gitmob.app.ui.common.StarredRepoCard

/**
 * 底部 Tab「星标」——当前登录用户的星标管理页（带列表筛选 Chip 行 + 下拉刷新 + 分页）。
 *
 * Scaffold 只处理 safeDrawing(Top+Horizontal) 生成 statusBar 内边距，
 * Chip 行在 ListsSection 前手动 Spacer(statusBarTop) 占位；
 * 底部 NavigationBar 高度由外层 NavDisplay 统一处理，不需要 Screen 内部再挂 Spacer。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarsScreen(
    onRepoClick: (owner: String, name: String) -> Unit = { _, _ -> },
    onForkSourceClick: (owner: String, name: String) -> Unit = { _, _ -> },
    onHomepageClick: (url: String) -> Unit = {},
    viewModel: StarsViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        // 无 TopAppBar → innerPadding.top = statusBar 高度
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val statusBarTop = innerPadding.calculateTopPadding()

        Column(modifier = Modifier.fillMaxSize()) {
            // ListsSection（Chip 行）：始终可见，不随列表滚动，先跳过 statusBar 高度
            Spacer(Modifier.height(statusBarTop))
            ListsSection(
                lists = state.lists,
                selectedFilter = state.selectedFilter,
                expanded = state.listsSectionExpanded,
                onToggleExpanded = viewModel::toggleListsSectionExpanded,
                onSelectFilter = viewModel::selectFilter,
                onCreateListClick = viewModel::openCreateListDialog,
                onEditListClick = viewModel::openEditListDialog,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
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
                                    // 当前登录用户自己的星标 Tab：显示 viewer 专属操作按钮（添加到列表 + 取消星标）
                                    StarredRepoCard(
                                        repo = repo,
                                        onClick = { onRepoClick(repo.ownerLogin, repo.name) },
                                        onForkSourceClick = onForkSourceClick,
                                        onHomepageClick = onHomepageClick,
                                        showViewerActions = true,
                                        onAddToListClick = { viewModel.openAddToList(repo) },
                                        onUnstarClick = { viewModel.unstarRepo(repo) },
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

    if (state.showCreateListDialog) {
        CreateListDialog(
            isSaving = state.isCreatingList,
            onDismiss = viewModel::dismissCreateListDialog,
            onConfirm = viewModel::createList,
        )
    }

    state.editingList?.let { editing ->
        EditListDialog(
            list = editing,
            isSaving = state.isSavingListEdit,
            onDismiss = viewModel::dismissEditListDialog,
            onConfirm = viewModel::saveListEdit,
            onDelete = { viewModel.deleteList(editing.id) },
        )
    }

    state.addToListTarget?.let { target ->
        AddToListBottomSheet(
            targetRepo = target,
            lists = state.lists,
            selection = state.addToListSelection,
            isLoadingSelection = state.isLoadingAddToListSelection,
            isSaving = state.isSavingAddToList,
            onToggle = viewModel::toggleListSelection,
            onCreateNewList = viewModel::openCreateListDialog,
            onDismiss = viewModel::dismissAddToList,
            onConfirm = viewModel::confirmAddToList,
        )
    }
}

@Composable
private fun ListsSection(
    lists: List<UserListSummary>,
    selectedFilter: StarFilter,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectFilter: (StarFilter) -> Unit,
    onCreateListClick: () -> Unit,
    onEditListClick: (UserListSummary) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableX(onClick = onToggleExpanded)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Menu, contentDescription = null)
            Text(
                "我的列表",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
            IconButton(onClick = onCreateListClick) {
                Icon(Icons.Default.Add, contentDescription = "新建列表")
            }
        }

        if (expanded) {
            Column {
                ListRow(
                    icon = Icons.Default.Star,
                    label = "全部星标",
                    count = null,
                    selected = selectedFilter is StarFilter.All,
                    onClick = { onSelectFilter(StarFilter.All) },
                )
                lists.forEach { list ->
                    ListRow(
                        icon = Icons.Default.Star,
                        label = list.name,
                        count = list.itemCount,
                        selected = (selectedFilter as? StarFilter.ByList)?.list?.id == list.id,
                        onClick = { onSelectFilter(StarFilter.ByList(list)) },
                        onEditClick = { onEditListClick(list) },
                    )
                }
            }
        }
    }
}

/**
 * 临时 helper：避免 Modifier.clickable 的 import 冲突。
 * 已经在顶部 import androidx.compose.foundation.clickable，这里直接走 Modifier.clickable 扩展语法即可。
 */
private fun Modifier.clickableX(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun ListRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    onEditClick: (() -> Unit)? = null,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableX(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(label, modifier = Modifier.padding(start = 12.dp).weight(1f))
            count?.let { Text("$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            onEditClick?.let {
                IconButton(onClick = it, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
