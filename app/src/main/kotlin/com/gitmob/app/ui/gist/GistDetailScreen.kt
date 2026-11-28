package com.gitmob.app.ui.gist

import com.gitmob.app.data.model.LauncherShortcut
import com.gitmob.app.ui.shortcuts.ShortcutAddDialog
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen

import androidx.compose.ui.res.pluralStringResource
import com.gitmob.app.ui.translation.*
import com.gitmob.app.data.translation.TranslationTarget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.height
import com.gitmob.app.ui.common.SoraTextViewer
import com.gitmob.app.ui.common.formatDateTime
import com.gitmob.app.ui.common.rememberSoraViewerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gitmob.app.R
import com.gitmob.app.data.model.ConversationEditSummary
import com.gitmob.app.data.model.FavoriteItem
import com.gitmob.app.data.model.GistComment
import com.gitmob.app.data.model.GistDetail
import com.gitmob.app.data.model.GistFile
import com.gitmob.app.data.model.LibraryContent
import com.gitmob.app.data.model.SimpleUser
import com.gitmob.app.data.model.cardData
import com.gitmob.app.navigation.ConversationComposerTarget
import com.gitmob.app.ui.common.ActionButtonContent
import com.gitmob.app.ui.common.ActionFloatingButton
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.AppDropdownMenu as DropdownMenu
import com.gitmob.app.ui.common.AppDropdownMenuItem as DropdownMenuItem
import com.gitmob.app.ui.common.ConversationComposeRequest
import com.gitmob.app.ui.common.ConversationContentCard
import com.gitmob.app.ui.common.ConversationEditHistorySheet
import com.gitmob.app.ui.common.PressableSurface
import com.gitmob.app.ui.common.RepoMetadataLinkRow
import com.gitmob.app.ui.common.StateActionButton
import com.gitmob.app.ui.common.pressClickable
import com.gitmob.app.ui.common.quoteMarkdown
import com.gitmob.app.ui.library.AccountLibraryViewModel
import com.gitmob.app.ui.library.FavoriteSheet
import com.gitmob.app.ui.library.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GistDetailScreen(
    accountLibrary: AccountLibraryViewModel,
    gistId: String,
    onBack: () -> Unit,
    onOwnerClick: (String) -> Unit,
    onForkSourceClick: (String) -> Unit,
    onRevisions: () -> Unit,
    onForks: () -> Unit,
    onStargazers: (String) -> Unit,
    onAddFile: () -> Unit,
    onEditFile: (GistFile) -> Unit,
    onCompose: (ConversationComposeRequest) -> Unit,
    onForked: (GistDetail) -> Unit,
    onDeleted: () -> Unit,
    viewModel: GistDetailViewModel = hiltViewModel(),
    libraryViewModel: LibraryViewModel = hiltViewModel(),
) {
    LaunchedEffect(gistId) { viewModel.init(gistId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    LaunchedEffect(detail?.id) { detail?.id?.let { libraryViewModel.show(LibraryContent.Gist(it)) } }
    val librarySettings by accountLibrary.settings.collectAsStateWithLifecycle()
    val library by libraryViewModel.state.collectAsStateWithLifecycle()
    val libraryBusy by libraryViewModel.busy.collectAsStateWithLifecycle()
    val favorite = library.favorites.firstOrNull { it.id == detail?.id }
    var showShortcut by remember(gistId) { mutableStateOf(false) }
    var showFavorite by remember(gistId) { mutableStateOf(false) }
    var topMenuOpen by remember { mutableStateOf(false) }
    var descriptionDialog by remember { mutableStateOf(false) }
    var deleteGistDialog by remember { mutableStateOf(false) }
    var deletingFile by remember { mutableStateOf<GistFile?>(null) }
    var deletingComment by remember { mutableStateOf<GistComment?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.description ?: stringResource(R.string.gist_detail_title), maxLines = 1) },
                navigationIcon = {
                    ActionIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    ActionIconButton(onClick = viewModel::share, enabled = detail != null) {
                        Icon(Icons.Default.Share, stringResource(R.string.conversation_share))
                    }
                    Box {
                        ActionIconButton(onClick = { topMenuOpen = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.common_more))
                        }
                        DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                            if (detail != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.shortcuts_add)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.AddToHomeScreen, null) },
                                onClick = { topMenuOpen = false; showShortcut = true },
                            )
                            if (detail != null) TranslationMenuItem(TranslationTarget("gist:" + detail.id + ":description", detail.description.orEmpty()), { topMenuOpen = false }, R.string.translation_description)
                            if (library.loaded && librarySettings.value?.favoritesEnabled == true && detail != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (favorite == null) R.string.favorites_add else R.string.favorites_manage)) },
                                    leadingIcon = { Icon(Icons.Default.BookmarkBorder, null) },
                                    onClick = { topMenuOpen = false; showFavorite = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.gist_download_zip)) },
                                leadingIcon = { Icon(Icons.Default.Download, null) },
                                enabled = state.oldestVersion != null,
                                onClick = { topMenuOpen = false; viewModel.downloadZip() },
                            )
                            if (detail?.isOwnedByViewer == true) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gist_edit_description)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { topMenuOpen = false; descriptionDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.gist_delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { topMenuOpen = false; deleteGistDialog = true },
                                )
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        floatingActionButton = {
            if (detail?.commentsEnabled == true) ActionFloatingButton(
                onClick = {
                    onCompose(
                        ConversationComposeRequest(
                            target = ConversationComposerTarget.GIST_COMMENT,
                            subjectId = detail.id,
                        ),
                    )
                },
                icon = { Icon(Icons.AutoMirrored.Filled.Comment, null) },
                text = { Text(stringResource(R.string.conversation_comment)) },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        when {
            state.isLoading && detail == null -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.loadFailed && detail == null -> LoadFailed(Modifier.fillMaxSize().padding(innerPadding), viewModel::retry)
            detail != null -> GistDetailContent(
                detail = detail,
                state = state,
                onOwnerClick = onOwnerClick,
                onForkSourceClick = onForkSourceClick,
                onRevisions = onRevisions,
                onForks = onForks,
                onStargazers = { onStargazers(detail.nodeId) },
                onToggleStar = viewModel::toggleStar,
                onFork = { viewModel.fork(onForked) },
                onAddFile = onAddFile,
                onEditFile = onEditFile,
                onDeleteFile = { deletingFile = it },
                onDownloadFile = viewModel::downloadFile,
                onShareFile = viewModel::shareFile,
                onEditComment = {
                    onCompose(
                        ConversationComposeRequest(
                            target = ConversationComposerTarget.GIST_COMMENT,
                            subjectId = detail.id,
                            initialText = it.body,
                            commentId = it.id.toString(),
                        ),
                    )
                },
                onDeleteComment = { deletingComment = it },
                onQuoteComment = {
                    onCompose(
                        ConversationComposeRequest(
                            target = ConversationComposerTarget.GIST_COMMENT,
                            subjectId = detail.id,
                            initialText = quoteMarkdown(it.body),
                        ),
                    )
                },
                onEditHistory = viewModel::openEditHistory,
                onLoadMoreComments = viewModel::loadMoreComments,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        }
    }

    if (showFavorite && librarySettings.value?.favoritesEnabled == true && detail != null) {
        val groups by accountLibrary.groups.collectAsStateWithLifecycle()
        FavoriteSheet(FavoriteItem.Gist(detail.cardData()), favorite, groups.value, libraryBusy,
            { showFavorite = false }, libraryViewModel::createGroup, libraryViewModel::saveFavorite, libraryViewModel::removeFavorite,
            groupsFailed = groups.failed, retryGroups = accountLibrary::retryGroups)
    }
    if (showShortcut && detail != null) {
        ShortcutAddDialog(LauncherShortcut.gist(detail, stringResource(R.string.gist_detail_title)), { showShortcut = false })
    }
    if (descriptionDialog && detail != null) TextEditorDialog(
        title = stringResource(R.string.gist_edit_description),
        initialText = detail.description.orEmpty(),
        singleLine = false,
        isSaving = state.pendingMutation == GistMutation.EDIT_DESCRIPTION,
        enabled = !state.isMutating,
        onDismiss = { descriptionDialog = false },
        onSave = { viewModel.updateDescription(it) { descriptionDialog = false } },
    )
    deletingFile?.let { file -> ConfirmDialog(
        title = stringResource(R.string.gist_delete_file),
        busy = state.pendingMutation == GistMutation.DELETE_FILE,
        enabled = !state.isMutating,
        message = stringResource(R.string.gist_delete_file_confirm, file.filename),
        onDismiss = { deletingFile = null },
        onConfirm = { viewModel.deleteFile(file) { deletingFile = null } },
    ) }
    if (deleteGistDialog) ConfirmDialog(
        title = stringResource(R.string.gist_delete),
        busy = state.pendingMutation == GistMutation.DELETE_GIST,
        enabled = !state.isMutating,
        message = stringResource(R.string.gist_delete_confirm),
        onDismiss = { deleteGistDialog = false },
        onConfirm = { viewModel.deleteGist(onDeleted) },
    )
    deletingComment?.let { comment -> ConfirmDialog(
        title = stringResource(R.string.gist_delete_comment),
        busy = state.pendingMutation == GistMutation.DELETE_COMMENT,
        enabled = !state.isMutating,
        message = stringResource(R.string.gist_delete_comment_confirm),
        onDismiss = { deletingComment = null },
        onConfirm = { viewModel.deleteComment(comment) { deletingComment = null } },
    ) }
    if (state.editHistory.isOpen) ConversationEditHistorySheet(
        edits = state.editHistory.items,
        isLoading = state.editHistory.isLoading,
        isLoadingMore = state.editHistory.isLoadingMore,
        loadFailed = state.editHistory.loadFailed,
        hasNextPage = state.editHistory.hasNextPage,
        selectedEdit = state.editHistory.selectedEdit,
        onDismiss = viewModel::closeEditHistory,
        onLoadMore = viewModel::loadMoreEditHistory,
        onRetry = viewModel::retryEditHistory,
        onSelect = viewModel::selectEdit,
        onClearSelected = viewModel::clearSelectedEdit,
    )
}

@Composable
internal fun GistDetailContent(
    detail: GistDetail,
    state: GistDetailUiState,
    onOwnerClick: (String) -> Unit,
    onForkSourceClick: (String) -> Unit,
    onRevisions: () -> Unit,
    onForks: () -> Unit,
    onStargazers: () -> Unit,
    onToggleStar: () -> Unit,
    onFork: () -> Unit,
    onAddFile: () -> Unit,
    onEditFile: (GistFile) -> Unit,
    onDeleteFile: (GistFile) -> Unit,
    onDownloadFile: (GistFile) -> Unit,
    onShareFile: (GistFile) -> Unit,
    onEditComment: (GistComment) -> Unit,
    onDeleteComment: (GistComment) -> Unit,
    onQuoteComment: (GistComment) -> Unit,
    onEditHistory: (String) -> Unit,
    onLoadMoreComments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedFile by rememberSaveable(detail.id) { mutableStateOf<String?>(null) }
    var menuFile by remember { mutableStateOf<String?>(null) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = bottom + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("summary") {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    detail.owner?.let { owner ->
                        Row(
                            Modifier.pressClickable { onOwnerClick(owner.login) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AsyncImage(owner.avatarUrl, owner.login, Modifier.size(40.dp).clip(CircleShape))
                            Column {
                                Text(owner.login, fontWeight = FontWeight.SemiBold)
                                Text(formatDateTime(detail.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    detail.forkOf?.let { source ->
                        val sourceOwner = source.owner?.login
                        if (!sourceOwner.isNullOrBlank() && source.id.isNotBlank()) {
                            RepoMetadataLinkRow(
                                icon = Icons.AutoMirrored.Filled.CallSplit,
                                text = stringResource(R.string.gist_forked_from, sourceOwner),
                                contentDescription = stringResource(R.string.gist_open_source),
                                onClick = { onForkSourceClick(source.id) },
                            )
                        }
                    }
                    if (detail.description.isNullOrBlank()) Text(stringResource(R.string.gist_no_description), style = MaterialTheme.typography.titleMedium)
                    else TranslationText(TranslationTarget("gist:" + detail.id + ":description", detail.description), style = MaterialTheme.typography.titleMedium, showStatus = true)
                    Text(
                        stringResource(
                            R.string.gist_detail_metadata,
                            stringResource(if (detail.isPublic) R.string.work_visibility_public else R.string.common_private),
                            pluralStringResource(R.plurals.gist_files_count, detail.files.size, detail.files.size),
                            pluralStringResource(R.plurals.gist_comments_count, detail.commentCount, detail.commentCount),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onStargazers, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) {
                            Icon(Icons.Default.Star, null)
                            Text(stringResource(R.string.gist_stargazers_count, detail.stargazerCount), Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = onForks, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) {
                            Icon(Icons.AutoMirrored.Filled.CallSplit, null)
                            Text(stringResource(R.string.gist_forks_count, detail.forkCount), Modifier.padding(start = 6.dp))
                        }
                        StateActionButton(
                            selected = detail.isStarred,
                            busy = state.pendingMutation == GistMutation.STAR,
                            onClick = onToggleStar,
                            label = stringResource(R.string.gist_star),
                            selectedLabel = stringResource(R.string.gist_unstar),
                            enabled = !state.isMutating,
                            icon = Icons.Outlined.StarOutline,
                            selectedIcon = Icons.Default.Star,
                        )
                        if (!detail.isOwnedByViewer) {
                            OutlinedButton(onClick = onFork, enabled = !state.isMutating, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) {
                                ActionButtonContent(busy = state.pendingMutation == GistMutation.FORK, label = stringResource(R.string.gist_fork), icon = Icons.AutoMirrored.Filled.CallSplit)
                            }
                        }
                    }
                }
            }
        }
        item("revisions") {
            PressableSurface(
                onClick = onRevisions,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.gist_revisions), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.gist_revisions_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item("files-title") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gist_files), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (detail.isOwnedByViewer) TextButton(onClick = onAddFile, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Icon(Icons.Default.Add, null)
                    Text(stringResource(R.string.gist_add_file), Modifier.padding(start = 4.dp))
                }
            }
        }
        items(detail.files, key = { "file-${it.filename}" }) { file ->
            GistFileCard(
                file = file,
                expanded = expandedFile == file.filename,
                menuExpanded = menuFile == file.filename,
                canEdit = detail.isOwnedByViewer && file.isText,
                canDelete = detail.isOwnedByViewer && detail.files.size > 1,
                onToggle = {
                    val closing = expandedFile == file.filename
                    expandedFile = if (closing) null else file.filename
                    if (closing && menuFile == file.filename) menuFile = null
                },
                onMenuChange = { menuFile = if (it) file.filename else null },
                onEdit = { onEditFile(file) },
                onDelete = { onDeleteFile(file) },
                onDownload = { onDownloadFile(file) },
                onShare = { onShareFile(file) },
            )
        }
        item("comments-title") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gist_comments), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!detail.commentsEnabled) Text(stringResource(R.string.gist_comments_closed), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.comments.isEmpty()) item("comments-empty") {
            Text(stringResource(R.string.gist_no_comments), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        items(state.comments, key = { "comment-${it.id}" }) { comment ->
            val byViewer = comment.author?.login?.equals(detail.viewerLogin, ignoreCase = true) == true
            ConversationContentCard(
                author = comment.author?.let { SimpleUser(it.login, null, it.avatarUrl, null) },
                createdAt = comment.createdAt,
                bodyHtml = comment.bodyHtml,
                url = comment.url,
                authorAssociation = comment.authorAssociation,
                isThreadAuthor = comment.author?.login?.equals(detail.owner?.login, ignoreCase = true) == true,
                onQuoteReply = { onQuoteComment(comment) },
                onEdit = if (byViewer) ({ onEditComment(comment) }) else null,
                onDelete = if (byViewer) ({ onDeleteComment(comment) }) else null,
                editSummary = ConversationEditSummary(lastEditedAt = comment.updatedAt.takeIf { it != comment.createdAt }),
                onEditHistoryClick = comment.nodeId.takeIf(String::isNotBlank)?.let { nodeId -> { onEditHistory(nodeId) } },
                onAuthorClick = onOwnerClick,
            )
        }
        if (state.nextCommentPage != null) item("comments-more") {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                if (state.isLoadingMoreComments) CircularProgressIndicator(Modifier.size(24.dp))
                else TextButton(onClick = onLoadMoreComments, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_more)) }
            }
        }
    }
}

@Composable
internal fun GistFileCard(
    file: GistFile,
    expanded: Boolean,
    menuExpanded: Boolean,
    canEdit: Boolean,
    canDelete: Boolean,
    onToggle: () -> Unit,
    onMenuChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
) {
    val readingState = rememberSoraViewerState(file.filename)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(Modifier.fillMaxWidth().pressClickable(onClick = onToggle).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(file.filename, fontWeight = FontWeight.SemiBold)
                    Text(pluralStringResource(R.plurals.gist_file_metadata, file.size, file.language ?: file.type.orEmpty(), file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    ActionIconButton(onClick = { onMenuChange(true) }) { Icon(Icons.Default.MoreVert, stringResource(R.string.common_more)) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuChange(false) }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.conversation_share)) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { onMenuChange(false); onShare() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.common_download)) }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { onMenuChange(false); onDownload() })
                        if (canEdit) DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { onMenuChange(false); onEdit() })
                        if (canDelete) DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { onMenuChange(false); onDelete() })
                    }
                }
            }
            if (expanded) {
                HorizontalDivider()
                if (file.isText) SoraTextViewer(
                    text = file.content.orEmpty(),
                    wrapLines = false,
                    state = readingState,
                    modifier = Modifier.fillMaxWidth(),
                    embedded = true,
                    lineNumbersAtStart = true,
                ) else Text(
                    stringResource(if (file.isTruncated) R.string.gist_file_truncated else R.string.gist_file_binary),
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TextEditorDialog(
    title: String,
    initialText: String,
    singleLine: Boolean,
    isSaving: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(title, initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !isSaving,
                label = { Text(title) },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 6,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = enabled && !isSaving, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = isSaving, label = stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, message: String, busy: Boolean, enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { ActionButtonContent(busy = busy, label = stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun LoadFailed(modifier: Modifier, onRetry: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.common_load_failed))
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_retry)) }
    }
}
