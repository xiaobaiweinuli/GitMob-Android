package com.gitmob.app.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.SearchCategory
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchCategoryScreen(
    query: String,
    category: SearchCategory,
    onBack: () -> Unit,
    actions: SearchResultActions,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    LaunchedEffect(query, category) { viewModel.init(query, category) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val section = state.sections[category] ?: SearchSectionState(loading = true)
    val page = section.page
    val listState = rememberLazyListState()
    var lastAutoRequest by remember { mutableStateOf<String?>(null) }
    val paginationKey = "search-pagination:${state.generation}:${section.completedPages}"
    LaunchedEffect(listState, paginationKey, section.canLoadMore) {
        if (!section.canLoadMore || lastAutoRequest == paginationKey) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == paginationKey } }.first { it }
        lastAutoRequest = paginationKey
        viewModel.loadMore()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(category.titleRes)) },
                actions = {
                    page?.let { Text(java.text.NumberFormat.getIntegerInstance().format(it.totalCount),
                        Modifier.padding(end = 16.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (page == null && section.loading) item(key = "initial-loading") { SearchPlaceholder() }
            items(page?.items.orEmpty(), key = { it.key }) { SearchResult(it, actions) }
            if (page != null && page.items.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.search_empty), Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(key = paginationKey) {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        section.error != null -> SearchFailure(section.error) { viewModel.retry(category) }
                        section.loading && page != null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        page?.limitReached == true -> Text(stringResource(R.string.search_limit, PageSize.SEARCH_ACCESSIBLE_LIMIT), style = MaterialTheme.typography.bodySmall)
                        page != null && page.next == null && page.items.isNotEmpty() -> Text(stringResource(R.string.search_end), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (page?.incomplete == true) Text(stringResource(R.string.search_incomplete), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
