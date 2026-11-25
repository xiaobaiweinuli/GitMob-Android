package com.gitmob.app.ui.repodiscussions

import com.gitmob.app.ui.common.StatusBadge
import com.gitmob.app.ui.common.ActionProgressIcon
import com.gitmob.app.ui.common.ActionButtonContent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import com.gitmob.app.data.translation.TranslationTarget
import com.gitmob.app.navigation.ConversationComposerTarget
import com.gitmob.app.ui.common.ActionFloatingButton
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.ActionStateIcon
import com.gitmob.app.ui.common.AppDropdownMenu as DropdownMenu
import com.gitmob.app.ui.common.AppDropdownMenuItem as DropdownMenuItem
import com.gitmob.app.ui.common.ConversationComposeRequest
import com.gitmob.app.ui.common.ConversationContentCard
import com.gitmob.app.ui.common.ConversationEditHistorySheet
import com.gitmob.app.ui.common.ConversationMenuItem
import com.gitmob.app.ui.common.DiscussionStateIcon
import com.gitmob.app.ui.common.FilterCapsuleMenu
import com.gitmob.app.ui.common.GitHubEmojiLabel
import com.gitmob.app.ui.common.ListFeedback
import com.gitmob.app.ui.common.ListFeedbackState
import com.gitmob.app.ui.common.RepositoryContextTitle
import com.gitmob.app.ui.common.RepositorySearchButton
import com.gitmob.app.ui.common.RepositorySearchField
import com.gitmob.app.ui.common.SearchActions
import com.gitmob.app.ui.common.SearchTitle
import com.gitmob.app.ui.common.discussionStateVisual
import com.gitmob.app.ui.common.informationItem
import com.gitmob.app.ui.common.pressClickable
import com.gitmob.app.ui.common.quoteMarkdown
import com.gitmob.app.ui.icons.Octicon
import com.gitmob.app.ui.icons.OcticonName
import com.gitmob.app.ui.translation.TranslationMenuItem
import com.gitmob.app.ui.translation.TranslationText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDiscussionListScreen(owner: String, name: String, permission: RepoPermission?, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, onDiscussionClick: (Int) -> Unit, onCreate: () -> Unit, viewModel: RepoDiscussionListViewModel = hiltViewModel(), initialQuery: String = "") {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searchExpanded by rememberSaveable { mutableStateOf(initialQuery.isNotBlank()) }
    var focusSearch by rememberSaveable { mutableStateOf(false) }
    var searchInput by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.submittedQuery)) }
    var draftInitialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(owner, name, initialQuery) {
        viewModel.init(owner, name, permission, initialQuery)
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    SearchTitle(searchExpanded) { expanded ->
                    if (expanded) {
                        RepositorySearchField(
                            value = searchInput,
                            onValueChange = { searchInput = it },
                            placeholder = stringResource(R.string.repo_discussion_search_hint),
                            hasSubmittedQuery = state.submittedQuery.isNotEmpty(),
                            focusOnExpand = focusSearch && searchExpanded,
                            onSubmit = { value ->
                                viewModel.submitSearch(value)
                                searchInput = TextFieldValue(viewModel.state.value.submittedQuery)
                            },
                        )
                    } else RepositoryContextTitle(
                        owner, name, stringResource(R.string.common_discussions),
                        { login -> leaveList { onOwnerClick(login) } },
                        { repoOwner, repoName -> leaveList { onRepositoryClick(repoOwner, repoName) } },
                    )
                                    }
                },
                navigationIcon = { ActionIconButton(onClick = { if (searchExpanded) leaveList {} else leaveList(onBack) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    SearchActions(visible = !searchExpanded) {
                        RepositorySearchButton(state.submittedQuery.isNotEmpty()) { focusSearch = true; searchExpanded = true }
                    }
                    if (state.hasDiscussionsEnabled && state.categories.isNotEmpty()) ActionIconButton(onClick = { leaveList(onCreate) }) { Icon(Icons.Default.Add, stringResource(R.string.discussion_new)) }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            DiscussionFilters(state, viewModel)
            PullToRefreshBox(state.isLoading && state.items.isNotEmpty(), viewModel::refresh, Modifier.fillMaxSize()) {
                val feedback = when {
                    state.isLoading && state.items.isEmpty() -> ListFeedbackState.LOADING
                    state.loadFailed && state.items.isEmpty() -> ListFeedbackState.ERROR
                    !state.hasDiscussionsEnabled -> ListFeedbackState.EMPTY
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
                    items(if (feedback == ListFeedbackState.CONTENT) state.items else emptyList(), key = { it.id }) { item ->
                        Box(Modifier.animateItem().informationItem()) {
                            DiscussionRow(item, state.capabilities.canManageIssuesAndPRs && item.viewerCanDelete, { viewModel.confirmDelete(item) }) { leaveList { onDiscussionClick(item.number) } }
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
                ListFeedback(feedback, viewModel::retry, emptyMessage = if (!state.hasDiscussionsEnabled) stringResource(R.string.discussion_disabled) else stringResource(R.string.discussion_empty))
            }
        }
    }
    state.pendingDelete?.let { item -> AlertDialog(onDismissRequest = { viewModel.confirmDelete(null) }, title = { Text(stringResource(R.string.discussion_delete_title, item.number)) }, text = { Text(stringResource(R.string.discussion_delete_message)) }, dismissButton = { TextButton(enabled = !state.isDeleting, onClick = { viewModel.confirmDelete(null) }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(enabled = !state.isDeleting, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = viewModel::deletePending, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = state.isDeleting, label = stringResource(R.string.common_delete)) } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDiscussionDetailScreen(owner: String, name: String, number: Int, permission: RepoPermission?, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, onEdit: () -> Unit, onCompose: (ConversationComposeRequest) -> Unit, onUserClick: (String) -> Unit, viewModel: RepoDiscussionDetailViewModel = hiltViewModel()) {
    LaunchedEffect(owner, name, number) { viewModel.init(owner, name, number, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { RepositoryContextTitle(owner, name, stringResource(R.string.discussion_number, number), onOwnerClick, onRepositoryClick) },
                navigationIcon = { ActionIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    state.discussion?.let { discussion ->
                        if (discussion.viewerCanSubscribe) IconToggleButton(
                            checked = discussion.viewerSubscription == "SUBSCRIBED",
                            onCheckedChange = { viewModel.toggleSubscription() },
                            shapes = IconButtonDefaults.toggleableShapes(),
                            colors = IconButtonDefaults.iconToggleButtonColors(),
                            enabled = !state.isUpdatingSubscription,
                        ) {
                            ActionProgressIcon(state.isUpdatingSubscription, busyDescription = stringResource(R.string.issue_subscribe)) {
                                ActionStateIcon(if (discussion.viewerSubscription == "SUBSCRIBED") Icons.Default.NotificationsActive else Icons.Default.NotificationsNone, stringResource(R.string.issue_subscribe))
                            }
                        }
                        Box {
                            ActionIconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.issue_more)) }
                            DropdownMenu(menuOpen, { menuOpen = false }) {
                                TranslationMenuItem(
                                    TranslationTarget(discussion.url + ":title", discussion.title),
                                    { menuOpen = false },
                                    R.string.translation_translate_title,
                                )
                                if (discussion.state == RepoDiscussionState.OPEN && discussion.viewerCanClose) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.discussion_close)) },
                                    onClick = { menuOpen = false; viewModel.close() },
                                )
                                if (discussion.state == RepoDiscussionState.CLOSED && discussion.viewerCanReopen) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.discussion_reopen)) },
                                    onClick = { menuOpen = false; viewModel.reopen() },
                                )
                                if (state.capabilities.canManageIssuesAndPRs && discussion.viewerCanDelete) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; viewModel.confirmDeleteDiscussion(true) },
                                )
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        floatingActionButton = {
            state.discussion?.let { discussion ->
                ActionFloatingButton(
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
                            isThreadAuthor = comment.author?.login?.equals(discussion.author?.login, ignoreCase = true) == true,
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

    state.pendingDeleteComment?.let { AlertDialog(onDismissRequest = { viewModel.confirmDeleteComment(null) }, title = { Text(stringResource(R.string.issue_delete_comment_title)) }, text = { Text(stringResource(R.string.common_cannot_be_undone)) }, dismissButton = { TextButton(enabled = !state.isDeletingComment, onClick = { viewModel.confirmDeleteComment(null) }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(enabled = !state.isDeletingComment, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = viewModel::deletePendingComment, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = state.isDeletingComment, label = stringResource(R.string.common_delete)) } }) }
    if (state.pendingDeleteDiscussion) AlertDialog(onDismissRequest = { viewModel.confirmDeleteDiscussion(false) }, title = { Text(stringResource(R.string.discussion_delete_title, number)) }, text = { Text(stringResource(R.string.discussion_delete_message)) }, dismissButton = { TextButton(enabled = !state.isDeletingDiscussion, onClick = { viewModel.confirmDeleteDiscussion(false) }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(enabled = !state.isDeletingDiscussion, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = { viewModel.deleteDiscussion(onBack) }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = state.isDeletingDiscussion, label = stringResource(R.string.common_delete)) } })
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
        TranslationText(TranslationTarget(d.url + ":title", d.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, showStatus = true)
        GitHubEmojiLabel(d.category.emoji, d.category.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusBadge(label = { Text(stringResource(visual.labelRes)) },
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
            if (d.locked) StatusBadge(label = { Text(stringResource(R.string.state_locked)) },
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
                selected = state.filter.category?.name ?: state.filter.categoryTitle,
                options = buildList<String?> {
                    add(null)
                    addAll(state.categories.map { it.name })
                },
                optionLabel = { category -> category ?: stringResource(R.string.common_all) },
                onSelected = { title -> vm.setCategory(state.categories.find { it.name == title }) },
                filterLabel = stringResource(R.string.discussion_category),
                neutralLabel = stringResource(R.string.discussion_category),
                isNeutral = { it == null },
                optionContent = { category ->
                    if (category == null) Text(stringResource(R.string.common_all))
                    else GitHubEmojiLabel(state.categories.find { it.name == category }?.emoji.orEmpty(), category)
                },
                selectedContent = { category ->
                    if (category == null) Text(stringResource(R.string.discussion_category))
                    else GitHubEmojiLabel(state.categories.find { it.name == category }?.emoji.orEmpty(), category, iconSize = 16.dp)
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
            text = pluralStringResource(R.plurals.work_items_count, state.totalCount, state.totalCount),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider()
    }
}
@Composable private fun DiscussionRow(item: RepoDiscussion, canDelete: Boolean, delete: () -> Unit, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().pressClickable(onClick = click).padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
        if (canDelete) TextButton(onClick = delete, modifier = Modifier.align(Alignment.End), shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Icon(Icons.Default.Delete, null); Text(stringResource(R.string.common_delete)) }
    }
}
@Composable private fun DiscussionRetry(modifier: Modifier, retry: () -> Unit) { Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = retry, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_retry)) } } }
private val RepoDiscussionStateFilter.label: Int @StringRes get() = when (this) { RepoDiscussionStateFilter.OPEN -> R.string.work_filter_open; RepoDiscussionStateFilter.CLOSED -> R.string.common_state_closed; RepoDiscussionStateFilter.ALL -> R.string.common_all }
private val RepoDiscussionSort.label: Int @StringRes get() = when (this) { RepoDiscussionSort.UPDATED_DESC -> R.string.work_sort_updated_desc; RepoDiscussionSort.UPDATED_ASC -> R.string.work_sort_updated_asc; RepoDiscussionSort.CREATED_DESC -> R.string.work_sort_created_desc; RepoDiscussionSort.CREATED_ASC -> R.string.work_sort_created_asc }
