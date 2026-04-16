package com.gitmob.android.ui.repos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.android.api.ApiClient
import com.gitmob.android.api.GHOrg
import com.gitmob.android.api.GHRepo
import com.gitmob.android.api.GHUpdateRepoRequest
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.data.RepoRepository
import com.gitmob.android.data.RepoUpdateEvent
import com.gitmob.android.data.RepoUpdateEventBus
import com.gitmob.android.util.LanguageEntry
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class OrgContext(
    val login: String,
    val avatarUrl: String?,
    val isUser: Boolean = true,
)

data class RepoListState(
    val repos: List<GHRepo> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasNextPage: Boolean = false,
    val endCursor: String? = null,
    // Search API 分页（语言筛选模式）
    val searchPage: Int = 1,
    val searchTotal: Int = 0,
    val isLangSearchMode: Boolean = false,
    val userLogin: String = "",
    val userAvatar: String = "",
    val userOrgs: List<GHOrg> = emptyList(),
    val currentContext: OrgContext? = null,
    val searchQuery: String = "",
    val filterState: RepoFilterState = RepoFilterState(),
    val toast: String? = null,
    val targetUserLogin: String? = null, // 用于查看其他用户的仓库
    val targetUserAvatar: String? = null, // 目标用户的头像
    val viewMode: ViewMode = ViewMode.REPOS, // 查看模式：仓库或星标
    val initialViewMode: ViewMode = ViewMode.REPOS, // 初始进入时的视图模式
)

enum class ViewMode {
    REPOS, // 查看仓库列表
    STARRED, // 查看星标列表
}

class RepoListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RepoRepository()
    private val tokenStorage = TokenStorage(app)
    private val _state = MutableStateFlow(RepoListState())
    val state = _state.asStateFlow()

    /** filteredRepos：语言筛选模式下 repos 已是 Search API 结果，只做类型/排序过滤 */
    val filteredRepos = _state.map { s ->
        filterAndSortRepos(s.repos, s.searchQuery, s.filterState)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            tokenStorage.userProfile.collect { profile ->
                if (profile != null) {
                    _state.update { it.copy(userLogin = profile.first,
                        userAvatar = thumbUrl(profile.third).orEmpty()) }
                }
            }
        }

        viewModelScope.launch {
            RepoUpdateEventBus.events.collect { event ->
                when (event) {
                    is RepoUpdateEvent.RepoUpdated -> {
                        try {
                            val updated = repo.getRepo(event.owner, event.repo, forceRefresh = true)
                            _state.update { s ->
                                s.copy(
                                    repos = s.repos.map {
                                        if (it.fullName == "${event.owner}/${event.repo}") updated else it
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            LogManager.e("RepoListViewModel", "更新仓库失败", e)
                        }
                    }
                    else -> {}
                }
            }
        }
        
        loadRepos()
    }

    private fun loadOrgs() = viewModelScope.launch {
        try { 
            val orgs = if (_state.value.targetUserLogin != null) {
                repo.getUserOrgs(_state.value.targetUserLogin!!)
            } else {
                repo.getUserOrgs()
            }
            _state.update { it.copy(userOrgs = orgs) } 
        } catch (_: Exception) {}
    }
    
    fun ensureOrgsLoaded() {
        if (_state.value.userOrgs.isEmpty()) {
            loadOrgs()
        }
    }

    fun switchContext(ctx: OrgContext?) {
        _state.update { it.copy(currentContext = ctx, filterState = it.filterState.copy(languageFilter = null)) }
        loadRepos(forceRefresh = false)
    }

    /**
     * 切换到查看其他用户的仓库
     */
    fun switchToUserRepos(login: String, avatarUrl: String? = null) {
        _state.update { 
            it.copy(
                targetUserLogin = login,
                targetUserAvatar = avatarUrl,
                viewMode = ViewMode.REPOS,
                // 只有当是第一次进入该用户页面时才设置 initialViewMode
                initialViewMode = if (it.targetUserLogin == null) ViewMode.REPOS else it.initialViewMode,
                currentContext = OrgContext(login, avatarUrl, isUser = true),
                userOrgs = emptyList(),
                repos = emptyList(), // 立即清空旧数据，避免显示之前的内容
                loading = true,
                filterState = it.filterState.copy(languageFilter = null)
            ) 
        }
        if (avatarUrl == null) {
            loadUserInfo(login)
        }
        loadRepos(forceRefresh = true)
    }

    /**
     * 切换到查看其他用户的星标
     */
    fun switchToUserStarred(login: String, avatarUrl: String? = null) {
        _state.update { 
            it.copy(
                targetUserLogin = login,
                targetUserAvatar = avatarUrl,
                viewMode = ViewMode.STARRED,
                // 只有当是第一次进入该用户页面时才设置 initialViewMode
                initialViewMode = if (it.targetUserLogin == null) ViewMode.STARRED else it.initialViewMode,
                currentContext = OrgContext(login, avatarUrl, isUser = true),
                userOrgs = emptyList(),
                repos = emptyList(), // 立即清空旧数据，避免显示之前的内容
                loading = true,
                filterState = it.filterState.copy(languageFilter = null)
            ) 
        }
        if (avatarUrl == null) {
            loadUserInfo(login)
        }
        loadRepos(forceRefresh = true)
    }

    /**
     * 切换到查看其他用户的组织
     */
    fun switchToUserOrgs(login: String, avatarUrl: String? = null) {
        _state.update { 
            it.copy(
                targetUserLogin = login,
                targetUserAvatar = avatarUrl,
                viewMode = ViewMode.REPOS,
                currentContext = null,
                userOrgs = emptyList(),
                filterState = it.filterState.copy(languageFilter = null)
            ) 
        }
        if (avatarUrl == null) {
            loadUserInfo(login)
        }
        loadOrgs()
    }

    /**
     * 加载用户信息以获取头像
     */
    private fun loadUserInfo(login: String) = viewModelScope.launch {
        try {
            val user = ApiClient.api.getUser(login)
            _state.update { 
                it.copy(
                    targetUserAvatar = user.avatarUrl,
                    currentContext = if (it.currentContext != null) {
                        OrgContext(login, user.avatarUrl, isUser = true)
                    } else {
                        null
                    }
                ) 
            }
        } catch (_: Exception) {}
    }

    /**
     * 重置回当前用户
     */
    fun resetToCurrentUser() {
        _state.update { 
            it.copy(
                targetUserLogin = null,
                targetUserAvatar = null,
                viewMode = ViewMode.REPOS,
                initialViewMode = ViewMode.REPOS,
                currentContext = null,
                userOrgs = emptyList(),
                repos = emptyList(), // 立即清空旧数据
                loading = true,
                filterState = it.filterState.copy(languageFilter = null)
            ) 
        }
        loadRepos(forceRefresh = true)
    }

    // ── 构建 Search API 的 q 参数 ─────────────────────────────────────────
    private fun buildSearchQ(langId: String): String {
        val state = _state.value
        val targetLogin = state.targetUserLogin
        val ctx = state.currentContext
        val login = targetLogin ?: ctx?.login ?: state.userLogin
        val prefix = if (targetLogin != null || (ctx == null || ctx.isUser)) "user:$login" else "org:$login"
        return "$prefix language:$langId"
    }

    // ── 加载逻辑：有语言筛选→Search API，无→GraphQL/REST ─────────────────
    fun loadRepos(forceRefresh: Boolean = false) {
        val langFilter = _state.value.filterState.languageFilter
        if (langFilter != null) {
            loadReposByLanguage(langFilter, page = 1, clear = true)
        } else {
            loadReposNormal(forceRefresh)
        }
    }

    private fun loadReposByLanguage(entry: LanguageEntry, page: Int, clear: Boolean) =
        viewModelScope.launch {
            if (clear) _state.update { it.copy(loading = true, repos = emptyList(), isLangSearchMode = true, searchPage = 1, searchTotal = 0) }
            try {
                val q = buildSearchQ(entry.id)
                val result = ApiClient.api.searchRepos(q, sort = "updated", perPage = 30, page = page)
                _state.update { s -> s.copy(
                    repos           = if (clear) result.items else s.repos + result.items,
                    loading         = false,
                    loadingMore     = false,
                    searchPage      = page,
                    searchTotal     = result.totalCount,
                    hasNextPage     = (page * 30) < result.totalCount,
                    isLangSearchMode = true,
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadingMore = false, error = e.message) }
            }
        }

    private fun loadReposNormal(forceRefresh: Boolean) = viewModelScope.launch {
        val ctx = _state.value.currentContext
        val targetLogin = _state.value.targetUserLogin
        val viewMode = _state.value.viewMode
        _state.update { it.copy(isLangSearchMode = false) }
        
        _state.update { it.copy(loading = true, error = null, repos = emptyList(), hasNextPage = false, endCursor = null) }
        try {
            val result = when {
                // 查看其他用户的仓库
                targetLogin != null && viewMode == ViewMode.REPOS && (ctx == null || ctx.isUser) -> {
                    repo.getUserRepos(targetLogin, forceRefresh = forceRefresh, page = 1)
                }
                // 查看其他用户的星标
                targetLogin != null && viewMode == ViewMode.STARRED -> {
                    repo.getUserStarred(targetLogin, forceRefresh = forceRefresh, page = 1)
                }
                // 查看组织仓库（无论是否是当前用户）
                ctx != null && !ctx.isUser -> {
                    if (forceRefresh) repo.refreshOrgReposIncremental(ctx.login)
                    else repo.getOrgRepos(ctx.login, cursor = null)
                }
                // 查看当前用户的仓库
                else -> {
                    if (forceRefresh) repo.refreshMyReposIncremental()
                    else repo.getMyRepos(forceRefresh = false, cursor = null)
                }
            }
            _state.update { it.copy(repos = result.repos, hasNextPage = result.hasNextPage, endCursor = result.endCursor, loading = false) }
        } catch (e: Exception) { _state.update { it.copy(loading = false, error = e.message ?: "加载失败") } }
    }

    fun loadMoreRepos() = viewModelScope.launch {
        if (_state.value.loadingMore || !_state.value.hasNextPage) return@launch
        _state.update { it.copy(loadingMore = true) }
        val s = _state.value
        val targetLogin = s.targetUserLogin
        val viewMode = s.viewMode
        if (s.isLangSearchMode) {
            val langFilter = s.filterState.languageFilter ?: return@launch
            loadReposByLanguage(langFilter, page = s.searchPage + 1, clear = false)
        } else {
            val ctx = s.currentContext
            try {
                val result = when {
                    targetLogin != null && viewMode == ViewMode.REPOS && (ctx == null || ctx.isUser) -> {
                        repo.getUserRepos(targetLogin, forceRefresh = false, page = (s.repos.size / 50) + 1)
                    }
                    targetLogin != null && viewMode == ViewMode.STARRED -> {
                        repo.getUserStarred(targetLogin, forceRefresh = false, page = (s.repos.size / 50) + 1)
                    }
                    ctx != null && !ctx.isUser -> {
                        repo.getOrgRepos(ctx.login, cursor = s.endCursor)
                    }
                    else -> {
                        repo.getMyRepos(forceRefresh = false, cursor = s.endCursor)
                    }
                }
                _state.update { it.copy(repos = it.repos + result.repos, hasNextPage = result.hasNextPage, endCursor = result.endCursor, loadingMore = false) }
            } catch (e: Exception) { _state.update { it.copy(loadingMore = false) } }
        }
    }

    fun deleteRepo(owner: String, repoName: String) = viewModelScope.launch {
        try {
            repo.deleteRepo(owner, repoName)
            _state.update { s -> s.copy(repos = s.repos.filter { it.name != repoName || it.owner.login != owner }, toast = "已删除 $repoName") }
        } catch (e: Exception) { _state.update { it.copy(toast = "删除失败：${e.message}") } }
    }

    fun renameRepo(owner: String, oldName: String, newName: String) = viewModelScope.launch {
        try {
            val updated = repo.updateRepo(owner, oldName, GHUpdateRepoRequest(name = newName))
            _state.update { s -> s.copy(repos = s.repos.map { if (it.name == oldName && it.owner.login == owner) updated else it }, toast = "已重命名为 $newName") }
        } catch (e: Exception) { _state.update { it.copy(toast = "重命名失败：${e.message}") } }
    }

    fun editRepo(owner: String, repoName: String, desc: String, website: String, topics: List<String>) = viewModelScope.launch {
        try {
            repo.updateRepo(owner, repoName, GHUpdateRepoRequest(description = desc, homepage = website))
            repo.replaceTopics(owner, repoName, topics)
            val updated = repo.getRepo(owner, repoName)
            _state.update { s -> s.copy(repos = s.repos.map { if (it.name == repoName && it.owner.login == owner) updated else it }, toast = "已更新仓库信息") }
        } catch (e: Exception) { _state.update { it.copy(toast = "更新失败：${e.message}") } }
    }

    fun archiveRepo(owner: String, repoName: String) = viewModelScope.launch {
        try {
            repo.archiveRepo(owner, repoName)
            val updated = repo.getRepo(owner, repoName)
            _state.update { s -> s.copy(repos = s.repos.map { if (it.name == repoName && it.owner.login == owner) updated else it }, toast = "已归档 $repoName") }
        } catch (e: Exception) { _state.update { it.copy(toast = "归档失败：${e.message}") } }
    }

    fun unarchiveRepo(owner: String, repoName: String) = viewModelScope.launch {
        try {
            repo.unarchiveRepo(owner, repoName)
            val updated = repo.getRepo(owner, repoName)
            _state.update { s -> s.copy(repos = s.repos.map { if (it.name == repoName && it.owner.login == owner) updated else it }, toast = "已取消归档 $repoName") }
        } catch (e: Exception) { _state.update { it.copy(toast = "取消归档失败：${e.message}") } }
    }

    fun clearToast() = _state.update { it.copy(toast = null) }
    fun setSearch(q: String) = _state.update { it.copy(searchQuery = q) }
    fun setTypeFilter(f: RepoTypeFilter) = _state.update { it.copy(filterState = it.filterState.copy(typeFilter = f)) }
    fun setSortBy(s: RepoSortBy) = _state.update { it.copy(filterState = it.filterState.copy(sortBy = s)) }

    /** 语言筛选：有值→切换到 Search API 模式；null→恢复正常加载 */
    fun setLanguageFilter(entry: LanguageEntry?) {
        _state.update { it.copy(filterState = it.filterState.copy(languageFilter = entry)) }
        loadRepos(forceRefresh = false)
    }

    fun clearFilters() {
        _state.update { it.copy(filterState = RepoFilterState()) }
        loadRepos(forceRefresh = false)
    }
}

private fun thumbUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    return if (url.contains("?")) "$url&s=40" else "$url?s=40"
}