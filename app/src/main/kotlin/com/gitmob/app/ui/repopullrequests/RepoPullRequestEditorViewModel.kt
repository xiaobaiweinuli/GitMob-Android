package com.gitmob.app.ui.repopullrequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.core.markdown.CommonMarkRenderer
import com.gitmob.app.ui.common.MarkdownEditorTab
import com.gitmob.app.ui.common.MarkdownEditorUiState
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
    val bodyEditor: MarkdownEditorUiState = MarkdownEditorUiState(),
)

@HiltViewModel
class RepoPullRequestEditorViewModel @Inject constructor(
    private val repository: RepoPullRequestRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val markdownRenderer: MarkdownRenderer,
) : ViewModel() {
    constructor(repository: RepoPullRequestRepository, errorEventBus: ErrorEventBus, repoUpdateEventBus: RepoUpdateEventBus) : this(repository, errorEventBus, repoUpdateEventBus, CommonMarkRenderer())
    private val _state = MutableStateFlow(RepoPullRequestEditorUiState())
    val state: StateFlow<RepoPullRequestEditorUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var number: Int? = null
    private var routeHeadRepositoryId: String? = null
    private var routeHeadOwner: String? = null
    private var initialized = false
    private var previewJob: kotlinx.coroutines.Job? = null

    fun init(owner: String, name: String, number: Int?, baseOwner: String? = null, baseName: String? = null, baseRef: String? = null, headOwner: String? = null, headName: String? = null, headRef: String? = null, headRepositoryId: String? = null) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        this.number = number
        routeHeadRepositoryId = headRepositoryId
        routeHeadOwner = headOwner
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            val metadata = repository.getCreateMetadata(owner, name)
            if (metadata is ApiResult.Failure) return@launch failLoad(metadata)
            val existing = number?.let { repository.getPullRequest(owner, name, it) }
            if (existing is ApiResult.Failure) return@launch failLoad(existing)
            _state.update { it.copy(metadata = (metadata as ApiResult.Success).data, existing = (existing as? ApiResult.Success)?.data?.pullRequest, isLoading = false, bodyEditor = MarkdownEditorUiState()) }
        }
    }

    fun selectBodyEditorTab(tab: MarkdownEditorTab, markdown: String = "") {
        _state.update { it.copy(bodyEditor = it.bodyEditor.copy(selectedTab = tab, previewFailed = false)) }
        if (tab == MarkdownEditorTab.PREVIEW) renderBodyPreview(markdown)
    }

    fun renderBodyPreview(markdown: String) {
        previewJob?.cancel()
        if (markdown.isBlank()) {
            _state.update { it.copy(bodyEditor = it.bodyEditor.copy(previewHtml = "", isRenderingPreview = false, previewFailed = false)) }
            return
        }
        previewJob = viewModelScope.launch {
            kotlinx.coroutines.delay(120)
            _state.update { it.copy(bodyEditor = it.bodyEditor.copy(isRenderingPreview = true, previewFailed = false)) }
            runCatching { markdownRenderer.renderToHtml(markdown) }
                .onSuccess { html -> _state.update { it.copy(bodyEditor = it.bodyEditor.copy(previewHtml = html, isRenderingPreview = false)) } }
                .onFailure { error ->
                    errorEventBus.emit(com.gitmob.app.core.error.ApiError.Unknown(error.message ?: "Markdown preview failed"))
                    _state.update { it.copy(bodyEditor = it.bodyEditor.copy(isRenderingPreview = false, previewFailed = true)) }
                }
        }
    }

    fun save(title: String, body: String, baseRef: String, headRepoId: String, headRef: String, draft: Boolean, labelIds: List<String>, assigneeIds: List<String>, milestoneId: String?, reviewerIds: List<String>, done: (RepoPullRequest) -> Unit) {
        val metadata = _state.value.metadata ?: return
        if (title.isBlank() || baseRef.isBlank() || headRef.isBlank() || _state.value.isSaving) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val existing = _state.value.existing
            val initial = if (existing == null) repository.createPullRequest(CreateRepoPullRequestInput(metadata.repositoryId, baseRef, headRef, headRepoId.takeUnless { it == metadata.repositoryId }, title.trim(), body, draft, routeHeadOwner))
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
                    if (reviewerIds.isNotEmpty()) {
                        val request = repository.requestReviews(pullRequest.id, reviewerIds)
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
