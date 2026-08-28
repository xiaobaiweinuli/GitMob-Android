package com.gitmob.app.ui.repodiscussions

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
import com.gitmob.app.data.repository.RepoDiscussionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoDiscussionEditorUiState(
    val repositoryId: String = "",
    val categories: List<RepoDiscussionCategory> = emptyList(),
    val labels: List<IssueLabel> = emptyList(),
    val existing: RepoDiscussion? = null,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
    val bodyEditor: MarkdownEditorUiState = MarkdownEditorUiState(),
)

@HiltViewModel
class RepoDiscussionEditorViewModel @Inject constructor(
    private val repository: RepoDiscussionRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val markdownRenderer: MarkdownRenderer,
) : ViewModel() {
    constructor(repository: RepoDiscussionRepository, errorEventBus: ErrorEventBus, repoUpdateEventBus: RepoUpdateEventBus) : this(repository, errorEventBus, repoUpdateEventBus, CommonMarkRenderer())
    private val _state = MutableStateFlow(RepoDiscussionEditorUiState())
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
            _state.update { it.copy(repositoryId = pageData.repositoryId, categories = pageData.categories, labels = (labels as ApiResult.Success).data, existing = (detail as? ApiResult.Success)?.data?.discussion, isLoading = false, bodyEditor = MarkdownEditorUiState()) }
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

    fun save(title: String, body: String, categoryId: String, done: (RepoDiscussion) -> Unit) {
        save(title, body, categoryId, emptyList(), done)
    }

    fun save(title: String, body: String, categoryId: String, labelIds: List<String>, done: (RepoDiscussion) -> Unit) {
        val state = _state.value
        if (title.isBlank() || categoryId.isBlank() || state.isSaving) return
        if (state.existing != null && !state.existing.viewerCanUpdate) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
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
