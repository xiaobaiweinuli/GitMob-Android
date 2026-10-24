package com.gitmob.app.ui.gist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.core.share.ShareLinkLauncher
import com.gitmob.app.core.event.GistUpdateEventBus
import com.gitmob.app.data.model.ConversationEdit
import com.gitmob.app.data.model.GistComment
import com.gitmob.app.data.model.GistDetail
import com.gitmob.app.data.model.GistFile
import com.gitmob.app.data.repository.ConversationEditRepository
import com.gitmob.app.data.repository.GistRepository
import com.gitmob.app.ui.common.ConversationEditHistoryUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GistDetailUiState(
    val detail: GistDetail? = null,
    val comments: List<GistComment> = emptyList(),
    val nextCommentPage: Int? = null,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isLoadingMoreComments: Boolean = false,
    val isMutating: Boolean = false,
    val oldestVersion: String? = null,
    val editHistory: ConversationEditHistoryUiState = ConversationEditHistoryUiState(),
)

@HiltViewModel
class GistDetailViewModel @Inject constructor(
    private val repository: GistRepository,
    private val editRepository: ConversationEditRepository,
    private val markdownRenderer: MarkdownRenderer,
    private val errorEventBus: ErrorEventBus,
    private val shareLinkLauncher: ShareLinkLauncher,
    private val downloadLauncher: ExternalDownloadLauncher,
    private val gistUpdateEventBus: GistUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(GistDetailUiState())
    val state: StateFlow<GistDetailUiState> = _state.asStateFlow()
    private var gistId = ""
    private var viewerLogin = ""
    private var initializedId: String? = null
    private var editJob: Job? = null

    fun init(id: String) {
        if (initializedId == id) return
        initializedId = id
        gistId = id
        viewModelScope.launch {
            gistUpdateEventBus.events.filter { it == id }.collect { load() }
        }
        load()
        loadOldestVersion()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val detail = repository.getGist(gistId)) {
                is ApiResult.Failure -> {
                    errorEventBus.emit(detail.error)
                    _state.update { it.copy(isLoading = false, loadFailed = true) }
                }
                is ApiResult.Success -> {
                    viewerLogin = detail.data.viewerLogin
                    when (val comments = repository.getComments(gistId)) {
                    is ApiResult.Failure -> {
                        errorEventBus.emit(comments.error)
                        _state.update {
                            it.copy(detail = detail.data, isLoading = false, loadFailed = false)
                        }
                    }
                    is ApiResult.Success -> _state.update {
                        it.copy(
                            detail = detail.data,
                            comments = comments.data.items.map(::render),
                            nextCommentPage = comments.data.nextPage,
                            isLoading = false,
                            loadFailed = false,
                        )
                    }
                }
                }
            }
        }
    }

    fun retry() = load()

    fun loadMoreComments() {
        val page = _state.value.nextCommentPage ?: return
        if (_state.value.isLoadingMoreComments) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMoreComments = true) }
            when (val result = repository.getComments(gistId, page)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        comments = it.comments + result.data.items.map(::render),
                        nextCommentPage = result.data.nextPage,
                        isLoadingMoreComments = false,
                    )
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingMoreComments = false) }
                }
            }
        }
    }

    fun toggleStar() {
        val detail = _state.value.detail ?: return
        mutate(R.string.gist_star_updated) {
            repository.setStarred(gistId, !detail.isStarred).map { starred ->
                _state.update {
                    it.copy(
                        detail = it.detail?.copy(
                            isStarred = starred,
                            stargazerCount = (it.detail.stargazerCount + if (starred) 1 else -1).coerceAtLeast(0),
                        ),
                    )
                }
            }
        }
    }

    fun fork(onForked: (GistDetail) -> Unit) {
        mutate(R.string.gist_fork_created) {
            repository.fork(gistId).map(onForked)
        }
    }

    fun updateDescription(description: String, onDone: () -> Unit) {
        val detail = _state.value.detail ?: return
        if (!detail.isOwnedByViewer) return
        mutate(R.string.gist_saved) {
            repository.updateDescription(gistId, description).map {
                _state.update { it.copy(detail = it.detail?.copy(description = description.takeIf(String::isNotBlank))) }
                onDone()
            }
        }
    }

    fun deleteFile(file: GistFile, onDone: () -> Unit) {
        val detail = _state.value.detail ?: return
        if (!detail.isOwnedByViewer || detail.files.size <= 1) return
        mutate(R.string.gist_file_deleted) {
            repository.deleteFile(gistId, file.filename).map {
                load()
                onDone()
            }
        }
    }

    fun deleteGist(onDeleted: () -> Unit) {
        if (_state.value.detail?.isOwnedByViewer != true) return
        mutate(R.string.gist_deleted) { repository.deleteGist(gistId).map { onDeleted() } }
    }

    fun createComment(body: String, onDone: () -> Unit) {
        val detail = _state.value.detail ?: return
        if (!detail.commentsEnabled || body.isBlank()) return
        mutate(R.string.gist_comment_saved) {
            repository.createComment(gistId, body.trim()).map { comment ->
                _state.update {
                    it.copy(
                        comments = it.comments + render(comment),
                        detail = it.detail?.copy(commentCount = it.detail.commentCount + 1),
                    )
                }
                onDone()
            }
        }
    }

    fun updateComment(comment: GistComment, body: String, onDone: () -> Unit) {
        if (!comment.isBy(viewerLogin) || body.isBlank()) return
        mutate(R.string.gist_comment_saved) {
            repository.updateComment(gistId, comment.id, body.trim()).map { updated ->
                _state.update { state ->
                    state.copy(comments = state.comments.map { if (it.id == comment.id) render(updated) else it })
                }
                onDone()
            }
        }
    }

    fun deleteComment(comment: GistComment, onDone: () -> Unit) {
        if (!comment.isBy(viewerLogin)) return
        mutate(R.string.gist_comment_deleted) {
            repository.deleteComment(gistId, comment.id).map {
                _state.update { state ->
                    state.copy(
                        comments = state.comments.filterNot { it.id == comment.id },
                        detail = state.detail?.copy(commentCount = (state.detail.commentCount - 1).coerceAtLeast(0)),
                    )
                }
                onDone()
            }
        }
    }

    fun share() = _state.value.detail?.url?.takeIf(String::isNotBlank)?.let(shareLinkLauncher::share)

    fun shareFile(file: GistFile) = file.rawUrl.takeIf(String::isNotBlank)?.let(shareLinkLauncher::share)

    fun downloadZip() {
        val detail = _state.value.detail ?: return
        val owner = detail.owner?.login ?: return
        val version = _state.value.oldestVersion ?: return
        openDownload("https://gist.github.com/$owner/${detail.id}/archive/$version.zip")
    }

    fun downloadFile(file: GistFile) = file.rawUrl.takeIf(String::isNotBlank)?.let(::openDownload)

    fun openEditHistory(nodeId: String) {
        if (nodeId.isBlank()) return
        editJob?.cancel()
        editJob = viewModelScope.launch {
            _state.update {
                it.copy(editHistory = ConversationEditHistoryUiState(targetNodeId = nodeId, isOpen = true, isLoading = true))
            }
            when (val result = editRepository.getEdits(nodeId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(editHistory = it.editHistory.copy(items = result.data.items, hasNextPage = result.data.hasNextPage, endCursor = result.data.endCursor, isLoading = false))
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(editHistory = it.editHistory.copy(isLoading = false, loadFailed = true)) }
                }
            }
        }
    }

    fun loadMoreEditHistory() {
        val history = _state.value.editHistory
        val nodeId = history.targetNodeId ?: return
        if (history.isLoadingMore || !history.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(editHistory = it.editHistory.copy(isLoadingMore = true)) }
            when (val result = editRepository.getEdits(nodeId, history.endCursor)) {
                is ApiResult.Success -> _state.update {
                    it.copy(editHistory = it.editHistory.copy(items = it.editHistory.items + result.data.items, hasNextPage = result.data.hasNextPage, endCursor = result.data.endCursor, isLoadingMore = false))
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(editHistory = it.editHistory.copy(isLoadingMore = false)) }
                }
            }
        }
    }

    fun selectEdit(edit: ConversationEdit) = _state.update { it.copy(editHistory = it.editHistory.copy(selectedEdit = edit)) }
    fun clearSelectedEdit() = _state.update { it.copy(editHistory = it.editHistory.copy(selectedEdit = null)) }
    fun closeEditHistory() = _state.update { it.copy(editHistory = it.editHistory.copy(isOpen = false, selectedEdit = null)) }
    fun retryEditHistory() = _state.value.editHistory.targetNodeId?.let(::openEditHistory)

    private fun loadOldestVersion() {
        viewModelScope.launch {
            when (val result = repository.getOldestVersion(gistId)) {
                is ApiResult.Success -> _state.update { it.copy(oldestVersion = result.data) }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    private fun mutate(notice: Int, action: suspend () -> ApiResult<*>) {
        if (_state.value.isMutating) return
        viewModelScope.launch {
            _state.update { it.copy(isMutating = true) }
            when (val result = action()) {
                is ApiResult.Success -> errorEventBus.emitNotice(notice)
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
            _state.update { it.copy(isMutating = false) }
        }
    }

    private fun render(comment: GistComment): GistComment = if (comment.bodyHtml.isBlank()) {
        comment.copy(bodyHtml = markdownRenderer.renderToHtml(comment.body))
    } else {
        comment
    }

    private fun GistComment.isBy(login: String): Boolean = author?.login?.equals(login, ignoreCase = true) == true

    private fun openDownload(url: String) {
        try {
            downloadLauncher.open(url)
        } catch (error: UserVisibleException) {
            viewModelScope.launch { errorEventBus.emit(ApiError.UserVisible(error.messageRes)) }
        }
    }
}
