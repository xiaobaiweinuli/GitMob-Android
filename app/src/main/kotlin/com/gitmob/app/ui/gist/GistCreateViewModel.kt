package com.gitmob.app.ui.gist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.GistUpdateEventBus
import com.gitmob.app.data.repository.GistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class GistFilenameError { REQUIRED, DUPLICATE }

data class GistCreateFileUiState(
    val id: Long,
    val filename: String = "",
    val content: String = "",
)

data class GistCreateUiState(
    val description: String = "",
    val isPublic: Boolean = false,
    val files: List<GistCreateFileUiState> = listOf(GistCreateFileUiState(id = 0)),
    val isCreating: Boolean = false,
) {
    val canCreate: Boolean
        get() {
            val names = files.map { it.filename.trim() }
            return !isCreating && names.isNotEmpty() && names.all(String::isNotEmpty) && names.distinct().size == names.size
        }

    fun filenameError(file: GistCreateFileUiState): GistFilenameError? {
        val name = file.filename.trim()
        return when {
            name.isEmpty() -> GistFilenameError.REQUIRED
            files.count { it.filename.trim() == name } > 1 -> GistFilenameError.DUPLICATE
            else -> null
        }
    }
}

@HiltViewModel
class GistCreateViewModel @Inject constructor(
    private val repository: GistRepository,
    private val errorEventBus: ErrorEventBus,
    private val gistUpdateEventBus: GistUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(GistCreateUiState())
    val state: StateFlow<GistCreateUiState> = _state.asStateFlow()
    private val _createdEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val createdEvents: SharedFlow<String> = _createdEvents.asSharedFlow()
    private var nextFileId = 1L

    fun updateDescription(description: String) = _state.update { it.copy(description = description) }

    fun updatePublic(isPublic: Boolean) = _state.update { it.copy(isPublic = isPublic) }

    fun addFile() = _state.update {
        it.copy(files = it.files + GistCreateFileUiState(id = nextFileId++))
    }

    fun removeFile(id: Long) = _state.update {
        if (it.files.size == 1) it else it.copy(files = it.files.filterNot { file -> file.id == id })
    }

    fun updateFilename(id: Long, filename: String) = updateFile(id) { it.copy(filename = filename) }

    fun updateContent(id: Long, content: String) = updateFile(id) { it.copy(content = content) }

    fun create() {
        val snapshot = _state.value
        if (!snapshot.canCreate) return
        _state.update { it.copy(isCreating = true) }
        viewModelScope.launch {
            val result = repository.createGist(
                description = snapshot.description.takeIf(String::isNotBlank),
                isPublic = snapshot.isPublic,
                files = snapshot.files.associate { it.filename.trim() to it.content },
            )
            _state.update { it.copy(isCreating = false) }
            when (result) {
                is ApiResult.Success -> {
                    gistUpdateEventBus.emitListChanged()
                    _createdEvents.emit(result.data)
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    private fun updateFile(
        id: Long,
        transform: (GistCreateFileUiState) -> GistCreateFileUiState,
    ) = _state.update { state ->
        state.copy(files = state.files.map { if (it.id == id) transform(it) else it })
    }
}
