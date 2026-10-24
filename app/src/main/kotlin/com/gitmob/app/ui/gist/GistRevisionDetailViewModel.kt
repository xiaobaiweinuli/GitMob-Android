package com.gitmob.app.ui.gist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.GistRevisionComparison
import com.gitmob.app.data.model.GistRevision
import com.gitmob.app.data.repository.GistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GistRevisionDetailUiState(
    val comparison: GistRevisionComparison? = null,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
)

@HiltViewModel
class GistRevisionDetailViewModel @Inject constructor(
    private val repository: GistRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(GistRevisionDetailUiState())
    val state: StateFlow<GistRevisionDetailUiState> = _state.asStateFlow()
    private var gistId = ""
    private var version = ""
    private var previousVersion: String? = null
    private var revision: GistRevision? = null

    fun init(
        id: String,
        version: String,
        previousVersion: String?,
        committedAt: String,
        additions: Int,
        deletions: Int,
        totalChanges: Int,
    ) {
        if (gistId == id && this.version == version && this.previousVersion == previousVersion) return
        gistId = id
        this.version = version
        this.previousVersion = previousVersion
        revision = GistRevision(version, null, committedAt, additions, deletions, totalChanges)
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            val selectedRevision = revision ?: return@launch
            when (val result = repository.compareRevisions(gistId, selectedRevision, previousVersion)) {
                is ApiResult.Success -> _state.update {
                    it.copy(comparison = result.data, isLoading = false)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoading = false, loadFailed = true) }
                }
            }
        }
    }
}
