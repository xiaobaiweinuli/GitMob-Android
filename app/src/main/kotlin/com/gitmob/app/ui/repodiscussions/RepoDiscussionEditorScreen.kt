package com.gitmob.app.ui.repodiscussions

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.gitmob.app.ui.common.SoraEditorState
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoDiscussionCategory
import com.gitmob.app.ui.common.ActionButtonContent
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.GitHubEmojiLabel
import com.gitmob.app.ui.common.MarkdownBodyEditor
import com.gitmob.app.ui.common.MarkdownEditorTab
import com.gitmob.app.ui.common.MarkdownEditorUiState
import com.gitmob.app.ui.common.RepositoryContextTitle

private enum class DiscussionMetadataSheet { CATEGORY, LABELS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDiscussionEditorScreen(
    owner: String,
    name: String,
    number: Int?,
    onBack: () -> Unit,
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    onSaved: (Int) -> Unit,
    viewModel: RepoDiscussionEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, number) { viewModel.init(owner, name, number) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { RepositoryContextTitle(owner, name, stringResource(if (number == null) R.string.discussion_new else R.string.discussion_edit), onOwnerClick, onRepositoryClick) }, navigationIcon = { ActionIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, actions = { TextButton(onClick = { viewModel.save { onSaved(it.number) } }, enabled = state.repositoryId.isNotBlank() && !state.isLoading && !state.loadFailed && !state.isSaving, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = state.isSaving, label = stringResource(R.string.common_save)) } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .fitInside(WindowInsetsRulers.Ime.current)
        when {
            state.isLoading -> Box(contentModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> Column(contentModifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = viewModel::load, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_retry)) } }
            else -> DiscussionEditorContent(
                categories = state.categories,
                labels = state.labels,
                draft = state.draft,
                bodyState = viewModel.bodyState,
                bodyEditorState = state.bodyEditor,
                modifier = contentModifier,
                onBodyTabSelected = viewModel::selectBodyEditorTab,
                onTitleChange = viewModel::editTitle,
                onMetadataConfirmed = viewModel::confirmMetadata,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscussionEditorContent(
    categories: List<RepoDiscussionCategory>,
    labels: List<com.gitmob.app.data.model.IssueLabel>,
    draft: RepoDiscussionEditorDraft,
    bodyState: SoraEditorState,
    bodyEditorState: MarkdownEditorUiState,
    modifier: Modifier,
    onBodyTabSelected: (MarkdownEditorTab) -> Unit,
    onTitleChange: (String) -> Unit,
    onMetadataConfirmed: (String, Set<String>) -> Unit,
) {
    val title = draft.title
    val category = draft.categoryId
    val selectedLabels = draft.labelIds
    var metadataSheet by remember { mutableStateOf<DiscussionMetadataSheet?>(null) }
    var draftCategory by remember { mutableStateOf(category) }
    var draftLabels by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun openMetadata(sheet: DiscussionMetadataSheet) {
        draftCategory = category
        draftLabels = selectedLabels
        metadataSheet = sheet
    }
    Column(
        modifier = modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(title, onTitleChange, label = { Text(stringResource(R.string.issue_editor_title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = MaterialTheme.shapes.medium)
        MarkdownBodyEditor(
            textState = bodyState,
            state = bodyEditorState,
            onTabSelected = onBodyTabSelected,
            accessoryContent = {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = category.isNotBlank(), shapes = FilterChipDefaults.shapes(), modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec(), alignment = Alignment.CenterStart), onClick = { openMetadata(DiscussionMetadataSheet.CATEGORY) }, label = { Text("${stringResource(R.string.discussion_category)} · ${categories.firstOrNull { it.id == category }?.name ?: "-"}") })
                    FilterChip(selected = selectedLabels.isNotEmpty(), shapes = FilterChipDefaults.shapes(), modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec(), alignment = Alignment.CenterStart), onClick = { openMetadata(DiscussionMetadataSheet.LABELS) }, label = { Text("${stringResource(R.string.issue_labels)} · ${selectedLabels.size}") })
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
    metadataSheet?.let { sheet ->
        ModalBottomSheet(onDismissRequest = { metadataSheet = null }, sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(if (sheet == DiscussionMetadataSheet.CATEGORY) stringResource(R.string.discussion_category) else stringResource(R.string.issue_labels), style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f, fill = false).then(if (sheet == DiscussionMetadataSheet.CATEGORY) Modifier.selectableGroup() else Modifier), contentPadding = PaddingValues(vertical = 12.dp)) {
                    if (sheet == DiscussionMetadataSheet.CATEGORY) items(categories, key = { it.id }) { item ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressSelectable(draftCategory == item.id, role = Role.RadioButton, onClick = { draftCategory = item.id }).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(draftCategory == item.id, onClick = null); GitHubEmojiLabel(item.emoji, item.name) }
                    } else items(labels, key = { it.id }) { label ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressToggleable(label.id in draftLabels, role = Role.Checkbox, onValueChange = { checked -> draftLabels = if (checked) draftLabels + label.id else draftLabels - label.id }).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(label.id in draftLabels, onCheckedChange = null); Text(label.name) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) { Button(onClick = { onMetadataConfirmed(draftCategory, draftLabels); metadataSheet = null }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_done)) } }
            }
        }
    }
}
