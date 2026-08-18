package com.gitmob.app.ui.repopullrequests

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.gitmob.app.data.model.RepoPullRequest
import com.gitmob.app.data.model.RepoPullRequestCreateMetadata

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoPullRequestEditorScreen(
    owner: String,
    name: String,
    number: Int?,
    onBack: () -> Unit,
    onSaved: (Int) -> Unit,
    viewModel: RepoPullRequestEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, number) { viewModel.init(owner, name, number) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(if (number == null) R.string.pr_new else R.string.pr_edit)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> PullRequestEditorRetry(Modifier.fillMaxSize().padding(padding), viewModel::load)
            state.metadata != null -> PullRequestEditor(state.metadata!!, state.existing, state.isSaving, Modifier.fillMaxSize().padding(padding), onBack) { title, body, base, repo, head, draft, labels, assignees, milestone, reviewers -> viewModel.save(title, body, base, repo, head, draft, labels, assignees, milestone, reviewers) { onSaved(it.number) } }
        }
    }
}

@Composable
private fun PullRequestEditor(metadata: RepoPullRequestCreateMetadata, existing: RepoPullRequest?, saving: Boolean, modifier: Modifier, onCancel: () -> Unit, onSave: (String, String, String, String, String, Boolean, List<String>, List<String>, String?, List<String>) -> Unit) {
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember(existing) { mutableStateOf(existing?.body.orEmpty()) }
    var base by remember(existing, metadata) { mutableStateOf(existing?.baseRefName ?: metadata.defaultBranchName.orEmpty()) }
    var headRepo by remember(metadata) { mutableStateOf(metadata.repositories.firstOrNull()?.id.orEmpty()) }
    var head by remember(existing) { mutableStateOf(existing?.headRefName.orEmpty()) }
    var draft by remember(existing) { mutableStateOf(existing?.isDraft ?: false) }
    var selectedLabels by remember(existing) { mutableStateOf(existing?.labels.orEmpty().map { it.id }.toSet()) }
    var selectedAssignees by remember(existing) { mutableStateOf(existing?.assignees.orEmpty().mapNotNull { it.id }.toSet()) }
    var selectedMilestone by remember(existing) { mutableStateOf(existing?.milestone?.id) }
    var selectedReviewers by remember { mutableStateOf(emptySet<String>()) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.issue_editor_title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.issue_editor_body_label)) }, minLines = 8, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(base, { base = it }, label = { Text(stringResource(R.string.pr_base_branch)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Text(stringResource(R.string.pr_head_repository), fontWeight = FontWeight.SemiBold) }
        items(metadata.repositories, key = { it.id }) { repo -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(headRepo == repo.id, { headRepo = repo.id }); Text("${repo.owner}/${repo.name}") } }
        item { OutlinedTextField(head, { head = it }, label = { Text(stringResource(R.string.pr_head_branch)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (existing == null) item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft, { draft = it }); Text(stringResource(R.string.pr_create_draft)) } }
        if (metadata.labels.isNotEmpty()) { item { Text(stringResource(R.string.issue_labels), fontWeight = FontWeight.SemiBold) }; items(metadata.labels, key = { "label-${it.id}" }) { label -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(label.id in selectedLabels, { selectedLabels = if (it) selectedLabels + label.id else selectedLabels - label.id }); Text(label.name) } } }
        item { Text(stringResource(R.string.issue_milestone), fontWeight = FontWeight.SemiBold) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedMilestone == null, { selectedMilestone = null }); Text(stringResource(R.string.issue_no_milestone)) } }
        items(metadata.milestones, key = { "milestone-${it.id}" }) { milestone -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedMilestone == milestone.id, { selectedMilestone = milestone.id }); Text(milestone.title) } }
        if (metadata.assignees.isNotEmpty()) { item { Text(stringResource(R.string.issue_assignees), fontWeight = FontWeight.SemiBold) }; items(metadata.assignees, key = { "assignee-${it.login}" }) { user -> user.id?.let { id -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(id in selectedAssignees, { selectedAssignees = if (it) selectedAssignees + id else selectedAssignees - id }); Text(user.login) } } } }
        if (metadata.reviewers.isNotEmpty()) { item { Text(stringResource(R.string.pr_reviewers), fontWeight = FontWeight.SemiBold) }; items(metadata.reviewers, key = { "reviewer-${it.login}" }) { user -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(user.login in selectedReviewers, { selectedReviewers = if (it) selectedReviewers + user.login else selectedReviewers - user.login }); Text(user.login) } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onCancel, enabled = !saving) { Text(stringResource(R.string.common_cancel)) }; Button(onClick = { onSave(title, body, base, headRepo, head, draft, selectedLabels.toList(), selectedAssignees.toList(), selectedMilestone, selectedReviewers.toList()) }, enabled = title.isNotBlank() && base.isNotBlank() && head.isNotBlank() && !saving) { Text(stringResource(if (saving) R.string.conversation_submitting else R.string.common_save)) } } }
    }
}

@Composable
private fun PullRequestEditorRetry(modifier: Modifier, onRetry: () -> Unit) { Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = onRetry) { Text(stringResource(R.string.common_retry)) } } }
