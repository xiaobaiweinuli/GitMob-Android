package com.gitmob.app.ui.repopullrequests

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fitInside
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoPullRequest
import com.gitmob.app.data.model.RepoPullRequestCreateMetadata
import com.gitmob.app.ui.common.MarkdownBodyEditor
import com.gitmob.app.ui.common.MarkdownEditorTab
import com.gitmob.app.ui.common.MarkdownEditorUiState

private enum class PullRequestMetadataSheet { LABELS, ASSIGNEES, MILESTONE, REVIEWERS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoPullRequestEditorScreen(
    owner: String,
    name: String,
    number: Int?,
    baseOwner: String? = null,
    baseName: String? = null,
    baseRef: String? = null,
    headOwner: String? = null,
    headName: String? = null,
    headRef: String? = null,
    headRepositoryId: String? = null,
    onBack: () -> Unit,
    onSaved: (Int) -> Unit,
    viewModel: RepoPullRequestEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, number, baseOwner, baseName, baseRef, headOwner, headName, headRef, headRepositoryId) {
        viewModel.init(owner, name, number, baseOwner, baseName, baseRef, headOwner, headName, headRef, headRepositoryId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var saveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(if (number == null) R.string.pr_new else R.string.pr_edit)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, actions = { IconButton(onClick = { saveAction?.invoke() }, enabled = saveAction != null && !state.isSaving) { if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp)) else Text(stringResource(R.string.common_save)) } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .fitInside(WindowInsetsRulers.Ime.current)
        when {
            state.isLoading -> Box(contentModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> PullRequestEditorRetry(contentModifier, viewModel::load)
            state.metadata != null -> PullRequestEditor(state.metadata!!, state.existing, baseOwner, baseName, baseRef, headOwner, headName, headRef, headRepositoryId, state.bodyEditor, contentModifier, viewModel::selectBodyEditorTab, { saveAction = it }) { title, body, base, repo, head, draft, labels, assignees, milestone, reviewers -> viewModel.save(title, body, base, repo, head, draft, labels, assignees, milestone, reviewers) { onSaved(it.number) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullRequestEditor(metadata: RepoPullRequestCreateMetadata, existing: RepoPullRequest?, baseOwner: String?, baseName: String?, baseRefArg: String?, headOwner: String?, headName: String?, headRefArg: String?, headRepositoryIdArg: String?, bodyEditorState: MarkdownEditorUiState, modifier: Modifier, onBodyTabSelected: (MarkdownEditorTab, String) -> Unit, onSaveActionReady: ((() -> Unit) -> Unit), onSave: (String, String, String, String, String, Boolean, List<String>, List<String>, String?, List<String>) -> Unit) {
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember(existing) { mutableStateOf(existing?.body.orEmpty()) }
    var bodyValue by remember(existing) { mutableStateOf(TextFieldValue(body, TextRange(body.length))) }
    val createMode = existing == null
    var base by remember(existing, metadata, baseRefArg) { mutableStateOf(existing?.baseRefName ?: baseRefArg ?: metadata.defaultBranchName.orEmpty()) }
    var headRepo by remember(metadata, headRepositoryIdArg) { mutableStateOf(headRepositoryIdArg ?: metadata.repositories.firstOrNull()?.id.orEmpty()) }
    var head by remember(existing, headRefArg) { mutableStateOf(existing?.headRefName.orEmpty().ifBlank { headRefArg.orEmpty() }) }
    var draft by remember(existing) { mutableStateOf(existing?.isDraft ?: false) }
    var selectedLabels by remember(existing) { mutableStateOf(existing?.labels.orEmpty().map { it.id }.toSet()) }
    var selectedAssignees by remember(existing) { mutableStateOf(existing?.assignees.orEmpty().mapNotNull { it.id }.toSet()) }
    var selectedMilestone by remember(existing) { mutableStateOf(existing?.milestone?.id) }
    var selectedReviewers by remember { mutableStateOf(emptySet<String>()) }
    var metadataSheet by remember { mutableStateOf<PullRequestMetadataSheet?>(null) }
    var draftLabels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draftAssignees by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draftMilestone by remember { mutableStateOf<String?>(null) }
    var draftReviewers by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun openMetadata(sheet: PullRequestMetadataSheet) {
        draftLabels = selectedLabels
        draftAssignees = selectedAssignees
        draftMilestone = selectedMilestone
        draftReviewers = selectedReviewers
        metadataSheet = sheet
    }
    SideEffect {
        onSaveActionReady { onSave(title, body, base, headRepo, head, draft, selectedLabels.toList(), selectedAssignees.toList(), selectedMilestone, selectedReviewers.toList()) }
    }
    Column(
        modifier = modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.issue_editor_title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        if (!createMode) {
            OutlinedTextField(base, { base = it }, label = { Text(stringResource(R.string.pr_base_branch)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.pr_head_repository), fontWeight = FontWeight.SemiBold)
                Text(existing.headRepositoryNameWithOwner ?: "-", style = MaterialTheme.typography.bodyLarge)
                Text("${stringResource(R.string.pr_head_branch)} · $head", style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (createMode) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(draft, { draft = it }); Text(stringResource(R.string.pr_create_draft)) }
        MarkdownBodyEditor(
            value = bodyValue,
            state = bodyEditorState,
            onValueChange = { bodyValue = it; body = it.text; if (bodyEditorState.selectedTab == MarkdownEditorTab.PREVIEW) onBodyTabSelected(MarkdownEditorTab.PREVIEW, it.text) },
            onTabSelected = { onBodyTabSelected(it, bodyValue.text) },
            accessoryContent = {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(false, { openMetadata(PullRequestMetadataSheet.LABELS) }, label = { Text("${stringResource(R.string.issue_labels)} · ${selectedLabels.size}") })
                    FilterChip(false, { openMetadata(PullRequestMetadataSheet.ASSIGNEES) }, label = { Text("${stringResource(R.string.issue_assignees)} · ${selectedAssignees.size}") })
                    FilterChip(false, { openMetadata(PullRequestMetadataSheet.MILESTONE) }, label = { Text("${stringResource(R.string.issue_milestone)} · ${metadata.milestones.firstOrNull { it.id == selectedMilestone }?.title ?: stringResource(R.string.issue_no_milestone)}") })
                    FilterChip(false, { openMetadata(PullRequestMetadataSheet.REVIEWERS) }, label = { Text("${stringResource(R.string.pr_reviewers)} · ${selectedReviewers.size}") })
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
    metadataSheet?.let { sheet ->
        ModalBottomSheet(onDismissRequest = { metadataSheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(when (sheet) { PullRequestMetadataSheet.LABELS -> R.string.issue_labels; PullRequestMetadataSheet.ASSIGNEES -> R.string.issue_assignees; PullRequestMetadataSheet.MILESTONE -> R.string.issue_milestone; PullRequestMetadataSheet.REVIEWERS -> R.string.pr_reviewers }), style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(vertical = 12.dp)) {
                    when (sheet) {
                        PullRequestMetadataSheet.LABELS -> items(metadata.labels, key = { "sheet-label-${it.id}" }) { label -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Checkbox(label.id in draftLabels, { checked -> draftLabels = if (checked) draftLabels + label.id else draftLabels - label.id }); Text(label.name) } }
                        PullRequestMetadataSheet.ASSIGNEES -> items(metadata.assignees, key = { "sheet-assignee-${it.login}" }) { user -> user.id?.let { id -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Checkbox(id in draftAssignees, { checked -> draftAssignees = if (checked) draftAssignees + id else draftAssignees - id }); Text(user.login) } } }
                        PullRequestMetadataSheet.MILESTONE -> { item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(draftMilestone == null, { draftMilestone = null }); Text(stringResource(R.string.issue_no_milestone)) } }; items(metadata.milestones, key = { "sheet-milestone-${it.id}" }) { milestone -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(draftMilestone == milestone.id, { draftMilestone = milestone.id }); Text(milestone.title) } } }
                        PullRequestMetadataSheet.REVIEWERS -> items(metadata.reviewers, key = { "sheet-reviewer-${it.login}" }) { user -> user.id?.let { id -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Checkbox(id in draftReviewers, { checked -> draftReviewers = if (checked) draftReviewers + id else draftReviewers - id }); Text(user.login) } } }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { metadataSheet = null }) { Text(stringResource(R.string.common_cancel)) }
                    Button(onClick = { selectedLabels = draftLabels; selectedAssignees = draftAssignees; selectedMilestone = draftMilestone; selectedReviewers = draftReviewers; metadataSheet = null }) { Text(stringResource(R.string.common_done)) }
                }
            }
        }
    }
}

@Composable
private fun PullRequestEditorRetry(modifier: Modifier, onRetry: () -> Unit) { Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = onRetry) { Text(stringResource(R.string.common_retry)) } } }
