package com.gitmob.app.ui.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.PagedWorkIssues
import com.gitmob.app.data.model.WorkIssueItem
import com.gitmob.app.data.repository.WorkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class WorkListMode { ISSUES, PULL_REQUESTS }

data class WorkIssueListUiState(
    val items: List<WorkIssueItem> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val hasNextPage: Boolean = false,
)

/** 议题/拉取请求共用——involves:@me 聚合视图，两者数据形状完全一样，只是调用的 Repository 方法不同 */
@HiltViewModel
class WorkIssueListViewModel @Inject constructor(
    private val workRepository: WorkRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkIssueListUiState())
    val state: StateFlow<WorkIssueListUiState> = _state.asStateFlow()

    private var mode: WorkListMode = WorkListMode.ISSUES
    private var endCursor: String? = null
    private var initialized = false

    fun init(mode: WorkListMode) {
        if (initialized) return
        initialized = true
        this.mode = mode
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            applyFirstPage(fetch(after = null))
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = fetch(after = endCursor)) {
                is ApiResult.Success -> {
                    endCursor = result.data.endCursor
                    _state.update {
                        it.copy(items = it.items + result.data.items, hasNextPage = result.data.hasNextPage, isLoadingMore = false)
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

    private suspend fun fetch(after: String?): ApiResult<PagedWorkIssues> = when (mode) {
        WorkListMode.ISSUES -> workRepository.getInvolvedIssues(after)
        WorkListMode.PULL_REQUESTS -> workRepository.getInvolvedPullRequests(after)
    }

    private fun applyFirstPage(result: ApiResult<PagedWorkIssues>) {
        when (result) {
            is ApiResult.Success -> {
                endCursor = result.data.endCursor
                _state.update {
                    it.copy(
                        items = result.data.items, totalCount = result.data.totalCount,
                        hasNextPage = result.data.hasNextPage, isLoading = false, loadFailed = false,
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
