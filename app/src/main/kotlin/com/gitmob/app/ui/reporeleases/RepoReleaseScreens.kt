package com.gitmob.app.ui.reporeleases

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import com.gitmob.app.ui.common.MarkdownWebView
import com.gitmob.app.ui.common.RepositoryContextTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoReleaseListScreen(owner: String, name: String, permission: RepoPermission?, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, onReleaseClick: (String) -> Unit, onCreate: () -> Unit, viewModel: RepoReleaseListViewModel = hiltViewModel()) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name, permission) }; val state by viewModel.state.collectAsStateWithLifecycle(); var delete by remember { mutableStateOf<RepoRelease?>(null) }
    Scaffold(topBar = { TopAppBar(title = { RepositoryContextTitle(owner, name, stringResource(R.string.repo_releases), onOwnerClick, onRepositoryClick) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, actions = { if (state.capabilities.canPush) IconButton(onClick = onCreate) { Icon(Icons.Default.Add, stringResource(R.string.release_new)) } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) }, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) { padding ->
        PullToRefreshBox(state.isLoading && state.releases.isNotEmpty(), viewModel::refresh, Modifier.fillMaxSize().padding(padding)) { when { state.isLoading && state.releases.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center)); state.loadFailed -> ReleaseRetry(viewModel::retry); state.releases.isEmpty() -> Text(stringResource(R.string.release_empty), modifier = Modifier.align(Alignment.Center)); else -> LazyColumn { items(state.releases, key = { it.id }) { release -> ReleaseRow(release, state.capabilities.canPush, { onReleaseClick(release.tagName) }) { delete = release }; HorizontalDivider() }; if (state.hasNextPage) item { LaunchedEffect(state.releases.size) { viewModel.loadMore() } } } } }
    }
    delete?.let { release -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(stringResource(R.string.release_delete_title)) }, text = { Text(stringResource(R.string.release_delete_message)) }, dismissButton = { TextButton(onClick = { delete = null }) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(onClick = { viewModel.delete(release) { delete = null } }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } }) }
}

@Composable private fun ReleaseRow(release: RepoRelease, canManage: Boolean, click: () -> Unit, delete: () -> Unit) { Column(Modifier.fillMaxWidth().clickable(onClick = click).padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (release.draft) Icons.Default.Drafts else Icons.Default.Sell, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(release.name ?: release.tagName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(release.tagName, style = MaterialTheme.typography.bodySmall); Text(stringResource(if (release.draft) R.string.release_draft else if (release.prerelease) R.string.release_prerelease else R.string.release_published), style = MaterialTheme.typography.labelSmall) }; if (canManage) IconButton(onClick = delete) { Icon(Icons.Default.Delete, stringResource(R.string.common_delete)) } }; Text(stringResource(R.string.release_assets_count, release.assets.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoReleaseDetailScreen(owner: String, name: String, tag: String?, releaseId: Long?, permission: RepoPermission?, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, onEdit: (Long) -> Unit, viewModel: RepoReleaseDetailViewModel = hiltViewModel()) {
    val context = LocalContext.current
    LaunchedEffect(owner, name, tag, releaseId) { viewModel.init(owner, name, tag, releaseId, permission) }; val state by viewModel.state.collectAsStateWithLifecycle(); var menu by remember { mutableStateOf(false) }; var editorAsset by remember { mutableStateOf<RepoReleaseAsset?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::uploadAsset)
    }
    Scaffold(topBar = { TopAppBar(title = { RepositoryContextTitle(owner, name, state.release?.name ?: state.release?.tagName ?: stringResource(R.string.repo_releases), onOwnerClick, onRepositoryClick) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, actions = {
        state.release?.let { release ->
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.issue_more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.conversation_share)) },
                        leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = { menu = false; viewModel.share() },
                    )
                    if (state.capabilities.canPush) DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menu = false; onEdit(release.id) })
                    if (state.capabilities.canPush) DropdownMenuItem(text = { Text(stringResource(R.string.release_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; viewModel.confirmDelete(true) })
                }
            }
        }
    }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) }, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) { padding ->
        when { state.isLoading && state.release == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; state.loadFailed -> ReleaseRetry(viewModel::retry); state.release != null -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) { item { val release = state.release!!; Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(release.name ?: release.tagName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text("${release.tagName} · ${release.targetCommitish}"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { AssistChip(onClick = {}, label = { Text(stringResource(if (release.draft) R.string.release_draft else if (release.prerelease) R.string.release_prerelease else R.string.release_published)) }); Text(release.publishedAt?.take(10) ?: release.createdAt.take(10)) } }; if (release.bodyHtml?.isNotBlank() == true) MarkdownWebView(release.bodyHtml, Modifier.fillMaxWidth()) else Text(release.body, modifier = Modifier.padding(horizontal = 16.dp)) }; item { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.release_assets), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); if (state.capabilities.canPush) IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.upload.isActive && !state.isLoading) { Icon(Icons.Default.UploadFile, stringResource(R.string.release_upload_asset)) } } }; item { ReleaseAssetUploadStatus(state.upload, state.isLoading, viewModel::cancelUpload, viewModel::load) }; items(state.release!!.assets, key = { it.id }) { asset -> val opening = asset.id in state.openingAssetIds; ListItem(headlineContent = { Text(asset.name) }, supportingContent = { Text(stringResource(R.string.release_asset_meta, asset.downloadCount, Formatter.formatShortFileSize(context, asset.size))) }, trailingContent = { Row { if (state.capabilities.canPush) IconButton(onClick = { editorAsset = asset }) { Icon(Icons.Default.Edit, stringResource(R.string.common_edit)) }; IconButton(onClick = { viewModel.downloadAsset(asset) }, enabled = !opening) { if (opening) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, stringResource(R.string.common_download)) }; if (state.capabilities.canPush) IconButton(onClick = { viewModel.confirmDeleteAsset(asset) }) { Icon(Icons.Default.Delete, stringResource(R.string.common_delete)) } } }) } } }
    }
    if (state.pendingDelete) AlertDialog(onDismissRequest = { viewModel.confirmDelete(false) }, title = { Text(stringResource(R.string.release_delete_title)) }, text = { Text(stringResource(R.string.release_delete_message)) }, dismissButton = { TextButton(onClick = { viewModel.confirmDelete(false) }) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(onClick = { viewModel.delete(onBack) }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } })
    state.pendingDeleteAsset?.let { asset -> AlertDialog(onDismissRequest = { viewModel.confirmDeleteAsset(null) }, title = { Text(stringResource(R.string.release_delete_asset_title)) }, text = { Text(asset.name) }, dismissButton = { TextButton(onClick = { viewModel.confirmDeleteAsset(null) }) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(onClick = viewModel::deleteAsset) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } }) }
    editorAsset?.let { asset -> ReleaseAssetEditDialog(asset, { editorAsset = null }) { fileName, label -> viewModel.updateAsset(asset, fileName, label) { editorAsset = null } } }
}

@Composable
private fun ReleaseAssetUploadStatus(
    upload: ReleaseAssetUploadState,
    isRefreshing: Boolean,
    cancel: () -> Unit,
    refresh: () -> Unit,
) {
    if (upload.phase == ReleaseAssetUploadPhase.IDLE) return
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val message = when {
            upload.isCancelling -> R.string.release_asset_cancelling
            upload.phase == ReleaseAssetUploadPhase.PREPARING -> R.string.release_asset_preparing
            upload.phase == ReleaseAssetUploadPhase.UPLOADING -> R.string.release_asset_uploading
            upload.phase == ReleaseAssetUploadPhase.SUCCESS -> R.string.release_asset_upload_success
            upload.phase == ReleaseAssetUploadPhase.FAILURE -> R.string.release_asset_upload_failed
            else -> R.string.release_asset_upload_cancelled
        }
        Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
        if (upload.phase == ReleaseAssetUploadPhase.UPLOADING && upload.total > 0) {
            LinearProgressIndicator(progress = { (upload.sent.toDouble() / upload.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.release_asset_progress, Formatter.formatShortFileSize(context, upload.sent), Formatter.formatShortFileSize(context, upload.total)), style = MaterialTheme.typography.bodySmall)
        } else if (upload.isActive) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (upload.isActive) TextButton(onClick = cancel, enabled = !upload.isCancelling) { Text(stringResource(R.string.common_cancel)) }
        if (upload.phase == ReleaseAssetUploadPhase.CANCELLED) {
            TextButton(onClick = refresh, enabled = !isRefreshing) { Text(stringResource(R.string.common_refresh)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoReleaseEditorScreen(owner: String, name: String, releaseId: Long?, permission: RepoPermission?, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, onSaved: (String) -> Unit, viewModel: RepoReleaseEditorViewModel = hiltViewModel()) {
    LaunchedEffect(owner, name, releaseId, permission) { viewModel.init(owner, name, releaseId, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft = state.draft
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    fun requestExit() {
        if (state.isSaving) return
        if (state.isDirty) showDiscardConfirmation = true else onBack()
    }
    // BackHandler is invoked only for a completed back action; cancelling a gesture leaves the draft intact.
    BackHandler { requestExit() }
    LaunchedEffect(state.savedRelease) { state.savedRelease?.let { onSaved(it.tagName) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    RepositoryContextTitle(
                        owner, name, stringResource(if (releaseId == null) R.string.release_new else R.string.release_edit),
                        { login -> if (!state.isSaving) onOwnerClick(login) },
                        { repoOwner, repoName -> if (!state.isSaving) onRepositoryClick(repoOwner, repoName) },
                    )
                },
                navigationIcon = { IconButton(onClick = { requestExit() }, enabled = !state.isSaving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        when (state.phase) {
            ReleaseEditorPhase.LOADING -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ReleaseEditorPhase.LOAD_FAILED -> Box(Modifier.fillMaxSize().padding(padding)) { ReleaseRetry(viewModel::retry) }
            ReleaseEditorPhase.EDITABLE, ReleaseEditorPhase.SAVING -> {
                val enabled = !state.isSaving && state.savedRelease == null
                LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { OutlinedTextField(draft.tag, viewModel::editTag, enabled = enabled, label = { Text(stringResource(R.string.release_tag)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(draft.target, viewModel::editTarget, enabled = enabled, label = { Text(stringResource(R.string.release_target)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(draft.title, viewModel::editTitle, enabled = enabled, label = { Text(stringResource(R.string.release_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item {
                        TextButton(onClick = viewModel::generateNotes, enabled = enabled && state.capabilities.canPush && draft.tag.isNotBlank() && !state.isGenerating) {
                            if (state.isGenerating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.AutoAwesome, null)
                            Text(stringResource(R.string.release_generate_notes))
                        }
                    }
                    item { OutlinedTextField(draft.body, viewModel::editBody, enabled = enabled, label = { Text(stringResource(R.string.release_notes)) }, minLines = 10, modifier = Modifier.fillMaxWidth()) }
                    item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.draft, viewModel::editDraft, enabled = enabled); Text(stringResource(R.string.release_draft)) } }
                    item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.prerelease, viewModel::editPrerelease, enabled = enabled); Text(stringResource(R.string.release_prerelease)) } }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { requestExit() }, enabled = enabled) { Text(stringResource(R.string.common_cancel)) }
                            Button(onClick = viewModel::save, enabled = enabled && state.capabilities.canPush && draft.tag.isNotBlank()) {
                                if (state.isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.common_save))
                            }
                        }
                    }
                }
            }
        }
    }
    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text(stringResource(R.string.conversation_discard_title)) },
            text = { Text(stringResource(R.string.conversation_discard_message)) },
            dismissButton = { TextButton(onClick = { showDiscardConfirmation = false }) { Text(stringResource(R.string.common_cancel)) } },
            confirmButton = { TextButton(onClick = { showDiscardConfirmation = false; onBack() }) { Text(stringResource(R.string.conversation_discard), color = MaterialTheme.colorScheme.error) } },
        )
    }
}

@Composable private fun ReleaseAssetEditDialog(asset: RepoReleaseAsset, dismiss: () -> Unit, save: (String, String?) -> Unit) { var name by remember(asset) { mutableStateOf(asset.name) }; var label by remember(asset) { mutableStateOf(asset.label.orEmpty()) }; AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.release_edit_asset)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.release_asset_name)) }); OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.release_asset_label)) }) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { Button(onClick = { save(name, label.ifBlank { null }) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.common_save)) } }) }
@Composable private fun ReleaseRetry(retry: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = retry) { Text(stringResource(R.string.common_retry)) } } }
