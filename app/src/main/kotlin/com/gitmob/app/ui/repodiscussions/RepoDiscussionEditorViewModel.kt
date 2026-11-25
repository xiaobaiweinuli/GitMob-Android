package com.gitmob.app.ui.repodiscussions

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
import com.gitmob.app.data.repository.RepoDiscussionRepository
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

data class RepoDiscussionEditorDraft(
    val title: String = "",
    val categoryId: String = "",
    val labelIds: Set<String> = emptySet(),
) : Serializable

data class RepoDiscussionEditorUiState(
    val draft: RepoDiscussionEditorDraft = RepoDiscussionEditorDraft(),
    val repositoryId: String = "",
    val categories: List<RepoDiscussionCategory> = emptyList(),
    val labels: List<IssueLabel> = emptyList(),
    val existing: RepoDiscussion? = null,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
    val bodyEditor: MarkdownEditorUiState = MarkdownEditorUiState(),
)

@OptIn(SavedStateHandleSaveableApi::class)
@HiltViewModel
class RepoDiscussionEditorViewModel @Inject constructor(
    private val repository: RepoDiscussionRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val markdownRenderer: MarkdownRenderer,
    session: AccountSession,
    private val savedStateHandle: SavedStateHandle,
) : AccountViewModel(session) {
    val bodyState = savedStateHandle.saveable(key = "editor.body", saver = SoraEditorState.Saver) { SoraEditorState() }
    private var draftInitialized = savedStateHandle.contains("editor.draft")
    private val _state = MutableStateFlow(RepoDiscussionEditorUiState(
        draft = savedStateHandle["editor.draft"] ?: RepoDiscussionEditorDraft(),
    ))
    val state: StateFlow<RepoDiscussionEditorUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var number: Int? = null
    private var initialized = false
    private var previewJob: kotlinx.coroutines.Job? = null

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
            val page = repository.getDiscussions(owner, name, RepoDiscussionFilter())
            if (page is ApiResult.Failure) return@launch failLoad(page)
            val detail = number?.let { repository.getDiscussion(owner, name, it) }
            if (detail is ApiResult.Failure) return@launch failLoad(detail)
            val labels = repository.getLabels(owner, name)
            if (labels is ApiResult.Failure) return@launch failLoad(labels)
            val pageData = (page as ApiResult.Success).data
            val discussion = (detail as? ApiResult.Success)?.data?.discussion
            if (!draftInitialized) {
                bodyState.loadText(discussion?.body.orEmpty())
                storeDraft(RepoDiscussionEditorDraft(
                    title = discussion?.title.orEmpty(),
                    categoryId = discussion?.category?.id ?: pageData.categories.firstOrNull()?.id.orEmpty(),
                    labelIds = discussion?.labels.orEmpty().map { it.id }.toSet(),
                ))
            }
            _state.update { it.copy(repositoryId = pageData.repositoryId, categories = pageData.categories, labels = (labels as ApiResult.Success).data, existing = discussion, isLoading = false) }
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

    private fun storeDraft(draft: RepoDiscussionEditorDraft) {
        savedStateHandle["editor.draft"] = draft
        draftInitialized = true
        _state.update { it.copy(draft = draft) }
    }

    fun editTitle(title: String) = storeDraft(_state.value.draft.copy(title = title))
    fun confirmMetadata(categoryId: String, labels: Set<String>) =
        storeDraft(_state.value.draft.copy(categoryId = categoryId, labelIds = labels))

    fun save(done: (RepoDiscussion) -> Unit) {
        val state = _state.value
        val draft = state.draft
        val title = draft.title
        val categoryId = draft.categoryId
        if (!draftInitialized || state.isLoading || state.loadFailed || title.isBlank() || categoryId.isBlank() || state.isSaving) return
        val body = bodyState.snapshot()
        val labelIds = draft.labelIds.toList()
        if (state.existing != null && !state.existing.viewerCanUpdate) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val existing = state.existing
            val result = if (existing == null) repository.createDiscussion(state.repositoryId, title.trim(), body, categoryId)
            else repository.updateDiscussion(existing.id, title.trim(), body, categoryId)
            when (result) {
                is ApiResult.Success -> {
                    val previous = existing?.labels.orEmpty().map { it.id }.toSet()
                    val requested = labelIds.toSet()
                    val labelUpdate = if (existing == null) {
                        repository.addLabelsToDiscussion(result.data.id, requested.toList())
                    } else {
                        when (val removed = repository.removeLabelsFromDiscussion(result.data.id, (previous - requested).toList())) {
                            is ApiResult.Failure -> removed
                            is ApiResult.Success -> repository.addLabelsToDiscussion(result.data.id, (requested - previous).toList())
                        }
                    }
                    if (labelUpdate is ApiResult.Failure) errorEventBus.emit(labelUpdate.error)
                    _state.update { it.copy(existing = result.data, isSaving = false) }
                    emitRefresh()
                    done(result.data)
                }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isSaving = false) } }
            }
        }
    }

    private suspend fun failLoad(result: ApiResult.Failure) { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } }
    private suspend fun emitRefresh() { val result = repository.getDiscussions(owner, name, RepoDiscussionFilter()); val count = (result as? ApiResult.Success)?.data?.totalCount ?: return; repoUpdateEventBus.emit(RepoUpdateEvent.DiscussionCountChanged(owner, name, count)) }
}
