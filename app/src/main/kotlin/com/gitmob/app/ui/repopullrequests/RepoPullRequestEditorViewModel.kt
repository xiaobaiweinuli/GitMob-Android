package com.gitmob.app.ui.repopullrequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoPullRequestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoPullRequestEditorUiState(
    val metadata: RepoPullRequestCreateMetadata? = null,
    val existing: RepoPullRequest? = null,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
)

@HiltViewModel
class RepoPullRequestEditorViewModel @Inject constructor(
    private val repository: RepoPullRequestRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoPullRequestEditorUiState())
    val state: StateFlow<RepoPullRequestEditorUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var number: Int? = null
    private var initialized = false

    fun init(owner: String, name: String, number: Int?) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        this.number = number
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            val metadata = repository.getCreateMetadata(owner, name)
            if (metadata is ApiResult.Failure) return@launch failLoad(metadata)
            val existing = number?.let { repository.getPullRequest(owner, name, it) }
            if (existing is ApiResult.Failure) return@launch failLoad(existing)
            _state.update { it.copy(metadata = (metadata as ApiResult.Success).data, existing = (existing as? ApiResult.Success)?.data?.pullRequest, isLoading = false) }
        }
    }

    fun save(title: String, body: String, baseRef: String, headRepoId: String, headRef: String, draft: Boolean, labelIds: List<String>, assigneeIds: List<String>, milestoneId: String?, reviewerLogins: List<String>, done: (RepoPullRequest) -> Unit) {
        val metadata = _state.value.metadata ?: return
        if (title.isBlank() || baseRef.isBlank() || headRef.isBlank() || _state.value.isSaving) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val existing = _state.value.existing
            val initial = if (existing == null) repository.createPullRequest(CreateRepoPullRequestInput(metadata.repositoryId, baseRef, headRef, headRepoId.takeUnless { it == metadata.repositoryId }, title.trim(), body, draft))
            else repository.updatePullRequest(UpdateRepoPullRequestInput(existing.id, title.trim(), body, baseRef, labelIds, assigneeIds, milestoneId))
            when (initial) {
                is ApiResult.Failure -> { errorEventBus.emit(initial.error); _state.update { it.copy(isSaving = false) } }
                is ApiResult.Success -> {
                    var pullRequest = initial.data
                    if (existing == null && (labelIds.isNotEmpty() || assigneeIds.isNotEmpty() || milestoneId != null)) {
                        when (val update = repository.updatePullRequest(UpdateRepoPullRequestInput(pullRequest.id, pullRequest.title, pullRequest.body, pullRequest.baseRefName, labelIds, assigneeIds, milestoneId))) {
                            is ApiResult.Success -> pullRequest = update.data
                            is ApiResult.Failure -> errorEventBus.emit(update.error)
                        }
                    }
                    if (reviewerLogins.isNotEmpty()) {
                        val request = repository.requestReviews(pullRequest.id, reviewerLogins)
                        if (request is ApiResult.Failure) errorEventBus.emit(request.error)
                    }
                    emitRefresh()
                    _state.update { it.copy(existing = pullRequest, isSaving = false) }
                    done(pullRequest)
                }
            }
        }
    }

    private suspend fun failLoad(result: ApiResult.Failure) { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } }
    private suspend fun emitRefresh() { val result = repository.getPullRequests(owner, name, RepoPullRequestFilter()); val count = (result as? ApiResult.Success)?.data?.totalCount ?: return; repoUpdateEventBus.emit(RepoUpdateEvent.PullRequestCountChanged(owner, name, count)) }
}
