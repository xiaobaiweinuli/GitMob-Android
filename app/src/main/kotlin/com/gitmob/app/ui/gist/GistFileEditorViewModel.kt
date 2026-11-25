package com.gitmob.app.ui.gist

import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.ui.common.AccountViewModel
import com.gitmob.app.ui.common.SoraEditorState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class GistFileEditorUiState(
    val originalPath: String? = null,
    val path: String = "",
    val editorState: SoraEditorState = SoraEditorState(),
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
) {
    val content: String get() = editorState.snapshot()
}

@HiltViewModel
class GistFileEditorViewModel @Inject constructor(
    private val repository: GistRepository,
    private val errorEventBus: ErrorEventBus,
    private val gistUpdateEventBus: GistUpdateEventBus,
    session: AccountSession,
) : AccountViewModel(session) {
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
                        val document = withContext(Dispatchers.Default) { SoraEditorState(file.content.orEmpty()) }
                        _state.update {
                            it.copy(
                                originalPath = file.filename,
                                path = file.filename,
                                editorState = document,
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

    fun updatePath(value: String) {
        if (!_state.value.isLoading && !_state.value.loadFailed && !_state.value.isSaving) _state.update { it.copy(path = value) }
    }
    fun updateContent(value: String) {
        if (!_state.value.isLoading && !_state.value.loadFailed && !_state.value.isSaving) _state.value.editorState.setTextAndPlaceCursorAtEnd(value)
    }

    fun save(gistId: String, onSaved: () -> Unit) {
        val current = _state.value
        val path = current.path.trim()
        if (path.isBlank() || current.isLoading || current.loadFailed || current.isSaving) return
        val content = current.editorState.snapshot()
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = current.originalPath?.let { originalPath ->
                repository.updateFile(gistId, originalPath, path, content)
            } ?: repository.addFile(gistId, path, content)
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
