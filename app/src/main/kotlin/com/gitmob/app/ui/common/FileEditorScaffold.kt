package com.gitmob.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gitmob.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScaffold(
    title: @Composable () -> Unit,
    path: String,
    content: String,
    isSaving: Boolean,
    isLoading: Boolean = false,
    loadFailed: Boolean = false,
    canSave: Boolean = true,
    middleContent: @Composable ColumnScope.() -> Unit = {},
    onPathChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = onSave, enabled = path.isNotBlank() && canSave && !isSaving) {
                        if (isSaving) CircularProgressIndicator()
                        else Icon(Icons.Default.Save, stringResource(R.string.common_save))
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
                    label = { Text(stringResource(R.string.repo_file_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                middleContent()
                OutlinedTextField(
                    value = content,
                    onValueChange = onContentChange,
                    label = { Text(stringResource(R.string.repo_file_content)) },
                    placeholder = { Text(stringResource(R.string.repo_file_content_placeholder)) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    maxLines = Int.MAX_VALUE,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}
