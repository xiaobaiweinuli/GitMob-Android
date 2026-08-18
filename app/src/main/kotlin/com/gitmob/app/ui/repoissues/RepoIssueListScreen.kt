package com.gitmob.app.ui.repoissues

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoIssueListScreen(
    owner: String,
    name: String,
    permission: RepoPermission?,
    viewerCanCreateIssues: Boolean?,
    onBack: () -> Unit,
    onIssueClick: (Int) -> Unit,
    onCreate: (String?) -> Unit,
    viewModel: RepoIssueListViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name, permission, viewerCanCreateIssues) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_issues)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    if (state.viewerCanCreateIssues) {
                        IconButton(onClick = viewModel::beginCreate) { Icon(Icons.Default.Add, stringResource(R.string.issue_new)) }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterControls(state, viewModel)
            PullToRefreshBox(
                isRefreshing = state.isLoading && state.items.isNotEmpty(),
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.isLoading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.loadFailed && state.items.isEmpty() -> ErrorState(viewModel::retry)
                    state.items.isEmpty() -> Text(stringResource(R.string.issue_empty_filtered), modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.items, key = { it.id }) { issue ->
                            val canDelete = state.capabilities.canDeleteIssues && issue.viewerCanDelete
                            IssueSwipeRow(issue, canDelete, { viewModel.confirmDelete(issue) }) { onIssueClick(issue.number) }
                            HorizontalDivider()
                        }
                        if (state.hasNextPage) item(key = "more") {
                            LaunchedEffect(state.items.size) { viewModel.loadMore() }
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
                        }
                        item(key = "bottom") { Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)) }
                    }
                }
            }
        }
    }

    state.pendingDelete?.let { issue ->
        AlertDialog(
            onDismissRequest = { viewModel.confirmDelete(null) },
            title = { Text(stringResource(R.string.issue_delete_title, issue.number)) },
            text = { Text(stringResource(R.string.issue_delete_message)) },
            dismissButton = { TextButton(onClick = { viewModel.confirmDelete(null) }) { Text(stringResource(R.string.common_cancel)) } },
            confirmButton = { TextButton(onClick = viewModel::deletePending, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_delete)) } },
        )
    }

    LaunchedEffect(state.blankCreateRequested) {
        if (state.blankCreateRequested) {
            viewModel.consumeBlankCreateRequest()
            onCreate(null)
        }
    }
    if (state.templatePickerLoading || state.templatePickerVisible || state.templateLoadFailed) {
        AlertDialog(
            onDismissRequest = { if (!state.templatePickerLoading) viewModel.dismissTemplatePicker() },
            title = { Text(stringResource(R.string.issue_template_picker_title)) },
            text = {
                when {
                    state.templatePickerLoading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.templateLoadFailed -> Text(stringResource(R.string.common_load_failed))
                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.blankIssuesEnabled) {
                            TextButton(onClick = { viewModel.dismissTemplatePicker(); onCreate(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.issue_template_blank)) }
                        }
                        state.templates.forEach { template ->
                            TextButton(onClick = { viewModel.dismissTemplatePicker(); onCreate(template.filename) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                    Text(template.name)
                                    template.about?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                        if (!state.blankIssuesEnabled && state.templates.isEmpty()) Text(stringResource(R.string.issue_template_none_available), color = MaterialTheme.colorScheme.error)
                        if (state.invalidTemplateCount > 0) Text(pluralStringResource(R.plurals.issue_template_invalid_count, state.invalidTemplateCount, state.invalidTemplateCount), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                if (state.templateLoadFailed) TextButton(onClick = viewModel::beginCreate) { Text(stringResource(R.string.common_retry)) }
                else if (!state.templatePickerLoading) TextButton(onClick = viewModel::dismissTemplatePicker) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

}

@Composable
private fun FilterControls(state: RepoIssueListUiState, vm: RepoIssueListViewModel) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            FilterMenu(R.string.work_filter_state, state.filter.state, RepoIssueStateFilter.entries, { it.labelRes }, vm::setState, Modifier.weight(1f))
            FilterMenu(R.string.work_filter_sort, state.filter.sort, RepoIssueSort.entries, { it.labelRes }, vm::setSort, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            MultiLabelMenu(state.labels, state.filter.labels, vm::setLabels, Modifier.weight(1f))
            MoreFilterMenu(state, vm, Modifier.weight(1f))
        }
        Text(stringResource(R.string.work_items_count, state.totalCount), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        HorizontalDivider()
    }
}

@Composable
private fun <T> FilterMenu(@StringRes label: Int, selected: T, options: List<T>, optionLabel: (T) -> Int, onSelected: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(stringResource(optionLabel(selected)), maxLines = 1) }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { item -> DropdownMenuItem(text = { Text(stringResource(optionLabel(item))) }, onClick = { expanded = false; onSelected(item) }, leadingIcon = { if (item == selected) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) }) } }
    }
}

@Composable
private fun MultiLabelMenu(labels: List<IssueLabel>, selected: Set<String>, onSelected: (Set<String>) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(stringResource(R.string.issue_labels), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(if (selected.isEmpty()) stringResource(R.string.common_all) else stringResource(R.string.issue_filter_selected_count, selected.size), maxLines = 1) }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.issue_filter_clear_labels)) }, onClick = { onSelected(emptySet()); expanded = false })
            labels.forEach { label -> DropdownMenuItem(text = { Text(label.name) }, onClick = { onSelected(if (label.name in selected) selected - label.name else selected + label.name) }, leadingIcon = { Checkbox(label.name in selected, null) }) }
        }
    }
}

@Composable
private fun MoreFilterMenu(state: RepoIssueListUiState, vm: RepoIssueListViewModel, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(stringResource(R.string.issue_filter_more), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(stringResource(R.string.issue_filter_more_desc), maxLines = 1) }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.issue_filter_all_milestones)) }, onClick = { vm.setMilestone(RepoMilestoneFilter.ALL); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.issue_no_milestone)) }, onClick = { vm.setMilestone(RepoMilestoneFilter.NONE); expanded = false })
            state.milestones.forEach { m -> DropdownMenuItem(text = { Text(m.title) }, onClick = { vm.setMilestone(RepoMilestoneFilter.Number(m.number)); expanded = false }) }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.issue_filter_all_assignee_states)) }, onClick = { vm.setAssignee(RepoAssigneeFilter.ALL); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.issue_filter_assigned_any)) }, onClick = { vm.setAssignee(RepoAssigneeFilter.ANY); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.issue_filter_assigned_none)) }, onClick = { vm.setAssignee(RepoAssigneeFilter.NONE); expanded = false })
            state.assignableUsers.forEach { user -> DropdownMenuItem(text = { Text(user.login) }, onClick = { vm.setAssignee(RepoAssigneeFilter.Login(user.login)); expanded = false }) }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.issue_filter_all_authors)) }, onClick = { vm.setAuthor(RepoAuthorFilter.ALL); expanded = false })
            state.assignableUsers.forEach { user -> DropdownMenuItem(text = { Text(stringResource(R.string.issue_filter_author_login, user.login)) }, onClick = { vm.setAuthor(RepoAuthorFilter.Login(user.login)); expanded = false }) }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(if (state.filter.mentioned) R.string.issue_filter_mentioned_off else R.string.issue_filter_mentioned)) }, onClick = { vm.setMentioned(!state.filter.mentioned); expanded = false }, leadingIcon = { Checkbox(state.filter.mentioned, null) })
            DropdownMenuItem(text = { Text(stringResource(if (state.filter.subscribed) R.string.issue_filter_subscribed_off else R.string.issue_filter_subscribed)) }, onClick = { vm.setSubscribed(!state.filter.subscribed); expanded = false }, leadingIcon = { Checkbox(state.filter.subscribed, null) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.issue_filter_all_updated)) }, onClick = { vm.setUpdatedSince(null); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.issue_filter_updated_7d)) }, onClick = { vm.setUpdatedSince(java.time.Instant.now().minusSeconds(7L * 24 * 60 * 60)); expanded = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.issue_filter_updated_30d)) }, onClick = { vm.setUpdatedSince(java.time.Instant.now().minusSeconds(30L * 24 * 60 * 60)); expanded = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IssueSwipeRow(issue: RepoIssue, canDelete: Boolean, onDelete: () -> Unit, onClick: () -> Unit) {
    // confirmValueChange 已弃用：改用 M3 1.4 的 onDismiss 回调触发删除确认，
    // reset() 把行弹回原位（真正的删除要等对话框二次确认）。
    val scope = rememberCoroutineScope()
    val dismissState = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = canDelete,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && canDelete) onDelete()
            scope.launch { dismissState.reset() }
        },
        backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Default.Delete, stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.onError) } },
    ) { IssueRow(issue, onClick) }
}

@Composable
private fun IssueRow(issue: RepoIssue, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(if (issue.state == IssueState.OPEN) Icons.Default.RadioButtonChecked else Icons.Default.CheckCircle, null, tint = if (issue.state == IssueState.OPEN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(issue.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("#${issue.number} · ${issue.author?.login ?: "ghost"} · ${issue.updatedAt.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (issue.commentCount > 0) Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(issue.commentCount.toString(), style = MaterialTheme.typography.labelMedium) }
        }
        if (issue.labels.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { issue.labels.take(4).forEach { label -> LabelPill(label) } }
        val meta = listOfNotNull(issue.milestone?.let { stringResource(R.string.issue_meta_milestone, it.title) }, issue.assignees.takeIf { it.isNotEmpty() }?.joinToString { "@${it.login}" }, if (issue.locked) stringResource(R.string.state_locked) else null).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun LabelPill(label: IssueLabel) { val fallback = MaterialTheme.colorScheme.secondaryContainer; val color = remember(label.color, fallback) { runCatching { Color(android.graphics.Color.parseColor("#${label.color}")) }.getOrDefault(fallback) }; Text(label.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(color.copy(alpha = .22f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) }

@Composable private fun ErrorState(retry: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(retry, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.common_retry)) } } }

private val RepoIssueStateFilter.labelRes: Int
    @StringRes get() = when (this) {
        RepoIssueStateFilter.OPEN -> R.string.work_filter_open
        RepoIssueStateFilter.CLOSED -> R.string.common_state_closed
        RepoIssueStateFilter.ALL -> R.string.common_all
    }

private val RepoIssueSort.labelRes: Int
    @StringRes get() = when (this) {
        RepoIssueSort.UPDATED_DESC -> R.string.work_sort_updated_desc
        RepoIssueSort.UPDATED_ASC -> R.string.work_sort_updated_asc
        RepoIssueSort.CREATED_DESC -> R.string.work_sort_created_desc
        RepoIssueSort.CREATED_ASC -> R.string.work_sort_created_asc
        RepoIssueSort.COMMENTS_DESC -> R.string.work_sort_comments_desc
        RepoIssueSort.COMMENTS_ASC -> R.string.work_sort_comments_asc
    }
