package com.gitmob.android.ui.repo

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.android.GitMobApp
import com.gitmob.android.api.*
import com.gitmob.android.api.GraphQLClient
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.data.RepoRepository
import com.gitmob.android.data.RepoUpdateEvent
import com.gitmob.android.data.RepoUpdateEventBus
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit


import kotlinx.coroutines.Job

class RepoDetailViewModel(app: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {

    val owner: String = savedStateHandle["owner"] ?: ""
    private val initialRepoName: String = savedStateHandle["repo"] ?: ""

    private val repository = RepoRepository()
    private val tokenStorage = TokenStorage(app)
    private val _state = MutableStateFlow(RepoDetailState())
    val state = _state.asStateFlow()

    private val currentRepoName: String
        get() = _state.value.currentRepoName

    // ── Job 去重：同类请求只保留最新一个，取消旧 Job 前先等其自然结束 ──────
    // 注意：不主动 cancel 旧 Job，让其继续执行直到 OkHttp 完成；
    // 通过 isActive 检查避免重复写入 State，而非通过 cancel 中断 OkHttp。
    private var loadAllJob: Job? = null
    private var contentsJob: Job? = null
    private var commitsJob: Job? = null

    init {
        _state.update { it.copy(currentRepoName = initialRepoName) }
        // 当前登录用户名与组织（用于仓库转移等）
        viewModelScope.launch {
            tokenStorage.userProfile.collect { profile ->
                if (profile != null) {
                    _state.update {
                        it.copy(
                            userLogin  = profile.first,
                            userAvatar = profile.third,
                        )
                    }
                    loadOrgs()
                }
            }
        }
        // 监听仓库更新事件（PR、Issue等更新）
        viewModelScope.launch {
            RepoUpdateEventBus.events.collect { event ->
                LogManager.d("RepoDetailViewModel", "收到事件: $event")
                when (event) {
                    is RepoUpdateEvent.PRUpdated -> {
                        LogManager.d("RepoDetailViewModel", "PRUpdated 事件匹配: owner match=${event.owner == owner}, repo match=${event.repo == currentRepoName}")
                        if (event.owner == owner && event.repo == currentRepoName) {
                            handlePRUpdated(event)
                        }
                    }
                    is RepoUpdateEvent.IssueUpdated -> {
                        LogManager.d("RepoDetailViewModel", "IssueUpdated 事件匹配: owner match=${event.owner == owner}, repo match=${event.repo == currentRepoName}")
                        if (event.owner == owner && event.repo == currentRepoName) {
                            handleIssueUpdated(event)
                        }
                    }
                    else -> {}
                }
            }
        }
        loadAll()
    }

    private fun loadOrgs() = viewModelScope.launch {
        try {
            val orgs = repository.getUserOrgs()
            _state.update { it.copy(userOrgs = orgs) }
        } catch (_: Exception) {}
    }

    fun loadAll(forceRefresh: Boolean = false) {
        // 若前一个 loadAll 仍在运行，不重复启动（避免 OkHttp 连接竞争触发 Canceled）
        if (loadAllJob?.isActive == true && !forceRefresh) return
        loadAllJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                // ── 只加载"文件 Tab"必需的内容（任务二：懒加载） ──────────────
                val r        = repository.getRepo(owner, currentRepoName, forceRefresh)
                val branches = repository.getBranches(owner, currentRepoName, forceRefresh)
                val starred  = repository.isStarred(owner, currentRepoName)
                val currentTab = _state.value.tab
                val validTab = ensureValidTabIndex(r, currentTab)
                _state.update {
                    it.copy(
                        repo          = r,
                        branches      = branches,
                        currentBranch = r.defaultBranch,
                        isStarred     = starred,
                        tab           = validTab,
                        loading       = false,
                    )
                }
                // 文件树 + 订阅状态（用于显示 Watch 按钮）
                loadContents("", r.defaultBranch, forceRefresh)
                // 复刻仓库同步状态
                if (r.fork) checkForkSyncStatus()
            } catch (e: Exception) {
                val is404 = e.message?.contains("404") == true ||
                    e.message?.contains("Not Found") == true ||
                    (e is retrofit2.HttpException && e.code() == 404)
                _state.update { it.copy(loading = false, error = e.message ?: "加载失败", repoNotFound = is404) }
            }
        } // loadAllJob
    }

    /** 点击"提交"Tab 时调用（懒加载） */
    fun ensureCommitsLoaded() {
        if (_state.value.commits.isNotEmpty()) return
        loadCommits(_state.value.currentBranch)
    }

    /** 点击"README"时调用（懒加载） */
    fun ensureReadmeLoaded() {
        val s = _state.value
        if (s.readmeContent != null) return
        loadReadme(owner, currentRepoName, s.currentBranch)
    }

    /** 点击"发行版"Tab 时调用（懒加载） */
    fun ensureReleasesLoaded() {
        if (_state.value.releases.isNotEmpty()) return
        loadReleases()
    }

    /** 点击"操作"Tab 时调用（懒加载） */
    fun ensureActionsLoaded() {
        if (_state.value.workflows.isNotEmpty()) return
        loadWorkflows()
        loadWorkflowRuns(null)
    }

    /** 点击"Issues"Tab 时调用（懒加载） */
    fun ensureIssuesLoaded() {
        if (_state.value.issuesLoaded) return
        loadLabels()
        loadIssuesByState(forceRefresh = false)
    }

    fun ensurePRsLoaded() {
        if (_state.value.prsLoaded) return
        loadLabels()
        loadPRsByFilter(forceRefresh = false)
    }

    fun ensureDiscussionsLoaded() {
        if (_state.value.discussions.isNotEmpty() || _state.value.discussionsLoading) return
        loadLabels()
        loadDiscussions(cursor = null, clear = true)
    }

    // ─── Discussions（GraphQL）────────────────────────────────────────────────

    fun loadDiscussions(cursor: String? = null, clear: Boolean = false) = viewModelScope.launch {
        val token = GitMobApp.instance.tokenStorage.accessToken.first() ?: return@launch
        if (clear) _state.update { it.copy(discussionsLoading = true, discussions = emptyList(), discussionsCursor = null) }
        else _state.update { it.copy(discussionsLoadingMore = true) }
        try {
            if (_state.value.discussionCategories.isEmpty()) {
                val cats = GraphQLClient.getDiscussionCategories(token, owner, _state.value.currentRepoName)
                _state.update { it.copy(discussionCategories = cats) }
            }
            val s = _state.value
            val (items, nextCursor) = GraphQLClient.getDiscussions(
                token = token, owner = owner, repo = _state.value.currentRepoName,
                first = 20, after = cursor,
                categoryId = s.discussionCategoryFilter,
                answered = s.discussionAnsweredFilter,
                orderField = s.discussionFilterState.sortBy.apiValue,
            )
            var filteredItems = items
            if (s.discussionFilterState.selectedCreator != null) {
                filteredItems = filteredItems.filter { it.author?.login == s.discussionFilterState.selectedCreator }
            }
            if (s.discussionFilterState.selectedLabels.isNotEmpty()) {
                filteredItems = filteredItems.filter { item ->
                    s.discussionFilterState.selectedLabels.all { selectedLabel ->
                        item.labels.any { it.name == selectedLabel }
                    }
                }
            }
            _state.update { s2 ->
                s2.copy(
                    discussions        = if (clear) filteredItems else s2.discussions + filteredItems,
                    discussionsCursor  = nextCursor,
                    discussionsHasMore = nextCursor != null,
                    discussionsLoading = false,
                    discussionsLoadingMore = false,
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(discussionsLoading = false, discussionsLoadingMore = false) }
        }
    }

    fun loadMoreDiscussions() {
        if (_state.value.discussionsLoadingMore || !_state.value.discussionsHasMore) return
        loadDiscussions(cursor = _state.value.discussionsCursor, clear = false)
    }

    fun setDiscussionCategoryFilter(categoryId: String?) {
        _state.update { it.copy(discussionCategoryFilter = categoryId) }
        loadDiscussions(cursor = null, clear = true)
    }

    fun setDiscussionAnsweredFilter(answered: Boolean?) {
        _state.update { it.copy(discussionAnsweredFilter = answered) }
        loadDiscussions(cursor = null, clear = true)
    }

    fun setDiscussionSortBy(sortBy: DiscussionSortBy) {
        _state.update { it.copy(discussionFilterState = it.discussionFilterState.copy(sortBy = sortBy)) }
        loadDiscussions(cursor = null, clear = true)
    }

    fun setDiscussionCreator(creator: String?) {
        _state.update { it.copy(discussionFilterState = it.discussionFilterState.copy(selectedCreator = creator)) }
        loadDiscussions(cursor = null, clear = true)
    }

    fun toggleDiscussionLabel(label: String) {
        _state.update {
            val current = it.discussionFilterState.selectedLabels
            val newSet = if (current.contains(label)) current - label else current + label
            it.copy(discussionFilterState = it.discussionFilterState.copy(selectedLabels = newSet))
        }
        loadDiscussions(cursor = null, clear = true)
    }

    fun clearDiscussionFilters() {
        _state.update {
            it.copy(
                discussionCategoryFilter = null,
                discussionAnsweredFilter = null,
                discussionFilterState = DiscussionFilterState()
            )
        }
        loadDiscussions(cursor = null, clear = true)
    }

    fun getAllDiscussionAuthors(): Set<String> {
        return _state.value.discussions.mapNotNull { it.author?.login }.toSet()
    }

    fun refreshDiscussions() = viewModelScope.launch {
        _state.update { it.copy(discussionsRefreshing = true) }
        loadDiscussions(cursor = null, clear = true)
        _state.update { it.copy(discussionsRefreshing = false) }
    }

    /**
     * 删除讨论
     */
    fun deleteDiscussion(discussionNumber: Int) = viewModelScope.launch {
        try {
            _state.update { it.copy(discussions = it.discussions.filter { d -> d.number != discussionNumber }, toast = "已删除讨论 #$discussionNumber") }
            // TODO: 实际的删除API调用需要使用GraphQL
        } catch (e: Exception) {
            _state.update { it.copy(toast = "删除失败：${e.message}") }
        }
    }



    
    /**
     * 检查复刻仓库与上游仓库的同步状态
     */
    fun checkForkSyncStatus() = viewModelScope.launch {
        val repo = _state.value.repo ?: return@launch
        if (!repo.fork) return@launch
        val parent = repo.parent ?: return@launch
        
        _state.update { it.copy(forkSyncLoading = true) }
        
        try {
            val upstreamOwner = parent.owner.login
            val upstreamRepo = parent.name
            val upstreamBranch = parent.defaultBranch
            val forkBranch = _state.value.currentBranch
            
            // 正向比较：upstream...fork，获取fork领先的提交
            val compareResult = repository.compareCommits(
                upstreamOwner, upstreamRepo,
                upstreamBranch,
                "$owner:$forkBranch"
            )
            
            // 反向比较：fork...upstream，获取fork落后的提交（即upstream领先的提交）
            val reverseCompareResult = repository.compareCommits(
                owner, _state.value.currentRepoName,
                forkBranch,
                "$upstreamOwner:$upstreamBranch"
            )
            
            _state.update { 
                it.copy(
                    forkSyncLoading = false,
                    forkSyncStatus = compareResult.status,
                    forkAheadBy = compareResult.aheadBy,
                    forkBehindBy = compareResult.behindBy,
                    forkSyncCompareResult = compareResult,
                    forkSyncReverseCompareResult = reverseCompareResult
                ) 
            }
        } catch (e: Exception) {
            _state.update { 
                it.copy(
                    forkSyncLoading = false,
                    forkSyncStatus = null,
                    forkAheadBy = 0,
                    forkBehindBy = 0,
                    forkSyncCompareResult = null,
                    forkSyncReverseCompareResult = null
                ) 
            }
        }
    }
    
    /**
     * 使用API同步复刻分支与上游仓库
     */
    fun syncForkBranch() = viewModelScope.launch {
        val repo = _state.value.repo ?: return@launch
        if (!repo.fork) return@launch
        
        _state.update { 
            it.copy(
                forkSyncing = true,
                forkSyncError = null,
                forkSyncSuccess = false,
                forkSyncResponse = null
            ) 
        }
        
        try {
            val response = repository.syncForkBranch(owner, _state.value.currentRepoName, _state.value.currentBranch)
            
            _state.update { 
                it.copy(
                    forkSyncing = false,
                    forkSyncSuccess = true,
                    forkSyncResponse = response
                ) 
            }
            
            // 同步成功后，重新检查同步状态并刷新仓库信息
            checkForkSyncStatus()
            loadAll(forceRefresh = true)
            
        } catch (e: Exception) {
            _state.update { 
                it.copy(
                    forkSyncing = false,
                    forkSyncError = e.message ?: "同步失败"
                ) 
            }
        }
    }
    
    /**
     * 丢弃自己的提交后同步
     */
    fun discardCommitsAndSync() = viewModelScope.launch {
        val repo = _state.value.repo ?: return@launch
        val parent = repo.parent ?: return@launch
        if (!repo.fork) return@launch
        
        _state.update { 
            it.copy(
                forkDiscardingCommits = true,
                forkDiscardSuccess = false,
                forkSyncError = null
            ) 
        }
        
        try {
            val upstreamOwner = parent.owner.login
            val upstreamRepo = parent.name
            val upstreamBranch = parent.defaultBranch
            val currentBranch = _state.value.currentBranch
            
            // 1. 先获取上游分支的最新 commit
            val upstreamBranchData = repository.getBranch(upstreamOwner, upstreamRepo, upstreamBranch)
            
            // 2. 强制重置当前分支到上游的 commit
            repository.resetBranchToCommit(
                owner, _state.value.currentRepoName, currentBranch,
                upstreamBranchData.commit.sha
            )
            
            _state.update { 
                it.copy(
                    forkDiscardingCommits = false,
                    forkDiscardSuccess = true
                ) 
            }
            
            // 3. 重新检查同步状态并刷新仓库信息
            checkForkSyncStatus()
            loadAll(forceRefresh = true)
            
        } catch (e: Exception) {
            _state.update { 
                it.copy(
                    forkDiscardingCommits = false,
                    forkDiscardSuccess = false,
                    forkSyncError = e.message ?: "操作失败"
                ) 
            }
        }
    }
    
    /**
     * 创建拉取请求
     */
    fun createPullRequest() = viewModelScope.launch {
        val repo = _state.value.repo ?: return@launch
        val parent = repo.parent ?: return@launch
        if (!repo.fork) return@launch
        
        _state.update { 
            it.copy(
                forkCreatingPR = true,
                forkCreatePRSuccess = false,
                forkCreatedPR = null,
                forkSyncError = null
            ) 
        }
        
        try {
            val upstreamOwner = parent.owner.login
            val upstreamRepo = parent.name
            val upstreamBranch = parent.defaultBranch
            val currentBranch = _state.value.currentBranch
            
            val pr = repository.createPullRequest(
                upstreamOwner, upstreamRepo,
                "同步 ${repo.owner.login}/${currentBranch} 到上游",
                "此 PR 用于同步 ${repo.owner.login}/${currentRepoName} 的 ${currentBranch} 分支到 ${upstreamOwner}/${upstreamRepo} 的 ${upstreamBranch} 分支。\n\n自动创建",
                "${repo.owner.login}:${currentBranch}",
                upstreamBranch
            )
            
            _state.update { 
                it.copy(
                    forkCreatingPR = false,
                    forkCreatePRSuccess = true,
                    forkCreatedPR = pr
                ) 
            }
            
        } catch (e: Exception) {
            _state.update { 
                it.copy(
                    forkCreatingPR = false,
                    forkCreatePRSuccess = false,
                    forkCreatedPR = null,
                    forkSyncError = e.message ?: "创建 PR 失败"
                ) 
            }
        }
    }

    fun loadContents(path: String, ref: String? = null, forceRefresh: Boolean = false) {
        // 同路径非强制刷新时去重
        if (!forceRefresh && contentsJob?.isActive == true && _state.value.currentPath == path) return
        contentsJob = viewModelScope.launch {
        _state.update { it.copy(contentsLoading = true, filesRefreshing = forceRefresh) }
        try {
            val branch = ref ?: _state.value.currentBranch
            val contents = repository.getContents(owner, currentRepoName, path, branch, forceRefresh)
                .sortedWith(compareBy({ it.type != "dir" }, { it.name }))
            _state.update { it.copy(contents = contents, currentPath = path, contentsLoading = false, filesRefreshing = false) }
        } catch (e: Exception) {
            _state.update { it.copy(contentsLoading = false, filesRefreshing = false) }
        }
     } // contentsJob
    }

    fun navigateUp() {
        val path = _state.value.currentPath
        val parent = if (path.contains("/")) path.substringBeforeLast("/") else ""
        loadContents(parent)
    }

    fun loadCommits(sha: String? = null, path: String? = null, forceRefresh: Boolean = false) = viewModelScope.launch {
        _state.update { it.copy(commitsRefreshing = forceRefresh) }
        try {
            val ref = sha ?: _state.value.currentBranch
            val commits = if (forceRefresh) {
                repository.refreshCommitsIncremental(owner, currentRepoName, ref)
            } else {
                repository.getCommits(owner, currentRepoName, ref, path, false)
            }
            _state.update { it.copy(commits = commits, commitsRefreshing = false) }
        } catch (_: Exception) {
            _state.update { it.copy(commitsRefreshing = false) }
        }
    }

    // ─── PR：筛选分页加载 ────────────────────────────────────────────────────

    /** 按当前筛选加载 PR 第1页 */
    fun loadPRsByFilter(forceRefresh: Boolean = false) = viewModelScope.launch {
        val f = _state.value.prFilterState
        val isMerged = f.status == PRStatusFilter.MERGED
        val apiState = when (f.status) {
            PRStatusFilter.OPEN   -> "open"
            PRStatusFilter.MERGED -> "closed"
            PRStatusFilter.CLOSED -> "closed"
            PRStatusFilter.ALL    -> "all"
        }
        try {
            _state.update { it.copy(prsPage = 1, prsHasMore = false) }
            val prs = if (forceRefresh) {
                repository.refreshPRsIncremental(owner, currentRepoName, apiState, f.sortBy.sort,
                    f.sortBy.direction, f.labelFilter, f.authorFilter, f.reviewerFilter, isMerged)
            } else {
                repository.getPRs(owner, currentRepoName, apiState, f.sortBy.sort, f.sortBy.direction,
                    1, false, f.labelFilter, f.authorFilter, f.reviewerFilter, isMerged)
            }
            _state.update { it.copy(prs = prs, prsPage = 1, prsHasMore = prs.size >= 30, prsLoaded = true) }
        } catch (_: Exception) {}
    }

    fun loadMorePRs() = viewModelScope.launch {
        if (_state.value.prsLoadingMore || !_state.value.prsHasMore) return@launch
        val nextPage = _state.value.prsPage + 1
        val f = _state.value.prFilterState
        val isMerged = f.status == PRStatusFilter.MERGED
        val apiState = when (f.status) {
            PRStatusFilter.OPEN   -> "open"
            PRStatusFilter.MERGED -> "closed"
            PRStatusFilter.CLOSED -> "closed"
            PRStatusFilter.ALL    -> "all"
        }
        _state.update { it.copy(prsLoadingMore = true) }
        try {
            val more = repository.getPRs(owner, currentRepoName, apiState, f.sortBy.sort,
                f.sortBy.direction, nextPage, false, f.labelFilter, f.authorFilter,
                f.reviewerFilter, isMerged)
            _state.update { s ->
                s.copy(
                    prs            = s.prs + more,
                    prsPage        = nextPage,
                    prsHasMore     = more.size >= 30,
                    prsLoadingMore = false,
                )
            }
        } catch (_: Exception) {
            _state.update { it.copy(prsLoadingMore = false) }
        }
    }

    fun setPRStatusFilter(status: PRStatusFilter) {
        _state.update { it.copy(prFilterState = it.prFilterState.copy(status = status)) }
        loadPRsByFilter(forceRefresh = true)
    }

    fun setPRSortBy(sortBy: PRSortBy) {
        _state.update { it.copy(prFilterState = it.prFilterState.copy(sortBy = sortBy)) }
        loadPRsByFilter(forceRefresh = true)
    }

    fun setPRLabelFilter(label: String?) {
        _state.update { it.copy(prFilterState = it.prFilterState.copy(labelFilter = label)) }
        loadPRsByFilter(forceRefresh = true)
    }

    fun setPRAuthorFilter(author: String?) {
        _state.update { it.copy(prFilterState = it.prFilterState.copy(authorFilter = author)) }
        loadPRsByFilter(forceRefresh = true)
    }

    fun setPRReviewerFilter(reviewer: String?) {
        _state.update { it.copy(prFilterState = it.prFilterState.copy(reviewerFilter = reviewer)) }
        loadPRsByFilter(forceRefresh = true)
    }

    fun clearPRFilters() {
        _state.update { it.copy(prFilterState = PRFilterState()) }
        loadPRsByFilter(forceRefresh = true)
    }

    // ─── PR：刷新（增量）────────────────────────────────────────────────────

    fun refreshPRs() = viewModelScope.launch {
        _state.update { it.copy(prsRefreshing = true) }
        try {
            val f = _state.value.prFilterState
            val isMerged = f.status == PRStatusFilter.MERGED
            val apiState = when (f.status) {
                PRStatusFilter.OPEN -> "open"; PRStatusFilter.MERGED, PRStatusFilter.CLOSED -> "closed"
                PRStatusFilter.ALL -> "all"
            }
            val prs = repository.refreshPRsIncremental(owner, currentRepoName, apiState,
                f.sortBy.sort, f.sortBy.direction, f.labelFilter, f.authorFilter, f.reviewerFilter, isMerged)
            _state.update { it.copy(prs = prs, prsRefreshing = false) }
        } catch (_: Exception) {
            _state.update { it.copy(prsRefreshing = false) }
        }
    }

    // ─── PR 详情：加载 ───────────────────────────────────────────────────────

    fun openPRDetail(pr: GHPullRequest) = viewModelScope.launch {
        _state.update { it.copy(selectedPR = pr, prDetailLoading = true, prReviews = emptyList(), prComments = emptyList()) }
        try {
            val reviews  = repository.getPRReviews(owner, currentRepoName, pr.number)
            val comments = repository.getPRComments(owner, currentRepoName, pr.number)
            // 刷新 PR 本身（获取最新 mergeable 状态）
            val freshPR  = repository.getPRDetail(owner, currentRepoName, pr.number)
            _state.update { it.copy(selectedPR = freshPR, prReviews = reviews, prComments = comments, prDetailLoading = false) }
        } catch (_: Exception) {
            _state.update { it.copy(prDetailLoading = false) }
        }
    }

    fun closePRDetail() { _state.update { it.copy(selectedPR = null, prReviews = emptyList(), prComments = emptyList(), prOpResult = null) } }

    fun clearPROpResult() { _state.update { it.copy(prOpResult = null) } }

    // ─── PR 操作 ─────────────────────────────────────────────────────────────

    /** 合并 PR */
    fun mergePR(method: MergeMethod, commitTitle: String? = null, commitMessage: String? = null) = viewModelScope.launch {
        val pr = _state.value.selectedPR ?: return@launch
        _state.update { it.copy(prOpInProgress = true) }
        try {
            val resp = repository.mergePR(owner, currentRepoName, pr.number, method.apiValue, commitTitle, commitMessage)
            if (resp.merged) {
                repository.invalidatePRCache(owner, currentRepoName)
                loadPRsByFilter(forceRefresh = true)
                val freshPR = repository.getPRDetail(owner, currentRepoName, pr.number)
                _state.update { it.copy(selectedPR = freshPR, prOpResult = "✓ 合并成功", prOpInProgress = false) }
            } else {
                _state.update { it.copy(prOpResult = "合并失败: ${resp.message}", prOpInProgress = false) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(prOpResult = "合并失败: ${e.message}", prOpInProgress = false) }
        }
    }

    /** 更新分支（基分支有新提交时同步） */
    fun updatePRBranch() = viewModelScope.launch {
        val pr = _state.value.selectedPR ?: return@launch
        _state.update { it.copy(prOpInProgress = true) }
        try {
            repository.updatePRBranch(owner, currentRepoName, pr.number)
            _state.update { it.copy(prOpResult = "✓ 分支已更新", prOpInProgress = false) }
        } catch (e: Exception) {
            _state.update { it.copy(prOpResult = "更新失败: ${e.message}", prOpInProgress = false) }
        }
    }

    /** 提交审查（APPROVE / REQUEST_CHANGES / COMMENT） */
    fun submitReview(event: String, body: String) = viewModelScope.launch {
        val pr = _state.value.selectedPR ?: return@launch
        _state.update { it.copy(prOpInProgress = true) }
        try {
            val review = repository.createPRReview(owner, currentRepoName, pr.number, event, body.ifBlank { null })
            val label = when (event) {
                "APPROVE"          -> "✓ 已批准"
                "REQUEST_CHANGES"  -> "✓ 已请求修改"
                else               -> "✓ 评论已提交"
            }
            _state.update { it.copy(
                prReviews     = listOf(review) + it.prReviews,
                prOpResult    = label,
                prOpInProgress = false,
            ) }
        } catch (e: Exception) {
            _state.update { it.copy(prOpResult = "提交失败: ${e.message}", prOpInProgress = false) }
        }
    }

    /** 请求审查者 */
    fun requestReviewers(reviewers: List<String>) = viewModelScope.launch {
        val pr = _state.value.selectedPR ?: return@launch
        _state.update { it.copy(prOpInProgress = true) }
        try {
            val updated = repository.requestReviewers(owner, currentRepoName, pr.number, reviewers)
            _state.update { it.copy(selectedPR = updated, prOpResult = "✓ 审查请求已发送", prOpInProgress = false) }
        } catch (e: Exception) {
            _state.update { it.copy(prOpResult = "请求失败: ${e.message}", prOpInProgress = false) }
        }
    }

    /** 添加 PR 评论 */
    fun addPRComment(body: String) = viewModelScope.launch {
        val pr = _state.value.selectedPR ?: return@launch
        if (body.isBlank()) return@launch
        _state.update { it.copy(prOpInProgress = true) }
        try {
            val comment = repository.addPRComment(owner, currentRepoName, pr.number, body)
            _state.update { it.copy(
                prComments    = it.prComments + comment,
                prOpInProgress = false,
            ) }
        } catch (e: Exception) {
            _state.update { it.copy(prOpResult = "评论失败: ${e.message}", prOpInProgress = false) }
        }
    }

    /** 关闭 / 重新打开 PR */
    fun togglePRState() = viewModelScope.launch {
        val pr = _state.value.selectedPR ?: return@launch
        _state.update { it.copy(prOpInProgress = true) }
        try {
            val newState = if (pr.state == "open") "closed" else "open"
            val updated = ApiClient.api.updatePullRequest(owner, currentRepoName, pr.number, GHUpdatePullRequestRequest(state = newState))
            repository.invalidatePRCache(owner, currentRepoName)
            loadPRsByFilter(forceRefresh = true)
            _state.update { it.copy(selectedPR = updated, prOpResult = if (newState == "closed") "✓ PR 已关闭" else "✓ PR 已重新打开", prOpInProgress = false) }
        } catch (e: Exception) {
            _state.update { it.copy(prOpResult = "操作失败: ${e.message}", prOpInProgress = false) }
        }
    }

    /** 将草稿 PR 标记为就绪（必须用 GraphQL） */
    fun markPRReadyForReview() = viewModelScope.launch {
        val pr = _state.value.selectedPR ?: return@launch
        if (pr.nodeId.isBlank()) {
            _state.update { it.copy(prOpResult = "缺少 PR node_id，无法操作") }
            return@launch
        }
        _state.update { it.copy(prOpInProgress = true) }
        try {
            val token = GitMobApp.instance.tokenStorage.accessToken.first() ?: return@launch
            val ok = GraphQLClient.markPrReadyForReview(token, pr.nodeId)
            if (ok) {
                val freshPR = repository.getPRDetail(owner, currentRepoName, pr.number)
                repository.invalidatePRCache(owner, currentRepoName)
                loadPRsByFilter(forceRefresh = true)
                _state.update { it.copy(selectedPR = freshPR, prOpResult = "✓ 已标记为就绪", prOpInProgress = false) }
            } else {
                _state.update { it.copy(prOpResult = "标记失败", prOpInProgress = false) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(prOpResult = "操作失败: ${e.message}", prOpInProgress = false) }
        }
    }

    /** 创建 Pull Request */
    fun createPR(title: String, body: String, head: String, base: String, draft: Boolean) = viewModelScope.launch {
        _state.update { it.copy(prOpInProgress = true) }
        try {
            ApiClient.api.createPullRequest(
                owner, _state.value.currentRepoName,
                GHCreatePullRequestRequest(title, body.ifBlank { null }, head, base, draft),
            )
            repository.invalidatePRCache(owner, currentRepoName)
            loadPRsByFilter(forceRefresh = true)
            _state.update { it.copy(prOpResult = "✓ PR 已创建", prOpInProgress = false) }
        } catch (e: Exception) {
            _state.update { it.copy(prOpResult = "创建失败: ${e.message}", prOpInProgress = false) }
        }
    }

    /** 按当前筛选状态加载 Issues（第1页） */
    fun loadIssuesByState(forceRefresh: Boolean = false) = viewModelScope.launch {
        val filter = _state.value.issueFilterState
        val labelsStr = if (filter.selectedLabels.isNotEmpty()) filter.selectedLabels.joinToString(",") else null
        try {
            _state.update { it.copy(issuesPage = 1, issuesHasMore = false) }
            val issues = repository.getIssues(
                owner = owner,
                repo = currentRepoName,
                state = filter.status.apiValue,
                labels = labelsStr,
                creator = filter.selectedCreator,
                sort = filter.sortBy.sort,
                direction = filter.sortBy.direction,
                page = 1,
                forceRefresh = forceRefresh
            )
            _state.update { it.copy(
                issues = issues,
                issuesPage = 1,
                issuesHasMore = issues.size >= 30,
                issuesLoaded = true
            )}
        } catch (_: Exception) {}
    }

    /** 加载 Issues 下一页 */
    fun loadMoreIssues() = viewModelScope.launch {
        if (_state.value.issuesLoadingMore || !_state.value.issuesHasMore) return@launch
        val nextPage = _state.value.issuesPage + 1
        val filter = _state.value.issueFilterState
        val labelsStr = if (filter.selectedLabels.isNotEmpty()) filter.selectedLabels.joinToString(",") else null
        _state.update { it.copy(issuesLoadingMore = true) }
        try {
            val more = repository.getIssues(
                owner = owner,
                repo = currentRepoName,
                state = filter.status.apiValue,
                labels = labelsStr,
                creator = filter.selectedCreator,
                sort = filter.sortBy.sort,
                direction = filter.sortBy.direction,
                page = nextPage
            )
            _state.update { s -> s.copy(
                issues = s.issues + more,
                issuesPage = nextPage,
                issuesHasMore = more.size >= 30,
                issuesLoadingMore = false,
            )}
        } catch (_: Exception) {
            _state.update { it.copy(issuesLoadingMore = false) }
        }
    }

    /** 刷新分支列表（增量：按 name 合并，直接覆盖因为分支可能被删除） */
    fun refreshBranches() = viewModelScope.launch {
        _state.update { it.copy(branchesRefreshing = true) }
        try {
            val branches = repository.refreshBranchesIncremental(owner, currentRepoName)
            _state.update { it.copy(branches = branches, branchesRefreshing = false) }
        } catch (_: Exception) {
            _state.update { it.copy(branchesRefreshing = false) }
        }
    }

    /** 刷新 Issues（增量：按 number 合并，新条目优先） */
    fun refreshIssues() = viewModelScope.launch {
        _state.update { it.copy(issuesRefreshing = true) }
        try {
            val f      = _state.value.issueFilterState
            val labels = if (f.selectedLabels.isNotEmpty()) f.selectedLabels.joinToString(",") else null
            val fresh  = repository.refreshIssuesIncremental(
                owner, _state.value.currentRepoName, f.status.apiValue, labels, f.selectedCreator, f.sortBy.sort, f.sortBy.direction,
            )
            _state.update { it.copy(issues = fresh, issuesPage = 1, issuesHasMore = fresh.size >= 30, issuesRefreshing = false) }
        } catch (_: Exception) {
            _state.update { it.copy(issuesRefreshing = false) }
        }
    }

    fun deleteIssue(issueNumber: Int) = viewModelScope.launch {
        try {
            val success = repository.deleteIssue(owner, currentRepoName, issueNumber)
            if (success) {
                _state.update { it.copy(issues = it.issues.filter { issue -> issue.number != issueNumber }, toast = "已删除 Issue #$issueNumber") }
            } else {
                _state.update { it.copy(toast = "删除失败") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "删除失败：${e.message}") }
        }
    }

    /**
     * 获取所有可用的标签
     */
    fun getAllLabels(): Set<String> {
        return _state.value.labels.map { it.name }.toSet()
    }
    
    /**
     * 加载仓库的所有标签（包括默认标签）
     */
    fun loadLabels() = viewModelScope.launch {
        try {
            val labels = repository.getLabels(owner, currentRepoName)
            _state.update { it.copy(labels = labels) }
        } catch (_: Exception) {}
    }

    /**
     * 获取所有作者
     */
    fun getAllAuthors(): Set<String> {
        return _state.value.issues.map { it.user.login }.toSet()
    }

    /**
     * 获取所有受理人
     */
    fun getAllAssignees(): Set<String> {
        return emptySet()
    }

    /**
     * 获取所有里程碑
     */
    fun getAllMilestones(): Set<String> {
        return emptySet()
    }

    /**
     * 设置Issue状态筛选
     */
    fun setIssueStatusFilter(status: IssueStatusFilter) {
        _state.update { it.copy(issueFilterState = it.issueFilterState.copy(status = status)) }
        loadIssuesByState(forceRefresh = true)
    }

    /**
     * 设置Issue排序方式
     */
    fun setIssueSortBy(sortBy: IssueSortBy) {
        _state.update { it.copy(issueFilterState = it.issueFilterState.copy(sortBy = sortBy)) }
        loadIssuesByState(forceRefresh = true)
    }

    /**
     * 切换标签选择
     */
    fun toggleIssueLabel(label: String) {
        _state.update {
            val current = it.issueFilterState.selectedLabels
            val newSet = if (current.contains(label)) current - label else current + label
            it.copy(issueFilterState = it.issueFilterState.copy(selectedLabels = newSet))
        }
        loadIssuesByState(forceRefresh = true)
    }

    /**
     * 设置作者筛选
     */
    fun setIssueCreator(creator: String?) {
        _state.update { it.copy(issueFilterState = it.issueFilterState.copy(selectedCreator = creator)) }
        loadIssuesByState(forceRefresh = true)
    }

    /**
     * 清除所有筛选条件
     */
    fun clearIssueFilters() {
        _state.update { it.copy(issueFilterState = IssueFilterState()) }
    }

    /**
     * 创建新的Issue
     */
    fun createIssue(title: String, body: String) = viewModelScope.launch {
        try {
            val newIssue = repository.createIssue(owner, currentRepoName, title, body.ifBlank { null })
            _state.update {
                it.copy(
                    issues = listOf(newIssue) + it.issues,
                    toast = "Issue创建成功"
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "创建失败：${e.message}") }
        }
    }

    fun switchBranch(branch: String) {
        _state.update { it.copy(currentBranch = branch, currentPath = "") }
        loadContents("", branch)
        loadCommits(branch)
        loadReadme(owner, currentRepoName, branch)
    }

    /**
     * 加载 README 文件
     */
    fun loadReadme(owner: String, repo: String, ref: String) {
        viewModelScope.launch {
            _state.update { it.copy(readmeLoading = true, readmeContent = null) }
            try {
                // 尝试常见的 README 文件名
                val readmeNames = listOf("README.md", "README.MD", "Readme.md", "readme.md", "README")
                var content: String? = null
                for (name in readmeNames) {
                    try {
                        content = repository.getFileContent(owner, repo, name, ref)
                        break
                    } catch (e: Exception) {
                        continue
                    }
                }
                _state.update { it.copy(readmeLoading = false, readmeContent = content) }
            } catch (e: Exception) {
                _state.update { it.copy(readmeLoading = false, readmeContent = null) }
            }
        }
    }

    fun setTab(t: Int) = _state.update { it.copy(tab = t) }

    fun toggleStar() = viewModelScope.launch {
        try {
            if (_state.value.isStarred) repository.unstarRepo(owner, currentRepoName)
            else repository.starRepo(owner, currentRepoName)
            _state.update { it.copy(isStarred = !it.isStarred) }
        } catch (_: Exception) {}
    }

    fun renameRepo(newName: String, onSuccess: () -> Unit) = viewModelScope.launch {
        try {
            val currentRepoName = _state.value.currentRepoName
            val updated = repository.updateRepo(owner, currentRepoName, GHUpdateRepoRequest(name = newName))
            _state.update { it.copy(repo = updated, currentRepoName = newName, toast = "已重命名为 $newName") }
            onSuccess()
        } catch (e: Exception) {
            _state.update { it.copy(toast = "重命名失败：${e.message}") }
        }
    }

    fun editRepo(desc: String, website: String, topics: List<String>) = viewModelScope.launch {
        try {
            repository.updateRepo(owner, currentRepoName, GHUpdateRepoRequest(description = desc, homepage = website))
            repository.replaceTopics(owner, currentRepoName, topics)
            val updated = repository.getRepo(owner, currentRepoName, forceRefresh = true)
            _state.update { it.copy(repo = updated, toast = "已更新仓库信息") }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "更新失败：${e.message}") }
        }
    }

    /**
     * 计算当前仓库支持的 tab 数量
     */
    private fun calculateTabCount(repo: GHRepo?): Int {
        var count = 6 // 文件、提交、分支、操作、发行版、PR
        if (repo?.hasIssues != false) count++
        if (repo?.hasDiscussions == true) count++
        return count
    }

    /**
     * 确保 tab 索引有效
     */
    private fun ensureValidTabIndex(repo: GHRepo?, currentTab: Int): Int {
        val tabCount = calculateTabCount(repo)
        return if (currentTab >= tabCount) 0 else currentTab
    }

    fun toggleIssues(enable: Boolean) = viewModelScope.launch {
        try {
            repository.updateRepo(owner, currentRepoName, GHUpdateRepoRequest(hasIssues = enable))
            val updated = repository.getRepo(owner, currentRepoName, forceRefresh = true)
            val currentTab = _state.value.tab
            val validTab = ensureValidTabIndex(updated, currentTab)
            _state.update {
                it.copy(
                    repo = updated,
                    tab = validTab,
                    toast = if (enable) "已打开议题功能" else "已关闭议题功能",
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "更新失败：${e.message}") }
        }
    }

    fun toggleDiscussions(enable: Boolean) = viewModelScope.launch {
        try {
            repository.updateRepo(owner, currentRepoName, GHUpdateRepoRequest(hasDiscussions = enable))
            val updated = repository.getRepo(owner, currentRepoName, forceRefresh = true)
            val currentTab = _state.value.tab
            val validTab = ensureValidTabIndex(updated, currentTab)
            _state.update {
                it.copy(
                    repo = updated,
                    tab = validTab,
                    toast = if (enable) "已打开讨论功能" else "已关闭讨论功能",
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "更新失败：${e.message}") }
        }
    }

    fun updateVisibility(makePrivate: Boolean) = viewModelScope.launch {
        try {
            repository.updateRepo(owner, currentRepoName, GHUpdateRepoRequest(private = makePrivate))
            val updated = repository.getRepo(owner, currentRepoName, forceRefresh = true)
            _state.update {
                it.copy(
                    repo = updated,
                    toast = if (makePrivate) "已设置为私有仓库" else "已设置为公开仓库",
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "更新可见性失败：${e.message}") }
        }
    }

    fun deleteRepo(onSuccess: () -> Unit) = viewModelScope.launch {
        try {
            repository.deleteRepo(owner, currentRepoName)
            _state.update { it.copy(toast = "已删除仓库 $currentRepoName") }
            onSuccess()
        } catch (e: Exception) {
            _state.update { it.copy(toast = "删除失败：${e.message}") }
        }
    }

    fun transferRepo(targetOwner: String, newName: String?, onSuccess: () -> Unit) = viewModelScope.launch {
        try {
            repository.transferRepo(owner, currentRepoName, newOwner = targetOwner, newName = newName)
            _state.update {
                it.copy(
                    toast = if (newName.isNullOrBlank())
                        "已发起转移到 $targetOwner"
                    else
                        "已发起转移到 $targetOwner/$newName",
                )
            }
            onSuccess()
        } catch (e: Exception) {
            _state.update { it.copy(toast = "转移失败：${e.message}") }
        }
    }

    fun archiveRepo(onSuccess: () -> Unit) = viewModelScope.launch {
        try {
            repository.archiveRepo(owner, currentRepoName)
            val updated = repository.getRepo(owner, currentRepoName, forceRefresh = true)
            _state.update {
                it.copy(
                    repo = updated,
                    toast = "已归档仓库 $currentRepoName",
                )
            }
            onSuccess()
        } catch (e: Exception) {
            _state.update { it.copy(toast = "归档失败：${e.message}") }
        }
    }

    fun unarchiveRepo(onSuccess: () -> Unit) = viewModelScope.launch {
        try {
            repository.unarchiveRepo(owner, currentRepoName)
            val updated = repository.getRepo(owner, currentRepoName, forceRefresh = true)
            _state.update {
                it.copy(
                    repo = updated,
                    toast = "已取消归档仓库 $currentRepoName",
                )
            }
            onSuccess()
        } catch (e: Exception) {
            _state.update { it.copy(toast = "取消归档失败：${e.message}") }
        }
    }

    fun checkNameAvailability(
        owner: String,
        name: String,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val exists = repository.checkRepoExists(owner, name)
                // exists == true 表示已存在；我们需要“可用”= false
                onResult(!exists)
            } catch (_: Exception) {
                onResult(false)
            }
        }
    }

    fun createBranch(name: String) = viewModelScope.launch {
        try {
            val sha = _state.value.branches.find { it.name == _state.value.currentBranch }?.commit?.sha ?: return@launch
            repository.createBranch(owner, currentRepoName, name, sha)
            val branches = repository.getBranches(owner, currentRepoName, forceRefresh = true)
            _state.update { it.copy(branches = branches, toast = "已创建分支 $name") }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "创建失败：${e.message}") }
        }
    }

    fun deleteBranch(branch: String) = viewModelScope.launch {
        try {
            repository.deleteBranch(owner, currentRepoName, branch)
            val branches = repository.getBranches(owner, currentRepoName, forceRefresh = true)
            val filteredBranches = branches.filter { it.name != branch }
            val currentBranch = if (_state.value.currentBranch == branch) {
                filteredBranches.firstOrNull()?.name ?: _state.value.currentBranch
            } else _state.value.currentBranch
            _state.update { it.copy(branches = filteredBranches, currentBranch = currentBranch, toast = "已删除分支 $branch") }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "删除失败：${e.message}") }
        }
    }

    fun renameBranch(oldName: String, newName: String) = viewModelScope.launch {
        try {
            repository.renameBranch(owner, currentRepoName, oldName, newName)
            val branches = repository.getBranches(owner, currentRepoName, forceRefresh = true)
            val filteredBranches = branches.filter { it.name != oldName }
            val currentBranch = if (_state.value.currentBranch == oldName) newName else _state.value.currentBranch
            _state.update { it.copy(branches = filteredBranches, currentBranch = currentBranch, toast = "已重命名为 $newName") }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "重命名失败：${e.message}") }
        }
    }

    fun setDefaultBranch(branch: String) = viewModelScope.launch {
        try {
            repository.updateRepo(owner, currentRepoName, com.gitmob.android.api.GHUpdateRepoRequest(defaultBranch = branch))
            val updated = repository.getRepo(owner, currentRepoName, forceRefresh = true)
            _state.update { it.copy(repo = updated, currentBranch = branch, toast = "默认分支已设为 $branch") }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "设置失败：${e.message}") }
        }
    }

    fun loadCommitDetail(sha: String) = viewModelScope.launch {
        _state.update { it.copy(commitDetailLoading = true) }
        try {
            val detail = repository.getCommitDetail(owner, currentRepoName, sha)
            _state.update { it.copy(selectedCommit = detail, commitDetailLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(commitDetailLoading = false, toast = "加载详情失败") }
        }
    }

    fun clearCommitDetail() = _state.update { it.copy(selectedCommit = null, selectedFilePatch = null) }

    /**
     * 加载同步提交列表中提交的详情（支持加载上游仓库的提交）
     */
    fun loadForkSyncCommitDetail(commitOwner: String, commitRepo: String, sha: String) = viewModelScope.launch {
        _state.update { it.copy(commitDetailLoading = true) }
        try {
            val detail = repository.getCommitDetail(commitOwner, commitRepo, sha)
            _state.update { it.copy(selectedCommit = detail, commitDetailLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(commitDetailLoading = false, toast = "加载详情失败") }
        }
    }

    fun openFilePatch(info: FilePatchInfo) =
        _state.update { it.copy(selectedFilePatch = info) }

    fun closeFilePatch() = _state.update { it.copy(selectedFilePatch = null) }
    fun clearToast() = _state.update { it.copy(toast = null) }
    fun clearGitOpResult() = _state.update { it.copy(gitOpResult = null) }

    // 复刻仓库相关方法
    fun showCreateForkDialog() = _state.update {
        it.copy(
            showCreateForkDialog = true,
            createForkError = null,
            createForkSuccess = false
        )
    }

    fun hideCreateForkDialog() = _state.update {
        it.copy(
            showCreateForkDialog = false,
            createForkError = null,
            createForkSuccess = false
        )
    }

    /**
     * 创建复刻仓库
     */
    fun createFork(
        name: String? = null,
        organization: String? = null,
        defaultBranchOnly: Boolean = false,
    ) = viewModelScope.launch {
        _state.update {
            it.copy(
                creatingFork = true,
                createForkError = null,
                createForkSuccess = false
            )
        }

        try {
            val result = repository.createFork(
                owner = owner,
                repo = currentRepoName,
                name = name?.ifBlank { null },
                organization = organization?.ifBlank { null },
                defaultBranchOnly = defaultBranchOnly
            )
            _state.update {
                it.copy(
                    creatingFork = false,
                    createForkSuccess = true,
                    toast = "正在创建复刻，您稍后可以在您的仓库中查看"
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    creatingFork = false,
                    createForkError = e.message ?: "创建复刻失败"
                )
            }
        }
    }
    
    // 文件历史相关方法
    fun showFileHistory(content: GHContent) = viewModelScope.launch {
        _state.update { it.copy(showFileHistorySheet = true, selectedFileForHistory = content, fileHistoryLoading = true) }
        try {
            val commits = repository.getCommits(owner, currentRepoName, _state.value.currentBranch, content.path)
            _state.update { it.copy(fileHistoryCommits = commits, fileHistoryLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(fileHistoryCommits = emptyList(), fileHistoryLoading = false) }
        }
    }
    
    fun hideFileHistory() = _state.update { 
        it.copy(
            showFileHistorySheet = false, 
            fileHistoryCommits = emptyList(), 
            selectedFileForHistory = null, 
            selectedCommitForFileHistory = null
        ) 
    }
    
    fun loadFileHistoryCommitDetail(sha: String) = viewModelScope.launch {
        _state.update { it.copy(fileHistoryCommitDetailLoading = true) }
        try {
            val detail = repository.getCommitDetail(owner, currentRepoName, sha)
            _state.update { it.copy(selectedCommitForFileHistory = detail, fileHistoryCommitDetailLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(fileHistoryCommitDetailLoading = false, toast = "加载详情失败") }
        }
    }
    
    fun clearFileHistoryCommitDetail() = _state.update { it.copy(selectedCommitForFileHistory = null) }

    /**
     * 回滚：强制将当前分支的 HEAD 指向目标 SHA（服务端操作，重写历史）
     * 等同于 git reset --hard <sha> && git push -f origin <branch>
     */
    fun resetToCommit(sha: String) = viewModelScope.launch {
        val branch = _state.value.currentBranch.ifBlank { return@launch }
        _state.update { it.copy(gitOpInProgress = true) }
        try {
            repository.resetBranchToCommit(owner, currentRepoName, branch, sha)
            // 失效缓存，重新加载提交列表
            repository.invalidateCommitCache(owner, currentRepoName, branch)
            loadCommits(branch)
            _state.update {
                it.copy(
                    gitOpInProgress = false,
                    gitOpResult = GitOpResult(true, "回滚", "分支 $branch 已回滚到 ${sha.take(7)}"),
                    selectedCommit  = null,
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    gitOpInProgress = false,
                    gitOpResult = GitOpResult(false, "回滚", e.message ?: "回滚失败"),
                )
            }
        }
    }

    /**
     * 撤销（Revert）：在当前 HEAD 上创建一个新的 revert commit
     * 不重写历史，安全用于受保护分支
     */
    fun revertCommit(targetSha: String, commitMessage: String) = viewModelScope.launch {
        val branch = _state.value.currentBranch.ifBlank { return@launch }
        // 获取当前 HEAD SHA（从分支列表取）
        val headSha = _state.value.branches
            .find { it.name == branch }?.commit?.sha
            ?: return@launch
        _state.update { it.copy(gitOpInProgress = true) }
        try {
            val result = repository.revertCommit(
                owner, _state.value.currentRepoName, branch, targetSha, headSha, commitMessage,
            )
            repository.invalidateCommitCache(owner, currentRepoName, branch)
            loadCommits(branch)
            _state.update {
                it.copy(
                    gitOpInProgress = false,
                    gitOpResult = GitOpResult(
                        true, "撤销",
                        "已创建 revert commit ${result.sha.take(7)}",
                        newSha = result.sha,
                    ),
                    selectedCommit = null,
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    gitOpInProgress = false,
                    gitOpResult = GitOpResult(false, "撤销", e.message ?: "撤销失败"),
                )
            }
        }
    }

    // ─── Releases ─────────────────────────────────────────────────────────────
    fun loadReleases() = viewModelScope.launch {
        try {
            val releases = repository.getReleases(owner, currentRepoName)
            _state.update { it.copy(releases = releases) }
        } catch (_: Exception) {}
    }

    /** 刷新Releases列表（增量：按 id 合并） */
    fun refreshReleases() = viewModelScope.launch {
        _state.update { it.copy(releasesRefreshing = true) }
        try {
            val releases = repository.refreshReleasesIncremental(owner, currentRepoName)
            _state.update { it.copy(releases = releases, releasesRefreshing = false) }
        } catch (_: Exception) {
            _state.update { it.copy(releasesRefreshing = false) }
        }
    }

    /** 编辑发行版（PATCH /repos/{owner}/{repo}/releases/{releaseId}） */
    fun updateRelease(
        releaseId: Long, tagName: String, name: String, body: String,
        draft: Boolean, prerelease: Boolean,
        onSuccess: () -> Unit, onError: (String) -> Unit,
    ) = viewModelScope.launch {
        try {
            ApiClient.api.updateRelease(
                owner, _state.value.currentRepoName, releaseId,
                UpdateReleaseRequest(tagName, name, body, draft, prerelease)
            )
            refreshReleases()
            onSuccess()
        } catch (e: Exception) { onError(e.message ?: "编辑失败") }
    }

    /** 删除发行版（DELETE /repos/{owner}/{repo}/releases/{releaseId}） */
    fun deleteRelease(
        releaseId: Long, onSuccess: () -> Unit, onError: (String) -> Unit,
    ) = viewModelScope.launch {
        try {
            val resp = ApiClient.api.deleteRelease(owner, currentRepoName, releaseId)
            if (resp.isSuccessful) {
                _state.update { it.copy(releases = it.releases.filter { r -> r.id != releaseId }) }
                onSuccess()
            } else {
                onError("删除失败 (${resp.code()})")
            }
        } catch (e: Exception) { onError(e.message ?: "删除失败") }
    }

    // ─── 多文件上传（Git Data API，一次 commit）────────────────────────────────
    /**
     * 通过 Git Data API 将本地文件列表上传为单个 commit：
     * 1. GET  git/refs/heads/{branch}   → parentSha
     * 2. POST git/blobs × N (并发≤5)   → blob SHA 列表
     * 3. POST git/trees                 → tree SHA
     * 4. POST git/commits               → commit SHA
     * 5. PATCH git/refs/heads/{branch}  → 更新 branch
     *
     * @param fileEntries  List of Pair(本地绝对路径, 仓库相对路径)
     * @param commitMessage  commit 信息
     */
    fun uploadFiles(
        fileEntries: List<Pair<String, String>>,  // (localAbsPath, repoRelPath)
        commitMessage: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) = viewModelScope.launch {
        val branch = _state.value.currentBranch
        _state.update {
            it.copy(
                uploadPhase       = UploadPhase.BLOBS,
                uploadBlobProgress = 0,
                uploadBlobTotal   = fileEntries.size,
                uploadCurrentFile = "",
                uploadError       = null,
            )
        }
        try {
            // ─ Step 1: 获取分支 HEAD SHA（处理空仓库 409）────────────────────
            var parentSha: String? = null
            try {
                parentSha = ApiClient.api.getRef(owner, currentRepoName, branch).obj.sha
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 409) {
                    // 仓库为空（无任何 commit），分支不存在
                    // 策略：用 Contents API 创建第一个文件，它会自动建立分支和初始 commit
                    val firstEntry = fileEntries.first()
                    val bytes   = java.io.File(firstEntry.first).readBytes()
                    val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    _state.update { it.copy(uploadCurrentFile = java.io.File(firstEntry.first).name, uploadBlobProgress = 0) }
                    val resp = ApiClient.api.createOrUpdateFile(
                        owner, _state.value.currentRepoName, firstEntry.second,
                        GHCreateFileRequest(
                            message = commitMessage,
                            content = encoded,
                            branch  = null,  // 空仓库让 API 自动用默认分支名
                        )
                    )
                    if (fileEntries.size == 1) {
                        // 只有一个文件，直接完成
                        _state.update { it.copy(uploadPhase = UploadPhase.DONE, uploadBlobProgress = 1) }
                        loadContents(_state.value.currentPath, forceRefresh = true)
                        // 刷新仓库信息（分支现在存在了）
                        repository.getRepo(owner, currentRepoName, forceRefresh = true).let { repo ->
                            _state.update { s -> s.copy(repo = repo, currentBranch = repo.defaultBranch) }
                        }
                        onSuccess()
                        return@launch
                    }
                    // 还有更多文件：拿到第一个 commit 的 SHA 再继续
                    parentSha = resp.commit.sha
                    // 剩余文件走下面的正常流程
                    val remainingEntries = fileEntries.drop(1)
                    uploadRemainingWithParent(remainingEntries, commitMessage, parentSha, branch, 1, onSuccess, onError)
                    return@launch
                } else {
                    throw e
                }
            }

            // ─ Step 2: 并发创建 blobs（每批最多 5 个）────────────────────────
            val blobShas = mutableListOf<String>()
            val semaphore = kotlinx.coroutines.sync.Semaphore(5)
            kotlinx.coroutines.coroutineScope {
                val jobs = fileEntries.mapIndexed { idx, (localPath, repoPath) ->
                    async {
                        semaphore.withPermit {
                            _state.update {
                                it.copy(
                                    uploadCurrentFile  = java.io.File(localPath).name,
                                    uploadBlobProgress = idx,
                                )
                            }
                            val bytes   = java.io.File(localPath).readBytes()
                            val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            val resp    = ApiClient.api.createBlob(owner, currentRepoName, GHCreateBlobRequest(encoded))
                            resp.sha
                        }
                    }
                }
                val results = jobs.map { it.await() }
                blobShas.addAll(results)
            }
            _state.update { it.copy(uploadBlobProgress = fileEntries.size) }

            // ─ Step 3: 构建 tree ──────────────────────────────────────────────
            _state.update { it.copy(uploadPhase = UploadPhase.TREE) }
            val treeItems = fileEntries.mapIndexed { idx, (_, repoPath) ->
                GHTreeItem(path = repoPath, sha = blobShas[idx])
            }
            val treeResp = ApiClient.api.createTree(
                owner, _state.value.currentRepoName,
                GHCreateTreeRequest(tree = treeItems, baseTree = parentSha)
            )

            // ─ Step 4: 创建 commit ────────────────────────────────────────────
            _state.update { it.copy(uploadPhase = UploadPhase.COMMIT) }
            val commitResp = ApiClient.api.createCommit(
                owner, _state.value.currentRepoName,
                GHCreateCommitRequest(
                    message = commitMessage,
                    tree    = treeResp.sha,
                    parents = listOf(parentSha),
                )
            )

            // ─ Step 5: 更新分支 ref ───────────────────────────────────────────
            _state.update { it.copy(uploadPhase = UploadPhase.REF) }
            ApiClient.api.updateRef(owner, currentRepoName, branch, GHUpdateRefRequest(sha = commitResp.sha))

            // ─ 完成 ───────────────────────────────────────────────────────────
            _state.update { it.copy(uploadPhase = UploadPhase.DONE) }
            loadContents(_state.value.currentPath, forceRefresh = true)
            onSuccess()
        } catch (e: Exception) {
            _state.update { it.copy(uploadPhase = UploadPhase.ERROR, uploadError = e.message ?: "上传失败") }
            onError(e.message ?: "上传失败")
        }
    }

    /** 空仓库：第一个文件已用 Contents API 创建，剩余文件继续用 Git Data API 提交 */
    private fun uploadRemainingWithParent(
        remaining: List<Pair<String, String>>,
        commitMessage: String,
        parentSha: String,
        branch: String,
        doneCount: Int,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) = viewModelScope.launch {
        try {
            val blobShas = mutableListOf<String>()
            val semaphore = kotlinx.coroutines.sync.Semaphore(5)
            kotlinx.coroutines.coroutineScope {
                val jobs = remaining.mapIndexed { idx, (localPath, _) ->
                    async {
                        semaphore.withPermit {
                            _state.update { it.copy(uploadCurrentFile = java.io.File(localPath).name, uploadBlobProgress = doneCount + idx) }
                            val bytes = java.io.File(localPath).readBytes()
                            val enc   = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            ApiClient.api.createBlob(owner, currentRepoName, GHCreateBlobRequest(enc)).sha
                        }
                    }
                }
                blobShas.addAll(jobs.map { it.await() })
            }
            _state.update { it.copy(uploadBlobProgress = doneCount + remaining.size, uploadPhase = UploadPhase.TREE) }
            val treeItems = remaining.mapIndexed { idx, (_, repoPath) -> GHTreeItem(path = repoPath, sha = blobShas[idx]) }
            val treeResp = ApiClient.api.createTree(owner, currentRepoName, GHCreateTreeRequest(tree = treeItems, baseTree = parentSha))
            _state.update { it.copy(uploadPhase = UploadPhase.COMMIT) }
            val commitResp = ApiClient.api.createCommit(owner, currentRepoName,
                GHCreateCommitRequest(message = commitMessage, tree = treeResp.sha, parents = listOf(parentSha)))
            _state.update { it.copy(uploadPhase = UploadPhase.REF) }
            ApiClient.api.updateRef(owner, currentRepoName, branch, GHUpdateRefRequest(sha = commitResp.sha))
            _state.update { it.copy(uploadPhase = UploadPhase.DONE) }
            loadContents(_state.value.currentPath, forceRefresh = true)
            onSuccess()
        } catch (e: Exception) {
            _state.update { it.copy(uploadPhase = UploadPhase.ERROR, uploadError = e.message ?: "上传失败") }
            onError(e.message ?: "上传失败")
        }
    }


    /** 重置上传状态（关闭进度弹窗后调用） */
    fun resetUploadState() = _state.update {
        it.copy(
            uploadPhase       = UploadPhase.IDLE,
            uploadBlobProgress = 0,
            uploadBlobTotal   = 0,
            uploadCurrentFile = "",
            uploadError       = null,
        )
    }

    // ─── GitHub Actions ───────────────────────────────────────────────────────
    fun loadWorkflows() = viewModelScope.launch {
        try {
            val workflows = repository.getWorkflows(owner, currentRepoName)
            _state.update { it.copy(workflows = workflows) }
        } catch (_: Exception) {}
    }

    /** 刷新Actions工作流和运行记录（增量：runs 按 id 合并，新在前） */
    fun refreshActions() = viewModelScope.launch {
        _state.update { it.copy(actionsRefreshing = true) }
        try {
            val workflows = repository.getWorkflows(owner, currentRepoName)
            val freshRuns = repository.getWorkflowRuns(
                owner, _state.value.currentRepoName, _state.value.selectedWorkflow?.id, page = 1
            )
            // 增量合并：新条目优先，按 id 去重
            val cachedRuns = _state.value.allWorkflowRuns
            val mergedAll  = (freshRuns + cachedRuns).distinctBy { it.id }
            val mergedView = if (_state.value.selectedWorkflow == null) mergedAll
                             else (freshRuns + _state.value.workflowRuns).distinctBy { it.id }
            _state.update { it.copy(
                workflows            = workflows,
                allWorkflowRuns      = mergedAll,
                workflowRuns         = mergedView,
                workflowRunsPage     = 1,
                workflowRunsHasMore  = freshRuns.size >= 30,
                actionsRefreshing    = false,
            ) }
        } catch (_: Exception) {
            _state.update { it.copy(actionsRefreshing = false) }
        }
    }

    fun loadWorkflowRuns(workflowId: Long? = null) = viewModelScope.launch {
        try {
            val runs = repository.getWorkflowRuns(owner, currentRepoName, workflowId, page = 1)
            _state.update { it.copy(
                allWorkflowRuns = if (workflowId == null) runs else it.allWorkflowRuns,
                workflowRuns = runs,
                workflowRunsPage = 1,
                workflowRunsHasMore = runs.size >= 30
            )}
        } catch (_: Exception) {}
    }

    fun loadMoreWorkflowRuns() = viewModelScope.launch {
        if (_state.value.workflowRunsLoadingMore || !_state.value.workflowRunsHasMore) return@launch
        val nextPage = _state.value.workflowRunsPage + 1
        _state.update { it.copy(workflowRunsLoadingMore = true) }
        try {
            val more = repository.getWorkflowRuns(owner, currentRepoName, _state.value.selectedWorkflow?.id, page = nextPage)
            _state.update { s -> s.copy(
                workflowRuns = s.workflowRuns + more,
                workflowRunsPage = nextPage,
                workflowRunsHasMore = more.size >= 30,
                workflowRunsLoadingMore = false
            )}
        } catch (_: Exception) {
            _state.update { it.copy(workflowRunsLoadingMore = false) }
        }
    }

    fun selectWorkflow(workflow: GHWorkflow) = viewModelScope.launch {
        _state.update { it.copy(selectedWorkflow = workflow) }
        loadWorkflowRuns(workflow.id)
    }

    fun clearSelectedWorkflow() {
        _state.update { it.copy(selectedWorkflow = null) }
        loadWorkflowRuns(null)
    }

    fun selectWorkflowRun(run: GHWorkflowRun) = viewModelScope.launch {
        _state.update { it.copy(selectedWorkflowRun = run) }
        try {
            val jobs = repository.getWorkflowJobs(owner, currentRepoName, run.id)
            val artifacts = repository.getWorkflowRunArtifacts(owner, currentRepoName, run.id)
            _state.update { it.copy(workflowJobs = jobs, workflowArtifacts = artifacts) }
        } catch (_: Exception) {
            _state.update { it.copy(workflowJobs = emptyList(), workflowArtifacts = emptyList()) }
        }
    }

    fun clearSelectedWorkflowRun() {
        _state.update { it.copy(selectedWorkflowRun = null, workflowJobs = emptyList(), workflowArtifacts = emptyList()) }
    }

    fun deleteArtifact(artifactId: Long) = viewModelScope.launch {
        try {
            val success = repository.deleteArtifact(owner, currentRepoName, artifactId)
            if (success) {
                _state.update { it.copy(workflowArtifacts = it.workflowArtifacts.filter { it.id != artifactId }, toast = "已删除产物") }
            } else {
                _state.update { it.copy(toast = "删除失败") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "删除失败：${e.message}") }
        }
    }

    fun downloadArtifact(artifact: GHWorkflowArtifact) {
        val ctx = getApplication<Application>()
        // 直接在 url 后面添加 /zip 构造下载链接
        val apiUrl = "${artifact.url}/zip"
        val key = "artifact_${artifact.id}"
        val existing = _state.value.downloadTaskIds[key]
        val taskStatus = existing?.let { com.gitmob.android.util.GmDownloadManager.statusOf(it)?.value }
        if (taskStatus is com.gitmob.android.util.DownloadStatus.Progress) {
            com.gitmob.android.util.GmDownloadManager.cancel(ctx, existing)
            _state.update { it.copy(downloadTaskIds = it.downloadTaskIds - key) }
        } else {
            val id = com.gitmob.android.util.GmDownloadManager.download(ctx, apiUrl, "${artifact.name}.zip")
            _state.update { it.copy(downloadTaskIds = it.downloadTaskIds + (key to id), toast = "开始下载：${artifact.name}") }
        }
    }

    /** Release Asset API 下载（流式，带通知进度） */
    fun downloadAsset(asset: GHAsset) {
        val ctx = getApplication<Application>()
        val apiUrl = asset.url
        val key = "asset_${asset.id}"
        val existing = _state.value.downloadTaskIds[key]
        val taskStatus = existing?.let { com.gitmob.android.util.GmDownloadManager.statusOf(it)?.value }
        if (taskStatus is com.gitmob.android.util.DownloadStatus.Progress) {
            com.gitmob.android.util.GmDownloadManager.cancel(ctx, existing)
            _state.update { it.copy(downloadTaskIds = it.downloadTaskIds - key) }
        } else {
            val id = com.gitmob.android.util.GmDownloadManager.download(ctx, apiUrl, asset.name)
            _state.update { it.copy(downloadTaskIds = it.downloadTaskIds + (key to id), toast = "开始下载：${asset.name}") }
        }
    }

    fun dispatchWorkflow(
        workflowId: Long,
        ref: String,
        inputs: Map<String, Any>? = null,
    ) = viewModelScope.launch {
        try {
            val success = repository.dispatchWorkflow(owner, currentRepoName, workflowId, ref, inputs)
            if (success) {
                _state.update { it.copy(toast = "工作流已触发") }
                kotlinx.coroutines.delay(2000)
                loadWorkflowRuns()
            } else {
                _state.update { it.copy(toast = "触发工作流失败") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "触发工作流失败：${e.message}") }
        }
    }

    fun deleteWorkflowRun(runId: Long) = viewModelScope.launch {
        val currentState = _state.value
        _state.update { it.copy(
            workflowRuns = it.workflowRuns.filter { r -> r.id != runId },
            allWorkflowRuns = it.allWorkflowRuns.filter { r -> r.id != runId }
        )}
        
        try {
            val success = repository.deleteWorkflowRun(owner, currentRepoName, runId)
            if (success) {
                _state.update { it.copy(toast = "运行记录已删除") }
            } else {
                _state.update { it.copy(
                    workflowRuns = currentState.workflowRuns,
                    allWorkflowRuns = currentState.allWorkflowRuns,
                    toast = "删除失败"
                )}
            }
        } catch (e: Exception) {
            _state.update { it.copy(
                workflowRuns = currentState.workflowRuns,
                allWorkflowRuns = currentState.allWorkflowRuns,
                toast = "删除失败：${e.message}"
            )}
        }
    }

    fun rerunWorkflow(runId: Long) = viewModelScope.launch {
        try {
            val success = repository.rerunWorkflow(owner, currentRepoName, runId)
            if (success) {
                _state.update { it.copy(toast = "已重新运行") }
                kotlinx.coroutines.delay(2000)
                loadWorkflowRuns()
            } else {
                _state.update { it.copy(toast = "重新运行失败") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "重新运行失败：${e.message}") }
        }
    }

    fun cancelWorkflow(runId: Long) = viewModelScope.launch {
        try {
            val success = repository.cancelWorkflow(owner, currentRepoName, runId)
            if (success) {
                _state.update { it.copy(toast = "已取消") }
                kotlinx.coroutines.delay(2000)
                loadWorkflowRuns()
            } else {
                _state.update { it.copy(toast = "取消失败") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "取消失败：${e.message}") }
        }
    }

    fun loadWorkflowLogs(runId: Long) = viewModelScope.launch {
        _state.update { it.copy(workflowLogsLoading = true, workflowLogs = null) }
        try {
            val logs = repository.getWorkflowLogs(owner, currentRepoName, runId)
            _state.update { it.copy(workflowLogs = logs, workflowLogsLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(workflowLogs = null, workflowLogsLoading = false, toast = "加载日志失败：${e.message}") }
        }
    }

    fun clearWorkflowLogs() {
        _state.update { it.copy(workflowLogs = null) }
    }

    fun loadWorkflowInputs(workflowPath: String) = viewModelScope.launch {
        _state.update { it.copy(workflowInputsLoading = true, workflowInputs = emptyList()) }
        try {
            val inputs = repository.getWorkflowInputs(owner, currentRepoName, workflowPath, _state.value.currentBranch)
            _state.update { it.copy(workflowInputs = inputs, workflowInputsLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(workflowInputs = emptyList(), workflowInputsLoading = false) }
        }
    }

    fun clearWorkflowInputs() {
        _state.update { it.copy(workflowInputs = emptyList()) }
    }

    // ─── 文件操作 ──────────────────────────────────────────────────────────────

    /**
     * 创建或更新文件
     */
    fun createOrUpdateFile(
        path: String,
        message: String,
        content: String,
        sha: String? = null,
        onSuccess: () -> Unit = {},
    ) = viewModelScope.launch {
        try {
            repository.createOrUpdateFile(
                owner = owner,
                repo = currentRepoName,
                path = path,
                message = message,
                content = content,
                sha = sha,
                branch = _state.value.currentBranch,
            )
            loadContents(_state.value.currentPath, forceRefresh = true)
            // 若创建/更新的是 README 文件，立即重新加载 README 内容
            val isReadme = listOf("README.md", "README.MD", "Readme.md", "readme.md", "README")
                .any { path.equals(it, ignoreCase = true) || path.endsWith("/$it", ignoreCase = true) }
            if (isReadme) {
                loadReadme(owner, currentRepoName, _state.value.currentBranch)
            }
            _state.update { it.copy(toast = "操作成功") }
            onSuccess()
        } catch (e: Exception) {
            _state.update { it.copy(toast = "操作失败：${e.message}") }
        }
    }

    /**
     * 使用 Git Tree API 创建符号链接
     */
    fun createSymlinkWithGitTree(
        path: String,
        targetPath: String,
        message: String,
        onSuccess: () -> Unit = {},
    ) = viewModelScope.launch {
        try {
            val success = repository.createSymlink(
                owner = owner,
                repo = currentRepoName,
                branch = _state.value.currentBranch,
                path = path,
                targetPath = targetPath,
                message = message
            )
            if (success) {
                loadContents(_state.value.currentPath, forceRefresh = true)
                _state.update { it.copy(toast = "符号链接创建成功") }
                onSuccess()
            } else {
                _state.update { it.copy(toast = "符号链接创建失败") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "操作失败：${e.message}") }
        }
    }

    /**
     * 使用 Git Tree API 创建子模块
     */
    fun createSubmoduleWithGitTree(
        submodulePath: String,
        submoduleUrl: String,
        submoduleCommitSha: String,
        message: String,
        onSuccess: () -> Unit = {},
    ) = viewModelScope.launch {
        try {
            val success = repository.createSubmodule(
                owner = owner,
                repo = currentRepoName,
                branch = _state.value.currentBranch,
                submodulePath = submodulePath,
                submoduleUrl = submoduleUrl,
                submoduleCommitSha = submoduleCommitSha,
                message = message
            )
            if (success) {
                loadContents(_state.value.currentPath, forceRefresh = true)
                _state.update { it.copy(toast = "子模块创建成功") }
                onSuccess()
            } else {
                _state.update { it.copy(toast = "子模块创建失败") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "操作失败：${e.message}") }
        }
    }

    /**
     * 使用 Git Tree API 更新子模块 commit SHA
     */
    fun updateSubmoduleCommitWithGitTree(
        submodulePath: String,
        newCommitSha: String,
        message: String,
        onSuccess: () -> Unit = {},
    ) = viewModelScope.launch {
        try {
            val success = repository.updateSubmoduleCommit(
                owner = owner,
                repo = currentRepoName,
                branch = _state.value.currentBranch,
                submodulePath = submodulePath,
                newCommitSha = newCommitSha,
                message = message
            )
            if (success) {
                loadContents(_state.value.currentPath, forceRefresh = true)
                _state.update { it.copy(toast = "子模块 commit 已更新") }
                onSuccess()
            } else {
                _state.update { it.copy(toast = "更新失败") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "操作失败：${e.message}") }
        }
    }

    /**
     * 重命名文件
     */
    fun renameFile(
        oldPath: String,
        newPath: String,
        message: String,
        onSuccess: () -> Unit = {},
    ) = viewModelScope.launch {
        try {
            val oldFile = repository.getFileWithInfo(owner, currentRepoName, oldPath, _state.value.currentBranch)
            val contentBase64 = oldFile.info.content ?: ""
            val cleanContentBase64 = contentBase64.replace("\n", "").replace("\r", "")
            val token = GitMobApp.instance.tokenStorage.accessToken.first() ?: return@launch
            repository.renameFileGraphQL(
                token = token,
                owner = owner,
                repo = currentRepoName,
                branch = _state.value.currentBranch,
                oldPath = oldPath,
                newPath = newPath,
                contentBase64 = cleanContentBase64,
                message = message
            )
            loadContents(_state.value.currentPath, forceRefresh = true)
            _state.update { it.copy(toast = "文件已重命名") }
            onSuccess()
        } catch (e: Exception) {
            _state.update { it.copy(toast = "重命名失败：${e.message}") }
        }
    }

    /**
     * 删除文件
     */
    fun deleteFile(
        path: String,
        message: String,
        sha: String,
        contentType: String = "file",
        onSuccess: () -> Unit = {},
    ) = viewModelScope.launch {
        try {
            repository.deleteFile(
                owner = owner,
                repo = currentRepoName,
                path = path,
                message = message,
                sha = sha,
                branch = _state.value.currentBranch,
            )
            loadContents(_state.value.currentPath, forceRefresh = true)
            val toastMsg = when (contentType) {
                "file" -> "文件已删除"
                "dir" -> "文件夹已删除"
                "symlink" -> "符号链接已删除"
                "submodule" -> "子模块已删除"
                else -> "已删除"
            }
            _state.update { it.copy(toast = toastMsg) }
            onSuccess()
        } catch (e: Exception) {
            _state.update { it.copy(toast = "删除失败：${e.message}") }
        }
    }

    /**
     * 获取文件信息（包含sha）
     */
    fun getFileInfo(
        path: String,
        onSuccess: (GHContent) -> Unit = {},
        onError: (String) -> Unit = {},
    ) = viewModelScope.launch {
        try {
            val info = repository.getFileInfo(owner, currentRepoName, path, _state.value.currentBranch)
            onSuccess(info)
        } catch (e: Exception) {
            onError(e.message ?: "获取文件信息失败")
        }
    }

    // ── 仓库订阅（Watch）─────────────────────────────────────────────────────

    fun loadSubscription() = viewModelScope.launch {
        _state.update { it.copy(subscriptionLoading = true) }
        val sub = repository.getRepoSubscription(owner, currentRepoName)
        _state.update { it.copy(subscription = sub, subscriptionLoading = false) }
    }

    /**
     * 设置订阅模式（GitHub REST API 仅支持三档）：
     *   WatchMode.ALL_ACTIVITY  → subscribed=true,  ignored=false
     *   WatchMode.PARTICIPATING → deleteRepoSubscription（恢复默认行为）
     *   WatchMode.IGNORE        → subscribed=false, ignored=true
     */
    fun setWatchMode(mode: WatchMode) = viewModelScope.launch {
        _state.update { it.copy(subscriptionLoading = true) }
        try {
            val sub = when (mode) {
                WatchMode.ALL_ACTIVITY  -> repository.setRepoSubscription(owner, currentRepoName, true, false)
                WatchMode.IGNORE        -> repository.setRepoSubscription(owner, currentRepoName, false, true)
                WatchMode.PARTICIPATING -> {
                    repository.unsubscribeRepo(owner, currentRepoName)
                    null
                }
            }
            _state.update { it.copy(subscription = sub, subscriptionLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(subscriptionLoading = false, toast = "订阅设置失败：${e.message}") }
        }
    }

    // ── Issue Template 解析 ──────────────────────────────────────────────────

    fun loadIssueTemplates(onDone: () -> Unit = {}) = viewModelScope.launch {
        // 已有缓存直接回调
        if (_state.value.issueTemplates.isNotEmpty() && !_state.value.issueTemplatesLoading) {
            onDone()
            return@launch
        }
        _state.update { it.copy(issueTemplatesLoading = true) }
        val templates = repository.getIssueTemplates(owner, currentRepoName)
        _state.update { it.copy(issueTemplates = templates, issueTemplatesLoading = false) }
        onDone()
    }

    // ── 星标用户列表 ────────────────────────────────────────────────────────

    /**
     * 加载星标用户列表（第一页）
     */
    fun loadStargazers() = viewModelScope.launch {
        _state.update { it.copy(stargazersLoading = true, stargazersPage = 1, stargazersHasMore = false, stargazers = emptyList()) }
        try {
            val stargazers = repository.getStargazers(owner, currentRepoName, page = 1)
            _state.update { it.copy(
                stargazers = stargazers,
                stargazersPage = 1,
                stargazersHasMore = stargazers.size >= 50,
                stargazersLoading = false
            ) }
        } catch (e: Exception) {
            _state.update { it.copy(stargazersLoading = false, toast = "加载星标用户失败: ${e.message}") }
        }
    }

    /**
     * 加载更多星标用户
     */
    fun loadMoreStargazers() = viewModelScope.launch {
        if (_state.value.stargazersLoadingMore || !_state.value.stargazersHasMore) return@launch
        val nextPage = _state.value.stargazersPage + 1
        _state.update { it.copy(stargazersLoadingMore = true) }
        try {
            val more = repository.getStargazers(owner, currentRepoName, page = nextPage)
            _state.update { s -> s.copy(
                stargazers = s.stargazers + more,
                stargazersPage = nextPage,
                stargazersHasMore = more.size >= 50,
                stargazersLoadingMore = false,
            )}
        } catch (e: Exception) {
            _state.update { it.copy(stargazersLoadingMore = false) }
        }
    }

    /**
     * 显示星标用户列表弹窗
     */
    fun showStargazersSheet() {
        _state.update { it.copy(showStargazersSheet = true) }
        if (_state.value.stargazers.isEmpty()) {
            loadStargazers()
        }
    }

    /**
     * 隐藏星标用户列表弹窗
     */
    fun hideStargazersSheet() = _state.update { it.copy(showStargazersSheet = false) }

    // ── 复刻仓库列表 ────────────────────────────────────────────────────────

    /**
     * 加载复刻仓库列表（第一页）
     */
    fun loadForks() = viewModelScope.launch {
        _state.update { it.copy(forksLoading = true, forksPage = 1, forksHasMore = false, forks = emptyList()) }
        try {
            val forks = repository.getForks(owner, currentRepoName, sort = _state.value.forkSortBy.apiValue, page = 1)
            _state.update { it.copy(
                forks = forks,
                forksPage = 1,
                forksHasMore = forks.size >= 50,
                forksLoading = false
            ) }
        } catch (e: Exception) {
            _state.update { it.copy(forksLoading = false, toast = "加载复刻仓库失败: ${e.message}") }
        }
    }

    /**
     * 加载更多复刻仓库
     */
    fun loadMoreForks() = viewModelScope.launch {
        if (_state.value.forksLoadingMore || !_state.value.forksHasMore) return@launch
        val nextPage = _state.value.forksPage + 1
        _state.update { it.copy(forksLoadingMore = true) }
        try {
            val more = repository.getForks(owner, currentRepoName, sort = _state.value.forkSortBy.apiValue, page = nextPage)
            _state.update { s -> s.copy(
                forks = s.forks + more,
                forksPage = nextPage,
                forksHasMore = more.size >= 50,
                forksLoadingMore = false,
            )}
        } catch (e: Exception) {
            _state.update { it.copy(forksLoadingMore = false) }
        }
    }

    /**
     * 设置复刻列表排序方式并重新加载
     */
    fun setForkSortBy(sortBy: ForkSortBy) {
        _state.update { it.copy(forkSortBy = sortBy) }
        loadForks()
    }

    /**
     * 显示复刻仓库列表弹窗
     */
    fun showForksSheet() {
        _state.update { it.copy(showForksSheet = true) }
        if (_state.value.forks.isEmpty()) {
            loadForks()
        }
    }

    /**
     * 清除复刻筛选，恢复默认排序
     */
    fun clearForkFilters() {
        _state.update { it.copy(forkSortBy = ForkSortBy.NEWEST) }
        loadForks()
    }

    /**
     * 隐藏复刻仓库列表弹窗
     */
    fun hideForksSheet() = _state.update { it.copy(showForksSheet = false) }

    /**
     * 显示同步提交列表
     */
    fun showForkSyncCommits(type: ForkSyncCommitsType) {
        _state.update { it.copy(showForkSyncCommits = true, forkSyncCommitsType = type) }
    }

    /**
     * 隐藏同步提交列表
     */
    fun hideForkSyncCommits() = _state.update { it.copy(showForkSyncCommits = false) }

    /**
     * 处理 PR 更新事件
     */
    private fun handlePRUpdated(event: RepoUpdateEvent.PRUpdated) = viewModelScope.launch {
        LogManager.d("RepoDetailViewModel", "收到 PR 更新事件: owner=${event.owner}, repo=${event.repo}, number=${event.number}, state=${event.state}")
        try {
            LogManager.d("RepoDetailViewModel", "当前 owner=$owner, currentRepoName=$currentRepoName")
            val updatedPR = repository.getPRDetail(owner, currentRepoName, event.number)
            LogManager.d("RepoDetailViewModel", "获取到更新后的 PR: state=${updatedPR.state}, isOpen=${updatedPR.isOpen}, isMerged=${updatedPR.isMerged}, isClosed=${updatedPR.isClosed}")
            _state.update { state ->
                val filterState = state.prFilterState
                LogManager.d("RepoDetailViewModel", "当前筛选状态: ${filterState.status}")
                val shouldShow = when (filterState.status) {
                    com.gitmob.android.api.PRStatusFilter.OPEN -> updatedPR.isOpen
                    com.gitmob.android.api.PRStatusFilter.MERGED -> updatedPR.isMerged
                    com.gitmob.android.api.PRStatusFilter.CLOSED -> updatedPR.isClosed
                    com.gitmob.android.api.PRStatusFilter.ALL -> true
                }
                LogManager.d("RepoDetailViewModel", "shouldShow=$shouldShow")
                
                val newState = if (shouldShow) {
                    val newPrs = state.prs.map { pr ->
                        if (pr.number == event.number) {
                            LogManager.d("RepoDetailViewModel", "更新列表中的 PR #${event.number}")
                            updatedPR
                        } else {
                            pr
                        }
                    }
                    state.copy(prs = newPrs)
                } else {
                    LogManager.d("RepoDetailViewModel", "从列表中移除 PR #${event.number}")
                    val newPrs = state.prs.filter { pr -> pr.number != event.number }
                    state.copy(prs = newPrs)
                }
                LogManager.d("RepoDetailViewModel", "更新后的 prs 数量: ${newState.prs.size}")
                newState
            }
        } catch (e: Exception) {
            LogManager.e("RepoDetailViewModel", "更新 PR 失败", e)
        }
    }

    /**
     * 处理 Issue 更新事件
     */
    private fun handleIssueUpdated(event: RepoUpdateEvent.IssueUpdated) = viewModelScope.launch {
        LogManager.d("RepoDetailViewModel", "收到 Issue 更新事件: owner=${event.owner}, repo=${event.repo}, number=${event.number}, state=${event.state}")
        try {
            LogManager.d("RepoDetailViewModel", "当前 owner=$owner, currentRepoName=$currentRepoName")
            val updatedIssue = repository.getIssue(owner, currentRepoName, event.number)
            LogManager.d("RepoDetailViewModel", "获取到更新后的 Issue: state=${updatedIssue.state}")
            _state.update { state ->
                val filterState = state.issueFilterState
                LogManager.d("RepoDetailViewModel", "当前筛选状态: ${filterState.status}")
                val shouldShow = when (filterState.status) {
                    IssueStatusFilter.OPEN -> updatedIssue.state == "open"
                    IssueStatusFilter.CLOSED -> updatedIssue.state == "closed"
                    IssueStatusFilter.ALL -> true
                }
                LogManager.d("RepoDetailViewModel", "shouldShow=$shouldShow")
                
                val newState = if (shouldShow) {
                    val newIssues = state.issues.map { issue ->
                        if (issue.number == event.number) {
                            LogManager.d("RepoDetailViewModel", "更新列表中的 Issue #${event.number}")
                            updatedIssue
                        } else {
                            issue
                        }
                    }
                    state.copy(issues = newIssues)
                } else {
                    LogManager.d("RepoDetailViewModel", "从列表中移除 Issue #${event.number}")
                    val newIssues = state.issues.filter { issue -> issue.number != event.number }
                    state.copy(issues = newIssues)
                }
                LogManager.d("RepoDetailViewModel", "更新后的 issues 数量: ${newState.issues.size}")
                newState
            }
        } catch (e: Exception) {
            LogManager.e("RepoDetailViewModel", "更新 Issue 失败", e)
        }
    }

    companion object {
        fun factory(owner: String, repo: String): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras,
                ): T {
                    val app = checkNotNull(extras[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                    val handle = androidx.lifecycle.SavedStateHandle(mapOf("owner" to owner, "repo" to repo))
                    return RepoDetailViewModel(app, handle) as T
                }
            }
    }
}