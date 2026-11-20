package com.gitmob.app.ui.repoissues

import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.ui.common.AccountViewModel
import androidx.lifecycle.SavedStateHandle
import com.gitmob.app.data.repository.issueListSearch
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoIssueListUiState(
    val items: List<RepoIssue> = emptyList(),
    val totalCount: Int = 0,
    val filter: RepoIssueFilter = RepoIssueFilter(),
    val submittedQuery: String = "",
    val listRevision: Long = 0,
    val nextCursor: String? = null,
    val loadMoreFailed: Boolean = false,
    val searchLimitReached: Boolean = false,
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val viewerCanCreateIssues: Boolean = false,
    val hasIssuesEnabled: Boolean = false,
    val labels: List<IssueLabel> = emptyList(),
    val milestones: List<IssueMilestone> = emptyList(),
    val assignableUsers: List<SimpleUser> = emptyList(),
    val templatePickerVisible: Boolean = false,
    val templatePickerLoading: Boolean = false,
    val templates: List<IssueTemplate> = emptyList(),
    val blankIssuesEnabled: Boolean = true,
    val invalidTemplateCount: Int = 0,
    val blankCreateRequested: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val hasNextPage: Boolean = false,
    val pendingDelete: RepoIssue? = null,
) {
    val searchActive: Boolean get() = submittedQuery.isNotBlank() || filter.milestone is RepoMilestoneFilter.Title
}

@HiltViewModel
class RepoIssueListViewModel @Inject constructor(
    private val repository: RepoIssueRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    session: AccountSession,
    private val savedSearch: SavedStateHandle = SavedStateHandle(),
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(RepoIssueListUiState())
    val state: StateFlow<RepoIssueListUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var initialized = false
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var requestGeneration = 0L
    private var searchPosition: SearchContinuation? = null
    private var suppressNextIssueCountRefresh = false

    fun init(owner: String, name: String, permission: RepoPermission? = null, viewerCanCreateIssues: Boolean? = null, initialQuery: String = "") {
        if (initialized) return
        initialized = true; this.owner = owner; this.name = name
        _state.update { it.copy(permission = permission ?: RepoPermission.NONE, capabilities = (permission ?: RepoPermission.NONE).let { p -> p.toCapabilities() }, viewerCanCreateIssues = viewerCanCreateIssues ?: false) }
        val savedFilter = savedSearch.get<RepoIssueFilter>("filter")
        if (savedFilter != null) {
            _state.update { it.copy(filter = savedFilter, submittedQuery = savedSearch["query"] ?: "") }
        } else if (initialQuery.isNotBlank()) {
            val parsed = issueListSearch(initialQuery, owner, name, RepoIssueFilter(state = RepoIssueStateFilter.ALL))
            _state.update { it.copy(filter = parsed.filter, submittedQuery = parsed.text) }
        }
        load()
        viewModelScope.launch {
            repoUpdateEventBus.eventsFor(accountEpoch).filterIsInstance<RepoUpdateEvent.IssueCountChanged>().collect { event ->
                if (event.owner == owner && event.name == name) {
                    if (suppressNextIssueCountRefresh) suppressNextIssueCountRefresh = false else load()
                }
            }
        }
        viewModelScope.launch {
            repository.getLabels(owner, name).onSuccess { _state.update { s -> s.copy(labels = it) } }
            repository.getMilestones(owner, name).onSuccess { _state.update { s -> s.copy(milestones = it) } }
            repository.getAssignableUsers(owner, name).onSuccess { _state.update { s -> s.copy(assignableUsers = it) } }
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
                repository.getIssues(owner, name, request.filter)
            } else {
                repository.searchIssues(owner, name, request.submittedQuery, request.filter)
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
        val parsed = issueListSearch(value, owner, name, current.filter)
        if (parsed.text == current.submittedQuery && parsed.filter == current.filter) return
        _state.update { it.copy(submittedQuery = parsed.text, filter = parsed.filter, items = emptyList(), totalCount = 0, listRevision = it.listRevision + 1) }
        load()
    }

    fun refresh() = load()
    fun retry() = load()
    fun setFilter(filter: RepoIssueFilter) { if (filter != _state.value.filter) { _state.update { it.copy(filter = filter, items = emptyList(), totalCount = 0, listRevision = it.listRevision + 1) }; load() } }
    fun setState(value: RepoIssueStateFilter) = setFilter(_state.value.filter.copy(state = value))
    fun setSort(value: RepoIssueSort) = setFilter(_state.value.filter.copy(sort = value))
    fun setLabels(value: Set<String>) = setFilter(_state.value.filter.copy(labels = value))
    fun setAssignee(value: RepoAssigneeFilter) = setFilter(_state.value.filter.copy(assignee = value))
    fun setMilestone(value: RepoMilestoneFilter) = setFilter(_state.value.filter.copy(milestone = value))
    fun setAuthor(value: RepoAuthorFilter) = setFilter(_state.value.filter.copy(author = value))
    fun setMentionedMe(value: Boolean) = setFilter(_state.value.filter.copy(mentionedMe = value))
    fun setUpdatedSince(value: java.time.Instant?) = setFilter(_state.value.filter.copy(updatedSince = value))

    fun beginCreate() {
        val current = _state.value
        if (!current.viewerCanCreateIssues || current.templatePickerLoading) return
        _state.update { it.copy(templatePickerLoading = true, templatePickerVisible = false, blankCreateRequested = false) }
        viewModelScope.launch {
            when (val result = repository.getIssueTemplates(owner, name)) {
                is ApiResult.Success -> {
                    val templates = result.data
                    _state.update {
                        it.copy(
                            templates = templates.templates,
                            blankIssuesEnabled = templates.blankIssuesEnabled,
                            invalidTemplateCount = templates.invalidTemplateCount,
                            templatePickerLoading = false,
                            templatePickerVisible = templates.templates.isNotEmpty() || !templates.blankIssuesEnabled,
                            blankCreateRequested = templates.templates.isEmpty() && templates.blankIssuesEnabled,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(templatePickerLoading = false) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }

    fun dismissTemplatePicker() = _state.update { it.copy(templatePickerVisible = false) }
    fun consumeBlankCreateRequest() = _state.update { it.copy(blankCreateRequested = false) }

    fun loadMore() {
        val request = _state.value
        if (request.isLoading || request.isLoadingMore || !request.hasNextPage) return
        val generation = requestGeneration
        val after = request.nextCursor
        val position = searchPosition
        _state.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
        loadMoreJob = viewModelScope.launch {
            val result = if (!request.searchActive) {
                repository.getIssues(owner, name, request.filter, after)
            } else {
                repository.searchIssues(owner, name, request.submittedQuery, request.filter, position)
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

    private fun applyPage(page: RepoIssuePage, append: Boolean) {
        searchPosition = page.searchNext
        _state.update {
            it.copy(
                items = if (append) (it.items + page.items).distinctBy { item -> item.id } else page.items,
                totalCount = page.totalCount,
                permission = page.permission,
                capabilities = page.capabilities,
                viewerCanCreateIssues = page.viewerCanCreateIssues,
                hasIssuesEnabled = page.hasIssuesEnabled,
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

    fun confirmDelete(issue: RepoIssue?) { _state.update { it.copy(pendingDelete = issue) } }
    fun deletePending() {
        val issue = _state.value.pendingDelete ?: return
        if (!_state.value.capabilities.canDeleteIssues || !issue.viewerCanDelete) return
        viewModelScope.launch {
            when (val result = repository.deleteIssue(issue.id)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(items = it.items.filterNot { item -> item.id == issue.id }, totalCount = (it.totalCount - 1).coerceAtLeast(0), pendingDelete = null) }
                    emitIssueCount(suppressRefresh = true)
                }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(pendingDelete = null) } }
            }
        }
    }

    private suspend fun emitIssueCount(suppressRefresh: Boolean = false) {
        val result = repository.getIssues(owner, name, RepoIssueFilter(), null)
        val count = (result as? ApiResult.Success)?.data?.totalCount ?: return
        suppressNextIssueCountRefresh = suppressRefresh
        repoUpdateEventBus.emit(RepoUpdateEvent.IssueCountChanged(owner, name, count))
    }

    private suspend fun <T> ApiResult<T>.onSuccess(block: suspend (T) -> Unit) { if (this is ApiResult.Success) block(data) }
}
