package com.gitmob.android.ui.repo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.android.api.*
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.data.RepoRepository
import com.gitmob.android.data.RepoUpdateEvent
import com.gitmob.android.data.RepoUpdateEventBus
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class IssueDetailState(
    val issue: GHIssue? = null,
    val comments: List<GHComment> = emptyList(),
    val loading: Boolean = false,
    val commentsLoading: Boolean = false,
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

class IssueDetailViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    val owner: String = savedStateHandle["owner"] ?: ""
    val repoName: String = savedStateHandle["repo"] ?: ""
    val issueNumber: Int = savedStateHandle["issueNumber"] ?: 0

    private val repository = RepoRepository()
    private val tokenStorage = TokenStorage(app)
    private val _state = MutableStateFlow(IssueDetailState())
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
        loadIssueDetail()
    }

    fun loadIssueDetail(forceRefresh: Boolean = false) = viewModelScope.launch {
        LogManager.d("IssueDetailViewModel", "开始加载 Issue 详情，owner: $owner, repo: $repoName, Issue号: $issueNumber, forceRefresh: $forceRefresh")
        _state.update { it.copy(loading = true, refreshing = forceRefresh, error = null) }
        try {
            LogManager.d("IssueDetailViewModel", "正在使用 GraphQL API 获取 Issue 详情...")
            val (issue, comments) = repository.getIssueDetailGraphQL(owner, repoName, issueNumber)
            
            if (issue == null) {
                throw Exception("Issue 详情获取失败")
            }
            
            LogManager.d("IssueDetailViewModel", "Issue 详情获取成功，title: ${issue.title}, comments 字段值: ${issue.comments}, lastEditedAt: ${issue.lastEditedAt}")
            LogManager.d("IssueDetailViewModel", "评论列表获取成功，数量: ${comments.size}")
            
            LogManager.d("IssueDetailViewModel", "正在加载仓库信息以判断是否归档...")
            val repo = repository.getRepo(owner, repoName)
            val isArchived = repo.archived == true
            
            _state.update { it.copy(issue = issue, comments = comments, isArchived = isArchived, loading = false, refreshing = false) }
            LogManager.d("IssueDetailViewModel", "Issue 详情加载完成，最终状态 - issue.comments: ${issue.comments}, comments.size: ${comments.size}, isArchived: $isArchived")
            loadSubscription()
        } catch (e: Exception) {
            LogManager.e("IssueDetailViewModel", "加载 Issue 详情失败", e)
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    error = e.message ?: "加载失败"
                )
            }
        }
    }

    /**
     * 加载Issue订阅状态
     */
    fun loadSubscription() = viewModelScope.launch {
        try {
            val token = tokenStorage.accessToken.first() ?: return@launch
            val subscription = GraphQLClient.getIssueSubscription(token, owner, repoName, issueNumber)
            _state.update { it.copy(isSubscribed = subscription == "SUBSCRIBED") }
        } catch (e: Exception) {
            LogManager.w("IssueDetailViewModel", "loadSubscription 失败: ${e.message}")
        }
    }

    fun loadComments(forceRefresh: Boolean = false) = viewModelScope.launch {
        _state.update { it.copy(commentsLoading = true) }
        try {
            LogManager.d("IssueDetailViewModel", "正在获取 Issue 评论列表...")
            val comments = repository.getIssueComments(owner, repoName, issueNumber)
            LogManager.d("IssueDetailViewModel", "Issue 评论列表获取成功，数量: ${comments.size}")
            _state.update { it.copy(comments = comments, commentsLoading = false) }
        } catch (e: Exception) {
            LogManager.e("IssueDetailViewModel", "获取 Issue 评论列表失败", e)
            _state.update { it.copy(commentsLoading = false) }
        }
    }

    fun createComment(body: String) = viewModelScope.launch {
        try {
            val comment = repository.createIssueComment(owner, repoName, issueNumber, body)
            _state.update {
                it.copy(
                    comments = it.comments + comment,
                    toast = "评论已发表"
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "发表失败：${e.message}") }
        }
    }

    fun updateComment(commentId: Long, body: String) = viewModelScope.launch {
        try {
            val updated = repository.updateIssueComment(owner, repoName, commentId, body)
            _state.update {
                it.copy(
                    comments = it.comments.map { c -> if (c.id == commentId) updated else c },
                    toast = "评论已更新"
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "更新失败：${e.message}") }
        }
    }

    fun deleteComment(commentId: Long) = viewModelScope.launch {
        try {
            val success = repository.deleteIssueComment(owner, repoName, commentId)
            if (success) {
                _state.update {
                    it.copy(
                        comments = it.comments.filter { c -> c.id != commentId },
                        toast = "评论已删除"
                    )
                }
            } else {
                _state.update { it.copy(toast = "删除失败") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "删除失败：${e.message}") }
        }
    }

    fun updateIssue(title: String? = null, body: String? = null, state: String? = null, stateReason: String? = null) = viewModelScope.launch {
        try {
            val updated = repository.updateIssue(
                owner, repoName, issueNumber,
                GHUpdateIssueRequest(title = title, body = body, state = state, stateReason = stateReason)
            )
            _state.update { it.copy(issue = updated, toast = "议题已更新") }
            RepoUpdateEventBus.send(
                RepoUpdateEvent.IssueUpdated(
                    owner = owner,
                    repo = repoName,
                    number = issueNumber,
                    title = updated.title,
                    state = updated.state
                )
            )
        } catch (e: Exception) {
            _state.update { it.copy(toast = "更新失败：${e.message}") }
        }
    }

    /**
     * 切换订阅状态
     */
    fun toggleSubscription() = viewModelScope.launch {
        val currentIssue = _state.value.issue ?: return@launch
        _state.update { it.copy(operationInProgress = true) }
        try {
            val token = tokenStorage.accessToken.first() ?: return@launch
            val newSubscribed = !_state.value.isSubscribed
            val success = GraphQLClient.updateSubscribableSubscription(
                token = token,
                subscribableId = currentIssue.nodeId,
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

    fun deleteIssue(onSuccess: () -> Unit) = viewModelScope.launch {
        try {
            val success = repository.deleteIssue(owner, repoName, issueNumber)
            if (success) {
                _state.update { it.copy(toast = "议题已删除") }
                onSuccess()
            } else {
                _state.update { it.copy(toast = "删除失败") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "删除失败：${e.message}") }
        }
    }

    fun clearToast() = _state.update { it.copy(toast = null) }

    /**
     * 获取Issue主体的编辑历史
     */
    fun loadIssueBodyEditHistory() = viewModelScope.launch {
        _state.update { it.copy(editHistoryLoading = true, editHistory = emptyList()) }
        try {
            val token = tokenStorage.accessToken.first() ?: return@launch
            val history = GraphQLClient.getIssueBodyEditHistory(token, owner, repoName, issueNumber)
            _state.update { it.copy(editHistoryLoading = false, editHistory = history) }
        } catch (e: Exception) {
            LogManager.w("IssueDetailViewModel", "loadIssueBodyEditHistory 失败: ${e.message}")
            _state.update { it.copy(editHistoryLoading = false, editHistory = emptyList()) }
        }
    }

    /**
     * 获取Issue评论的编辑历史
     */
    fun loadIssueCommentEditHistory(commentId: String) = viewModelScope.launch {
        _state.update { it.copy(editHistoryLoading = true, editHistory = emptyList()) }
        try {
            val token = tokenStorage.accessToken.first() ?: return@launch
            val history = GraphQLClient.getIssueCommentEditHistory(token, commentId)
            _state.update { it.copy(editHistoryLoading = false, editHistory = history) }
        } catch (e: Exception) {
            LogManager.w("IssueDetailViewModel", "loadIssueCommentEditHistory 失败: ${e.message}")
            _state.update { it.copy(editHistoryLoading = false, editHistory = emptyList()) }
        }
    }

    companion object {
        fun factory(owner: String, repo: String, issueNumber: Int): androidx.lifecycle.ViewModelProvider.Factory =
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
                            "issueNumber" to issueNumber
                        )
                    )
                    return IssueDetailViewModel(app, handle) as T
                }
            }
    }
}
