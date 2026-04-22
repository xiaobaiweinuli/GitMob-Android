package com.gitmob.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.android.GitMobApp
import com.gitmob.android.api.ApiClient
import com.gitmob.android.api.GHCodeResult
import com.gitmob.android.api.GHIssue
import com.gitmob.android.api.GHRepo
import com.gitmob.android.api.GHSearchUser
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchCategory(val label: String) {
    ALL("全部"), REPOS("仓库"), CODE("代码"), ISSUES("议题"), PRS("拉取请求"), USERS("人员"), ORGS("组织"),
}

data class SearchState(
    val query: String = "",
    val category: SearchCategory = SearchCategory.ALL,
    val repoResults: List<GHRepo> = emptyList(),
    val codeResults: List<GHCodeResult> = emptyList(),
    val issueResults: List<GHIssue> = emptyList(),
    val prResults: List<GHIssue> = emptyList(),
    val userResults: List<GHSearchUser> = emptyList(),
    val orgResults: List<GHSearchUser> = emptyList(),
    val repoTotal: Int = 0, val codeTotal: Int = 0,
    val issueTotal: Int = 0, val prTotal: Int = 0,
    val userTotal: Int = 0, val orgTotal: Int = 0,
    val repoPage: Int = 1, val codePage: Int = 1,
    val issuePage: Int = 1, val prPage: Int = 1,
    val userPage: Int = 1, val orgPage: Int = 1,
    val repoHasMore: Boolean = false, val codeHasMore: Boolean = false,
    val issueHasMore: Boolean = false, val prHasMore: Boolean = false,
    val userHasMore: Boolean = false, val orgHasMore: Boolean = false,
    val repoLoadingMore: Boolean = false, val codeLoadingMore: Boolean = false,
    val issueLoadingMore: Boolean = false, val prLoadingMore: Boolean = false,
    val userLoadingMore: Boolean = false, val orgLoadingMore: Boolean = false,
    val loadingRepos: Boolean = false, val loadingCode: Boolean = false,
    val loadingIssues: Boolean = false, val loadingPrs: Boolean = false,
    val loadingUsers: Boolean = false, val loadingOrgs: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
)

class SearchViewModel : ViewModel() {
    private val api get() = ApiClient.api
    private val tokenStorage = GitMobApp.instance.tokenStorage
    private val tag = "SearchVM"
    val state = MutableStateFlow(SearchState())

    val searchHistory: StateFlow<List<String>> = flow {
        tokenStorage.searchHistoryJson.collect { raw ->
            val list = raw.removeSurrounding("[", "]")
                .split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotBlank() }
            emit(list)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var searchJob: Job? = null
    private var debounceJob: Job? = null

    /**
     * 只更新查询内容，不执行搜索
     */
    fun updateQuery(q: String) {
        state.update { it.copy(query = q, error = null) }
        if (q.isBlank()) state.update { SearchState() }
    }

    fun setQuery(q: String) {
        updateQuery(q)
    }

    fun setCategory(cat: SearchCategory) {
        state.update { it.copy(category = cat) }
    }

    /**
     * 搜索（只有用户回车或点击搜索按钮时才触发）
     */
    fun search(query: String = state.value.query) {
        val q = query.trim(); if (q.isBlank()) return
        
        searchJob?.cancel()
        performSearch(q)
    }

    /**
     * 搜索单个分类（用于 SearchResultsScreen）
     */
    fun searchSingleCategory(query: String, category: SearchCategory) {
        val q = query.trim(); if (q.isBlank()) return

        // 如果已有该分类的搜索结果，就不重新搜索
        if (state.value.query == q && state.value.hasSearched) {
            val hasResults = when (category) {
                SearchCategory.REPOS -> state.value.repoResults.isNotEmpty()
                SearchCategory.CODE -> state.value.codeResults.isNotEmpty()
                SearchCategory.ISSUES -> state.value.issueResults.isNotEmpty()
                SearchCategory.PRS -> state.value.prResults.isNotEmpty()
                SearchCategory.USERS -> state.value.userResults.isNotEmpty()
                SearchCategory.ORGS -> state.value.orgResults.isNotEmpty()
                SearchCategory.ALL -> false
            }
            if (hasResults) {
                state.update { it.copy(category = category) }
                return
            }
        }
        
        searchJob?.cancel()
        
        state.update { SearchState(query = q, category = category, hasSearched = true) }
        viewModelScope.launch { tokenStorage.addSearchHistory(q) }
        
        searchJob = viewModelScope.launch {
            when (category) {
                SearchCategory.REPOS -> { 
                    state.update { it.copy(loadingRepos = true) }
                    searchRepos(q, 1)
                }
                SearchCategory.CODE -> { 
                    state.update { it.copy(loadingCode = true) }
                    searchCode(q, 1)
                }
                SearchCategory.ISSUES -> { 
                    state.update { it.copy(loadingIssues = true) }
                    searchIssues(q, 1)
                }
                SearchCategory.PRS -> { 
                    state.update { it.copy(loadingPrs = true) }
                    searchPrs(q, 1)
                }
                SearchCategory.USERS -> { 
                    state.update { it.copy(loadingUsers = true) }
                    searchUsers(q, 1)
                }
                SearchCategory.ORGS -> { 
                    state.update { it.copy(loadingOrgs = true) }
                    searchOrgs(q, 1)
                }
                SearchCategory.ALL -> { /* 不会到这里 */ }
            }
        }
    }

    private fun performSearch(q: String) {
        val category = state.value.category
        state.update { SearchState(
            query = q, 
            category = category,
            hasSearched = true,
            loadingRepos = category == SearchCategory.ALL || category == SearchCategory.REPOS,
            loadingCode = category == SearchCategory.ALL || category == SearchCategory.CODE,
            loadingIssues = category == SearchCategory.ALL || category == SearchCategory.ISSUES,
            loadingPrs = category == SearchCategory.ALL || category == SearchCategory.PRS,
            loadingUsers = category == SearchCategory.ALL || category == SearchCategory.USERS,
            loadingOrgs = category == SearchCategory.ALL || category == SearchCategory.ORGS
        ) }
        viewModelScope.launch { tokenStorage.addSearchHistory(q) }
        
        searchJob = viewModelScope.launch {
            if (state.value.category == SearchCategory.ALL) {
                // 全部搜索
                launch { searchRepos(q, 1) }
                launch { searchCode(q, 1) }
                launch { searchIssues(q, 1) }
                launch { searchPrs(q, 1) }
                launch { searchUsers(q, 1) }
                launch { searchOrgs(q, 1) }
            } else {
                // 单个分类搜索
                when (state.value.category) {
                    SearchCategory.REPOS -> searchRepos(q, 1)
                    SearchCategory.CODE -> searchCode(q, 1)
                    SearchCategory.ISSUES -> searchIssues(q, 1)
                    SearchCategory.PRS -> searchPrs(q, 1)
                    SearchCategory.USERS -> searchUsers(q, 1)
                    SearchCategory.ORGS -> searchOrgs(q, 1)
                    SearchCategory.ALL -> {}
                }
            }
        }
    }

    private suspend fun searchRepos(q: String, page: Int) {
        try {
            val r = api.searchRepos(q, perPage = 20, page = page)
            if (page == 1) state.update { it.copy(repoResults = r.items, repoTotal = r.totalCount, repoPage = 1, repoHasMore = r.totalCount > 20, loadingRepos = false) }
            else state.update { s -> s.copy(repoResults = s.repoResults + r.items, repoPage = page, repoHasMore = s.repoResults.size + r.items.size < s.repoTotal, repoLoadingMore = false) }
        } catch (e: Exception) { LogManager.e(tag, "searchRepos", e); state.update { it.copy(loadingRepos = false, repoLoadingMore = false) } }
    }

    private suspend fun searchCode(q: String, page: Int) {
        try {
            val r = api.searchCode(q, perPage = 20, page = page)
            if (page == 1) state.update { it.copy(codeResults = r.items, codeTotal = r.totalCount, codePage = 1, codeHasMore = r.totalCount > 20, loadingCode = false) }
            else state.update { s -> s.copy(codeResults = s.codeResults + r.items, codePage = page, codeHasMore = s.codeResults.size + r.items.size < s.codeTotal, codeLoadingMore = false) }
        } catch (e: Exception) { LogManager.e(tag, "searchCode", e); state.update { it.copy(loadingCode = false, codeLoadingMore = false) } }
    }

    private suspend fun searchIssues(q: String, page: Int) {
        try {
            val r = api.searchIssues("$q type:issue", perPage = 20, page = page)
            if (page == 1) state.update { it.copy(issueResults = r.items, issueTotal = r.totalCount, issuePage = 1, issueHasMore = r.totalCount > 20, loadingIssues = false) }
            else state.update { s -> s.copy(issueResults = s.issueResults + r.items, issuePage = page, issueHasMore = s.issueResults.size + r.items.size < s.issueTotal, issueLoadingMore = false) }
        } catch (e: Exception) { LogManager.e(tag, "searchIssues", e); state.update { it.copy(loadingIssues = false, issueLoadingMore = false) } }
    }

    private suspend fun searchPrs(q: String, page: Int) {
        try {
            val r = api.searchIssues("$q type:pr", perPage = 20, page = page)
            if (page == 1) state.update { it.copy(prResults = r.items, prTotal = r.totalCount, prPage = 1, prHasMore = r.totalCount > 20, loadingPrs = false) }
            else state.update { s -> s.copy(prResults = s.prResults + r.items, prPage = page, prHasMore = s.prResults.size + r.items.size < s.prTotal, prLoadingMore = false) }
        } catch (e: Exception) { LogManager.e(tag, "searchPrs", e); state.update { it.copy(loadingPrs = false, prLoadingMore = false) } }
    }

    private suspend fun searchUsers(q: String, page: Int) {
        try {
            val r = api.searchUsers("$q type:user", perPage = 20, page = page)
            if (page == 1) state.update { it.copy(userResults = r.items, userTotal = r.totalCount, userPage = 1, userHasMore = r.totalCount > 20, loadingUsers = false) }
            else state.update { s -> s.copy(userResults = s.userResults + r.items, userPage = page, userHasMore = s.userResults.size + r.items.size < s.userTotal, userLoadingMore = false) }
        } catch (e: Exception) { LogManager.e(tag, "searchUsers", e); state.update { it.copy(loadingUsers = false, userLoadingMore = false) } }
    }

    private suspend fun searchOrgs(q: String, page: Int) {
        try {
            val r = api.searchUsers("$q type:org", perPage = 20, page = page)
            if (page == 1) state.update { it.copy(orgResults = r.items, orgTotal = r.totalCount, orgPage = 1, orgHasMore = r.totalCount > 20, loadingOrgs = false) }
            else state.update { s -> s.copy(orgResults = s.orgResults + r.items, orgPage = page, orgHasMore = s.orgResults.size + r.items.size < s.orgTotal, orgLoadingMore = false) }
        } catch (e: Exception) { LogManager.e(tag, "searchOrgs", e); state.update { it.copy(loadingOrgs = false, orgLoadingMore = false) } }
    }

    fun loadMoreRepos()   { if (state.value.repoLoadingMore  || !state.value.repoHasMore)  return; state.update { it.copy(repoLoadingMore  = true) }; viewModelScope.launch { searchRepos(state.value.query,  state.value.repoPage  + 1) } }
    fun loadMoreCode()    { if (state.value.codeLoadingMore  || !state.value.codeHasMore)  return; state.update { it.copy(codeLoadingMore  = true) }; viewModelScope.launch { searchCode(state.value.query,   state.value.codePage  + 1) } }
    fun loadMoreIssues()  { if (state.value.issueLoadingMore || !state.value.issueHasMore) return; state.update { it.copy(issueLoadingMore = true) }; viewModelScope.launch { searchIssues(state.value.query, state.value.issuePage + 1) } }
    fun loadMorePrs()     { if (state.value.prLoadingMore    || !state.value.prHasMore)    return; state.update { it.copy(prLoadingMore    = true) }; viewModelScope.launch { searchPrs(state.value.query,    state.value.prPage    + 1) } }
    fun loadMoreUsers()   { if (state.value.userLoadingMore  || !state.value.userHasMore)  return; state.update { it.copy(userLoadingMore  = true) }; viewModelScope.launch { searchUsers(state.value.query,  state.value.userPage  + 1) } }
    fun loadMoreOrgs()    { if (state.value.orgLoadingMore   || !state.value.orgHasMore)   return; state.update { it.copy(orgLoadingMore   = true) }; viewModelScope.launch { searchOrgs(state.value.query,   state.value.orgPage   + 1) } }

    /**
     * 从历史记录搜索，只填充到查询框，不执行搜索
     */
    fun fillFromHistory(query: String) {
        updateQuery(query)
    }

    fun clearHistory() { viewModelScope.launch { tokenStorage.clearSearchHistory() } }
    val isAnyLoading get() = state.value.run { loadingRepos || loadingCode || loadingIssues || loadingPrs || loadingUsers || loadingOrgs }
}
