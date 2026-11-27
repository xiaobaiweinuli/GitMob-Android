package com.gitmob.app.ui.common

import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.markdown.documentResourcePath
import com.gitmob.app.core.media.MediaPlayback
import com.gitmob.app.data.model.DocumentResourceKind
import com.gitmob.app.data.model.DocumentResource
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.repository.MAX_DOCUMENT_RESOURCE_BYTES
import com.gitmob.app.data.repository.RepoDocumentRepository
import com.gitmob.app.data.repository.RepoDocumentResource
import com.gitmob.app.data.repository.RepoGitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DocumentResourceState(
    val resource: RepoDocumentResource? = null,
    val playback: MediaPlayback.Session? = null,
    val unsupported: Boolean = false,
    val isLoading: Boolean = false,
    val error: ApiError? = null,
    val isDownloading: Boolean = false,
)

@HiltViewModel
class DocumentResourceViewModel @Inject constructor(
    private val repository: RepoDocumentRepository,
    private val gitRepository: RepoGitRepository,
    private val downloadLauncher: ExternalDownloadLauncher,
    private val errors: ErrorEventBus,
    session: AccountSession,
    private val mediaPlayback: MediaPlayback,
) : AccountViewModel(session) {
    private data class Request(val item: DocumentResource, val document: RepoDocumentContext?, val byteSize: Long?)
    private var request: Request? = null
    private var loadJob: Job? = null
    private var downloadJob: Job? = null
    private val _state = MutableStateFlow(DocumentResourceState())
    val state = _state.asStateFlow()

    fun show(item: DocumentResource, document: RepoDocumentContext?, byteSize: Long? = null) {
        val next = Request(item, document, byteSize)
        if (request == next) return
        clear()
        request = next
        load()
    }

    fun retry() { if (loadJob?.isActive != true) load(refresh = true) }

    private fun load(refresh: Boolean = false) {
        val current = request ?: return
        loadJob = viewModelScope.launch {
            _state.value.playback?.close()
            _state.update { DocumentResourceState(isLoading = true, isDownloading = it.isDownloading) }
            if (refresh) current.document?.let { document ->
                document.documentResourcePath(current.item.source)?.let { repository.invalidate(document, it) }
            }
            var imageSource = current.item.source
            if (current.item.kind != DocumentResourceKind.IMAGE) {
                when (val resolved = repository.resolveMedia(current.item, current.document)) {
                    is ApiResult.Failure -> { fail(resolved.error); return@launch }
                    is ApiResult.Success -> {
                        currentCoroutineContext().ensureActive()
                        accountEpoch.ensureCurrent()
                        when (resolved.data.kind) {
                            DocumentResourceKind.IMAGE -> imageSource = resolved.data.url
                            DocumentResourceKind.VIDEO, DocumentResourceKind.AUDIO -> {
                                when (val playback = safeCall { mediaPlayback.create(resolved.data, accountEpoch) }) {
                                    is ApiResult.Success -> _state.update { it.copy(playback = playback.data, isLoading = false) }
                                    is ApiResult.Failure -> fail(playback.error)
                                }
                                return@launch
                            }
                            DocumentResourceKind.UNKNOWN -> {
                                _state.update { it.copy(unsupported = true, isLoading = false) }
                                return@launch
                            }
                        }
                    }
                }
            }
            val result = if ((current.byteSize ?: 0) > MAX_DOCUMENT_RESOURCE_BYTES) {
                ApiResult.Failure(ApiError.UserVisible(R.string.image_preview_too_large))
            } else repository.loadImage(imageSource, current.document)
            currentCoroutineContext().ensureActive()
            accountEpoch.ensureCurrent()
            when (result) {
                is ApiResult.Success -> _state.update { it.copy(resource = result.data, isLoading = false) }
                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    private suspend fun fail(error: ApiError) {
        currentCoroutineContext().ensureActive()
        accountEpoch.ensureCurrent()
        _state.update { it.copy(error = error, isLoading = false) }
        errors.emit(error)
    }

    fun playbackFailed() {
        if (_state.value.error != null) return
        _state.value.playback?.close()
        val error = ApiError.UserVisible(R.string.media_preview_failed)
        _state.update { it.copy(playback = null, error = error, isLoading = false) }
        viewModelScope.launch { errors.emit(error) }
    }

    fun decodeFailed() {
        if (_state.value.error != null) return
        val error = ApiError.UserVisible(R.string.image_preview_failed)
        _state.update { it.copy(error = error, isLoading = false, resource = null) }
        viewModelScope.launch { errors.emit(error) }
    }

    fun download() {
        val current = request ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _state.update { it.copy(isDownloading = true) }
            try {
                val document = current.document
                val path = document?.documentResourcePath(current.item.source)
                val result = if (document != null && path != null) {
                    gitRepository.resolveFileDownloadUrl(document.owner, document.name, document.ref, path)
                } else ApiResult.Success(current.item.source.takeIf { it.startsWith("https://") || it.startsWith("http://") })
                currentCoroutineContext().ensureActive()
                accountEpoch.ensureCurrent()
                when (result) {
                    is ApiResult.Success -> {
                        val url = result.data
                        if (url.isNullOrBlank()) errors.emit(ApiError.UserVisible(R.string.download_address_unavailable))
                        else runCatching { downloadLauncher.open(url) }
                            .onSuccess { errors.emitNotice(R.string.download_opened_externally) }
                            .onFailure { errors.emit(ApiError.UserVisible(R.string.download_external_app_unavailable)) }
                    }
                    is ApiResult.Failure -> errors.emit(result.error)
                }
            } finally {
                if (request === current) _state.update { it.copy(isDownloading = false) }
            }
        }
    }

    override fun onCleared() { clear(); super.onCleared() }

    fun clear() {
        loadJob?.cancel()
        downloadJob?.cancel()
        request = null
        _state.value.playback?.close()
        _state.value = DocumentResourceState()
    }
}
