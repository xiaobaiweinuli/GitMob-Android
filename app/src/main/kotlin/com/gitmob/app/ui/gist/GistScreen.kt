package com.gitmob.app.ui.gist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.GistSort
import com.gitmob.app.ui.common.GistCard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun GistScreen(
    login: String? = null,
    onBack: (() -> Unit)? = null,
    onGistClick: (url: String) -> Unit = {},
    viewModel: GistViewModel = hiltViewModel(),
) {
    LaunchedEffect(login) { viewModel.init(login) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saveableStateHolder = rememberSaveableStateHolder()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp),
            ) {
                GistControls(
                    title = login?.let { "@$it 的 Gist" } ?: if (onBack != null) "我的 Gist" else "Gist",
                    onBack = onBack,
                    selectedCategory = state.selectedCategory,
                    selectedSort = state.selectedSort,
                    onCategorySelected = viewModel::selectCategory,
                    onSortSelected = viewModel::selectSort,
                )

                saveableStateHolder.SaveableStateProvider(state.selectedCategory.name) {
                    GistList(
                        state = state,
                        onRefresh = viewModel::refresh,
                        onRetry = viewModel::retry,
                        onLoadMore = viewModel::loadMore,
                        onGistClick = onGistClick,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (onBack != null) {
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

@Composable
private fun GistControls(
    title: String,
    onBack: (() -> Unit)?,
    selectedCategory: GistCategory,
    selectedSort: GistSort,
    onCategorySelected: (GistCategory) -> Unit,
    onSortSelected: (GistSort) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) {
                if (maxWidth < 360.dp) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategorySelector(
                            selectedCategory = selectedCategory,
                            onCategorySelected = onCategorySelected,
                            modifier = Modifier.width(220.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        SortMenu(selectedSort, onSortSelected)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategorySelector(
                            selectedCategory = selectedCategory,
                            onCategorySelected = onCategorySelected,
                            modifier = Modifier.widthIn(min = 200.dp, max = 240.dp),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        SortMenu(selectedSort, onSortSelected)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySelector(
    selectedCategory: GistCategory,
    onCategorySelected: (GistCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        GistCategory.entries.forEachIndexed { index, category ->
            SegmentedButton(
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) },
                shape = SegmentedButtonDefaults.itemShape(index, GistCategory.entries.size),
            ) {
                Text(category.label)
            }
        }
    }
}

@Composable
private fun SortMenu(
    selectedSort: GistSort,
    onSortSelected: (GistSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = true,
            onClick = { expanded = true },
            label = { Text(selectedSort.label, maxLines = 1) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            GistSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.label) },
                    onClick = {
                        expanded = false
                        onSortSelected(sort)
                    },
                )
            }
        }
    }
}

@Composable
private fun GistList(
    state: GistUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onGistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.selectedSort) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(listState, state.selectedCategory, state.selectedSort, state.hasNextPage) {
        if (!state.hasNextPage) return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            listState.isScrollInProgress &&
                layoutInfo.totalItemsCount > 0 &&
                lastVisibleIndex >= layoutInfo.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { onLoadMore() }
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.isLoading && state.items.isEmpty() -> item(key = "loading") {
                    FullHeightMessage {
                        CircularProgressIndicator()
                    }
                }
                state.loadFailed && state.items.isEmpty() -> item(key = "error") {
                    FullHeightMessage {
                        Text("加载失败", style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text("重试")
                        }
                    }
                }
                state.items.isEmpty() -> item(key = "empty") {
                    FullHeightMessage {
                        Text(
                            text = if (state.hasNextPage) "当前批次没有匹配的 Gist" else {
                                when (state.selectedCategory) {
                                    GistCategory.ORIGINAL -> "还没有原创 Gist"
                                    GistCategory.FORKED -> "还没有复刻 Gist"
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.hasNextPage) {
                            Button(
                                onClick = onLoadMore,
                                enabled = !state.isLoadingMore,
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                } else {
                                    Text("继续查找")
                                }
                            }
                        }
                    }
                }
                else -> items(state.items, key = { it.id }) { gist ->
                    GistCard(
                        gist = gist,
                        onClick = { onGistClick(gist.url) },
                    )
                }
            }

            if (state.isLoadingMore) {
                item(key = "loading-more") {
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

@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.FullHeightMessage(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillParentMaxHeight()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

private val GistCategory.label: String
    get() = when (this) {
        GistCategory.ORIGINAL -> "原创"
        GistCategory.FORKED -> "复刻"
    }

private val GistSort.label: String
    get() = when (this) {
        GistSort.RECENTLY_CREATED -> "最近创建"
        GistSort.RECENTLY_UPDATED -> "最近修改"
        GistSort.OLDEST_CREATED -> "最早创建"
        GistSort.OLDEST_UPDATED -> "最早修改"
    }
