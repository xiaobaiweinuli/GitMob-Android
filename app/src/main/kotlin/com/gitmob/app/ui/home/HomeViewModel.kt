package com.gitmob.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.ViewerProfile
import com.gitmob.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val profile: ViewerProfile? = null,
    val isLoading: Boolean = false,   // 首次加载（全屏 loading）
    val isRefreshing: Boolean = false, // 下拉刷新（保留旧数据，只显示刷新指示器）
    val loadFailed: Boolean = false,   // 首次加载失败，展示"重试"按钮
    val showOrgSheet: Boolean = false,
    val organizations: List<com.gitmob.app.data.model.SimpleOrg> = emptyList(),
    val isLoadingOrgs: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus = RepoUpdateEventBus(),
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        load()
        observeRepositoryCreated()
    }

    private fun observeRepositoryCreated() {
        viewModelScope.launch {
            repoUpdateEventBus.events.collect { event ->
                if (event is RepoUpdateEvent.RepositoryCreated && event.owner == _state.value.profile?.user?.login) {
                    _state.update { state -> state.copy(profile = state.profile?.copy(repoCount = state.profile.repoCount + 1)) }
                }
            }
        }
    }

    /**
     * 首次加载主页数据（全屏 loading，失败时展示"重试"按钮）
     */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            handleResult(userRepository.getViewerProfile())
        }
    }

    /**
     * 下拉刷新（保留旧数据，只显示刷新指示器，失败时不整页报错）
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            handleResult(userRepository.getViewerProfileFresh())
        }
    }

    fun retry() = load()

    fun openOrganizations() {
        _state.update { it.copy(showOrgSheet = true) }
        if (_state.value.organizations.isEmpty()) {
            viewModelScope.launch {
                _state.update { it.copy(isLoadingOrgs = true) }
                when (val result = userRepository.getOrganizations()) {
                    is ApiResult.Success -> _state.update {
                        it.copy(organizations = result.data, isLoadingOrgs = false)
                    }
                    is ApiResult.Failure -> {
                        errorEventBus.emit(result.error)
                        _state.update { it.copy(isLoadingOrgs = false) }
                    }
                }
            }
        }
    }

    fun dismissOrganizations() {
        _state.update { it.copy(showOrgSheet = false) }
    }

    /**
     * 切换关注/取消关注状态
     *
     * 注：自己主页（isViewer=true）时按钮不显示，此方法理论上不会被调用；
     * 但保留实现以兼容未来可能的场景。
     *
     * 对齐 GitHub 官方 App 做法：mutation 只验证请求成功，所有 UI 状态客户端本地乐观更新。
     * followersCount 使用 ±1 更新，因为 GitHub API 的聚合计数是最终一致的，mutation
     * 返回值会慢一拍。
     */
    fun toggleFollow() {
        val profile = _state.value.profile ?: return
        val wasFollowing = profile.followState.viewerIsFollowing
        viewModelScope.launch {
            val result = if (wasFollowing) {
                userRepository.unfollowUser(profile.user.id)
            } else {
                userRepository.followUser(profile.user.id)
            }
            when (result) {
                is ApiResult.Success -> _state.update { state ->
                    state.profile?.let { p ->
                        val newFollowing = !wasFollowing
                        val delta = if (newFollowing) 1 else -1
                        state.copy(
                            profile = p.copy(
                                followState = p.followState.copy(viewerIsFollowing = newFollowing),
                                user = p.user.copy(
                                    followers = (p.user.followers + delta).coerceAtLeast(0),
                                ),
                            ),
                        )
                    } ?: state
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    /**
     * 统一处理网络请求结果，成功时更新 profile 并重置 loading 状态，
     * 失败时通过 ErrorEventBus 上报错误，并根据是否已有数据决定是整页报错还是静默忽略。
     * isLoading 和 isRefreshing 的重置在两个分支内部都已处理，无需外部再传收尾动作。
     * 不使用 inline：该函数形参不含 lambda，inline 无性能收益，反触发编译器警告。
     *
     * @param result 网络请求结果（Success/Failure 两态）
     */
    private fun handleResult(result: ApiResult<ViewerProfile>) {
        when (result) {
            is ApiResult.Success -> _state.update {
                it.copy(profile = result.data, isLoading = false, isRefreshing = false, loadFailed = false)
            }
            is ApiResult.Failure -> {
                viewModelScope.launch { errorEventBus.emit(result.error) }
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        loadFailed = it.profile == null, // 已有数据时刷新失败不整页报错，只是这次没刷新成功
                    )
                }
            }
        }
    }
}
