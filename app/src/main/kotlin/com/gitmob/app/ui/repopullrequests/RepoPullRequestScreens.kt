package com.gitmob.app.ui.repopullrequests

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.FilePresent
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.PullRequestCreationPolicy
import com.gitmob.app.data.model.RepoPullRequest
import com.gitmob.app.data.model.RepoPullRequestComment
import com.gitmob.app.data.model.RepoPullRequestCreateMetadata
import com.gitmob.app.data.model.RepoPullRequestMergeMethod
import com.gitmob.app.data.model.RepoPullRequestReviewEvent
import com.gitmob.app.data.model.RepoPullRequestSort
import com.gitmob.app.data.model.RepoPullRequestState
import com.gitmob.app.data.model.RepoPullRequestStateFilter
import com.gitmob.app.ui.common.MarkdownWebView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoPullRequestListScreen(
    owner: String,
    name: String,
    permission: RepoPermission?,
    onBack: () -> Unit,
    onPullRequestClick: (Int) -> Unit,
    onCreate: () -> Unit,
    viewModel: RepoPullRequestListViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canCreate = state.hasPullRequestsEnabled &&
        (state.creationPolicy == PullRequestCreationPolicy.ALL || state.capabilities.canPush)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_pull_requests)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = { if (canCreate) IconButton(onClick = onCreate) { Icon(Icons.Default.Add, stringResource(R.string.pr_new)) } },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PullRequestFilters(state, viewModel)
            PullToRefreshBox(
                isRefreshing = state.isLoading && state.items.isNotEmpty(),
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.isLoading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.loadFailed && state.items.isEmpty() -> RetryContent(viewModel::retry)
                    !state.hasPullRequestsEnabled -> Text(stringResource(R.string.pr_disabled), modifier = Modifier.align(Alignment.Center))
                    state.items.isEmpty() -> Text(stringResource(R.string.pr_empty), modifier = Modifier.align(Alignment.Center))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.items, key = { it.id }) { pullRequest ->
                            PullRequestRow(pullRequest) { onPullRequestClick(pullRequest.number) }
                            HorizontalDivider()
                        }
                        if (state.hasNextPage) item("more") {
                            LaunchedEffect(state.items.size) { viewModel.loadMore() }
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
                        }
                        item("bottom") { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PullRequestFilters(state: RepoPullRequestListUiState, viewModel: RepoPullRequestListViewModel) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            PullRequestFilterMenu(R.string.work_filter_state, state.filter.state, RepoPullRequestStateFilter.entries, { it.label }, viewModel::setState, Modifier.weight(1f))
            PullRequestFilterMenu(R.string.work_filter_sort, state.filter.sort, RepoPullRequestSort.entries, { it.label }, viewModel::setSort, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            RefFilter(R.string.pr_base_branch, state.filter.baseRefName, viewModel::setBase, Modifier.weight(1f))
            RefFilter(R.string.pr_head_branch, state.filter.headRefName, viewModel::setHead, Modifier.weight(1f))
        }
        Text(stringResource(R.string.work_items_count, state.totalCount), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        HorizontalDivider()
    }
}

@Composable
private fun <T> PullRequestFilterMenu(@StringRes title: Int, selected: T, values: List<T>, label: (T) -> Int, onSelect: (T) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(stringResource(title), style = MaterialTheme.typography.labelSmall); Text(stringResource(label(selected)), maxLines = 1) }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) { values.forEach { value -> DropdownMenuItem(text = { Text(stringResource(label(value))) }, leadingIcon = { if (value == selected) Icon(Icons.Default.Check, null) }, onClick = { open = false; onSelect(value) }) } }
    }
}

@Composable
private fun RefFilter(@StringRes title: Int, value: String?, onChange: (String?) -> Unit, modifier: Modifier) {
    var text by remember(value) { mutableStateOf(value.orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { next -> text = next; onChange(next.trim().ifBlank { null }) },
        label = { Text(stringResource(title)) },
        placeholder = { Text(stringResource(R.string.common_all)) },
        singleLine = true,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun PullRequestRow(pullRequest: RepoPullRequest, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                when {
                    pullRequest.state == RepoPullRequestState.MERGED -> Icons.Default.Merge
                    pullRequest.state == RepoPullRequestState.CLOSED -> Icons.Default.CheckCircle
                    pullRequest.isDraft -> Icons.Default.Drafts
                    else -> Icons.AutoMirrored.Filled.CallSplit
                },
                null,
                tint = if (pullRequest.state == RepoPullRequestState.OPEN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(pullRequest.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.pr_row_meta, pullRequest.number, pullRequest.author?.login ?: stringResource(R.string.common_deleted_user), pullRequest.updatedAt.take(10)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${pullRequest.headRefName} -> ${pullRequest.baseRefName}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (pullRequest.commentCount > 0) Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Filled.Comment, null, Modifier.size(16.dp)); Text(pullRequest.commentCount.toString()) }
        }
        if (pullRequest.labels.isNotEmpty()) Text(pullRequest.labels.joinToString(" · ") { it.name }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoPullRequestDetailScreen(
    owner: String,
    name: String,
    number: Int,
    permission: RepoPermission?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    viewModel: RepoPullRequestDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, number) { viewModel.init(owner, name, number, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var comment by remember { mutableStateOf("") }
    var reviewOpen by remember { mutableStateOf(false) }
    var mergeOpen by remember { mutableStateOf(false) }
    var editingComment by remember { mutableStateOf<RepoPullRequestComment?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pr_number, number)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    state.pullRequest?.let { pullRequest ->
                        if (pullRequest.viewerCanSubscribe) IconButton(onClick = viewModel::toggleSubscription) { Icon(if (pullRequest.viewerSubscription == "SUBSCRIBED") Icons.Default.NotificationsActive else Icons.Default.NotificationsNone, stringResource(R.string.issue_subscribe)) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.issue_more)) }
                            DropdownMenu(menu, { menu = false }) {
                                if (pullRequest.viewerCanUpdate) DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menu = false; onEdit() })
                                if (pullRequest.state == RepoPullRequestState.OPEN && pullRequest.viewerCanClose) DropdownMenuItem(text = { Text(stringResource(R.string.pr_close)) }, onClick = { menu = false; viewModel.close() })
                                if (pullRequest.state == RepoPullRequestState.CLOSED && pullRequest.viewerCanReopen) DropdownMenuItem(text = { Text(stringResource(R.string.pr_reopen)) }, onClick = { menu = false; viewModel.reopen() })
                                if (pullRequest.state == RepoPullRequestState.OPEN && pullRequest.viewerCanUpdate) DropdownMenuItem(text = { Text(stringResource(if (pullRequest.isDraft) R.string.pr_mark_ready else R.string.pr_convert_draft)) }, onClick = { menu = false; viewModel.toggleDraft() })
                                if (pullRequest.viewerCanUpdateBranch) DropdownMenuItem(text = { Text(stringResource(R.string.pr_update_branch)) }, onClick = { menu = false; viewModel.updateBranch() })
                                if (state.capabilities.canPush && pullRequest.state == RepoPullRequestState.OPEN) DropdownMenuItem(text = { Text(stringResource(R.string.pr_merge)) }, onClick = { menu = false; mergeOpen = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.pr_review)) }, onClick = { menu = false; reviewOpen = true })
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        bottomBar = {
            if (state.pullRequest != null && tab == 0) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(comment, { comment = it }, label = { Text(stringResource(R.string.issue_comment_add)) }, minLines = 1, maxLines = 5, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.addComment(comment) { comment = "" } }, enabled = comment.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.issue_comment_submit)) }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        when {
            state.isLoading && state.pullRequest == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Button(onClick = viewModel::retry) { Text(stringResource(R.string.common_retry)) } }
            state.pullRequest != null -> Column(Modifier.fillMaxSize().padding(padding)) {
                PullRequestHeader(state.pullRequest!!)
                SecondaryTabRow(selectedTabIndex = tab) {
                    listOf(R.string.pr_tab_conversation, R.string.pr_tab_files, R.string.pr_tab_commits, R.string.pr_tab_reviews).forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) })
                    }
                }
                when (tab) {
                    0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                        state.pullRequest!!.bodyHtml.takeIf(String::isNotBlank)?.let { item { MarkdownWebView(it, Modifier.fillMaxWidth()) } }
                        items(state.comments, key = { it.id }) { item -> PullRequestCommentRow(item, onEdit = { editingComment = item }, onDelete = { viewModel.confirmDeleteComment(item) }) }
                        if (state.hasMoreComments) item { LaunchedEffect(state.comments.size) { viewModel.loadMoreComments() } }
                    }
                    1 -> LazyColumn { items(state.files, key = { it.path }) { file -> Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(file.path, fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.pr_file_stats, file.additions, file.deletions)); file.patch?.let { Text(it, style = MaterialTheme.typography.bodySmall) }; HorizontalDivider(Modifier.padding(top = 12.dp)) } } }
                    2 -> LazyColumn { items(state.commits, key = { it.oid }) { commitItem -> Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(commitItem.headline, fontWeight = FontWeight.SemiBold); Text("${commitItem.oid.take(7)} · ${commitItem.authorLogin ?: stringResource(R.string.common_deleted_user)} · ${commitItem.committedAt.take(10)}", style = MaterialTheme.typography.bodySmall); HorizontalDivider(Modifier.padding(top = 12.dp)) } } }
                    else -> LazyColumn { items(state.reviews, key = { it.id }) { review -> Column(Modifier.fillMaxWidth().padding(16.dp)) { Text("${review.author?.login ?: stringResource(R.string.common_deleted_user)} · ${pullRequestReviewStateLabel(review.state)}", fontWeight = FontWeight.SemiBold); if (review.bodyHtml.isNotBlank()) MarkdownWebView(review.bodyHtml); HorizontalDivider() } }; items(state.threads, key = { it.id }) { thread -> Column(Modifier.fillMaxWidth().padding(16.dp)) { Text("${thread.path}:${thread.line ?: 0}", fontWeight = FontWeight.SemiBold); thread.comments.forEach { Text("${it.author?.login ?: stringResource(R.string.common_deleted_user)}: ${it.body}") }; if (thread.viewerCanResolve || thread.viewerCanUnresolve) TextButton(onClick = { viewModel.toggleThreadResolved(thread) }) { Text(stringResource(if (thread.isResolved) R.string.pr_unresolve else R.string.pr_resolve)) }; HorizontalDivider() } } }
                }
            }
        }
    }

    if (reviewOpen) ReviewDialog({ reviewOpen = false }) { event, body -> viewModel.submitReview(event, body) { reviewOpen = false } }
    if (mergeOpen) MergeDialog(state.allowedMergeMethods, state.pullRequest?.autoMergeEnabled == true, { mergeOpen = false }, viewModel::merge, viewModel::toggleAutoMerge)
    editingComment?.let { item -> TextEditDialog(R.string.issue_edit_comment, item.body, { editingComment = null }) { body -> viewModel.updateComment(item, body) { editingComment = null } } }
    state.pendingDeleteComment?.let { AlertDialog(onDismissRequest = { viewModel.confirmDeleteComment(null) }, title = { Text(stringResource(R.string.issue_delete_comment_title)) }, text = { Text(stringResource(R.string.common_cannot_be_undone)) }, dismissButton = { TextButton(onClick = { viewModel.confirmDeleteComment(null) }) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(onClick = viewModel::deletePendingComment) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } }) }
}

@Composable
private fun PullRequestHeader(pullRequest: RepoPullRequest) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(pullRequest.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.pr_author_meta, pullRequest.author?.login ?: stringResource(R.string.common_deleted_user), pullRequest.createdAt.take(10)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(stringResource(pullRequest.state.label)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallSplit, null, Modifier.size(16.dp)) })
            if (pullRequest.isDraft) AssistChip(onClick = {}, label = { Text(stringResource(R.string.pr_draft)) }, leadingIcon = { Icon(Icons.Default.Drafts, null, Modifier.size(16.dp)) })
            pullRequest.reviewDecision?.let { AssistChip(onClick = {}, label = { Text(pullRequestReviewStateLabel(it)) }) }
        }
        Text("${pullRequest.headRepositoryNameWithOwner.orEmpty()}:${pullRequest.headRefName} -> ${pullRequest.baseRefName}", style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.pr_diff_stats, pullRequest.additions, pullRequest.deletions, pullRequest.changedFiles), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PullRequestCommentRow(comment: RepoPullRequestComment, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(comment.author?.login ?: stringResource(R.string.common_deleted_user), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (comment.viewerCanUpdate || comment.viewerCanDelete) Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.issue_more)) }; DropdownMenu(menu, { menu = false }) { if (comment.viewerCanUpdate) DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menu = false; onEdit() }); if (comment.viewerCanDelete) DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { menu = false; onDelete() }) } }
        }
        MarkdownWebView(comment.bodyHtml)
        HorizontalDivider()
    }
}

@Composable
private fun ReviewDialog(onDismiss: () -> Unit, onSubmit: (RepoPullRequestReviewEvent, String) -> Unit) {
    var body by remember { mutableStateOf("") }
    var event by remember { mutableStateOf(RepoPullRequestReviewEvent.COMMENT) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.pr_review)) }, text = { Column { RepoPullRequestReviewEvent.entries.forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(event == item, { event = item }); Text(stringResource(item.label)) } }; OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.issue_editor_body_label)) }, minLines = 4) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { Button(onClick = { onSubmit(event, body) }) { Text(stringResource(R.string.pr_submit_review)) } })
}

@Composable
private fun MergeDialog(methods: Set<RepoPullRequestMergeMethod>, autoMergeEnabled: Boolean, onDismiss: () -> Unit, onMerge: (RepoPullRequestMergeMethod, String?, String?, () -> Unit) -> Unit, onAutoMerge: (RepoPullRequestMergeMethod) -> Unit) {
    var method by remember(methods) { mutableStateOf(methods.firstOrNull() ?: RepoPullRequestMergeMethod.MERGE) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.pr_merge)) }, text = { Column { methods.forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(method == item, { method = item }); Text(stringResource(item.label)) } }; OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.pr_commit_title)) }); OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.pr_commit_message)) }, minLines = 3); TextButton(onClick = { onAutoMerge(method); onDismiss() }) { Text(stringResource(if (autoMergeEnabled) R.string.pr_disable_auto_merge else R.string.pr_enable_auto_merge)) } } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { Button(onClick = { onMerge(method, title, body, onDismiss) }) { Text(stringResource(R.string.pr_merge)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoPullRequestEditorScreen(
    owner: String,
    name: String,
    number: Int?,
    onBack: () -> Unit,
    onSaved: (Int) -> Unit,
    viewModel: RepoPullRequestEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, number) { viewModel.init(owner, name, number) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(if (number == null) R.string.pr_new else R.string.pr_edit)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) }, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> RetryContent(viewModel::load)
            state.metadata != null -> PullRequestEditor(state.metadata!!, state.existing, state.isSaving, Modifier.padding(padding), onBack) { title, body, base, repo, head, draft, labels, assignees, milestone, reviewers -> viewModel.save(title, body, base, repo, head, draft, labels, assignees, milestone, reviewers) { onSaved(it.number) } }
        }
    }
}

@Composable
private fun PullRequestEditor(metadata: RepoPullRequestCreateMetadata, existing: RepoPullRequest?, saving: Boolean, modifier: Modifier, onCancel: () -> Unit, onSave: (String, String, String, String, String, Boolean, List<String>, List<String>, String?, List<String>) -> Unit) {
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember(existing) { mutableStateOf(existing?.body.orEmpty()) }
    var base by remember(existing, metadata) { mutableStateOf(existing?.baseRefName ?: metadata.defaultBranchName.orEmpty()) }
    var headRepo by remember(metadata) { mutableStateOf(metadata.repositories.firstOrNull()?.id.orEmpty()) }
    var head by remember(existing) { mutableStateOf(existing?.headRefName.orEmpty()) }
    var draft by remember(existing) { mutableStateOf(existing?.isDraft ?: false) }
    var selectedLabels by remember(existing) { mutableStateOf(existing?.labels.orEmpty().map { it.id }.toSet()) }
    var selectedAssignees by remember(existing) { mutableStateOf(existing?.assignees.orEmpty().mapNotNull { it.id }.toSet()) }
    var selectedMilestone by remember(existing) { mutableStateOf(existing?.milestone?.id) }
    var selectedReviewers by remember { mutableStateOf(emptySet<String>()) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.issue_editor_title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.issue_editor_body_label)) }, minLines = 8, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(base, { base = it }, label = { Text(stringResource(R.string.pr_base_branch)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Text(stringResource(R.string.pr_head_repository), fontWeight = FontWeight.SemiBold) }
        items(metadata.repositories, key = { it.id }) { repo -> Row(Modifier.fillMaxWidth().clickable { headRepo = repo.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(headRepo == repo.id, { headRepo = repo.id }); Text("${repo.owner}/${repo.name}") } }
        item { OutlinedTextField(head, { head = it }, label = { Text(stringResource(R.string.pr_head_branch)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (existing == null) item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft, { draft = it }); Text(stringResource(R.string.pr_create_draft)) } }
        if (metadata.labels.isNotEmpty()) { item { Text(stringResource(R.string.issue_labels), fontWeight = FontWeight.SemiBold) }; items(metadata.labels, key = { "label-${it.id}" }) { label -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(label.id in selectedLabels, { checked -> selectedLabels = if (checked) selectedLabels + label.id else selectedLabels - label.id }); Text(label.name) } } }
        item { Text(stringResource(R.string.issue_milestone), fontWeight = FontWeight.SemiBold) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedMilestone == null, { selectedMilestone = null }); Text(stringResource(R.string.issue_no_milestone)) } }
        items(metadata.milestones, key = { "milestone-${it.id}" }) { milestone -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedMilestone == milestone.id, { selectedMilestone = milestone.id }); Text(milestone.title) } }
        if (metadata.assignees.isNotEmpty()) { item { Text(stringResource(R.string.issue_assignees), fontWeight = FontWeight.SemiBold) }; items(metadata.assignees, key = { "assignee-${it.login}" }) { user -> user.id?.let { id -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(id in selectedAssignees, { checked -> selectedAssignees = if (checked) selectedAssignees + id else selectedAssignees - id }); Text(user.login) } } } }
        if (metadata.reviewers.isNotEmpty()) { item { Text(stringResource(R.string.pr_reviewers), fontWeight = FontWeight.SemiBold) }; items(metadata.reviewers, key = { "reviewer-${it.login}" }) { user -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(user.login in selectedReviewers, { checked -> selectedReviewers = if (checked) selectedReviewers + user.login else selectedReviewers - user.login }); Text(user.login) } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }; Button(onClick = { onSave(title, body, base, headRepo, head, draft, selectedLabels.toList(), selectedAssignees.toList(), selectedMilestone, selectedReviewers.toList()) }, enabled = title.isNotBlank() && base.isNotBlank() && head.isNotBlank() && !saving) { Text(stringResource(R.string.common_save)) } } }
    }
}

@Composable
private fun TextEditDialog(@StringRes title: Int, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) { var text by remember(initial) { mutableStateOf(initial) }; AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) }, text = { OutlinedTextField(text, { text = it }, minLines = 5, modifier = Modifier.fillMaxWidth()) }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { Button(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_save)) } }) }

@Composable
private fun RetryContent(onRetry: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.common_retry)) } } }

private val RepoPullRequestStateFilter.label: Int @StringRes get() = when (this) { RepoPullRequestStateFilter.OPEN -> R.string.work_filter_open; RepoPullRequestStateFilter.CLOSED -> R.string.common_state_closed; RepoPullRequestStateFilter.MERGED -> R.string.pr_merged; RepoPullRequestStateFilter.ALL -> R.string.common_all }
private val RepoPullRequestSort.label: Int @StringRes get() = when (this) { RepoPullRequestSort.UPDATED_DESC -> R.string.work_sort_updated_desc; RepoPullRequestSort.UPDATED_ASC -> R.string.work_sort_updated_asc; RepoPullRequestSort.CREATED_DESC -> R.string.work_sort_created_desc; RepoPullRequestSort.CREATED_ASC -> R.string.work_sort_created_asc; RepoPullRequestSort.COMMENTS_DESC -> R.string.work_sort_comments_desc; RepoPullRequestSort.COMMENTS_ASC -> R.string.work_sort_comments_asc }
private val RepoPullRequestReviewEvent.label: Int @StringRes get() = when (this) { RepoPullRequestReviewEvent.COMMENT -> R.string.pr_review_comment; RepoPullRequestReviewEvent.APPROVE -> R.string.pr_review_approve; RepoPullRequestReviewEvent.REQUEST_CHANGES -> R.string.pr_review_request_changes }
private val RepoPullRequestState.label: Int @StringRes get() = when (this) { RepoPullRequestState.OPEN -> R.string.work_filter_open; RepoPullRequestState.CLOSED -> R.string.common_state_closed; RepoPullRequestState.MERGED -> R.string.pr_merged }
private val RepoPullRequestMergeMethod.label: Int @StringRes get() = when (this) { RepoPullRequestMergeMethod.MERGE -> R.string.pr_merge_method_merge; RepoPullRequestMergeMethod.SQUASH -> R.string.pr_merge_method_squash; RepoPullRequestMergeMethod.REBASE -> R.string.pr_merge_method_rebase }
@Composable private fun pullRequestReviewStateLabel(value: String): String = stringResource(when (value) { "COMMENTED" -> R.string.pr_review_state_commented; "APPROVED" -> R.string.pr_review_state_approved; "CHANGES_REQUESTED" -> R.string.pr_review_state_changes_requested; "DISMISSED" -> R.string.pr_review_state_dismissed; "PENDING" -> R.string.pr_review_state_pending; "REVIEW_REQUIRED" -> R.string.pr_review_required; else -> R.string.pr_review_state_pending })
