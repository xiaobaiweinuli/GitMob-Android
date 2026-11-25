package com.gitmob.app.ui.repocode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
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
    val defaultCommitTitle = stringResource(R.string.repo_commit_default_title, state.path.substringAfterLast('/'))
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
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
        editorState = state.editorState,
        isSaving = state.isSaving,
        isLoading = state.isLoading,
        loadFailed = state.loadFailed,
        canSave = state.canCommit,
        onPathChange = viewModel::updatePath,
        onBack = onBack,
        onSave = {
            focusManager.clearFocus()
            keyboard?.hide()
            viewModel.openCommitSheet(defaultCommitTitle)
        },
    )
    if (state.isCommitSheetOpen) {
        RepoFileCommitSheet(
            state = state,
            onTitleChange = viewModel::updateCommitTitle,
            onBodyChange = viewModel::updateCommitBody,
            onDismiss = viewModel::dismissCommitSheet,
            onCommit = { viewModel.save(onSaved) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoFileCommitSheet(
    state: RepoFileEditorUiState,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCommit: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        sheetGesturesEnabled = !state.isSaving,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = !state.isSaving,
            shouldDismissOnClickOutside = !state.isSaving,
        ),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.repo_commit_changes),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, enabled = !state.isSaving) {
                    Icon(Icons.Default.Close, stringResource(R.string.common_cancel))
                }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = state.commitTitle,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.repo_commit_title)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.commitBody,
                    onValueChange = onBodyChange,
                    label = { Text(stringResource(R.string.repo_commit_description)) },
                    minLines = 4,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = onCommit,
                enabled = state.canCommit && state.commitTitle.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(stringResource(R.string.repo_commit_submit))
            }
        }
    }
}
