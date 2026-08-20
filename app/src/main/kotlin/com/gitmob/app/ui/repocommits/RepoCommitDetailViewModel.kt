package com.gitmob.app.ui.repocommits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.diff.UnifiedDiffParser
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoChangedFile
import com.gitmob.app.data.model.RepoCommitDetail
import com.gitmob.app.data.repository.RepoGitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoCommitDetailUiState(
    val detail: RepoCommitDetail? = null,
    val selectedFile: RepoChangedFile? = null,
    val selectedDiff: com.gitmob.app.core.diff.UnifiedDiff? = null,
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isReverting: Boolean = false,
    val revertingPath: String? = null,
)

@HiltViewModel
class RepoCommitDetailViewModel @Inject constructor(
    private val repository: RepoGitRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoCommitDetailUiState())
    val state: StateFlow<RepoCommitDetailUiState> = _state.asStateFlow()
    private var initialized = false
    private var owner = ""
    private var name = ""
    private var ref = ""
    private var sha = ""

    fun init(owner: String, name: String, ref: String, sha: String, permission: RepoPermission?) {
        if (initialized) return
        initialized = true; this.owner = owner; this.name = name; this.ref = ref; this.sha = sha
        permission?.let { _state.update { state -> state.copy(permission = it, capabilities = it.toCapabilities()) } }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = repository.getCommitDetail(owner, name, ref, sha)) {
                is ApiResult.Success -> _state.update { it.copy(detail = result.data, permission = result.data.permission, capabilities = result.data.capabilities, isLoading = false) }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } }
            }
        }
    }

    fun retry() = load()

    fun toggleFile(file: RepoChangedFile) = _state.update { current ->
        if (current.selectedFile?.filename == file.filename) {
            current.copy(selectedFile = null, selectedDiff = null)
        } else {
            current.copy(selectedFile = file, selectedDiff = UnifiedDiffParser.parse(file.patch))
        }
    }

    fun revertFile(file: RepoChangedFile, message: String, onDone: () -> Unit = {}) {
        val detail = _state.value.detail ?: return
        val capabilities = _state.value.capabilities
        if (_state.value.isReverting || detail.isArchived || !capabilities.canPush || !capabilities.canPushToProtectedBranch || message.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isReverting = true, revertingPath = file.filename) }
            when (val result = repository.revertFile(owner, name, ref, sha, file.filename, message, file.previousFilename)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isReverting = false, revertingPath = null) }
                    repoUpdateEventBus.emit(
                        RepoUpdateEvent.CodeChanged(owner, name, ref, result.data.oid, listOfNotNull(file.filename, file.previousFilename).distinct()),
                    )
                    onDone()
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isReverting = false, revertingPath = null) }
                }
            }
        }
    }

    fun revertCommit(message: String, onDone: () -> Unit = {}) {
        val detail = _state.value.detail ?: return
        val capabilities = _state.value.capabilities
        if (
            _state.value.isReverting ||
            detail.commit.isMergeCommit ||
            detail.isArchived ||
            !capabilities.canPush ||
            !capabilities.canPushToProtectedBranch ||
            message.isBlank()
        ) return
        viewModelScope.launch {
            _state.update { it.copy(isReverting = true, revertingPath = null) }
            when (val result = repository.revertCommit(owner, name, ref, sha, message)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isReverting = false) }
                    val changedPaths = detail.changedFiles
                        .flatMap { listOfNotNull(it.filename, it.previousFilename) }
                        .distinct()
                    repoUpdateEventBus.emit(RepoUpdateEvent.CodeChanged(owner, name, ref, result.data.oid, changedPaths))
                    onDone()
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isReverting = false) }
                }
            }
        }
    }
}
