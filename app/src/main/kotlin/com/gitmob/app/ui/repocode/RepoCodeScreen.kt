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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoEntryType
import com.gitmob.app.core.storage.SafFileReader
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
    onAdd: () -> Unit,
    onDirectoryClick: (String) -> Unit,
    onFileClick: (String) -> Unit,
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
                title = { Text(path.ifBlank { name }) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
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
}

internal fun folderZipFileName(repositoryName: String, ref: String, path: String): String {
    val folderName = path.trim('/').substringAfterLast('/').ifBlank { repositoryName }
    val safeRef = ref.removePrefix("refs/heads/").replace('/', '-')
    return "$repositoryName-$folderName-$safeRef.zip"
}
