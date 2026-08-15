package com.gitmob.app.ui.work

import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.WorkIssueItem
import com.gitmob.app.data.model.UserIssueRelationFilter
import com.gitmob.app.data.model.UserIssueSortFilter
import com.gitmob.app.data.model.UserIssueStateFilter
import com.gitmob.app.data.model.UserIssueVisibilityFilter
import com.gitmob.app.ui.common.GitHubStateChip
import com.gitmob.app.ui.common.IssueStateIcon
import com.gitmob.app.ui.common.issueStateVisual

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkIssueListScreen(
    onBack: () -> Unit,
    onItemClick: (owner: String, name: String, number: Int) -> Unit,
    viewModel: WorkIssueListViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_issues)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            IssueFilterControls(
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
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.isLoading && state.items.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.loadFailed && state.items.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(R.string.common_load_failed))
                        Button(onClick = viewModel::retry, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.common_retry)) }
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.items) { item ->
                            WorkIssueRow(item, onClick = { onItemClick(item.repoOwner, item.repoName, item.number) })
                        }
                        if (state.hasNextPage) {
                            item {
                                LaunchedEffect(state.items.size) { viewModel.loadMore() }
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
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

@Composable
private fun IssueFilterControls(
    state: UserIssueStateFilter,
    relation: UserIssueRelationFilter,
    visibility: UserIssueVisibilityFilter,
    sort: UserIssueSortFilter,
    totalCount: Int,
    onStateSelected: (UserIssueStateFilter) -> Unit,
    onRelationSelected: (UserIssueRelationFilter) -> Unit,
    onVisibilitySelected: (UserIssueVisibilityFilter) -> Unit,
    onSortSelected: (UserIssueSortFilter) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            IssueFilterMenu(
                label = R.string.work_filter_state,
                selected = state,
                options = UserIssueStateFilter.entries,
                optionLabel = { it.labelRes },
                onSelected = onStateSelected,
                modifier = Modifier.weight(1f),
            )
            IssueFilterMenu(
                label = R.string.work_filter_relation,
                selected = relation,
                options = UserIssueRelationFilter.entries,
                optionLabel = { it.labelRes },
                onSelected = onRelationSelected,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            IssueFilterMenu(
                label = R.string.work_filter_visibility,
                selected = visibility,
                options = UserIssueVisibilityFilter.entries,
                optionLabel = { it.labelRes },
                onSelected = onVisibilitySelected,
                modifier = Modifier.weight(1f),
            )
            IssueFilterMenu(
                label = R.string.work_filter_sort,
                selected = sort,
                options = UserIssueSortFilter.entries,
                optionLabel = { it.labelRes },
                onSelected = onSortSelected,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(R.string.work_items_count, totalCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider()
    }
}

@Composable
private fun <T> IssueFilterMenu(
    @StringRes label: Int,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> Int,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val labelText = stringResource(label)
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(labelText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(optionLabel(selected)), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.work_select_filter, labelText))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(optionLabel(option))) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                    leadingIcon = {
                        if (option == selected) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}

@get:StringRes
private val UserIssueStateFilter.labelRes: Int
    get() = when (this) {
        UserIssueStateFilter.OPEN -> R.string.work_filter_open
        UserIssueStateFilter.CLOSED -> R.string.common_state_closed
        UserIssueStateFilter.ALL -> R.string.common_all
    }

@get:StringRes
private val UserIssueRelationFilter.labelRes: Int
    get() = when (this) {
        UserIssueRelationFilter.INVOLVED -> R.string.work_relation_involved
        UserIssueRelationFilter.AUTHORED -> R.string.work_relation_authored
        UserIssueRelationFilter.ASSIGNED -> R.string.work_relation_assigned
        UserIssueRelationFilter.MENTIONED -> R.string.work_relation_mentioned
        UserIssueRelationFilter.COMMENTED -> R.string.work_relation_commented
    }

@get:StringRes
private val UserIssueVisibilityFilter.labelRes: Int
    get() = when (this) {
        UserIssueVisibilityFilter.ALL -> R.string.common_all
        UserIssueVisibilityFilter.PUBLIC -> R.string.work_visibility_public
        UserIssueVisibilityFilter.PRIVATE -> R.string.common_private
        UserIssueVisibilityFilter.INTERNAL -> R.string.work_visibility_internal
    }

@get:StringRes
private val UserIssueSortFilter.labelRes: Int
    get() = when (this) {
        UserIssueSortFilter.CREATED_DESC -> R.string.work_sort_created_desc
        UserIssueSortFilter.CREATED_ASC -> R.string.work_sort_created_asc
        UserIssueSortFilter.COMMENTS_DESC -> R.string.work_sort_comments_desc
        UserIssueSortFilter.COMMENTS_ASC -> R.string.work_sort_comments_asc
        UserIssueSortFilter.UPDATED_DESC -> R.string.work_sort_updated_desc
        UserIssueSortFilter.UPDATED_ASC -> R.string.work_sort_updated_asc
    }

@Composable
private fun WorkIssueRow(item: WorkIssueItem, onClick: () -> Unit) {
    val visual = issueStateVisual(
        state = checkNotNull(item.issueState),
        stateReason = item.issueStateReason,
        locked = item.locked,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IssueStateIcon(
            state = checkNotNull(item.issueState),
            stateReason = item.issueStateReason,
            locked = item.locked,
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
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
