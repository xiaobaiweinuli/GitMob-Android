package com.gitmob.android.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.android.api.ApiClient
import com.gitmob.android.api.GHOrg
import com.gitmob.android.api.GHRepo
import com.gitmob.android.api.GHUser
import com.gitmob.android.api.GraphQLClient
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.data.FavGroup
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val user: GHUser? = null,
    val pinnedRepos: List<GHRepo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val repoCount: Int = 0,
    val orgs: List<GHOrg> = emptyList(),
    val orgCount: Int = 0,
    val starredCount: Int = 0,
    val showOrgPicker: Boolean = false,
    val isCurrentUser: Boolean = true,
    val refreshing: Boolean = false,
    val followers: List<GHUser> = emptyList(),
    val following: List<GHUser> = emptyList(),
    val showFollowers: Boolean = false,
    val showFollowing: Boolean = false,
    val followersLoading: Boolean = false,
    val followingLoading: Boolean = false,
    val showFavGroupDetail: FavGroup? = null,
    val showUngroupedDetail: Boolean = false,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val api get() = ApiClient.api
    private val tokenStorage = TokenStorage(app)
    private val repo = com.gitmob.android.data.RepoRepository()
    private val tag = "HomeVM"

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()

    private var initialized = false

    fun load(forceRefresh: Boolean = false) {
        if (!initialized || forceRefresh) {
            initialized = true
            loadUser(null)
        }
    }

    fun loadUser(login: String?) {
        viewModelScope.launch {
            _state.update { 
                HomeState(
                    loading = true, 
                    error = null,
                    isCurrentUser = login == null
                )
            }
            try {
                val user = if (login == null) {
                    api.getCurrentUser()
                } else {
                    api.getUser(login)
                }
                _state.update { 
                    it.copy(
                        user = user, 
                        loading = false, 
                        error = null,
                        isCurrentUser = login == null
                    ) 
                }
                launch { loadRepoCount(login) }
                launch { loadPinnedRepos(user.login) }
                launch { loadStarredCount(login) }
                launch { loadOrgs(login) }
            } catch (e: Exception) {
                LogManager.e(tag, "加载主页失败", e)
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    private suspend fun loadRepoCount(login: String?) {
        try {
            val token = tokenStorage.accessToken.first() ?: return
            val count = if (login == null) {
                GraphQLClient.getViewerRepoCount(token)
            } else {
                GraphQLClient.getUserRepoCount(token, login)
            }
            _state.update { it.copy(repoCount = count) }
        } catch (e: Exception) {
            _state.value.user?.publicRepos?.let { _state.update { s -> s.copy(repoCount = it) } }
            LogManager.w(tag, "仓库总数失败: ${e.message}")
        }
    }

    private suspend fun loadPinnedRepos(login: String) {
        try {
            val token = tokenStorage.accessToken.first() ?: return
            _state.update { it.copy(pinnedRepos = GraphQLClient.getPinnedRepos(token, login)) }
        } catch (e: Exception) { LogManager.w(tag, "置顶仓库失败: ${e.message}") }
    }

    private suspend fun loadStarredCount(login: String?) {
        try {
            val token = tokenStorage.accessToken.first() ?: return
            val count = if (login == null) {
                GraphQLClient.getStarredCount(token)
            } else {
                GraphQLClient.getUserStarredCount(token, login)
            }
            _state.update { it.copy(starredCount = count) }
        } catch (e: Exception) { LogManager.w(tag, "星标数失败: ${e.message}") }
    }

    private suspend fun loadOrgs(login: String?) {
        try {
            val orgs = if (login == null) {
                repo.getUserOrgs()
            } else {
                api.getUserOrgs(login, perPage = 100)
            }
            _state.update { it.copy(orgs = orgs, orgCount = orgs.size) }
        } catch (e: Exception) { LogManager.w(tag, "组织失败: ${e.message}") }
    }

    fun showOrgPicker() { _state.update { it.copy(showOrgPicker = true) } }
    fun hideOrgPicker() { _state.update { it.copy(showOrgPicker = false) } }

    fun refresh(login: String?) {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            loadUser(login)
            _state.update { it.copy(refreshing = false) }
        }
    }

    fun showFollowers() {
        _state.update { it.copy(showFollowers = true) }
        loadFollowers(_state.value.user?.login)
    }

    fun hideFollowers() {
        _state.update { it.copy(showFollowers = false) }
    }

    fun showFollowing() {
        _state.update { it.copy(showFollowing = true) }
        loadFollowing(_state.value.user?.login)
    }

    fun hideFollowing() {
        _state.update { it.copy(showFollowing = false) }
    }

    private fun loadFollowers(login: String?) {
        viewModelScope.launch {
            _state.update { it.copy(followersLoading = true) }
            try {
                val followers = if (login == null) {
                    api.getMyFollowers()
                } else {
                    api.getFollowers(login)
                }
                _state.update { it.copy(followers = followers, followersLoading = false) }
            } catch (e: Exception) {
                LogManager.w(tag, "获取关注者失败: ${e.message}")
                _state.update { it.copy(followersLoading = false) }
            }
        }
    }

    private fun loadFollowing(login: String?) {
        viewModelScope.launch {
            _state.update { it.copy(followingLoading = true) }
            try {
                val following = if (login == null) {
                    api.getMyFollowing()
                } else {
                    api.getFollowing(login)
                }
                _state.update { it.copy(following = following, followingLoading = false) }
            } catch (e: Exception) {
                LogManager.w(tag, "获取关注失败: ${e.message}")
                _state.update { it.copy(followingLoading = false) }
            }
        }
    }

    /**
     * 显示收藏夹分组详情
     */
    fun showFavGroupDetail(group: FavGroup) {
        _state.update { it.copy(showFavGroupDetail = group) }
    }

    /**
     * 隐藏收藏夹分组详情
     */
    fun hideFavGroupDetail() {
        _state.update { it.copy(showFavGroupDetail = null) }
    }

    /**
     * 显示未分组收藏详情
     */
    fun showUngroupedDetail() {
        _state.update { it.copy(showUngroupedDetail = true) }
    }

    /**
     * 隐藏未分组收藏详情
     */
    fun hideUngroupedDetail() {
        _state.update { it.copy(showUngroupedDetail = false) }
    }
}
