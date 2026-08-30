package com.gitmob.app.ui.repodiscussions

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
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
import com.gitmob.app.data.model.*
import com.gitmob.app.navigation.ConversationComposerTarget
import com.gitmob.app.ui.common.ConversationComposeRequest
import com.gitmob.app.ui.common.ConversationContentCard
import com.gitmob.app.ui.common.ConversationEditHistorySheet
import com.gitmob.app.ui.common.ConversationMenuItem
import com.gitmob.app.ui.common.FilterCapsuleMenu
import com.gitmob.app.ui.common.GitHubEmojiLabel
import com.gitmob.app.ui.common.RepositoryContextTitle
import com.gitmob.app.ui.common.DiscussionStateIcon
import com.gitmob.app.ui.common.discussionStateVisual
import com.gitmob.app.ui.icons.Octicon
import com.gitmob.app.ui.icons.OcticonName
import com.gitmob.app.ui.common.quoteMarkdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDiscussionListScreen(owner: String, name: String, permission: RepoPermission?, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, onDiscussionClick: (Int) -> Unit, onCreate: () -> Unit, viewModel: RepoDiscussionListViewModel = hiltViewModel()) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { RepositoryContextTitle(owner, name, stringResource(R.string.common_discussions), onOwnerClick, onRepositoryClick) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, actions = { if (state.hasDiscussionsEnabled && state.categories.isNotEmpty()) IconButton(onClick = onCreate) { Icon(Icons.Default.Add, stringResource(R.string.discussion_new)) } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            DiscussionFilters(state, viewModel)
            PullToRefreshBox(state.isLoading && state.items.isNotEmpty(), viewModel::refresh, Modifier.fillMaxSize()) {
                when {
                    state.isLoading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.loadFailed && state.items.isEmpty() -> DiscussionRetry(Modifier.fillMaxSize(), viewModel::retry)
                    !state.hasDiscussionsEnabled -> Text(stringResource(R.string.discussion_disabled), modifier = Modifier.align(Alignment.Center))
                    state.items.isEmpty() -> Text(stringResource(R.string.discussion_empty), modifier = Modifier.align(Alignment.Center))
                    else -> LazyColumn { items(state.items, key = { it.id }) { item -> DiscussionRow(item, state.capabilities.canManageIssuesAndPRs && item.viewerCanDelete, { viewModel.confirmDelete(item) }) { onDiscussionClick(item.number) }; HorizontalDivider() }; if (state.hasNextPage) item { LaunchedEffect(state.items.size) { viewModel.loadMore() }; Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) } } }
                }
            }
        }
    }
    state.pendingDelete?.let { item -> AlertDialog(onDismissRequest = { viewModel.confirmDelete(null) }, title = { Text(stringResource(R.string.discussion_delete_title, item.number)) }, text = { Text(stringResource(R.string.discussion_delete_message)) }, dismissButton = { TextButton(onClick = { viewModel.confirmDelete(null) }) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(onClick = viewModel::deletePending) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDiscussionDetailScreen(owner: String, name: String, number: Int, permission: RepoPermission?, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, onEdit: () -> Unit, onCompose: (ConversationComposeRequest) -> Unit, onUserClick: (String) -> Unit, viewModel: RepoDiscussionDetailViewModel = hiltViewModel()) {
    LaunchedEffect(owner, name, number) { viewModel.init(owner, name, number, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { RepositoryContextTitle(owner, name, stringResource(R.string.discussion_number, number), onOwnerClick, onRepositoryClick) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, actions = { state.discussion?.let { d -> if (d.viewerCanSubscribe) IconButton(onClick = viewModel::toggleSubscription) { Icon(if (d.viewerSubscription == "SUBSCRIBED") Icons.Default.NotificationsActive else Icons.Default.NotificationsNone, stringResource(R.string.issue_subscribe)) }; Box { IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.issue_more)) }; DropdownMenu(menuOpen, { menuOpen = false }) { if (d.state == RepoDiscussionState.OPEN && d.viewerCanClose) DropdownMenuItem(text = { Text(stringResource(R.string.discussion_close)) }, onClick = { menuOpen = false; viewModel.close() }); if (d.state == RepoDiscussionState.CLOSED && d.viewerCanReopen) DropdownMenuItem(text = { Text(stringResource(R.string.discussion_reopen)) }, onClick = { menuOpen = false; viewModel.reopen() }); if (state.capabilities.canManageIssuesAndPRs && d.viewerCanDelete) DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; viewModel.confirmDeleteDiscussion(true) }) } } } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) },
        floatingActionButton = {
            state.discussion?.let { discussion ->
                ExtendedFloatingActionButton(
                    onClick = {
                        onCompose(
                            ConversationComposeRequest(
                                target = ConversationComposerTarget.DISCUSSION_COMMENT,
                                subjectId = discussion.id,
                            ),
                        )
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.Comment, null) },
                    text = { Text(stringResource(R.string.conversation_comment)) },
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        when {
            state.isLoading && state.discussion == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> DiscussionRetry(Modifier.fillMaxSize().padding(padding), viewModel::retry)
            state.discussion != null -> {
                val discussion = state.discussion!!
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { DiscussionHeader(discussion) }
                    item {
                        ConversationContentCard(
                            author = discussion.author,
                            createdAt = discussion.createdAt,
                            bodyHtml = discussion.bodyHtml,
                            url = discussion.url,
                            authorAssociation = discussion.authorAssociation,
                            isThreadAuthor = true,
                            onQuoteReply = {
                                onCompose(
                                    ConversationComposeRequest(
                                        target = ConversationComposerTarget.DISCUSSION_COMMENT,
                                        subjectId = discussion.id,
                                        initialText = quoteMarkdown(discussion.body),
                                    ),
                                )
                            },
                            onEdit = onEdit.takeIf { discussion.viewerCanUpdate },
                            editSummary = discussion.editSummary,
                            onEditHistoryClick = { viewModel.openEditHistory(discussion.id) },
                            onAuthorClick = onUserClick,
                        )
                    }
                    item { Text(stringResource(R.string.issue_comments_count, discussion.commentCount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    items(state.comments, key = { it.id }) { comment ->
                        val answerLabel = stringResource(if (comment.isAnswer) R.string.discussion_unmark_answer else R.string.discussion_mark_answer)
                        val answerActions = if (comment.viewerCanMarkAsAnswer || comment.viewerCanUnmarkAsAnswer) listOf(ConversationMenuItem(answerLabel) { viewModel.markAnswer(comment, !comment.isAnswer) }) else emptyList()
                        ConversationContentCard(
                            author = comment.author,
                            createdAt = comment.createdAt,
                            bodyHtml = comment.bodyHtml,
                            url = comment.url,
                            authorAssociation = comment.authorAssociation,
                            isThreadAuthor = comment.author?.login == discussion.author?.login,
                            onQuoteReply = {
                                onCompose(
                                    ConversationComposeRequest(
                                        target = ConversationComposerTarget.DISCUSSION_COMMENT,
                                        subjectId = discussion.id,
                                        initialText = quoteMarkdown(comment.body),
                                        replyToId = comment.id,
                                    ),
                                )
                            },
                            onEdit = ({
                                onCompose(
                                    ConversationComposeRequest(
                                        target = ConversationComposerTarget.DISCUSSION_COMMENT,
                                        subjectId = discussion.id,
                                        initialText = comment.body,
                                        commentId = comment.id,
                                    ),
                                )
                            }).takeIf { comment.viewerCanUpdate },
                            onDelete = ({ viewModel.confirmDeleteComment(comment) }).takeIf { comment.viewerCanDelete },
                            extraMenuItems = answerActions,
                            editSummary = comment.editSummary,
                            onEditHistoryClick = { viewModel.openEditHistory(comment.id) },
                            onAuthorClick = onUserClick,
                        )
                    }
                    if (state.hasMoreComments) item { LaunchedEffect(state.comments.size) { viewModel.loadMoreComments() } }
                }
            }
        }
    }

    state.pendingDeleteComment?.let { AlertDialog(onDismissRequest = { viewModel.confirmDeleteComment(null) }, title = { Text(stringResource(R.string.issue_delete_comment_title)) }, text = { Text(stringResource(R.string.common_cannot_be_undone)) }, dismissButton = { TextButton(onClick = { viewModel.confirmDeleteComment(null) }) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(onClick = viewModel::deletePendingComment) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } }) }
    if (state.pendingDeleteDiscussion) AlertDialog(onDismissRequest = { viewModel.confirmDeleteDiscussion(false) }, title = { Text(stringResource(R.string.discussion_delete_title, number)) }, text = { Text(stringResource(R.string.discussion_delete_message)) }, dismissButton = { TextButton(onClick = { viewModel.confirmDeleteDiscussion(false) }) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(onClick = { viewModel.deleteDiscussion(onBack) }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } })
    if (state.editHistory.isOpen) ConversationEditHistorySheet(
        edits = state.editHistory.items,
        isLoading = state.editHistory.isLoading,
        isLoadingMore = state.editHistory.isLoadingMore,
        loadFailed = state.editHistory.loadFailed,
        hasNextPage = state.editHistory.hasNextPage,
        selectedEdit = state.editHistory.selectedEdit,
        onDismiss = viewModel::closeEditHistory,
        onLoadMore = viewModel::loadMoreEditHistory,
        onRetry = viewModel::retryEditHistory,
        onSelect = viewModel::selectEdit,
        onClearSelected = viewModel::clearSelectedEdit,
    )
}

@Composable private fun DiscussionHeader(d: RepoDiscussion) {
    val visual = discussionStateVisual(
        stateReason = d.stateReason,
        isAnswered = d.answerChosenAt != null,
        locked = false,
        isClosed = d.state == RepoDiscussionState.CLOSED,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(d.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        GitHubEmojiLabel(d.category.emoji, d.category.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {},
                label = { Text(stringResource(visual.labelRes)) },
                leadingIcon = {
                    DiscussionStateIcon(
                        stateReason = d.stateReason,
                        isAnswered = d.answerChosenAt != null,
                        locked = false,
                        isClosed = d.state == RepoDiscussionState.CLOSED,
                        size = 16.dp,
                    )
                },
            )
            if (d.locked) AssistChip(
                onClick = {},
                label = { Text(stringResource(R.string.state_locked)) },
                leadingIcon = {
                    Octicon(
                        name = OcticonName.LOCKED,
                        contentDescription = stringResource(R.string.state_locked),
                        size = 16.dp,
                    )
                },
            )
        }
    }
}
@Composable private fun DiscussionFilters(state: RepoDiscussionListUiState, vm: RepoDiscussionListViewModel) {
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
                options = RepoDiscussionStateFilter.entries,
                optionLabel = { stringResource(it.label) },
                onSelected = vm::setState,
                filterLabel = stringResource(R.string.work_filter_state),
                neutralLabel = stringResource(R.string.work_filter_state),
                isNeutral = { it == RepoDiscussionStateFilter.ALL },
            )
            FilterCapsuleMenu(
                selected = state.filter.sort,
                options = RepoDiscussionSort.entries,
                optionLabel = { stringResource(it.label) },
                onSelected = vm::setSort,
                filterLabel = stringResource(R.string.work_filter_sort),
            )
            FilterCapsuleMenu(
                selected = state.categories.firstOrNull { it.id == state.filter.categoryId },
                options = buildList<RepoDiscussionCategory?> {
                    add(null)
                    addAll(state.categories)
                },
                optionLabel = { category -> category?.name ?: stringResource(R.string.common_all) },
                onSelected = { category -> vm.setCategory(category?.id) },
                filterLabel = stringResource(R.string.discussion_category),
                neutralLabel = stringResource(R.string.discussion_category),
                isNeutral = { it == null },
                optionContent = { category ->
                    if (category == null) Text(stringResource(R.string.common_all))
                    else GitHubEmojiLabel(category.emoji, category.name)
                },
                selectedContent = { category ->
                    if (category == null) Text(stringResource(R.string.discussion_category))
                    else GitHubEmojiLabel(category.emoji, category.name, iconSize = 16.dp)
                },
            )
            FilterCapsuleMenu(
                selected = state.filter.answered,
                options = listOf(null, true, false),
                optionLabel = { answered ->
                    stringResource(
                        when (answered) {
                            true -> R.string.discussion_answered
                            false -> R.string.discussion_unanswered
                            null -> R.string.common_all
                        },
                    )
                },
                onSelected = vm::setAnswered,
                filterLabel = stringResource(R.string.discussion_answer_filter),
                neutralLabel = stringResource(R.string.discussion_answer_filter),
                isNeutral = { it == null },
            )
        }
        Text(
            text = stringResource(R.string.work_items_count, state.totalCount),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider()
    }
}
@Composable private fun DiscussionRow(item: RepoDiscussion, canDelete: Boolean, delete: () -> Unit, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = click).padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            DiscussionStateIcon(
                stateReason = item.stateReason,
                isAnswered = item.answerChosenAt != null,
                locked = item.locked,
                isClosed = item.state == RepoDiscussionState.CLOSED,
                size = 20.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                GitHubEmojiLabel(item.category.emoji, "${item.category.name} · #${item.number} · ${item.updatedAt.take(10)}", style = MaterialTheme.typography.bodySmall, iconSize = 16.dp)
            }
            if (item.commentCount > 0) Text(item.commentCount.toString())
        }
        if (item.answerChosenAt != null) Text(stringResource(R.string.discussion_answered), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        if (canDelete) TextButton(onClick = delete, modifier = Modifier.align(Alignment.End)) { Icon(Icons.Default.Delete, null); Text(stringResource(R.string.common_delete)) }
    }
}
@Composable private fun DiscussionRetry(modifier: Modifier, retry: () -> Unit) { Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = retry) { Text(stringResource(R.string.common_retry)) } } }
private val RepoDiscussionStateFilter.label: Int @StringRes get() = when (this) { RepoDiscussionStateFilter.OPEN -> R.string.work_filter_open; RepoDiscussionStateFilter.CLOSED -> R.string.common_state_closed; RepoDiscussionStateFilter.ALL -> R.string.common_all }
private val RepoDiscussionSort.label: Int @StringRes get() = when (this) { RepoDiscussionSort.UPDATED_DESC -> R.string.work_sort_updated_desc; RepoDiscussionSort.UPDATED_ASC -> R.string.work_sort_updated_asc; RepoDiscussionSort.CREATED_DESC -> R.string.work_sort_created_desc; RepoDiscussionSort.CREATED_ASC -> R.string.work_sort_created_asc }
