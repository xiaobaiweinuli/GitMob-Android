package com.gitmob.android.ui.repo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.android.GitMobApp
import com.gitmob.android.api.*
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.data.RepoRepository
import com.gitmob.android.data.RepoUpdateEvent
import com.gitmob.android.data.RepoUpdateEventBus
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PRDetailState(
    val pr: GHPullRequest? = null,
    val reviews: List<GHReview> = emptyList(),
    val comments: List<GHComment> = emptyList(),
    val branches: List<GHBranch> = emptyList(),
    val loading: Boolean = false,
    val commentsLoading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val userLogin: String = "",
    val toast: String? = null,
    val isRepoOwner: Boolean = false,
    val opInProgress: Boolean = false,
    val isSubscribed: Boolean = false,
    val editHistoryLoading: Boolean = false,
    val editHistory: List<GHUserContentEdit> = emptyList(),
    val isArchived: Boolean = false,
)

class PRDetailViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    val owner: String = savedStateHandle["owner"] ?: ""
    val repoName: String = savedStateHandle["repo"] ?: ""
    val prNumber: Int = savedStateHandle["prNumber"] ?: 0

    private val repository = RepoRepository()
    private val tokenStorage = TokenStorage(app)
    private val _state = MutableStateFlow(PRDetailState())
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
        loadPRDetail()
    }

    /**
     * 加载PR详情
     */
    fun loadPRDetail(forceRefresh: Boolean = false) = viewModelScope.launch {
        LogManager.d("PRDetailViewModel", "开始加载 PR 详情，owner: $owner, repo: $repoName, PR号: $prNumber, forceRefresh: $forceRefresh")
        _state.update { it.copy(loading = true, refreshing = forceRefresh, error = null) }
        try {
            LogManager.d("PRDetailViewModel", "正在使用 GraphQL API 获取 PR 详情...")
            val (pr, comments, reviews) = repository.getPRDetailGraphQL(owner, repoName, prNumber)
            
            if (pr == null) {
                throw Exception("PR 详情获取失败")
            }
            
            LogManager.d("PRDetailViewModel", "PR 详情获取成功，title: ${pr.title}, comments 字段值: ${pr.comments}, lastEditedAt: ${pr.lastEditedAt}")
            LogManager.d("PRDetailViewModel", "评论列表获取成功，数量: ${comments.size}")
            LogManager.d("PRDetailViewModel", "审查列表获取成功，数量: ${reviews.size}")
            
            LogManager.d("PRDetailViewModel", "正在加载分支列表...")
            val branches = repository.getBranches(owner, repoName)
            LogManager.d("PRDetailViewModel", "分支列表获取成功，数量: ${branches.size}")
            
            LogManager.d("PRDetailViewModel", "正在加载仓库信息以判断是否归档...")
            val repo = repository.getRepo(owner, repoName)
            val isArchived = repo.archived == true
            
            _state.update {
                it.copy(
                    pr = pr,
                    reviews = reviews,
                    comments = comments,
                    branches = branches,
                    isArchived = isArchived,
                    loading = false,
                    refreshing = false
                )
            }
            LogManager.d("PRDetailViewModel", "PR 详情加载完成，最终状态 - pr.comments: ${pr.comments}, reviews.size: ${reviews.size}, comments.size: ${comments.size}, isArchived: $isArchived")
            loadSubscription()
        } catch (e: Exception) {
            LogManager.e("PRDetailViewModel", "加载 PR 详情失败", e)
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
     * 加载PR订阅状态
     */
    fun loadSubscription() = viewModelScope.launch {
        try {
            val token = tokenStorage.accessToken.first() ?: return@launch
            val subscription = GraphQLClient.getPullRequestSubscription(token, owner, repoName, prNumber)
            _state.update { it.copy(isSubscribed = subscription == "SUBSCRIBED") }
        } catch (e: Exception) {
            LogManager.w("PRDetailViewModel", "loadSubscription 失败: ${e.message}")
        }
    }

    /**
     * 切换订阅状态
     */
    fun toggleSubscription() = viewModelScope.launch {
        val currentPR = _state.value.pr ?: return@launch
        _state.update { it.copy(opInProgress = true) }
        try {
            val token = tokenStorage.accessToken.first() ?: return@launch
            val newSubscribed = !_state.value.isSubscribed
            val success = GraphQLClient.updateSubscribableSubscription(
                token = token,
                subscribableId = currentPR.nodeId,
                subscribed = newSubscribed
            )
            if (success) {
                _state.update { 
                    it.copy(
                        isSubscribed = newSubscribed,
                        opInProgress = false,
                        toast = if (newSubscribed) "已订阅" else "已取消订阅"
                    ) 
                }
            } else {
                _state.update {
                    it.copy(
                        opInProgress = false,
                        toast = "操作失败"
                    )
                }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    opInProgress = false,
                    toast = "操作失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 锁定PR对话
     */
    fun lockPR() = viewModelScope.launch {
        _state.update { it.copy(opInProgress = true) }
        try {
            val success = repository.lockIssue(owner, repoName, prNumber)
            if (success) {
                val freshPR = repository.getPRDetail(owner, repoName, prNumber)
                _state.update {
                    it.copy(
                        pr = freshPR,
                        toast = "✓ 已锁定对话",
                        opInProgress = false
                    )
                }
            } else {
                _state.update { it.copy(toast = "锁定失败", opInProgress = false) }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    toast = "锁定失败：${e.message}",
                    opInProgress = false
                )
            }
        }
    }

    /**
     * 解锁PR对话
     */
    fun unlockPR() = viewModelScope.launch {
        _state.update { it.copy(opInProgress = true) }
        try {
            val success = repository.unlockIssue(owner, repoName, prNumber)
            if (success) {
                val freshPR = repository.getPRDetail(owner, repoName, prNumber)
                _state.update {
                    it.copy(
                        pr = freshPR,
                        toast = "✓ 已解锁对话",
                        opInProgress = false
                    )
                }
            } else {
                _state.update { it.copy(toast = "解锁失败", opInProgress = false) }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    toast = "解锁失败：${e.message}",
                    opInProgress = false
                )
            }
        }
    }

    /**
     * 更新PR
     */
    fun updatePR(title: String? = null, body: String? = null, base: String? = null) = viewModelScope.launch {
        _state.update { it.copy(opInProgress = true) }
        try {
            val updated = ApiClient.api.updatePullRequest(
                owner, repoName, prNumber,
                GHUpdatePullRequestRequest(title = title, body = body, base = base)
            )
            repository.invalidatePRCache(owner, repoName)
            _state.update {
                it.copy(
                    pr = updated,
                    toast = "✓ PR 已更新",
                    opInProgress = false
                )
            }
            RepoUpdateEventBus.send(
                RepoUpdateEvent.PRUpdated(
                    owner = owner,
                    repo = repoName,
                    number = prNumber,
                    title = updated.title,
                    state = updated.state
                )
            )
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    toast = "更新失败：${e.message}",
                    opInProgress = false
                )
            }
        }
    }

    /**
     * 合并PR
     */
    fun mergePR(method: MergeMethod, commitTitle: String? = null, commitMessage: String? = null) = viewModelScope.launch {
        _state.update { it.copy(opInProgress = true) }
        try {
            val resp = repository.mergePR(owner, repoName, prNumber, method.apiValue, commitTitle, commitMessage)
            if (resp.merged) {
                repository.invalidatePRCache(owner, repoName)
                val freshPR = repository.getPRDetail(owner, repoName, prNumber)
                _state.update {
                    it.copy(
                        pr = freshPR,
                        toast = "✓ 合并成功",
                        opInProgress = false
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        toast = "合并失败: ${resp.message}",
                        opInProgress = false
                    )
                }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    toast = "合并失败: ${e.message}",
                    opInProgress = false
                )
            }
        }
    }

    /**
     * 更新分支（基分支有新提交时同步）
     */
    fun updatePRBranch() = viewModelScope.launch {
        _state.update { it.copy(opInProgress = true) }
        try {
            repository.updatePRBranch(owner, repoName, prNumber)
            _state.update {
                it.copy(
                    toast = "✓ 分支已更新",
                    opInProgress = false
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    toast = "更新失败: ${e.message}",
                    opInProgress = false
                )
            }
        }
    }

    /**
     * 提交审查（APPROVE / REQUEST_CHANGES / COMMENT）
     */
    fun submitReview(event: String, body: String) = viewModelScope.launch {
        _state.update { it.copy(opInProgress = true) }
        try {
            val review = repository.createPRReview(owner, repoName, prNumber, event, body.ifBlank { null })
            val label = when (event) {
                "APPROVE" -> "✓ 已批准"
                "REQUEST_CHANGES" -> "✓ 已请求修改"
                else -> "✓ 评论已提交"
            }
            val freshReviews = repository.getPRReviews(owner, repoName, prNumber)
            _state.update {
                it.copy(
                    reviews = freshReviews,
                    toast = label,
                    opInProgress = false
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    toast = "提交失败: ${e.message}",
                    opInProgress = false
                )
            }
        }
    }

    /**
     * 请求审查者
     */
    fun requestReviewers(reviewers: List<String>) = viewModelScope.launch {
        _state.update { it.copy(opInProgress = true) }
        try {
            val updated = repository.requestReviewers(owner, repoName, prNumber, reviewers)
            _state.update {
                it.copy(
                    pr = updated,
                    toast = "✓ 审查请求已发送",
                    opInProgress = false
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    toast = "请求失败: ${e.message}",
                    opInProgress = false
                )
            }
        }
    }

    /**
     * 添加PR评论
     */
    fun addPRComment(body: String) = viewModelScope.launch {
        if (body.isBlank()) return@launch
        _state.update { it.copy(opInProgress = true) }
        try {
            val comment = repository.addPRComment(owner, repoName, prNumber, body)
            _state.update {
                it.copy(
                    comments = it.comments + comment,
                    opInProgress = false
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    toast = "评论失败: ${e.message}",
                    opInProgress = false
                )
            }
        }
    }

    /**
     * 关闭/重新打开PR
     */
    fun togglePRState() = viewModelScope.launch {
        _state.update { it.copy(opInProgress = true) }
        try {
            val currentPR = _state.value.pr ?: return@launch
            val newState = if (currentPR.state == "open") "closed" else "open"
            val toastMessage = if (newState == "closed") "✓ PR 已关闭" else "✓ PR 已重新打开"
            
            ApiClient.api.updatePullRequest(owner, repoName, prNumber, GHUpdatePullRequestRequest(state = newState))
            repository.invalidatePRCache(owner, repoName)
            
            val token = GitMobApp.instance.tokenStorage.accessToken.first() ?: return@launch
            val (pr, _, _) = GraphQLClient.getPRDetail(
                token = token,
                owner = owner,
                repo = repoName,
                number = prNumber
            )
            
            if (pr != null) {
                _state.update {
                    it.copy(
                        pr = pr,
                        toast = toastMessage,
                        opInProgress = false
                    )
                }
                RepoUpdateEventBus.send(
                    RepoUpdateEvent.PRUpdated(
                        owner = owner,
                        repo = repoName,
                        number = prNumber,
                        title = pr.title,
                        state = pr.state
                    )
                )
            } else {
                _state.update {
                    it.copy(
                        toast = toastMessage,
                        opInProgress = false
                    )
                }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    toast = "操作失败: ${e.message}",
                    opInProgress = false
                )
            }
        }
    }

    /**
     * 将草稿 PR 标记为就绪
     */
    fun markPRReadyForReview() = viewModelScope.launch {
        val currentPR = _state.value.pr ?: return@launch
        if (currentPR.nodeId.isBlank()) {
            _state.update { it.copy(toast = "缺少 PR node_id，无法操作") }
            return@launch
        }
        _state.update { it.copy(opInProgress = true) }
        try {
            val token = GitMobApp.instance.tokenStorage.accessToken.first() ?: return@launch
            val ok = GraphQLClient.markPrReadyForReview(token, currentPR.nodeId)
            if (ok) {
                val freshPR = repository.getPRDetail(owner, repoName, prNumber)
                repository.invalidatePRCache(owner, repoName)
                _state.update {
                    it.copy(
                        pr = freshPR,
                        toast = "✓ 已标记为就绪",
                        opInProgress = false
                    )
                }
            } else {
                _state.update { it.copy(toast = "标记失败", opInProgress = false) }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    toast = "操作失败: ${e.message}",
                    opInProgress = false
                )
            }
        }
    }

    /**
     * 更新评论
     */
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

    /**
     * 删除评论
     */
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

    fun clearToast() = _state.update { it.copy(toast = null) }

    /**
     * 获取PR主体的编辑历史
     */
    fun loadPRBodyEditHistory() = viewModelScope.launch {
        _state.update { it.copy(editHistoryLoading = true, editHistory = emptyList()) }
        try {
            val token = tokenStorage.accessToken.first() ?: return@launch
            val history = GraphQLClient.getPRBodyEditHistory(token, owner, repoName, prNumber)
            _state.update { it.copy(editHistoryLoading = false, editHistory = history) }
        } catch (e: Exception) {
            LogManager.w("PRDetailViewModel", "loadPRBodyEditHistory 失败: ${e.message}")
            _state.update { it.copy(editHistoryLoading = false, editHistory = emptyList()) }
        }
    }

    /**
     * 获取PR评论的编辑历史
     */
    fun loadPRCommentEditHistory(commentId: String) = viewModelScope.launch {
        _state.update { it.copy(editHistoryLoading = true, editHistory = emptyList()) }
        try {
            val token = tokenStorage.accessToken.first() ?: return@launch
            val history = GraphQLClient.getPRCommentEditHistory(token, commentId)
            _state.update { it.copy(editHistoryLoading = false, editHistory = history) }
        } catch (e: Exception) {
            LogManager.w("PRDetailViewModel", "loadPRCommentEditHistory 失败: ${e.message}")
            _state.update { it.copy(editHistoryLoading = false, editHistory = emptyList()) }
        }
    }

    companion object {
        fun factory(owner: String, repo: String, prNumber: Int): androidx.lifecycle.ViewModelProvider.Factory =
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
                            "prNumber" to prNumber
                        )
                    )
                    return PRDetailViewModel(app, handle) as T
                }
            }
    }
}
