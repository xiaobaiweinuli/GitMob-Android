package com.gitmob.app.ui.repopullrequests

import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.ui.common.AccountViewModel
import com.gitmob.app.ui.common.SoraEditorState
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.lifecycle.viewModelScope
import java.io.Serializable
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.ui.common.MarkdownEditorTab
import com.gitmob.app.ui.common.MarkdownEditorUiState
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoPullRequestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoPullRequestEditorDraft(
    val title: String = "",
    val baseRef: String = "",
    val headRepositoryId: String = "",
    val headRef: String = "",
    val isDraft: Boolean = false,
    val labelIds: Set<String> = emptySet(),
    val assigneeIds: Set<String> = emptySet(),
    val milestoneId: String? = null,
    val reviewerIds: Set<String> = emptySet(),
) : Serializable

data class RepoPullRequestEditorUiState(
    val draft: RepoPullRequestEditorDraft = RepoPullRequestEditorDraft(),
    val metadata: RepoPullRequestCreateMetadata? = null,
    val existing: RepoPullRequest? = null,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
    val bodyEditor: MarkdownEditorUiState = MarkdownEditorUiState(),
)

@OptIn(SavedStateHandleSaveableApi::class)
@HiltViewModel
class RepoPullRequestEditorViewModel @Inject constructor(
    private val repository: RepoPullRequestRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val markdownRenderer: MarkdownRenderer,
    session: AccountSession,
    private val savedStateHandle: SavedStateHandle,
) : AccountViewModel(session) {
    val bodyState = savedStateHandle.saveable(key = "editor.body", saver = SoraEditorState.Saver) { SoraEditorState() }
    private var draftInitialized = savedStateHandle.contains("editor.draft")
    private val _state = MutableStateFlow(RepoPullRequestEditorUiState(
        draft = savedStateHandle["editor.draft"] ?: RepoPullRequestEditorDraft(),
    ))
    val state: StateFlow<RepoPullRequestEditorUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var number: Int? = null
    private var routeHeadRepositoryId: String? = null
    private var routeHeadOwner: String? = null
    private var routeBaseRef: String? = null
    private var routeHeadRef: String? = null
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
        routeBaseRef = baseRef
        routeHeadRef = headRef
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            val metadata = repository.getCreateMetadata(owner, name)
            if (metadata is ApiResult.Failure) return@launch failLoad(metadata)
            val existing = number?.let { repository.getPullRequest(owner, name, it) }
            if (existing is ApiResult.Failure) return@launch failLoad(existing)
            val metadataValue = (metadata as ApiResult.Success).data
            val pullRequest = (existing as? ApiResult.Success)?.data?.pullRequest
            if (!draftInitialized) {
                bodyState.loadText(pullRequest?.body.orEmpty())
                storeDraft(RepoPullRequestEditorDraft(
                    title = pullRequest?.title.orEmpty(),
                    baseRef = pullRequest?.baseRefName ?: routeBaseRef ?: metadataValue.defaultBranchName.orEmpty(),
                    headRepositoryId = routeHeadRepositoryId ?: metadataValue.repositories.firstOrNull()?.id.orEmpty(),
                    headRef = pullRequest?.headRefName.orEmpty().ifBlank { routeHeadRef.orEmpty() },
                    isDraft = pullRequest?.isDraft ?: false,
                    labelIds = pullRequest?.labels.orEmpty().map { it.id }.toSet(),
                    assigneeIds = pullRequest?.assignees.orEmpty().mapNotNull { it.id }.toSet(),
                    milestoneId = pullRequest?.milestone?.id,
                ))
            }
            _state.update { it.copy(metadata = metadataValue, existing = pullRequest, isLoading = false) }
        }
    }

    fun selectBodyEditorTab(tab: MarkdownEditorTab) {
        previewJob?.cancel()
        _state.update { it.copy(bodyEditor = it.bodyEditor.copy(selectedTab = tab, previewFailed = false, isRenderingPreview = false)) }
        if (tab == MarkdownEditorTab.PREVIEW) renderBodyPreview(bodyState.snapshot())
    }

    private fun renderBodyPreview(markdown: String) {
        previewJob?.cancel()
        if (markdown.isBlank()) {
            _state.update { it.copy(bodyEditor = it.bodyEditor.copy(previewHtml = "", isRenderingPreview = false, previewFailed = false)) }
            return
        }
        previewJob = viewModelScope.launch {
            kotlinx.coroutines.delay(120)
            _state.update { it.copy(bodyEditor = it.bodyEditor.copy(isRenderingPreview = true, previewFailed = false)) }
            runCatching { withContext(Dispatchers.Default) { markdownRenderer.renderToHtml(markdown) } }
                .onSuccess { html -> _state.update { it.copy(bodyEditor = it.bodyEditor.copy(previewHtml = html, isRenderingPreview = false)) } }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    errorEventBus.emit(com.gitmob.app.core.error.ApiError.Unknown(error.message ?: "Markdown preview failed"))
                    _state.update { it.copy(bodyEditor = it.bodyEditor.copy(isRenderingPreview = false, previewFailed = true)) }
                }
        }
    }

    private fun storeDraft(draft: RepoPullRequestEditorDraft) {
        savedStateHandle["editor.draft"] = draft
        draftInitialized = true
        _state.update { it.copy(draft = draft) }
    }

    fun editTitle(title: String) = storeDraft(_state.value.draft.copy(title = title))
    fun editBaseRef(baseRef: String) = storeDraft(_state.value.draft.copy(baseRef = baseRef))
    fun editDraft(isDraft: Boolean) = storeDraft(_state.value.draft.copy(isDraft = isDraft))
    fun confirmMetadata(labels: Set<String>, assignees: Set<String>, milestone: String?, reviewers: Set<String>) =
        storeDraft(_state.value.draft.copy(labelIds = labels, assigneeIds = assignees, milestoneId = milestone, reviewerIds = reviewers))

    fun save(done: (RepoPullRequest) -> Unit) {
        val state = _state.value
        val metadata = state.metadata ?: return
        val input = state.draft
        val title = input.title
        val baseRef = input.baseRef
        val headRef = input.headRef
        if (!draftInitialized || state.isLoading || state.loadFailed || title.isBlank() || baseRef.isBlank() || headRef.isBlank() || state.isSaving) return
        val body = bodyState.snapshot()
        val headRepoId = input.headRepositoryId
        val draft = input.isDraft
        val labelIds = input.labelIds.toList()
        val assigneeIds = input.assigneeIds.toList()
        val milestoneId = input.milestoneId
        val reviewerIds = input.reviewerIds.toList()
        val existing = state.existing
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
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
