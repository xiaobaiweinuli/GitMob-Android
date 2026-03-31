package com.gitmob.android.ui.repos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.android.api.GHOrg
import com.gitmob.android.api.GHRepo
import com.gitmob.android.api.GHUpdateRepoRequest
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.data.RepoRepository
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
    val error: String? = null,
    val userLogin: String = "",
    val userAvatar: String = "",
    val userOrgs: List<GHOrg> = emptyList(),
    val currentContext: OrgContext? = null,   // null = 用户自己
    val searchQuery: String = "",
    val filterState: RepoFilterState = RepoFilterState(),
    val toast: String? = null,
)

class RepoListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RepoRepository()
    private val tokenStorage = TokenStorage(app)
    private val _state = MutableStateFlow(RepoListState())
    val state = _state.asStateFlow()

    val filteredRepos = _state.map { s ->
        filterAndSortRepos(s.repos, s.searchQuery, s.filterState)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            tokenStorage.userProfile.collect { profile ->
                if (profile != null) {
                    _state.update { it.copy(userLogin = profile.first,
                        userAvatar = thumbUrl(profile.third).orEmpty()) }
                    loadOrgs()
                }
            }
        }
        loadRepos()
    }

    private fun loadOrgs() = viewModelScope.launch {
        try {
            val orgs = repo.getUserOrgs()
            _state.update { it.copy(userOrgs = orgs) }
        } catch (_: Exception) {}
    }

    fun switchContext(ctx: OrgContext?) {
        _state.update { it.copy(currentContext = ctx) }
        loadRepos(forceRefresh = true)
    }

    fun loadRepos(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val ctx = _state.value.currentContext
                val repos = if (ctx == null || ctx.isUser) {
                    repo.getMyRepos(forceRefresh)
                } else {
                    repo.getOrgRepos(ctx.login)
                }
                _state.update { it.copy(repos = repos, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun deleteRepo(owner: String, repoName: String) = viewModelScope.launch {
        try {
            repo.deleteRepo(owner, repoName)
            _state.update { s -> s.copy(
                repos = s.repos.filter { it.name != repoName || it.owner.login != owner },
                toast = "已删除 $repoName",
            )}
        } catch (e: Exception) {
            _state.update { it.copy(toast = "删除失败：${e.message}") }
        }
    }

    fun renameRepo(owner: String, oldName: String, newName: String) = viewModelScope.launch {
        try {
            val updated = repo.updateRepo(owner, oldName, GHUpdateRepoRequest(name = newName))
            _state.update { s -> s.copy(
                repos = s.repos.map { if (it.name == oldName && it.owner.login == owner) updated else it },
                toast = "已重命名为 $newName",
            )}
        } catch (e: Exception) {
            _state.update { it.copy(toast = "重命名失败：${e.message}") }
        }
    }

    fun editRepo(owner: String, repoName: String, desc: String, website: String, topics: List<String>) =
        viewModelScope.launch {
            try {
                repo.updateRepo(owner, repoName, GHUpdateRepoRequest(description = desc, homepage = website))
                repo.replaceTopics(owner, repoName, topics)
                val updated = repo.getRepo(owner, repoName)
                _state.update { s -> s.copy(
                    repos = s.repos.map { if (it.name == repoName && it.owner.login == owner) updated else it },
                    toast = "已更新仓库信息",
                )}
            } catch (e: Exception) {
                _state.update { it.copy(toast = "更新失败：${e.message}") }
            }
        }

    fun clearToast() = _state.update { it.copy(toast = null) }
    fun setSearch(q: String) = _state.update { it.copy(searchQuery = q) }
    
    /** 设置仓库类型筛选 */
    fun setTypeFilter(filter: RepoTypeFilter) = _state.update { 
        it.copy(filterState = it.filterState.copy(typeFilter = filter)) 
    }
    
    /** 设置排序方式 */
    fun setSortBy(sortBy: RepoSortBy) = _state.update { 
        it.copy(filterState = it.filterState.copy(sortBy = sortBy)) 
    }
    
    /** 设置语言筛选 */
    fun setLanguageFilter(language: String?) = _state.update { 
        it.copy(filterState = it.filterState.copy(languageFilter = language)) 
    }
    
    /** 清除所有筛选 */
    fun clearFilters() = _state.update { 
        it.copy(filterState = RepoFilterState()) 
    }
}

private fun thumbUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    return if (url.contains("?")) "$url&s=40" else "$url?s=40"
}
