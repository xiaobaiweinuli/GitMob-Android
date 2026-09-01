package com.gitmob.app.ui.repos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.repository.RepoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReposUiState(
    val repos: List<RepoListItem> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val hasNextPage: Boolean = false,
    val ownerContext: RepositoryCreateOwner? = null,
)

@HiltViewModel
class ReposViewModel @Inject constructor(
    private val repoRepository: RepoRepository,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(ReposUiState())
    val state: StateFlow<ReposUiState> = _state.asStateFlow()

    /** 要查询的用户 login；`null` 表示 viewer（当前登录用户自己，底部 Tab 的"仓库"用） */
    private var login: String? = null
    private var endCursor: String? = null
    private var initialized = false

    init {
        observeRepoUpdates()
    }

    /**
     * 初始化仓库列表的数据源。
     *
     * 与 UserListViewModel.init() 相同的范式：Nav3 路由对象里取出 login，
     * 在 Screen 的 LaunchedEffect(login) 里调用本方法，避免 ViewModel 初始化时就立即
     * 发请求（底部 Tab ReposRoute 走 login=null，他人仓库 UserRepoListRoute 走具体 login）。
     *
     * @param login `null` → viewer（自己的仓库，底部 Tab 用）；非 null → 指定 login 用户的仓库
     */
    fun init(login: String?) {
        if (initialized) return
        initialized = true
        this.login = login
        load()
    }

    /** 仓库详情页星标切换后，这里同步更新对应卡片的星标数，不用整页重新拉取 */
    private fun observeRepoUpdates() {
        viewModelScope.launch {
        repoUpdateEventBus.events
                .collect { event ->
                    if (event is RepoUpdateEvent.RepositoryCreated) {
                        val owner = _state.value.ownerContext?.login
                        if (owner != null && owner == event.owner) refresh()
                        return@collect
                    }
                    if (event !is RepoUpdateEvent.StarChanged) return@collect
                    _state.update { state ->
                        state.copy(repos = state.repos.map {
                            if (it.ownerLogin == event.owner && it.name == event.name) {
                                it.copy(stargazerCount = event.stargazerCount)
                            } else it
                        })
                    }
                }
        }
    }

    /** 加载第一页（初始化 + 重试共用）。走 RepoRepository.getRepos(login?, after=null)。 */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            applyFirstPage(repoRepository.getRepos(login = login, after = null))
        }
    }

    /** 下拉刷新第一页。 */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            val result = if (login == null) {
                repoRepository.getViewerReposFresh()
            } else {
                repoRepository.getRepos(login = login, after = null)
            }
            applyFirstPage(result)
        }
    }

    /** 加载下一页（endCursor 分页）。 */
    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = repoRepository.getRepos(login = login, after = endCursor)) {
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

    fun retry() = load()

    private suspend fun applyFirstPage(result: ApiResult<com.gitmob.app.data.model.RepoList>) {
        when (result) {
            is ApiResult.Success -> {
                endCursor = result.data.endCursor
                _state.update {
                    it.copy(
                        repos = result.data.items,
                        totalCount = result.data.totalCount,
                        ownerContext = result.data.ownerContext?.owner,
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
