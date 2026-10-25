package com.gitmob.app.ui.gist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fitInside
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GistCreateScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: GistCreateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.createdEvents.collect(onCreated)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gist_create_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::create, enabled = state.canCreate) {
                        if (state.isCreating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Text(stringResource(R.string.repo_create_action))
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        GistCreateForm(
            state = state,
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .fitInside(WindowInsetsRulers.Ime.current),
        )
    }
}

@Composable
private fun GistCreateForm(
    state: GistCreateUiState,
    viewModel: GistCreateViewModel,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::updateDescription,
                label = { Text(stringResource(R.string.gist_create_description_optional)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.gist_create_public), Modifier.weight(1f))
                Switch(checked = state.isPublic, onCheckedChange = viewModel::updatePublic)
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.gist_create_files),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::addFile) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.gist_create_add_file), Modifier.padding(start = 4.dp))
                }
            }
        }
        item {
            Column {
                state.files.forEachIndexed { index, file ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                    }
                    GistCreateFileFields(
                        number = index + 1,
                        file = file,
                        error = state.filenameError(file),
                        canRemove = state.files.size > 1,
                        onFilenameChange = { viewModel.updateFilename(file.id, it) },
                        onContentChange = { viewModel.updateContent(file.id, it) },
                        onRemove = { viewModel.removeFile(file.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GistCreateFileFields(
    number: Int,
    file: GistCreateFileUiState,
    error: GistFilenameError?,
    canRemove: Boolean,
    onFilenameChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val errorText = when (error) {
        GistFilenameError.REQUIRED -> stringResource(R.string.gist_create_filename_required)
        GistFilenameError.DUPLICATE -> stringResource(R.string.gist_create_filename_duplicate)
        null -> ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.gist_create_file_number, number),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove, enabled = canRemove) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.gist_delete_file))
            }
        }
        OutlinedTextField(
            value = file.filename,
            onValueChange = onFilenameChange,
            label = { Text(stringResource(R.string.gist_filename)) },
            supportingText = { Text(errorText) },
            isError = error != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = file.content,
            onValueChange = onContentChange,
            label = { Text(stringResource(R.string.gist_file_content)) },
            minLines = 10,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
