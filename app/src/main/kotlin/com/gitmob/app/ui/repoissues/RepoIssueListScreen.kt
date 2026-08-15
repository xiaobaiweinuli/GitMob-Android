package com.gitmob.app.ui.repoissues

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoIssueListScreen(
    owner: String,
    name: String,
    permission: RepoPermission?,
    viewerCanCreateIssues: Boolean?,
    onBack: () -> Unit,
    onIssueClick: (Int) -> Unit,
    viewModel: RepoIssueListViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name, permission, viewerCanCreateIssues) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var templatePicker by remember { mutableStateOf(false) }
    var editorTemplate by remember { mutableStateOf<IssueTemplate?>(null) }
    var editorOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("议题") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (state.viewerCanCreateIssues) {
                        IconButton(onClick = {
                            if (state.templates.isNotEmpty()) templatePicker = true else { editorTemplate = null; editorOpen = true }
                        }) { Icon(Icons.Default.Add, "新建议题") }
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
                    state.items.isEmpty() -> Text("没有符合条件的议题", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            title = { Text("删除议题 #${issue.number}？") },
            text = { Text("此操作无法撤销，议题及其评论都会被删除。") },
            dismissButton = { TextButton(onClick = { viewModel.confirmDelete(null) }) { Text("取消") } },
            confirmButton = { TextButton(onClick = viewModel::deletePending, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") } },
        )
    }

    if (templatePicker) {
        AlertDialog(
            onDismissRequest = { templatePicker = false },
            title = { Text("选择议题模板") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (state.blankIssuesEnabled) item {
                        ListItem(headlineContent = { Text("空白议题") }, leadingContent = { Icon(Icons.Default.NoteAdd, null) }, modifier = Modifier.clickable { templatePicker = false; editorTemplate = null; editorOpen = true })
                    }
                    items(state.templates) { template ->
                        ListItem(headlineContent = { Text(template.name) }, supportingContent = { template.about?.let { Text(it) } }, modifier = Modifier.clickable { templatePicker = false; editorTemplate = template; editorOpen = true })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { templatePicker = false }) { Text("取消") } },
        )
    }

    if (editorOpen) {
        IssueEditorDialog(
            template = editorTemplate,
            labels = state.labels,
            milestones = state.milestones,
            assignees = state.assignableUsers,
            onDismiss = { editorOpen = false },
            onSubmit = { title, body, labelIds, assigneeIds, milestoneId ->
                viewModel.createIssue(title, body, editorTemplate, labelIds, assigneeIds, milestoneId) { issue -> editorOpen = false; onIssueClick(issue.number) }
            },
        )
    }
}

@Composable
private fun FilterControls(state: RepoIssueListUiState, vm: RepoIssueListViewModel) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            FilterMenu("状态", state.filter.state, RepoIssueStateFilter.entries, { when (it) { RepoIssueStateFilter.OPEN -> "打开"; RepoIssueStateFilter.CLOSED -> "已关闭"; RepoIssueStateFilter.ALL -> "全部" } }, vm::setState, Modifier.weight(1f))
            FilterMenu("排序", state.filter.sort, RepoIssueSort.entries, { sortLabel(it) }, vm::setSort, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            MultiLabelMenu(state.labels, state.filter.labels, vm::setLabels, Modifier.weight(1f))
            MoreFilterMenu(state, vm, Modifier.weight(1f))
        }
        Text("${state.totalCount} 条", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        HorizontalDivider()
    }
}

@Composable
private fun <T> FilterMenu(label: String, selected: T, options: List<T>, text: (T) -> String, onSelected: (T) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(text(selected), maxLines = 1) }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { item -> DropdownMenuItem(text = { Text(text(item)) }, onClick = { expanded = false; onSelected(item) }, leadingIcon = { if (item == selected) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) }) } }
    }
}

@Composable
private fun MultiLabelMenu(labels: List<IssueLabel>, selected: Set<String>, onSelected: (Set<String>) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("标签", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(if (selected.isEmpty()) "全部" else "已选 ${selected.size}", maxLines = 1) }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("清除标签") }, onClick = { onSelected(emptySet()); expanded = false })
            labels.forEach { label -> DropdownMenuItem(text = { Text(label.name) }, onClick = { onSelected(if (label.name in selected) selected - label.name else selected + label.name) }, leadingIcon = { Checkbox(label.name in selected, null) }) }
        }
    }
}

@Composable
private fun MoreFilterMenu(state: RepoIssueListUiState, vm: RepoIssueListViewModel, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("更多筛选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("里程碑 / 指派人", maxLines = 1) }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("全部里程碑") }, onClick = { vm.setMilestone(RepoMilestoneFilter.ALL); expanded = false })
            DropdownMenuItem(text = { Text("无里程碑") }, onClick = { vm.setMilestone(RepoMilestoneFilter.NONE); expanded = false })
            state.milestones.forEach { m -> DropdownMenuItem(text = { Text(m.title) }, onClick = { vm.setMilestone(RepoMilestoneFilter.Number(m.number)); expanded = false }) }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("全部指派状态") }, onClick = { vm.setAssignee(RepoAssigneeFilter.ALL); expanded = false })
            DropdownMenuItem(text = { Text("已指派给任何人") }, onClick = { vm.setAssignee(RepoAssigneeFilter.ANY); expanded = false })
            DropdownMenuItem(text = { Text("无人指派") }, onClick = { vm.setAssignee(RepoAssigneeFilter.NONE); expanded = false })
            state.assignableUsers.forEach { user -> DropdownMenuItem(text = { Text(user.login) }, onClick = { vm.setAssignee(RepoAssigneeFilter.Login(user.login)); expanded = false }) }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("全部创建者") }, onClick = { vm.setAuthor(RepoAuthorFilter.ALL); expanded = false })
            state.assignableUsers.forEach { user -> DropdownMenuItem(text = { Text("创建者 @${user.login}") }, onClick = { vm.setAuthor(RepoAuthorFilter.Login(user.login)); expanded = false }) }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(if (state.filter.mentioned) "取消：被提及" else "被提及") }, onClick = { vm.setMentioned(!state.filter.mentioned); expanded = false }, leadingIcon = { Checkbox(state.filter.mentioned, null) })
            DropdownMenuItem(text = { Text(if (state.filter.subscribed) "取消：已订阅" else "已订阅") }, onClick = { vm.setSubscribed(!state.filter.subscribed); expanded = false }, leadingIcon = { Checkbox(state.filter.subscribed, null) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("全部更新时间") }, onClick = { vm.setUpdatedSince(null); expanded = false })
            DropdownMenuItem(text = { Text("最近 7 天更新") }, onClick = { vm.setUpdatedSince(java.time.Instant.now().minusSeconds(7L * 24 * 60 * 60)); expanded = false })
            DropdownMenuItem(text = { Text("最近 30 天更新") }, onClick = { vm.setUpdatedSince(java.time.Instant.now().minusSeconds(30L * 24 * 60 * 60)); expanded = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IssueSwipeRow(issue: RepoIssue, canDelete: Boolean, onDelete: () -> Unit, onClick: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { value -> if (value == SwipeToDismissBoxValue.EndToStart && canDelete) onDelete(); false })
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = canDelete,
        backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.onError) } },
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
        val meta = listOfNotNull(issue.milestone?.let { "里程碑 ${it.title}" }, issue.assignees.takeIf { it.isNotEmpty() }?.joinToString { "@${it.login}" }, if (issue.locked) "已锁定" else null).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun LabelPill(label: IssueLabel) { val fallback = MaterialTheme.colorScheme.secondaryContainer; val color = remember(label.color, fallback) { runCatching { Color(android.graphics.Color.parseColor("#${label.color}")) }.getOrDefault(fallback) }; Text(label.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(color.copy(alpha = .22f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) }

@Composable private fun ErrorState(retry: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("加载失败"); Button(retry, Modifier.padding(top = 12.dp)) { Text("重试") } } }

@Composable
private fun IssueEditorDialog(template: IssueTemplate?, labels: List<IssueLabel>, milestones: List<IssueMilestone>, assignees: List<SimpleUser>, onDismiss: () -> Unit, onSubmit: (String, String, List<String>, List<String>, String?) -> Unit) {
    var title by remember(template) { mutableStateOf(template?.title.orEmpty()) }
    var body by remember(template) { mutableStateOf(template?.body.orEmpty()) }
    var selectedLabels by remember(template, labels) { mutableStateOf(labels.filter { it.name in template?.labels.orEmpty() }.map { it.id }.toSet()) }
    var selectedAssignees by remember(template, assignees) { mutableStateOf(assignees.filter { it.login in template?.assignees.orEmpty() }.mapNotNull { it.id }.toSet()) }
    var selectedMilestone by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建议题") },
        text = { LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(body, { body = it }, label = { Text("Markdown 正文") }, minLines = 6, modifier = Modifier.fillMaxWidth()) }
            if (labels.isNotEmpty()) { item { Text("标签", fontWeight = FontWeight.SemiBold) }; items(labels, key = { "create-label-${it.id}" }) { label -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(label.id in selectedLabels, { checked -> selectedLabels = if (checked) selectedLabels + label.id else selectedLabels - label.id }); Text(label.name) } } }
            item { Text("里程碑", fontWeight = FontWeight.SemiBold) }
            item { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedMilestone == null, { selectedMilestone = null }); Text("无里程碑") } }
            items(milestones, key = { "create-milestone-${it.id}" }) { milestone -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedMilestone == milestone.id, { selectedMilestone = milestone.id }); Text(milestone.title) } }
            if (assignees.isNotEmpty()) { item { Text("指派人", fontWeight = FontWeight.SemiBold) }; items(assignees, key = { "create-assignee-${it.login}" }) { user -> val id = user.id; if (id != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(id in selectedAssignees, { checked -> selectedAssignees = if (checked) selectedAssignees + id else selectedAssignees - id }); Text(user.login) } } }
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { Button(onClick = { onSubmit(title, body, selectedLabels.toList(), selectedAssignees.toList(), selectedMilestone) }, enabled = title.isNotBlank()) { Text("创建") } },
    )
}

private fun sortLabel(value: RepoIssueSort) = when (value) {
    RepoIssueSort.UPDATED_DESC -> "最近更新"; RepoIssueSort.UPDATED_ASC -> "最早更新"; RepoIssueSort.CREATED_DESC -> "最新创建"; RepoIssueSort.CREATED_ASC -> "最早创建"; RepoIssueSort.COMMENTS_DESC -> "评论最多"; RepoIssueSort.COMMENTS_ASC -> "评论最少"
}
