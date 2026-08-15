package com.gitmob.app.ui.repoissues

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import com.gitmob.app.ui.common.MarkdownWebView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoIssueDetailScreen(
    owner: String,
    name: String,
    number: Int,
    permission: RepoPermission?,
    onBack: () -> Unit,
    viewModel: RepoIssueDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, number) { viewModel.init(owner, name, number, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editOpen by remember { mutableStateOf(false) }
    var commentText by remember { mutableStateOf("") }
    var editingComment by remember { mutableStateOf<IssueComment?>(null) }
    var closeMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.issue == null) "议题 #$number" else "#${state.issue!!.number}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    val issue = state.issue
                    if (issue != null) {
                        if (issue.viewerCanSubscribe) IconButton(onClick = viewModel::toggleSubscription) { Icon(if (issue.viewerSubscription == "SUBSCRIBED") Icons.Default.NotificationsActive else Icons.Default.NotificationsNone, "订阅") }
                        Box {
                            IconButton(onClick = { closeMenu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                            DropdownMenu(closeMenu, { closeMenu = false }) {
                                if (issue.viewerCanUpdate) DropdownMenuItem(text = { Text("编辑议题") }, onClick = { closeMenu = false; editOpen = true })
                                if (issue.state == IssueState.OPEN && issue.viewerCanClose) {
                                    DropdownMenuItem(text = { Text("关闭为已完成") }, onClick = { closeMenu = false; viewModel.closeIssue(IssueStateReason.COMPLETED) })
                                    DropdownMenuItem(text = { Text("关闭为不计划处理") }, onClick = { closeMenu = false; viewModel.closeIssue(IssueStateReason.NOT_PLANNED) })
                                    DropdownMenuItem(text = { Text("关闭为重复") }, onClick = { closeMenu = false; viewModel.closeIssue(IssueStateReason.DUPLICATE) })
                                }
                                if (issue.state == IssueState.CLOSED && issue.viewerCanReopen) DropdownMenuItem(text = { Text("重新打开") }, onClick = { closeMenu = false; viewModel.reopenIssue() })
                                if (state.capabilities.canDeleteIssues && issue.viewerCanDelete) DropdownMenuItem(text = { Text("删除议题", color = MaterialTheme.colorScheme.error) }, onClick = { closeMenu = false; viewModel.confirmDeleteIssue(true) })
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        bottomBar = {
            if (state.issue != null) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(commentText, { commentText = it }, label = { Text("添加评论") }, modifier = Modifier.weight(1f), minLines = 1, maxLines = 5)
                IconButton(onClick = { viewModel.addComment(commentText) { commentText = "" } }, enabled = commentText.isNotBlank()) { Icon(Icons.Default.Send, "提交评论") }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        when {
            state.isLoading && state.issue == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Button(viewModel::retry) { Text("重试") } }
            state.issue != null -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 80.dp)) {
                item { IssueHeader(state.issue!!, state.labels, state.milestones, state.assignableUsers) }
                state.issue!!.bodyHtml?.takeIf { it.isNotBlank() }?.let { html -> item { MarkdownWebView(html, Modifier.fillMaxWidth()) } }
                item { Text("评论 ${state.issue!!.commentCount}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp, 12.dp)) }
                items(state.comments, key = { it.id }) { comment -> CommentRow(comment, onEdit = { editingComment = comment }, onDelete = { viewModel.confirmDeleteComment(comment) }) }
                if (state.hasMoreComments) item { LaunchedEffect(state.comments.size) { viewModel.loadMoreComments() }; Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) } }
            }
        }
    }

    if (editOpen) {
        val issue = state.issue!!
        IssueEditDialog(
            issue = issue,
            labels = state.labels,
            milestones = state.milestones,
            assignees = state.assignableUsers,
            canEditLabels = state.capabilities.canManageIssuesAndPRs && issue.viewerCanLabel,
            canEditMilestone = state.capabilities.canManageIssuesAndPRs && issue.viewerCanSetMilestone,
            canEditAssignees = state.capabilities.canManageIssuesAndPRs && issue.viewerCanUpdate,
            onDismiss = { editOpen = false },
        ) { title, body, labelIds, assigneeIds, milestoneId ->
            viewModel.updateIssue(title, body, labelIds, assigneeIds, milestoneId) { editOpen = false }
        }
    }
    editingComment?.let { comment -> CommentEditDialog(comment, { editingComment = null }) { body -> viewModel.updateComment(comment, body) { editingComment = null } } }
    state.pendingDeleteComment?.let { comment -> AlertDialog(onDismissRequest = { viewModel.confirmDeleteComment(null) }, title = { Text("删除评论？") }, text = { Text("此操作无法撤销。") }, dismissButton = { TextButton({ viewModel.confirmDeleteComment(null) }) { Text("取消") } }, confirmButton = { TextButton(viewModel::deletePendingComment, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") } }) }
    if (state.pendingDeleteIssue) AlertDialog(onDismissRequest = { viewModel.confirmDeleteIssue(false) }, title = { Text("删除议题 #$number？") }, text = { Text("此操作无法撤销，议题及其评论都会被删除。") }, dismissButton = { TextButton({ viewModel.confirmDeleteIssue(false) }) { Text("取消") } }, confirmButton = { TextButton({ viewModel.deleteIssue(onBack) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") } })
}

@Composable
private fun IssueHeader(issue: RepoIssue, labels: List<IssueLabel>, milestones: List<IssueMilestone>, assignableUsers: List<SimpleUser>) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(issue.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("#${issue.number} · ${issue.author?.login ?: "ghost"} · ${issue.createdAt.take(10)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) { AssistChip(onClick = {}, label = { Text(if (issue.state == IssueState.OPEN) "打开" else "已关闭") }, leadingIcon = { Icon(if (issue.state == IssueState.OPEN) Icons.Default.RadioButtonChecked else Icons.Default.CheckCircle, null, Modifier.size(16.dp)) }); if (issue.locked) { Spacer(Modifier.width(8.dp)); AssistChip(onClick = {}, label = { Text("已锁定") }, leadingIcon = { Icon(Icons.Default.Lock, null, Modifier.size(16.dp)) }) } }
        if (issue.labels.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { issue.labels.forEach { AssistChip(onClick = {}, label = { Text(it.name) }) } }
        issue.milestone?.let { Text("里程碑：${it.title}", style = MaterialTheme.typography.bodyMedium) }
        if (issue.assignees.isNotEmpty()) Text("指派：${issue.assignees.joinToString { "@${it.login}" }}", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
    }
}

@Composable
private fun CommentRow(comment: IssueComment, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(comment.author?.login ?: "ghost", fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(8.dp)); Text(comment.createdAt.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.weight(1f)); IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }; DropdownMenu(menu, { menu = false }) { if (comment.viewerCanUpdate) DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() }); if (comment.viewerCanDelete) DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() }) } }
        MarkdownWebView(comment.bodyHtml, Modifier.fillMaxWidth())
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun IssueEditDialog(
    issue: RepoIssue,
    labels: List<IssueLabel>,
    milestones: List<IssueMilestone>,
    assignees: List<SimpleUser>,
    canEditLabels: Boolean,
    canEditMilestone: Boolean,
    canEditAssignees: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String, List<String>, List<String>, String?) -> Unit,
) {
    var title by remember { mutableStateOf(issue.title) }
    var body by remember { mutableStateOf(issue.body.orEmpty()) }
    var selectedLabels by remember { mutableStateOf(issue.labels.map { it.id }.toSet()) }
    var selectedAssignees by remember { mutableStateOf(issue.assignees.mapNotNull { it.id }.toSet()) }
    var selectedMilestone by remember { mutableStateOf(issue.milestone?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑议题") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(body, { body = it }, label = { Text("Markdown 正文") }, minLines = 6, modifier = Modifier.fillMaxWidth()) }
                if (canEditLabels && labels.isNotEmpty()) {
                    item { Text("标签", fontWeight = FontWeight.SemiBold) }
                    items(labels, key = { "label-${it.id}" }) { label -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(label.id in selectedLabels, { checked -> selectedLabels = if (checked) selectedLabels + label.id else selectedLabels - label.id }); Text(label.name) } }
                }
                if (canEditMilestone) {
                    item { Text("里程碑", fontWeight = FontWeight.SemiBold) }
                    item { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedMilestone == null, { selectedMilestone = null }); Text("无里程碑") } }
                    items(milestones, key = { "milestone-${it.id}" }) { milestone -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedMilestone == milestone.id, { selectedMilestone = milestone.id }); Text(milestone.title) } }
                }
                if (canEditAssignees && assignees.isNotEmpty()) {
                    item { Text("指派人", fontWeight = FontWeight.SemiBold) }
                    items(assignees, key = { "assignee-${it.login}" }) { user -> val id = user.id; if (id != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(id in selectedAssignees, { checked -> selectedAssignees = if (checked) selectedAssignees + id else selectedAssignees - id }); Text(user.login) } }
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
        confirmButton = { Button({ onSubmit(title, body, selectedLabels.toList(), selectedAssignees.toList(), selectedMilestone) }, enabled = title.isNotBlank()) { Text("保存") } },
    )
}

@Composable
private fun CommentEditDialog(comment: IssueComment, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var body by remember { mutableStateOf(comment.body.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑评论") }, text = { OutlinedTextField(body, { body = it }, minLines = 5, label = { Text("Markdown") }) }, dismissButton = { TextButton(onDismiss) { Text("取消") } }, confirmButton = { Button({ onSubmit(body) }, enabled = body.isNotBlank()) { Text("保存") } })
}
