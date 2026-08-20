package com.gitmob.app.ui.repocommits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.ui.common.CommitStats
import com.gitmob.app.ui.common.GitChangedFileRow
import com.gitmob.app.ui.common.UnifiedDiffViewer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoCommitDetailScreen(
    owner: String,
    name: String,
    ref: String,
    sha: String,
    permission: RepoPermission?,
    onBack: () -> Unit,
    viewModel: RepoCommitDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, ref, sha) { viewModel.init(owner, name, ref, sha, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var fileToRevert by remember { mutableStateOf<com.gitmob.app.data.model.RepoChangedFile?>(null) }
    var commitRevertRequested by remember { mutableStateOf(false) }
    var commitMenuExpanded by remember { mutableStateOf(false) }
    val revertMessage = stringResource(R.string.git_revert_file_commit_message)
    val commitRevertMessage = stringResource(R.string.git_revert_commit_message)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.git_commit_detail, sha.take(7))) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    if (state.detail != null && state.capabilities.canPush && state.capabilities.canPushToProtectedBranch && !state.detail!!.isArchived) {
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { commitMenuExpanded = true }, enabled = !state.isReverting) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.common_more))
                            }
                            DropdownMenu(expanded = commitMenuExpanded, onDismissRequest = { commitMenuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.git_revert_commit)) },
                                    enabled = !state.detail!!.commit.isMergeCommit,
                                    onClick = { commitMenuExpanded = false; commitRevertRequested = true },
                                )
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        when {
            state.isLoading -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            state.loadFailed || state.detail == null -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); androidx.compose.material3.TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.common_retry)) } }
            else -> {
                val detail = requireNotNull(state.detail)
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(detail.commit.headline, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                            Text(detail.commit.oid, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            Text(detail.commit.author?.login ?: detail.commit.author?.displayName ?: stringResource(R.string.common_unknown), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                            CommitStats(detail.commit.additions, detail.commit.deletions, detail.commit.changedFiles)
                            Text(stringResource(R.string.git_commit_changed_files), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        }
                    }
                    items(detail.changedFiles, key = { it.filename }) { file ->
                        GitChangedFileRow(
                            file = file,
                            onClick = { viewModel.toggleFile(file) },
                            onRevert = if (state.capabilities.canPush && state.capabilities.canPushToProtectedBranch && !detail.isArchived) {
                                { fileToRevert = file }
                            } else null,
                        )
                        if (state.selectedFile?.filename == file.filename) {
                            UnifiedDiffViewer(state.selectedDiff)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    fileToRevert?.let { file ->
        AlertDialog(
            onDismissRequest = { if (!state.isReverting) fileToRevert = null },
            title = { Text(stringResource(R.string.git_revert_file_title, file.filename)) },
            text = { Text(stringResource(R.string.git_revert_file_message)) },
            dismissButton = {
                TextButton(onClick = { fileToRevert = null }, enabled = !state.isReverting) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.revertFile(file, revertMessage) { fileToRevert = null } },
                    enabled = !state.isReverting,
                    colors = ButtonDefaults.textButtonColors(contentColor = androidx.compose.material3.MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.git_revert_file)) }
            },
        )
    }
    if (commitRevertRequested && state.detail != null) {
        AlertDialog(
            onDismissRequest = { if (!state.isReverting) commitRevertRequested = false },
            title = { Text(stringResource(R.string.git_revert_commit_title, sha.take(7))) },
            text = { Text(stringResource(R.string.git_revert_commit_message_body)) },
            dismissButton = {
                TextButton(onClick = { commitRevertRequested = false }, enabled = !state.isReverting) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.revertCommit(commitRevertMessage) { commitRevertRequested = false } },
                    enabled = !state.isReverting && !state.detail!!.commit.isMergeCommit,
                    colors = ButtonDefaults.textButtonColors(contentColor = androidx.compose.material3.MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.git_revert_commit)) }
            },
        )
    }
}
