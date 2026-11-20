package com.gitmob.app.ui.repoissues

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.fitInside
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.net.toUri
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.IssueFormSubmissionBuilder
import com.gitmob.app.ui.common.MarkdownWebView
import com.gitmob.app.ui.common.MarkdownBodyEditor
import com.gitmob.app.ui.common.MarkdownEditorTab
import com.gitmob.app.ui.common.MarkdownEditorUiState
import com.gitmob.app.ui.common.RepositoryContextTitle

private enum class IssueMetadataSheet { LABELS, ASSIGNEES, MILESTONE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoIssueEditorScreen(
    owner: String,
    name: String,
    number: Int?,
    templateFilename: String? = null,
    onBack: () -> Unit,
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    onSaved: (Int) -> Unit,
    onLinkClick: (String) -> Unit,
    viewModel: RepoIssueEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, number, templateFilename) { viewModel.init(owner, name, number, templateFilename) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var saveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    RepositoryContextTitle(
                        owner = owner,
                        repository = name,
                        pageTitle = stringResource(if (number == null) R.string.issue_new else R.string.issue_edit),
                        onOwnerClick = onOwnerClick,
                        onRepositoryClick = onRepositoryClick,
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = { saveAction?.invoke() }, enabled = saveAction != null && !state.isSaving) {
                        if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp)) else Text(stringResource(R.string.common_save))
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .fitInside(WindowInsetsRulers.Ime.current)
        when {
            state.isLoading -> Box(contentModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> EditorRetry(contentModifier, viewModel::load)
            else -> IssueEditor(
                template = state.templates.firstOrNull { it.filename == templateFilename },
                templateHtml = state.templateHtml,
                onLinkClick = onLinkClick,
                existing = state.existing,
                labels = state.labels,
                milestones = state.milestones,
                assignees = state.assignees,
                bodyEditorState = state.bodyEditor,
                saving = state.isSaving,
                modifier = contentModifier,
                onBodyTabSelected = viewModel::selectBodyEditorTab,
                onSave = { title, body, labels, assignees, milestone -> viewModel.save(title, body, labels, assignees, milestone) { onSaved(it.number) } },
                onSaveActionReady = { saveAction = it },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IssueEditor(
    template: IssueTemplate?,
    templateHtml: Map<String, String>,
    onLinkClick: (String) -> Unit,
    existing: RepoIssue?,
    labels: List<IssueLabel>,
    milestones: List<IssueMilestone>,
    assignees: List<SimpleUser>,
    bodyEditorState: MarkdownEditorUiState,
    saving: Boolean,
    modifier: Modifier,
    onBodyTabSelected: (MarkdownEditorTab, String) -> Unit,
    onSave: (String, String, List<String>, List<String>, String?) -> Unit,
    onSaveActionReady: ((() -> Unit) -> Unit),
) {
    var title by remember(template, existing) { mutableStateOf(existing?.title ?: template?.title.orEmpty()) }
    var body by remember(template, existing) { mutableStateOf(existing?.body.orEmpty()) }
    var bodyValue by remember(template, existing) { mutableStateOf(TextFieldValue(body, TextRange(body.length))) }
    var textValues by remember(template) { mutableStateOf(template?.fields.orEmpty().mapNotNull { field -> when (field) { is IssueFormField.Input -> field.id to field.value.orEmpty(); is IssueFormField.Textarea -> field.id to field.value.orEmpty(); else -> null } }.toMap()) }
    var selections by remember(template) { mutableStateOf(template?.fields.orEmpty().mapNotNull { field -> (field as? IssueFormField.Dropdown)?.defaultIndex?.let { field.id to setOf(it) } }.toMap()) }
    var selectedLabels by remember(template, existing, labels) { mutableStateOf(existing?.labels?.map { it.id }?.toSet() ?: labels.filter { it.name in template?.labels.orEmpty() }.map { it.id }.toSet()) }
    var selectedAssignees by remember(template, existing, assignees) { mutableStateOf(existing?.assignees?.mapNotNull { it.id }?.toSet() ?: assignees.filter { it.login in template?.assignees.orEmpty() }.mapNotNull { it.id }.toSet()) }
    var selectedMilestone by remember(existing) { mutableStateOf(existing?.milestone?.id) }
    var metadataSheet by remember { mutableStateOf<IssueMetadataSheet?>(null) }
    var draftLabels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draftAssignees by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draftMilestone by remember { mutableStateOf<String?>(null) }
    fun openMetadata(sheet: IssueMetadataSheet) {
        draftLabels = selectedLabels
        draftAssignees = selectedAssignees
        draftMilestone = selectedMilestone
        metadataSheet = sheet
    }
    val complete = template?.let { IssueFormSubmissionBuilder.isComplete(it, textValues, selections) } ?: true
    SideEffect {
        onSaveActionReady {
            onSave(title, template?.let { IssueFormSubmissionBuilder.build(it, textValues, selections) } ?: body, selectedLabels.toList(), selectedAssignees.toList(), selectedMilestone)
        }
    }

    if (template == null || existing != null) {
        Column(
            modifier = modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                title,
                { title = it },
                label = { Text(stringResource(R.string.issue_editor_title_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            MarkdownBodyEditor(
                value = bodyValue,
                state = bodyEditorState,
                onValueChange = { bodyValue = it; body = it.text; if (bodyEditorState.selectedTab == MarkdownEditorTab.PREVIEW) onBodyTabSelected(MarkdownEditorTab.PREVIEW, it.text) },
                onTabSelected = { onBodyTabSelected(it, bodyValue.text) },
                accessoryContent = {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetadataChip(stringResource(R.string.issue_labels), selectedLabels.size) { openMetadata(IssueMetadataSheet.LABELS) }
                        MetadataChip(stringResource(R.string.issue_assignees), selectedAssignees.size) { openMetadata(IssueMetadataSheet.ASSIGNEES) }
                        MetadataChip(stringResource(R.string.issue_milestone), value = milestones.firstOrNull { it.id == selectedMilestone }?.title ?: stringResource(R.string.issue_no_milestone)) { openMetadata(IssueMetadataSheet.MILESTONE) }
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.issue_editor_title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            itemsIndexed(template.fields, key = { index, field -> issueFormFieldKey(index, field) }) { _, field ->
                IssueFormFieldContent(
                    field = field,
                    templateHtml = templateHtml,
                    value = textValues[field.id].orEmpty(),
                    selected = selections[field.id].orEmpty(),
                    onValueChange = { value -> field.id?.let { textValues = textValues + (it to value) } },
                    onSelected = { selected -> field.id?.let { selections = selections + (it to selected) } },
                    onLinkClick = onLinkClick,
                )
            }
        }
    }
    metadataSheet?.let { sheet ->
        ModalBottomSheet(
            onDismissRequest = { metadataSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    text = when (sheet) {
                        IssueMetadataSheet.LABELS -> stringResource(R.string.issue_labels)
                        IssueMetadataSheet.ASSIGNEES -> stringResource(R.string.issue_assignees)
                        IssueMetadataSheet.MILESTONE -> stringResource(R.string.issue_milestone)
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(vertical = 12.dp)) {
                    when (sheet) {
                        IssueMetadataSheet.LABELS -> items(labels, key = { "sheet-label-${it.id}" }) { label ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(label.id in draftLabels, { checked -> draftLabels = if (checked) draftLabels + label.id else draftLabels - label.id })
                                Text(label.name)
                            }
                        }
                        IssueMetadataSheet.ASSIGNEES -> items(assignees, key = { "sheet-assignee-${it.login}" }) { user ->
                            user.id?.let { id -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(id in draftAssignees, { checked -> draftAssignees = if (checked) draftAssignees + id else draftAssignees - id })
                                Text(user.login)
                            } }
                        }
                        IssueMetadataSheet.MILESTONE -> {
                            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(draftMilestone == null, { draftMilestone = null }); Text(stringResource(R.string.issue_no_milestone))
                            } }
                            items(milestones, key = { "sheet-milestone-${it.id}" }) { milestone ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(draftMilestone == milestone.id, { draftMilestone = milestone.id }); Text(milestone.title)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { selectedLabels = draftLabels; selectedAssignees = draftAssignees; selectedMilestone = draftMilestone; metadataSheet = null }) { Text(stringResource(R.string.common_done)) }
                }
            }
        }
    }
}

internal fun issueFormFieldKey(index: Int, field: IssueFormField): String =
    if (field is IssueFormField.Markdown) "form-markdown-$index" else "form-field-${field.id}"

@Composable
internal fun IssueFormFieldContent(
    field: IssueFormField,
    templateHtml: Map<String, String>,
    value: String,
    selected: Set<Int>,
    onValueChange: (String) -> Unit,
    onSelected: (Set<Int>) -> Unit,
    onLinkClick: (String) -> Unit,
) {
    val description = when (field) {
        is IssueFormField.Markdown -> null
        is IssueFormField.Input -> field.description
        is IssueFormField.Textarea -> field.description
        is IssueFormField.Dropdown -> field.description
        is IssueFormField.Checkboxes -> field.description
    }
    val descriptionHtml = description?.takeIf(String::isNotBlank)?.let(templateHtml::getValue)
    when (field) {
        is IssueFormField.Markdown -> IssueFormMarkdown(templateHtml.getValue(field.value), onLinkClick)
        is IssueFormField.Input -> IssueFormTextField(field.label, descriptionHtml, field.placeholder, field.required,
            value, true, onLinkClick, onValueChange)
        is IssueFormField.Textarea -> IssueFormTextField(field.label, descriptionHtml, field.placeholder, field.required,
            value, false, onLinkClick, onValueChange)
        is IssueFormField.Dropdown -> IssueFormDropdown(field, descriptionHtml, selected, onLinkClick, onSelected)
        is IssueFormField.Checkboxes -> IssueFormCheckboxes(field, descriptionHtml, templateHtml, selected, onSelected, onLinkClick)
    }
}

@Composable
private fun IssueFormMarkdown(html: String?, onLinkClick: (String) -> Unit) {
    if (html == null) return
    MarkdownWebView(
        bodyHtml = html,
        linkColor = MaterialTheme.colorScheme.primary,
        contentPadding = PaddingValues(0.dp),
        onExternalLink = onLinkClick,
    )
}

@Composable
private fun IssueFormLabel(text: AnnotatedString, required: Boolean, fontWeight: FontWeight? = null) {
    val marker = stringResource(R.string.issue_form_required_marker)
    val primary = MaterialTheme.colorScheme.primary
    Text(
        text = buildAnnotatedString {
            append(text)
            if (required) {
                append(' ')
                withStyle(SpanStyle(color = primary)) { append(marker) }
            }
        },
        fontWeight = fontWeight,
    )
}

@Composable
private fun IssueFormTextField(
    label: String,
    descriptionHtml: String?,
    placeholder: String?,
    required: Boolean,
    value: String,
    singleLine: Boolean,
    onLinkClick: (String) -> Unit,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { IssueFormLabel(AnnotatedString(label), required) },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 4,
            modifier = Modifier.fillMaxWidth(),
        )
        IssueFormMarkdown(descriptionHtml, onLinkClick)
    }
}

@Composable
private fun IssueFormDropdown(
    field: IssueFormField.Dropdown,
    descriptionHtml: String?,
    selected: Set<Int>,
    onLinkClick: (String) -> Unit,
    onSelected: (Set<Int>) -> Unit,
) {
    Column {
        IssueFormLabel(AnnotatedString(field.label), field.required, FontWeight.SemiBold)
        IssueFormMarkdown(descriptionHtml, onLinkClick)
        field.options.forEachIndexed { index, option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (field.multiple) {
                    Checkbox(index in selected, { onSelected(if (it) selected + index else selected - index) })
                } else {
                    RadioButton(index in selected, { onSelected(setOf(index)) })
                }
                Text(option)
            }
        }
    }
}

@Composable
private fun IssueFormCheckboxes(
    field: IssueFormField.Checkboxes,
    descriptionHtml: String?,
    templateHtml: Map<String, String>,
    selected: Set<Int>,
    onSelected: (Set<Int>) -> Unit,
    onLinkClick: (String) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val currentLinkClick by rememberUpdatedState(onLinkClick)
    Column {
        Text(field.label, fontWeight = FontWeight.SemiBold)
        IssueFormMarkdown(descriptionHtml, onLinkClick)
        field.options.forEachIndexed { index, option ->
            val html = templateHtml.getValue(option.label)
            val label = remember(html, primary) {
                AnnotatedString.fromHtml(
                    htmlString = html,
                    linkStyles = TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { link ->
                        if (link is LinkAnnotation.Url) {
                            val uri = link.url.toUri()
                            if (uri.scheme in setOf("https", "http", "mailto") && uri.userInfo.isNullOrEmpty()) {
                                currentLinkClick(link.url)
                            }
                        }
                    },
                ).let { it.subSequence(0, it.text.trimEnd().length) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(index in selected, { onSelected(if (it) selected + index else selected - index) })
                IssueFormLabel(label, option.required)
            }
        }
    }
}

@Composable private fun MetadataChip(label: String, count: Int? = null, value: String? = null, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(if (value != null) "$label · $value" else "$label · ${count ?: 0}") },
    )
}
@Composable private fun EditorRetry(modifier: Modifier, retry: () -> Unit) { Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = retry) { Text(stringResource(R.string.common_retry)) } } }
