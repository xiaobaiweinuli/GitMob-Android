package com.gitmob.app.ui.repoforks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.RepoForkSort
import com.gitmob.app.data.model.RepoForkSortDirection
import com.gitmob.app.data.model.RepoForkSortField
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.ui.common.AccountViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoForksUiState(
    val repos: List<RepoListItem> = emptyList(),
    val sort: RepoForkSort = RepoForkSort(),
    val totalCount: Int? = null,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val hasNextPage: Boolean = false,
    val endCursor: String? = null,
)

@HiltViewModel
class RepoForksViewModel @Inject constructor(
    private val repository: RepoDetailRepository,
    private val errorEventBus: ErrorEventBus,
    session: AccountSession,
    private val savedStateHandle: SavedStateHandle,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(
        RepoForksUiState(
            sort = RepoForkSort(
                field = RepoForkSortField.entries.firstOrNull { it.name == savedStateHandle.get<String>(SORT_FIELD) }
                    ?: RepoForkSortField.CREATED,
                direction = RepoForkSortDirection.entries.firstOrNull { it.name == savedStateHandle.get<String>(SORT_DIRECTION) }
                    ?: RepoForkSortDirection.DESC,
            ),
        ),
    )
    val state: StateFlow<RepoForksUiState> = _state.asStateFlow()

    private var owner = ""
    private var name = ""
    private var initialized = false
    private var requestJob: Job? = null

    fun init(owner: String, name: String) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        load()
    }

    fun selectSort(sort: RepoForkSort) {
        if (sort == _state.value.sort) return
        savedStateHandle[SORT_FIELD] = sort.field.name
        savedStateHandle[SORT_DIRECTION] = sort.direction.name
        _state.update { it.copy(sort = sort) }
        load()
    }

    fun load() {
        if (!initialized) return
        requestJob?.cancel()
        _state.update {
            it.copy(
                repos = emptyList(), isLoading = true, isLoadingMore = false,
                loadFailed = false, hasNextPage = false, endCursor = null,
            )
        }
        request(after = null)
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore || !current.hasNextPage) return
        val cursor = current.endCursor ?: return
        _state.update { it.copy(isLoadingMore = true, loadFailed = false) }
        request(after = cursor)
    }

    private fun request(after: String?) {
        val sort = _state.value.sort
        requestJob = viewModelScope.launch {
            val result = repository.getForks(owner, name, after, sort)
            // A cancelled page must not publish into a newly selected ordering.
            ensureActive()
            when (result) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            repos = ((if (after == null) emptyList() else it.repos) + result.data.items)
                                .distinctBy(RepoListItem::key),
                            totalCount = result.data.totalCount,
                            isLoading = false,
                            isLoadingMore = false,
                            hasNextPage = result.data.hasNextPage,
                            endCursor = result.data.endCursor,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(isLoading = false, isLoadingMore = false, loadFailed = true) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }

    private companion object {
        const val SORT_FIELD = "forkSortField"
        const val SORT_DIRECTION = "forkSortDirection"
    }
}
