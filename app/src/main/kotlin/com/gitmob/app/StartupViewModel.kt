package com.gitmob.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.preferences.ThemePreferenceStore
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.repository.AuthRepository
import com.gitmob.app.ui.theme.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 配合 Splash Screen API：启动时异步判断登录状态，不阻塞主线程（不用 runBlocking）。
 * 同时把主题偏好也在这里暴露成 StateFlow，MainActivity 一处订阅即可。
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    themePreferenceStore: ThemePreferenceStore,
    private val errorEventBus: ErrorEventBus,
    session: AccountSession,
) : ViewModel() {

    private val _isRouteResolved = MutableStateFlow(false)

    val accountState = authRepository.accountState

    val themePreference: StateFlow<ThemePreference> = themePreferenceStore.preference
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemePreference())

    /**
     * 登录路由已解析 && 主题偏好已加载，两者齐了首帧才是最终 UI——
     * 启动页的 setKeepOnScreenCondition 等的就是它，系统淡出因此揭开的是
     * 真实主页而不是空白/默认主题的中间帧（文档/splash-screen-deep-analysis.md §10）。
     */
    val isReady: StateFlow<Boolean> =
        combine(_isRouteResolved, themePreference) { routeResolved, theme ->
            routeResolved && theme.isLoaded
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        val started = session.epoch
        viewModelScope.launch {
            when (val result = authRepository.initialize(started)) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> errorEventBus.emit(result.error, started)
            }
            _isRouteResolved.value = true
        }
    }
}
