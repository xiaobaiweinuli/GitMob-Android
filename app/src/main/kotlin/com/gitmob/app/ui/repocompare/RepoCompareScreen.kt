package com.gitmob.app.ui.repocompare

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.GitChangedFileRow
import com.gitmob.app.ui.common.GitCommitRow
import com.gitmob.app.ui.common.ListFeedback
import com.gitmob.app.ui.common.ListFeedbackState
import com.gitmob.app.ui.common.RepositoryContextTitle
import com.gitmob.app.ui.common.UnifiedDiffViewer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoCompareScreen(
    owner: String,
    name: String,
    base: String,
    head: String,
    onBack: () -> Unit,
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    onBrowserClick: () -> Unit,
    onCommitClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    viewModel: RepoCompareViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, base, head) { viewModel.init(owner, name, base, head) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val comparison = state.comparison
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var expandedFile by rememberSaveable { mutableStateOf<String?>(null) }
    val filesState = rememberLazyListState()
    val commitsState = rememberLazyListState()
    if (selectedTab == 1 && comparison?.commitsHasNextPage == true && !state.loadMoreFailed) {
        LaunchedEffect(commitsState, comparison.commitsPage) {
            snapshotFlow { commitsState.layoutInfo.visibleItemsInfo.any { it.key == "more" } }
                .distinctUntilChanged().filter { it }.collect { viewModel.loadMore() }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    RepositoryContextTitle(owner, name, stringResource(R.string.compare_title), onOwnerClick, onRepositoryClick)
                },
                navigationIcon = { ActionIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = { ActionIconButton(onClick = onBrowserClick) { Icon(Icons.Default.OpenInBrowser, stringResource(R.string.compare_open_browser)) } },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(stringResource(R.string.compare_endpoints, base, head), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            if (comparison != null) {
                val status = when (comparison.status) {
                    "ahead" -> R.string.compare_status_ahead
                    "behind" -> R.string.compare_status_behind
                    "diverged" -> R.string.compare_status_diverged
                    "identical" -> R.string.compare_status_identical
                    else -> R.string.common_unknown
                }
                Text(stringResource(status), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.compare_counts, comparison.aheadBy, comparison.behindBy),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = {
                        Text(stringResource(R.string.compare_files, comparison.files.size, if (comparison.filesTruncated) "+" else ""))
                    })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = {
                        Text(stringResource(R.string.compare_commits, comparison.totalCommits))
                    })
                }
                Box(Modifier.weight(1f)) {
                    if (selectedTab == 0) {
                        LazyColumn(state = filesState, modifier = Modifier.fillMaxSize(), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
                            if (comparison.filesTruncated) item(key = "limit") {
                                Text(stringResource(R.string.compare_files_limited, comparison.files.size), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(comparison.files, key = { "file:${it.filename}" }) { file ->
                                GitChangedFileRow(file, onClick = { expandedFile = if (expandedFile == file.filename) null else file.filename })
                                if (expandedFile == file.filename) {
                                    if (file.patch.isNullOrBlank()) {
                                        Text(stringResource(R.string.compare_patch_unavailable), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else UnifiedDiffViewer(file.patch, documentId = file.filename)
                                }
                            }
                        }
                        if (comparison.files.isEmpty()) ListFeedback(ListFeedbackState.EMPTY, viewModel::retry)
                    } else {
                        LazyColumn(state = commitsState, modifier = Modifier.fillMaxSize(), contentPadding = WindowInsets.navigationBars.asPaddingValues()) {
                            items(comparison.commits, key = { it.oid }) { commit ->
                                GitCommitRow(commit, onClick = { onCommitClick(commit.oid) }, onAuthorClick = onUserClick)
                            }
                            if (comparison.commitsHasNextPage) item(key = "more") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (state.loadMoreFailed) {
                                        TextButton(onClick = viewModel::retryMore, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                                            Text(stringResource(R.string.common_retry))
                                        }
                                    } else CircularProgressIndicator()
                                }
                            }
                        }
                        if (comparison.commits.isEmpty() && !comparison.commitsHasNextPage) ListFeedback(ListFeedbackState.EMPTY, viewModel::retry)
                    }
                }
            } else {
                ListFeedback(
                    state = when {
                        state.isLoading -> ListFeedbackState.LOADING
                        state.loadFailed -> ListFeedbackState.ERROR
                        else -> ListFeedbackState.EMPTY
                    },
                    onRetry = viewModel::retry,
                    emptyMessage = if (state.noCommonAncestor) stringResource(R.string.compare_no_common_ancestor) else stringResource(R.string.common_empty),
                )
            }
        }
    }
}
