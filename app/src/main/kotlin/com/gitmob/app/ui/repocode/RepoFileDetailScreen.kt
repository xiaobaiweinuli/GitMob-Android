package com.gitmob.app.ui.repocode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.ui.common.MarkdownWebView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoFileDetailScreen(
    owner: String,
    name: String,
    ref: String,
    path: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    viewModel: RepoFileDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, ref, path) { viewModel.init(owner, name, ref, path) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val deleteCommitMessage = stringResource(R.string.repo_delete_file_title)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(path) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = onHistory) { Icon(Icons.Default.History, stringResource(R.string.repo_commits)) }
                    IconButton(onClick = viewModel::downloadFile, enabled = !state.isDownloading) {
                        if (state.isDownloading) CircularProgressIndicator() else Icon(Icons.Default.Download, stringResource(R.string.common_download))
                    }
                    if (state.file?.capabilities?.canPush == true && state.file?.capabilities?.canPushToProtectedBranch == true && state.file?.isArchived == false) IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.common_edit)) }
                    if (state.file?.capabilities?.canPush == true && state.file?.capabilities?.canPushToProtectedBranch == true && state.file?.isArchived == false) IconButton(onClick = { confirmDelete = true }, enabled = !state.isDeleting) { Icon(Icons.Default.Delete, stringResource(R.string.common_delete)) }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed || state.file == null -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); OutlinedButton(onClick = viewModel::retry) { Text(stringResource(R.string.common_retry)) } }
            state.file?.isBinary == true -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_file_no_preview)) }
            else -> {
                val file = requireNotNull(state.file)
                val previewHtml = state.previewHtml
                Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.previewSupported) {
                    androidx.compose.material3.PrimaryTabRow(selectedTabIndex = if (state.previewMode) 1 else 0) {
                        androidx.compose.material3.Tab(!state.previewMode, { viewModel.setPreviewMode(false) }, text = { Text(stringResource(R.string.conversation_edit_tab)) })
                        androidx.compose.material3.Tab(state.previewMode, { viewModel.setPreviewMode(true) }, text = { Text(stringResource(R.string.conversation_preview_tab)) })
                    }
                }
                if (state.previewMode && previewHtml != null) MarkdownWebView(previewHtml, Modifier.fillMaxWidth())
                else Text(file.text.orEmpty(), Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.repo_delete_file_title)) },
            text = { Text(stringResource(R.string.repo_delete_file_message)) },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false; viewModel.deleteFile(deleteCommitMessage, onBack) }, colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_delete)) } },
        )
    }
}
