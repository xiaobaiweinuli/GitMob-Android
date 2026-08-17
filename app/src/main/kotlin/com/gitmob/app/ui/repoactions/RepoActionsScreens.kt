package com.gitmob.app.ui.repoactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoActionsScreen(owner: String, name: String, permission: RepoPermission?, defaultRef: String?, onBack: () -> Unit, onRunClick: (Long) -> Unit, viewModel: RepoActionsViewModel = hiltViewModel()) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name, permission, defaultRef) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.repo_actions)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) }, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SecondaryTabRow(tab) { Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.actions_runs)) }); Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.actions_workflows)) }) }
            PullToRefreshBox(state.isLoading && (state.runs.isNotEmpty() || state.workflows.isNotEmpty()), viewModel::refresh, Modifier.fillMaxSize()) {
                when {
                    state.isLoading && state.runs.isEmpty() && state.workflows.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.loadFailed -> ActionsRetry(viewModel::load)
                    tab == 0 && state.runs.isEmpty() -> Text(stringResource(R.string.actions_empty_runs), modifier = Modifier.align(Alignment.Center))
                    tab == 1 && state.workflows.isEmpty() -> Text(stringResource(R.string.actions_empty_workflows), modifier = Modifier.align(Alignment.Center))
                    tab == 0 -> LazyColumn { items(state.runs, key = { it.id }) { run -> RunRow(run) { onRunClick(run.id) }; HorizontalDivider() }; if (state.hasNextPage) item { LaunchedEffect(state.runs.size) { viewModel.loadMore() }; Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) } } }
                    else -> LazyColumn { items(state.workflows, key = { it.id }) { workflow -> WorkflowRow(workflow, state.capabilities.canPush, { viewModel.prepareDispatch(workflow) }, { enabled -> viewModel.setWorkflowEnabled(workflow, enabled) }); HorizontalDivider() } }
                }
            }
        }
    }
    state.dispatchWorkflow?.let { workflow -> DispatchDialog(workflow, state.dispatchInputs, state.dispatchRef, state.dispatchInputsRef, state.isLoadingInputs, viewModel::loadDispatchInputs, viewModel::dismissDispatch, viewModel::dispatch) }
}

@Composable private fun RunRow(run: RepoWorkflowRun, click: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(16.dp), verticalAlignment = Alignment.Top) { Icon(runIcon(run), null, tint = runColor(run), modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(run.displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${run.name.orEmpty()} · #${run.runNumber} · ${run.event}", style = MaterialTheme.typography.bodySmall); Text("${run.headBranch ?: run.headSha.take(7)} · ${run.createdAt.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(actionStateLabel(run.conclusion ?: run.status), style = MaterialTheme.typography.labelMedium) } }
@Composable private fun WorkflowRow(workflow: RepoWorkflow, canManage: Boolean, dispatch: () -> Unit, enable: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AccountTree, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(workflow.name, fontWeight = FontWeight.SemiBold); Text(workflow.path, style = MaterialTheme.typography.bodySmall); Text(workflowStateLabel(workflow.state), style = MaterialTheme.typography.labelSmall) }; if (canManage) { IconButton(onClick = dispatch, enabled = workflow.state == "active") { Icon(Icons.Default.PlayArrow, stringResource(R.string.actions_run_workflow)) }; IconButton(onClick = { enable(workflow.state != "active") }) { Icon(if (workflow.state == "active") Icons.Default.Pause else Icons.Default.PlayCircle, stringResource(if (workflow.state == "active") R.string.actions_disable_workflow else R.string.actions_enable_workflow)) } } } }

@Composable
private fun DispatchDialog(workflow: RepoWorkflow, definitions: List<WorkflowDispatchInput>, initialRef: String, loadedRef: String?, loading: Boolean, loadInputs: (String) -> Unit, dismiss: () -> Unit, dispatch: (String, Map<String, String>) -> Unit) {
    var ref by remember(workflow) { mutableStateOf(initialRef) }
    val values = remember(workflow, definitions) { mutableStateMapOf<String, String>().apply { definitions.forEach { put(it.name, it.defaultValue.orEmpty()) } } }
    val valid = definitions.all { input ->
        val value = values[input.name].orEmpty()
        (!input.required || value.isNotBlank()) && (input.type != WorkflowDispatchInputType.NUMBER || value.isBlank() || value.toDoubleOrNull() != null)
    }
    val definitionsCurrent = loadedRef == ref.trim()
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.actions_run_workflow)) }, text = { LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text(workflow.name, fontWeight = FontWeight.SemiBold) }; item { OutlinedTextField(ref, { ref = it }, label = { Text(stringResource(R.string.actions_ref)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }; if (loading) item { Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } } else if (!definitionsCurrent) item { Button(onClick = { loadInputs(ref) }, enabled = ref.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.actions_load_inputs)) } }; if (definitionsCurrent) items(definitions, key = { it.name }) { input -> DispatchInputField(input, values[input.name].orEmpty()) { values[input.name] = it } } } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { Button(onClick = { dispatch(ref.trim(), values.toMap()) }, enabled = !loading && definitionsCurrent && ref.isNotBlank() && valid) { Text(stringResource(R.string.actions_run_workflow)) } })
}

@Composable private fun DispatchInputField(input: WorkflowDispatchInput, value: String, change: (String) -> Unit) { when (input.type) { WorkflowDispatchInputType.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(value.equals("true", true), { change(it.toString()) }); Column { Text(input.name); input.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) } } }; WorkflowDispatchInputType.CHOICE -> { var open by remember { mutableStateOf(false) }; Box { OutlinedTextField(value, {}, label = { Text(input.name + if (input.required) " *" else "") }, supportingText = { input.description?.let { Text(it) } }, readOnly = true, trailingIcon = { IconButton(onClick = { open = true }) { Icon(Icons.Default.ArrowDropDown, null) } }, modifier = Modifier.fillMaxWidth().clickable { open = true }); DropdownMenu(open, { open = false }) { input.options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { open = false; change(option) }) } } } }; else -> OutlinedTextField(value, change, label = { Text(input.name + if (input.required) " *" else "") }, supportingText = { input.description?.let { Text(it) } }, singleLine = input.type == WorkflowDispatchInputType.ENVIRONMENT || input.type == WorkflowDispatchInputType.NUMBER, keyboardOptions = if (input.type == WorkflowDispatchInputType.NUMBER) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default, modifier = Modifier.fillMaxWidth()) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoWorkflowRunScreen(owner: String, name: String, runId: Long, permission: RepoPermission?, onBack: () -> Unit, viewModel: RepoWorkflowRunViewModel = hiltViewModel()) {
    LaunchedEffect(owner, name, runId) { viewModel.init(owner, name, runId, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.actions_run_number, runId)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, actions = { if (state.detail != null && state.capabilities.canPush) Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.issue_more)) }; DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text(stringResource(R.string.actions_cancel)) }, onClick = { menu = false; viewModel.cancel() }); DropdownMenuItem(text = { Text(stringResource(R.string.actions_force_cancel)) }, onClick = { menu = false; viewModel.cancel(true) }); DropdownMenuItem(text = { Text(stringResource(R.string.actions_rerun)) }, onClick = { menu = false; viewModel.rerun() }); DropdownMenuItem(text = { Text(stringResource(R.string.actions_rerun_failed)) }, onClick = { menu = false; viewModel.rerun(true) }); DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; viewModel.confirmDelete(true) }) } } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) }, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) { padding ->
        when { state.isLoading && state.detail == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; state.loadFailed -> ActionsRetry(viewModel::load); state.detail != null -> LazyColumn(Modifier.fillMaxSize().padding(padding)) { item { val run = state.detail!!.run; val status = actionStateLabel(run.status); val conclusion = actionStateLabel(run.conclusion); Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(run.displayTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text(listOf(status, conclusion).filter(String::isNotBlank).joinToString(" / ")); Text("${run.event} · ${run.headBranch ?: run.headSha.take(7)} · #${run.runNumber}.${run.runAttempt}") } }; if (state.detail!!.artifacts.isNotEmpty()) { item { Text(stringResource(R.string.actions_artifacts), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }; items(state.detail!!.artifacts, key = { it.id }) { artifact -> val opening = artifact.id in state.openingArtifactIds; ListItem(headlineContent = { Text(artifact.name) }, supportingContent = { Text(stringResource(R.string.actions_artifact_size, artifact.sizeInBytes)) }, trailingContent = { IconButton(onClick = { viewModel.downloadArtifact(artifact) }, enabled = !artifact.expired && !opening) { if (opening) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, stringResource(R.string.common_download)) } }) } }; item { Text(stringResource(R.string.actions_jobs), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }; items(state.detail!!.jobs, key = { it.id }) { job -> Column(Modifier.fillMaxWidth().padding(16.dp)) { Row { Icon(if (job.conclusion == "success") Icons.Default.CheckCircle else Icons.Default.RadioButtonChecked, null); Spacer(Modifier.width(8.dp)); Text(job.name, fontWeight = FontWeight.SemiBold) }; job.steps.forEach { step -> Text("${step.number}. ${step.name} · ${actionStateLabel(step.conclusion ?: step.status)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 32.dp, top = 4.dp)) }; HorizontalDivider(Modifier.padding(top = 12.dp)) } } } }
    }
    if (state.pendingDelete) AlertDialog(onDismissRequest = { viewModel.confirmDelete(false) }, title = { Text(stringResource(R.string.actions_delete_run_title)) }, text = { Text(stringResource(R.string.common_cannot_be_undone)) }, dismissButton = { TextButton(onClick = { viewModel.confirmDelete(false) }) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(onClick = { viewModel.delete(onBack) }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } })
}

@Composable private fun ActionsRetry(retry: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = retry) { Text(stringResource(R.string.common_retry)) } } }
@Composable private fun runColor(run: RepoWorkflowRun) = when (run.conclusion) { "success" -> MaterialTheme.colorScheme.primary; "failure", "cancelled" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.tertiary }
private fun runIcon(run: RepoWorkflowRun) = when (run.conclusion) { "success" -> Icons.Default.CheckCircle; "failure" -> Icons.Default.Cancel; "cancelled" -> Icons.Default.StopCircle; else -> Icons.Default.Schedule }
@Composable private fun actionStateLabel(value: String?): String = when (value) { null, "" -> ""; "queued" -> stringResource(R.string.actions_state_queued); "in_progress" -> stringResource(R.string.actions_state_in_progress); "completed" -> stringResource(R.string.actions_state_completed); "waiting" -> stringResource(R.string.actions_state_waiting); "requested" -> stringResource(R.string.actions_state_requested); "pending" -> stringResource(R.string.actions_state_pending); "success" -> stringResource(R.string.actions_conclusion_success); "failure" -> stringResource(R.string.actions_conclusion_failure); "neutral" -> stringResource(R.string.actions_conclusion_neutral); "cancelled" -> stringResource(R.string.actions_conclusion_cancelled); "skipped" -> stringResource(R.string.actions_conclusion_skipped); "timed_out" -> stringResource(R.string.actions_conclusion_timed_out); "action_required" -> stringResource(R.string.actions_conclusion_action_required); "startup_failure" -> stringResource(R.string.actions_conclusion_startup_failure); "stale" -> stringResource(R.string.actions_conclusion_stale); else -> value }
@Composable private fun workflowStateLabel(value: String): String = stringResource(when (value) { "active" -> R.string.actions_workflow_active; "disabled_inactivity" -> R.string.actions_workflow_disabled_inactivity; "disabled_fork" -> R.string.actions_workflow_disabled_fork; "deleted" -> R.string.actions_workflow_deleted; else -> R.string.actions_workflow_disabled_manually })
