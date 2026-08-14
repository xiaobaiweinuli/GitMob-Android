package com.gitmob.app.ui.userstars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.PagedStarredRepos
import com.gitmob.app.data.model.StarredRepo
import com.gitmob.app.data.repository.StarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 他人星标仓库列表的轻量 ViewModel——只读，不带任何管理交互。
 *
 * 与"星标"Tab 的 StarsViewModel 严格区分职责：
 * - StarsViewModel：当前登录用户自己的星标，带建/改/删列表、加入列表 BottomSheet、取消星标等完整状态机
 * - 本 ViewModel：任意 login 用户的公开星标，只有"分页拉取 + 展示"，不存在对该用户收藏夹的写操作
 *   （你不能管理别人的收藏夹，也不能替别人取消星标）
 *
 * UI 层（UserStarredReposScreen）复用公共 StarredRepoCard(showViewerActions=false)，
 * 右侧的"添加到列表 + 取消星标"按钮会被隐藏，保证语义和权限一致。
 */
data class UserStarredReposUiState(
    val repos: List<StarredRepo> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val hasNextPage: Boolean = false,
)

@HiltViewModel
class UserStarredReposViewModel @Inject constructor(
    private val starRepository: StarRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(UserStarredReposUiState())
    val state: StateFlow<UserStarredReposUiState> = _state.asStateFlow()

    /** 要查询的目标用户 login（非空，因为是"他人主页 → 点击星标"跳转进来的） */
    private var login: String = ""
    private var endCursor: String? = null
    private var initialized = false

    /**
     * 初始化数据源：Screen 层从路由里拿到 login 后在 LaunchedEffect 里调用。
     * initialized 防重复初始化（Nav3 返回后 ViewModel 没死，LaunchedEffect 会再次触发）。
     */
    fun init(login: String) {
        if (initialized) return
        initialized = true
        this.login = login
        load()
    }

    /** 加载第一页（初始化 + 重试共用）。 */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            applyFirstPage(starRepository.getUserStarred(login = login, after = null))
        }
    }

    /** 下拉刷新第一页。 */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            applyFirstPage(starRepository.getUserStarred(login = login, after = null))
        }
    }

    /** 加载下一页（endCursor 分页）。 */
    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = starRepository.getUserStarred(login = login, after = endCursor)) {
                is ApiResult.Success -> {
                    endCursor = result.data.endCursor
                    _state.update {
                        it.copy(
                            repos = it.repos + result.data.items,
                            hasNextPage = result.data.hasNextPage,
                            isLoadingMore = false,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    /** 加载失败后的重试按钮回调。 */
    fun retry() = load()

    /** 统一处理第一页返回（初始化 / 刷新两条路径都走这里）。 */
    private suspend fun applyFirstPage(result: ApiResult<PagedStarredRepos>) {
        when (result) {
            is ApiResult.Success -> {
                endCursor = result.data.endCursor
                _state.update {
                    it.copy(
                        repos = result.data.items,
                        totalCount = result.data.totalCount,
                        hasNextPage = result.data.hasNextPage,
                        isLoading = false,
                        isRefreshing = false,
                        loadFailed = false,
                    )
                }
            }
            is ApiResult.Failure -> {
                errorEventBus.emit(result.error)
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, loadFailed = it.repos.isEmpty())
                }
            }
        }
    }
}
