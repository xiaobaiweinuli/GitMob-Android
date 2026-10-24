package com.gitmob.app.ui.repos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepoFilters
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.model.RepoSort
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.repository.RepoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReposUiState(
    val filters: RepoFilters = RepoFilters(),
    val repos: List<RepoListItem> = emptyList(),
    val hasLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val loadMoreFailed: Boolean = false,
    val hasNextPage: Boolean = false,
    val endCursor: String? = null,
    val requiresManualContinue: Boolean = false,
    val requestGeneration: Long = 0,
    val completedBatches: Int = 0,
    val scrollToTop: Boolean = true,
    val ownerContext: RepositoryCreateOwner? = null,
) {
    val isBusy: Boolean get() = isLoading || isRefreshing || isLoadingMore
    val canAutoLoadMore: Boolean
        get() = !isBusy && hasNextPage && !endCursor.isNullOrBlank() &&
            !loadMoreFailed && !requiresManualContinue
}

@HiltViewModel
class ReposViewModel @Inject constructor(
    private val repoRepository: RepoRepository,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val errorEventBus: ErrorEventBus,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private companion object {
        const val QUERY_KEY = "repos.query"
        const val VISIBILITY_KEY = "repos.visibility"
        const val ARCHIVED_KEY = "repos.archived"
        const val FORK_KEY = "repos.fork"
        const val ISSUES_KEY = "repos.issues"
        const val SORT_KEY = "repos.sort"
    }

    private val _state = MutableStateFlow(
        ReposUiState(
            filters = RepoFilters(
                query = savedStateHandle[QUERY_KEY] ?: "",
                visibility = RepoVisibility.entries.firstOrNull { it.name == savedStateHandle.get<String>(VISIBILITY_KEY) },
                isArchived = savedStateHandle[ARCHIVED_KEY],
                isFork = savedStateHandle[FORK_KEY],
                hasIssuesEnabled = savedStateHandle[ISSUES_KEY],
                sort = RepoSort.entries.firstOrNull { it.name == savedStateHandle.get<String>(SORT_KEY) }
                    ?: RepoSort.UPDATED_DESC,
            ),
        ),
    )
    val state: StateFlow<ReposUiState> = _state.asStateFlow()

    private var login: String? = null
    private var initialized = false
    private var queryJob: Job? = null

    init {
        observeRepoUpdates()
    }

    fun init(login: String?) {
        if (initialized && this.login.equals(login, ignoreCase = true)) return
        if (initialized) {
            saveFilters(RepoFilters())
            _state.update { ReposUiState(requestGeneration = it.requestGeneration) }
        }
        initialized = true
        this.login = login
        loadFirstPage(preserveItems = false)
    }

    fun updateSearch(query: String) = changeFilters(_state.value.filters.copy(query = query), debounce = true)
    fun clearSearch() = changeFilters(_state.value.filters.copy(query = ""))
    fun selectVisibility(value: RepoVisibility?) = changeFilters(_state.value.filters.copy(visibility = value))
    fun selectArchived(value: Boolean?) = changeFilters(_state.value.filters.copy(isArchived = value))
    fun selectFork(value: Boolean?) = changeFilters(_state.value.filters.copy(isFork = value))
    fun selectIssues(value: Boolean?) = changeFilters(_state.value.filters.copy(hasIssuesEnabled = value))
    fun selectSort(value: RepoSort) = changeFilters(_state.value.filters.copy(sort = value))
    fun reset() = changeFilters(RepoFilters(query = _state.value.filters.query))

    private fun changeFilters(filters: RepoFilters, debounce: Boolean = false) {
        val changed = filters.normalized() != _state.value.filters.normalized()
        saveFilters(filters)
        _state.update { it.copy(filters = filters) }
        if (initialized && changed) loadFirstPage(preserveItems = false, debounce = debounce)
    }

    private fun saveFilters(filters: RepoFilters) {
        savedStateHandle[QUERY_KEY] = filters.query
        savedStateHandle[VISIBILITY_KEY] = filters.visibility?.name
        savedStateHandle[ARCHIVED_KEY] = filters.isArchived
        savedStateHandle[FORK_KEY] = filters.isFork
        savedStateHandle[ISSUES_KEY] = filters.hasIssuesEnabled
        savedStateHandle[SORT_KEY] = filters.sort.name
    }

    fun refresh() {
        if (!initialized || _state.value.isRefreshing) return
        loadFirstPage(preserveItems = true, forceRefresh = true)
    }

    fun retry() {
        val current = _state.value
        if (!initialized || current.isBusy) return
        when {
            current.loadMoreFailed -> loadNextPage(manual = true)
            current.loadFailed -> loadFirstPage(preserveItems = false)
        }
    }

    fun loadMore() = loadNextPage(manual = false)
    fun continueSearch() = loadNextPage(manual = true)

    fun onScrolledToTop(generation: Long) {
        if (_state.value.requestGeneration == generation) _state.update { it.copy(scrollToTop = false) }
    }

    private fun loadFirstPage(
        preserveItems: Boolean,
        forceRefresh: Boolean = false,
        debounce: Boolean = false,
    ) {
        queryJob?.cancel()
        val current = _state.value
        val generation = current.requestGeneration + 1
        val requestLogin = login
        val filters = current.filters.normalized()
        _state.value = if (preserveItems && current.hasLoaded) {
            current.copy(isRefreshing = true, isLoading = false, isLoadingMore = false, requestGeneration = generation)
        } else {
            ReposUiState(
                filters = current.filters,
                ownerContext = current.ownerContext,
                isLoading = true,
                requestGeneration = generation,
            )
        }
        queryJob = viewModelScope.launch {
            if (debounce) delay(300)
            val result = repoRepository.getRepos(requestLogin, after = null, filters = filters, forceRefresh = forceRefresh)
            if (!isCurrentRequest(requestLogin, filters, generation)) return@launch
            when (result) {
                is ApiResult.Success -> applyPage(result.data, append = false)
                is ApiResult.Failure -> {
                    _state.update { it.copy(isLoading = false, isRefreshing = false, loadFailed = !it.hasLoaded) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }

    private fun loadNextPage(manual: Boolean) {
        val current = _state.value
        val cursor = current.endCursor
        if (!initialized || current.isBusy || !current.hasNextPage || cursor.isNullOrBlank()) return
        if (!manual && (current.loadMoreFailed || current.requiresManualContinue)) return
        val requestLogin = login
        val filters = current.filters.normalized()
        val generation = current.requestGeneration
        _state.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
        queryJob = viewModelScope.launch {
            val result = repoRepository.getRepos(requestLogin, cursor, filters)
            if (!isCurrentRequest(requestLogin, filters, generation) || _state.value.endCursor != cursor) return@launch
            when (result) {
                is ApiResult.Success -> applyPage(result.data, append = true)
                is ApiResult.Failure -> {
                    _state.update { it.copy(isLoadingMore = false, loadMoreFailed = true) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }

    private fun isCurrentRequest(requestLogin: String?, filters: RepoFilters, generation: Long): Boolean =
        login == requestLogin && _state.value.requestGeneration == generation && _state.value.filters.normalized() == filters

    private fun applyPage(page: RepoList, append: Boolean) {
        _state.update { current ->
            val merged = ((if (append) current.repos else emptyList()) + page.items)
                .associateBy(RepoListItem::key).values.toList()
            current.copy(
                repos = current.filters.sort.sorted(merged),
                hasLoaded = true,
                ownerContext = page.ownerContext?.owner ?: current.ownerContext,
                hasNextPage = page.hasNextPage,
                endCursor = page.endCursor,
                requiresManualContinue = page.hasNextPage &&
                    (page.requiresManualContinue || merged.isEmpty() || (append && merged.size == current.repos.size)),
                completedBatches = if (append) current.completedBatches + 1 else 1,
                isLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                loadFailed = false,
                loadMoreFailed = false,
            )
        }
    }

    private fun observeRepoUpdates() {
        viewModelScope.launch {
            repoUpdateEventBus.events.collect { event ->
                if (!initialized) return@collect
                val current = _state.value
                val owner = current.ownerContext?.login ?: login
                val related = owner.equals(event.owner, ignoreCase = true) ||
                    (event is RepoUpdateEvent.RepositoryAddressChanged && owner.equals(event.newOwner, ignoreCase = true)) ||
                    current.repos.any { it.matches(event) }
                if (!related) return@collect
                when (event) {
                    is RepoUpdateEvent.StarChanged -> {
                        repoRepository.invalidateAllCaches()
                        _state.update { state ->
                            state.copy(repos = state.filters.sort.sorted(state.repos.map {
                                if (it.matches(event)) it.copy(stargazerCount = event.stargazerCount) else it
                            }))
                        }
                        if (current.filters.sort in listOf(RepoSort.STARS_ASC, RepoSort.STARS_DESC) || current.isBusy) {
                            loadFirstPage(preserveItems = true, forceRefresh = true)
                        }
                    }
                    is RepoUpdateEvent.IssueCountChanged -> _state.update { state ->
                        state.copy(repos = state.repos.map {
                            if (it.matches(event)) it.copy(openIssueCount = event.openIssueCount) else it
                        })
                    }
                    is RepoUpdateEvent.RepositoryCreated,
                    is RepoUpdateEvent.RepositoryChanged,
                    is RepoUpdateEvent.RepositoryDeleted,
                    is RepoUpdateEvent.RepositoryAddressChanged,
                    is RepoUpdateEvent.CodeChanged,
                    is RepoUpdateEvent.BranchSynced -> {
                        repoRepository.invalidateAllCaches()
                        loadFirstPage(preserveItems = true, forceRefresh = true)
                    }
                    else -> Unit
                }
            }
        }
    }
}

private fun RepoListItem.matches(event: RepoUpdateEvent): Boolean =
    ownerLogin.equals(event.owner, ignoreCase = true) && name.equals(event.name, ignoreCase = true)
