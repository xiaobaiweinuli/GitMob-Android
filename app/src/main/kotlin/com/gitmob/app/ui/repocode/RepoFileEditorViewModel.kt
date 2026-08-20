package com.gitmob.app.ui.repocode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoPendingFileChange
import com.gitmob.app.data.repository.RepoGitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Base64
import javax.inject.Inject

data class RepoFileEditorUiState(
    val originalPath: String? = null,
    val path: String = "",
    val content: String = "",
    val headOid: String? = null,
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val isArchived: Boolean = false,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
)

@HiltViewModel
class RepoFileEditorViewModel @Inject constructor(
    private val repository: RepoGitRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoFileEditorUiState())
    val state: StateFlow<RepoFileEditorUiState> = _state.asStateFlow()
    private var initialized = false
    private var owner = ""
    private var name = ""
    private var ref = ""

    fun init(owner: String, name: String, ref: String, path: String?) {
        if (initialized) return
        initialized = true; this.owner = owner; this.name = name; this.ref = ref
        _state.update { it.copy(originalPath = path, path = path.orEmpty(), isLoading = true) }
        viewModelScope.launch {
            when (val tree = repository.getCodeTree(owner, name, ref, path?.substringBeforeLast('/', "").orEmpty())) {
                is ApiResult.Success -> {
                    _state.update { it.copy(permission = tree.data.permission, capabilities = tree.data.capabilities, isArchived = tree.data.isArchived, headOid = tree.data.headOid, isLoading = false) }
                    if (!path.isNullOrBlank()) when (val file = repository.getFileContent(owner, name, ref, path)) {
                        is ApiResult.Success -> _state.update { it.copy(content = file.data.text.orEmpty()) }
                        is ApiResult.Failure -> { errorEventBus.emit(file.error); _state.update { it.copy(loadFailed = true) } }
                    }
                }
                is ApiResult.Failure -> { errorEventBus.emit(tree.error); _state.update { it.copy(isLoading = false, loadFailed = true) } }
            }
        }
    }

    fun updatePath(value: String) = _state.update { it.copy(path = value) }
    fun updateContent(value: String) = _state.update { it.copy(content = value) }

    fun save(message: String, onSaved: () -> Unit) {
        val current = _state.value
        if (current.isSaving || current.isArchived || !current.capabilities.canPush || !current.capabilities.canPushToProtectedBranch || current.path.isBlank() || message.isBlank() || current.headOid == null) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val addition = RepoPendingFileChange.Addition(current.path, Base64.getEncoder().encodeToString(current.content.toByteArray(Charsets.UTF_8)))
            val deletions = current.originalPath?.takeIf { it != current.path }?.let { listOf(RepoPendingFileChange.Deletion(it)) }.orEmpty()
            when (val result = repository.createCommit(owner, name, ref, message, listOf(addition), deletions, current.headOid)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isSaving = false, headOid = result.data.oid, originalPath = current.path) }
                    repoUpdateEventBus.emit(RepoUpdateEvent.CodeChanged(owner, name, ref, result.data.oid, listOfNotNull(current.originalPath, current.path).distinct()))
                    onSaved()
                }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isSaving = false) } }
            }
        }
    }

    fun retry() {
        initialized = false
        init(owner, name, ref, _state.value.originalPath)
    }
}
