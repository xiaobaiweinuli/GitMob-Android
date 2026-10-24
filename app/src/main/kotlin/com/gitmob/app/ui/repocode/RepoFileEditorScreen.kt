package com.gitmob.app.ui.repocode

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.ui.common.FileEditorScaffold
import com.gitmob.app.ui.common.RepositoryContextTitle

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
    FileEditorScaffold(
        title = {
            RepositoryContextTitle(
                owner = owner,
                repository = name,
                pageTitle = stringResource(R.string.repo_code),
                onOwnerClick = onOwnerClick,
                onRepositoryClick = onRepositoryClick,
            )
        },
        path = state.path,
        content = state.content,
        isSaving = state.isSaving,
        isLoading = state.isLoading,
        loadFailed = state.loadFailed,
        canSave = state.capabilities.canPush && state.capabilities.canPushToProtectedBranch,
        middleContent = {
            OutlinedTextField(
                message,
                { message = it },
                label = { Text(stringResource(R.string.repo_commit_message)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        onPathChange = viewModel::updatePath,
        onContentChange = viewModel::updateContent,
        onBack = onBack,
        onSave = { viewModel.save(message, onSaved) },
    )
}
