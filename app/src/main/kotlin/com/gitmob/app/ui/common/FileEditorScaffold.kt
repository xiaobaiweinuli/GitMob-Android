package com.gitmob.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gitmob.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScaffold(
    title: @Composable () -> Unit,
    path: String,
    editorState: SoraEditorState,
    isSaving: Boolean,
    isLoading: Boolean = false,
    loadFailed: Boolean = false,
    canSave: Boolean = true,
    onPathChange: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    var wrapLines by rememberSaveable { mutableStateOf(true) }
    val controller = rememberSoraEditorController(editorState)
    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = {
                    ActionIconButton(onClick = onBack, enabled = !isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    ActionIconButton(onClick = onSave, enabled = path.isNotBlank() && canSave && !isSaving) {
                        ActionProgressIcon(busy = isSaving, busyDescription = stringResource(R.string.common_save)) { Icon(Icons.Default.Save, stringResource(R.string.common_save)) }
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
            isLoading -> Box(contentModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loadFailed -> Box(contentModifier, contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.common_load_failed))
            }
            else -> Column(
                contentModifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = path,
                    onValueChange = onPathChange,
                    enabled = !isSaving,
                    label = { Text(stringResource(R.string.repo_file_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.repo_file_content), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    SoraUndoRedoButtons(editorState, controller, enabled = !isSaving)
                    Text(stringResource(R.string.repo_file_wrap_lines), style = MaterialTheme.typography.labelMedium)
                    Switch(checked = wrapLines, onCheckedChange = { wrapLines = it })
                }
                SoraTextEditor(
                    state = editorState,
                    enabled = !isSaving,
                    wrapLines = wrapLines,
                    controller = controller,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}
