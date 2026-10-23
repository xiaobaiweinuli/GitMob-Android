package com.gitmob.app.ui.repocode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoEntryType
import com.gitmob.app.data.model.RepoForkSyncInfo
import com.gitmob.app.data.model.RepoForkSyncStatus
import com.gitmob.app.core.storage.SafFileReader
import com.gitmob.app.ui.common.RepositoryContextTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoCodeScreen(
    owner: String,
    name: String,
    ref: String,
    path: String,
    permission: RepoPermission?,
    onBack: () -> Unit,
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    onAdd: () -> Unit,
    onDirectoryClick: (String) -> Unit,
    onFileClick: (String) -> Unit,
    onCreateSyncPullRequest: (RepoForkSyncInfo) -> Unit,
    onExistingSyncPullRequest: (Int) -> Unit,
    viewModel: RepoCodeViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, ref, path) { viewModel.init(owner, name, ref, path, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uploadMessage = stringResource(R.string.repo_upload_commit)
    var addMenuExpanded by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        runCatching { SafFileReader.readDocuments(context, uris) }
            .onSuccess { viewModel.uploadFiles(it, uploadMessage) }
            .onFailure { uploadError = it.message }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { SafFileReader.readTree(context, uri) }
            .onSuccess { viewModel.uploadFiles(it, uploadMessage) }
            .onFailure { uploadError = it.message }
    }
    val folderZipPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.exportCurrentFolder { bytes ->
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    output.write(bytes)
                } ?: throw IOException("Unable to open the selected document")
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    RepositoryContextTitle(
                        owner = owner,
                        repository = name,
                        pageTitle = path.ifBlank { stringResource(R.string.repo_code) },
                        onOwnerClick = onOwnerClick,
                        onRepositoryClick = onRepositoryClick,
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    if (state.tree?.isFork == true) {
                        IconButton(onClick = viewModel::openSync, enabled = !state.isSyncBusy) {
                            Icon(Icons.Default.Sync, stringResource(R.string.repo_sync_title))
                        }
                    }
                    IconButton(onClick = viewModel::downloadArchive, enabled = !state.openingDownload && !state.isFolderExporting) {
                        if (state.openingDownload) CircularProgressIndicator() else Icon(Icons.Default.Archive, stringResource(R.string.repo_download_archive))
                    }
                    if (path.isNotBlank()) {
                        IconButton(
                            onClick = { folderZipPicker.launch(folderZipFileName(name, ref, path)) },
                            enabled = !state.isFolderExporting,
                        ) {
                            Icon(Icons.Default.Download, stringResource(R.string.repo_download_folder))
                        }
                    }
                    if (state.capabilities.canPush && state.capabilities.canPushToProtectedBranch && state.tree?.isArchived == false) {
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { addMenuExpanded = true }, enabled = !state.isUploading) {
                                if (state.isUploading) CircularProgressIndicator() else Icon(Icons.Default.Add, stringResource(R.string.repo_add_file))
                            }
                            DropdownMenu(expanded = addMenuExpanded, onDismissRequest = { addMenuExpanded = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.repo_add_file)) }, onClick = { addMenuExpanded = false; onAdd() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.repo_upload_files)) }, onClick = { addMenuExpanded = false; filePicker.launch(arrayOf("*/*")) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.repo_upload_folder)) }, onClick = { addMenuExpanded = false; folderPicker.launch(null) })
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
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed || state.tree == null -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.common_retry)) } }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                if (path.isNotBlank()) item(key = "parent") { ListItem(headlineContent = { Text("..") }, leadingContent = { Icon(Icons.Default.Folder, null) }, modifier = Modifier.clickable { onDirectoryClick(path.substringBeforeLast('/', "")) }); HorizontalDivider() }
                items(state.tree!!.entries, key = { it.path }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = { Text(listOfNotNull(entry.languageName, entry.size?.takeIf { it > 0 }?.let { "$it B" }).joinToString(" · ")) },
                        leadingContent = { Icon(if (entry.type == RepoEntryType.DIRECTORY) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                        modifier = Modifier.fillMaxWidth().clickable { if (entry.type == RepoEntryType.DIRECTORY) onDirectoryClick(entry.path) else onFileClick(entry.path) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    uploadError?.let { message ->
        AlertDialog(
            onDismissRequest = { uploadError = null },
            title = { Text(stringResource(R.string.common_load_failed)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { uploadError = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (state.isFolderExporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.repo_downloading_folder)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.folderExportTotal > 0) {
                        LinearProgressIndicator(
                            progress = { state.folderExportCompleted.toFloat() / state.folderExportTotal },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(
                                R.string.repo_folder_download_progress,
                                state.folderExportCompleted,
                                state.folderExportTotal,
                            ),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.repo_collecting_folder_files))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::cancelFolderExport) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (state.isSyncDialogOpen) {
        ForkSyncDialog(
            owner = owner,
            name = name,
            state = state,
            dismiss = viewModel::dismissSync,
            retry = viewModel::openSync,
            merge = viewModel::mergeUpstream,
            showDiscard = { viewModel.showDiscardConfirmation(true) },
            hideDiscard = { viewModel.showDiscardConfirmation(false) },
            discard = viewModel::discardAndAlign,
            showPullRequest = { viewModel.showSyncPullRequestConfirmation(true) },
            hidePullRequest = { viewModel.showSyncPullRequestConfirmation(false) },
            pullRequest = { viewModel.openSyncPullRequest(onExistingSyncPullRequest, onCreateSyncPullRequest) },
        )
    }
}

@Composable
private fun ForkSyncDialog(
    owner: String,
    name: String,
    state: RepoCodeUiState,
    dismiss: () -> Unit,
    retry: () -> Unit,
    merge: () -> Unit,
    showDiscard: () -> Unit,
    hideDiscard: () -> Unit,
    discard: (String) -> Unit,
    showPullRequest: () -> Unit,
    hidePullRequest: () -> Unit,
    pullRequest: () -> Unit,
) {
    val info = state.syncInfo
    val direction = if (info?.upstreamOwner != null && info.upstreamName != null) {
        stringResource(R.string.repo_sync_direction, info.upstreamOwner, info.upstreamName, info.branch, owner, name)
    } else null
    if (state.showDiscardConfirmation && info != null) {
        var confirmation by remember(info.branch, info.currentOid, info.upstreamOid) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = hideDiscard,
            modifier = Modifier.padding(horizontal = 24.dp),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text(stringResource(R.string.repo_sync_discard_title)) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    direction?.let { Text(it) }
                    Text(stringResource(R.string.repo_sync_discard_warning, info.aheadBy), color = MaterialTheme.colorScheme.error)
                    info.currentOid?.let { Text(stringResource(R.string.repo_sync_current_head, it.take(12))) }
                    info.upstreamOid?.let { Text(stringResource(R.string.repo_sync_upstream_head, it.take(12))) }
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text(stringResource(R.string.repo_sync_confirm_branch)) },
                        singleLine = true,
                        enabled = !state.isSyncBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            dismissButton = { TextButton(onClick = hideDiscard, enabled = !state.isSyncBusy) { Text(stringResource(R.string.common_cancel)) } },
            confirmButton = {
                Button(
                    onClick = { discard(confirmation) },
                    enabled = confirmation == info.branch && !state.isSyncBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) {
                    if (state.isSyncing) CircularProgressIndicator(Modifier.size(20.dp)) else Text(stringResource(R.string.repo_sync_discard))
                }
            },
        )
        return
    }
    if (state.showSyncPullRequestConfirmation && info?.upstreamOwner != null && info.upstreamName != null) {
        AlertDialog(
            onDismissRequest = hidePullRequest,
            title = { Text(stringResource(R.string.repo_sync_pr_title)) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.repo_sync_pr_base, owner, name, info.branch))
                    Text(stringResource(R.string.repo_sync_pr_head, info.upstreamOwner, info.upstreamName, info.branch))
                    Text(stringResource(R.string.repo_sync_pr_hint))
                }
            },
            dismissButton = { TextButton(onClick = hidePullRequest, enabled = !state.isSyncBusy) { Text(stringResource(R.string.common_cancel)) } },
            confirmButton = {
                Button(onClick = pullRequest, enabled = !state.isSyncBusy) {
                    if (state.isCheckingSyncPullRequest) CircularProgressIndicator(Modifier.size(20.dp))
                    else Text(stringResource(R.string.common_continue))
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.repo_sync_title)) },
        text = {
            when {
                state.isLoadingSync -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                info == null -> Text(stringResource(R.string.common_load_failed))
                else -> ForkSyncContent(info, state, direction, showDiscard, showPullRequest)
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.isSyncBusy) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = {
            if (info?.status == RepoForkSyncStatus.READY && info.behindBy > 0 && state.capabilities.canPush) {
                Button(onClick = merge, enabled = !state.isSyncBusy) {
                    if (state.isSyncing) CircularProgressIndicator(Modifier.size(20.dp))
                    else Text(stringResource(R.string.repo_sync_merge))
                }
            } else if (info == null && !state.isLoadingSync) {
                Button(onClick = retry) { Text(stringResource(R.string.common_retry)) }
            }
        },
    )
}

@Composable
private fun ForkSyncContent(
    info: RepoForkSyncInfo,
    state: RepoCodeUiState,
    direction: String?,
    showDiscard: () -> Unit,
    pullRequest: () -> Unit,
) {
    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        direction?.let { Text(it) }
        when (info.status) {
            RepoForkSyncStatus.NOT_FORK -> Text(stringResource(R.string.repo_sync_not_fork))
            RepoForkSyncStatus.MISSING_UPSTREAM -> Text(stringResource(R.string.repo_sync_missing_upstream))
            RepoForkSyncStatus.MISSING_UPSTREAM_BRANCH -> Text(stringResource(R.string.repo_sync_missing_branch))
            RepoForkSyncStatus.EMPTY_BRANCH -> Text(stringResource(R.string.repo_sync_empty_branch))
            RepoForkSyncStatus.NO_COMMON_ANCESTOR -> Text(stringResource(R.string.repo_sync_no_common_ancestor))
            RepoForkSyncStatus.READY -> {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(stringResource(R.string.repo_sync_counts, info.aheadBy, info.behindBy), modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
                if (info.behindBy == 0) Text(stringResource(R.string.repo_sync_up_to_date))
                if (!state.capabilities.canPush) Text(stringResource(R.string.repo_sync_no_permission))
                if (state.syncHasConflict) Text(stringResource(R.string.repo_sync_conflict), color = MaterialTheme.colorScheme.error)
                if (info.behindBy > 0) Text(stringResource(R.string.repo_sync_merge_hint), style = MaterialTheme.typography.bodySmall)
                if (info.aheadBy > 0 && state.capabilities.canPush) {
                    TextButton(
                        onClick = showDiscard,
                        enabled = !state.isSyncBusy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.repo_sync_discard)) }
                }
                if (info.behindBy > 0 && state.tree?.isArchived == false) {
                    TextButton(onClick = pullRequest, enabled = !state.isSyncBusy) { Text(stringResource(R.string.repo_sync_pr)) }
                }
            }
        }
    }
}

internal fun folderZipFileName(repositoryName: String, ref: String, path: String): String {
    val folderName = path.trim('/').substringAfterLast('/').ifBlank { repositoryName }
    val safeRef = ref.removePrefix("refs/heads/").replace('/', '-')
    return "$repositoryName-$folderName-$safeRef.zip"
}
