package com.gitmob.app.ui.gist

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.GistListItem
import com.gitmob.app.data.model.GistSort
import com.gitmob.app.data.model.GistVisibility
import com.gitmob.app.ui.common.FilterCapsuleMenu
import com.gitmob.app.ui.common.GistCard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GistScreen(
    login: String? = null,
    onBack: (() -> Unit)? = null,
    onGistClick: (GistListItem) -> Unit = {},
    onForkSourceClick: (id: String) -> Unit = {},
    viewModel: GistViewModel = hiltViewModel(),
) {
    LaunchedEffect(login) { viewModel.init(login) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saveableStateHolder = rememberSaveableStateHolder()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        login?.let { stringResource(R.string.gist_title_of_user, it) }
                            ?: stringResource(if (onBack != null) R.string.gist_title_mine else R.string.nav_tab_gist),
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize(),
            ) {
                GistControls(
                    selectedCategory = state.selectedCategory,
                    selectedSort = state.selectedSort,
                    selectedVisibility = state.selectedVisibility,
                    onCategorySelected = viewModel::selectCategory,
                    onSortSelected = viewModel::selectSort,
                    onVisibilitySelected = viewModel::selectVisibility,
                )

                saveableStateHolder.SaveableStateProvider(state.selectedCategory.name) {
                    GistList(
                        state = state,
                        onRefresh = viewModel::refresh,
                        onRetry = viewModel::retry,
                        onLoadMore = viewModel::loadMore,
                        onGistClick = onGistClick,
                        onForkSourceClick = onForkSourceClick,
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
    selectedCategory: GistCategory,
    selectedSort: GistSort,
    selectedVisibility: GistVisibility,
    onCategorySelected: (GistCategory) -> Unit,
    onSortSelected: (GistSort) -> Unit,
    onVisibilitySelected: (GistVisibility) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterCapsuleMenu(
            selected = selectedCategory,
            options = GistCategory.entries,
            optionLabel = { stringResource(it.labelRes) },
            onSelected = onCategorySelected,
            filterLabel = stringResource(R.string.gist_filter_category),
        )
        FilterCapsuleMenu(
            selected = selectedSort,
            options = listOf(
                GistSort.RECENTLY_UPDATED,
                GistSort.OLDEST_CREATED,
                GistSort.RECENTLY_CREATED,
                GistSort.OLDEST_UPDATED,
            ),
            optionLabel = { stringResource(it.labelRes) },
            onSelected = onSortSelected,
            filterLabel = stringResource(R.string.work_filter_sort),
        )
        FilterCapsuleMenu(
            selected = selectedVisibility,
            options = GistVisibility.entries,
            optionLabel = { stringResource(it.labelRes) },
            onSelected = onVisibilitySelected,
            filterLabel = stringResource(R.string.work_filter_visibility),
            neutralLabel = stringResource(R.string.work_filter_visibility),
            isNeutral = { it == GistVisibility.ALL },
        )
    }
}

@Composable
private fun GistList(
    state: GistUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onGistClick: (GistListItem) -> Unit,
    onForkSourceClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var scrollSort by rememberSaveable { mutableStateOf(state.selectedSort) }
    var scrollVisibility by rememberSaveable { mutableStateOf(state.selectedVisibility) }

    LaunchedEffect(state.selectedSort, state.selectedVisibility) {
        if (scrollSort != state.selectedSort || scrollVisibility != state.selectedVisibility) {
            listState.scrollToItem(0)
        }
        scrollSort = state.selectedSort
        scrollVisibility = state.selectedVisibility
    }

    LaunchedEffect(listState, state.selectedCategory, state.selectedSort, state.selectedVisibility, state.hasNextPage) {
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
                        Text(stringResource(R.string.common_load_failed), style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
                state.items.isEmpty() -> item(key = "empty") {
                    FullHeightMessage {
                        Text(
                            text = stringResource(
                                when {
                                    state.hasNextPage -> R.string.gist_empty_batch
                                    state.selectedVisibility != GistVisibility.ALL -> R.string.gist_empty_filtered
                                    state.selectedCategory == GistCategory.ALL -> R.string.gist_empty_all
                                    state.selectedCategory == GistCategory.ORIGINAL -> R.string.gist_empty_original
                                    else -> R.string.gist_empty_forked
                                },
                            ),
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
                                    Text(stringResource(R.string.gist_continue_search))
                                }
                            }
                        }
                    }
                }
                else -> items(state.items, key = { it.id }) { gist ->
                    GistCard(
                        gist = gist,
                        onClick = { onGistClick(gist) },
                        onForkSourceClick = onForkSourceClick,
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

private val GistCategory.labelRes: Int
    @StringRes get() = when (this) {
        GistCategory.ALL -> R.string.common_all
        GistCategory.ORIGINAL -> R.string.gist_category_original
        GistCategory.FORKED -> R.string.gist_category_forked
    }

private val GistSort.labelRes: Int
    @StringRes get() = when (this) {
        GistSort.RECENTLY_CREATED -> R.string.work_sort_created_desc
        GistSort.RECENTLY_UPDATED -> R.string.work_sort_updated_desc
        GistSort.OLDEST_CREATED -> R.string.work_sort_created_asc
        GistSort.OLDEST_UPDATED -> R.string.work_sort_updated_asc
    }

private val GistVisibility.labelRes: Int
    @StringRes get() = when (this) {
        GistVisibility.ALL -> R.string.common_all
        GistVisibility.PUBLIC -> R.string.work_visibility_public
        GistVisibility.PRIVATE -> R.string.common_private
    }
