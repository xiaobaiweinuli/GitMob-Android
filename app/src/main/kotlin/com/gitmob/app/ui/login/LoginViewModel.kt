package com.gitmob.app.ui.login

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.error.displayMessageRes
import com.gitmob.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val tokenInput: String = "",
    val isLoading: Boolean = false,
    val loginSucceeded: Boolean = false,
    /** 输入框下方的即时校验错误（资源 id，跟随应用语言），和顶部全局错误提示分开 */
    @field:StringRes val inlineErrorRes: Int? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onTokenInputChange(value: String) {
        _state.update { it.copy(tokenInput = value, inlineErrorRes = null) }
    }

    /** 用户点击"登录"按钮的入口。 */
    fun login() {
        val token = _state.value.tokenInput.trim()
        if (token.isEmpty()) {
            _state.update { it.copy(inlineErrorRes = R.string.login_token_required) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, inlineErrorRes = null) }

            when (val result = authRepository.loginWithToken(token)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isLoading = false, loginSucceeded = true) }
                }
                is ApiResult.Failure -> {
                    // 同时发两份：一份给全局顶部 Banner，一份给输入框 inline error（用户直接能看到）
                    errorEventBus.emit(result.error)
                    _state.update {
                        it.copy(isLoading = false, inlineErrorRes = result.error.displayMessageRes())
                    }
                }
            }
        }
    }
}
