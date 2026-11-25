package com.gitmob.app.ui.repocode

import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.ui.common.AccountViewModel
import com.gitmob.app.ui.common.SoraEditorState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.inject.Inject

data class RepoFileEditorUiState(
    val originalPath: String? = null,
    val path: String = "",
    val editorState: SoraEditorState = SoraEditorState(),
    val commitTitle: String = "",
    val commitBody: String = "",
    val isCommitSheetOpen: Boolean = false,
    val headOid: String? = null,
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val isArchived: Boolean = false,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
) {
    val content: String get() = editorState.snapshot()
    val canCommit: Boolean get() = !isLoading && !loadFailed && !isSaving && !isArchived &&
        capabilities.canPush && capabilities.canPushToProtectedBranch && path.isNotBlank() && headOid != null
}

@HiltViewModel
class RepoFileEditorViewModel @Inject constructor(
    private val repository: RepoGitRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    session: AccountSession,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(RepoFileEditorUiState())
    val state: StateFlow<RepoFileEditorUiState> = _state.asStateFlow()
    private var initialized = false
    private var draftInitialized = false
    private var commitTitleEdited = false
    private var owner = ""
    private var name = ""
    private var ref = ""

    fun init(owner: String, name: String, ref: String, path: String?) {
        if (initialized) return
        initialized = true; this.owner = owner; this.name = name; this.ref = ref
        _state.update { it.copy(originalPath = path, path = path.orEmpty(), isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            when (val tree = repository.getCodeTree(owner, name, ref, path?.substringBeforeLast('/', "").orEmpty())) {
                is ApiResult.Success -> {
                    _state.update { it.copy(permission = tree.data.permission, capabilities = tree.data.capabilities, isArchived = tree.data.isArchived, headOid = tree.data.headOid) }
                    if (!path.isNullOrBlank()) when (val file = repository.getFileContent(owner, name, ref, path)) {
                        is ApiResult.Success -> {
                            val document = withContext(Dispatchers.Default) { SoraEditorState(file.data.text.orEmpty()) }
                            _state.update { it.copy(editorState = document) }
                        }
                        is ApiResult.Failure -> { errorEventBus.emit(file.error); _state.update { it.copy(isLoading = false, loadFailed = true) }; return@launch }
                    }
                    draftInitialized = true
                    _state.update { it.copy(isLoading = false) }
                }
                is ApiResult.Failure -> { errorEventBus.emit(tree.error); _state.update { it.copy(isLoading = false, loadFailed = true) } }
            }
        }
    }

    fun updatePath(value: String) {
        if (editable()) _state.update { it.copy(path = value) }
    }
    fun updateContent(value: String) {
        if (editable()) _state.value.editorState.setTextAndPlaceCursorAtEnd(value)
    }
    fun openCommitSheet(defaultTitle: String) {
        if (!editable() || !_state.value.canCommit) return
        _state.update { it.copy(
            isCommitSheetOpen = true,
            commitTitle = if (commitTitleEdited) it.commitTitle else defaultTitle,
        ) }
    }

    fun dismissCommitSheet() {
        if (!_state.value.isSaving) _state.update { it.copy(isCommitSheetOpen = false) }
    }

    fun updateCommitTitle(value: String) {
        if (!editable()) return
        commitTitleEdited = true
        _state.update { it.copy(commitTitle = value.replace('\r', ' ').replace('\n', ' ')) }
    }

    fun updateCommitBody(value: String) {
        if (editable()) _state.update { it.copy(commitBody = value) }
    }

    private fun editable(): Boolean = draftInitialized && !_state.value.isLoading && !_state.value.loadFailed && !_state.value.isSaving

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        val title = current.commitTitle.trim()
        if (!editable() || !current.canCommit || !current.isCommitSheetOpen || title.isBlank()) return
        val headOid = current.headOid ?: return
        val content = current.editorState.snapshot()
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val encoded = withContext(Dispatchers.Default) { Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)) }
            val addition = RepoPendingFileChange.Addition(current.path, encoded)
            val deletions = current.originalPath?.takeIf { it != current.path }?.let { listOf(RepoPendingFileChange.Deletion(it)) }.orEmpty()
            when (val result = repository.createCommit(owner, name, ref, title, listOf(addition), deletions, headOid, body = current.commitBody)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isSaving = false, isCommitSheetOpen = false, headOid = result.data.oid, originalPath = current.path) }
                    repoUpdateEventBus.emit(RepoUpdateEvent.CodeChanged(owner, name, ref, result.data.oid, listOfNotNull(current.originalPath, current.path).distinct()))
                    onSaved()
                }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isSaving = false) } }
            }
        }
    }

    fun retry() {
        if (!_state.value.loadFailed || _state.value.isLoading || draftInitialized) return
        initialized = false
        init(owner, name, ref, _state.value.originalPath)
    }
}
