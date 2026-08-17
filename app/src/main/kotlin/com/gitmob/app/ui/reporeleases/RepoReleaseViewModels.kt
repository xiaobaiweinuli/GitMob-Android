package com.gitmob.app.ui.reporeleases

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
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
class RepoReleaseListViewModel @Inject constructor(private val repository: RepoReleaseRepository, private val errorEventBus: ErrorEventBus, private val repoUpdateEventBus: RepoUpdateEventBus) : ViewModel() {
    private val _state = MutableStateFlow(RepoReleaseListUiState()); val state: StateFlow<RepoReleaseListUiState> = _state.asStateFlow(); private var owner = ""; private var name = ""; private var initialized = false
    fun init(owner: String, name: String, permission: RepoPermission?) { if (initialized) return; initialized = true; this.owner = owner; this.name = name; permission?.let { _state.update { s -> s.copy(permission = it, capabilities = it.toCapabilities()) } } ?: viewModelScope.launch { when (val result = repository.getRepositoryPermission(owner, name)) { is ApiResult.Success -> _state.update { it.copy(permission = result.data, capabilities = result.data.toCapabilities()) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } }; load() }
    fun load() { viewModelScope.launch { _state.update { it.copy(isLoading = true, loadFailed = false) }; when (val result = repository.getReleases(owner, name)) { is ApiResult.Success -> _state.update { it.copy(releases = result.data.items, page = result.data.page, hasNextPage = result.data.hasNextPage, isLoading = false) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } } } } }
    fun refresh() = load(); fun retry() = load()
    fun loadMore() { val s = _state.value; if (!s.hasNextPage) return; viewModelScope.launch { when (val result = repository.getReleases(owner, name, s.page + 1)) { is ApiResult.Success -> _state.update { it.copy(releases = it.releases + result.data.items, page = result.data.page, hasNextPage = result.data.hasNextPage) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun delete(release: RepoRelease, done: () -> Unit) { if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = repository.deleteRelease(owner, name, release.id)) { is ApiResult.Success -> { _state.update { it.copy(releases = it.releases.filterNot { item -> item.id == release.id }) }; repoUpdateEventBus.emit(RepoUpdateEvent.ReleaseChanged(owner, name, -1, _state.value.releases.firstOrNull()?.name, _state.value.releases.firstOrNull()?.tagName)); done() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
}

data class RepoReleaseDetailUiState(val release: RepoRelease? = null, val permission: RepoPermission = RepoPermission.NONE, val capabilities: RepoCapabilities = RepoCapabilities.NONE, val isLoading: Boolean = false, val loadFailed: Boolean = false, val pendingDelete: Boolean = false, val pendingDeleteAsset: RepoReleaseAsset? = null, val openingAssetIds: Set<Long> = emptySet())
@HiltViewModel
class RepoReleaseDetailViewModel @Inject constructor(private val repository: RepoReleaseRepository, private val errorEventBus: ErrorEventBus, private val repoUpdateEventBus: RepoUpdateEventBus) : ViewModel() {
    private val _state = MutableStateFlow(RepoReleaseDetailUiState()); val state: StateFlow<RepoReleaseDetailUiState> = _state.asStateFlow(); private var owner = ""; private var name = ""; private var tag = ""; private var releaseId: Long? = null; private var initialized = false
    fun init(owner: String, name: String, tag: String?, releaseId: Long?, permission: RepoPermission?) { if (initialized) return; initialized = true; this.owner = owner; this.name = name; this.tag = tag.orEmpty(); this.releaseId = releaseId; permission?.let { _state.update { s -> s.copy(permission = it, capabilities = it.toCapabilities()) } } ?: viewModelScope.launch { when (val result = repository.getRepositoryPermission(owner, name)) { is ApiResult.Success -> _state.update { it.copy(permission = result.data, capabilities = result.data.toCapabilities()) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } }; load() }
    fun load() { viewModelScope.launch { _state.update { it.copy(isLoading = true, loadFailed = false) }; val result = if (releaseId != null) repository.getRelease(owner, name, releaseId!!) else repository.getReleaseByTag(owner, name, tag); when (result) { is ApiResult.Success -> _state.update { it.copy(release = result.data, isLoading = false) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } } } } }
    fun retry() = load()
    fun update(input: SaveRepoReleaseInput, done: () -> Unit = {}) { val release = _state.value.release ?: return; if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = repository.updateRelease(owner, name, release.id, input)) { is ApiResult.Success -> { _state.update { it.copy(release = result.data) }; emitChanged(); done() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun delete(done: () -> Unit) { val release = _state.value.release ?: return; if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = repository.deleteRelease(owner, name, release.id)) { is ApiResult.Success -> { emitChanged(); done() }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(pendingDelete = false) } } } } }
    fun confirmDelete(value: Boolean) = _state.update { it.copy(pendingDelete = value) }
    fun confirmDeleteAsset(asset: RepoReleaseAsset?) = _state.update { it.copy(pendingDeleteAsset = asset) }
    fun deleteAsset() { val asset = _state.value.pendingDeleteAsset ?: return; if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = repository.deleteAsset(owner, name, asset.id)) { is ApiResult.Success -> _state.update { it.copy(release = it.release?.copy(assets = it.release.assets.filterNot { value -> value.id == asset.id }), pendingDeleteAsset = null) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(pendingDeleteAsset = null) } } } } }
    fun uploadAsset(fileName: String, label: String?, contentType: String, bytes: ByteArray) { val release = _state.value.release ?: return; if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = repository.uploadAsset(release.uploadUrl, fileName, label, contentType, bytes)) { is ApiResult.Success -> _state.update { it.copy(release = it.release?.copy(assets = it.release.assets + result.data)) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
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

data class RepoReleaseEditorUiState(val existing: RepoRelease? = null, val capabilities: RepoCapabilities = RepoCapabilities.NONE, val isSaving: Boolean = false)
@HiltViewModel
class RepoReleaseEditorViewModel @Inject constructor(private val repository: RepoReleaseRepository, private val errorEventBus: ErrorEventBus, private val repoUpdateEventBus: RepoUpdateEventBus) : ViewModel() {
    private val _state = MutableStateFlow(RepoReleaseEditorUiState()); val state: StateFlow<RepoReleaseEditorUiState> = _state.asStateFlow(); private var owner = ""; private var name = ""; private var releaseId: Long? = null; private var initialized = false
    fun init(owner: String, name: String, releaseId: Long?, permission: RepoPermission?) { if (initialized) return; initialized = true; this.owner = owner; this.name = name; this.releaseId = releaseId; permission?.let { _state.update { s -> s.copy(capabilities = it.toCapabilities()) } } ?: viewModelScope.launch { when (val result = repository.getRepositoryPermission(owner, name)) { is ApiResult.Success -> _state.update { it.copy(capabilities = result.data.toCapabilities()) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } }; if (releaseId != null) viewModelScope.launch { when (val result = repository.getRelease(owner, name, releaseId)) { is ApiResult.Success -> _state.update { it.copy(existing = result.data) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun save(input: SaveRepoReleaseInput, done: (RepoRelease) -> Unit) { if (!_state.value.capabilities.canPush || input.tagName.isBlank() || _state.value.isSaving) return; viewModelScope.launch { _state.update { it.copy(isSaving = true) }; val result = if (releaseId == null) repository.createRelease(owner, name, input) else repository.updateRelease(owner, name, releaseId!!, input); when (result) { is ApiResult.Success -> { _state.update { it.copy(existing = result.data, isSaving = false) }; repoUpdateEventBus.emit(RepoUpdateEvent.ReleaseChanged(owner, name, -1, result.data.name, result.data.tagName)); done(result.data) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isSaving = false) } } } } }
    fun generateNotes(tag: String, target: String?, done: (ReleaseNotes) -> Unit) { if (tag.isBlank()) return; viewModelScope.launch { when (val result = repository.generateNotes(owner, name, tag.trim(), target?.takeIf(String::isNotBlank), null)) { is ApiResult.Success -> done(result.data); is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
}
