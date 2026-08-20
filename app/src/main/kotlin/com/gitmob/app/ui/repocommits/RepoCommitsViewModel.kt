package com.gitmob.app.ui.repocommits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoCommitSummary
import com.gitmob.app.data.repository.RepoGitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoCommitsUiState(
    val items: List<RepoCommitSummary> = emptyList(),
    val totalCount: Int = 0,
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val isArchived: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val hasNextPage: Boolean = false,
)

@HiltViewModel
class RepoCommitsViewModel @Inject constructor(
    private val repository: RepoGitRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoCommitsUiState())
    val state: StateFlow<RepoCommitsUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var ref = ""
    private var path: String? = null
    private var cursor: String? = null
    private var initialized = false
    private var loadJob: Job? = null

    fun init(owner: String, name: String, ref: String, path: String?, permission: RepoPermission?) {
        if (initialized) return
        initialized = true
        this.owner = owner; this.name = name; this.ref = ref; this.path = path
        permission?.let { _state.update { state -> state.copy(permission = it, capabilities = it.toCapabilities()) } }
        observeCodeChanges()
        load()
    }

    private fun observeCodeChanges() {
        viewModelScope.launch {
            repoUpdateEventBus.events
                .filterIsInstance<RepoUpdateEvent.CodeChanged>()
                .collect { event ->
                    if (event.owner == owner && event.name == name && event.ref == ref &&
                        (path.isNullOrBlank() || event.changedPaths.any { changed -> changed == path || changed.startsWith("$path/") })) {
                        load()
                    }
                }
        }
    }

    fun load() {
        loadJob?.cancel(); cursor = null
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = repository.getCommitHistory(owner, name, ref, path)) {
                is ApiResult.Success -> _state.update { it.copy(items = result.data.items, totalCount = result.data.totalCount, permission = result.data.permission, capabilities = result.data.capabilities, isArchived = result.data.isArchived, hasNextPage = result.data.hasNextPage, isLoading = false) }.also { cursor = result.data.endCursor }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } }
            }
        }
    }

    fun refresh() = load()
    fun retry() = load()

    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = repository.getCommitHistory(owner, name, ref, path, cursor)) {
                is ApiResult.Success -> { cursor = result.data.endCursor; _state.update { it.copy(items = it.items + result.data.items, totalCount = result.data.totalCount, hasNextPage = result.data.hasNextPage, isLoadingMore = false) } }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoadingMore = false) } }
            }
        }
    }
}
