package com.gitmob.app.ui.repodiscussions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoDiscussionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoDiscussionListUiState(
    val repositoryId: String = "",
    val items: List<RepoDiscussion> = emptyList(),
    val categories: List<RepoDiscussionCategory> = emptyList(),
    val filter: RepoDiscussionFilter = RepoDiscussionFilter(),
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val hasDiscussionsEnabled: Boolean = true,
    val totalCount: Int = 0,
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val pendingDelete: RepoDiscussion? = null,
)

@HiltViewModel
class RepoDiscussionListViewModel @Inject constructor(
    private val repository: RepoDiscussionRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoDiscussionListUiState())
    val state: StateFlow<RepoDiscussionListUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var cursor: String? = null
    private var initialized = false
    private var loadJob: Job? = null

    fun init(owner: String, name: String, permission: RepoPermission?) {
        if (initialized) return
        initialized = true; this.owner = owner; this.name = name
        permission?.let { _state.update { state -> state.copy(permission = it, capabilities = it.toCapabilities()) } }
        load()
    }
    fun load() { loadJob?.cancel(); cursor = null; loadJob = viewModelScope.launch { _state.update { it.copy(isLoading = true, loadFailed = false) }; when (val result = repository.getDiscussions(owner, name, _state.value.filter)) { is ApiResult.Success -> { cursor = result.data.endCursor; _state.update { it.copy(repositoryId = result.data.repositoryId, items = result.data.items, categories = result.data.categories, permission = result.data.permission, capabilities = result.data.capabilities, hasDiscussionsEnabled = result.data.hasDiscussionsEnabled, totalCount = result.data.totalCount, hasNextPage = result.data.hasNextPage, isLoading = false) } }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } } } } }
    fun refresh() = load()
    fun retry() = load()
    fun setState(value: RepoDiscussionStateFilter) = setFilter(_state.value.filter.copy(state = value))
    fun setSort(value: RepoDiscussionSort) = setFilter(_state.value.filter.copy(sort = value))
    fun setCategory(value: String?) = setFilter(_state.value.filter.copy(categoryId = value))
    fun setAnswered(value: Boolean?) = setFilter(_state.value.filter.copy(answered = value))
    private fun setFilter(value: RepoDiscussionFilter) { if (value == _state.value.filter) return; _state.update { it.copy(filter = value) }; load() }
    fun loadMore() { val state = _state.value; if (state.isLoadingMore || !state.hasNextPage) return; viewModelScope.launch { _state.update { it.copy(isLoadingMore = true) }; when (val result = repository.getDiscussions(owner, name, state.filter, cursor)) { is ApiResult.Success -> { cursor = result.data.endCursor; _state.update { it.copy(items = it.items + result.data.items, totalCount = result.data.totalCount, hasNextPage = result.data.hasNextPage, isLoadingMore = false) } }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoadingMore = false) } } } } }
    fun confirmDelete(item: RepoDiscussion?) = _state.update { it.copy(pendingDelete = item) }
    fun create(title: String, body: String, categoryId: String, done: (RepoDiscussion) -> Unit) { val repositoryId = _state.value.repositoryId; if (repositoryId.isBlank() || title.isBlank() || categoryId.isBlank()) return; viewModelScope.launch { when (val result = repository.createDiscussion(repositoryId, title.trim(), body, categoryId)) { is ApiResult.Success -> { _state.update { it.copy(items = listOf(result.data) + it.items, totalCount = it.totalCount + 1) }; repoUpdateEventBus.emit(RepoUpdateEvent.DiscussionCountChanged(owner, name, _state.value.totalCount)); done(result.data) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun deletePending() { val item = _state.value.pendingDelete ?: return; if (!item.viewerCanDelete || !_state.value.capabilities.canManageIssuesAndPRs) return; viewModelScope.launch { when (val result = repository.deleteDiscussion(item.id)) { is ApiResult.Success -> { _state.update { it.copy(items = it.items.filterNot { value -> value.id == item.id }, pendingDelete = null, totalCount = (it.totalCount - 1).coerceAtLeast(0)) }; repoUpdateEventBus.emit(RepoUpdateEvent.DiscussionCountChanged(owner, name, _state.value.totalCount)) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(pendingDelete = null) } } } } }
}

data class RepoDiscussionDetailUiState(
    val discussion: RepoDiscussion? = null,
    val categories: List<RepoDiscussionCategory> = emptyList(),
    val comments: List<RepoDiscussionComment> = emptyList(),
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val hasMoreComments: Boolean = false,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val pendingDeleteComment: RepoDiscussionComment? = null,
    val pendingDeleteDiscussion: Boolean = false,
)

@HiltViewModel
class RepoDiscussionDetailViewModel @Inject constructor(
    private val repository: RepoDiscussionRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoDiscussionDetailUiState())
    val state: StateFlow<RepoDiscussionDetailUiState> = _state.asStateFlow()
    private var owner = ""; private var name = ""; private var number = 0; private var cursor: String? = null; private var initialized = false
    fun init(owner: String, name: String, number: Int, permission: RepoPermission?) { if (initialized) return; initialized = true; this.owner = owner; this.name = name; this.number = number; permission?.let { _state.update { s -> s.copy(permission = it, capabilities = it.toCapabilities()) } }; load() }
    fun load() { cursor = null; viewModelScope.launch { _state.update { it.copy(isLoading = true, loadFailed = false) }; when (val result = repository.getDiscussion(owner, name, number)) { is ApiResult.Success -> apply(result.data); is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } } } } }
    fun retry() = load()
    fun loadMoreComments() { val state = _state.value; if (!state.hasMoreComments) return; viewModelScope.launch { when (val result = repository.getDiscussion(owner, name, number, cursor)) { is ApiResult.Success -> { cursor = result.data.commentsEndCursor; _state.update { it.copy(comments = it.comments + result.data.comments, hasMoreComments = result.data.hasNextComments) } }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun addComment(body: String, replyToId: String? = null, done: () -> Unit = {}) { val d = _state.value.discussion ?: return; if (body.isBlank()) return; viewModelScope.launch { when (val result = repository.addComment(d.id, body.trim(), replyToId)) { is ApiResult.Success -> { _state.update { it.copy(comments = it.comments + result.data, discussion = it.discussion?.copy(commentCount = it.discussion.commentCount + 1)) }; done() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun updateComment(comment: RepoDiscussionComment, body: String, done: () -> Unit = {}) { if (!comment.viewerCanUpdate || body.isBlank()) return; viewModelScope.launch { when (val result = repository.updateComment(comment.id, body.trim())) { is ApiResult.Success -> { _state.update { it.copy(comments = it.comments.map { value -> if (value.id == comment.id) result.data else value }) }; done() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun confirmDeleteComment(comment: RepoDiscussionComment?) = _state.update { it.copy(pendingDeleteComment = comment) }
    fun deletePendingComment() { val c = _state.value.pendingDeleteComment ?: return; if (!c.viewerCanDelete) return; viewModelScope.launch { when (val result = repository.deleteComment(c.id)) { is ApiResult.Success -> _state.update { it.copy(comments = it.comments.filterNot { value -> value.id == c.id }, pendingDeleteComment = null) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(pendingDeleteComment = null) } } } } }
    fun markAnswer(comment: RepoDiscussionComment, answer: Boolean) { if (answer && !comment.viewerCanMarkAsAnswer || !answer && !comment.viewerCanUnmarkAsAnswer) return; viewModelScope.launch { when (val result = repository.markAnswer(comment.id, answer)) { is ApiResult.Success -> _state.update { it.copy(comments = it.comments.map { value -> if (value.id == comment.id) value.copy(isAnswer = answer) else value }) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun update(title: String, body: String, categoryId: String?, done: () -> Unit = {}) { val d = _state.value.discussion ?: return; if (!d.viewerCanUpdate || title.isBlank()) return; viewModelScope.launch { when (val result = repository.updateDiscussion(d.id, title.trim(), body, categoryId)) { is ApiResult.Success -> { _state.update { it.copy(discussion = result.data) }; done() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun close() { val d = _state.value.discussion ?: return; if (!d.viewerCanClose) return; mutateDiscussion { repository.closeDiscussion(d.id) } }
    fun reopen() { val d = _state.value.discussion ?: return; if (!d.viewerCanReopen) return; mutateDiscussion { repository.reopenDiscussion(d.id) } }
    fun toggleSubscription() { val d = _state.value.discussion ?: return; if (!d.viewerCanSubscribe) return; viewModelScope.launch { when (val result = repository.updateSubscription(d.id, d.viewerSubscription != "SUBSCRIBED")) { is ApiResult.Success -> _state.update { it.copy(discussion = it.discussion?.copy(viewerSubscription = result.data)) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    fun confirmDeleteDiscussion(value: Boolean) = _state.update { it.copy(pendingDeleteDiscussion = value) }
    fun deleteDiscussion(done: () -> Unit) { val d = _state.value.discussion ?: return; if (!d.viewerCanDelete || !_state.value.capabilities.canManageIssuesAndPRs) return; viewModelScope.launch { when (val result = repository.deleteDiscussion(d.id)) { is ApiResult.Success -> { _state.update { it.copy(pendingDeleteDiscussion = false) }; emitCount(); done() }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(pendingDeleteDiscussion = false) } } } } }
    private fun mutateDiscussion(block: suspend () -> ApiResult<RepoDiscussion>) { viewModelScope.launch { when (val result = block()) { is ApiResult.Success -> { _state.update { it.copy(discussion = result.data) }; emitCount() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } } }
    private fun apply(detail: RepoDiscussionDetail) { cursor = detail.commentsEndCursor; _state.update { it.copy(discussion = detail.discussion, categories = detail.categories, comments = detail.comments, permission = detail.permission, capabilities = detail.capabilities, hasMoreComments = detail.hasNextComments, isLoading = false, loadFailed = false) } }
    private suspend fun emitCount() { val result = repository.getDiscussions(owner, name, RepoDiscussionFilter()); val count = (result as? ApiResult.Success)?.data?.totalCount ?: return; repoUpdateEventBus.emit(RepoUpdateEvent.DiscussionCountChanged(owner, name, count)) }
}
