package com.gitmob.app.ui.repoactions

import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.ui.common.AccountViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.share.ShareLinkLauncher
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoActionsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
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
    val dispatchInputsRef: String? = null,
    val isLoadingInputs: Boolean = false,
    val pendingDeleteRun: RepoWorkflowRun? = null,
    val deletingRunIds: Set<Long> = emptySet(),
)

@HiltViewModel
class RepoActionsViewModel @Inject constructor(
    private val repository: RepoActionsRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    session: AccountSession,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(RepoActionsUiState())
    val state: StateFlow<RepoActionsUiState> = _state.asStateFlow()
    private var owner = ""; private var name = ""; private var initialized = false
    private var dispatchInputsJob: Job? = null
    private val locallyDeletedRunIds = mutableSetOf<Long>()
    fun init(owner: String, name: String, permission: RepoPermission?, defaultRef: String?) { if (initialized) return; initialized = true; this.owner = owner; this.name = name; _state.update { it.copy(dispatchRef = defaultRef ?: "main") }; permission?.let { _state.update { s -> s.copy(permission = it, capabilities = it.toCapabilities()) } } ?: viewModelScope.launch { when (val result = repository.getRepositoryPermission(owner, name)) { is ApiResult.Success -> _state.update { it.copy(permission = result.data, capabilities = result.data.toCapabilities()) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } }; observeRunChanges(); load() }
    fun load() { viewModelScope.launch { _state.update { it.copy(isLoading = true, loadFailed = false) }; when (val result = repository.getActions(owner, name)) { is ApiResult.Success -> _state.update { it.copy(workflows = result.data.workflows, runs = result.data.runs, totalCount = result.data.totalCount, page = result.data.page, hasNextPage = result.data.hasNextPage, isLoading = false) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } } } } }
    fun refresh() = load()
    fun loadMore() { val s = _state.value; if (!s.hasNextPage || s.isLoadingMore) return; viewModelScope.launch { _state.update { it.copy(isLoadingMore = true) }; when (val result = repository.getActions(owner, name, s.page + 1)) { is ApiResult.Success -> _state.update { it.copy(runs = it.runs + result.data.runs, page = result.data.page, hasNextPage = result.data.hasNextPage, isLoadingMore = false) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoadingMore = false) } } } } }
    fun prepareDispatch(workflow: RepoWorkflow) { if (!_state.value.capabilities.canPush) return; _state.update { it.copy(dispatchWorkflow = workflow, dispatchInputs = emptyList(), dispatchInputsRef = null) }; loadDispatchInputs(_state.value.dispatchRef) }
    fun loadDispatchInputs(ref: String) {
        val workflow = _state.value.dispatchWorkflow ?: return
        val normalizedRef = ref.trim()
        if (normalizedRef.isEmpty()) return
        dispatchInputsJob?.cancel()
        dispatchInputsJob = viewModelScope.launch {
            _state.update { it.copy(dispatchRef = normalizedRef, isLoadingInputs = true, dispatchInputsRef = null) }
            when (val result = repository.getDispatchInputs(owner, name, workflow, normalizedRef)) {
                is ApiResult.Success -> _state.update { it.copy(dispatchInputs = result.data, dispatchInputsRef = normalizedRef, isLoadingInputs = false) }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoadingInputs = false) } }
            }
        }
    }
    fun dismissDispatch() { dispatchInputsJob?.cancel(); _state.update { it.copy(dispatchWorkflow = null, dispatchInputs = emptyList(), dispatchInputsRef = null, isLoadingInputs = false) } }
    fun dispatch(ref: String, inputs: Map<String, String>) { val workflow = _state.value.dispatchWorkflow ?: return; viewModelScope.launch { when (val result = repository.dispatch(owner, name, workflow.id, ref, inputs)) { is ApiResult.Success -> { dismissDispatch(); load(); repoUpdateEventBus.emit(RepoUpdateEvent.ActionRunChanged(owner, name, 0L)) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun setWorkflowEnabled(workflow: RepoWorkflow, enabled: Boolean) { if (!_state.value.capabilities.canPush) return; viewModelScope.launch { when (val result = repository.enableWorkflow(owner, name, workflow.id, enabled)) { is ApiResult.Success -> _state.update { it.copy(workflows = it.workflows.map { value -> if (value.id == workflow.id) value.copy(state = if (enabled) "active" else "disabled_manually") else value }) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }

    fun requestDelete(run: RepoWorkflowRun) {
        if (!_state.value.capabilities.canDeleteActionRuns || run.id in _state.value.deletingRunIds) return
        if (run.status != "completed") {
            viewModelScope.launch { errorEventBus.emit(ApiError.UserVisible(R.string.actions_delete_running)) }
            return
        }
        _state.update { it.copy(pendingDeleteRun = run) }
    }

    fun dismissDelete() = _state.update { it.copy(pendingDeleteRun = null) }

    fun deletePendingRun() {
        val run = _state.value.pendingDeleteRun ?: return
        if (run.id in _state.value.deletingRunIds) return
        _state.update { it.copy(pendingDeleteRun = null, deletingRunIds = it.deletingRunIds + run.id) }
        viewModelScope.launch {
            when (val result = repository.deleteRun(owner, name, run.id)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            runs = it.runs.filterNot { item -> item.id == run.id },
                            totalCount = (it.totalCount - 1).coerceAtLeast(0),
                        )
                    }
                    errorEventBus.emitNotice(R.string.actions_run_deleted)
                    locallyDeletedRunIds += run.id
                    repoUpdateEventBus.emit(RepoUpdateEvent.ActionRunChanged(owner, name, run.id))
                    _state.update { it.copy(deletingRunIds = it.deletingRunIds - run.id) }
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(deletingRunIds = it.deletingRunIds - run.id) }
                }
            }
        }
    }

    private fun observeRunChanges() {
        viewModelScope.launch {
            repoUpdateEventBus.eventsFor(accountEpoch).filterIsInstance<RepoUpdateEvent.ActionRunChanged>().collect { event ->
                if (event.owner != owner || event.name != name) return@collect
                if (!locallyDeletedRunIds.remove(event.runId)) load()
            }
        }
    }
}

data class RepoWorkflowRunUiState(
    val detail: RepoWorkflowRunDetail? = null,
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val pendingDelete: Boolean = false,
    val openingArtifactIds: Set<Long> = emptySet(),
    val openingLogs: Boolean = false,
    val pendingRerunFailedOnly: Boolean? = null,
    val enableDebugLogging: Boolean = false,
    val isMutating: Boolean = false,
)

@HiltViewModel
class RepoWorkflowRunViewModel @Inject constructor(
    private val repository: RepoActionsRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val shareLinkLauncher: ShareLinkLauncher,
    session: AccountSession,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(RepoWorkflowRunUiState())
    val state: StateFlow<RepoWorkflowRunUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var runId = 0L
    private var initialized = false
    private var loadJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadGeneration = 0
    private var generation = 0

    fun init(owner: String, name: String, runId: Long, permission: RepoPermission?) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        this.runId = runId
        if (permission != null) {
            _state.update { it.copy(permission = permission, capabilities = permission.toCapabilities()) }
        } else viewModelScope.launch {
            when (val result = repository.getRepositoryPermission(owner, name)) {
                is ApiResult.Success -> _state.update { it.copy(permission = result.data, capabilities = result.data.toCapabilities()) }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
        load()
    }

    fun load() {
        loadJob?.cancel()
        cancelLogDownload()
        val request = ++generation
        _state.update { it.copy(isLoading = true, loadFailed = false) }
        loadJob = viewModelScope.launch {
            val result = repository.getRun(owner, name, runId)
            currentCoroutineContext().ensureActive()
            accountEpoch.ensureCurrent()
            if (request != generation) return@launch
            when (result) {
                is ApiResult.Success -> _state.update { it.copy(detail = result.data, isLoading = false) }
                is ApiResult.Failure -> {
                    _state.update { it.copy(isLoading = false, loadFailed = true) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }

    fun cancel(force: Boolean = false) = runAction { repository.cancel(owner, name, runId, force) }
    fun share() {
        if (accountEpoch.isCurrent) _state.value.detail?.run?.htmlUrl?.takeIf(String::isNotBlank)?.let(shareLinkLauncher::share)
    }
    fun requestRerun(failedOnly: Boolean) {
        if (!_state.value.capabilities.canPush || _state.value.isMutating) return
        _state.update { it.copy(pendingRerunFailedOnly = failedOnly, enableDebugLogging = false) }
    }
    fun dismissRerun() {
        if (!_state.value.isMutating) _state.update { it.copy(pendingRerunFailedOnly = null, enableDebugLogging = false) }
    }
    fun setDebugLogging(enabled: Boolean) {
        if (!_state.value.isMutating) _state.update { it.copy(enableDebugLogging = enabled) }
    }
    fun confirmRerun() {
        val failedOnly = _state.value.pendingRerunFailedOnly ?: return
        val debug = _state.value.enableDebugLogging
        runAction { repository.rerun(owner, name, runId, failedOnly, debug) }
    }
    fun confirmDelete(value: Boolean) = _state.update { it.copy(pendingDelete = value) }
    fun delete(done: () -> Unit) {
        if (!_state.value.capabilities.canPush || _state.value.isMutating) return
        _state.update { it.copy(isMutating = true) }
        viewModelScope.launch {
            try {
                when (val result = repository.deleteRun(owner, name, runId)) {
                    is ApiResult.Success -> { repoUpdateEventBus.emit(RepoUpdateEvent.ActionRunChanged(owner, name, runId)); done() }
                    is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(pendingDelete = false) } }
                }
            } finally { _state.update { it.copy(isMutating = false) } }
        }
    }

    fun downloadLogs() {
        val current = _state.value
        val run = current.detail?.run ?: return
        if (current.openingLogs || current.isLoading || !accountEpoch.isCurrent) return
        _state.update { it.copy(openingLogs = true) }
        val request = ++downloadGeneration
        downloadJob = viewModelScope.launch {
            try {
                val result = repository.downloadRunLogs(owner, name, run.id, run.runAttempt)
                currentCoroutineContext().ensureActive()
                accountEpoch.ensureCurrent()
                if (request != downloadGeneration) return@launch
                when (result) {
                    is ApiResult.Success -> errorEventBus.emitNotice(R.string.download_opened_externally)
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                if (request == downloadGeneration) _state.update { it.copy(openingLogs = false) }
            }
        }
    }

    fun cancelLogDownload() {
        ++downloadGeneration
        downloadJob?.cancel()
        downloadJob = null
        _state.update { it.copy(openingLogs = false) }
    }

    fun downloadArtifact(artifact: RepoActionArtifact) {
        if (artifact.expired || artifact.id in _state.value.openingArtifactIds) return
        viewModelScope.launch {
            _state.update { it.copy(openingArtifactIds = it.openingArtifactIds + artifact.id) }
            try {
                when (val result = repository.downloadArtifact(owner, name, artifact)) {
                    is ApiResult.Success -> errorEventBus.emitNotice(R.string.download_opened_externally)
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally { _state.update { it.copy(openingArtifactIds = it.openingArtifactIds - artifact.id) } }
        }
    }

    private fun runAction(block: suspend () -> ApiResult<Unit>) {
        if (!_state.value.capabilities.canPush || _state.value.isMutating || !accountEpoch.isCurrent) return
        _state.update { it.copy(isMutating = true) }
        viewModelScope.launch {
            try {
                val result = block()
                currentCoroutineContext().ensureActive()
                accountEpoch.ensureCurrent()
                when (result) {
                    is ApiResult.Success -> {
                        _state.update { it.copy(pendingRerunFailedOnly = null, enableDebugLogging = false) }
                        repoUpdateEventBus.emit(RepoUpdateEvent.ActionRunChanged(owner, name, runId))
                        load()
                    }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally { _state.update { it.copy(isMutating = false) } }
        }
    }
}

data class RepoActionStepLogUiState(
    val log: RepoActionStepLog? = null,
    val isLoading: Boolean = false,
    val error: ApiError? = null,
    val wrapLines: Boolean = false,
)

@HiltViewModel
class RepoActionStepLogViewModel @Inject constructor(
    private val repository: RepoActionsRepository,
    private val errorEventBus: ErrorEventBus,
    session: AccountSession,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(RepoActionStepLogUiState())
    val state: StateFlow<RepoActionStepLogUiState> = _state.asStateFlow()
    private var key: RepoActionStepLogKey? = null
    private var request: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            try { awaitCancellation() } finally { close() }
        }
    }

    fun init(key: RepoActionStepLogKey) {
        if (this.key == key) return
        close()
        this.key = key
        load()
    }
    fun load() {
        val requestedKey = key ?: return
        if (!accountEpoch.isCurrent) return
        request?.cancel()
        val requestedGeneration = ++generation
        _state.update { it.copy(log = null, isLoading = true, error = null) }
        request = viewModelScope.launch {
            val result = repository.getStepLog(requestedKey)
            currentCoroutineContext().ensureActive()
            accountEpoch.ensureCurrent()
            if (key != requestedKey || generation != requestedGeneration) return@launch
            when (result) {
                is ApiResult.Success -> _state.update { it.copy(log = result.data, isLoading = false) }
                is ApiResult.Failure -> {
                    _state.update { it.copy(error = result.error, isLoading = false) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }
    fun setWrapLines(enabled: Boolean) = _state.update { it.copy(wrapLines = enabled) }
    fun copied() {
        viewModelScope.launch { errorEventBus.emitNotice(if (_state.value.log?.truncated == true) R.string.actions_logs_copied_partial else R.string.actions_logs_copied) }
    }
    fun close() {
        ++generation
        request?.cancel()
        request = null
        key = null
        _state.value = RepoActionStepLogUiState()
    }
}
