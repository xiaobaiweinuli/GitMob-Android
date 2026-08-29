package com.gitmob.app.ui.repoissues

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.gitmob.app.ui.common.FilterCapsuleMenu
import com.gitmob.app.ui.common.FilterMultiCapsuleMenu
import com.gitmob.app.ui.common.IssueStateIcon
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterCapsuleMenu(
                selected = state.filter.state,
                options = RepoIssueStateFilter.entries,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = vm::setState,
                filterLabel = stringResource(R.string.work_filter_state),
                neutralLabel = stringResource(R.string.work_filter_state),
                isNeutral = { it == RepoIssueStateFilter.ALL },
            )
            FilterCapsuleMenu(
                selected = state.filter.sort,
                options = RepoIssueSort.entries,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = vm::setSort,
                filterLabel = stringResource(R.string.work_filter_sort),
            )
            FilterMultiCapsuleMenu(
                selected = state.filter.labels,
                options = state.labels.map(IssueLabel::name),
                emptyLabel = stringResource(R.string.common_all),
                selectedCountRes = R.string.issue_filter_selected_count,
                clearLabel = stringResource(R.string.issue_filter_clear_labels),
                onSelect = vm::setLabels,
                filterLabel = stringResource(R.string.issue_labels),
            )
            FilterCapsuleMenu(
                selected = state.filter.milestone,
                options = milestoneOptions(state),
                optionLabel = { it.label(state.milestones) },
                onSelected = vm::setMilestone,
                filterLabel = stringResource(R.string.issue_milestone),
                neutralLabel = stringResource(R.string.issue_milestone),
                isNeutral = { it == RepoMilestoneFilter.ALL },
            )
            FilterCapsuleMenu(
                selected = state.filter.assignee,
                options = assigneeOptions(state),
                optionLabel = { it.label() },
                onSelected = vm::setAssignee,
                filterLabel = stringResource(R.string.issue_assignees),
                neutralLabel = stringResource(R.string.issue_assignees),
                isNeutral = { it == RepoAssigneeFilter.ALL },
            )
            FilterCapsuleMenu(
                selected = state.filter.author,
                options = authorOptions(state),
                optionLabel = { it.label() },
                onSelected = vm::setAuthor,
                filterLabel = stringResource(R.string.issue_author_filter),
                neutralLabel = stringResource(R.string.issue_author_filter),
                isNeutral = { it == RepoAuthorFilter.ALL },
            )
            FilterCapsuleMenu(
                selected = state.updateWindow(),
                options = UpdatedWindow.entries,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = vm::setUpdatedWindow,
                filterLabel = stringResource(R.string.issue_updated_filter),
                neutralLabel = stringResource(R.string.issue_updated_filter),
                isNeutral = { it == UpdatedWindow.ANY },
            )
            ToggleFilterCapsule(
                label = stringResource(R.string.issue_filter_mentioned),
                active = state.filter.mentioned,
                onClick = { vm.setMentioned(!state.filter.mentioned) },
            )
            ToggleFilterCapsule(
                label = stringResource(R.string.issue_filter_subscribed),
                active = state.filter.subscribed,
                onClick = { vm.setSubscribed(!state.filter.subscribed) },
            )
        }
        Text(
            text = stringResource(R.string.work_items_count, state.totalCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider()
    }
}

/** 更新时间筛选的离散档位：全部 / 最近 7 天 / 最近 30 天（点击时换算成具体 Instant） */
private enum class UpdatedWindow(@StringRes val labelRes: Int, val days: Long?) {
    ANY(R.string.issue_filter_all_updated, null),
    DAYS_7(R.string.issue_filter_updated_7d, 7),
    DAYS_30(R.string.issue_filter_updated_30d, 30),
}

private fun UpdatedWindow.instant(): java.time.Instant? = days?.let { java.time.Instant.now().minusSeconds(it * 24 * 60 * 60) }

/** 把模型里的 updatedSince 还原成分档，供胶囊显示当前选中项 */
private fun RepoIssueListUiState.updateWindow(): UpdatedWindow {
    val since = filter.updatedSince ?: return UpdatedWindow.ANY
    val hours = java.time.Duration.between(since, java.time.Instant.now()).toHours()
    return UpdatedWindow.entries.filter { it.days != null }.minBy { kotlin.math.abs(it.days!! * 24 - hours) }
}

private fun RepoIssueListViewModel.setUpdatedWindow(window: UpdatedWindow) = setUpdatedSince(window.instant())

private fun milestoneOptions(state: RepoIssueListUiState): List<RepoMilestoneFilter> = buildList {
    add(RepoMilestoneFilter.ALL)
    add(RepoMilestoneFilter.NONE)
    state.milestones.forEach { add(RepoMilestoneFilter.Number(it.number)) }
}

private fun assigneeOptions(state: RepoIssueListUiState): List<RepoAssigneeFilter> = buildList {
    add(RepoAssigneeFilter.ALL)
    add(RepoAssigneeFilter.ANY)
    add(RepoAssigneeFilter.NONE)
    state.assignableUsers.forEach { add(RepoAssigneeFilter.Login(it.login)) }
}

private fun authorOptions(state: RepoIssueListUiState): List<RepoAuthorFilter> = buildList {
    add(RepoAuthorFilter.ALL)
    state.assignableUsers.forEach { add(RepoAuthorFilter.Login(it.login)) }
}

@Composable
private fun RepoMilestoneFilter.label(milestones: List<IssueMilestone>): String = when (this) {
    RepoMilestoneFilter.ALL -> stringResource(R.string.issue_filter_all_milestones)
    RepoMilestoneFilter.NONE -> stringResource(R.string.issue_no_milestone)
    is RepoMilestoneFilter.Number -> milestones.firstOrNull { it.number == value }?.title ?: "#$value"
}

@Composable
private fun RepoAssigneeFilter.label(): String = when (this) {
    RepoAssigneeFilter.ALL -> stringResource(R.string.issue_filter_all_assignee_states)
    RepoAssigneeFilter.ANY -> stringResource(R.string.issue_filter_assigned_any)
    RepoAssigneeFilter.NONE -> stringResource(R.string.issue_filter_assigned_none)
    is RepoAssigneeFilter.Login -> "@$value"
}

@Composable
private fun RepoAuthorFilter.label(): String = when (this) {
    RepoAuthorFilter.ALL -> stringResource(R.string.issue_filter_all_authors)
    is RepoAuthorFilter.Login -> value
}

/** 布尔型筛选的切换胶囊：开启=主题容器色+勾，关闭=灰色 */
@Composable
private fun ToggleFilterCapsule(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            if (active) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
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
            IssueStateIcon(
                state = issue.state,
                stateReason = issue.stateReason,
                locked = issue.locked,
                size = 20.dp,
            )
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
