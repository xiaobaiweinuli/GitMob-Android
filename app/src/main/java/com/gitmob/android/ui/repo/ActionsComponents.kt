@file:OptIn(ExperimentalMaterial3Api::class)

package com.gitmob.android.ui.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.gitmob.android.api.*
import com.gitmob.android.ui.common.*
import com.gitmob.android.ui.theme.*
import com.gitmob.android.util.LogManager
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.defaultMinSize
import okhttp3.Request

/**
 * 对Issues列表进行筛选和排序
 */


@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * Actions标签页组件
 * 
 * @param state 仓库详情状态
 * @param c 颜色主题
 * @param vm 仓库详情ViewModel
 * @param owner 仓库所有者
 * @param repoName 仓库名称
 * @param onRefresh 刷新回调
 */
fun ActionsTab(
    state: RepoDetailState,
    c: GmColors,
    vm: RepoDetailViewModel,
    owner: String,
    repoName: String,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    var showDispatchDialog by remember { mutableStateOf<GHWorkflow?>(null) }
    var showDeleteDialog by remember { mutableStateOf<GHWorkflowRun?>(null) }
    var workflowsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(showDispatchDialog) {
        showDispatchDialog?.let { workflow ->
            vm.loadWorkflowInputs(workflow.path)
        } ?: vm.clearWorkflowInputs()
    }

    LaunchedEffect(state.selectedWorkflow) {
        if (state.selectedWorkflow == null) {
            workflowsExpanded = false
        }
    }

    PullToRefreshBox(
        isRefreshing = state.actionsRefreshing,
        onRefresh = onRefresh,
    ) {
        if (state.selectedWorkflow != null) {
            // 工作流详情页面
            Column(Modifier.fillMaxSize()) {
                // 顶部返回栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        null,
                        tint = c.textPrimary,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { vm.clearSelectedWorkflow() }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            state.selectedWorkflow.name,
                            fontSize = 16.sp,
                            color = c.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            state.selectedWorkflow.path,
                            fontSize = 11.sp,
                            color = c.textTertiary
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.selectedWorkflow.state == "active") {
                        Button(
                            onClick = { showDispatchDialog = state.selectedWorkflow },
                            colors = ButtonDefaults.buttonColors(containerColor = Coral)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("运行")
                        }
                    }
                }
                GmDivider()

                // 运行记录列表
                val listState1 = androidx.compose.foundation.lazy.rememberLazyListState()
                val isAtBottom1 = remember {
                    derivedStateOf {
                        val last = listState1.layoutInfo.visibleItemsInfo.lastOrNull()
                        last != null && last.index >= listState1.layoutInfo.totalItemsCount - 3
                    }
                }
                LaunchedEffect(isAtBottom1.value) {
                    if (isAtBottom1.value && state.workflowRunsHasMore && !state.workflowRunsLoadingMore) {
                        vm.loadMoreWorkflowRuns()
                    }
                }
                
                LazyColumn(
                    state = listState1,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.workflowRuns.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                Text("暂无运行记录", fontSize = 13.sp, color = c.textTertiary)
                            }
                        }
                    } else {
                        items(state.workflowRuns, key = { it.id }) { run ->
                            WorkflowRunItem(
                                run = run,
                                c = c,
                                onClick = { vm.selectWorkflowRun(run) },
                                onRerun = { vm.rerunWorkflow(run.id) },
                                onCancel = { vm.cancelWorkflow(run.id) },
                                onDelete = { showDeleteDialog = run },
                                isArchived = isArchived
                            )
                        }
                        if (state.workflowRunsLoadingMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Coral)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 默认页面：显示所有工作流 + 所有运行记录
            Column(Modifier.fillMaxSize()) {
                // 工作流列表
                if (state.workflows.isNotEmpty()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("工作流", fontSize = 12.sp, color = c.textTertiary, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        val visibleWorkflows = if (workflowsExpanded || state.workflows.size <= 2) {
                            state.workflows
                        } else {
                            state.workflows.take(2)
                        }
                        visibleWorkflows.forEach { workflow ->
                            WorkflowItem(
                                workflow = workflow,
                                c = c,
                                onDispatch = { showDispatchDialog = workflow },
                                onClick = { vm.selectWorkflow(workflow) },
                                onRefresh = { vm.loadWorkflows() },
                                isArchived = isArchived
                            )
                        }
                        if (state.workflows.size > 2) {
                            TextButton(
                                onClick = { workflowsExpanded = !workflowsExpanded },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (workflowsExpanded) "收起" else "显示全部 (${state.workflows.size})",
                                    color = Coral
                                )
                            }
                        }
                    }
                    GmDivider()
                }

                // 运行记录列表
                val listState2 = androidx.compose.foundation.lazy.rememberLazyListState()
                val isAtBottom2 = remember {
                    derivedStateOf {
                        val last = listState2.layoutInfo.visibleItemsInfo.lastOrNull()
                        last != null && last.index >= listState2.layoutInfo.totalItemsCount - 3
                    }
                }
                LaunchedEffect(isAtBottom2.value) {
                    if (isAtBottom2.value && state.workflowRunsHasMore && !state.workflowRunsLoadingMore) {
                        vm.loadMoreWorkflowRuns()
                    }
                }
                
                LazyColumn(
                    state = listState2,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    item {
                        Text("运行记录", fontSize = 12.sp, color = c.textTertiary, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (state.workflowRuns.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                Text("暂无运行记录", fontSize = 13.sp, color = c.textTertiary)
                            }
                        }
                    } else {
                        items(state.workflowRuns, key = { it.id }) { run ->
                            WorkflowRunItem(
                                run = run,
                                c = c,
                                onClick = { vm.selectWorkflowRun(run) },
                                onRerun = { vm.rerunWorkflow(run.id) },
                                onCancel = { vm.cancelWorkflow(run.id) },
                                onDelete = { showDeleteDialog = run },
                                isArchived = isArchived
                            )
                        }
                        if (state.workflowRunsLoadingMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Coral)
                                }
                            }
                        }
                    }
                }
            }
        }

    }

    // 触发工作流对话框
    showDispatchDialog?.let { workflow ->
        DispatchWorkflowDialog(
            workflow = workflow,
            currentBranch = state.currentBranch,
            branches = state.branches.map { it.name },
            inputs = state.workflowInputs,
            inputsLoading = state.workflowInputsLoading,
            c = c,
            onConfirm = { ref, inputs ->
                vm.dispatchWorkflow(workflow.id, ref, inputs)
                showDispatchDialog = null
            },
            onDismiss = { showDispatchDialog = null }
        )
    }

    // 删除运行记录对话框
    showDeleteDialog?.let { run ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = c.bgCard,
            title = { Text("删除运行记录", color = c.textPrimary) },
            text = { Text("确认删除此运行记录？此操作不可撤销。", color = c.textSecondary) },
            confirmButton = {
                Button(onClick = {
                    vm.deleteWorkflowRun(run.id)
                    showDeleteDialog = null
                }, colors = ButtonDefaults.buttonColors(containerColor = RedColor)) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消", color = c.textSecondary)
                }
            }
        )
    }

    // 工作流运行详情 Modal
    state.selectedWorkflowRun?.let { run ->
        WorkflowRunDetailSheet(
            run = run,
            jobs = state.workflowJobs,
            artifacts = state.workflowArtifacts,
            c = c,
            vm = vm,
            onDismiss = { vm.clearSelectedWorkflowRun() },
            onRerun = { vm.rerunWorkflow(run.id) },
            onCancel = { vm.cancelWorkflow(run.id) }
        )
    }
}

@Composable
fun WorkflowItem(
    workflow: GHWorkflow,
    c: GmColors,
    onDispatch: () -> Unit,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
    isArchived: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (workflow.state == "active") Icons.Default.PlayArrow else Icons.Default.Pause,
            null,
            tint = if (workflow.state == "active") Green else c.textTertiary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(workflow.name, fontSize = 13.sp, color = c.textPrimary)
            Text(workflow.path, fontSize = 11.sp, color = c.textTertiary)
        }
        if (workflow.state == "active") {
            IconButton(
                onClick = onDispatch, 
                modifier = Modifier.size(28.dp),
                enabled = !isArchived
            ) {
                Icon(
                    Icons.Default.PlayCircle, 
                    null, 
                    tint = if (isArchived) c.textTertiary else Coral, 
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun WorkflowRunItem(
    run: GHWorkflowRun,
    c: GmColors,
    onClick: () -> Unit,
    onRerun: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    isArchived: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }
    val statusColor = when (run.status) {
        "completed" -> when (run.conclusion) {
            "success" -> Green
            "failure" -> RedColor
            "cancelled" -> c.textTertiary
            else -> Yellow
        }
        "in_progress" -> BlueColor
        "queued" -> Yellow
        else -> c.textTertiary
    }
    val statusText = when (run.status) {
        "completed" -> run.conclusion ?: "completed"
        "in_progress" -> "运行中"
        "queued" -> "排队中"
        else -> run.status ?: "unknown"
    }

    val durationText = remember(run.runStartedAt, run.updatedAt, run.status, run.conclusion) {
        getDurationOrStatus(run.runStartedAt, run.updatedAt, run.status, run.conclusion)
    }
    val timeText = remember(run.createdAt) {
        formatTime(run.createdAt)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(statusColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(run.displayTitle ?: run.name ?: "工作流运行", fontSize = 13.sp, color = c.textPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            GmBadge(statusText, statusColor.copy(alpha = 0.15f), statusColor)
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(
                    onClick = { showMenu = true }, 
                    modifier = Modifier.size(28.dp),
                    enabled = !isArchived
                ) {
                    Icon(
                        Icons.Default.MoreVert, 
                        null, 
                        tint = if (isArchived) c.textTertiary.copy(alpha = 0.38f) else c.textTertiary, 
                        modifier = Modifier.size(16.dp)
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(c.bgCard)) {
                    if (run.status == "completed") {
                        ArchivedAwareDropdownMenuItem(
                            text = { Text("重新运行", fontSize = 13.sp, color = c.textPrimary) },
                            isArchived = isArchived,
                            leadingIcon = { Icon(Icons.Default.Refresh, null, tint = BlueColor, modifier = Modifier.size(16.dp)) },
                            onClick = { onRerun(); showMenu = false }
                        )
                    }
                    if (run.status == "in_progress") {
                        ArchivedAwareDropdownMenuItem(
                            text = { Text("取消", fontSize = 13.sp, color = RedColor) },
                            isArchived = isArchived,
                            leadingIcon = { Icon(Icons.Default.Close, null, tint = RedColor, modifier = Modifier.size(16.dp)) },
                            onClick = { onCancel(); showMenu = false }
                        )
                    }
                    ArchivedAwareDropdownMenuItem(
                        text = { Text("删除", fontSize = 13.sp, color = RedColor) },
                        isArchived = isArchived,
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = RedColor, modifier = Modifier.size(16.dp)) },
                        onClick = { onDelete(); showMenu = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GmBadge(run.headBranch ?: "unknown", BlueDim, BlueColor)
            Text((run.headSha ?: "").take(7), fontSize = 11.sp, color = c.textTertiary, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text(
                durationText,
                fontSize = 11.sp,
                color = c.textTertiary
            )
            Text(timeText, fontSize = 11.sp, color = c.textTertiary)
            run.actor?.let { actor ->
                Text(actor.login, fontSize = 11.sp, color = c.textTertiary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchWorkflowDialog(
    workflow: GHWorkflow,
    currentBranch: String,
    branches: List<String>,
    inputs: List<WorkflowInput>,
    inputsLoading: Boolean,
    c: GmColors,
    onConfirm: (String, Map<String, Any>?) -> Unit,
    onDismiss: () -> Unit
) {
    var ref by remember { mutableStateOf(currentBranch) }
    val inputValues = remember { mutableStateMapOf<String, Any>() }
    var branchExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(inputs) {
        inputValues.clear()
        inputs.forEach { input ->
            input.default?.let { inputValues[input.name] = it }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("触发 ${workflow.name}", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                ExposedDropdownMenuBox(
                    expanded = branchExpanded,
                    onExpandedChange = { branchExpanded = !branchExpanded }
                ) {
                    OutlinedTextField(
                        value = ref,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("分支/标签") },
                        singleLine = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = branchExpanded
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Coral,
                            unfocusedBorderColor = c.border,
                            focusedTextColor = c.textPrimary,
                            unfocusedTextColor = c.textPrimary,
                            focusedContainerColor = c.bgItem,
                            unfocusedContainerColor = c.bgItem,
                            focusedLabelColor = Coral,
                            unfocusedLabelColor = c.textTertiary,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = branchExpanded,
                        onDismissRequest = { branchExpanded = false }
                    ) {
                        branches.forEach { branch ->
                            DropdownMenuItem(
                                text = { Text(branch, color = c.textPrimary) },
                                onClick = {
                                    ref = branch
                                    branchExpanded = false
                                }
                            )
                        }
                    }
                }

                if (inputsLoading) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Coral)
                    }
                } else if (inputs.isNotEmpty()) {
                    Text("输入参数", fontSize = 12.sp, color = c.textTertiary, fontWeight = FontWeight.Medium)
                    inputs.forEach { input ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "${input.name}${if (input.required) " *" else ""}",
                                fontSize = 13.sp,
                                color = c.textPrimary,
                                fontWeight = FontWeight.Medium
                            )
                            input.description?.let {
                                Text(
                                    text = it,
                                    fontSize = 11.sp,
                                    color = c.textTertiary
                                )
                            }

                            when {
                                input.options != null && input.options.isNotEmpty() || input.type == "choice" -> {
                                    var expanded by remember { mutableStateOf(false) }
                                    val selectedValue = inputValues[input.name]?.toString() ?: input.default?.toString() ?: ""
                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = !expanded }
                                    ) {
                                        OutlinedTextField(
                                            value = selectedValue,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text("选择值") },
                                            trailingIcon = {
                                                ExposedDropdownMenuDefaults.TrailingIcon(
                                                    expanded = expanded
                                                )
                                            },
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Coral,
                                                unfocusedBorderColor = c.border,
                                                focusedTextColor = c.textPrimary,
                                                unfocusedTextColor = c.textPrimary,
                                                focusedContainerColor = c.bgItem,
                                                unfocusedContainerColor = c.bgItem,
                                                focusedLabelColor = Coral,
                                                unfocusedLabelColor = c.textTertiary,
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                        )
                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            input.options?.forEach { option ->
                                                DropdownMenuItem(
                                                    text = { Text(option, color = c.textPrimary) },
                                                    onClick = {
                                                        inputValues[input.name] = option
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                input.type == "boolean" -> {
                                    val currentValue = (inputValues[input.name] as? Boolean) ?: (input.default as? Boolean) ?: false
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(
                                            checked = currentValue,
                                            onCheckedChange = { inputValues[input.name] = it },
                                            colors = CheckboxDefaults.colors(checkedColor = Coral)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (currentValue) "是" else "否", color = c.textPrimary)
                                    }
                                }
                                input.type == "number" -> {
                                    OutlinedTextField(
                                        value = (inputValues[input.name] as? String) ?: (input.default as? String) ?: "",
                                        onValueChange = { inputValues[input.name] = it },
                                        label = { Text("输入数字") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Coral,
                                            unfocusedBorderColor = c.border,
                                            focusedTextColor = c.textPrimary,
                                            unfocusedTextColor = c.textPrimary,
                                            focusedContainerColor = c.bgItem,
                                            unfocusedContainerColor = c.bgItem,
                                            focusedLabelColor = Coral,
                                            unfocusedLabelColor = c.textTertiary,
                                        ),
                                    )
                                }
                                else -> {
                                    OutlinedTextField(
                                        value = (inputValues[input.name] as? String) ?: (input.default as? String) ?: "",
                                        onValueChange = { inputValues[input.name] = it },
                                        label = { Text("输入值") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Coral,
                                            unfocusedBorderColor = c.border,
                                            focusedTextColor = c.textPrimary,
                                            unfocusedTextColor = c.textPrimary,
                                            focusedContainerColor = c.bgItem,
                                            unfocusedContainerColor = c.bgItem,
                                            focusedLabelColor = Coral,
                                            unfocusedLabelColor = c.textTertiary,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val filteredInputs = inputValues.filterValues { it.toString().isNotEmpty() }
                    onConfirm(ref, filteredInputs.ifEmpty { null })
                },
                colors = ButtonDefaults.buttonColors(containerColor = Coral)
            ) {
                Text("触发")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowRunDetailSheet(
    run: GHWorkflowRun,
    jobs: List<GHWorkflowJob>,
    artifacts: List<GHWorkflowArtifact>,
    c: GmColors,
    vm: RepoDetailViewModel,
    onDismiss: () -> Unit,
    onRerun: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val statusColor = when (run.status) {
        "completed" -> when (run.conclusion) {
            "success" -> Green
            "failure" -> RedColor
            "cancelled" -> c.textTertiary
            else -> Yellow
        }
        "in_progress" -> BlueColor
        "queued" -> Yellow
        else -> c.textTertiary
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(run.displayTitle ?: run.name ?: "工作流运行", fontSize = 16.sp, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
            }

            // 信息
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GmBadge(run.headBranch ?: "unknown", BlueDim, BlueColor)
                Text((run.headSha ?: "").take(7), fontSize = 12.sp, color = c.textTertiary, fontFamily = FontFamily.Monospace)
            }

            // 操作按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(run.htmlUrl))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = c.bgItem, contentColor = c.textPrimary)
                ) {
                    Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("浏览器打开")
                }
                if (run.status == "completed") {
                    Button(
                        onClick = onRerun,
                        colors = ButtonDefaults.buttonColors(containerColor = Coral)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重新运行")
                    }
                }
                if (run.status == "in_progress") {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = RedColor)
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("取消")
                    }
                }
            }

            GmDivider()

            // Job 列表
            Text("Jobs", fontSize = 12.sp, color = c.textTertiary, fontWeight = FontWeight.Medium)
            if (jobs.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Text("暂无 Job 信息", fontSize = 13.sp, color = c.textTertiary)
                }
            } else {
                jobs.forEach { job ->
                    JobCard(
                        job = job,
                        c = c
                    )
                }
            }

            if (artifacts.isNotEmpty()) {
                GmDivider()
                Text("Artifacts", fontSize = 12.sp, color = c.textTertiary, fontWeight = FontWeight.Medium)
                artifacts.forEach { artifact ->
                    ArtifactCard(
                        artifact = artifact,
                        c = c,
                        vm = vm
                    )
                }
            }
        }
    }
}

@Composable
fun JobCard(job: GHWorkflowJob, c: GmColors) {
    val statusColor = when (job.status) {
        "completed" -> when (job.conclusion) {
            "success" -> Green
            "failure" -> RedColor
            "cancelled" -> c.textTertiary
            else -> Yellow
        }
        "in_progress" -> BlueColor
        "queued" -> Yellow
        else -> c.textTertiary
    }
    val jobStatusText = job.status ?: "unknown"
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgItem, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(job.name ?: "Job", fontSize = 13.sp, color = c.textPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            GmBadge(jobStatusText, statusColor.copy(alpha = 0.15f), statusColor)
            Spacer(Modifier.width(4.dp))
            androidx.compose.animation.AnimatedContent(
                targetState = expanded,
                label = "expand_icon"
            ) { isExpanded ->
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = c.textTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            Column {
                GmDivider()
                Spacer(Modifier.height(8.dp))
                job.steps?.let { steps ->
                    steps.forEach { step ->
                        StepRow(
                            step = step,
                            c = c,
                            jobHtmlUrl = job.htmlUrl
                        )
                    }
                } ?: Text("暂无步骤信息", fontSize = 12.sp, color = c.textTertiary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun ArtifactCard(
    artifact: GHWorkflowArtifact,
    c: GmColors,
    vm: RepoDetailViewModel
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val sizeText = formatFileSize(artifact.sizeInBytes)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgItem, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Inventory2,
                null,
                tint = BlueColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                artifact.name,
                fontSize = 13.sp,
                color = c.textPrimary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(sizeText, fontSize = 11.sp, color = c.textTertiary)
            Spacer(Modifier.width(8.dp))
            androidx.compose.animation.AnimatedContent(
                targetState = expanded,
                label = "expand_icon"
            ) { isExpanded ->
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = c.textTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            Column {
                GmDivider()
                Spacer(Modifier.height(8.dp))
                if (artifact.expired) {
                    Text(
                        "此产物已过期",
                        fontSize = 12.sp,
                        color = c.textTertiary,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // ── 下载按钮（带进度背景）──
                        val taskKey    = "artifact_${artifact.id}"
                        val taskId     = vm.state.collectAsState().value.downloadTaskIds[taskKey]
                        val dlStatus   = taskId?.let {
                            com.gitmob.android.util.GmDownloadManager.statusOf(it)
                        }?.collectAsState()?.value
                        val dlPct      = (dlStatus as? com.gitmob.android.util.DownloadStatus.Progress)?.percent ?: 0
                        val isDownload = dlStatus is com.gitmob.android.util.DownloadStatus.Progress
                        val isDone     = dlStatus is com.gitmob.android.util.DownloadStatus.Success
                        val downloadedFile = (dlStatus as? com.gitmob.android.util.DownloadStatus.Success)?.file

                        Box(modifier = Modifier.weight(1f)) {
                            // 进度背景层
                            if (isDownload && dlPct > 0)
                                Box(modifier = Modifier
                                    .fillMaxWidth(dlPct / 100f)
                                    .matchParentSize()
                                    .background(BlueColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp)))
                            Button(
                                onClick = {
                                    if (isDone && downloadedFile != null) {
                                        val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(
                                                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", downloadedFile),
                                                "*/*"
                                            )
                                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(openIntent)
                                    } else {
                                        vm.downloadArtifact(artifact)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        isDone     -> Green
                                        isDownload -> BlueColor.copy(alpha = 0.7f)
                                        else       -> BlueColor
                                    }
                                ),
                            ) {
                                Icon(
                                    when {
                                        isDone     -> Icons.Default.CheckCircle
                                        isDownload -> Icons.Default.Close
                                        else       -> Icons.Default.Download
                                    },
                                    null, modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    when {
                                        isDone     -> "点击打开"
                                        isDownload -> "$dlPct%"
                                        else       -> "下载"
                                    },
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Button(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = RedColor)
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("删除", fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        DeleteArtifactDialog(
            artifactName = artifact.name,
            artifactSize = sizeText,
            onConfirm = {
                vm.deleteArtifact(artifact.id)
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
            c = c
        )
    }
}

@Composable
fun DeleteArtifactDialog(
    artifactName: String,
    artifactSize: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    c: GmColors,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        icon = {
            Icon(Icons.Default.DeleteForever, null, tint = RedColor, modifier = Modifier.size(28.dp))
        },
        title = { Text("删除产物", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bgItem, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Inventory2,
                        null,
                        tint = BlueColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        artifactName,
                        fontSize = 13.sp,
                        color = c.textPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    "大小：$artifactSize",
                    fontSize = 13.sp,
                    color = c.textSecondary,
                )
                Text(
                    "此操作不可撤销。确认要删除这个产物吗？",
                    fontSize = 13.sp,
                    color = c.textTertiary,
                    lineHeight = 20.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = RedColor)
            ) {
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

@Composable
fun StepRow(step: GHWorkflowStep, c: GmColors, jobHtmlUrl: String) {
    val context = LocalContext.current
    val statusColor = when (step.status) {
        "completed" -> when (step.conclusion) {
            "success" -> Green
            "failure" -> RedColor
            "cancelled" -> c.textTertiary
            else -> Yellow
        }
        "in_progress" -> BlueColor
        "queued" -> Yellow
        else -> c.textTertiary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(statusColor, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${step.number}. ${step.name ?: "Step"}",
            fontSize = 12.sp,
            color = c.textSecondary,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = {
                val stepUrl = "$jobHtmlUrl#step:${step.number}:1"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(stepUrl))
                context.startActivity(intent)
            },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                null,
                tint = c.textTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ─── Commit Detail Modal ───────────────────────────────────────────

fun getDurationOrStatus(runStartedAt: String?, updatedAt: String?, status: String?, conclusion: String?): String {
    if (status == "completed") {
        return try {
            val start = runStartedAt?.let {
                try {
                    OffsetDateTime.parse(it)
                } catch (e: Exception) {
                    null
                }
            }
            
            val end = updatedAt?.let {
                try {
                    OffsetDateTime.parse(it)
                } catch (e: Exception) {
                    null
                }
            }
            
            if (start != null && end != null) {
                val duration = java.time.Duration.between(start, end)
                val seconds = duration.seconds
                
                if (seconds > 0) {
                    val hours = duration.toHours()
                    val minutes = duration.toMinutes() % 60
                    val secs = duration.seconds % 60
                    
                    return when {
                        hours > 0 -> "${hours}h ${minutes}m"
                        minutes > 0 -> "${minutes}m ${secs}s"
                        else -> "${secs}秒"
                    }
                }
            }
            
            getStatusText(conclusion ?: status)
        } catch (e: Exception) {
            getStatusText(conclusion ?: status)
        }
    } else {
        return getStatusText(status)
    }
}

/**
 * 获取状态文本
 */
fun getStatusText(status: String?): String {
    return when (status) {
        "in_progress" -> "正在进行"
        "queued" -> "排队中"
        "requested" -> "请求中"
        "waiting" -> "等待中"
        "pending" -> "待处理"
        "action_required" -> "需要操作"
        "cancelled" -> "已取消"
        "failure" -> "失败"
        "neutral" -> "中立"
        "skipped" -> "已跳过"
        "stale" -> "过时"
        "success" -> "成功"
        "timed_out" -> "超时"
        else -> status ?: "-"
    }
}

/**
 * 格式化运行时间
 */
fun formatTime(createdAt: String): String {
    return try {
        val dateTime = OffsetDateTime.parse(createdAt)
        val now = OffsetDateTime.now()
        val duration = java.time.Duration.between(dateTime, now)
        
        when {
            duration.toMinutes() < 1 -> "刚刚"
            duration.toHours() < 1 -> "${duration.toMinutes()}分钟前"
            duration.toDays() < 1 -> "${duration.toHours()}小时前"
            duration.toDays() < 7 -> "${duration.toDays()}天前"
            else -> {
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                dateTime.format(formatter)
            }
        }
    } catch (e: Exception) {
        createdAt
    }
}

// ─── 订阅通知 Sheet ─────────────────────────────────────────────────────────

