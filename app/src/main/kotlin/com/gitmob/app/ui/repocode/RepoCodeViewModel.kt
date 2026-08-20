package com.gitmob.app.ui.repocode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoCodeTree
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.core.storage.SafFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.Base64

data class RepoCodeUiState(
    val tree: RepoCodeTree? = null,
    val permission: RepoPermission = RepoPermission.NONE,
    val capabilities: RepoCapabilities = RepoCapabilities.NONE,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isUploading: Boolean = false,
    val isFolderExporting: Boolean = false,
    val folderExportCompleted: Int = 0,
    val folderExportTotal: Int = 0,
    val openingDownload: Boolean = false,
)

@HiltViewModel
class RepoCodeViewModel @Inject constructor(
    private val repository: RepoGitRepository,
    private val errorEventBus: ErrorEventBus,
    private val downloadLauncher: ExternalDownloadLauncher,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoCodeUiState())
    val state: StateFlow<RepoCodeUiState> = _state.asStateFlow()
    private var initialized = false
    private var owner = ""
    private var name = ""
    private var ref = ""
    private var path = ""
    private var folderExportJob: Job? = null

    fun init(owner: String, name: String, ref: String, path: String, permission: RepoPermission?) {
        if (initialized) return
        initialized = true; this.owner = owner; this.name = name; this.ref = ref; this.path = path
        permission?.let { _state.update { state -> state.copy(permission = it, capabilities = it.toCapabilities()) } }
        observeCodeChanges()
        load()
    }

    private fun observeCodeChanges() {
        viewModelScope.launch {
            repoUpdateEventBus.events
                .filterIsInstance<RepoUpdateEvent.CodeChanged>()
                .collect { event ->
                    if (event.owner == owner && event.name == name && event.ref == ref &&
                        (path.isBlank() || event.changedPaths.any { changed -> changed == path || changed.startsWith("$path/") })) {
                        load()
                    }
                }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = repository.getCodeTree(owner, name, ref, path)) {
                is ApiResult.Success -> _state.update { it.copy(tree = result.data, permission = result.data.permission, capabilities = result.data.capabilities, isLoading = false) }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoading = false, loadFailed = true) } }
            }
        }
    }

    fun retry() = load()
    fun refresh() = load()

    fun uploadFiles(files: List<SafFile>, message: String, onDone: () -> Unit = {}) {
        val tree = _state.value.tree ?: return
        if (_state.value.isUploading || tree.isArchived || !tree.capabilities.canPush || !tree.capabilities.canPushToProtectedBranch || tree.headOid.isBlank() || message.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isUploading = true) }
            val prefix = tree.path.trim('/').takeIf { it.isNotBlank() }?.plus('/') ?: ""
            val additions = files.map { file ->
                com.gitmob.app.data.model.RepoPendingFileChange.Addition(prefix + file.relativePath, Base64.getEncoder().encodeToString(file.bytes))
            }
            when (val result = repository.createCommit(owner, name, tree.ref, message, additions, emptyList(), tree.headOid)) {
                is ApiResult.Success -> { _state.update { it.copy(isUploading = false) }; load(); onDone() }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isUploading = false) } }
            }
        }
    }

    fun exportCurrentFolder(writeZip: suspend (ByteArray) -> Unit) {
        if (folderExportJob?.isActive == true || _state.value.isFolderExporting) return
        folderExportJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isFolderExporting = true,
                    folderExportCompleted = 0,
                    folderExportTotal = 0,
                )
            }
            try {
                when (
                    val result = repository.createFolderZip(owner, name, ref, path) { completed, total ->
                        _state.update {
                            it.copy(
                                folderExportCompleted = completed,
                                folderExportTotal = total,
                            )
                        }
                    }
                ) {
                    is ApiResult.Success -> {
                        try {
                            writeZip(result.data)
                            errorEventBus.emitNotice(R.string.repo_folder_download_complete)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            errorEventBus.emit(ApiError.UserVisible(R.string.repo_folder_save_failed))
                        }
                    }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update {
                    it.copy(
                        isFolderExporting = false,
                        folderExportCompleted = 0,
                        folderExportTotal = 0,
                    )
                }
                folderExportJob = null
            }
        }
    }

    fun cancelFolderExport() {
        folderExportJob?.cancel()
        folderExportJob = null
        _state.update {
            it.copy(
                isFolderExporting = false,
                folderExportCompleted = 0,
                folderExportTotal = 0,
            )
        }
    }

    fun downloadArchive() {
        if (_state.value.openingDownload) return
        viewModelScope.launch {
            _state.update { it.copy(openingDownload = true) }
            try {
                when (val result = repository.resolveArchiveDownloadUrl(owner, name, ref)) {
                    is ApiResult.Success -> {
                        val url = result.data
                        if (url.isNullOrBlank()) errorEventBus.emit(ApiError.UserVisible(R.string.download_address_unavailable))
                        else {
                            runCatching { downloadLauncher.open(url) }
                                .onSuccess { errorEventBus.emitNotice(R.string.download_opened_externally) }
                                .onFailure { errorEventBus.emit(ApiError.UserVisible(R.string.download_external_app_unavailable)) }
                        }
                    }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update { it.copy(openingDownload = false) }
            }
        }
    }
}
