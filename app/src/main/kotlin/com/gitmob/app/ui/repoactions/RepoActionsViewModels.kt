package com.gitmob.app.ui.repoactions

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
import com.gitmob.app.data.repository.RepoActionsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoActionsUiState(
    val workflows: List<RepoWorkflow> = emptyList(),
    val runs: List<RepoWorkflowRun> = emptyList(),
    val totalCount: Int = 0,
    val page: Int = 1,
    val hasNextPage: Boolean = false,
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val dispatchWorkflow: RepoWorkflow? = null,
    val dispatchInputs: List<WorkflowDispatchInput> = emptyList(),
    val dispatchRef: String = "main",
    val isLoadingInputs: Boolean = false,
)

@HiltViewModel
class RepoActionsViewModel @Inject constructor(
    private val repository: RepoActionsRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoActionsUiState())
    val state: StateFlow<RepoActionsUiState> = _state.asStateFlow()
    private var owner = ""; private var name = ""; private var initialized = false
    fun init(owner: String, name: String, permission: RepoPermission?, defaultRef: String?) { if (initialized) return; initialized = true; this.owner = owner; this.name = name; _state.update { it.copy(dispatchRef = defaultRef ?: "main") }; permission?.let { _state.update { s -> s.copy(permission = it, capabilities = it.toCapabilities()) } } ?: viewModelScope.launch { when (val result = repository.getRepositoryPermission(owner, name)) { is ApiResult.Success -> _state.update { it.copy(permission = result.data, capabilities = result.data.toCapabilities()) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } }; load() }
    fun load() { viewModelScope.launch { _state.update { it.copy(isLoading = true, loadFailed = false) }; when (val result = repository.getActions(owner, name)) { is ApiResult.Success -> _state.update { it.copy(workflows = result.data.workflows, runs = result.data.runs, totalCount = result.data.totalCount, page = result.data.page, hasNextPage = result.data.hasNextPage, isLoading = false) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } } } } }
    fun refresh() = load()
    fun loadMore() { val s = _state.value; if (!s.hasNextPage || s.isLoadingMore) return; viewModelScope.launch { _state.update { it.copy(isLoadingMore = true) }; when (val result = repository.getActions(owner, name, s.page + 1)) { is ApiResult.Success -> _state.update { it.copy(runs = it.runs + result.data.runs, page = result.data.page, hasNextPage = result.data.hasNextPage, isLoadingMore = false) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoadingMore = false) } } } } }
    fun prepareDispatch(workflow: RepoWorkflow) { if (!_state.value.capabilities.canPush) return; viewModelScope.launch { _state.update { it.copy(dispatchWorkflow = workflow, isLoadingInputs = true) }; when (val result = repository.getDispatchInputs(owner, name, workflow)) { is ApiResult.Success -> _state.update { it.copy(dispatchInputs = result.data, isLoadingInputs = false) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(dispatchWorkflow = null, isLoadingInputs = false) } } } } }
    fun dismissDispatch() = _state.update { it.copy(dispatchWorkflow = null, dispatchInputs = emptyList()) }
    fun dispatch(ref: String, inputs: Map<String, String>) { val workflow = _state.value.dispatchWorkflow ?: return; viewModelScope.launch { when (val result = repository.dispatch(owner, name, workflow.id, ref, inputs)) { is ApiResult.Success -> { dismissDispatch(); load(); repoUpdateEventBus.emit(RepoUpdateEvent.ActionRunChanged(owner, name, 0L)) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun setWorkflowEnabled(workflow: RepoWorkflow, enabled: Boolean) { if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = repository.enableWorkflow(owner, name, workflow.id, enabled)) { is ApiResult.Success -> _state.update { it.copy(workflows = it.workflows.map { value -> if (value.id == workflow.id) value.copy(state = if (enabled) "active" else "disabled_manually") else value }) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
}

data class RepoWorkflowRunUiState(
    val detail: RepoWorkflowRunDetail? = null,
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val pendingDelete: Boolean = false,
    val openingArtifactIds: Set<Long> = emptySet(),
)

@HiltViewModel
class RepoWorkflowRunViewModel @Inject constructor(
    private val repository: RepoActionsRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoWorkflowRunUiState())
    val state: StateFlow<RepoWorkflowRunUiState> = _state.asStateFlow()
    private var owner = ""; private var name = ""; private var runId = 0L; private var initialized = false
    fun init(owner: String, name: String, runId: Long, permission: RepoPermission?) { if (initialized) return; initialized = true; this.owner = owner; this.name = name; this.runId = runId; permission?.let { _state.update { s -> s.copy(permission = it, capabilities = it.toCapabilities()) } } ?: viewModelScope.launch { when (val result = repository.getRepositoryPermission(owner, name)) { is ApiResult.Success -> _state.update { it.copy(permission = result.data, capabilities = result.data.toCapabilities()) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } }; load() }
    fun load() { viewModelScope.launch { _state.update { it.copy(isLoading = true, loadFailed = false) }; when (val result = repository.getRun(owner, name, runId)) { is ApiResult.Success -> _state.update { it.copy(detail = result.data, isLoading = false) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } } } } }
    fun cancel(force: Boolean = false) = runAction { repository.cancel(owner, name, runId, force) }
    fun rerun(failedOnly: Boolean = false) = runAction { repository.rerun(owner, name, runId, failedOnly) }
    fun confirmDelete(value: Boolean) = _state.update { it.copy(pendingDelete = value) }
    fun delete(done: () -> Unit) { if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = repository.deleteRun(owner, name, runId)) { is ApiResult.Success -> { repoUpdateEventBus.emit(RepoUpdateEvent.ActionRunChanged(owner, name, runId)); done() }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(pendingDelete = false) } } } } }
    fun downloadArtifact(artifact: RepoActionArtifact) {
        if (artifact.expired || artifact.id in _state.value.openingArtifactIds) return
        viewModelScope.launch {
            _state.update { it.copy(openingArtifactIds = it.openingArtifactIds + artifact.id) }
            try {
                when (val result = repository.downloadArtifact(owner, name, artifact)) {
                    is ApiResult.Success -> errorEventBus.emitNotice(R.string.download_opened_externally)
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update { it.copy(openingArtifactIds = it.openingArtifactIds - artifact.id) }
            }
        }
    }
    private fun runAction(block: suspend () -> ApiResult<Unit>) { if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = block()) { is ApiResult.Success -> { repoUpdateEventBus.emit(RepoUpdateEvent.ActionRunChanged(owner, name, runId)); load() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
}
