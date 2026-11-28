package com.gitmob.app.ui.reporeleases

import com.gitmob.app.ui.common.pressToggleable
import com.gitmob.app.ui.common.formatDateTime
import androidx.compose.ui.semantics.Role
import com.gitmob.app.ui.common.StatusBadge
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import com.gitmob.app.data.translation.TranslationTarget
import com.gitmob.app.ui.translation.TranslationHtml
import com.gitmob.app.ui.translation.TranslationMenuItem
import com.gitmob.app.ui.translation.TranslationText
import com.gitmob.app.ui.common.RepositoryContextTitle
import com.gitmob.app.ui.common.MarkdownBodyEditor
import com.gitmob.app.ui.common.MarkdownEditorTab
import com.gitmob.app.ui.common.MarkdownEditorToolbar
import com.gitmob.app.ui.common.rememberSoraEditorController
import com.gitmob.app.ui.common.ActionButtonContent
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.ActionProgressIcon
import com.gitmob.app.ui.common.AppDropdownMenu as DropdownMenu
import com.gitmob.app.ui.common.AppDropdownMenuItem as DropdownMenuItem
import com.gitmob.app.ui.common.ListFeedback
import com.gitmob.app.ui.common.ListFeedbackState
import com.gitmob.app.ui.common.informationItem
import com.gitmob.app.ui.common.pressClickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoReleaseListScreen(owner: String, name: String, permission: RepoPermission?, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, onReleaseClick: (String) -> Unit, onCreate: () -> Unit, viewModel: RepoReleaseListViewModel = hiltViewModel()) {
    LaunchedEffect(owner, name) { viewModel.init(owner, name, permission) }; val state by viewModel.state.collectAsStateWithLifecycle(); var delete by remember { mutableStateOf<RepoRelease?>(null) }
    Scaffold(topBar = { TopAppBar(title = { RepositoryContextTitle(owner, name, stringResource(R.string.repo_releases), onOwnerClick, onRepositoryClick) }, navigationIcon = { ActionIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, actions = { if (state.capabilities.canPush) ActionIconButton(onClick = onCreate) { Icon(Icons.Default.Add, stringResource(R.string.release_new)) } }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) }, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) { padding ->
        PullToRefreshBox(state.isLoading && state.releases.isNotEmpty(), viewModel::refresh, Modifier.fillMaxSize().padding(padding)) {
            val feedback = when {
                state.isLoading && state.releases.isEmpty() -> ListFeedbackState.LOADING
                state.loadFailed -> ListFeedbackState.ERROR
                state.releases.isEmpty() -> ListFeedbackState.EMPTY
                else -> ListFeedbackState.CONTENT
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = 8.dp + WindowInsets.navigationBars.union(WindowInsets.captionBar)
                        .asPaddingValues().calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (feedback == ListFeedbackState.CONTENT) {
                    items(state.releases, key = { it.id }) { release ->
                        Box(Modifier.animateItem().informationItem()) {
                            ReleaseRow(release, state.capabilities.canPush, { onReleaseClick(release.tagName) }) { delete = release }
                        }
                    }
                    if (state.hasNextPage) item(key = "more") { LaunchedEffect(state.releases.size) { viewModel.loadMore() } }
                }

            }
            ListFeedback(feedback, viewModel::retry, emptyMessage = stringResource(R.string.release_empty))
        }
    }
    delete?.let { release -> AlertDialog(onDismissRequest = { if (!state.isDeleting) delete = null }, title = { Text(stringResource(R.string.release_delete_title)) }, text = { Text(stringResource(R.string.release_delete_message)) }, dismissButton = { TextButton(enabled = !state.isDeleting, onClick = { delete = null }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(enabled = !state.isDeleting, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = { viewModel.delete(release) { delete = null } }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = state.isDeleting, label = stringResource(R.string.common_delete)) } }) }
}

@Composable private fun ReleaseRow(release: RepoRelease, canManage: Boolean, click: () -> Unit, delete: () -> Unit) { Column(Modifier.fillMaxWidth().pressClickable(onClick = click).padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (release.draft) Icons.Default.Drafts else Icons.Default.Sell, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(release.name ?: release.tagName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(release.tagName, style = MaterialTheme.typography.bodySmall); Text(stringResource(if (release.draft) R.string.release_draft else if (release.prerelease) R.string.release_prerelease else R.string.release_published), style = MaterialTheme.typography.labelSmall) }; if (canManage) ActionIconButton(onClick = delete) { Icon(Icons.Default.Delete, stringResource(R.string.common_delete)) } }; Text(pluralStringResource(R.plurals.release_assets_count, release.assets.size, release.assets.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoReleaseDetailScreen(owner: String, name: String, tag: String?, releaseId: Long?, permission: RepoPermission?, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, onEdit: (Long) -> Unit, viewModel: RepoReleaseDetailViewModel = hiltViewModel()) {
    val context = LocalContext.current
    LaunchedEffect(owner, name, tag, releaseId) { viewModel.init(owner, name, tag, releaseId, permission) }; val state by viewModel.state.collectAsStateWithLifecycle(); var menu by remember { mutableStateOf(false) }; var editorAsset by remember { mutableStateOf<RepoReleaseAsset?>(null) }
    val translationTarget = remember(owner, name, state.release?.id, state.release?.bodyHtml, state.release?.body) {
        state.release?.let { release ->
            val html = release.bodyHtml?.takeIf { it.isNotBlank() }
            TranslationTarget("release:$owner/$name/${release.id}", html ?: release.body, html = html != null)
                .takeIf { it.content.isNotBlank() }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::uploadAsset)
    }
    Scaffold(topBar = { TopAppBar(title = { RepositoryContextTitle(owner, name, state.release?.name ?: state.release?.tagName ?: stringResource(R.string.repo_releases), onOwnerClick, onRepositoryClick) }, navigationIcon = { ActionIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } }, actions = {
        state.release?.let { release ->
            Box {
                ActionIconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.issue_more)) }
                DropdownMenu(menu, { menu = false }) {
                    translationTarget?.let { TranslationMenuItem(it, onDismiss = { menu = false }) }
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
        when { state.isLoading && state.release == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; state.loadFailed -> ReleaseRetry(viewModel::retry); state.release != null -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) { item { val release = state.release!!; Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(release.name ?: release.tagName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text("${release.tagName} · ${release.targetCommitish}"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusBadge(label = { Text(stringResource(if (release.draft) R.string.release_draft else if (release.prerelease) R.string.release_prerelease else R.string.release_published)) }); Text(formatDateTime(release.publishedAt ?: release.createdAt)) } }; translationTarget?.let { target ->
            if (target.html) TranslationHtml(target.key, target.content, Modifier.fillMaxWidth())
            else TranslationText(target, Modifier.padding(horizontal = 16.dp), showStatus = true)
        } }; item { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.release_assets), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); if (state.capabilities.canPush) ActionIconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.upload.isActive && !state.isLoading) { ActionProgressIcon(busy = state.upload.isActive, busyDescription = stringResource(R.string.release_asset_uploading)) { Icon(Icons.Default.UploadFile, stringResource(R.string.release_upload_asset)) } } } }; item { ReleaseAssetUploadStatus(state.upload, state.isLoading, viewModel::cancelUpload, viewModel::load) }; items(state.release!!.assets, key = { it.id }) { asset -> val opening = asset.id in state.openingAssetIds; androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            ListItem(content = { Text(asset.name) }, supportingContent = { Text(pluralStringResource(R.plurals.release_asset_meta, asset.downloadCount, asset.downloadCount, Formatter.formatShortFileSize(context, asset.size))) }, trailingContent = { Row { if (state.capabilities.canPush) ActionIconButton(onClick = { editorAsset = asset }) { Icon(Icons.Default.Edit, stringResource(R.string.common_edit)) }; ActionIconButton(onClick = { viewModel.downloadAsset(asset) }, enabled = !opening) { ActionProgressIcon(busy = opening, busyDescription = stringResource(R.string.common_download)) { Icon(Icons.Default.Download, stringResource(R.string.common_download)) } }; if (state.capabilities.canPush) ActionIconButton(onClick = { viewModel.confirmDeleteAsset(asset) }) { Icon(Icons.Default.Delete, stringResource(R.string.common_delete)) } } }, shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large), colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
        } } } }
    }
    if (state.pendingDelete) AlertDialog(onDismissRequest = { viewModel.confirmDelete(false) }, title = { Text(stringResource(R.string.release_delete_title)) }, text = { Text(stringResource(R.string.release_delete_message)) }, dismissButton = { TextButton(enabled = !state.isDeleting, onClick = { viewModel.confirmDelete(false) }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(enabled = !state.isDeleting, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = { viewModel.delete(onBack) }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = state.isDeleting, label = stringResource(R.string.common_delete)) } })
    state.pendingDeleteAsset?.let { asset -> AlertDialog(onDismissRequest = { viewModel.confirmDeleteAsset(null) }, title = { Text(stringResource(R.string.release_delete_asset_title)) }, text = { Text(asset.name) }, dismissButton = { TextButton(enabled = !state.isDeletingAsset, onClick = { viewModel.confirmDeleteAsset(null) }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } }, confirmButton = { TextButton(enabled = !state.isDeletingAsset, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = viewModel::deleteAsset, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = state.isDeletingAsset, label = stringResource(R.string.common_delete)) } }) }
    editorAsset?.let { asset -> ReleaseAssetEditDialog(asset, state.isSavingAsset, { editorAsset = null }) { fileName, label -> viewModel.updateAsset(asset, fileName, label) { editorAsset = null } } }
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
        if (upload.isActive) TextButton(onClick = cancel, enabled = !upload.isCancelling, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) }
        if (upload.phase == ReleaseAssetUploadPhase.CANCELLED) {
            TextButton(onClick = refresh, enabled = !isRefreshing, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = isRefreshing, label = stringResource(R.string.common_refresh)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoReleaseEditorScreen(owner: String, name: String, releaseId: Long?, permission: RepoPermission?, onBack: () -> Unit, onOwnerClick: (String) -> Unit, onRepositoryClick: (String, String) -> Unit, onSaved: (String) -> Unit, viewModel: RepoReleaseEditorViewModel = hiltViewModel()) {
    LaunchedEffect(owner, name, releaseId, permission) { viewModel.init(owner, name, releaseId, permission) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val bodyController = rememberSoraEditorController(viewModel.bodyState)
    val enabled = !state.isSaving && state.savedRelease == null
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    fun requestExit() {
        if (state.isSaving) return
        if (viewModel.isDirty) showDiscardConfirmation = true else onBack()
    }
    // BackHandler is invoked only for a completed back action; cancelling a gesture leaves the draft intact.
    BackHandler { requestExit() }
    LaunchedEffect(state.savedRelease) { state.savedRelease?.let { onSaved(it.tagName) } }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    RepositoryContextTitle(
                        owner, name, stringResource(if (releaseId == null) R.string.release_new else R.string.release_edit),
                        { login -> if (!state.isSaving) onOwnerClick(login) },
                        { repoOwner, repoName -> if (!state.isSaving) onRepositoryClick(repoOwner, repoName) },
                    )
                },
                navigationIcon = { ActionIconButton(onClick = { requestExit() }, enabled = !state.isSaving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        bottomBar = {
            if ((state.phase == ReleaseEditorPhase.EDITABLE || state.phase == ReleaseEditorPhase.SAVING) &&
                state.bodyEditor.selectedTab == MarkdownEditorTab.EDIT
            ) {
                MarkdownEditorToolbar(
                    textState = viewModel.bodyState,
                    controller = bodyController,
                    enabled = enabled,
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ),
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        when (state.phase) {
            ReleaseEditorPhase.LOADING -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ReleaseEditorPhase.LOAD_FAILED -> Box(Modifier.fillMaxSize().padding(padding)) { ReleaseRetry(viewModel::retry) }
            ReleaseEditorPhase.EDITABLE, ReleaseEditorPhase.SAVING -> {
                LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { OutlinedTextField(draft.tag, viewModel::editTag, enabled = enabled, label = { Text(stringResource(R.string.release_tag)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) }
                    item { OutlinedTextField(draft.target, viewModel::editTarget, enabled = enabled, label = { Text(stringResource(R.string.release_target)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) }
                    item { OutlinedTextField(draft.title, viewModel::editTitle, enabled = enabled, label = { Text(stringResource(R.string.release_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) }
                    item {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = viewModel::generateNotes, enabled = enabled && state.capabilities.canPush && draft.tag.isNotBlank() && !state.isGenerating, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                                ActionProgressIcon(busy = state.isGenerating) { Icon(Icons.Default.AutoAwesome, null) }
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.release_generate_notes))
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                ReleaseOption(draft.draft, viewModel::editDraft, stringResource(R.string.release_draft), enabled)
                                ReleaseOption(draft.prerelease, viewModel::editPrerelease, stringResource(R.string.release_prerelease), enabled)
                            }
                        }
                    }
                    item {
                        Text(stringResource(R.string.release_notes), style = MaterialTheme.typography.titleSmall)
                        MarkdownBodyEditor(
                            textState = viewModel.bodyState,
                            state = state.bodyEditor,
                            onTabSelected = viewModel::selectBodyEditorTab,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            embedded = true,
                            minLines = 6,
                            controller = bodyController,
                            showToolbar = false,
                        )
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { requestExit() }, enabled = enabled, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) }
                            Button(onClick = viewModel::save, enabled = enabled && state.capabilities.canPush && draft.tag.isNotBlank(), shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) {
                                ActionButtonContent(busy = state.isSaving, label = stringResource(R.string.common_save))
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
            dismissButton = { TextButton(onClick = { showDiscardConfirmation = false }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } },
            confirmButton = { TextButton(onClick = { showDiscardConfirmation = false; onBack() }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.conversation_discard), color = MaterialTheme.colorScheme.error) } },
        )
    }
}

@Composable
private fun ReleaseOption(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, enabled: Boolean) {
    Row(
        modifier = Modifier
            .pressToggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun ReleaseAssetEditDialog(asset: RepoReleaseAsset, busy: Boolean, dismiss: () -> Unit, save: (String, String?) -> Unit) {
    var name by remember(asset) { mutableStateOf(asset.name) }
    var label by remember(asset) { mutableStateOf(asset.label.orEmpty()) }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(R.string.release_edit_asset)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text(stringResource(R.string.release_asset_name)) }, shape = MaterialTheme.shapes.medium)
                OutlinedTextField(label, { label = it }, enabled = !busy, label = { Text(stringResource(R.string.release_asset_label)) }, shape = MaterialTheme.shapes.medium)
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = { Button(onClick = { save(name, label.ifBlank { null }) }, enabled = !busy && name.isNotBlank(), shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { ActionButtonContent(busy = busy, label = stringResource(R.string.common_save)) } },
    )
}
@Composable private fun ReleaseRetry(retry: () -> Unit) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); Button(onClick = retry, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_retry)) } } }
