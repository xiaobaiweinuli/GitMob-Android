package com.gitmob.app.ui.repoactions

import androidx.compose.foundation.selection.selectableGroup
import com.gitmob.app.ui.common.ExpansionArrow
import com.gitmob.app.ui.common.formatDateTime
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import com.gitmob.app.ui.common.ActionButtonContent
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.ActionProgressIcon
import com.gitmob.app.ui.common.ActionStateIcon
import com.gitmob.app.ui.common.AppDropdownMenu as DropdownMenu
import com.gitmob.app.ui.common.AppDropdownMenuItem as DropdownMenuItem
import com.gitmob.app.ui.common.ListFeedback
import com.gitmob.app.ui.common.ListFeedbackState
import com.gitmob.app.ui.common.RepositoryContextTitle
import com.gitmob.app.ui.common.informationItem
import com.gitmob.app.ui.common.pressClickable
import com.gitmob.app.ui.common.pressToggleable
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoActionsScreen(owner: String, name: String, permission: RepoPermission?, defaultRef: String?, isArchived: Boolean?, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, onRunClick: (Long) -> Unit, viewModel: RepoActionsViewModel = hiltViewModel()) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name, permission, defaultRef, isArchived) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { RepositoryContextTitle(owner, name, stringResource(R.string.repo_actions), onOwnerClick, onRepositoryClick) },
                navigationIcon = { ActionIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = { ActionsOverflowMenu(state, viewModel::onActionsMenuOpened, viewModel::shareActions, viewModel::setRepositoryActionsEnabled) },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SecondaryTabRow(tab) { Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.actions_runs)) }); Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.actions_workflows)) }) }
            PullToRefreshBox(state.isLoading && (state.runs.isNotEmpty() || state.workflows.isNotEmpty()), viewModel::refresh, Modifier.fillMaxSize()) {
                val feedback = when {
                    state.isLoading && state.runs.isEmpty() && state.workflows.isEmpty() -> ListFeedbackState.LOADING
                    state.loadFailed -> ListFeedbackState.ERROR
                    tab == 0 && state.runs.isEmpty() -> ListFeedbackState.EMPTY
                    tab == 1 && state.workflows.isEmpty() -> ListFeedbackState.EMPTY
                    else -> ListFeedbackState.CONTENT
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp,
                        bottom = 8.dp + WindowInsets.navigationBars.union(WindowInsets.captionBar)
                            .asPaddingValues().calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (feedback == ListFeedbackState.CONTENT) {
                        if (tab == 0) {
                            items(state.runs, key = { "run:${it.id}" }) { run ->
                                Box(Modifier.animateItem().informationItem()) {
                                    SwipeableRunRow(run, state.capabilities.canDeleteActionRuns, run.id in state.deletingRunIds,
                                        { onRunClick(run.id) }, { viewModel.requestDelete(run) })
                                }
                            }
                            if (state.hasNextPage) item(key = "more") {
                                LaunchedEffect(state.runs.size) { viewModel.loadMore() }
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
                            }
                        } else {
                            items(state.workflows, key = { "workflow:${it.id}" }) { workflow ->
                                Box(Modifier.animateItem().informationItem()) {
                                    WorkflowRow(workflow, state.capabilities.canPush, state.canDispatch, workflow.id in state.updatingWorkflowIds, { viewModel.prepareDispatch(workflow) },
                                        { enabled -> viewModel.setWorkflowEnabled(workflow, enabled) })
                                }
                            }
                        }
                    }

                }
                ListFeedback(feedback, viewModel::refresh,
                    emptyMessage = stringResource(if (tab == 0) R.string.actions_empty_runs else R.string.actions_empty_workflows))
            }
        }
    }
    state.dispatchWorkflow?.let { workflow ->
        DispatchDialog(workflow, state, viewModel::loadDispatchInputs, viewModel::dismissDispatch,
            viewModel::dispatch)
    }
    state.pendingDeleteRun?.let { run ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.actions_delete_run)) },
            text = { Text(stringResource(R.string.actions_delete_run_message, run.runNumber, run.displayTitle)) },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } },
            confirmButton = { TextButton(onClick = viewModel::deletePendingRun, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRunRow(run: RepoWorkflowRun, canDelete: Boolean, deleting: Boolean, click: () -> Unit, delete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dismissState = rememberSwipeToDismissBoxState()
    val deleteEnabled = canDelete && !deleting
    val deleteLabel = stringResource(R.string.actions_delete_run)
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = deleteEnabled,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && deleteEnabled) delete()
            scope.launch { dismissState.reset() }
        },
        backgroundContent = {
            if (canDelete) Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        RunRow(
            run = run,
            deleting = deleting,
            click = click,
            modifier = Modifier
                .semantics {
                    if (deleteEnabled) customActions = listOf(CustomAccessibilityAction(deleteLabel) { delete(); true })
                }
                .onPreviewKeyEvent { event ->
                    if (deleteEnabled && event.key == Key.Delete && event.type == KeyEventType.KeyUp) {
                        delete()
                        true
                    } else false
                },
        )
    }
}

@Composable
private fun RunRow(run: RepoWorkflowRun, deleting: Boolean, click: () -> Unit, modifier: Modifier) {
    val duration = run.durationSeconds?.let {
        val elapsed = DateUtils.formatElapsedTime(it)
        if (run.status == "completed") elapsed
        else stringResource(R.string.actions_run_elapsed, elapsed)
    }
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).pressClickable(enabled = !deleting, onClick = click).padding(16.dp), verticalAlignment = Alignment.Top) {
        Icon(runIcon(run), null, tint = runColor(run), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(run.displayTitle, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${run.name.orEmpty()} · #${run.runNumber} · ${run.event}", style = MaterialTheme.typography.bodySmall)
            Text(
                listOfNotNull(run.headBranch ?: run.headSha.take(7), formatDateTime(run.createdAt), duration).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(actionStateLabel(run.conclusion ?: run.status), style = MaterialTheme.typography.labelMedium)
            if (deleting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
@Composable
private fun ActionsOverflowMenu(
    state: RepoActionsUiState,
    opened: () -> Unit,
    share: () -> Unit,
    setEnabled: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val shareLabel = stringResource(R.string.conversation_share)
    val shareTextWidth = rememberTextMeasurer().measure(AnnotatedString(shareLabel), MaterialTheme.typography.bodyLarge).size.width
    val shareMenuWidth = with(LocalDensity.current) { shareTextWidth.toDp() + 32.dp }.coerceAtLeast(48.dp)
    Box {
        ActionIconButton(onClick = { expanded = true; opened() }) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.common_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(shareLabel, style = MaterialTheme.typography.bodyLarge) },
                onClick = { expanded = false; share() },
                modifier = if (state.capabilities.canManageRepositoryActions) Modifier else Modifier.width(shareMenuWidth),
            )
            if (state.capabilities.canManageRepositoryActions) {
                val label = when {
                    state.pendingRepositoryActionsEnabled == true -> R.string.actions_repository_enabling
                    state.pendingRepositoryActionsEnabled == false -> R.string.actions_repository_disabling
                    state.repositoryActionsEnabled == true -> R.string.actions_repository_disable
                    state.repositoryActionsEnabled == false -> R.string.actions_repository_enable
                    else -> R.string.actions_repository_unknown
                }
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = { expanded = false; setEnabled(state.repositoryActionsEnabled != true) },
                    enabled = state.repositoryActionsEnabled != null && state.pendingRepositoryActionsEnabled == null &&
                        !state.isLoadingRepositoryActions && !state.repositoryActionsNeedsConfirmation && !state.isDispatching,
                )
            }
        }
    }
}

@Composable
private fun WorkflowRow(workflow: RepoWorkflow, canManage: Boolean, canDispatch: Boolean, busy: Boolean, dispatch: () -> Unit, enable: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.AccountTree, null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(workflow.name, fontWeight = FontWeight.SemiBold)
            Text(workflow.path, style = MaterialTheme.typography.bodySmall)
            Text(workflowStateLabel(workflow.state), style = MaterialTheme.typography.labelSmall)
        }
        if (canManage) {
            ActionIconButton(onClick = dispatch, enabled = !busy && workflow.state == "active" && canDispatch) {
                Icon(Icons.Default.PlayArrow, stringResource(R.string.actions_run_workflow))
            }
            IconToggleButton(
                checked = workflow.state == "active",
                onCheckedChange = enable,
                shapes = IconButtonDefaults.toggleableShapes(),
                colors = IconButtonDefaults.iconToggleButtonColors(),
                enabled = !busy,
            ) {
                val label = stringResource(if (workflow.state == "active") R.string.actions_disable_workflow else R.string.actions_enable_workflow)
                ActionProgressIcon(busy, busyDescription = label) {
                    ActionStateIcon(if (workflow.state == "active") Icons.Default.Pause else Icons.Default.PlayCircle, label)
                }
            }
        }
    }
}

@Composable
private fun DispatchDialog(
    workflow: RepoWorkflow,
    state: RepoActionsUiState,
    loadInputs: (String) -> Unit,
    dismiss: () -> Unit,
    dispatch: (String, Map<String, String>) -> Unit,
) {
    var ref by remember(workflow.id) { mutableStateOf(state.dispatchRef) }
    val busy = state.isDispatching
    val loading = state.isLoadingInputs
    val definitions = state.dispatchInputs
    val values = remember(workflow.id, definitions) {
        mutableStateMapOf<String, String>().apply { definitions.forEach { put(it.name, it.defaultValue.orEmpty()) } }
    }
    val valid = definitions.all { input ->
        val value = values[input.name].orEmpty()
        (!input.required || value.isNotBlank()) && (input.type != WorkflowDispatchInputType.NUMBER || value.isBlank() || value.toDoubleOrNull() != null)
    }
    val definitionsCurrent = state.dispatchInputsRef == ref.trim()
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(R.string.actions_run_workflow)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text(workflow.name, fontWeight = FontWeight.SemiBold) }
                item { OutlinedTextField(ref, { ref = it }, enabled = !busy, label = { Text(stringResource(R.string.actions_ref)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) }
                if (loading || !definitionsCurrent) item {
                    Button(onClick = { loadInputs(ref) }, enabled = !busy && !loading && ref.isNotBlank(), modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) {
                        ActionButtonContent(busy = loading, label = stringResource(R.string.actions_load_inputs), icon = Icons.Default.Refresh)
                    }
                }
                if (definitionsCurrent) items(definitions, key = { it.name }) { input ->
                    DispatchInputField(input, values[input.name].orEmpty(), enabled = !busy) { values[input.name] = it }
                }
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = {
            Button(onClick = { dispatch(ref.trim(), values.toMap()) }, enabled = state.canDispatch && !busy && !loading && definitionsCurrent && ref.isNotBlank() && valid, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) {
                ActionButtonContent(busy = busy, label = stringResource(R.string.actions_run_workflow))
            }
        },
    )
}

@Composable
private fun DispatchInputField(input: WorkflowDispatchInput, value: String, enabled: Boolean, change: (String) -> Unit) {
    when (input.type) {
        WorkflowDispatchInputType.BOOLEAN -> {
            val checked = value.equals("true", true)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressToggleable(checked, enabled = enabled, role = Role.Checkbox) { change(it.toString()) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, onCheckedChange = null, enabled = enabled)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(input.name)
                    input.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        WorkflowDispatchInputType.CHOICE -> {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedTextField(value, {}, enabled = enabled, label = { Text(input.name + if (input.required) " *" else "") }, supportingText = { input.description?.let { Text(it) } }, readOnly = true,
                    trailingIcon = { ActionIconButton(onClick = { open = !open }, enabled = enabled) { ExpansionArrow(open) } },
                    modifier = Modifier.fillMaxWidth().pressClickable(enabled = enabled) { open = true }, shape = MaterialTheme.shapes.medium)
                DropdownMenu(open && enabled, { open = false }, modifier = Modifier.selectableGroup()) {
                    input.options.forEachIndexed { index, option ->
                        SelectableDropdownMenuItem(
                            selected = value == option,
                            shapes = MenuDefaults.itemShape(index, input.options.size),
                            text = { Text(option) },
                            onClick = { open = false; change(option) },
                            leadingIcon = { Spacer(Modifier.size(24.dp)) },
                            selectedLeadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                        )
                    }
                }
            }
        }
        else -> OutlinedTextField(value, change, enabled = enabled, label = { Text(input.name + if (input.required) " *" else "") }, supportingText = { input.description?.let { Text(it) } }, singleLine = input.type == WorkflowDispatchInputType.ENVIRONMENT || input.type == WorkflowDispatchInputType.NUMBER, keyboardOptions = if (input.type == WorkflowDispatchInputType.NUMBER) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoWorkflowRunScreen(
    owner: String, name: String, runId: Long, permission: RepoPermission?, isArchived: Boolean?,
    onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit,
    onStepClick: (RepoActionStepLogKey) -> Unit,
    viewModel: RepoWorkflowRunViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(owner, name, runId) { viewModel.init(owner, name, runId, permission, isArchived) }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelLogDownload() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var focusedStep by rememberSaveable(owner, name, runId) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { RepositoryContextTitle(owner, name, stringResource(R.string.actions_run_number, runId), onOwnerClick, onRepositoryClick) },
                navigationIcon = { ActionIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    if (state.detail != null) Box {
                        ActionIconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.issue_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_share)) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; viewModel.share() })
                            if (state.capabilities.canPush) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.actions_cancel)) }, enabled = !state.isMutating, onClick = { menu = false; viewModel.cancel() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.actions_force_cancel)) }, enabled = !state.isMutating, onClick = { menu = false; viewModel.cancel(true) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.actions_rerun)) }, enabled = !state.isMutating, onClick = { menu = false; viewModel.requestRerun(false) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.actions_rerun_failed)) }, enabled = !state.isMutating, onClick = { menu = false; viewModel.requestRerun(true) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }, enabled = !state.isMutating, onClick = { menu = false; viewModel.confirmDelete(true) })
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        PullToRefreshBox(state.isLoading && state.detail != null, viewModel::load, Modifier.fillMaxSize().padding(padding)) {
            val detail = state.detail
            when {
                state.isLoading && detail == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.loadFailed -> ActionsRetry(viewModel::load)
                detail != null -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item(key = "summary") {
                        val run = detail.run
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(run.displayTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text(listOf(actionStateLabel(run.status), actionStateLabel(run.conclusion)).filter(String::isNotBlank).joinToString(" / "))
                            Text("${run.event} · ${run.headBranch ?: run.headSha.take(7)} · #${run.runNumber}${run.runAttempt?.let { ".$it" }.orEmpty()}")
                        }
                    }
                    if (detail.artifacts.isNotEmpty()) {
                        item(key = "artifacts") { Text(stringResource(R.string.actions_artifacts), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
                        items(detail.artifacts, key = { "artifact-${it.id}" }) { artifact ->
                            val opening = artifact.id in state.openingArtifactIds
                            androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                ListItem(content = { Text(artifact.name) }, supportingContent = { Text(Formatter.formatShortFileSize(context, artifact.sizeInBytes)) }, trailingContent = {
                                ActionIconButton(onClick = { viewModel.downloadArtifact(artifact) }, enabled = !artifact.expired && !opening) {
                                    ActionProgressIcon(busy = opening, busyDescription = stringResource(R.string.common_download)) { Icon(Icons.Default.Download, stringResource(R.string.common_download)) }
                                }
                            },
    shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large), colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                )
                            }
                        }
                    }
                    item(key = "jobs") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.actions_jobs), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            ActionLogTooltip(stringResource(R.string.actions_logs_download_zip)) {
                                TextButton(onClick = viewModel::downloadLogs, enabled = !state.openingLogs && !state.isLoading, modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                                    ActionProgressIcon(busy = state.openingLogs, busyDescription = stringResource(R.string.actions_logs_download_zip)) { Icon(Icons.Default.Download, stringResource(R.string.actions_logs_download_zip)) }
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.actions_logs_zip_label))
                                }
                            }
                        }
                    }
                    detail.jobs.forEach { job ->
                        item(key = "job-${job.id}") {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (job.conclusion == "success") Icons.Default.CheckCircle else Icons.Default.RadioButtonChecked, null)
                                Spacer(Modifier.width(8.dp))
                                Text(job.name, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        items(job.steps, key = { "step-${job.id}-${it.number}" }) { step ->
                            val identity = "${job.id}:${step.number}"
                            val focusRequester = remember { FocusRequester() }
                            LaunchedEffect(focusedStep) { if (focusedStep == identity) focusRequester.requestFocus() }
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).informationItem().focusRequester(focusRequester).pressClickable(onClick = {
                                    focusedStep = identity
                                    onStepClick(RepoActionStepLogKey(owner, name, runId, detail.run.runAttempt, job.id, step.number))
                                }).heightIn(min = 48.dp).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("${step.number}. ${step.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(actionStateLabel(step.conclusion ?: step.status), style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(max = 100.dp))
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                            }
                        }
                        item(key = "divider-${job.id}") { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                    }
                }
            }
        }
    }
    state.pendingRerunFailedOnly?.let { failedOnly ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRerun,
            title = { Text(stringResource(if (failedOnly) R.string.actions_rerun_failed else R.string.actions_rerun)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (failedOnly) R.string.actions_rerun_failed_scope else R.string.actions_rerun_all_scope))
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressToggleable(state.enableDebugLogging, enabled = !state.isMutating, role = Role.Checkbox, onValueChange = viewModel::setDebugLogging), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.enableDebugLogging, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.actions_debug_logging))
                    }
                    Text(stringResource(R.string.actions_debug_logging_description), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmRerun, enabled = !state.isMutating, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = state.isMutating, label = stringResource(R.string.common_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissRerun, enabled = !state.isMutating, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (state.pendingDelete) AlertDialog(
        onDismissRequest = { viewModel.confirmDelete(false) },
        title = { Text(stringResource(R.string.actions_delete_run_title)) },
        text = { Text(stringResource(R.string.common_cannot_be_undone)) },
        dismissButton = { TextButton(onClick = { viewModel.confirmDelete(false) }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = { TextButton(onClick = { viewModel.delete(onBack) }, enabled = !state.isMutating, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = state.isMutating, label = stringResource(R.string.common_delete)) } },
    )
}

@Composable private fun ActionsRetry(retry: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = retry, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_retry)) } } }
@Composable private fun runColor(run: RepoWorkflowRun) = when (run.conclusion) { "success" -> MaterialTheme.colorScheme.primary; "failure", "cancelled" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.tertiary }
private fun runIcon(run: RepoWorkflowRun) = when (run.conclusion) { "success" -> Icons.Default.CheckCircle; "failure" -> Icons.Default.Cancel; "cancelled" -> Icons.Default.StopCircle; else -> Icons.Default.Schedule }
@Composable private fun actionStateLabel(value: String?): String = when (value) { null, "" -> ""; "queued" -> stringResource(R.string.actions_state_queued); "in_progress" -> stringResource(R.string.actions_state_in_progress); "completed" -> stringResource(R.string.actions_state_completed); "waiting" -> stringResource(R.string.actions_state_waiting); "requested" -> stringResource(R.string.actions_state_requested); "pending" -> stringResource(R.string.actions_state_pending); "success" -> stringResource(R.string.actions_conclusion_success); "failure" -> stringResource(R.string.actions_conclusion_failure); "neutral" -> stringResource(R.string.actions_conclusion_neutral); "cancelled" -> stringResource(R.string.actions_conclusion_cancelled); "skipped" -> stringResource(R.string.actions_conclusion_skipped); "timed_out" -> stringResource(R.string.actions_conclusion_timed_out); "action_required" -> stringResource(R.string.actions_conclusion_action_required); "startup_failure" -> stringResource(R.string.actions_conclusion_startup_failure); "stale" -> stringResource(R.string.actions_conclusion_stale); else -> value }
@Composable private fun workflowStateLabel(value: String): String = stringResource(when (value) { "active" -> R.string.actions_workflow_active; "disabled_inactivity" -> R.string.actions_workflow_disabled_inactivity; "disabled_fork" -> R.string.actions_workflow_disabled_fork; "deleted" -> R.string.actions_workflow_deleted; else -> R.string.actions_workflow_disabled_manually })
