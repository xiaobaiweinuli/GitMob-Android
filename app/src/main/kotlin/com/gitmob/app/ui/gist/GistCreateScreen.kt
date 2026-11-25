package com.gitmob.app.ui.gist

import com.gitmob.app.ui.common.pressToggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fitInside
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.ui.common.SoraTextEditor
import com.gitmob.app.ui.common.SoraUndoRedoButtons
import com.gitmob.app.ui.common.rememberSoraEditorController
import com.gitmob.app.ui.common.ActionButtonContent
import com.gitmob.app.ui.common.ActionIconButton

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
                    ActionIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::create, enabled = state.canCreate, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                        ActionButtonContent(busy = state.isCreating, label = stringResource(R.string.repo_create_action))
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
                enabled = !state.isCreating,
                label = { Text(stringResource(R.string.gist_create_description_optional)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .pressToggleable(state.isPublic, enabled = !state.isCreating, role = Role.Switch, onValueChange = viewModel::updatePublic),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.gist_create_public), Modifier.weight(1f))
                Switch(checked = state.isPublic, onCheckedChange = null, enabled = !state.isCreating)
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
                TextButton(onClick = viewModel::addFile, enabled = !state.isCreating, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.gist_create_add_file), Modifier.padding(start = 4.dp))
                }
            }
        }
        itemsIndexed(state.files, key = { _, file -> file.id }) { index, file ->
            if (index > 0) {
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            }
            GistCreateFileFields(
                number = index + 1,
                file = file,
                error = state.filenameError(file),
                canRemove = state.files.size > 1,
                enabled = !state.isCreating,
                onFilenameChange = { viewModel.updateFilename(file.id, it) },
                onRemove = { viewModel.removeFile(file.id) },
            )
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
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val controller = rememberSoraEditorController(file.editorState)
    var wrapLines by rememberSaveable(file.id) { mutableStateOf(true) }
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
            ActionIconButton(onClick = onRemove, enabled = canRemove && enabled) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.gist_delete_file))
            }
        }
        OutlinedTextField(
            value = file.filename,
            onValueChange = onFilenameChange,
            enabled = enabled,
            label = { Text(stringResource(R.string.gist_filename)) },
            supportingText = { Text(errorText) },
            isError = error != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gist_file_content), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            SoraUndoRedoButtons(file.editorState, controller, enabled = enabled)
            Text(stringResource(R.string.repo_file_wrap_lines), style = MaterialTheme.typography.labelMedium)
            Switch(checked = wrapLines, onCheckedChange = { wrapLines = it })
        }
        SoraTextEditor(
            state = file.editorState,
            enabled = enabled,
            wrapLines = wrapLines,
            controller = controller,
            modifier = Modifier.fillMaxWidth().height(320.dp),
        )
    }
}
