package com.gitmob.app.ui.userlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.PagedUsers
import com.gitmob.app.data.model.SimpleUser
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 关注者/关注/仓库关注者 Watchers/组织成员 Members 共用一个 ViewModel——四者数据形状完全一样（PagedUsers），
 * 只是调用的 Repository 方法不同、参数维度不同（用户维度用 login，仓库维度用 owner+name，组织维度用 orgLogin）。
 *
 * Nav3 的路由本身就是普通 Kotlin 对象，参数直接由调用方从 route 里取出传入 init()，
 * 不走 SavedStateHandle.toRoute()——那是 Nav2 的机制，Nav3 用不上，见 references/navigation3.md。
 */
enum class UserListMode {
    /** 用户维度：某个人的关注者 */
    FOLLOWERS,
    /** 用户维度：某个人关注的人 */
    FOLLOWING,
    /** 仓库维度：某个仓库的 Watchers（关注者），复用现有 UserListScreen 架构 */
    WATCHERS,
    /** 组织维度：某个组织的 Members（成员），与上面三个走完全一样的 PagedUsers 渲染逻辑 */
    ORG_MEMBERS,
}
/** 组织成员 Members 的 UI 状态机（与上面 3 种模式完全一致，共用同一个 state） */
data class UserListUiState(
    val users: List<SimpleUser> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val hasNextPage: Boolean = false,
)

@HiltViewModel
class UserListViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val repoDetailRepository: RepoDetailRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(UserListUiState())
    val state: StateFlow<UserListUiState> = _state.asStateFlow()

    private var login: String = ""
    /** 复用 repoOwner 字段存组织 login（与 WATCHERS 模式的 repoOwner 互斥，不会同时使用） */
    private var repoOwner: String = ""
    private var repoName: String = ""
    private var mode: UserListMode = UserListMode.FOLLOWERS
    private var endCursor: String? = null
    private var initialized = false

    /** 关注者/关注（用户维度） */
    fun init(login: String, mode: UserListMode) {
        if (initialized) return
        initialized = true
        this.login = login
        this.mode = mode
        load()
    }

    /** 仓库关注者（Watchers，仓库维度） */
    fun initForRepoWatchers(owner: String, name: String) {
        if (initialized) return
        initialized = true
        this.repoOwner = owner
        this.repoName = name
        this.mode = UserListMode.WATCHERS
        load()
    }

    /**
     * 组织成员（ORG_MEMBERS，组织维度）。
     * 与 initForRepoWatchers 同构：都是单 login 参数 → 调用不同的 Repository 方法，
     * 复用现有 UI 状态机 / 分页 / 加载失败重试全部逻辑，零新文件。
     */
    fun initForOrgMembers(orgLogin: String) {
        if (initialized) return
        initialized = true
        this.repoOwner = orgLogin
        this.mode = UserListMode.ORG_MEMBERS
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            applyFirstPage(fetch(after = null))
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            applyNextPage(fetch(after = endCursor))
        }
    }

    fun retry() = load()

    private suspend fun fetch(after: String?): ApiResult<PagedUsers> = when (mode) {
        UserListMode.FOLLOWERS -> userRepository.getFollowers(login, after)
        UserListMode.FOLLOWING -> userRepository.getFollowing(login, after)
        UserListMode.WATCHERS -> repoDetailRepository.getWatchers(repoOwner, repoName, after)
        // 组织成员：复用 UserRepository 已存在的 getOrgMembers(orgLogin, after) 方法
        UserListMode.ORG_MEMBERS -> userRepository.getOrgMembers(repoOwner, after)
    }

    private fun applyFirstPage(result: ApiResult<PagedUsers>) {
        when (result) {
            is ApiResult.Success -> {
                endCursor = result.data.endCursor
                _state.update {
                    it.copy(
                        users = result.data.users,
                        totalCount = result.data.totalCount,
                        hasNextPage = result.data.hasNextPage,
                        isLoading = false,
                        loadFailed = false,
                    )
                }
            }
            is ApiResult.Failure -> {
                viewModelScope.launch { errorEventBus.emit(result.error) }
                _state.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }

    private fun applyNextPage(result: ApiResult<PagedUsers>) {
        when (result) {
            is ApiResult.Success -> {
                endCursor = result.data.endCursor
                _state.update {
                    it.copy(
                        users = it.users + result.data.users,
                        hasNextPage = result.data.hasNextPage,
                        isLoadingMore = false,
                    )
                }
            }
            is ApiResult.Failure -> {
                viewModelScope.launch { errorEventBus.emit(result.error) }
                _state.update { it.copy(isLoadingMore = false) }
            }
        }
    }
}
