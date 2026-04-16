package com.gitmob.android.ui.repo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.android.api.GHDiscussion
import com.gitmob.android.api.GHDiscussionComment
import com.gitmob.android.api.GHUserContentEdit
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.data.RepoRepository
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DiscussionDetailState(
    val discussion: GHDiscussion? = null,
    val comments: List<GHDiscussionComment> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val userLogin: String = "",
    val toast: String? = null,
    val isRepoOwner: Boolean = false,
    val isSubscribed: Boolean = false,
    val operationInProgress: Boolean = false,
    val editHistoryLoading: Boolean = false,
    val editHistory: List<GHUserContentEdit> = emptyList(),
    val isArchived: Boolean = false,
)

class DiscussionDetailViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    val owner: String = savedStateHandle["owner"] ?: ""
    val repoName: String = savedStateHandle["repo"] ?: ""
    val discussionNumber: Int = savedStateHandle["discussionNumber"] ?: 0

    private val repository = RepoRepository()
    private val tokenStorage = TokenStorage(app)
    private val _state = MutableStateFlow(DiscussionDetailState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            tokenStorage.userProfile.collect { profile ->
                if (profile != null) {
                    _state.update {
                        it.copy(
                            userLogin = profile.first,
                            isRepoOwner = profile.first == owner
                        )
                    }
                }
            }
        }
        loadDiscussionDetail()
    }

    fun loadDiscussionDetail(forceRefresh: Boolean = false) = viewModelScope.launch {
        _state.update { it.copy(loading = true, refreshing = forceRefresh, error = null) }
        try {
            val (discussion, comments) = repository.getDiscussionDetailGraphQL(
                owner = owner,
                repo = repoName,
                number = discussionNumber
            )
            LogManager.d("DiscussionDetailViewModel", "正在加载仓库信息以判断是否归档...")
            val repo = repository.getRepo(owner, repoName)
            val isArchived = repo.archived == true
            
            _state.update { 
                it.copy(
                    discussion = discussion, 
                    comments = comments,
                    isArchived = isArchived,
                    loading = false, 
                    refreshing = false,
                    isSubscribed = discussion?.viewerSubscription == "SUBSCRIBED"
                ) 
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = e.message ?: "加载失败"
                )
            }
        }
    }

    fun toggleSubscription() = viewModelScope.launch {
        val currentDiscussion = _state.value.discussion ?: return@launch
        _state.update { it.copy(operationInProgress = true) }
        try {
            val newSubscribed = !_state.value.isSubscribed
            val success = repository.updateDiscussionSubscription(
                discussionId = currentDiscussion.id,
                subscribed = newSubscribed
            )
            if (success) {
                _state.update { 
                    it.copy(
                        isSubscribed = newSubscribed,
                        operationInProgress = false,
                        toast = if (newSubscribed) "已订阅" else "已取消订阅"
                    ) 
                }
            } else {
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        toast = "操作失败"
                    )
                }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    operationInProgress = false,
                    toast = "操作失败: ${e.message}"
                )
            }
        }
    }

    fun updateDiscussion(title: String? = null, body: String? = null) = viewModelScope.launch {
        val currentDiscussion = _state.value.discussion ?: return@launch
        _state.update { it.copy(operationInProgress = true) }
        try {
            val success = repository.updateDiscussion(
                discussionId = currentDiscussion.id,
                title = title,
                body = body
            )
            if (success) {
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        toast = "更新成功"
                    )
                }
                loadDiscussionDetail(forceRefresh = true)
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    operationInProgress = false,
                    toast = "操作失败: ${e.message}"
                )
            }
        }
    }

    fun deleteDiscussion() = viewModelScope.launch {
        val currentDiscussion = _state.value.discussion ?: return@launch
        _state.update { it.copy(operationInProgress = true) }
        try {
            val success = repository.deleteDiscussion(
                discussionId = currentDiscussion.id
            )
            if (success) {
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        toast = "删除成功"
                    )
                }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    operationInProgress = false,
                    toast = "操作失败: ${e.message}"
                )
            }
        }
    }

    fun createComment(
        body: String,
        replyToId: String? = null,
    ) = viewModelScope.launch {
        val currentDiscussion = _state.value.discussion ?: return@launch
        _state.update { it.copy(operationInProgress = true) }
        try {
            val newComment = repository.addDiscussionComment(
                discussionId = currentDiscussion.id,
                body = body,
                replyToId = replyToId
            )
            if (newComment != null) {
                _state.update {
                    it.copy(
                        comments = it.comments + newComment,
                        operationInProgress = false,
                        toast = "评论成功"
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        toast = "评论失败"
                    )
                }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    operationInProgress = false,
                    toast = "操作失败: ${e.message}"
                )
            }
        }
    }

    fun updateComment(commentId: String, body: String) = viewModelScope.launch {
        _state.update { it.copy(operationInProgress = true) }
        try {
            val success = repository.updateDiscussionComment(
                commentId = commentId,
                body = body
            )
            if (success) {
                _state.update { state ->
                    state.copy(
                        comments = state.comments.map { comment ->
                            if (comment.id == commentId) {
                                comment.copy(body = body)
                            } else {
                                comment
                            }
                        },
                        operationInProgress = false,
                        toast = "更新成功"
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        toast = "更新失败"
                    )
                }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    operationInProgress = false,
                    toast = "操作失败: ${e.message}"
                )
            }
        }
    }

    fun deleteComment(commentId: String) = viewModelScope.launch {
        _state.update { it.copy(operationInProgress = true) }
        try {
            val success = repository.deleteDiscussionComment(
                commentId = commentId
            )
            if (success) {
                _state.update {
                    it.copy(
                        comments = it.comments.filter { comment -> comment.id != commentId },
                        operationInProgress = false,
                        toast = "删除成功"
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        operationInProgress = false,
                        toast = "删除失败"
                    )
                }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    operationInProgress = false,
                    toast = "操作失败: ${e.message}"
                )
            }
        }
    }

    fun clearToast() = _state.update { it.copy(toast = null) }

    /**
     * 获取Discussion主体的编辑历史
     */
    fun loadDiscussionBodyEditHistory() = viewModelScope.launch {
        _state.update { it.copy(editHistoryLoading = true, editHistory = emptyList()) }
        try {
            val history = repository.getDiscussionBodyEditHistory(owner, repoName, discussionNumber)
            _state.update { it.copy(editHistoryLoading = false, editHistory = history) }
        } catch (e: Exception) {
            LogManager.w("DiscussionDetailViewModel", "loadDiscussionBodyEditHistory 失败: ${e.message}")
            _state.update { it.copy(editHistoryLoading = false, editHistory = emptyList()) }
        }
    }

    /**
     * 获取Discussion评论的编辑历史
     */
    fun loadDiscussionCommentEditHistory(commentId: String) = viewModelScope.launch {
        _state.update { it.copy(editHistoryLoading = true, editHistory = emptyList()) }
        try {
            val history = repository.getDiscussionCommentEditHistory(commentId)
            _state.update { it.copy(editHistoryLoading = false, editHistory = history) }
        } catch (e: Exception) {
            LogManager.w("DiscussionDetailViewModel", "loadDiscussionCommentEditHistory 失败: ${e.message}")
            _state.update { it.copy(editHistoryLoading = false, editHistory = emptyList()) }
        }
    }

    companion object {
        fun factory(owner: String, repo: String, discussionNumber: Int): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras,
                ): T {
                    val app = checkNotNull(extras[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                    val handle = SavedStateHandle(
                        mapOf(
                            "owner" to owner,
                            "repo" to repo,
                            "discussionNumber" to discussionNumber
                        )
                    )
                    return DiscussionDetailViewModel(app, handle) as T
                }
            }
    }
}
