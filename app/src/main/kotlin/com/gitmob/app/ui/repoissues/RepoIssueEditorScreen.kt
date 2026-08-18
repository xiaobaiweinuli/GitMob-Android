package com.gitmob.app.ui.repoissues

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.IssueFormSubmissionBuilder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoIssueEditorScreen(
    owner: String,
    name: String,
    number: Int?,
    templateFilename: String? = null,
    onBack: () -> Unit,
    onSaved: (Int) -> Unit,
    viewModel: RepoIssueEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, number, templateFilename) { viewModel.init(owner, name, number, templateFilename) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (number == null) R.string.issue_new else R.string.issue_edit)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> EditorRetry(Modifier.fillMaxSize().padding(padding), viewModel::load)
            else -> IssueEditor(
                template = state.templates.firstOrNull { it.filename == templateFilename },
                existing = state.existing,
                labels = state.labels,
                milestones = state.milestones,
                assignees = state.assignees,
                saving = state.isSaving,
                modifier = Modifier.fillMaxSize().padding(padding),
                onSave = { title, body, labels, assignees, milestone -> viewModel.save(title, body, labels, assignees, milestone) { onSaved(it.number) } },
            )
        }
    }
}

@Composable
private fun IssueEditor(
    template: IssueTemplate?,
    existing: RepoIssue?,
    labels: List<IssueLabel>,
    milestones: List<IssueMilestone>,
    assignees: List<SimpleUser>,
    saving: Boolean,
    modifier: Modifier,
    onSave: (String, String, List<String>, List<String>, String?) -> Unit,
) {
    var title by remember(template, existing) { mutableStateOf(existing?.title ?: template?.title.orEmpty()) }
    var body by remember(template, existing) { mutableStateOf(existing?.body.orEmpty()) }
    var textValues by remember(template) { mutableStateOf(template?.fields.orEmpty().mapNotNull { field -> when (field) { is IssueFormField.Input -> field.id to field.value.orEmpty(); is IssueFormField.Textarea -> field.id to field.value.orEmpty(); else -> null } }.toMap()) }
    var selections by remember(template) { mutableStateOf(template?.fields.orEmpty().mapNotNull { field -> (field as? IssueFormField.Dropdown)?.defaultIndex?.let { field.id to setOf(it) } }.toMap()) }
    var selectedLabels by remember(template, existing, labels) { mutableStateOf(existing?.labels?.map { it.id }?.toSet() ?: labels.filter { it.name in template?.labels.orEmpty() }.map { it.id }.toSet()) }
    var selectedAssignees by remember(template, existing, assignees) { mutableStateOf(existing?.assignees?.mapNotNull { it.id }?.toSet() ?: assignees.filter { it.login in template?.assignees.orEmpty() }.mapNotNull { it.id }.toSet()) }
    var selectedMilestone by remember(existing) { mutableStateOf(existing?.milestone?.id) }
    val complete = template?.let { IssueFormSubmissionBuilder.isComplete(it, textValues, selections) } ?: true

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.issue_editor_title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (template == null || existing != null) item { OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.issue_editor_body_label)) }, minLines = 8, modifier = Modifier.fillMaxWidth()) }
        else items(template.fields, key = { "form-${it.id ?: template.fields.indexOf(it)}" }) { field ->
            when (field) {
                is IssueFormField.Markdown -> Text(field.value)
                is IssueFormField.Input -> IssueFormTextField(field.label, field.description, field.placeholder, field.required, textValues[field.id].orEmpty(), true) { textValues = textValues + (field.id to it) }
                is IssueFormField.Textarea -> IssueFormTextField(field.label, field.description, field.placeholder, field.required, textValues[field.id].orEmpty(), false) { textValues = textValues + (field.id to it) }
                is IssueFormField.Dropdown -> IssueFormDropdown(field, selections[field.id].orEmpty()) { selections = selections + (field.id to it) }
                is IssueFormField.Checkboxes -> IssueFormCheckboxes(field, selections[field.id].orEmpty()) { selections = selections + (field.id to it) }
            }
        }
        if (labels.isNotEmpty()) { item { SectionTitle(R.string.issue_labels) }; items(labels, key = { "label-${it.id}" }) { label -> SelectionCheckbox(label.name, label.id in selectedLabels) { selectedLabels = if (it) selectedLabels + label.id else selectedLabels - label.id } } }
        item { SectionTitle(R.string.issue_milestone) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedMilestone == null, { selectedMilestone = null }); Text(stringResource(R.string.issue_no_milestone)) } }
        items(milestones, key = { "milestone-${it.id}" }) { milestone -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedMilestone == milestone.id, { selectedMilestone = milestone.id }); Text(milestone.title) } }
        if (assignees.isNotEmpty()) { item { SectionTitle(R.string.issue_assignees) }; items(assignees, key = { "assignee-${it.login}" }) { user -> user.id?.let { id -> SelectionCheckbox(user.login, id in selectedAssignees) { selectedAssignees = if (it) selectedAssignees + id else selectedAssignees - id } } } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { onSave(title, template?.let { IssueFormSubmissionBuilder.build(it, textValues, selections) } ?: body, selectedLabels.toList(), selectedAssignees.toList(), selectedMilestone) },
                    enabled = title.isNotBlank() && complete && !saving,
                ) { Text(stringResource(if (saving) R.string.conversation_submitting else R.string.common_save)) }
            }
        }
    }
}

@Composable private fun IssueFormTextField(label: String, description: String?, placeholder: String?, required: Boolean, value: String, singleLine: Boolean, onValueChange: (String) -> Unit) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { OutlinedTextField(value, onValueChange, label = { Text(if (required) stringResource(R.string.issue_form_required_label, label) else label) }, placeholder = placeholder?.let { { Text(it) } }, singleLine = singleLine, minLines = if (singleLine) 1 else 4, modifier = Modifier.fillMaxWidth()); description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun IssueFormDropdown(field: IssueFormField.Dropdown, selected: Set<Int>, onSelected: (Set<Int>) -> Unit) { Column { Text(if (field.required) stringResource(R.string.issue_form_required_label, field.label) else field.label, fontWeight = FontWeight.SemiBold); field.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }; field.options.forEachIndexed { index, option -> Row(verticalAlignment = Alignment.CenterVertically) { if (field.multiple) Checkbox(index in selected, { onSelected(if (it) selected + index else selected - index) }) else RadioButton(index in selected, { onSelected(setOf(index)) }); Text(option) } } } }
@Composable private fun IssueFormCheckboxes(field: IssueFormField.Checkboxes, selected: Set<Int>, onSelected: (Set<Int>) -> Unit) { Column { Text(field.label, fontWeight = FontWeight.SemiBold); field.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }; field.options.forEachIndexed { index, option -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(index in selected, { onSelected(if (it) selected + index else selected - index) }); Text(if (option.required) stringResource(R.string.issue_form_required_label, option.label) else option.label) } } } }
@Composable private fun SelectionCheckbox(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, onChecked); Text(label) } }
@Composable private fun SectionTitle(id: Int) { Text(stringResource(id), fontWeight = FontWeight.SemiBold) }
@Composable private fun EditorRetry(modifier: Modifier, retry: () -> Unit) { Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = retry) { Text(stringResource(R.string.common_retry)) } } }
