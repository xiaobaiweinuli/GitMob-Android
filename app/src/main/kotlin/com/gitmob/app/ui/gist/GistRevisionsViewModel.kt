package com.gitmob.app.ui.gist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.GistRevision
import com.gitmob.app.data.repository.GistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GistRevisionsUiState(
    val items: List<GistRevision> = emptyList(),
    val nextPage: Int? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
)

@HiltViewModel
class GistRevisionsViewModel @Inject constructor(
    private val repository: GistRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(GistRevisionsUiState())
    val state: StateFlow<GistRevisionsUiState> = _state.asStateFlow()
    private var gistId = ""

    fun init(id: String) {
        if (gistId == id) return
        gistId = id
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = repository.getRevisions(gistId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(items = result.data.items, nextPage = result.data.nextPage, isLoading = false)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoading = false, loadFailed = true) }
                }
            }
        }
    }

    fun loadMore() {
        val page = _state.value.nextPage ?: return
        if (_state.value.isLoadingMore) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = repository.getRevisions(gistId, page)) {
                is ApiResult.Success -> _state.update {
                    it.copy(items = it.items + result.data.items, nextPage = result.data.nextPage, isLoadingMore = false)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }
}
