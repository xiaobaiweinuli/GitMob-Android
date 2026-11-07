package com.gitmob.app.ui.repocode

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.translation.TranslationTarget
import com.gitmob.app.ui.translation.TranslationFileMenuItem
import com.gitmob.app.ui.translation.TranslationHtml
import com.gitmob.app.ui.common.RepositoryContextTitle
import com.gitmob.app.ui.common.RepositoryImagePreview
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoFileDetailScreen(
    owner: String,
    name: String,
    ref: String,
    path: String,
    onBack: () -> Unit,
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onDirectoryFound: () -> Unit,
    onDocumentLink: (RepoDocumentContext) -> Unit = {},
    onDocumentExternalLink: (String) -> Unit = {},
    viewModel: RepoFileDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(owner, name, ref, path) { viewModel.init(owner, name, ref, path) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.isDirectory) { if (state.isDirectory) onDirectoryFound() }
    var confirmDelete by remember { mutableStateOf(false) }
    val fileTarget = remember(owner, name, ref, path, state.file, state.previewHtml) {
        state.previewHtml?.takeIf { state.file?.let { file -> !file.isBinary && !file.isTruncated && file.text != null && !state.imagePreview } == true }
            ?.let { TranslationTarget("file:$owner/$name/$ref/$path", it, html = true) }
    }
    val previewScroll = rememberScrollState()
    val sourceScroll = rememberScrollState()
    val sourceHorizontalScroll = rememberScrollState()
    val deleteCommitMessage = stringResource(R.string.repo_delete_file_title)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    RepositoryContextTitle(
                        owner = owner,
                        repository = name,
                        pageTitle = path,
                        onOwnerClick = onOwnerClick,
                        onRepositoryClick = onRepositoryClick,
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    RepoFileMenu(
                        state = state,
                        translationTarget = fileTarget,
                        onPreviewModeChange = viewModel::setPreviewMode,
                        onWrapLinesChange = viewModel::setWrapLines,
                        onHistory = onHistory,
                        onDownload = viewModel::downloadFile,
                        onEdit = onEdit,
                        onDelete = { confirmDelete = true },
                    )
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        when {
            state.isLoading || state.isDirectory -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadFailed || state.file == null -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_load_failed)); OutlinedButton(onClick = viewModel::retry) { Text(stringResource(R.string.common_retry)) } }
            state.file?.isBinary == true && !state.imagePreview -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.common_file_no_preview)) }
            else -> {
                val file = requireNotNull(state.file)
                val previewHtml = state.previewHtml
                Column(Modifier.fillMaxSize().padding(padding)) {
                    when {
                        file.text == "" && !state.imagePreview -> {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.repo_file_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        state.imagePreview && state.previewMode -> key(state.previewRevision) {
                            RepositoryImagePreview(requireNotNull(state.previewContext), file.byteSize, Modifier.weight(1f))
                        }
                        state.previewMode && previewHtml != null -> {
                            // Owned by this preview composition; a return from source creates a fresh scope.
                            val documentResources = remember(previewHtml, state.previewContext, state.previewRevision, viewModel) {
                                state.previewContext?.let(viewModel::createDocumentResources)
                            }
                            RepoFilePreviewViewport(previewScroll, Modifier.weight(1f)) {
                                TranslationHtml(
                                    translationKey = "file:$owner/$name/$ref/$path",
                                    bodyHtml = previewHtml,
                                    documentResources = documentResources,
                                    onDocumentLink = onDocumentLink,
                                    onExternalLink = onDocumentExternalLink,
                                )
                            }
                        }
                        state.sourceAvailable -> RepoFileSource(
                            text = requireNotNull(file.text),
                            wrapLines = state.wrapLines,
                            verticalScrollState = sourceScroll,
                            horizontalScrollState = sourceHorizontalScroll,
                            modifier = Modifier.weight(1f),
                        )
                        else -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.repo_file_text_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (file.isTruncated) Text(
                        stringResource(R.string.repo_file_truncated),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
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

/** Keep intrinsic menu width: no empty leading slot, weighted label or reserved trailing space. */
@Composable
internal fun RepoFileMenu(
    state: RepoFileDetailUiState,
    onPreviewModeChange: (Boolean) -> Unit,
    onWrapLinesChange: (Boolean) -> Unit,
    onHistory: () -> Unit,
    onDownload: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    translationTarget: TranslationTarget? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val file = state.file
    val canModify = file != null && !file.isCommitSnapshot && !file.isArchived &&
        file.capabilities.canPush && file.capabilities.canPushToProtectedBranch
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.common_more)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (state.canSwitchDisplayMode) RepoFileMenuAction(
                label = stringResource(if (state.previewMode) R.string.repo_file_source else R.string.conversation_preview_tab),
                icon = if (state.previewMode) Icons.Default.Code else Icons.Default.Visibility,
                onClick = { expanded = false; onPreviewModeChange(!state.previewMode) },
            )
            if (state.sourceAvailable && !state.previewMode) Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = state.wrapLines, role = Role.Switch, onValueChange = onWrapLinesChange)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.repo_file_wrap_lines), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = state.wrapLines, onCheckedChange = null)
            }
            if (state.canSwitchDisplayMode || state.sourceAvailable && !state.previewMode) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            TranslationFileMenuItem(
                target = translationTarget,
                onDismiss = { expanded = false },
                onOpen = { if (state.previewHtml != null) onPreviewModeChange(true) },
            )
            if (file?.isCommitSnapshot == false) RepoFileMenuAction(
                stringResource(R.string.repo_commits), Icons.Default.History,
                onClick = { expanded = false; onHistory() },
            )
            RepoFileMenuAction(
                stringResource(R.string.common_download), Icons.Default.Download,
                enabled = !state.isDownloading, busy = state.isDownloading,
                onClick = { expanded = false; onDownload() },
            )
            if (canModify && !file.isBinary) RepoFileMenuAction(
                stringResource(R.string.common_edit), Icons.Default.Edit,
                onClick = { expanded = false; onEdit() },
            )
            if (canModify) RepoFileMenuAction(
                stringResource(R.string.common_delete), Icons.Default.Delete,
                enabled = !state.isDeleting, color = MaterialTheme.colorScheme.error,
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

@Composable
private fun RepoFileMenuAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val contentColor = if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = contentColor)
        Text(label, color = contentColor, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Restore only after asynchronous document layout can contain the previous scroll offset. */
@Composable
internal fun RepoFilePreviewViewport(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val restoreOffset = remember { scrollState.value }
    var restored by remember { mutableStateOf(restoreOffset == 0) }
    var contentHeight by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(contentHeight, viewportHeight) {
        if (!restored && viewportHeight > 0 && contentHeight - viewportHeight >= restoreOffset) {
            scrollState.scrollTo(restoreOffset)
            restored = true
        }
    }
    // An explicit user scroll takes precedence if images or WebView layout are still pending.
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }.first { it }
        restored = true
    }
    Column(modifier.onSizeChanged { viewportHeight = it.height }.verticalScroll(scrollState)) {
        Box(Modifier.fillMaxWidth().onSizeChanged { contentHeight = it.height }) { content() }
    }
}
