package com.gitmob.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.ProfileOwner
import com.gitmob.app.data.model.SimpleOrg
import com.gitmob.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val owner: ProfileOwner? = null,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    // ---- OrganizationsBottomSheet 状态：用户/组织资料页点击"组织"统计项，弹出和 HomeScreen 同款弹窗 ----
    val showOrgSheet: Boolean = false,
    val organizations: List<SimpleOrg> = emptyList(),
    val isLoadingOrgs: Boolean = false,
)

/**
 * 统一承接用户和组织资料页——只有一个 login 时无法预先知道类型，
 * 用 UserRepository.getProfileOwner(login) 一次查询、运行时按 __typename 分流，
 * 见 references/architecture.md"个人主页/组织主页统一查询"一节。
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    /** 当前正在查看的用户/组织 login（retry / openOrganizations 都需要用到） */
    private var currentLogin: String = ""
    private var initialized = false

    fun init(login: String) {
        if (initialized) return
        initialized = true
        currentLogin = login
        load(login)
    }

    /**
     * 加载资料主数据（ProfileOwner）。
     * login 从 currentLogin 取（因为 retry() 调用时没有参数入口）。
     */
    private fun load(login: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = userRepository.getProfileOwner(login)) {
                is ApiResult.Success -> _state.update {
                    it.copy(owner = result.data, isLoading = false, loadFailed = false)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoading = false, loadFailed = true) }
                }
            }
        }
    }

    /**
     * 修复：用户资料页的"重试"按钮之前是个空壳（只会触发一次 init，initialized=true 后再点没反应），
     * 这里显式把 initialized 复位 + 重新 load，和 HomeViewModel.retry() 的行为保持一致。
     */
    fun retry() {
        initialized = false
        init(currentLogin)
    }

    // ================================
    // OrganizationsBottomSheet 状态机
    // ================================

    /**
     * 打开"选择组织"底部弹窗 + 懒加载组织列表。
     * 只有个人资料（ProfileOwner.Person）才能有组织，组织资料点了也不显示。
     * 复用 HomeViewModel 同款逻辑 + UserRepository.getOrganizations(login) 参数化方法，
     * 传入当前用户 login（不是 viewer）就能查任意公开用户的组织列表。
     */
    fun openOrganizations() {
        // 只有 Person 有组织这个概念，Org 类型点"组织"统计项无意义（ProfileScreen 个人资料统计行
        // 只对 Person 渲染；OrgProfileContent 走仓库/成员两列，理论上不会走到这里，防御性判断）
        val person = _state.value.owner as? ProfileOwner.Person ?: return
        _state.update { it.copy(showOrgSheet = true) }
        // 如果列表还没加载过就触发一次加载；已加载过直接显示缓存（弹窗点开关开关开不重复发请求）
        if (_state.value.organizations.isEmpty() && !_state.value.isLoadingOrgs) {
            loadOrganizations(person.login)
        }
    }

    /** 关闭"选择组织"弹窗（状态复位，不清除列表缓存，下次打开直接显示）。 */
    fun dismissOrganizations() {
        _state.update { it.copy(showOrgSheet = false) }
    }

    /** 实际发请求拉取组织列表。login 是当前查看的用户 login，viewer 自己也传自己的 login。 */
    private fun loadOrganizations(login: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingOrgs = true) }
            when (val result = userRepository.getOrganizations(login = login)) {
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

    // ================================
    // Follow 按钮切换
    // ================================

    /**
     * 切换关注/取消关注状态（同时支持个人用户和组织）
     *
     * 对齐 GitHub 官方 App 做法：
     * mutation 只验证请求成功（clientMutationId 模式），所有 UI 状态客户端本地乐观更新。
     * 原因：GitHub API 的 followers.totalCount 是最终一致的聚合计数，mutation 返回值
     * 会慢一拍，不可直接用于更新 UI。
     *
     * 个人用户：本地翻转 viewerIsFollowing 布尔 + followersCount 乐观 ±1
     * 组织：本地翻转 viewerIsFollowing 布尔（Schema 无 followers 字段）
     */
    fun toggleFollow() {
        when (val owner = _state.value.owner) {
            is ProfileOwner.Person -> {
                val wasFollowing = owner.followState.viewerIsFollowing
                viewModelScope.launch {
                    val result = if (wasFollowing) {
                        userRepository.unfollowUser(owner.id)
                    } else {
                        userRepository.followUser(owner.id)
                    }
                    when (result) {
                        is ApiResult.Success -> _state.update { state ->
                            val current = state.owner as? ProfileOwner.Person ?: return@update state
                            val newFollowing = !wasFollowing
                            val delta = if (newFollowing) 1 else -1
                            state.copy(
                                owner = current.copy(
                                    followState = current.followState.copy(
                                        viewerIsFollowing = newFollowing,
                                    ),
                                    followersCount = (current.followersCount + delta).coerceAtLeast(0),
                                ),
                            )
                        }
                        is ApiResult.Failure -> errorEventBus.emit(result.error)
                    }
                }
            }
            is ProfileOwner.Org -> {
                val wasFollowing = owner.viewerIsFollowing
                viewModelScope.launch {
                    val result = if (wasFollowing) {
                        userRepository.unfollowOrganization(owner.id)
                    } else {
                        userRepository.followOrganization(owner.id)
                    }
                    when (result) {
                        is ApiResult.Success -> _state.update { state ->
                            val current = state.owner as? ProfileOwner.Org ?: return@update state
                            state.copy(
                                owner = current.copy(viewerIsFollowing = !wasFollowing),
                            )
                        }
                        is ApiResult.Failure -> errorEventBus.emit(result.error)
                    }
                }
            }
            null -> Unit
        }
    }
}
