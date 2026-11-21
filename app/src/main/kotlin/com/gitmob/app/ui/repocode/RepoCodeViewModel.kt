package com.gitmob.app.ui.repocode

import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.ui.common.AccountViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoCodeTree
import com.gitmob.app.data.model.RepoForkSyncInfo
import com.gitmob.app.data.model.RepoForkSyncStatus
import com.gitmob.app.data.model.RepoContentSource
import com.gitmob.app.data.model.RepoLinkKind
import com.gitmob.app.data.model.RepoLinkTarget
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.data.repository.RepoPullRequestRepository
import com.gitmob.app.core.storage.SafSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoCodeUiState(
    val target: RepoLinkTarget? = null,
    val tree: RepoCodeTree? = null,
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val isUploading: Boolean = false,
    val isFolderExporting: Boolean = false,
    val folderExportCompleted: Int = 0,
    val folderExportTotal: Int = 0,
    val openingDownload: Boolean = false,
    val isSyncDialogOpen: Boolean = false,
    val syncInfo: RepoForkSyncInfo? = null,
    val isLoadingSync: Boolean = false,
    val isSyncing: Boolean = false,
    val isCheckingSyncPullRequest: Boolean = false,
    val showDiscardConfirmation: Boolean = false,
    val showSyncPullRequestConfirmation: Boolean = false,
    val syncHasConflict: Boolean = false,
) {
    val isSyncBusy: Boolean get() = isLoadingSync || isSyncing || isCheckingSyncPullRequest
}

@HiltViewModel
class RepoCodeViewModel @Inject constructor(
    private val repository: RepoGitRepository,
    private val pullRequestRepository: RepoPullRequestRepository,
    private val errorEventBus: ErrorEventBus,
    private val downloadLauncher: ExternalDownloadLauncher,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    session: AccountSession,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(RepoCodeUiState())
    val state: StateFlow<RepoCodeUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var initialized = false
    private var owner = ""
    private var name = ""
    private lateinit var source: RepoContentSource
    private var folderExportJob: Job? = null

    fun init(owner: String, name: String, source: RepoContentSource, permission: RepoPermission?) {
        if (initialized) return
        initialized = true; this.owner = owner; this.name = name; this.source = source
        permission?.let { _state.update { state -> state.copy(permission = it, capabilities = it.toCapabilities()) } }
        _state.update { it.copy(target = source as? RepoLinkTarget) }
        observeCodeChanges()
        load()
    }

    private fun observeCodeChanges() {
        viewModelScope.launch {
            repoUpdateEventBus.eventsFor(accountEpoch)
                .filterIsInstance<RepoUpdateEvent.CodeChanged>()
                .collect { event ->
                    val (ref, path) = _state.value.target ?: return@collect
                    if (_state.value.tree?.isCommitSnapshot != true &&
                        event.owner == owner && event.name == name && event.ref == ref &&
                        (path.isBlank() || event.changedPaths.any { changed -> changed == path || changed.startsWith("$path/") })) {
                        load()
                    }
                }
        }
    }

    fun load() {
        if (!accountEpoch.isCurrent) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            val target = _state.value.target ?: when (val source = source) {
                is RepoLinkTarget -> source
                is RepoContentSource.Link -> when (val result = repository.resolveLink(owner, name, RepoLinkKind.DIRECTORY, source.refAndPath).also { coroutineContext.ensureActive() }) {
                    is ApiResult.Success -> result.data
                    is ApiResult.Failure -> {
                        _state.update { it.copy(isLoading = false, loadFailed = true) }
                        errorEventBus.emit(result.error)
                        return@launch
                    }
                }
            }
            _state.update { it.copy(target = target) }
            val (ref, path) = target
            when (val result = repository.getCodeTree(owner, name, ref, path).also { coroutineContext.ensureActive() }) {
                is ApiResult.Success -> _state.update { it.copy(tree = result.data, permission = result.data.permission, capabilities = result.data.capabilities, isLoading = false) }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } }
            }
        }
    }

    fun retry() = load()
    fun refresh() = load()

    fun uploadFiles(selection: SafSelection, message: String, onDone: () -> Unit = {}) {
        if (!accountEpoch.isCurrent) return
        val tree = _state.value.tree ?: return
        if (tree.isCommitSnapshot || _state.value.isUploading || tree.isArchived || !tree.capabilities.canPush || !tree.capabilities.canPushToProtectedBranch || tree.headOid.isBlank() || message.isBlank()) return
        val targetOwner = owner
        val targetName = name
        val selected = when (selection) {
            is SafSelection.Documents -> selection.copy(uris = selection.uris.toList())
            is SafSelection.Tree -> selection
        }
        _state.update { it.copy(isUploading = true) }
        viewModelScope.launch {
            try {
                val result = repository.uploadFiles(targetOwner, targetName, tree.ref, tree.path, tree.headOid, selected, message)
                if (!accountEpoch.isCurrent) return@launch
                when (result) {
                    is ApiResult.Success -> { load(); onDone() }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update { it.copy(isUploading = false) }
            }
        }
    }

    fun exportCurrentFolder(writeZip: suspend (ByteArray) -> Unit) {
        val (ref, path) = _state.value.target ?: return
        if (folderExportJob?.isActive == true || _state.value.isFolderExporting) return
        folderExportJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isFolderExporting = true,
                    folderExportCompleted = 0,
                    folderExportTotal = 0,
                )
            }
            try {
                when (
                    val result = repository.createFolderZip(owner, name, ref, path) { completed, total ->
                        _state.update {
                            it.copy(
                                folderExportCompleted = completed,
                                folderExportTotal = total,
                            )
                        }
                    }
                ) {
                    is ApiResult.Success -> {
                        try {
                            writeZip(result.data)
                            errorEventBus.emitNotice(R.string.repo_folder_download_complete)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            errorEventBus.emit(ApiError.UserVisible(R.string.repo_folder_save_failed))
                        }
                    }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update {
                    it.copy(
                        isFolderExporting = false,
                        folderExportCompleted = 0,
                        folderExportTotal = 0,
                    )
                }
                folderExportJob = null
            }
        }
    }

    fun cancelFolderExport() {
        folderExportJob?.cancel()
        folderExportJob = null
        _state.update {
            it.copy(
                isFolderExporting = false,
                folderExportCompleted = 0,
                folderExportTotal = 0,
            )
        }
    }

    fun downloadArchive() {
        val ref = _state.value.target?.ref ?: return
        if (_state.value.openingDownload) return
        viewModelScope.launch {
            _state.update { it.copy(openingDownload = true) }
            try {
                when (val result = repository.resolveArchiveDownloadUrl(owner, name, ref)) {
                    is ApiResult.Success -> {
                        val url = result.data
                        if (url.isNullOrBlank()) errorEventBus.emit(ApiError.UserVisible(R.string.download_address_unavailable))
                        else {
                            runCatching { downloadLauncher.open(url) }
                                .onSuccess { errorEventBus.emitNotice(R.string.download_opened_externally) }
                                .onFailure { errorEventBus.emit(ApiError.UserVisible(R.string.download_external_app_unavailable)) }
                        }
                    }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update { it.copy(openingDownload = false) }
            }
        }
    }

    fun openSync() {
        if (_state.value.tree?.isCommitSnapshot != false || _state.value.isSyncBusy) return
        _state.update { it.copy(isSyncDialogOpen = true) }
        refreshSync()
    }

    fun dismissSync() {
        if (_state.value.isSyncBusy) return
        _state.update { it.copy(isSyncDialogOpen = false, showDiscardConfirmation = false, showSyncPullRequestConfirmation = false) }
    }

    fun showDiscardConfirmation(value: Boolean) {
        val state = _state.value
        if (state.isSyncBusy) return
        if (value && (!state.isSyncDialogOpen || state.showSyncPullRequestConfirmation ||
                state.syncInfo?.status != RepoForkSyncStatus.READY || state.syncInfo.aheadBy == 0 || !state.capabilities.canPush)) return
        _state.update { it.copy(showDiscardConfirmation = value) }
    }

    fun showSyncPullRequestConfirmation(value: Boolean) {
        val state = _state.value
        val info = state.syncInfo
        if (state.isSyncBusy) return
        if (value && (!state.isSyncDialogOpen || state.showDiscardConfirmation ||
                info?.status != RepoForkSyncStatus.READY || info.behindBy == 0 ||
                info.upstreamOwner == null || info.upstreamName == null || state.tree?.isArchived != false)) return
        _state.update { it.copy(showSyncPullRequestConfirmation = value) }
    }

    fun mergeUpstream() {
        val state = _state.value
        val info = state.syncInfo ?: return
        if (state.isSyncBusy || !state.isSyncDialogOpen || state.showDiscardConfirmation || state.showSyncPullRequestConfirmation ||
            info.status != RepoForkSyncStatus.READY || info.behindBy == 0 || !state.capabilities.canPush) return
        _state.update { it.copy(isSyncing = true) }
        viewModelScope.launch {
            try {
                when (val result = repository.mergeUpstream(owner, name, info.branch)) {
                    is ApiResult.Success -> syncCompleted(info.branch)
                    is ApiResult.Failure -> {
                        _state.update { it.copy(syncHasConflict = result.error == ApiError.UserVisible(R.string.repo_sync_conflict)) }
                        errorEventBus.emit(result.error)
                    }
                }
            } finally {
                _state.update { it.copy(isSyncing = false) }
            }
        }
    }

    fun discardAndAlign(confirmation: String) {
        val state = _state.value
        val info = state.syncInfo ?: return
        if (
            state.isSyncBusy || !state.isSyncDialogOpen || !state.showDiscardConfirmation ||
            confirmation != info.branch || info.status != RepoForkSyncStatus.READY ||
            info.aheadBy == 0 || !state.capabilities.canPush
        ) return
        _state.update { it.copy(isSyncing = true) }
        viewModelScope.launch {
            try {
                when (val result = repository.forceAlignFork(owner, name, info)) {
                    is ApiResult.Success -> syncCompleted(info.branch)
                    is ApiResult.Failure -> {
                        if (result.error == ApiError.UserVisible(R.string.repo_sync_head_changed)) refreshSync()
                        errorEventBus.emit(result.error)
                    }
                }
            } finally {
                _state.update { it.copy(isSyncing = false) }
            }
        }
    }

    fun openSyncPullRequest(onExisting: (Int) -> Unit, onCreate: (RepoForkSyncInfo) -> Unit) {
        val state = _state.value
        val info = state.syncInfo ?: return
        val upstreamOwner = info.upstreamOwner ?: return
        if (state.isSyncBusy || !state.isSyncDialogOpen || !state.showSyncPullRequestConfirmation ||
            info.status != RepoForkSyncStatus.READY || info.behindBy == 0 || state.tree?.isArchived != false) return
        _state.update { it.copy(isCheckingSyncPullRequest = true) }
        viewModelScope.launch {
            try {
                when (
                    val result = pullRequestRepository.findOpenPullRequest(
                        baseOwner = owner,
                        baseRepository = name,
                        baseRef = info.branch,
                        headOwner = upstreamOwner,
                        headRef = info.branch,
                    )
                ) {
                    is ApiResult.Success -> {
                        _state.update { it.copy(isSyncDialogOpen = false, showSyncPullRequestConfirmation = false) }
                        result.data?.let { onExisting(it.number) } ?: onCreate(info)
                    }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update { it.copy(isCheckingSyncPullRequest = false) }
            }
        }
    }

    private fun refreshSync() {
        val ref = _state.value.target?.ref ?: return
        _state.update {
            it.copy(
                isLoadingSync = true, syncInfo = null, showDiscardConfirmation = false,
                showSyncPullRequestConfirmation = false, syncHasConflict = false,
            )
        }
        viewModelScope.launch {
            when (val result = repository.getForkSync(owner, name, ref)) {
                is ApiResult.Success -> _state.update { it.copy(syncInfo = result.data, isLoadingSync = false) }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingSync = false) }
                }
            }
        }
    }

    private suspend fun syncCompleted(branch: String) {
        errorEventBus.emitNotice(R.string.repo_sync_success)
        repoUpdateEventBus.emit(RepoUpdateEvent.BranchSynced(owner, name, branch))
        load()
        refreshSync()
    }
}
