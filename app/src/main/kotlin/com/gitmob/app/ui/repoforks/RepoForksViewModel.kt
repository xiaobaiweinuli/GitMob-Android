package com.gitmob.app.ui.repoforks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.repository.RepoDetailRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoForksUiState(
    val repos: List<RepoListItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val hasNextPage: Boolean = false,
)

@HiltViewModel
class RepoForksViewModel @Inject constructor(
    private val repository: RepoDetailRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoForksUiState())
    val state: StateFlow<RepoForksUiState> = _state.asStateFlow()

    private var owner = ""
    private var name = ""
    private var endCursor: String? = null
    private var initialized = false

    fun init(owner: String, name: String) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = repository.getForks(owner, name)) {
                is ApiResult.Success -> {
                    endCursor = result.data.endCursor
                    _state.update {
                        it.copy(
                            repos = result.data.items,
                            isLoading = false,
                            hasNextPage = result.data.hasNextPage,
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

    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = repository.getForks(owner, name, endCursor)) {
                is ApiResult.Success -> {
                    endCursor = result.data.endCursor
                    _state.update {
                        it.copy(
                            repos = it.repos + result.data.items,
                            isLoadingMore = false,
                            hasNextPage = result.data.hasNextPage,
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
