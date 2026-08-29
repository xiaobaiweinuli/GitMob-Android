package com.gitmob.app.ui.repoissues

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.gitmob.app.ui.common.IssueStateIcon
import com.gitmob.app.ui.common.issueStateVisual
import com.gitmob.app.ui.icons.Octicon
import com.gitmob.app.ui.icons.OcticonName
import com.gitmob.app.ui.common.quoteMarkdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoIssueDetailScreen(
    owner: String,
    name: String,
    number: Int,
    permission: RepoPermission?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onCompose: (ConversationComposeRequest) -> Unit,
    viewModel: RepoIssueDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, number) { viewModel.init(owner, name, number, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pageMenuOpen by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.issue_title_number, number)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    state.issue?.let { issue ->
                        if (issue.viewerCanSubscribe) IconButton(onClick = viewModel::toggleSubscription) {
                            Icon(if (issue.viewerSubscription == "SUBSCRIBED") Icons.Default.NotificationsActive else Icons.Default.NotificationsNone, stringResource(R.string.issue_subscribe))
                        }
                        Box {
                            IconButton(onClick = { pageMenuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.issue_more)) }
                            DropdownMenu(pageMenuOpen, { pageMenuOpen = false }) {
                                if (issue.state == IssueState.OPEN && issue.viewerCanClose) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.issue_close_completed)) }, onClick = { pageMenuOpen = false; viewModel.closeIssue(IssueStateReason.COMPLETED) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.issue_close_not_planned)) }, onClick = { pageMenuOpen = false; viewModel.closeIssue(IssueStateReason.NOT_PLANNED) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.issue_close_duplicate)) }, onClick = { pageMenuOpen = false; viewModel.closeIssue(IssueStateReason.DUPLICATE) })
                                }
                                if (issue.state == IssueState.CLOSED && issue.viewerCanReopen) DropdownMenuItem(text = { Text(stringResource(R.string.issue_reopen)) }, onClick = { pageMenuOpen = false; viewModel.reopenIssue() })
                                if (state.capabilities.canDeleteIssues && issue.viewerCanDelete) DropdownMenuItem(text = { Text(stringResource(R.string.issue_delete), color = MaterialTheme.colorScheme.error) }, onClick = { pageMenuOpen = false; viewModel.confirmDeleteIssue(true) })
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        floatingActionButton = {
            if (state.issue != null) ExtendedFloatingActionButton(
                onClick = { state.issue?.let { onCompose(ConversationComposeRequest(ConversationComposerTarget.ISSUE_COMMENT, it.id)) } },
                icon = { Icon(Icons.AutoMirrored.Filled.Comment, null) },
                text = { Text(stringResource(R.string.conversation_comment)) },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        when {
            state.isLoading && state.issue == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Button(viewModel::retry) { Text(stringResource(R.string.common_retry)) } }
            state.issue != null -> IssueConversation(
                issue = state.issue!!,
                comments = state.comments,
                hasMoreComments = state.hasMoreComments,
                onLoadMore = viewModel::loadMoreComments,
                onEditIssue = onEdit,
                onQuoteIssue = { onCompose(ConversationComposeRequest(ConversationComposerTarget.ISSUE_COMMENT, state.issue!!.id, quoteMarkdown(state.issue!!.body.orEmpty()))) },
                onEditComment = { onCompose(ConversationComposeRequest(ConversationComposerTarget.ISSUE_COMMENT, state.issue!!.id, it.body.orEmpty(), commentId = it.id)) },
                onQuoteComment = { onCompose(ConversationComposeRequest(ConversationComposerTarget.ISSUE_COMMENT, state.issue!!.id, quoteMarkdown(it.body.orEmpty()))) },
                onDeleteComment = viewModel::confirmDeleteComment,
                onEditHistory = viewModel::openEditHistory,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }

    state.pendingDeleteComment?.let {
        AlertDialog(
            onDismissRequest = { viewModel.confirmDeleteComment(null) },
            title = { Text(stringResource(R.string.issue_delete_comment_title)) },
            text = { Text(stringResource(R.string.common_cannot_be_undone)) },
            dismissButton = { TextButton({ viewModel.confirmDeleteComment(null) }) { Text(stringResource(R.string.common_cancel)) } },
            confirmButton = { TextButton(viewModel::deletePendingComment, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_delete)) } },
        )
    }
    if (state.pendingDeleteIssue) AlertDialog(
        onDismissRequest = { viewModel.confirmDeleteIssue(false) },
        title = { Text(stringResource(R.string.issue_delete_title, number)) },
        text = { Text(stringResource(R.string.issue_delete_message)) },
        dismissButton = { TextButton({ viewModel.confirmDeleteIssue(false) }) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = { TextButton({ viewModel.deleteIssue(onBack) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_delete)) } },
    )

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

@Composable
private fun IssueConversation(
    issue: RepoIssue,
    comments: List<IssueComment>,
    hasMoreComments: Boolean,
    onLoadMore: () -> Unit,
    onEditIssue: () -> Unit,
    onQuoteIssue: () -> Unit,
    onEditComment: (IssueComment) -> Unit,
    onQuoteComment: (IssueComment) -> Unit,
    onDeleteComment: (IssueComment?) -> Unit,
    onEditHistory: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { IssueHeader(issue) }
        item {
            ConversationContentCard(
                author = issue.author,
                createdAt = issue.createdAt,
                bodyHtml = issue.bodyHtml,
                url = issue.url,
                authorAssociation = issue.authorAssociation,
                isThreadAuthor = true,
                onQuoteReply = onQuoteIssue,
                onEdit = onEditIssue.takeIf { issue.viewerCanUpdate },
                editSummary = issue.editSummary,
                onEditHistoryClick = { onEditHistory(issue.id) },
            )
        }
        item { Text(stringResource(R.string.issue_comments_count, issue.commentCount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) }
        items(comments, key = { it.id }) { comment ->
            ConversationContentCard(
                author = comment.author,
                createdAt = comment.createdAt,
                bodyHtml = comment.bodyHtml,
                url = comment.url,
                authorAssociation = comment.authorAssociation,
                isThreadAuthor = comment.author?.login == issue.author?.login,
                onQuoteReply = { onQuoteComment(comment) },
                onEdit = ({ onEditComment(comment) }).takeIf { comment.viewerCanUpdate },
                onDelete = ({ onDeleteComment(comment) }).takeIf { comment.viewerCanDelete },
                editSummary = comment.editSummary,
                onEditHistoryClick = { onEditHistory(comment.id) },
            )
        }
        if (hasMoreComments) item { LaunchedEffect(comments.size) { onLoadMore() } }
    }
}

@Composable
private fun IssueHeader(issue: RepoIssue) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(issue.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val visual = issueStateVisual(issue.state, issue.stateReason)
            AssistChip(
                onClick = {},
                label = { Text(stringResource(visual.labelRes)) },
                leadingIcon = {
                    IssueStateIcon(
                        state = issue.state,
                        stateReason = issue.stateReason,
                        locked = false,
                        size = 16.dp,
                    )
                },
            )
            if (issue.locked) {
                Spacer(Modifier.width(8.dp))
                AssistChip(
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
        if (issue.labels.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { issue.labels.forEach { AssistChip(onClick = {}, label = { Text(it.name) }) } }
        issue.milestone?.let { Text(stringResource(R.string.issue_detail_milestone, it.title), style = MaterialTheme.typography.bodyMedium) }
        if (issue.assignees.isNotEmpty()) Text(stringResource(R.string.issue_detail_assignees, issue.assignees.joinToString { "@${it.login}" }), style = MaterialTheme.typography.bodyMedium)
    }
}
