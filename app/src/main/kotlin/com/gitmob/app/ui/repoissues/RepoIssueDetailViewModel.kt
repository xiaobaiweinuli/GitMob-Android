package com.gitmob.app.ui.repoissues

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
import com.gitmob.app.data.repository.RepoIssueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoIssueDetailUiState(
    val issue: RepoIssue? = null,
    val comments: List<IssueComment> = emptyList(),
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val repositoryId: String? = null,
    val labels: List<IssueLabel> = emptyList(),
    val milestones: List<IssueMilestone> = emptyList(),
    val assignableUsers: List<SimpleUser> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isSubmittingComment: Boolean = false,
    val loadFailed: Boolean = false,
    val hasMoreComments: Boolean = false,
    val pendingDeleteComment: IssueComment? = null,
    val pendingDeleteIssue: Boolean = false,
)

@HiltViewModel
class RepoIssueDetailViewModel @Inject constructor(
    private val repository: RepoIssueRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoIssueDetailUiState())
    val state: StateFlow<RepoIssueDetailUiState> = _state.asStateFlow()
    private var owner = ""; private var name = ""; private var number = 0; private var cursor: String? = null; private var initialized = false

    fun init(owner: String, name: String, number: Int, permission: RepoPermission?) {
        if (initialized) return
        initialized = true; this.owner = owner; this.name = name; this.number = number
        permission?.let { p -> _state.update { it.copy(permission = p, capabilities = p.toCapabilities()) } }
        observeCommentChanges()
        load()
        viewModelScope.launch {
            repository.getLabels(owner, name).success { _state.update { s -> s.copy(labels = it) } }
            repository.getMilestones(owner, name).success { _state.update { s -> s.copy(milestones = it) } }
            repository.getAssignableUsers(owner, name).success { _state.update { s -> s.copy(assignableUsers = it) } }
        }
    }

    private fun observeCommentChanges() {
        viewModelScope.launch {
            repoUpdateEventBus.events
                .filterIsInstance<RepoUpdateEvent.IssueCommentsChanged>()
                .collect { event ->
                    if (event.owner == owner && event.name == name && event.number == number) load()
                }
        }
    }

    fun load() { cursor = null; viewModelScope.launch { _state.update { it.copy(isLoading = true, loadFailed = false) }; when (val result = repository.getIssue(owner, name, number)) { is ApiResult.Success -> apply(result.data); is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } } } } }
    fun retry() = load()

    fun loadMoreComments() {
        if (_state.value.isLoadingMore || !_state.value.hasMoreComments) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = repository.getIssue(owner, name, number, cursor)) {
                is ApiResult.Success -> { cursor = result.data.comments.endCursor; _state.update { it.copy(comments = it.comments + result.data.comments.items, hasMoreComments = result.data.comments.hasNextPage, isLoadingMore = false) } }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoadingMore = false) } }
            }
        }
    }

    fun addComment(body: String, clear: () -> Unit) {
        val issue = _state.value.issue ?: return
        if (body.isBlank()) return
        if (_state.value.isSubmittingComment) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmittingComment = true) }
            when (val result = repository.addComment(issue.id, body.trim())) {
                is ApiResult.Success -> { _state.update { it.copy(comments = it.comments + result.data, issue = it.issue?.copy(commentCount = issue.commentCount + 1), isSubmittingComment = false) }; clear() }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isSubmittingComment = false) } }
            }
        }
    }

    fun updateComment(comment: IssueComment, body: String, done: () -> Unit) {
        if (!comment.viewerCanUpdate || body.isBlank()) return
        if (_state.value.isSubmittingComment) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmittingComment = true) }
            when (val result = repository.updateComment(comment.id, body.trim())) {
                is ApiResult.Success -> { _state.update { it.copy(comments = it.comments.map { old -> if (old.id == comment.id) result.data else old }, isSubmittingComment = false) }; done() }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isSubmittingComment = false) } }
            }
        }
    }

    fun confirmDeleteComment(value: IssueComment?) { _state.update { it.copy(pendingDeleteComment = value) } }
    fun deletePendingComment() {
        val comment = _state.value.pendingDeleteComment ?: return
        if (!comment.viewerCanDelete) return
        viewModelScope.launch { when (val result = repository.deleteComment(comment.id)) { is ApiResult.Success -> _state.update { it.copy(comments = it.comments.filterNot { c -> c.id == comment.id }, issue = it.issue?.copy(commentCount = (it.issue.commentCount.orZero() - 1).coerceAtLeast(0)), pendingDeleteComment = null) }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(pendingDeleteComment = null) } } } }
    }

    fun updateIssue(title: String, body: String, labelIds: List<String>, assigneeIds: List<String>, milestoneId: String?, done: () -> Unit) {
        val issue = _state.value.issue ?: return
        if (!issue.viewerCanUpdate || title.isBlank()) return
        viewModelScope.launch { when (val result = repository.updateIssue(UpdateRepoIssueInput(issue.id, title.trim(), body, labelIds, assigneeIds, milestoneId))) { is ApiResult.Success -> { _state.update { it.copy(issue = result.data) }; done() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } }
    }

    fun closeIssue(reason: IssueStateReason) {
        val issue = _state.value.issue ?: return
        if (!issue.viewerCanClose) return
        viewModelScope.launch { when (val result = repository.closeIssue(issue.id, reason)) { is ApiResult.Success -> { _state.update { it.copy(issue = result.data) }; emitIssueCount() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } }
    }

    fun reopenIssue() {
        val issue = _state.value.issue ?: return
        if (!issue.viewerCanReopen) return
        viewModelScope.launch { when (val result = repository.reopenIssue(issue.id)) { is ApiResult.Success -> { _state.update { it.copy(issue = result.data) }; emitIssueCount() }; is ApiResult.Failure -> errorEventBus.emit(result.error) } }
    }

    fun toggleSubscription() {
        val issue = _state.value.issue ?: return
        if (!issue.viewerCanSubscribe) return
        val subscribed = issue.viewerSubscription != "SUBSCRIBED"
        viewModelScope.launch { when (val result = repository.updateSubscription(issue.id, subscribed)) { is ApiResult.Success -> _state.update { it.copy(issue = it.issue?.copy(viewerSubscription = result.data)) }; is ApiResult.Failure -> errorEventBus.emit(result.error) } }
    }

    fun confirmDeleteIssue(value: Boolean) { _state.update { it.copy(pendingDeleteIssue = value) } }
    fun deleteIssue(onDeleted: () -> Unit) {
        val issue = _state.value.issue ?: return
        if (!_state.value.capabilities.canDeleteIssues || !issue.viewerCanDelete) return
        viewModelScope.launch { when (val result = repository.deleteIssue(issue.id)) { is ApiResult.Success -> { emitIssueCount(); onDeleted() }; is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(pendingDeleteIssue = false) } } } }
    }

    private fun apply(detail: RepoIssueDetail) { cursor = detail.comments.endCursor; _state.update { it.copy(issue = detail.issue, comments = detail.comments.items, permission = detail.permission, capabilities = detail.capabilities, repositoryId = detail.repositoryId, hasMoreComments = detail.comments.hasNextPage, isLoading = false, loadFailed = false) } }
    private suspend fun emitIssueCount() { val result = repository.getIssues(owner, name, RepoIssueFilter()); val count = (result as? ApiResult.Success)?.data?.totalCount ?: return; repoUpdateEventBus.emit(RepoUpdateEvent.IssueCountChanged(owner, name, count)) }
    private suspend fun <T> ApiResult<T>.success(block: suspend (T) -> Unit) { if (this is ApiResult.Success) block(data) }
    private fun Int?.orZero() = this ?: 0
}
