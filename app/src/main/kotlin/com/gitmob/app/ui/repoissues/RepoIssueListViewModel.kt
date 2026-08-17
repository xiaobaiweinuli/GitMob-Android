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
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val viewerCanCreateIssues: Boolean = false,
    val hasIssuesEnabled: Boolean = false,
    val repositoryId: String? = null,
    val labels: List<IssueLabel> = emptyList(),
    val milestones: List<IssueMilestone> = emptyList(),
    val assignableUsers: List<SimpleUser> = emptyList(),
    val templates: List<IssueTemplate> = emptyList(),
    val blankIssuesEnabled: Boolean = true,
    val templatesLoaded: Boolean = false,
    val isLoadingTemplates: Boolean = false,
    val templatesLoadFailed: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val hasNextPage: Boolean = false,
    val pendingDelete: RepoIssue? = null,
)

@HiltViewModel
class RepoIssueListViewModel @Inject constructor(
    private val repository: RepoIssueRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoIssueListUiState())
    val state: StateFlow<RepoIssueListUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var cursor: String? = null
    private var initialized = false
    private var loadJob: Job? = null
    private var suppressNextIssueCountRefresh = false

    fun init(owner: String, name: String, permission: RepoPermission? = null, viewerCanCreateIssues: Boolean? = null) {
        if (initialized) return
        initialized = true; this.owner = owner; this.name = name
        _state.update { it.copy(permission = permission ?: RepoPermission.NONE, capabilities = (permission ?: RepoPermission.NONE).let { p -> p.toCapabilities() }, viewerCanCreateIssues = viewerCanCreateIssues ?: false) }
        load()
        loadIssueTemplates()
        viewModelScope.launch {
            repoUpdateEventBus.events.filterIsInstance<RepoUpdateEvent.IssueCountChanged>().collect { event ->
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

    fun loadIssueTemplates() {
        if (_state.value.isLoadingTemplates) return
        _state.update { it.copy(isLoadingTemplates = true, templatesLoadFailed = false) }
        viewModelScope.launch {
            when (val result = repository.getIssueTemplates(owner, name)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        blankIssuesEnabled = result.data.first,
                        templates = result.data.second,
                        templatesLoaded = true,
                        isLoadingTemplates = false,
                        templatesLoadFailed = false,
                    )
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(templatesLoaded = false, isLoadingTemplates = false, templatesLoadFailed = true) }
                }
            }
        }
    }

    fun load() {
        loadJob?.cancel(); cursor = null
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = repository.getIssues(owner, name, _state.value.filter)) {
                is ApiResult.Success -> _state.update { it.merge(result.data, false) }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } }
            }
        }
    }

    fun refresh() = load()
    fun retry() = load()
    fun setFilter(filter: RepoIssueFilter) { if (filter != _state.value.filter) { _state.update { it.copy(filter = filter) }; load() } }
    fun setState(value: RepoIssueStateFilter) = setFilter(_state.value.filter.copy(state = value))
    fun setSort(value: RepoIssueSort) = setFilter(_state.value.filter.copy(sort = value))
    fun setLabels(value: Set<String>) = setFilter(_state.value.filter.copy(labels = value))
    fun setAssignee(value: RepoAssigneeFilter) = setFilter(_state.value.filter.copy(assignee = value))
    fun setMilestone(value: RepoMilestoneFilter) = setFilter(_state.value.filter.copy(milestone = value))
    fun setAuthor(value: RepoAuthorFilter) = setFilter(_state.value.filter.copy(author = value))
    fun setMentioned(value: Boolean) = setFilter(_state.value.filter.copy(mentioned = value))
    fun setSubscribed(value: Boolean) = setFilter(_state.value.filter.copy(subscribed = value))
    fun setUpdatedSince(value: java.time.Instant?) = setFilter(_state.value.filter.copy(updatedSince = value))

    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = repository.getIssues(owner, name, current.filter, cursor)) {
                is ApiResult.Success -> { cursor = result.data.endCursor; _state.update { it.copy(items = it.items + result.data.items, totalCount = result.data.totalCount, hasNextPage = result.data.hasNextPage, isLoadingMore = false) } }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoadingMore = false) } }
            }
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

    fun createIssue(title: String, body: String, template: IssueTemplate?, labelIds: List<String>, assigneeIds: List<String>, milestoneId: String?, onCreated: (RepoIssue) -> Unit) {
        val repositoryId = _state.value.repositoryId ?: return
        if (!_state.value.viewerCanCreateIssues || title.isBlank()) return
        viewModelScope.launch {
            when (val result = repository.createIssue(CreateRepoIssueInput(repositoryId, title.trim(), body, labelIds, assigneeIds, milestoneId, template?.name))) {
                is ApiResult.Success -> { emitIssueCount(suppressRefresh = true); onCreated(result.data); load() }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    private suspend fun emitIssueCount(suppressRefresh: Boolean = false) {
        val result = repository.getIssues(owner, name, RepoIssueFilter(), null)
        val count = (result as? ApiResult.Success)?.data?.totalCount ?: return
        suppressNextIssueCountRefresh = suppressRefresh
        repoUpdateEventBus.emit(RepoUpdateEvent.IssueCountChanged(owner, name, count))
    }

    private fun RepoIssueListUiState.merge(page: RepoIssuePage, loadingMore: Boolean) = copy(
        items = if (loadingMore) items + page.items else page.items, totalCount = page.totalCount, permission = page.permission,
        capabilities = page.capabilities, viewerCanCreateIssues = page.viewerCanCreateIssues, hasIssuesEnabled = page.hasIssuesEnabled,
        repositoryId = page.repositoryId, hasNextPage = page.hasNextPage, isLoading = false, isLoadingMore = false, loadFailed = false,
    ).also { cursor = page.endCursor }

    private suspend fun <T> ApiResult<T>.onSuccess(block: suspend (T) -> Unit) { if (this is ApiResult.Success) block(data) }
}
