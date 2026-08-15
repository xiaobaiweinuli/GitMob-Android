package com.gitmob.app.ui.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.PagedWorkIssues
import com.gitmob.app.data.model.UserPullRequestFilter
import com.gitmob.app.data.model.UserPullRequestRelationFilter
import com.gitmob.app.data.model.UserPullRequestSortFilter
import com.gitmob.app.data.model.UserPullRequestStateFilter
import com.gitmob.app.data.model.UserPullRequestVisibilityFilter
import com.gitmob.app.data.model.WorkIssueItem
import com.gitmob.app.data.repository.WorkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkPullRequestListUiState(
    val items: List<WorkIssueItem> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val hasNextPage: Boolean = false,
    val filter: UserPullRequestFilter = UserPullRequestFilter(),
)

@HiltViewModel
class WorkPullRequestListViewModel @Inject constructor(
    private val workRepository: WorkRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkPullRequestListUiState())
    val state: StateFlow<WorkPullRequestListUiState> = _state.asStateFlow()

    private var endCursor: String? = null
    private var loadedOnce = false
    private var loadJob: Job? = null

    fun loadIfNeeded() {
        if (loadedOnce) return
        loadedOnce = true
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            applyFirstPage(workRepository.getUserPullRequests(_state.value.filter, after = null))
        }
    }

    fun setStateFilter(value: UserPullRequestStateFilter) = updateFilter(_state.value.filter.copy(state = value))

    fun setRelationFilter(value: UserPullRequestRelationFilter) = updateFilter(_state.value.filter.copy(relation = value))

    fun setVisibilityFilter(value: UserPullRequestVisibilityFilter) = updateFilter(_state.value.filter.copy(visibility = value))

    fun setSortFilter(value: UserPullRequestSortFilter) = updateFilter(_state.value.filter.copy(sort = value))

    private fun updateFilter(filter: UserPullRequestFilter) {
        if (filter == _state.value.filter) return
        endCursor = null
        _state.update {
            it.copy(
                filter = filter,
                items = emptyList(),
                totalCount = 0,
                hasNextPage = false,
                loadFailed = false,
            )
        }
        load()
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = workRepository.getUserPullRequests(current.filter, after = endCursor)) {
                is ApiResult.Success -> {
                    endCursor = result.data.endCursor
                    _state.update {
                        it.copy(
                            items = it.items + result.data.items,
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

    fun retry() = load()

    private fun applyFirstPage(result: ApiResult<PagedWorkIssues>) {
        when (result) {
            is ApiResult.Success -> {
                endCursor = result.data.endCursor
                _state.update {
                    it.copy(
                        items = result.data.items,
                        totalCount = result.data.totalCount,
                        hasNextPage = result.data.hasNextPage,
                        isLoading = false,
                        loadFailed = false,
                    )
                }
            }
            is ApiResult.Failure -> {
                viewModelScope.launch { errorEventBus.emit(result.error) }
                _state.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }
}
