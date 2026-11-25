package com.gitmob.app.ui.repoissues

import com.gitmob.app.ui.common.ActionButtonContent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.ActionProgressIcon
import com.gitmob.app.ui.common.FilterCapsuleMenu
import com.gitmob.app.ui.common.FilterMultiCapsuleMenu
import com.gitmob.app.ui.common.IssueStateIcon
import com.gitmob.app.ui.common.ListFeedback
import com.gitmob.app.ui.common.ListFeedbackState
import com.gitmob.app.ui.common.RepositoryContextTitle
import com.gitmob.app.ui.common.RepositorySearchButton
import com.gitmob.app.ui.common.RepositorySearchField
import com.gitmob.app.ui.common.SearchActions
import com.gitmob.app.ui.common.SearchTitle
import com.gitmob.app.ui.common.informationItem
import com.gitmob.app.ui.common.pressClickable
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoIssueListScreen(
    owner: String,
    name: String,
    permission: RepoPermission?,
    viewerCanCreateIssues: Boolean?,
    onBack: () -> Unit,
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    onIssueClick: (Int) -> Unit,
    onCreate: (String?) -> Unit,
    viewModel: RepoIssueListViewModel = hiltViewModel(), initialQuery: String = "",
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searchExpanded by rememberSaveable { mutableStateOf(initialQuery.isNotBlank()) }
    var focusSearch by rememberSaveable { mutableStateOf(false) }
    var searchInput by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.submittedQuery)) }
    var draftInitialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(owner, name, initialQuery) {
        viewModel.init(owner, name, permission, viewerCanCreateIssues, initialQuery)
        if (!draftInitialized) {
            searchInput = TextFieldValue(viewModel.state.value.submittedQuery)
            draftInitialized = true
        }
    }
    val listState = rememberSaveable(state.listRevision, saver = LazyListState.Saver) { LazyListState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun leaveList(action: () -> Unit) {
        searchExpanded = false
        keyboard?.hide()
        focusManager.clearFocus()
        action()
    }

    BackHandler(enabled = searchExpanded) { leaveList {} }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    SearchTitle(searchExpanded) { expanded ->
                    if (expanded) {
                        RepositorySearchField(
                            value = searchInput,
                            onValueChange = { searchInput = it },
                            placeholder = stringResource(R.string.repo_issue_search_hint),
                            hasSubmittedQuery = state.submittedQuery.isNotEmpty(),
                            focusOnExpand = focusSearch && searchExpanded,
                            onSubmit = { value ->
                                viewModel.submitSearch(value)
                                searchInput = TextFieldValue(viewModel.state.value.submittedQuery)
                            },
                        )
                    } else RepositoryContextTitle(
                        owner = owner,
                        repository = name,
                        pageTitle = stringResource(R.string.common_issues),
                        onOwnerClick = { login -> leaveList { onOwnerClick(login) } },
                        onRepositoryClick = { repoOwner, repoName -> leaveList { onRepositoryClick(repoOwner, repoName) } },
                    )
                                    }
                },
                navigationIcon = { ActionIconButton(onClick = { if (searchExpanded) leaveList {} else leaveList(onBack) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    SearchActions(visible = !searchExpanded) {
                        RepositorySearchButton(state.submittedQuery.isNotEmpty()) { focusSearch = true; searchExpanded = true }
                    }
                    if (state.viewerCanCreateIssues) {
                        val createDescription = stringResource(R.string.issue_new)
                        ActionIconButton(
                            onClick = { keyboard?.hide(); focusManager.clearFocus(); viewModel.beginCreate() },
                            enabled = !state.templatePickerLoading,
                            modifier = Modifier.semantics { contentDescription = createDescription },
                        ) {
                            ActionProgressIcon(busy = state.templatePickerLoading) { Icon(Icons.Default.Add, contentDescription = null) }
                        }
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
                val feedback = when {
                    state.isLoading && state.items.isEmpty() -> ListFeedbackState.LOADING
                    state.loadFailed && state.items.isEmpty() -> ListFeedbackState.ERROR
                    state.items.isEmpty() && !state.hasNextPage && !state.searchLimitReached -> ListFeedbackState.EMPTY
                    else -> ListFeedbackState.CONTENT
                }
                LazyColumn(
                    Modifier.fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(if (feedback == ListFeedbackState.CONTENT) state.items else emptyList(), key = { it.id }) { issue ->
                        Box(Modifier.animateItem().informationItem()) {
                            val canDelete = state.capabilities.canDeleteIssues && issue.viewerCanDelete
                            IssueSwipeRow(issue, canDelete, { viewModel.confirmDelete(issue) }) { leaveList { onIssueClick(issue.number) } }
                        }
                    }
                    if (feedback == ListFeedbackState.CONTENT && state.hasNextPage) item(key = "more") {
                        LaunchedEffect(state.nextCursor, state.listRevision) { if (!state.loadMoreFailed) viewModel.loadMore() }
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            if (state.loadMoreFailed) TextButton(onClick = viewModel::loadMore, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_retry)) }
                            else CircularProgressIndicator(Modifier.size(20.dp))
                        }
                    }
                    if (state.searchLimitReached) item(key = "search-limit") {
                        Text(pluralStringResource(R.plurals.search_limit, PageSize.SEARCH_ACCESSIBLE_LIMIT, PageSize.SEARCH_ACCESSIBLE_LIMIT),
                            modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                                }
                ListFeedback(feedback, viewModel::retry, emptyMessage = stringResource(R.string.issue_empty_filtered))
            }
        }
    }

    state.pendingDelete?.let { issue ->
        AlertDialog(
            onDismissRequest = { viewModel.confirmDelete(null) },
            title = { Text(stringResource(R.string.issue_delete_title, issue.number)) },
            text = { Text(stringResource(R.string.issue_delete_message)) },
            dismissButton = { TextButton(onClick = { viewModel.confirmDelete(null) }, enabled = !state.isDeleting, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } },
            confirmButton = { TextButton(onClick = viewModel::deletePending, enabled = !state.isDeleting, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = state.isDeleting, label = stringResource(R.string.common_delete)) } },
        )
    }

    LaunchedEffect(state.blankCreateRequested) {
        if (state.blankCreateRequested) {
            viewModel.consumeBlankCreateRequest()
            leaveList { onCreate(null) }
        }
    }
    if (state.templatePickerVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissTemplatePicker,
            title = { Text(stringResource(R.string.issue_template_picker_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.blankIssuesEnabled) {
                        IssueTemplateOption(
                            title = stringResource(R.string.issue_template_blank),
                            onClick = { viewModel.dismissTemplatePicker(); leaveList { onCreate(null) } },
                        )
                    }
                    state.templates.forEach { template ->
                        IssueTemplateOption(
                            title = template.name,
                            description = template.about,
                            onClick = { viewModel.dismissTemplatePicker(); leaveList { onCreate(template.filename) } },
                        )
                    }
                    if (!state.blankIssuesEnabled && state.templates.isEmpty()) Text(stringResource(R.string.issue_template_none_available), color = MaterialTheme.colorScheme.error)
                    if (state.invalidTemplateCount > 0) Text(pluralStringResource(R.plurals.issue_template_invalid_count, state.invalidTemplateCount, state.invalidTemplateCount), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTemplatePicker, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun IssueTemplateOption(title: String, description: String? = null, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(title)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
                filterLabel = stringResource(R.string.repo_filter_any_label),
            )
            FilterCapsuleMenu(
                selected = state.filter.milestone,
                options = milestoneOptions(state),
                optionLabel = { it.label() },
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
                label = stringResource(R.string.issue_filter_mentioned_me),
                active = state.filter.mentionedMe,
                onClick = { vm.setMentionedMe(!state.filter.mentionedMe) },
            )
        }
        Text(
            text = pluralStringResource(R.plurals.work_items_count, state.totalCount, state.totalCount),
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
    val selected = state.filter.milestone as? RepoMilestoneFilter.Title
    if (selected != null && state.milestones.none { it.title == selected.title }) add(selected)
    state.milestones.forEach { add(if (it.title == selected?.title) selected else RepoMilestoneFilter.Number(it.number, it.title)) }
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
private fun RepoMilestoneFilter.label(): String = when (this) {
    RepoMilestoneFilter.ALL -> stringResource(R.string.issue_filter_all_milestones)
    RepoMilestoneFilter.NONE -> stringResource(R.string.issue_no_milestone)
    is RepoMilestoneFilter.Number -> title
    is RepoMilestoneFilter.Title -> title
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
    FilterChip(
        selected = active,
        onClick = onClick,
        shapes = FilterChipDefaults.shapes(),
        label = { Text(label) },
        leadingIcon = if (active) {{ Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }} else null,
    )
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
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).pressClickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

@Composable private fun LabelPill(label: IssueLabel) { val fallback = MaterialTheme.colorScheme.secondaryContainer; val color = remember(label.color, fallback) { runCatching { Color("#${label.color}".toColorInt()) }.getOrDefault(fallback) }; Text(label.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(color.copy(alpha = .22f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) }

@Composable private fun ErrorState(retry: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(retry, modifier = Modifier.padding(top = 12.dp), shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_retry)) } } }

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
