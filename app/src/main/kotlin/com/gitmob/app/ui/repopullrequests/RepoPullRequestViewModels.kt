package com.gitmob.app.ui.repopullrequests

import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.ui.common.AccountViewModel
import androidx.lifecycle.SavedStateHandle
import com.gitmob.app.data.repository.pullListSearch
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.share.ShareLinkLauncher
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoPullRequestRepository
import com.gitmob.app.data.repository.ConversationEditRepository
import com.gitmob.app.ui.common.ConversationEditHistoryUiState
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
    val submittedQuery: String = "",
    val listRevision: Long = 0,
    val nextCursor: String? = null,
    val loadMoreFailed: Boolean = false,
    val searchLimitReached: Boolean = false,
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
) {
    val searchActive: Boolean get() = submittedQuery.isNotBlank()
}

@HiltViewModel
class RepoPullRequestListViewModel @Inject constructor(
    private val repository: RepoPullRequestRepository,
    private val errorEventBus: ErrorEventBus,
    session: AccountSession,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val savedSearch: SavedStateHandle,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(RepoPullRequestListUiState())
    val state: StateFlow<RepoPullRequestListUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var initialized = false
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var requestGeneration = 0L
    private var searchPosition: SearchContinuation? = null

    fun init(owner: String, name: String, permission: RepoPermission?, initialQuery: String = "") {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        permission?.let { value -> _state.update { it.copy(permission = value, capabilities = value.toCapabilities()) } }
        val savedFilter = savedSearch.get<RepoPullRequestFilter>("filter")
        if (savedFilter != null) {
            _state.update { it.copy(filter = savedFilter, submittedQuery = savedSearch["query"] ?: "") }
        } else if (initialQuery.isNotBlank()) {
            val parsed = pullListSearch(initialQuery, owner, name, RepoPullRequestFilter(state = RepoPullRequestStateFilter.ALL))
            _state.update { it.copy(filter = parsed.filter, submittedQuery = parsed.text) }
        }
        load()
        viewModelScope.launch {
            repoUpdateEventBus.eventsFor(accountEpoch).filterIsInstance<RepoUpdateEvent.PullRequestCountChanged>().collect { event ->
                if (event.owner == owner && event.name == name) load()
            }
        }
        viewModelScope.launch {
            when (val result = repository.getLabels(owner, name)) {
                is ApiResult.Success -> _state.update { it.copy(labels = result.data) }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun load() {
        val generation = ++requestGeneration
        loadJob?.cancel()
        loadMoreJob?.cancel()
        searchPosition = null
        val request = _state.value
        savedSearch["filter"] = request.filter
        savedSearch["query"] = request.submittedQuery
        _state.update { it.copy(isLoading = true, isLoadingMore = false, hasNextPage = false, nextCursor = null, loadFailed = false, loadMoreFailed = false, searchLimitReached = false) }
        loadJob = viewModelScope.launch {
            val result = if (!request.searchActive) {
                repository.getPullRequests(owner, name, request.filter)
            } else {
                repository.searchPullRequests(owner, name, request.submittedQuery, request.filter)
            }
            if (generation != requestGeneration) return@launch
            when (result) {
                is ApiResult.Success -> applyPage(result.data, append = false)
                is ApiResult.Failure -> {
                    _state.update { it.copy(isLoading = false, loadFailed = true) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }

    fun submitSearch(value: String) {
        val current = _state.value
        val parsed = pullListSearch(value, owner, name, current.filter)
        if (parsed.text == current.submittedQuery && parsed.filter == current.filter) return
        _state.update { it.copy(submittedQuery = parsed.text, filter = parsed.filter, items = emptyList(), totalCount = 0, listRevision = it.listRevision + 1) }
        load()
    }

    fun refresh() = load()
    fun retry() = load()
    fun setState(value: RepoPullRequestStateFilter) = setFilter(_state.value.filter.copy(state = value))
    fun setSort(value: RepoPullRequestSort) = setFilter(_state.value.filter.copy(sort = value))
    fun setLabels(value: Set<String>) = setFilter(_state.value.filter.copy(labels = value))

    private fun setFilter(value: RepoPullRequestFilter) {
        if (value == _state.value.filter) return
        _state.update { it.copy(filter = value, items = emptyList(), totalCount = 0, listRevision = it.listRevision + 1) }
        load()
    }

    fun loadMore() {
        val request = _state.value
        if (request.isLoading || request.isLoadingMore || !request.hasNextPage) return
        val generation = requestGeneration
        val after = request.nextCursor
        val position = searchPosition
        _state.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
        loadMoreJob = viewModelScope.launch {
            val result = if (!request.searchActive) {
                repository.getPullRequests(owner, name, request.filter, after)
            } else {
                repository.searchPullRequests(owner, name, request.submittedQuery, request.filter, position)
            }
            if (generation != requestGeneration) return@launch
            when (result) {
                is ApiResult.Success -> applyPage(result.data, append = true)
                is ApiResult.Failure -> {
                    _state.update { it.copy(isLoadingMore = false, loadMoreFailed = true) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }

    private fun applyPage(page: RepoPullRequestPage, append: Boolean) {
        searchPosition = page.searchNext
        _state.update {
            it.copy(
                items = if (append) (it.items + page.items).distinctBy { item -> item.id } else page.items,
                totalCount = page.totalCount,
                permission = page.permission,
                capabilities = page.capabilities,
                hasPullRequestsEnabled = page.hasPullRequestsEnabled,
                creationPolicy = page.creationPolicy,
                hasNextPage = page.hasNextPage,
                searchLimitReached = page.searchLimitReached,
                nextCursor = page.endCursor,
                isLoading = false,
                isLoadingMore = false,
                loadFailed = false,
                loadMoreFailed = false,
            )
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
    val isDeletingComment: Boolean = false,
    val isMerging: Boolean = false,
    val isUpdatingAutoMerge: Boolean = false,
    val isUpdatingSubscription: Boolean = false,
    val updatingThreadIds: Set<String> = emptySet(),
    val editHistory: ConversationEditHistoryUiState = ConversationEditHistoryUiState(),
)

@HiltViewModel
class RepoPullRequestDetailViewModel @Inject constructor(
    private val repository: RepoPullRequestRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val editRepository: ConversationEditRepository,
    private val shareLinkLauncher: ShareLinkLauncher,
    session: AccountSession,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(RepoPullRequestDetailUiState())
    val state: StateFlow<RepoPullRequestDetailUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var number = 0
    private var cursor: String? = null
    private var initialized = false
    private var editJob: Job? = null

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
            repoUpdateEventBus.eventsFor(accountEpoch)
                .filterIsInstance<RepoUpdateEvent.PullRequestCommentsChanged>()
                .collect { event ->
                    if (event.owner == owner && event.name == name && event.number == number) load()
                }
        }
        viewModelScope.launch {
            repoUpdateEventBus.eventsFor(accountEpoch)
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

    fun share() { if (accountEpoch.isCurrent) _state.value.pullRequest?.url?.takeIf(String::isNotBlank)?.let(shareLinkLauncher::share) }

    fun openEditHistory(nodeId: String) {
        editJob?.cancel()
        val current = _state.value.editHistory
        if (current.targetNodeId == nodeId && current.items.isNotEmpty()) {
            _state.update { it.copy(editHistory = current.copy(isOpen = true, selectedEdit = null)) }
            return
        }
        editJob = viewModelScope.launch {
            _state.update { it.copy(editHistory = ConversationEditHistoryUiState(targetNodeId = nodeId, isOpen = true, isLoading = true)) }
            when (val result = editRepository.getEdits(nodeId)) {
                is ApiResult.Success -> _state.update { it.copy(editHistory = it.editHistory.copy(items = result.data.items, hasNextPage = result.data.hasNextPage, endCursor = result.data.endCursor, isLoading = false)) }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(editHistory = it.editHistory.copy(isLoading = false, loadFailed = true)) } }
            }
        }
    }
    fun loadMoreEditHistory() {
        val history = _state.value.editHistory
        val nodeId = history.targetNodeId ?: return
        if (history.isLoadingMore || !history.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(editHistory = it.editHistory.copy(isLoadingMore = true)) }
            when (val result = editRepository.getEdits(nodeId, history.endCursor)) {
                is ApiResult.Success -> _state.update { it.copy(editHistory = it.editHistory.copy(items = it.editHistory.items + result.data.items, hasNextPage = result.data.hasNextPage, endCursor = result.data.endCursor, isLoadingMore = false)) }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(editHistory = it.editHistory.copy(isLoadingMore = false)) } }
            }
        }
    }
    fun selectEdit(edit: ConversationEdit) = _state.update { it.copy(editHistory = it.editHistory.copy(selectedEdit = edit)) }
    fun clearSelectedEdit() = _state.update { it.copy(editHistory = it.editHistory.copy(selectedEdit = null)) }
    fun closeEditHistory() = _state.update { it.copy(editHistory = it.editHistory.copy(isOpen = false, selectedEdit = null)) }
    fun retryEditHistory() { _state.value.editHistory.targetNodeId?.let(::openEditHistory) }

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

    fun confirmDeleteComment(comment: RepoPullRequestComment?) {
        if (!_state.value.isDeletingComment) _state.update { it.copy(pendingDeleteComment = comment) }
    }

    fun deletePendingComment() {
        val comment = _state.value.pendingDeleteComment ?: return
        if (!comment.viewerCanDelete) return
        if (_state.value.isDeletingComment) return
        _state.update { it.copy(isDeletingComment = true) }
        viewModelScope.launch {
            try {
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
            } finally {
                _state.update { it.copy(isDeletingComment = false) }
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
        if (_state.value.isMerging || _state.value.isUpdatingAutoMerge) return
        _state.update { it.copy(isMerging = true) }
        viewModelScope.launch {
            val merged = try {
                when (val result = repository.mergePullRequest(pullRequest.id, method, headline, body)) {
                    is ApiResult.Success -> {
                        _state.update { it.copy(pullRequest = result.data) }
                        true
                    }
                    is ApiResult.Failure -> {
                        errorEventBus.emit(result.error)
                        false
                    }
                }
            } finally {
                _state.update { it.copy(isMerging = false) }
            }
            if (merged) {
                done()
                emitCount()
            }
        }
    }

    fun toggleAutoMerge(method: RepoPullRequestMergeMethod, done: () -> Unit = {}) {
        val pullRequest = _state.value.pullRequest ?: return
        val enabled = !pullRequest.autoMergeEnabled
        if (enabled && !pullRequest.viewerCanEnableAutoMerge) return
        if (!enabled && !pullRequest.viewerCanDisableAutoMerge) return
        if (_state.value.isMerging || _state.value.isUpdatingAutoMerge) return
        _state.update { it.copy(isUpdatingAutoMerge = true) }
        viewModelScope.launch {
            try {
                when (val result = repository.setAutoMerge(pullRequest.id, enabled, method)) {
                    is ApiResult.Success -> {
                        _state.update { it.copy(pullRequest = result.data) }
                        done()
                    }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update { it.copy(isUpdatingAutoMerge = false) }
            }
        }
    }

    fun toggleSubscription() {
        val pullRequest = _state.value.pullRequest ?: return
        if (!pullRequest.viewerCanSubscribe) return
        val subscribed = pullRequest.viewerSubscription != "SUBSCRIBED"
        if (_state.value.isUpdatingSubscription) return
        _state.update { it.copy(isUpdatingSubscription = true) }
        viewModelScope.launch {
            try {
                when (val result = repository.updateSubscription(pullRequest.id, subscribed)) {
                    is ApiResult.Success -> _state.update { it.copy(pullRequest = it.pullRequest?.copy(viewerSubscription = result.data)) }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update { it.copy(isUpdatingSubscription = false) }
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
        if (thread.id in _state.value.updatingThreadIds) return
        _state.update { it.copy(updatingThreadIds = it.updatingThreadIds + thread.id) }
        viewModelScope.launch {
            try {
                when (val result = repository.setThreadResolved(thread.id, resolved)) {
                    is ApiResult.Success -> _state.update { state ->
                        state.copy(threads = state.threads.map { if (it.id == thread.id) it.copy(isResolved = resolved) else it })
                    }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update { it.copy(updatingThreadIds = it.updatingThreadIds - thread.id) }
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
