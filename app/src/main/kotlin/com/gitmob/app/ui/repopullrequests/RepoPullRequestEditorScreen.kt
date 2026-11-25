package com.gitmob.app.ui.repopullrequests

import androidx.compose.animation.animateContentSize
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectableGroup
import com.gitmob.app.ui.common.pressToggleable
import com.gitmob.app.ui.common.pressSelectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.fitInside
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.gitmob.app.ui.common.SoraEditorState
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoPullRequest
import com.gitmob.app.data.model.RepoPullRequestCreateMetadata
import com.gitmob.app.ui.common.ActionButtonContent
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.MarkdownBodyEditor
import com.gitmob.app.ui.common.MarkdownEditorTab
import com.gitmob.app.ui.common.MarkdownEditorUiState
import com.gitmob.app.ui.common.RepositoryContextTitle

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
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    onSaved: (Int) -> Unit,
    viewModel: RepoPullRequestEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, number, baseOwner, baseName, baseRef, headOwner, headName, headRef, headRepositoryId) {
        viewModel.init(owner, name, number, baseOwner, baseName, baseRef, headOwner, headName, headRef, headRepositoryId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { RepositoryContextTitle(owner, name, stringResource(if (number == null) R.string.pr_new else R.string.pr_edit), onOwnerClick, onRepositoryClick) }, navigationIcon = { ActionIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, actions = { TextButton(onClick = { viewModel.save { onSaved(it.number) } }, enabled = state.metadata != null && !state.isLoading && !state.loadFailed && !state.isSaving, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = state.isSaving, label = stringResource(R.string.common_save)) } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) },
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
            state.metadata != null -> PullRequestEditor(
                metadata = state.metadata!!,
                existing = state.existing,
                draft = state.draft,
                bodyState = viewModel.bodyState,
                bodyEditorState = state.bodyEditor,
                modifier = contentModifier,
                onBodyTabSelected = viewModel::selectBodyEditorTab,
                onTitleChange = viewModel::editTitle,
                onBaseChange = viewModel::editBaseRef,
                onDraftChange = viewModel::editDraft,
                onMetadataConfirmed = viewModel::confirmMetadata,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullRequestEditor(
    metadata: RepoPullRequestCreateMetadata,
    existing: RepoPullRequest?,
    draft: RepoPullRequestEditorDraft,
    bodyState: SoraEditorState,
    bodyEditorState: MarkdownEditorUiState,
    modifier: Modifier,
    onBodyTabSelected: (MarkdownEditorTab) -> Unit,
    onTitleChange: (String) -> Unit,
    onBaseChange: (String) -> Unit,
    onDraftChange: (Boolean) -> Unit,
    onMetadataConfirmed: (Set<String>, Set<String>, String?, Set<String>) -> Unit,
) {
    val title = draft.title
    val base = draft.baseRef
    val head = draft.headRef
    val createMode = existing == null
    val selectedLabels = draft.labelIds
    val selectedAssignees = draft.assigneeIds
    val selectedMilestone = draft.milestoneId
    val selectedReviewers = draft.reviewerIds
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
    Column(
        modifier = modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(title, onTitleChange, label = { Text(stringResource(R.string.issue_editor_title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = MaterialTheme.shapes.medium)
        if (!createMode) {
            OutlinedTextField(base, onBaseChange, label = { Text(stringResource(R.string.pr_base_branch)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = MaterialTheme.shapes.medium)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.pr_head_repository), fontWeight = FontWeight.SemiBold)
                Text(existing.headRepositoryNameWithOwner ?: "-", style = MaterialTheme.typography.bodyLarge)
                Text("${stringResource(R.string.pr_head_branch)} · $head", style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (createMode) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp).pressToggleable(draft.isDraft, role = Role.Checkbox, onValueChange = onDraftChange).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.isDraft, onCheckedChange = null); Text(stringResource(R.string.pr_create_draft)) }
        MarkdownBodyEditor(
            textState = bodyState,
            state = bodyEditorState,
            onTabSelected = onBodyTabSelected,
            accessoryContent = {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selectedLabels.isNotEmpty(), { openMetadata(PullRequestMetadataSheet.LABELS) }, shapes = FilterChipDefaults.shapes(), modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec(), alignment = Alignment.CenterStart), label = { Text("${stringResource(R.string.issue_labels)} · ${selectedLabels.size}") })
                    FilterChip(selectedAssignees.isNotEmpty(), { openMetadata(PullRequestMetadataSheet.ASSIGNEES) }, shapes = FilterChipDefaults.shapes(), modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec(), alignment = Alignment.CenterStart), label = { Text("${stringResource(R.string.issue_assignees)} · ${selectedAssignees.size}") })
                    FilterChip(selectedMilestone != null, { openMetadata(PullRequestMetadataSheet.MILESTONE) }, shapes = FilterChipDefaults.shapes(), modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec(), alignment = Alignment.CenterStart), label = { Text("${stringResource(R.string.issue_milestone)} · ${metadata.milestones.firstOrNull { it.id == selectedMilestone }?.title ?: stringResource(R.string.issue_no_milestone)}") })
                    FilterChip(selectedReviewers.isNotEmpty(), { openMetadata(PullRequestMetadataSheet.REVIEWERS) }, shapes = FilterChipDefaults.shapes(), modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec(), alignment = Alignment.CenterStart), label = { Text("${stringResource(R.string.pr_reviewers)} · ${selectedReviewers.size}") })
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
    metadataSheet?.let { sheet ->
        ModalBottomSheet(onDismissRequest = { metadataSheet = null }, sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(when (sheet) { PullRequestMetadataSheet.LABELS -> R.string.issue_labels; PullRequestMetadataSheet.ASSIGNEES -> R.string.issue_assignees; PullRequestMetadataSheet.MILESTONE -> R.string.issue_milestone; PullRequestMetadataSheet.REVIEWERS -> R.string.pr_reviewers }), style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f, fill = false).then(if (sheet == PullRequestMetadataSheet.MILESTONE) Modifier.selectableGroup() else Modifier), contentPadding = PaddingValues(vertical = 12.dp)) {
                    when (sheet) {
                        PullRequestMetadataSheet.LABELS -> items(metadata.labels, key = { "sheet-label-${it.id}" }) { label -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressToggleable(label.id in draftLabels, role = Role.Checkbox, onValueChange = { checked -> draftLabels = if (checked) draftLabels + label.id else draftLabels - label.id }).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(label.id in draftLabels, onCheckedChange = null); Text(label.name) } }
                        PullRequestMetadataSheet.ASSIGNEES -> items(metadata.assignees, key = { "sheet-assignee-${it.login}" }) { user -> user.id?.let { id -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressToggleable(id in draftAssignees, role = Role.Checkbox, onValueChange = { checked -> draftAssignees = if (checked) draftAssignees + id else draftAssignees - id }).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(id in draftAssignees, onCheckedChange = null); Text(user.login) } } }
                        PullRequestMetadataSheet.MILESTONE -> { item { Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressSelectable(draftMilestone == null, role = Role.RadioButton, onClick = { draftMilestone = null }).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(draftMilestone == null, onClick = null); Text(stringResource(R.string.issue_no_milestone)) } }; items(metadata.milestones, key = { "sheet-milestone-${it.id}" }) { milestone -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressSelectable(draftMilestone == milestone.id, role = Role.RadioButton, onClick = { draftMilestone = milestone.id }).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(draftMilestone == milestone.id, onClick = null); Text(milestone.title) } } }
                        PullRequestMetadataSheet.REVIEWERS -> items(metadata.reviewers, key = { "sheet-reviewer-${it.login}" }) { user -> user.id?.let { id -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressToggleable(id in draftReviewers, role = Role.Checkbox, onValueChange = { checked -> draftReviewers = if (checked) draftReviewers + id else draftReviewers - id }).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(id in draftReviewers, onCheckedChange = null); Text(user.login) } } }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { metadataSheet = null }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) }
                    Button(onClick = { onMetadataConfirmed(draftLabels, draftAssignees, draftMilestone, draftReviewers); metadataSheet = null }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_done)) }
                }
            }
        }
    }
}

@Composable
private fun PullRequestEditorRetry(modifier: Modifier, onRetry: () -> Unit) { Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = onRetry, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_retry)) } } }
