package com.gitmob.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.preferences.ThemePreferenceStore
import com.gitmob.app.data.repository.AuthRepository
import com.gitmob.app.ui.theme.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    val themePreference: StateFlow<ThemePreference> = themePreferenceStore.preference
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemePreference())

    init {
        viewModelScope.launch {
            _isLoggedIn.value = authRepository.isLoggedIn()
            _isReady.value = true
        }
    }
}
