package com.gitmob.app.ui.repocode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.data.model.RepoFileContent
import com.gitmob.app.data.model.RepoPendingFileChange
import com.gitmob.app.data.repository.RepoGitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoFileDetailUiState(
    val file: RepoFileContent? = null,
    val previewHtml: String? = null,
    val previewSupported: Boolean = false,
    val previewMode: Boolean = false,
    val isDeleting: Boolean = false,
    val isDownloading: Boolean = false,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
)

@HiltViewModel
class RepoFileDetailViewModel @Inject constructor(
    private val repository: RepoGitRepository,
    private val markdownRenderer: MarkdownRenderer,
    private val errorEventBus: ErrorEventBus,
    private val downloadLauncher: ExternalDownloadLauncher,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoFileDetailUiState())
    val state: StateFlow<RepoFileDetailUiState> = _state.asStateFlow()
    private var initialized = false
    private var owner = ""
    private var name = ""
    private var ref = ""
    private var path = ""

    fun init(owner: String, name: String, ref: String, path: String) {
        if (initialized) return
        initialized = true; this.owner = owner; this.name = name; this.ref = ref; this.path = path
        observeCodeChanges()
        load()
    }

    private fun observeCodeChanges() {
        viewModelScope.launch {
            repoUpdateEventBus.events
                .filterIsInstance<RepoUpdateEvent.CodeChanged>()
                .collect { event ->
                    if (event.owner == owner && event.name == name && event.ref == ref && path in event.changedPaths) load()
                }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = repository.getFileContent(owner, name, ref, path)) {
                is ApiResult.Success -> {
                    val file = result.data
                    val extension = path.substringAfterLast('.', "").lowercase()
                    val supported = !file.isBinary && extension in setOf("md", "markdown", "html", "htm")
                    val html = when {
                        !supported -> null
                        extension == "html" || extension == "htm" -> file.text
                        else -> file.text?.let(markdownRenderer::renderToHtml)
                    }
                    _state.update { it.copy(file = file, previewHtml = html, previewSupported = supported, isLoading = false) }
                }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } }
            }
        }
    }

    fun retry() = load()
    fun setPreviewMode(value: Boolean) = _state.update { it.copy(previewMode = value) }

    fun deleteFile(message: String, onDeleted: () -> Unit) {
        val file = _state.value.file ?: return
        if (_state.value.isDeleting || file.isArchived || !file.capabilities.canPush || !file.capabilities.canPushToProtectedBranch || message.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isDeleting = true) }
            when (val result = repository.createCommit(owner, name, ref, message, emptyList(), listOf(RepoPendingFileChange.Deletion(path)), file.headOid)) {
                is ApiResult.Success -> { _state.update { it.copy(isDeleting = false) }; onDeleted() }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isDeleting = false) } }
            }
        }
    }

    fun downloadFile() {
        if (_state.value.isDownloading) return
        viewModelScope.launch {
            _state.update { it.copy(isDownloading = true) }
            try {
                when (val result = repository.resolveFileDownloadUrl(owner, name, ref, path)) {
                    is ApiResult.Success -> {
                        val url = result.data
                        if (url.isNullOrBlank()) errorEventBus.emit(ApiError.UserVisible(com.gitmob.app.R.string.download_address_unavailable))
                        else {
                            runCatching { downloadLauncher.open(url) }
                                .onSuccess { errorEventBus.emitNotice(com.gitmob.app.R.string.download_opened_externally) }
                                .onFailure { errorEventBus.emit(ApiError.UserVisible(com.gitmob.app.R.string.download_external_app_unavailable)) }
                        }
                    }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update { it.copy(isDownloading = false) }
            }
        }
    }
}
