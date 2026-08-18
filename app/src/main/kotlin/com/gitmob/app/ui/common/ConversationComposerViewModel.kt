package com.gitmob.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.data.model.RepoPullRequestReviewEvent
import com.gitmob.app.data.repository.RepoDiscussionRepository
import com.gitmob.app.data.repository.RepoIssueRepository
import com.gitmob.app.data.repository.RepoPullRequestRepository
import com.gitmob.app.navigation.ConversationComposerTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ComposerTab { EDIT, PREVIEW }

data class ConversationComposerUiState(
    val text: String = "",
    val selectedTab: ComposerTab = ComposerTab.EDIT,
    val previewHtml: String = "",
    val isRenderingPreview: Boolean = false,
    val previewFailed: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitted: Boolean = false,
    val allowsEmptySubmission: Boolean = false,
)

@HiltViewModel
class ConversationComposerViewModel @Inject constructor(
    private val issueRepository: RepoIssueRepository,
    private val pullRequestRepository: RepoPullRequestRepository,
    private val discussionRepository: RepoDiscussionRepository,
    private val markdownRenderer: MarkdownRenderer,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationComposerUiState())
    val state: StateFlow<ConversationComposerUiState> = _state.asStateFlow()
    private var previewJob: Job? = null
    private var initialized = false
    private lateinit var owner: String
    private lateinit var name: String
    private var number: Int = 0
    private lateinit var target: ConversationComposerTarget
    private lateinit var subjectId: String
    private var commentId: String? = null
    private var replyToId: String? = null
    private var reviewEvent: RepoPullRequestReviewEvent? = null

    fun init(
        owner: String,
        name: String,
        number: Int,
        target: ConversationComposerTarget,
        subjectId: String,
        initialText: String,
        commentId: String?,
        replyToId: String?,
        reviewEvent: String?,
    ) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        this.number = number
        this.target = target
        this.subjectId = subjectId
        this.commentId = commentId
        this.replyToId = replyToId
        this.reviewEvent = reviewEvent?.let { runCatching { RepoPullRequestReviewEvent.valueOf(it) }.getOrNull() }
        _state.update {
            it.copy(
                text = initialText,
                allowsEmptySubmission = target == ConversationComposerTarget.PULL_REQUEST_REVIEW,
            )
        }
    }

    fun updateText(value: String) {
        _state.update { it.copy(text = value, previewFailed = false) }
        if (_state.value.selectedTab == ComposerTab.PREVIEW) renderPreview()
    }

    fun selectTab(tab: ComposerTab) {
        _state.update { it.copy(selectedTab = tab) }
        if (tab == ComposerTab.PREVIEW) renderPreview()
    }

    fun renderPreview() {
        previewJob?.cancel()
        val markdown = _state.value.text
        if (markdown.isBlank()) {
            _state.update { it.copy(previewHtml = "", isRenderingPreview = false, previewFailed = false) }
            return
        }
        previewJob = viewModelScope.launch {
            delay(120)
            _state.update { it.copy(isRenderingPreview = true, previewFailed = false) }
            runCatching { markdownRenderer.renderToHtml(markdown) }
                .onSuccess { html -> _state.update { it.copy(previewHtml = html, isRenderingPreview = false) } }
                .onFailure { error ->
                    errorEventBus.emit(com.gitmob.app.core.error.ApiError.Unknown(error.message ?: "Markdown preview failed"))
                    _state.update { it.copy(isRenderingPreview = false, previewFailed = true) }
                }
        }
    }

    fun submit() {
        val body = _state.value.text.trim()
        if (body.isBlank() && !_state.value.allowsEmptySubmission || _state.value.isSubmitting) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true) }
            when (val result = submitBody(body)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isSubmitting = false, submitted = true) }
                    emitChanged()
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isSubmitting = false) }
                }
            }
        }
    }

    private suspend fun submitBody(body: String): ApiResult<Any> = when (target) {
        ConversationComposerTarget.ISSUE_COMMENT -> if (commentId == null) issueRepository.addComment(subjectId, body).mapAny() else issueRepository.updateComment(commentId!!, body).mapAny()
        ConversationComposerTarget.PULL_REQUEST_COMMENT -> if (commentId == null) pullRequestRepository.addComment(subjectId, body).mapAny() else pullRequestRepository.updateComment(commentId!!, body).mapAny()
        ConversationComposerTarget.PULL_REQUEST_REVIEW -> pullRequestRepository.submitReview(subjectId, reviewEvent ?: RepoPullRequestReviewEvent.COMMENT, body).mapAny()
        ConversationComposerTarget.PULL_REQUEST_THREAD -> pullRequestRepository.replyToThread(subjectId, body).mapAny()
        ConversationComposerTarget.DISCUSSION_COMMENT -> if (commentId == null) discussionRepository.addComment(subjectId, body, replyToId).mapAny() else discussionRepository.updateComment(commentId!!, body).mapAny()
    }

    private suspend fun emitChanged() {
        val event = when (target) {
            ConversationComposerTarget.ISSUE_COMMENT -> RepoUpdateEvent.IssueCommentsChanged(owner, name, number)
            ConversationComposerTarget.PULL_REQUEST_COMMENT, ConversationComposerTarget.PULL_REQUEST_REVIEW, ConversationComposerTarget.PULL_REQUEST_THREAD -> RepoUpdateEvent.PullRequestCommentsChanged(owner, name, number)
            ConversationComposerTarget.DISCUSSION_COMMENT -> RepoUpdateEvent.DiscussionCommentsChanged(owner, name, number)
        }
        repoUpdateEventBus.emit(event)
    }

    private fun <T> ApiResult<T>.mapAny(): ApiResult<Any> = when (this) {
        is ApiResult.Success -> ApiResult.Success(data as Any)
        is ApiResult.Failure -> this
    }
}
