package com.gitmob.app.ui.repodiscussions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fitInside
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoDiscussion
import com.gitmob.app.data.model.RepoDiscussionCategory
import com.gitmob.app.ui.common.GitHubEmojiLabel
import com.gitmob.app.ui.common.MarkdownBodyEditor
import com.gitmob.app.ui.common.MarkdownEditorTab
import com.gitmob.app.ui.common.MarkdownEditorUiState

private enum class DiscussionMetadataSheet { CATEGORY, LABELS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDiscussionEditorScreen(
    owner: String,
    name: String,
    number: Int?,
    onBack: () -> Unit,
    onSaved: (Int) -> Unit,
    viewModel: RepoDiscussionEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, number) { viewModel.init(owner, name, number) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var saveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(if (number == null) R.string.discussion_new else R.string.discussion_edit)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, actions = { IconButton(onClick = { saveAction?.invoke() }, enabled = saveAction != null && !state.isSaving) { if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp)) else Text(stringResource(R.string.common_save)) } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .fitInside(WindowInsetsRulers.Ime.current)
        when {
            state.isLoading -> Box(contentModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> Column(contentModifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = viewModel::load) { Text(stringResource(R.string.common_retry)) } }
            else -> DiscussionEditorContent(state.categories, state.labels, state.existing, state.bodyEditor, state.isSaving, contentModifier, viewModel::selectBodyEditorTab, { saveAction = it }) { title, body, category, labels -> viewModel.save(title, body, category, labels) { onSaved(it.number) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscussionEditorContent(categories: List<RepoDiscussionCategory>, labels: List<com.gitmob.app.data.model.IssueLabel>, existing: RepoDiscussion?, bodyEditorState: MarkdownEditorUiState, saving: Boolean, modifier: Modifier, onBodyTabSelected: (MarkdownEditorTab, String) -> Unit, onSaveActionReady: ((() -> Unit) -> Unit), onSave: (String, String, String, List<String>) -> Unit) {
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember(existing) { mutableStateOf(existing?.body.orEmpty()) }
    var bodyValue by remember(existing) { mutableStateOf(TextFieldValue(body, TextRange(body.length))) }
    var category by remember(existing, categories) { mutableStateOf(existing?.category?.id ?: categories.firstOrNull()?.id.orEmpty()) }
    var selectedLabels by remember(existing) { mutableStateOf(existing?.labels.orEmpty().map { it.id }.toSet()) }
    var metadataSheet by remember { mutableStateOf<DiscussionMetadataSheet?>(null) }
    var draftCategory by remember { mutableStateOf(category) }
    var draftLabels by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun openMetadata(sheet: DiscussionMetadataSheet) {
        draftCategory = category
        draftLabels = selectedLabels
        metadataSheet = sheet
    }
    SideEffect { onSaveActionReady { onSave(title, body, category, selectedLabels.toList()) } }
    Column(
        modifier = modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.issue_editor_title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        MarkdownBodyEditor(
            value = bodyValue,
            state = bodyEditorState,
            onValueChange = { bodyValue = it; body = it.text; if (bodyEditorState.selectedTab == MarkdownEditorTab.PREVIEW) onBodyTabSelected(MarkdownEditorTab.PREVIEW, it.text) },
            onTabSelected = { onBodyTabSelected(it, bodyValue.text) },
            accessoryContent = {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = false, onClick = { openMetadata(DiscussionMetadataSheet.CATEGORY) }, label = { Text("${stringResource(R.string.discussion_category)} · ${categories.firstOrNull { it.id == category }?.name ?: "-"}") })
                    FilterChip(selected = false, onClick = { openMetadata(DiscussionMetadataSheet.LABELS) }, label = { Text("${stringResource(R.string.issue_labels)} · ${selectedLabels.size}") })
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
    metadataSheet?.let { sheet ->
        ModalBottomSheet(onDismissRequest = { metadataSheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(if (sheet == DiscussionMetadataSheet.CATEGORY) stringResource(R.string.discussion_category) else stringResource(R.string.issue_labels), style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(vertical = 12.dp)) {
                    if (sheet == DiscussionMetadataSheet.CATEGORY) items(categories, key = { it.id }) { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(draftCategory == item.id, { draftCategory = item.id }); GitHubEmojiLabel(item.emoji, item.name) }
                    } else items(labels, key = { it.id }) { label ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Checkbox(label.id in draftLabels, { checked -> draftLabels = if (checked) draftLabels + label.id else draftLabels - label.id }); Text(label.name) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) { Button(onClick = { category = draftCategory; selectedLabels = draftLabels; metadataSheet = null }) { Text(stringResource(R.string.common_done)) } }
            }
        }
    }
}
