package com.gitmob.app.ui.reporeleases

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.ui.common.AccountViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.share.ShareLinkLauncher
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoReleaseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoReleaseListUiState(val releases: List<RepoRelease> = emptyList(), val page: Int = 1, val hasNextPage: Boolean = false, val permission: RepoPermission = RepoPermission.NONE, val capabilities: RepoCapabilities = RepoCapabilities.NONE, val isLoading: Boolean = false, val loadFailed: Boolean = false)
@HiltViewModel
class RepoReleaseListViewModel @Inject constructor(private val repository: RepoReleaseRepository, private val errorEventBus: ErrorEventBus, private val repoUpdateEventBus: RepoUpdateEventBus, session: AccountSession) : AccountViewModel(session) {
    private val _state = MutableStateFlow(RepoReleaseListUiState()); val state: StateFlow<RepoReleaseListUiState> = _state.asStateFlow(); private var owner = ""; private var name = ""; private var initialized = false
    fun init(owner: String, name: String, permission: RepoPermission?) { if (initialized) return; initialized = true; this.owner = owner; this.name = name; permission?.let { _state.update { s -> s.copy(permission = it, capabilities = it.toCapabilities()) } } ?: viewModelScope.launch { when (val result = repository.getRepositoryPermission(owner, name)) { is ApiResult.Success -> _state.update { it.copy(permission = result.data, capabilities = result.data.toCapabilities()) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } }; load() }
    fun load() { viewModelScope.launch { _state.update { it.copy(isLoading = true, loadFailed = false) }; when (val result = repository.getReleases(owner, name)) { is ApiResult.Success -> _state.update { it.copy(releases = result.data.items, page = result.data.page, hasNextPage = result.data.hasNextPage, isLoading = false) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } } } } }
    fun refresh() = load(); fun retry() = load()
    fun loadMore() { val s = _state.value; if (!s.hasNextPage) return; viewModelScope.launch { when (val result = repository.getReleases(owner, name, s.page + 1)) { is ApiResult.Success -> _state.update { it.copy(releases = it.releases + result.data.items, page = result.data.page, hasNextPage = result.data.hasNextPage) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun delete(release: RepoRelease, done: () -> Unit) { if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = repository.deleteRelease(owner, name, release.id)) { is ApiResult.Success -> { _state.update { it.copy(releases = it.releases.filterNot { item -> item.id == release.id }) }; repoUpdateEventBus.emit(RepoUpdateEvent.ReleaseChanged(owner, name, -1, _state.value.releases.firstOrNull()?.name, _state.value.releases.firstOrNull()?.tagName)); done() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
}

enum class ReleaseAssetUploadPhase { IDLE, PREPARING, UPLOADING, SUCCESS, FAILURE, CANCELLED }
data class ReleaseAssetUploadState(
    val phase: ReleaseAssetUploadPhase = ReleaseAssetUploadPhase.IDLE,
    val sent: Long = 0,
    val total: Long = 0,
    val isCancelling: Boolean = false,
) {
    val isActive: Boolean get() = phase == ReleaseAssetUploadPhase.PREPARING || phase == ReleaseAssetUploadPhase.UPLOADING
}

data class RepoReleaseDetailUiState(val release: RepoRelease? = null, val permission: RepoPermission = RepoPermission.NONE, val capabilities: RepoCapabilities = RepoCapabilities.NONE, val isLoading: Boolean = false, val loadFailed: Boolean = false, val pendingDelete: Boolean = false, val pendingDeleteAsset: RepoReleaseAsset? = null, val openingAssetIds: Set<Long> = emptySet(), val upload: ReleaseAssetUploadState = ReleaseAssetUploadState())
@HiltViewModel
class RepoReleaseDetailViewModel @Inject constructor(private val repository: RepoReleaseRepository, private val errorEventBus: ErrorEventBus, private val repoUpdateEventBus: RepoUpdateEventBus, private val shareLinkLauncher: ShareLinkLauncher, session: AccountSession) : AccountViewModel(session) {
    private val _state = MutableStateFlow(RepoReleaseDetailUiState()); val state: StateFlow<RepoReleaseDetailUiState> = _state.asStateFlow(); private var owner = ""; private var name = ""; private var tag = ""; private var releaseId: Long? = null; private var initialized = false
    fun init(owner: String, name: String, tag: String?, releaseId: Long?, permission: RepoPermission?) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        this.tag = tag.orEmpty()
        this.releaseId = releaseId
        permission?.let { _state.update { s -> s.copy(permission = it, capabilities = it.toCapabilities()) } } ?: viewModelScope.launch {
            when (val result = repository.getRepositoryPermission(owner, name)) {
                is ApiResult.Success -> _state.update { it.copy(permission = result.data, capabilities = result.data.toCapabilities()) }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
        viewModelScope.launch {
            repoUpdateEventBus.eventsFor(accountEpoch).collect { event ->
                val release = _state.value.release
                if (event is RepoUpdateEvent.ReleaseChanged && event.owner == owner && event.name == name &&
                    release != null && event.latestReleaseTag == release.tagName) {
                    load()
                }
            }
        }
        load()
    }
    fun load() { viewModelScope.launch { _state.update { it.copy(isLoading = true, loadFailed = false) }; val result = if (releaseId != null) repository.getRelease(owner, name, releaseId!!) else repository.getReleaseByTag(owner, name, tag); when (result) { is ApiResult.Success -> _state.update { it.copy(release = result.data, isLoading = false) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } } } } }
    fun retry() = load()
    fun share() { if (accountEpoch.isCurrent) _state.value.release?.htmlUrl?.takeIf(String::isNotBlank)?.let(shareLinkLauncher::share) }
    fun update(input: SaveRepoReleaseInput, done: () -> Unit = {}) { val release = _state.value.release ?: return; if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = repository.updateRelease(owner, name, release.id, input)) { is ApiResult.Success -> { _state.update { it.copy(release = result.data) }; emitChanged(); done() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun delete(done: () -> Unit) { val release = _state.value.release ?: return; if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = repository.deleteRelease(owner, name, release.id)) { is ApiResult.Success -> { _state.update { it.copy(release = null, pendingDelete = false) }; emitChanged(); done() }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(pendingDelete = false) } } } } }
    fun confirmDelete(value: Boolean) = _state.update { it.copy(pendingDelete = value) }
    fun confirmDeleteAsset(asset: RepoReleaseAsset?) = _state.update { it.copy(pendingDeleteAsset = asset) }
    fun deleteAsset() { val asset = _state.value.pendingDeleteAsset ?: return; if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = repository.deleteAsset(owner, name, asset.id)) { is ApiResult.Success -> _state.update { it.copy(release = it.release?.copy(assets = it.release.assets.filterNot { value -> value.id == asset.id }), pendingDeleteAsset = null) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(pendingDeleteAsset = null) } } } } }
    private var uploadJob: Job? = null

    fun uploadAsset(uri: Uri) {
        val release = _state.value.release ?: return
        if (!accountEpoch.isCurrent || !_state.value.capabilities.canPush || _state.value.isLoading || _state.value.upload.isActive || uploadJob?.isCompleted == false) return
        _state.update { it.copy(upload = ReleaseAssetUploadState(ReleaseAssetUploadPhase.PREPARING)) }
        uploadJob = viewModelScope.launch {
            try {
                val result = repository.uploadAsset(release.uploadUrl, uri) { sent, total ->
                    _state.update { state ->
                        if (accountEpoch.isCurrent && state.upload.isActive && !state.upload.isCancelling) {
                            state.copy(upload = state.upload.copy(phase = ReleaseAssetUploadPhase.UPLOADING, sent = sent, total = total))
                        } else state
                    }
                }
                currentCoroutineContext().ensureActive()
                accountEpoch.ensureCurrent()
                when (result) {
                    is ApiResult.Success -> _state.update { state ->
                        state.copy(
                            release = state.release?.copy(assets = state.release.assets.filterNot { it.id == result.data.id } + result.data),
                            upload = state.upload.copy(phase = ReleaseAssetUploadPhase.SUCCESS),
                        )
                    }
                    is ApiResult.Failure -> {
                        _state.update { it.copy(upload = it.upload.copy(phase = ReleaseAssetUploadPhase.FAILURE)) }
                        errorEventBus.emit(result.error)
                    }
                }
            } catch (cancelled: CancellationException) {
                val wasUploading = _state.value.upload.phase == ReleaseAssetUploadPhase.UPLOADING
                _state.update { it.copy(upload = it.upload.copy(phase = ReleaseAssetUploadPhase.CANCELLED, isCancelling = false)) }
                // Cancelling the request cannot undo an asset already accepted by the server.
                if (wasUploading && accountEpoch.isCurrent) load()
                throw cancelled
            }
        }
    }

    fun cancelUpload() {
        if (!_state.value.upload.isActive) return
        _state.update { it.copy(upload = it.upload.copy(isCancelling = true)) }
        uploadJob?.cancel()
    }
    fun updateAsset(asset: RepoReleaseAsset, assetName: String, label: String?, done: () -> Unit = {}) { if (!_state.value.capabilities.canPush || assetName.isBlank()) return; viewModelScope.launch { when (val result = repository.updateAsset(owner, name, asset.id, assetName.trim(), label)) { is ApiResult.Success -> { _state.update { it.copy(release = it.release?.copy(assets = it.release.assets.map { value -> if (value.id == asset.id) result.data else value })) }; done() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun downloadAsset(asset: RepoReleaseAsset) {
        if (asset.id in _state.value.openingAssetIds) return
        viewModelScope.launch {
            _state.update { it.copy(openingAssetIds = it.openingAssetIds + asset.id) }
            try {
                when (val result = repository.downloadAsset(owner, name, asset)) {
                    is ApiResult.Success -> errorEventBus.emitNotice(R.string.download_opened_externally)
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update { it.copy(openingAssetIds = it.openingAssetIds - asset.id) }
            }
        }
    }
    private suspend fun emitChanged() { repoUpdateEventBus.emit(RepoUpdateEvent.ReleaseChanged(owner, name, -1, _state.value.release?.name, _state.value.release?.tagName)) }
}

enum class ReleaseEditorPhase { LOADING, LOAD_FAILED, EDITABLE, SAVING }

data class ReleaseEditorDraft(
    val tag: String = "",
    val target: String = "",
    val title: String = "",
    val body: String = "",
    val draft: Boolean = true,
    val prerelease: Boolean = false,
) {
    fun toInput() = SaveRepoReleaseInput(tag, target, title.ifBlank { null }, body, draft, prerelease)
}

data class RepoReleaseEditorUiState(
    val phase: ReleaseEditorPhase = ReleaseEditorPhase.LOADING,
    val draft: ReleaseEditorDraft = ReleaseEditorDraft(),
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val isDirty: Boolean = false,
    val isGenerating: Boolean = false,
    val savedRelease: RepoRelease? = null,
) {
    val isSaving: Boolean get() = phase == ReleaseEditorPhase.SAVING
}

@HiltViewModel
class RepoReleaseEditorViewModel @Inject constructor(
    private val repository: RepoReleaseRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    session: AccountSession,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(RepoReleaseEditorUiState())
    val state: StateFlow<RepoReleaseEditorUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var releaseId: Long? = null
    private var initialized = false
    private var notesJob: Job? = null
    private var baseline = ReleaseEditorDraft()
    private var revision = 0L

    fun init(owner: String, name: String, releaseId: Long?, permission: RepoPermission?) {
        if (initialized || !accountEpoch.isCurrent) return
        initialized = true
        this.owner = owner
        this.name = name
        this.releaseId = releaseId
        if (permission != null) _state.update { it.copy(capabilities = permission.toCapabilities()) }
        else viewModelScope.launch {
            when (val result = repository.getRepositoryPermission(owner, name)) {
                is ApiResult.Success -> _state.update { it.copy(capabilities = result.data.toCapabilities()) }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
        if (releaseId == null) {
            _state.update { it.copy(phase = ReleaseEditorPhase.EDITABLE) }
        } else load()
    }

    fun retry() {
        if (_state.value.phase == ReleaseEditorPhase.LOAD_FAILED) load()
    }

    private fun load() {
        val id = releaseId ?: return
        if (!accountEpoch.isCurrent) return
        _state.update { it.copy(phase = ReleaseEditorPhase.LOADING) }
        viewModelScope.launch {
            val result = repository.getRelease(owner, name, id)
            currentCoroutineContext().ensureActive()
            accountEpoch.ensureCurrent()
            when (result) {
                is ApiResult.Success -> {
                    val release = result.data
                    baseline = ReleaseEditorDraft(release.tagName, release.targetCommitish, release.name.orEmpty(), release.body, release.draft, release.prerelease)
                    _state.update { it.copy(phase = ReleaseEditorPhase.EDITABLE, draft = baseline, isDirty = false) }
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(phase = ReleaseEditorPhase.LOAD_FAILED) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }

    fun editTag(value: String) = edit { it.copy(tag = value) }
    fun editTarget(value: String) = edit { it.copy(target = value) }
    fun editTitle(value: String) = edit { it.copy(title = value) }
    fun editBody(value: String) = edit { it.copy(body = value) }
    fun editDraft(value: Boolean) = edit { it.copy(draft = value) }
    fun editPrerelease(value: Boolean) = edit { it.copy(prerelease = value) }

    private fun edit(transform: (ReleaseEditorDraft) -> ReleaseEditorDraft) {
        val state = _state.value
        if (!accountEpoch.isCurrent || state.phase != ReleaseEditorPhase.EDITABLE || state.savedRelease != null) return
        val previous = state.draft
        val draft = transform(previous)
        if (draft == previous) return
        // Publishing flags do not change the generated text or its input parameters.
        if (draft.tag != previous.tag || draft.target != previous.target || draft.title != previous.title || draft.body != previous.body) revision++
        _state.update { it.copy(draft = draft, isDirty = draft != baseline) }
    }

    fun generateNotes() {
        val state = _state.value
        if (!accountEpoch.isCurrent || state.phase != ReleaseEditorPhase.EDITABLE || !state.capabilities.canPush || state.draft.tag.isBlank() || state.isGenerating || state.savedRelease != null) return
        val requestedRevision = revision
        _state.update { it.copy(isGenerating = true) }
        notesJob = viewModelScope.launch {
            try {
                val result = repository.generateNotes(owner, name, state.draft.tag.trim(), state.draft.target.takeIf(String::isNotBlank), null)
                currentCoroutineContext().ensureActive()
                accountEpoch.ensureCurrent()
                when (result) {
                    is ApiResult.Success -> {
                        if (revision == requestedRevision && _state.value.phase == ReleaseEditorPhase.EDITABLE) {
                            editBody(result.data.body)
                            if (_state.value.draft.title.isBlank()) editTitle(result.data.name.orEmpty())
                        } else errorEventBus.emitNotice(R.string.release_notes_draft_changed)
                    }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun save() {
        val state = _state.value
        if (!accountEpoch.isCurrent || !state.capabilities.canPush || state.draft.tag.isBlank() || state.phase != ReleaseEditorPhase.EDITABLE || state.savedRelease != null) return
        notesJob?.cancel()
        _state.update { it.copy(phase = ReleaseEditorPhase.SAVING, isGenerating = false) }
        val input = state.draft.toInput()
        viewModelScope.launch {
            val id = releaseId
            val result = if (id == null) repository.createRelease(owner, name, input) else repository.updateRelease(owner, name, id, input)
            currentCoroutineContext().ensureActive()
            accountEpoch.ensureCurrent()
            when (result) {
                is ApiResult.Success -> {
                    baseline = state.draft
                    // Publish completion after the account-scoped event, so navigation observes a fully saved result.
                    repoUpdateEventBus.emit(RepoUpdateEvent.ReleaseChanged(owner, name, -1, result.data.name, result.data.tagName))
                    _state.update { it.copy(phase = ReleaseEditorPhase.EDITABLE, isDirty = false, savedRelease = result.data) }
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(phase = ReleaseEditorPhase.EDITABLE) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }
}
