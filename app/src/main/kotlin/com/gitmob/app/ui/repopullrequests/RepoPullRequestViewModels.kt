package com.gitmob.app.ui.repopullrequests

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
import com.gitmob.app.data.repository.RepoPullRequestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoPullRequestListUiState(
    val items: List<RepoPullRequest> = emptyList(),
    val filter: RepoPullRequestFilter = RepoPullRequestFilter(),
    val labels: List<IssueLabel> = emptyList(),
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val hasPullRequestsEnabled: Boolean = true,
    val creationPolicy: PullRequestCreationPolicy = PullRequestCreationPolicy.UNKNOWN,
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasNextPage: Boolean = false,
    val loadFailed: Boolean = false,
)

@HiltViewModel
class RepoPullRequestListViewModel @Inject constructor(
    private val repository: RepoPullRequestRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoPullRequestListUiState())
    val state: StateFlow<RepoPullRequestListUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var cursor: String? = null
    private var initialized = false
    private var loadJob: Job? = null

    fun init(owner: String, name: String, permission: RepoPermission?) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        permission?.let { value -> _state.update { it.copy(permission = value, capabilities = value.toCapabilities()) } }
        load()
        viewModelScope.launch {
            when (val result = repository.getLabels(owner, name)) {
                is ApiResult.Success -> _state.update { it.copy(labels = result.data) }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        cursor = null
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = repository.getPullRequests(owner, name, _state.value.filter)) {
                is ApiResult.Success -> {
                    cursor = result.data.endCursor
                    _state.update {
                        it.copy(
                            items = result.data.items,
                            permission = result.data.permission,
                            capabilities = result.data.capabilities,
                            hasPullRequestsEnabled = result.data.hasPullRequestsEnabled,
                            creationPolicy = result.data.creationPolicy,
                            totalCount = result.data.totalCount,
                            hasNextPage = result.data.hasNextPage,
                            isLoading = false,
                            loadFailed = false,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoading = false, loadFailed = true) }
                }
            }
        }
    }

    fun refresh() = load()
    fun retry() = load()
    fun setState(value: RepoPullRequestStateFilter) = setFilter(_state.value.filter.copy(state = value))
    fun setSort(value: RepoPullRequestSort) = setFilter(_state.value.filter.copy(sort = value))
    fun setBase(value: String?) = setFilter(_state.value.filter.copy(baseRefName = value))
    fun setHead(value: String?) = setFilter(_state.value.filter.copy(headRefName = value))
    fun setLabels(value: Set<String>) = setFilter(_state.value.filter.copy(labels = value))

    private fun setFilter(value: RepoPullRequestFilter) {
        if (value == _state.value.filter) return
        _state.update { it.copy(filter = value) }
        load()
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = repository.getPullRequests(owner, name, current.filter, cursor)) {
                is ApiResult.Success -> {
                    cursor = result.data.endCursor
                    _state.update {
                        it.copy(
                            items = it.items + result.data.items,
                            totalCount = result.data.totalCount,
                            hasNextPage = result.data.hasNextPage,
                            isLoadingMore = false,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }
}

data class RepoPullRequestDetailUiState(
    val pullRequest: RepoPullRequest? = null,
    val comments: List<RepoPullRequestComment> = emptyList(),
    val reviews: List<RepoPullRequestReview> = emptyList(),
    val threads: List<RepoPullRequestReviewThread> = emptyList(),
    val commits: List<RepoPullRequestCommit> = emptyList(),
    val files: List<RepoPullRequestFile> = emptyList(),
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val allowedMergeMethods: Set<RepoPullRequestMergeMethod> = emptySet(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isSubmittingComment: Boolean = false,
    val hasMoreComments: Boolean = false,
    val loadFailed: Boolean = false,
    val pendingDeleteComment: RepoPullRequestComment? = null,
)

@HiltViewModel
class RepoPullRequestDetailViewModel @Inject constructor(
    private val repository: RepoPullRequestRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoPullRequestDetailUiState())
    val state: StateFlow<RepoPullRequestDetailUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var number = 0
    private var cursor: String? = null
    private var initialized = false

    fun init(owner: String, name: String, number: Int, permission: RepoPermission?) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        this.number = number
        permission?.let { value -> _state.update { it.copy(permission = value, capabilities = value.toCapabilities()) } }
        observeCommentChanges()
        load()
    }

    private fun observeCommentChanges() {
        viewModelScope.launch {
            repoUpdateEventBus.events
                .filterIsInstance<RepoUpdateEvent.PullRequestCommentsChanged>()
                .collect { event ->
                    if (event.owner == owner && event.name == name && event.number == number) load()
                }
        }
        viewModelScope.launch {
            repoUpdateEventBus.events
                .filterIsInstance<RepoUpdateEvent.CodeChanged>()
                .collect { event ->
                    if (event.owner == owner && event.name == name) load()
                }
        }
    }

    fun load() {
        cursor = null
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = repository.getPullRequest(owner, name, number)) {
                is ApiResult.Success -> apply(result.data)
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoading = false, loadFailed = true) }
                }
            }
        }
    }

    fun retry() = load()

    fun loadMoreComments() {
        if (_state.value.isLoadingMore || !_state.value.hasMoreComments) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = repository.getPullRequest(owner, name, number, cursor)) {
                is ApiResult.Success -> {
                    cursor = result.data.commentsEndCursor
                    _state.update {
                        it.copy(
                            comments = it.comments + result.data.comments,
                            hasMoreComments = result.data.commentsHasNextPage,
                            isLoadingMore = false,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    fun addComment(body: String, done: () -> Unit) {
        val pullRequest = _state.value.pullRequest ?: return
        if (body.isBlank()) return
        if (_state.value.isSubmittingComment) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmittingComment = true) }
            when (val result = repository.addComment(pullRequest.id, body.trim())) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            comments = it.comments + result.data,
                            pullRequest = it.pullRequest?.copy(commentCount = pullRequest.commentCount + 1),
                            isSubmittingComment = false,
                        )
                    }
                    done()
                }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isSubmittingComment = false) } }
            }
        }
    }

    fun updateComment(comment: RepoPullRequestComment, body: String, done: () -> Unit) {
        if (!comment.viewerCanUpdate || body.isBlank()) return
        if (_state.value.isSubmittingComment) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmittingComment = true) }
            when (val result = repository.updateComment(comment.id, body.trim())) {
                is ApiResult.Success -> {
                    _state.update { state ->
                        state.copy(comments = state.comments.map { if (it.id == comment.id) result.data else it }, isSubmittingComment = false)
                    }
                    done()
                }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isSubmittingComment = false) } }
            }
        }
    }

    fun confirmDeleteComment(comment: RepoPullRequestComment?) = _state.update { it.copy(pendingDeleteComment = comment) }

    fun deletePendingComment() {
        val comment = _state.value.pendingDeleteComment ?: return
        if (!comment.viewerCanDelete) return
        viewModelScope.launch {
            when (val result = repository.deleteComment(comment.id)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        comments = it.comments.filterNot { item -> item.id == comment.id },
                        pendingDeleteComment = null,
                        pullRequest = it.pullRequest?.copy(commentCount = (it.pullRequest.commentCount - 1).coerceAtLeast(0)),
                    )
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(pendingDeleteComment = null) }
                }
            }
        }
    }

    fun submitReview(event: RepoPullRequestReviewEvent, body: String, done: () -> Unit) {
        val pullRequest = _state.value.pullRequest ?: return
        viewModelScope.launch {
            when (val result = repository.submitReview(pullRequest.id, event, body.trim())) {
                is ApiResult.Success -> {
                    _state.update { it.copy(reviews = it.reviews + result.data) }
                    done()
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun close() = updateState { repository.closePullRequest(it.id) }
    fun reopen() = updateState { repository.reopenPullRequest(it.id) }
    fun toggleDraft() = updateState {
        if (it.isDraft) repository.markReadyForReview(it.id) else repository.convertToDraft(it.id)
    }
    fun updateBranch() = updateState { repository.updateBranch(it.id) }

    fun merge(method: RepoPullRequestMergeMethod, headline: String?, body: String?, done: () -> Unit) {
        val pullRequest = _state.value.pullRequest ?: return
        if (!_state.value.capabilities.canPush || method !in _state.value.allowedMergeMethods) return
        viewModelScope.launch {
            when (val result = repository.mergePullRequest(pullRequest.id, method, headline, body)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(pullRequest = result.data) }
                    emitCount()
                    done()
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun toggleAutoMerge(method: RepoPullRequestMergeMethod) {
        val pullRequest = _state.value.pullRequest ?: return
        val enabled = !pullRequest.autoMergeEnabled
        if (enabled && !pullRequest.viewerCanEnableAutoMerge) return
        if (!enabled && !pullRequest.viewerCanDisableAutoMerge) return
        viewModelScope.launch {
            when (val result = repository.setAutoMerge(pullRequest.id, enabled, method)) {
                is ApiResult.Success -> _state.update { it.copy(pullRequest = result.data) }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun toggleSubscription() {
        val pullRequest = _state.value.pullRequest ?: return
        if (!pullRequest.viewerCanSubscribe) return
        val subscribed = pullRequest.viewerSubscription != "SUBSCRIBED"
        viewModelScope.launch {
            when (val result = repository.updateSubscription(pullRequest.id, subscribed)) {
                is ApiResult.Success -> _state.update { it.copy(pullRequest = it.pullRequest?.copy(viewerSubscription = result.data)) }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun replyToThread(thread: RepoPullRequestReviewThread, body: String, done: () -> Unit) {
        if (!thread.viewerCanReply || body.isBlank()) return
        viewModelScope.launch {
            when (val result = repository.replyToThread(thread.id, body.trim())) {
                is ApiResult.Success -> {
                    _state.update { state ->
                        state.copy(threads = state.threads.map {
                            if (it.id == thread.id) it.copy(comments = it.comments + result.data) else it
                        })
                    }
                    done()
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun toggleThreadResolved(thread: RepoPullRequestReviewThread) {
        val resolved = !thread.isResolved
        if (resolved && !thread.viewerCanResolve) return
        if (!resolved && !thread.viewerCanUnresolve) return
        viewModelScope.launch {
            when (val result = repository.setThreadResolved(thread.id, resolved)) {
                is ApiResult.Success -> _state.update { state ->
                    state.copy(threads = state.threads.map { if (it.id == thread.id) it.copy(isResolved = resolved) else it })
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    private fun updateState(block: suspend (RepoPullRequest) -> ApiResult<RepoPullRequest>) {
        val pullRequest = _state.value.pullRequest ?: return
        viewModelScope.launch {
            when (val result = block(pullRequest)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(pullRequest = result.data) }
                    emitCount()
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    private fun apply(detail: RepoPullRequestDetail) {
        cursor = detail.commentsEndCursor
        _state.update {
            it.copy(
                pullRequest = detail.pullRequest,
                comments = detail.comments,
                reviews = detail.reviews,
                threads = detail.reviewThreads,
                commits = detail.commits,
                files = detail.files,
                permission = detail.permission,
                capabilities = detail.capabilities,
                allowedMergeMethods = detail.allowedMergeMethods,
                hasMoreComments = detail.commentsHasNextPage,
                isLoading = false,
                loadFailed = false,
            )
        }
    }

    private suspend fun emitCount() {
        val result = repository.getPullRequests(owner, name, RepoPullRequestFilter())
        val count = (result as? ApiResult.Success)?.data?.totalCount ?: return
        repoUpdateEventBus.emit(RepoUpdateEvent.PullRequestCountChanged(owner, name, count))
    }
}
