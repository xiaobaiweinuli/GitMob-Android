package com.gitmob.app.ui.repocode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fitInside
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.ui.common.RepositoryContextTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoFileEditorScreen(
    owner: String,
    name: String,
    ref: String,
    path: String?,
    onBack: () -> Unit,
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    onSaved: () -> Unit,
    viewModel: RepoFileEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, ref, path) { viewModel.init(owner, name, ref, path) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    RepositoryContextTitle(
                        owner = owner,
                        repository = name,
                        pageTitle = stringResource(R.string.repo_code),
                        onOwnerClick = onOwnerClick,
                        onRepositoryClick = onRepositoryClick,
                    )
                },
                navigationIcon = { IconButton(onClick = onBack, enabled = !state.isSaving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
            actions = { IconButton(onClick = { viewModel.save(message, onSaved) }, enabled = !state.isSaving && state.capabilities.canPush && state.capabilities.canPushToProtectedBranch) { if (state.isSaving) CircularProgressIndicator() else Icon(Icons.Default.Save, stringResource(R.string.common_save)) } },
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
            state.isLoading -> Box(
                modifier = contentModifier,
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { CircularProgressIndicator() }
            state.loadFailed -> Box(
                modifier = contentModifier,
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { Text(stringResource(R.string.common_load_failed)) }
            else -> Column(contentModifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(state.path, viewModel::updatePath, label = { Text(stringResource(R.string.repo_file_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.repo_commit_message)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = state.content,
                    onValueChange = viewModel::updateContent,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = { Text(stringResource(R.string.repo_file_content)) },
                    placeholder = { Text(stringResource(R.string.repo_file_content_placeholder)) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    maxLines = Int.MAX_VALUE,
                )
            }
        }
    }
}
