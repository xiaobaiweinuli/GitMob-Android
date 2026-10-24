package com.gitmob.app.ui.repos

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepoFilters
import com.gitmob.app.data.model.RepoSort
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.ui.common.FilterCapsuleMenu
import com.gitmob.app.ui.common.RepoCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 仓库列表页（底部 Tab「仓库」+ 他人主页 → 点击「仓库」统计项共用这一个 Screen）。
 *
 * 通过 [login] 参数区分两种场景：
 *   - `login = null`：底部 Tab「仓库」，查 viewer（当前登录用户）自己拥有的仓库
 *   - 非空 login：该用户或组织拥有且当前账号可访问的仓库
 *
 * Scaffold 只处理 statusBar / horizontal 内边距，底部 NavigationBar 高度由外层
 * NavDisplay 统一处理（Tab 根页）或 Push 路由 Screen 自己处理。
 *
 * @param login 要查看的用户 login；`null` 表示当前登录用户自己（底部 Tab「仓库」）
 * @param onRepoClick 点击某张仓库卡片的回调，传 ownerLogin + repoName 跳仓库详情
 * @param viewModel ReposViewModel（两种模式共用同一个 Hilt ViewModel）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen(
    login: String? = null,
    onRepoClick: (owner: String, name: String) -> Unit = { _, _ -> },
    onForkSourceClick: (owner: String, name: String) -> Unit = { _, _ -> },
    onHomepageClick: (url: String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onCreateRepository: (RepositoryCreateOwner) -> Unit = {},
    viewModel: ReposViewModel = hiltViewModel(),
) {
    LaunchedEffect(login) { viewModel.init(login) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    var searchExpanded by rememberSaveable(login) { mutableStateOf(false) }
    val closeSearch = {
        searchExpanded = false
        focusManager.clearFocus()
        keyboard?.hide()
    }
    var lastAutoRequest by remember { mutableStateOf<String?>(null) }
    val paginationKey = "repos-pagination:${state.requestGeneration}:${state.completedBatches}"

    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(state.scrollToTop, state.requestGeneration) {
        if (state.scrollToTop) {
            listState.scrollToItem(0)
            viewModel.onScrolledToTop(state.requestGeneration)
        }
    }
    LaunchedEffect(listState, paginationKey, state.canAutoLoadMore, state.scrollToTop) {
        if (!state.canAutoLoadMore || state.scrollToTop || lastAutoRequest == paginationKey) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == paginationKey } }.first { it }
        lastAutoRequest = paginationKey
        viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchExpanded) {
                        val searchHint = stringResource(R.string.repos_search_hint)
                        OutlinedTextField(
                            value = state.filters.query,
                            onValueChange = viewModel::updateSearch,
                            placeholder = { Text(searchHint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            singleLine = true,
                            trailingIcon = if (state.filters.query.isNotEmpty()) {
                                {
                                    IconButton(onClick = viewModel::clearSearch) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.repos_clear_search))
                                    }
                                }
                            } else null,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                keyboard?.hide()
                                focusManager.clearFocus()
                            }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocus)
                                .semantics { contentDescription = searchHint },
                        )
                    } else {
                        Text(if (login != null) stringResource(R.string.repos_owner_title, login)
                            else state.ownerContext?.name ?: state.ownerContext?.login ?: stringResource(R.string.common_repository))
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    if (!searchExpanded) {
                        val searching = state.filters.query.isNotBlank()
                        IconButton(
                            onClick = { searchExpanded = true },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (searching) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                contentColor = if (searching) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = if (searching) stringResource(R.string.repos_search_active, state.filters.query)
                                    else stringResource(R.string.repos_search),
                            )
                        }
                    }
                    val owner = state.ownerContext
                    if (owner?.canCreateRepository == true) {
                        IconButton(onClick = { onCreateRepository(owner) }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.repo_create_title))
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .then(if (onBack != null) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) else Modifier),
        ) {
            ReposFilterControls(
                filters = state.filters,
                onVisibility = viewModel::selectVisibility,
                onArchived = viewModel::selectArchived,
                onFork = viewModel::selectFork,
                onIssues = viewModel::selectIssues,
                onSort = viewModel::selectSort,
                onReset = viewModel::reset,
            )
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!state.hasLoaded || (state.repos.isEmpty() && !state.hasNextPage)) {
                        item(key = "repos-status") {
                            Column(
                                modifier = Modifier.fillMaxWidth().fillParentMaxHeight().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            ) {
                                when {
                                    state.loadFailed -> {
                                        Text(stringResource(R.string.common_load_failed), style = MaterialTheme.typography.titleMedium)
                                        Button(onClick = viewModel::retry) { Text(stringResource(R.string.common_retry)) }
                                    }
                                    !state.hasLoaded -> CircularProgressIndicator()
                                    else -> Text(
                                        stringResource(R.string.repos_empty),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    items(state.repos, key = { it.key }) { repo ->
                        RepoCard(
                            repo = repo,
                            onClick = { closeSearch(); onRepoClick(repo.ownerLogin, repo.name) },
                            onForkSourceClick = { owner, name -> closeSearch(); onForkSourceClick(owner, name) },
                            onHomepageClick = onHomepageClick,
                        )
                    }
                    if (state.hasNextPage) {
                        item(key = paginationKey) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (state.repos.isEmpty()) Modifier.fillParentMaxHeight() else Modifier)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            ) {
                                if (state.repos.isEmpty()) {
                                    Text(stringResource(R.string.repos_empty_batch), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                when {
                                    state.isBusy -> CircularProgressIndicator(Modifier.size(20.dp))
                                    state.loadMoreFailed -> {
                                        Text(stringResource(R.string.common_load_failed))
                                        Button(onClick = viewModel::retry) { Text(stringResource(R.string.common_retry)) }
                                    }
                                    state.requiresManualContinue -> {
                                        Button(onClick = viewModel::continueSearch) { Text(stringResource(R.string.repos_continue_search)) }
                                    }
                                    else -> CircularProgressIndicator(Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReposFilterControls(
    filters: RepoFilters,
    onVisibility: (RepoVisibility?) -> Unit,
    onArchived: (Boolean?) -> Unit,
    onFork: (Boolean?) -> Unit,
    onIssues: (Boolean?) -> Unit,
    onSort: (RepoSort) -> Unit,
    onReset: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (filters != RepoFilters(query = filters.query)) {
            val resetDescription = stringResource(R.string.repos_reset_filters)
            Surface(
                onClick = {
                    onReset()
                    scope.launch { scrollState.scrollTo(0) }
                },
                modifier = Modifier.semantics { contentDescription = resetDescription },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    stringResource(R.string.repos_reset),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterCapsuleMenu(
                selected = filters.visibility,
                options = listOf(null, RepoVisibility.PUBLIC, RepoVisibility.PRIVATE, RepoVisibility.INTERNAL),
                optionLabel = { value ->
                    stringResource(when (value) {
                        null -> R.string.common_all
                        RepoVisibility.PUBLIC -> R.string.work_visibility_public
                        RepoVisibility.PRIVATE -> R.string.common_private
                        RepoVisibility.INTERNAL -> R.string.work_visibility_internal
                    })
                },
                onSelected = onVisibility,
                filterLabel = stringResource(R.string.work_filter_visibility),
                neutralLabel = stringResource(R.string.work_filter_visibility),
                isNeutral = { it == null },
            )
            FilterCapsuleMenu(
                selected = filters.isArchived,
                options = listOf(null, false, true),
                optionLabel = { stringResource(when (it) {
                    null -> R.string.common_all
                    false -> R.string.repos_not_archived
                    true -> R.string.common_archived
                }) },
                onSelected = onArchived,
                filterLabel = stringResource(R.string.repos_filter_archived),
                neutralLabel = stringResource(R.string.repos_filter_archived),
                isNeutral = { it == null },
            )
            FilterCapsuleMenu(
                selected = filters.isFork,
                options = listOf(null, false, true),
                optionLabel = { stringResource(when (it) {
                    null -> R.string.common_all
                    false -> R.string.gist_category_original
                    true -> R.string.repo_fork
                }) },
                onSelected = onFork,
                filterLabel = stringResource(R.string.repo_fork),
                neutralLabel = stringResource(R.string.repo_fork),
                isNeutral = { it == null },
            )
            FilterCapsuleMenu(
                selected = filters.hasIssuesEnabled,
                options = listOf(null, true, false),
                optionLabel = { stringResource(when (it) {
                    null -> R.string.common_all
                    true -> R.string.repos_issues_enabled
                    false -> R.string.repos_issues_disabled
                }) },
                onSelected = onIssues,
                filterLabel = stringResource(R.string.common_issues),
                neutralLabel = stringResource(R.string.common_issues),
                isNeutral = { it == null },
            )
            val timeOptions = listOf(
                RepoSort.UPDATED_DESC, RepoSort.CREATED_ASC, RepoSort.CREATED_DESC,
                RepoSort.UPDATED_ASC, RepoSort.PUSHED_ASC, RepoSort.PUSHED_DESC,
            )
            FilterCapsuleMenu(
                selected = filters.sort.takeIf { it in timeOptions },
                options = timeOptions,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = { it?.let(onSort) },
                filterLabel = stringResource(R.string.repos_filter_time),
                neutralLabel = stringResource(R.string.work_sort_updated_desc),
                isNeutral = { it == null },
            )
            val starOptions = listOf(RepoSort.STARS_ASC, RepoSort.STARS_DESC)
            FilterCapsuleMenu(
                selected = filters.sort.takeIf { it in starOptions },
                options = starOptions,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = { it?.let(onSort) },
                filterLabel = stringResource(R.string.nav_tab_stars),
                neutralLabel = stringResource(R.string.nav_tab_stars),
                isNeutral = { it == null },
            )
            val nameOptions = listOf(RepoSort.NAME_DESC, RepoSort.NAME_ASC)
            FilterCapsuleMenu(
                selected = filters.sort.takeIf { it in nameOptions },
                options = nameOptions,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = { it?.let(onSort) },
                filterLabel = stringResource(R.string.repos_filter_name),
                neutralLabel = stringResource(R.string.repos_filter_name),
                isNeutral = { it == null },
            )
        }
    }
}

private val RepoSort?.labelRes: Int
    @StringRes get() = when (this) {
        null -> R.string.repos_sort_inactive
        RepoSort.UPDATED_DESC -> R.string.work_sort_updated_desc
        RepoSort.CREATED_ASC -> R.string.work_sort_created_asc
        RepoSort.CREATED_DESC -> R.string.work_sort_created_desc
        RepoSort.UPDATED_ASC -> R.string.work_sort_updated_asc
        RepoSort.PUSHED_ASC -> R.string.repos_sort_pushed_asc
        RepoSort.PUSHED_DESC -> R.string.repos_sort_pushed_desc
        RepoSort.STARS_ASC -> R.string.repos_sort_stars_asc
        RepoSort.STARS_DESC -> R.string.repos_sort_stars_desc
        RepoSort.NAME_DESC -> R.string.repos_sort_name_desc
        RepoSort.NAME_ASC -> R.string.repos_sort_name_asc
    }
