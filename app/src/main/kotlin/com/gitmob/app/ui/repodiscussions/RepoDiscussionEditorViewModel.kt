package com.gitmob.app.ui.repodiscussions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
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
    val existing: RepoDiscussion? = null,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
)

@HiltViewModel
class RepoDiscussionEditorViewModel @Inject constructor(
    private val repository: RepoDiscussionRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoDiscussionEditorUiState())
    val state: StateFlow<RepoDiscussionEditorUiState> = _state.asStateFlow()
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
            val page = repository.getDiscussions(owner, name, RepoDiscussionFilter())
            if (page is ApiResult.Failure) return@launch failLoad(page)
            val detail = number?.let { repository.getDiscussion(owner, name, it) }
            if (detail is ApiResult.Failure) return@launch failLoad(detail)
            val pageData = (page as ApiResult.Success).data
            _state.update { it.copy(repositoryId = pageData.repositoryId, categories = pageData.categories, existing = (detail as? ApiResult.Success)?.data?.discussion, isLoading = false) }
        }
    }

    fun save(title: String, body: String, categoryId: String, done: (RepoDiscussion) -> Unit) {
        val state = _state.value
        if (title.isBlank() || categoryId.isBlank() || state.isSaving) return
        if (state.existing != null && !state.existing.viewerCanUpdate) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val existing = state.existing
            val result = if (existing == null) repository.createDiscussion(state.repositoryId, title.trim(), body, categoryId)
            else repository.updateDiscussion(existing.id, title.trim(), body, categoryId)
            when (result) {
                is ApiResult.Success -> { _state.update { it.copy(existing = result.data, isSaving = false) }; emitRefresh(); done(result.data) }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isSaving = false) } }
            }
        }
    }

    private suspend fun failLoad(result: ApiResult.Failure) { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } }
    private suspend fun emitRefresh() { val result = repository.getDiscussions(owner, name, RepoDiscussionFilter()); val count = (result as? ApiResult.Success)?.data?.totalCount ?: return; repoUpdateEventBus.emit(RepoUpdateEvent.DiscussionCountChanged(owner, name, count)) }
}
