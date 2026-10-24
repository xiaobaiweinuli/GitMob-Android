package com.gitmob.app.ui.gist

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.ui.common.FileEditorScaffold

@Composable
fun GistFileEditorScreen(
    gistId: String,
    filename: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: GistFileEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(gistId, filename) { viewModel.init(gistId, filename) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    FileEditorScaffold(
        title = { Text(stringResource(if (filename == null) R.string.gist_add_file else R.string.gist_edit_file)) },
        path = state.path,
        content = state.content,
        isSaving = state.isSaving,
        isLoading = state.isLoading,
        loadFailed = state.loadFailed,
        onPathChange = viewModel::updatePath,
        onContentChange = viewModel::updateContent,
        onBack = onBack,
        onSave = { viewModel.save(gistId, onSaved) },
    )
}
