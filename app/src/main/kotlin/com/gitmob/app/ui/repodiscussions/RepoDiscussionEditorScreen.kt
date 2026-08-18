package com.gitmob.app.ui.repodiscussions

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
import com.gitmob.app.data.model.RepoDiscussion
import com.gitmob.app.data.model.RepoDiscussionCategory
import com.gitmob.app.ui.common.GitHubEmojiLabel

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
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(if (number == null) R.string.discussion_new else R.string.discussion_edit)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = viewModel::load) { Text(stringResource(R.string.common_retry)) } }
            else -> DiscussionEditorContent(state.categories, state.existing, state.isSaving, Modifier.fillMaxSize().padding(padding)) { title, body, category -> viewModel.save(title, body, category) { onSaved(it.number) } }
        }
    }
}

@Composable
private fun DiscussionEditorContent(categories: List<RepoDiscussionCategory>, existing: RepoDiscussion?, saving: Boolean, modifier: Modifier, onSave: (String, String, String) -> Unit) {
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember(existing) { mutableStateOf(existing?.body.orEmpty()) }
    var category by remember(existing, categories) { mutableStateOf(existing?.category?.id ?: categories.firstOrNull()?.id.orEmpty()) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.issue_editor_title_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.issue_editor_body_label)) }, minLines = 10, modifier = Modifier.fillMaxWidth()) }
        item { Text(stringResource(R.string.discussion_category), fontWeight = FontWeight.SemiBold) }
        items(categories, key = { it.id }) { item -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(category == item.id, { category = item.id }); GitHubEmojiLabel(item.emoji, item.name) } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Button(onClick = { onSave(title, body, category) }, enabled = title.isNotBlank() && category.isNotBlank() && !saving) { Text(stringResource(if (saving) R.string.conversation_submitting else R.string.common_save)) } } }
    }
}
