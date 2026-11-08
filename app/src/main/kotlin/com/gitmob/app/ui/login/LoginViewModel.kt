package com.gitmob.app.ui.login

import androidx.annotation.StringRes
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.ui.common.AccountViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.error.displayMessageRes
import com.gitmob.app.data.model.DeviceFlowState
import com.gitmob.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthSheet { PAT, DEVICE_FLOW }

data class LoginUiState(
    val tokenInput: String = "",
    val isLoading: Boolean = false,
    val loginSucceeded: Boolean = false,
    /** 输入框下方的即时校验错误（资源 id，跟随应用语言），和顶部全局错误提示分开 */
    @field:StringRes val inlineErrorRes: Int? = null,
    val authSheet: AuthSheet? = null,
    val deviceFlow: DeviceFlowState? = null,
    val switchingKey: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val errorEventBus: ErrorEventBus,
    session: AccountSession,
) : AccountViewModel(session) {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    val accountState = session.state
    private var authorizationJob: Job? = null
    private var operation = 0

    fun openPat() {
        if (_state.value.isLoading) return
        cancelAuthorization()
        _state.update { it.copy(authSheet = AuthSheet.PAT) }
    }

    fun openDeviceFlow() {
        if (_state.value.isLoading) return
        cancelAuthorization()
        _state.update { it.copy(authSheet = AuthSheet.DEVICE_FLOW) }
        retryDeviceFlow()
    }

    fun cancelAuthorization() {
        operation++
        authorizationJob?.cancel()
        authorizationJob = null
        _state.value = LoginUiState()
    }

    fun onTokenInputChange(value: String) {
        if (_state.value.isLoading) return
        _state.update { it.copy(tokenInput = value, inlineErrorRes = null) }
    }

    /** 用户点击"登录"按钮的入口。 */
    fun login() {
        if (_state.value.isLoading) return
        val token = _state.value.tokenInput.trim()
        if (token.isEmpty()) {
            _state.update { it.copy(inlineErrorRes = R.string.login_token_required) }
            return
        }

        val started = ++operation
        _state.update { it.copy(isLoading = true, inlineErrorRes = null) }
        authorizationJob = viewModelScope.launch {
            val result = authRepository.loginWithToken(token)
            currentCoroutineContext().ensureActive()
            if (started != operation || !accountEpoch.isCurrent) return@launch
            when (result) {
                is ApiResult.Success -> {
                    _state.value = LoginUiState(loginSucceeded = true)
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

    fun retryDeviceFlow() {
        if (_state.value.authSheet != AuthSheet.DEVICE_FLOW || _state.value.isLoading) return
        val started = ++operation
        _state.update { it.copy(isLoading = true, deviceFlow = DeviceFlowState.Requesting, inlineErrorRes = null) }
        authorizationJob = viewModelScope.launch {
            authRepository.authorizeDevice().collect { result ->
                currentCoroutineContext().ensureActive()
                if (started != operation || !accountEpoch.isCurrent) return@collect
                when (result) {
                    is DeviceFlowState.Complete -> _state.value = LoginUiState(loginSucceeded = true)
                    is DeviceFlowState.Failed -> {
                        _state.update { it.copy(isLoading = false, deviceFlow = result, inlineErrorRes = result.error.displayMessageRes()) }
                        errorEventBus.emit(result.error)
                    }
                    else -> _state.update { it.copy(deviceFlow = result) }
                }
            }
        }
    }

    fun switchAccount(key: String) {
        if (_state.value.isLoading || !accountEpoch.isCurrent) return
        val selected = accountState.value.accounts.firstOrNull { it.key == key } ?: return
        if (key == accountState.value.activeAccount?.key) return
        if (selected.needsReauthentication) {
            openPat()
            _state.update { it.copy(inlineErrorRes = R.string.accounts_reauthenticate) }
            return
        }
        val started = ++operation
        _state.update { it.copy(isLoading = true, switchingKey = key, inlineErrorRes = null) }
        authorizationJob = viewModelScope.launch {
            val result = authRepository.switchAccount(key)
            currentCoroutineContext().ensureActive()
            if (started != operation || !accountEpoch.isCurrent) return@launch
            when (result) {
                is ApiResult.Success -> _state.value = LoginUiState(loginSucceeded = true)
                is ApiResult.Failure -> {
                    _state.update { it.copy(isLoading = false, switchingKey = null, inlineErrorRes = result.error.displayMessageRes()) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }

    fun browserUnavailable(@StringRes messageRes: Int = R.string.auth_browser_unavailable) {
        viewModelScope.launch { errorEventBus.emit(ApiError.UserVisible(messageRes)) }
    }

    override fun onCleared() {
        cancelAuthorization()
        super.onCleared()
    }
}
