package com.gitmob.android.ui.repo

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.android.api.*
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.data.RepoRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit


import kotlinx.coroutines.Job

class RepoDetailViewModel(app: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {

    val owner: String = savedStateHandle["owner"] ?: ""
    val repoName: String = savedStateHandle["repo"] ?: ""

    private val repository = RepoRepository()
    private val tokenStorage = TokenStorage(app)
    private val _state = MutableStateFlow(RepoDetailState())
    val state = _state.asStateFlow()

    // ── Job 去重：同类请求只保留最新一个，取消旧 Job 前先等其自然结束 ──────
    // 注意：不主动 cancel 旧 Job，让其继续执行直到 OkHttp 完成；
    // 通过 isActive 检查避免重复写入 State，而非通过 cancel 中断 OkHttp。
    private var loadAllJob: Job? = null
    private var contentsJob: Job? = null
    private var commitsJob: Job? = null

    init {
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
            val r = repository.getRepo(owner, repoName, forceRefresh)
            val branches = repository.getBranches(owner, repoName, forceRefresh)
            val starred = repository.isStarred(owner, repoName)
            _state.update { it.copy(repo = r, branches = branches, currentBranch = r.defaultBranch, isStarred = starred, loading = false) }
            loadContents("", r.defaultBranch, forceRefresh)
            loadCommits(r.defaultBranch, forceRefresh = forceRefresh)
            loadReadme(owner, repoName, r.defaultBranch)
            loadPRsAndIssues()
            loadReleases()
            loadWorkflows()
            loadWorkflowRuns(null)
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "加载失败") }
        }
        } // loadAllJob
    }

    fun loadContents(path: String, ref: String? = null, forceRefresh: Boolean = false) {
        // 同路径非强制刷新时去重
        if (!forceRefresh && contentsJob?.isActive == true && _state.value.currentPath == path) return
        contentsJob = viewModelScope.launch {
        _state.update { it.copy(contentsLoading = true, filesRefreshing = forceRefresh) }
        try {
            val branch = ref ?: _state.value.currentBranch
            val contents = repository.getContents(owner, repoName, path, branch, forceRefresh)
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
            val commits = repository.getCommits(owner, repoName, ref, path, forceRefresh)
            _state.update { it.copy(commits = commits, commitsRefreshing = false) }
        } catch (_: Exception) {
            _state.update { it.copy(commitsRefreshing = false) }
        }
    }

    private fun loadPRsAndIssues() = viewModelScope.launch {
        try {
            val prs = repository.getPRs(owner, repoName)
            _state.update { it.copy(prs = prs) }
        } catch (_: Exception) {}
        loadIssuesByState(forceRefresh = false)
    }

    /** 按当前筛选状态加载 Issues（第1页） */
    fun loadIssuesByState(forceRefresh: Boolean = false) = viewModelScope.launch {
        val stateStr = when (_state.value.issueFilterState.status) {
            IssueStatusFilter.OPEN   -> "open"
            IssueStatusFilter.CLOSED -> "closed"
            IssueStatusFilter.ALL    -> "all"
        }
        try {
            _state.update { it.copy(issuesPage = 1, issuesHasMore = false) }
            val issues = repository.getIssues(owner, repoName, state = stateStr, page = 1, forceRefresh = forceRefresh)
            _state.update { it.copy(
                issues = issues,
                issuesPage = 1,
                issuesHasMore = issues.size >= 30,
            )}
        } catch (_: Exception) {}
    }

    /** 加载 Issues 下一页 */
    fun loadMoreIssues() = viewModelScope.launch {
        if (_state.value.issuesLoadingMore || !_state.value.issuesHasMore) return@launch
        val nextPage = _state.value.issuesPage + 1
        val stateStr = when (_state.value.issueFilterState.status) {
            IssueStatusFilter.OPEN   -> "open"
            IssueStatusFilter.CLOSED -> "closed"
            IssueStatusFilter.ALL    -> "all"
        }
        _state.update { it.copy(issuesLoadingMore = true) }
        try {
            val more = repository.getIssues(owner, repoName, state = stateStr, page = nextPage)
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

    /** 刷新分支列表 */
    fun refreshBranches() = viewModelScope.launch {
        _state.update { it.copy(branchesRefreshing = true) }
        try {
            val branches = repository.getBranches(owner, repoName, forceRefresh = true)
            _state.update { it.copy(branches = branches, branchesRefreshing = false) }
        } catch (_: Exception) {
            _state.update { it.copy(branchesRefreshing = false) }
        }
    }

    /** 刷新PR列表 */
    fun refreshPRs() = viewModelScope.launch {
        _state.update { it.copy(prsRefreshing = true) }
        try {
            val prs = repository.getPRs(owner, repoName, forceRefresh = true)
            _state.update { it.copy(prs = prs, prsRefreshing = false) }
        } catch (_: Exception) {
            _state.update { it.copy(prsRefreshing = false) }
        }
    }

    /** 刷新Issues列表 */
    fun refreshIssues() = viewModelScope.launch {
        _state.update { it.copy(issuesRefreshing = true) }
        try {
            loadIssuesByState(forceRefresh = true).join()
        } catch (_: Exception) {}
        _state.update { it.copy(issuesRefreshing = false) }
    }

    fun deleteIssue(issueNumber: Int) = viewModelScope.launch {
        try {
            val success = repository.deleteIssue(owner, repoName, issueNumber)
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
        return _state.value.issues.flatMap { it.labels }.map { it.name }.toSet()
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
    }

    /**
     * 切换作者选择
     */
    fun toggleIssueAuthor(author: String) {
        _state.update {
            val current = it.issueFilterState.selectedAuthors
            val newSet = if (current.contains(author)) current - author else current + author
            it.copy(issueFilterState = it.issueFilterState.copy(selectedAuthors = newSet))
        }
    }

    /**
     * 切换受理人选择
     */
    fun toggleIssueAssignee(assignee: String) {
        _state.update {
            val current = it.issueFilterState.selectedAssignees
            val newSet = if (current.contains(assignee)) current - assignee else current + assignee
            it.copy(issueFilterState = it.issueFilterState.copy(selectedAssignees = newSet))
        }
    }

    /**
     * 切换里程碑选择
     */
    fun toggleIssueMilestone(milestone: String) {
        _state.update {
            val current = it.issueFilterState.selectedMilestones
            val newSet = if (current.contains(milestone)) current - milestone else current + milestone
            it.copy(issueFilterState = it.issueFilterState.copy(selectedMilestones = newSet))
        }
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
            val newIssue = repository.createIssue(owner, repoName, title, body.ifBlank { null })
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
        loadReadme(owner, repoName, branch)
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
            if (_state.value.isStarred) repository.unstarRepo(owner, repoName)
            else repository.starRepo(owner, repoName)
            _state.update { it.copy(isStarred = !it.isStarred) }
        } catch (_: Exception) {}
    }

    fun renameRepo(newName: String, onSuccess: () -> Unit) = viewModelScope.launch {
        try {
            val updated = repository.updateRepo(owner, repoName, GHUpdateRepoRequest(name = newName))
            _state.update { it.copy(repo = updated, toast = "已重命名为 $newName") }
            onSuccess()
        } catch (e: Exception) {
            _state.update { it.copy(toast = "重命名失败：${e.message}") }
        }
    }

    fun editRepo(desc: String, website: String, topics: List<String>) = viewModelScope.launch {
        try {
            repository.updateRepo(owner, repoName, GHUpdateRepoRequest(description = desc, homepage = website))
            repository.replaceTopics(owner, repoName, topics)
            val updated = repository.getRepo(owner, repoName, forceRefresh = true)
            _state.update { it.copy(repo = updated, toast = "已更新仓库信息") }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "更新失败：${e.message}") }
        }
    }

    fun updateVisibility(makePrivate: Boolean) = viewModelScope.launch {
        try {
            repository.updateRepo(owner, repoName, GHUpdateRepoRequest(private = makePrivate))
            val updated = repository.getRepo(owner, repoName, forceRefresh = true)
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
            repository.deleteRepo(owner, repoName)
            _state.update { it.copy(toast = "已删除仓库 $repoName") }
            onSuccess()
        } catch (e: Exception) {
            _state.update { it.copy(toast = "删除失败：${e.message}") }
        }
    }

    fun transferRepo(targetOwner: String, newName: String?, onSuccess: () -> Unit) = viewModelScope.launch {
        try {
            repository.transferRepo(owner, repoName, newOwner = targetOwner, newName = newName)
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
            repository.createBranch(owner, repoName, name, sha)
            val branches = repository.getBranches(owner, repoName, forceRefresh = true)
            _state.update { it.copy(branches = branches, toast = "已创建分支 $name") }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "创建失败：${e.message}") }
        }
    }

    fun deleteBranch(branch: String) = viewModelScope.launch {
        try {
            repository.deleteBranch(owner, repoName, branch)
            val branches = repository.getBranches(owner, repoName, forceRefresh = true)
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
            repository.renameBranch(owner, repoName, oldName, newName)
            val branches = repository.getBranches(owner, repoName, forceRefresh = true)
            val filteredBranches = branches.filter { it.name != oldName }
            val currentBranch = if (_state.value.currentBranch == oldName) newName else _state.value.currentBranch
            _state.update { it.copy(branches = filteredBranches, currentBranch = currentBranch, toast = "已重命名为 $newName") }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "重命名失败：${e.message}") }
        }
    }

    fun setDefaultBranch(branch: String) = viewModelScope.launch {
        try {
            repository.updateRepo(owner, repoName, com.gitmob.android.api.GHUpdateRepoRequest(defaultBranch = branch))
            val updated = repository.getRepo(owner, repoName, forceRefresh = true)
            _state.update { it.copy(repo = updated, currentBranch = branch, toast = "默认分支已设为 $branch") }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "设置失败：${e.message}") }
        }
    }

    fun loadCommitDetail(sha: String) = viewModelScope.launch {
        _state.update { it.copy(commitDetailLoading = true) }
        try {
            val detail = repository.getCommitDetail(owner, repoName, sha)
            _state.update { it.copy(selectedCommit = detail, commitDetailLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(commitDetailLoading = false, toast = "加载详情失败") }
        }
    }

    fun clearCommitDetail() = _state.update { it.copy(selectedCommit = null, selectedFilePatch = null) }

    fun openFilePatch(info: FilePatchInfo) =
        _state.update { it.copy(selectedFilePatch = info) }

    fun closeFilePatch() = _state.update { it.copy(selectedFilePatch = null) }
    fun clearToast() = _state.update { it.copy(toast = null) }
    fun clearGitOpResult() = _state.update { it.copy(gitOpResult = null) }
    
    // 文件历史相关方法
    fun showFileHistory(content: GHContent) = viewModelScope.launch {
        _state.update { it.copy(showFileHistorySheet = true, selectedFileForHistory = content, fileHistoryLoading = true) }
        try {
            val commits = repository.getCommits(owner, repoName, _state.value.currentBranch, content.path)
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
            val detail = repository.getCommitDetail(owner, repoName, sha)
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
            repository.resetBranchToCommit(owner, repoName, branch, sha)
            // 失效缓存，重新加载提交列表
            repository.invalidateCommitCache(owner, repoName, branch)
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
                owner, repoName, branch, targetSha, headSha, commitMessage,
            )
            repository.invalidateCommitCache(owner, repoName, branch)
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
            val releases = repository.getReleases(owner, repoName)
            _state.update { it.copy(releases = releases) }
        } catch (_: Exception) {}
    }

    /** 刷新Releases列表 */
    fun refreshReleases() = viewModelScope.launch {
        _state.update { it.copy(releasesRefreshing = true) }
        try {
            val releases = repository.getReleases(owner, repoName, forceRefresh = true)
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
                owner, repoName, releaseId,
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
            val resp = ApiClient.api.deleteRelease(owner, repoName, releaseId)
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
            // ─ Step 1: 获取分支 HEAD SHA ──────────────────────────────────────
            val parentSha = ApiClient.api.getRef(owner, repoName, branch).obj.sha

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
                            val resp    = ApiClient.api.createBlob(owner, repoName, GHCreateBlobRequest(encoded))
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
                owner, repoName,
                GHCreateTreeRequest(tree = treeItems, baseTree = parentSha)
            )

            // ─ Step 4: 创建 commit ────────────────────────────────────────────
            _state.update { it.copy(uploadPhase = UploadPhase.COMMIT) }
            val commitResp = ApiClient.api.createCommit(
                owner, repoName,
                GHCreateCommitRequest(
                    message = commitMessage,
                    tree    = treeResp.sha,
                    parents = listOf(parentSha),
                )
            )

            // ─ Step 5: 更新分支 ref ───────────────────────────────────────────
            _state.update { it.copy(uploadPhase = UploadPhase.REF) }
            ApiClient.api.updateRef(owner, repoName, branch, GHUpdateRefRequest(sha = commitResp.sha))

            // ─ 完成 ───────────────────────────────────────────────────────────
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
            val workflows = repository.getWorkflows(owner, repoName)
            _state.update { it.copy(workflows = workflows) }
        } catch (_: Exception) {}
    }

    /** 刷新Actions工作流和运行记录 */
    fun refreshActions() = viewModelScope.launch {
        _state.update { it.copy(actionsRefreshing = true) }
        try {
            val workflows = repository.getWorkflows(owner, repoName)
            val runs = repository.getWorkflowRuns(owner, repoName, null)
            _state.update { it.copy(
                workflows = workflows,
                allWorkflowRuns = runs,
                workflowRuns = runs,
                actionsRefreshing = false
            )}
        } catch (_: Exception) {
            _state.update { it.copy(actionsRefreshing = false) }
        }
    }

    fun loadWorkflowRuns(workflowId: Long? = null) = viewModelScope.launch {
        try {
            val runs = repository.getWorkflowRuns(owner, repoName, workflowId)
            _state.update { it.copy(allWorkflowRuns = runs, workflowRuns = runs) }
        } catch (_: Exception) {}
    }

    fun selectWorkflow(workflow: GHWorkflow) {
        val filteredRuns = _state.value.allWorkflowRuns.filter { it.workflowId == workflow.id }
        _state.update { it.copy(selectedWorkflow = workflow, workflowRuns = filteredRuns) }
    }

    fun clearSelectedWorkflow() {
        _state.update { it.copy(selectedWorkflow = null, workflowRuns = it.allWorkflowRuns) }
    }

    fun selectWorkflowRun(run: GHWorkflowRun) = viewModelScope.launch {
        _state.update { it.copy(selectedWorkflowRun = run) }
        try {
            val jobs = repository.getWorkflowJobs(owner, repoName, run.id)
            val artifacts = repository.getWorkflowRunArtifacts(owner, repoName, run.id)
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
            val success = repository.deleteArtifact(owner, repoName, artifactId)
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
        // 直接使用 API 返回的 archive_download_url，避免手动拼接出错
        val apiUrl = artifact.archiveDownloadUrl
        val key = "artifact_${artifact.id}"
        val existing = _state.value.downloadTaskIds[key]
        val taskStatus = existing?.let { com.gitmob.android.util.GmDownloadManager.statusOf(it)?.value }
        if (taskStatus is com.gitmob.android.util.DownloadStatus.Progress) {
            // 点击取消
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
        val apiUrl = asset.url.ifBlank {
            "https://api.github.com/repos/$owner/$repoName/releases/assets/${asset.id}"
        }
        val key = "asset_${asset.id}"
        val existing = _state.value.downloadTaskIds[key]
        val taskStatus = existing?.let { com.gitmob.android.util.GmDownloadManager.statusOf(it)?.value }
        if (taskStatus is com.gitmob.android.util.DownloadStatus.Progress) {
            // 点击取消
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
            val success = repository.dispatchWorkflow(owner, repoName, workflowId, ref, inputs)
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
        try {
            val success = repository.deleteWorkflowRun(owner, repoName, runId)
            if (success) {
                _state.update { it.copy(toast = "运行记录已删除") }
                loadWorkflowRuns()
            } else {
                _state.update { it.copy(toast = "删除失败") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "删除失败：${e.message}") }
        }
    }

    fun rerunWorkflow(runId: Long) = viewModelScope.launch {
        try {
            val success = repository.rerunWorkflow(owner, repoName, runId)
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
            val success = repository.cancelWorkflow(owner, repoName, runId)
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
            val logs = repository.getWorkflowLogs(owner, repoName, runId)
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
            val inputs = repository.getWorkflowInputs(owner, repoName, workflowPath, _state.value.currentBranch)
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
                repo = repoName,
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
                loadReadme(owner, repoName, _state.value.currentBranch)
            }
            _state.update { it.copy(toast = "操作成功") }
            onSuccess()
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
            val oldFile = repository.getFileInfo(owner, repoName, oldPath, _state.value.currentBranch)
            repository.createOrUpdateFile(
                owner = owner,
                repo = repoName,
                path = newPath,
                message = message,
                content = oldFile.content ?: "",
                sha = null,
                branch = _state.value.currentBranch,
            )
            repository.deleteFile(
                owner = owner,
                repo = repoName,
                path = oldPath,
                message = message,
                sha = oldFile.sha,
                branch = _state.value.currentBranch,
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
        onSuccess: () -> Unit = {},
    ) = viewModelScope.launch {
        try {
            repository.deleteFile(
                owner = owner,
                repo = repoName,
                path = path,
                message = message,
                sha = sha,
                branch = _state.value.currentBranch,
            )
            loadContents(_state.value.currentPath, forceRefresh = true)
            _state.update { it.copy(toast = "文件已删除") }
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
            val info = repository.getFileInfo(owner, repoName, path, _state.value.currentBranch)
            onSuccess(info)
        } catch (e: Exception) {
            onError(e.message ?: "获取文件信息失败")
        }
    }

    // ── 仓库订阅（Watch）─────────────────────────────────────────────────────

    fun loadSubscription() = viewModelScope.launch {
        _state.update { it.copy(subscriptionLoading = true) }
        val sub = repository.getRepoSubscription(owner, repoName)
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
                WatchMode.ALL_ACTIVITY  -> repository.setRepoSubscription(owner, repoName, true, false)
                WatchMode.IGNORE        -> repository.setRepoSubscription(owner, repoName, false, true)
                WatchMode.PARTICIPATING -> {
                    repository.unsubscribeRepo(owner, repoName)
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
        val templates = repository.getIssueTemplates(owner, repoName)
        _state.update { it.copy(issueTemplates = templates, issueTemplatesLoading = false) }
        onDone()
    }

    // ── 星标用户列表 ────────────────────────────────────────────────────────

    /**
     * 加载星标用户列表
     */
    fun loadStargazers() = viewModelScope.launch {
        _state.update { it.copy(stargazersLoading = true) }
        try {
            val stargazers = repository.getStargazers(owner, repoName)
            _state.update { it.copy(stargazers = stargazers, stargazersLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(stargazersLoading = false, toast = "加载星标用户失败: ${e.message}") }
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
     * 加载复刻仓库列表
     */
    fun loadForks() = viewModelScope.launch {
        _state.update { it.copy(forksLoading = true) }
        try {
            val forks = repository.getForks(owner, repoName, sort = _state.value.forkSortBy.apiValue)
            _state.update { it.copy(originalForks = forks, forks = applyForkFilters(forks), forksLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(forksLoading = false, toast = "加载复刻仓库失败: ${e.message}") }
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
     * 设置复刻仓库期限筛选
     */
    fun setForkTimeFilter(filter: ForkTimeFilter) {
        _state.update { it.copy(forkTimeFilter = filter) }
        applyFilters()
    }

    /**
     * 设置复刻仓库类型筛选
     */
    fun toggleForkTypeFilter(filter: ForkTypeFilter) {
        val currentFilters = _state.value.forkTypeFilters
        val newFilters = if (currentFilters.contains(filter)) {
            currentFilters - filter
        } else {
            currentFilters + filter
        }
        _state.update { it.copy(forkTypeFilters = newFilters) }
        applyFilters()
    }

    /**
     * 设置复刻仓库排序方式
     */
    fun setForkOrderBy(orderBy: ForkOrderBy) {
        _state.update { it.copy(forkOrderBy = orderBy) }
        applyFilters()
    }

    /**
     * 清除所有筛选
     */
    fun clearForkFilters() {
        _state.update { 
            it.copy(
                forkTimeFilter = ForkTimeFilter.ALL,
                forkTypeFilters = emptySet(),
                forkOrderBy = ForkOrderBy.RECENTLY_UPDATED,
                forks = it.originalForks
            )
        }
    }

    /**
     * 应用筛选和排序
     */
    private fun applyFilters() {
        val filtered = applyForkFilters(_state.value.originalForks)
        _state.update { it.copy(forks = filtered) }
    }

    /**
     * 应用筛选逻辑
     */
    private fun applyForkFilters(forks: List<GHRepo>): List<GHRepo> {
        var result = forks

        // 期限筛选
        if (_state.value.forkTimeFilter != ForkTimeFilter.ALL) {
            val cutoffTime = System.currentTimeMillis() - (_state.value.forkTimeFilter.months * 30L * 24L * 60L * 60L * 1000L)
            result = result.filter { fork ->
                fork.updatedAt?.let { updatedAt ->
                    try {
                        val date = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).parse(updatedAt)
                        date?.time ?: 0 >= cutoffTime
                    } catch (e: Exception) {
                        true
                    }
                } ?: true
            }
        }

        // 类型筛选
        if (_state.value.forkTypeFilters.isNotEmpty()) {
            result = result.filter { fork ->
                _state.value.forkTypeFilters.any { type ->
                    when (type) {
                        ForkTypeFilter.ACTIVE -> !fork.archived && fork.updatedAt != null
                        ForkTypeFilter.INACTIVE -> fork.archived || fork.updatedAt == null
                        ForkTypeFilter.NETWORK -> true
                        ForkTypeFilter.ARCHIVED -> fork.archived
                        ForkTypeFilter.STARRED -> fork.stars > 0
                    }
                }
            }
        }

        // 排序
        result = when (_state.value.forkOrderBy) {
            ForkOrderBy.RECENTLY_UPDATED -> result.sortedByDescending { it.updatedAt }
            ForkOrderBy.MOST_STARS -> result.sortedByDescending { it.stars }
            ForkOrderBy.OPEN_ISSUES -> result.sortedByDescending { it.openIssues }
            ForkOrderBy.OPEN_PULL_REQUESTS -> result.sortedByDescending { it.openIssues }
        }

        return result
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
     * 隐藏复刻仓库列表弹窗
     */
    fun hideForksSheet() = _state.update { it.copy(showForksSheet = false) }

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