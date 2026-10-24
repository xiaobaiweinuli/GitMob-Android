package com.gitmob.app.ui.gist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.GistUpdateEventBus
import com.gitmob.app.data.repository.GistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GistFileEditorUiState(
    val originalPath: String? = null,
    val path: String = "",
    val content: String = "",
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
)

@HiltViewModel
class GistFileEditorViewModel @Inject constructor(
    private val repository: GistRepository,
    private val errorEventBus: ErrorEventBus,
    private val gistUpdateEventBus: GistUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(GistFileEditorUiState())
    val state: StateFlow<GistFileEditorUiState> = _state.asStateFlow()
    private var initialized = false

    fun init(gistId: String, filename: String?) {
        if (initialized) return
        initialized = true
        val originalPath = filename?.takeIf(String::isNotBlank) ?: return
        _state.update { it.copy(isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            when (val result = repository.getGistFile(gistId, originalPath)) {
                is ApiResult.Success -> {
                    val file = result.data
                    if (file.isText) {
                        _state.update {
                            it.copy(
                                originalPath = file.filename,
                                path = file.filename,
                                content = file.content.orEmpty(),
                                isLoading = false,
                            )
                        }
                    } else {
                        errorEventBus.emit(ApiError.Unknown("Gist file is not editable"))
                        _state.update { it.copy(isLoading = false, loadFailed = true) }
                    }
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoading = false, loadFailed = true) }
                }
            }
        }
    }

    fun updatePath(value: String) = _state.update { it.copy(path = value) }
    fun updateContent(value: String) = _state.update { it.copy(content = value) }

    fun save(gistId: String, onSaved: () -> Unit) {
        val current = _state.value
        val path = current.path.trim()
        if (path.isBlank() || current.isLoading || current.loadFailed || current.isSaving) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val result = current.originalPath?.let { originalPath ->
                repository.updateFile(gistId, originalPath, path, current.content)
            } ?: repository.addFile(gistId, path, current.content)
            when (result) {
                is ApiResult.Success -> {
                    gistUpdateEventBus.emit(gistId)
                    errorEventBus.emitNotice(R.string.gist_saved)
                    onSaved()
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
            _state.update { it.copy(isSaving = false) }
        }
    }
}
