package com.gitmob.android.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.android.api.ApiClient
import com.gitmob.android.auth.AccountInfo
import com.gitmob.android.auth.AccountStore
import com.gitmob.android.auth.AuthType
import com.gitmob.android.auth.OAuthManager
import com.gitmob.android.auth.TokenLoginManager
import com.gitmob.android.auth.TokenLoginResult
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.ui.login.LoginUiState.*
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

sealed class LoginUiState {
    object Idle    : LoginUiState()
    object Loading : LoginUiState()
    data class Success(val login: String) : LoginUiState()
    data class Error(val msg: String)     : LoginUiState()
    
    /** Token 登录时：检测到已有同一用户的 OAuth 账号，询问是否撤销 */
    data class ConfirmReplaceOAuth(
        val login: String,
        val token: String,
        val name: String,
        val email: String,
        val avatarUrl: String,
        val existingOAuthToken: String
    ) : LoginUiState()
    
    /** OAuth 登录时：检测到已有同一用户的 Token 账号，询问选择 */
    data class ConfirmReplaceToken(
        val login: String,
        val oauthToken: String,
        val name: String,
        val email: String,
        val avatarUrl: String,
        val existingToken: String
    ) : LoginUiState()
    
    /** OAuth 登录时：检测到已有同一用户的旧 OAuth 账号，询问选择 */
    data class ConfirmReplaceOldOAuth(
        val login: String,
        val newOAuthToken: String,
        val name: String,
        val email: String,
        val avatarUrl: String,
        val oldOAuthToken: String
    ) : LoginUiState()
}

class LoginViewModel(app: Application) : AndroidViewModel(app) {

    private val tokenStorage = TokenStorage(app)
    private val accountStore = AccountStore(app)

    private val _state = MutableStateFlow<LoginUiState>(Idle)
    val state = _state.asStateFlow()

    /** 所有已保存账号（供 LoginScreen 展示账号列表） */
    val savedAccounts: StateFlow<List<AccountInfo>> = accountStore.accounts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun onOAuthError(msg: String) {
        _state.value = Error("授权失败：$msg")
    }

    /** 收到新 token：获取用户信息 → 存入 AccountStore → 同步 TokenStorage → 重建 ApiClient */
    fun onTokenReceived(token: String) {
        LogManager.i("LoginVM", "🎯 onTokenReceived() 开始, token前缀: ${token.take(20)}...")
        viewModelScope.launch {
            _state.value = Loading
            try {
                // 先检查是否已有相同 Token 的账号（无论类型）
                val existingAccounts = accountStore.accounts.first()
                val existingSameTokenAccount = existingAccounts.firstOrNull { 
                    it.token == token 
                }
                
                if (existingSameTokenAccount != null) {
                    LogManager.i("LoginVM", "Token 已存在，直接切换账号: ${existingSameTokenAccount.login}")
                    // Token 已存在，直接使用
                    accountStore.switchAccount(existingSameTokenAccount.login)
                    tokenStorage.syncActiveAccount(existingSameTokenAccount)
                    ApiClient.rebuild()
                    _state.value = Success(existingSameTokenAccount.login)
                    return@launch
                }
                
                // 用临时 token 获取用户信息
                LogManager.i("LoginVM", "📝 第 1 次更新: tokenStorage.saveToken()")
                tokenStorage.saveToken(token)
                LogManager.i("LoginVM", "🔄 第 1 次 ApiClient.rebuild()")
                ApiClient.rebuild()
                LogManager.i("LoginVM", "📡 开始请求 getCurrentUser()")
                val user = ApiClient.api.getCurrentUser()
                LogManager.i("LoginVM", "✅ getCurrentUser() 成功: ${user.login}")
                
                val login = user.login
                val name = user.name ?: user.login
                val email = user.email ?: "${user.login}@users.noreply.github.com"
                val avatarUrl = user.avatarUrl ?: ""

                // 检查是否已有同一 login 的 Token 账号
                val existingTokenAccount = existingAccounts.firstOrNull { 
                    it.login == login && it.authType == AuthType.TOKEN 
                }
                
                if (existingTokenAccount != null) {
                    // 检测到 Token 账号，询问用户选择
                    _state.value = ConfirmReplaceToken(
                        login = login,
                        oauthToken = token,
                        name = name,
                        email = email,
                        avatarUrl = avatarUrl,
                        existingToken = existingTokenAccount.token
                    )
                    return@launch
                }
                
                // 检查是否已有同一 login 的 OAuth 账号
                val existingOAuthAccount = existingAccounts.firstOrNull { 
                    it.login == login && it.authType == AuthType.OAUTH 
                }
                
                if (existingOAuthAccount != null) {
                    // 检测到旧 OAuth 账号，询问用户选择
                    _state.value = ConfirmReplaceOldOAuth(
                        login = login,
                        newOAuthToken = token,
                        name = name,
                        email = email,
                        avatarUrl = avatarUrl,
                        oldOAuthToken = existingOAuthAccount.token
                    )
                    return@launch
                }

                // 没有冲突，直接登录
                LogManager.i("LoginVM", "🎯 调用 completeOAuthLogin()")
                completeOAuthLogin(
                    token = token,
                    login = login,
                    name = name,
                    email = email,
                    avatarUrl = avatarUrl
                )
            } catch (e: Exception) {
                LogManager.e("LoginVM", "❌ onTokenReceived() 失败", e)
                // 如果是协程取消异常（CancellationException），直接抛出，不清空 token
                if (e is kotlinx.coroutines.CancellationException) {
                    LogManager.i("LoginVM", "⚠️ 检测到协程取消，保留当前 token 并退出")
                    throw e
                }
                // 只有真正的网络/认证错误才清理脏数据
                // 清理脏数据：既清 TokenStorage 又恢复 AccountStore 的活跃账号
                tokenStorage.clearActiveAccount()
                // 如果有其他账号，把 active 恢复到第一个有效账号
                val remaining = accountStore.accounts.first()
                if (remaining.isNotEmpty()) {
                    tokenStorage.syncActiveAccount(remaining.first())
                    ApiClient.rebuild()
                }
                _state.value = Error(e.message ?: "认证失败")
            }
        }
    }
    
    /** 完成 OAuth 登录的内部函数 */
    private suspend fun completeOAuthLogin(
        token: String,
        login: String,
        name: String,
        email: String,
        avatarUrl: String
    ) {
        LogManager.i("LoginVM", "📝 completeOAuthLogin() 开始")
        val info = AccountInfo(
            login     = login,
            name      = name,
            email     = email,
            avatarUrl = avatarUrl,
            token     = token,
            authType  = AuthType.OAUTH
        )
        accountStore.addOrUpdateAccount(info)
        LogManager.i("LoginVM", "📝 第 2 次更新: tokenStorage.syncActiveAccount()")
        tokenStorage.syncActiveAccount(info)
        LogManager.i("LoginVM", "🔄 第 2 次 ApiClient.rebuild()")
        ApiClient.rebuild()

        LogManager.i("LoginVM", "✅ completeOAuthLogin() 完成，设置 Success")
        _state.value = Success(login)
    }
    
    /** OAuth 登录时：确认使用 OAuth，保留 Token */
    fun confirmUseOAuthKeepToken(
        oauthToken: String,
        login: String,
        name: String,
        email: String,
        avatarUrl: String
    ) {
        viewModelScope.launch {
            _state.value = Loading
            try {
                completeOAuthLogin(
                    token = oauthToken,
                    login = login,
                    name = name,
                    email = email,
                    avatarUrl = avatarUrl
                )
            } catch (e: Exception) {
                _state.value = Error(e.message ?: "登录失败")
            }
        }
    }
    
    /** OAuth 登录时：保持 Token，撤销 OAuth */
    fun keepTokenAndRevokeOAuth(
        oauthToken: String
    ) {
        viewModelScope.launch {
            _state.value = Loading
            try {
                OAuthManager.revokeToken(oauthToken)
            } catch (e: Exception) {
                // 即使撤销失败也没关系
            }
            _state.value = Idle
        }
    }
    
    /** OAuth 登录时：确认使用新 OAuth，撤销旧 OAuth */
    fun confirmUseNewOAuth(
        newOAuthToken: String,
        login: String,
        name: String,
        email: String,
        avatarUrl: String,
        oldOAuthToken: String
    ) {
        viewModelScope.launch {
            _state.value = Loading
            try {
                OAuthManager.revokeToken(oldOAuthToken)
                completeOAuthLogin(
                    token = newOAuthToken,
                    login = login,
                    name = name,
                    email = email,
                    avatarUrl = avatarUrl
                )
            } catch (e: Exception) {
                completeOAuthLogin(
                    token = newOAuthToken,
                    login = login,
                    name = name,
                    email = email,
                    avatarUrl = avatarUrl
                )
            }
        }
    }
    
    /** OAuth 登录时：保持旧 OAuth，撤销新 OAuth */
    fun keepOldOAuthAndRevokeNew(
        newOAuthToken: String
    ) {
        viewModelScope.launch {
            _state.value = Loading
            try {
                OAuthManager.revokeToken(newOAuthToken)
            } catch (e: Exception) {
                // 即使撤销失败也没关系
            }
            _state.value = Idle
        }
    }

    /** 直接切换到已有账号（不需要重新 OAuth） */
    fun switchToAccount(info: AccountInfo) {
        viewModelScope.launch {
            _state.value = Loading
            try {
                accountStore.switchAccount(info.login)
                tokenStorage.syncActiveAccount(info)
                ApiClient.rebuild()
                _state.value = Success(info.login)
            } catch (e: Exception) {
                _state.value = Error(e.message ?: "切换失败")
            }
        }
    }

    /** 手动输入 Token 登录 */
    fun onManualTokenReceived(token: String) {
        viewModelScope.launch {
            LogManager.d("LoginViewModel", "手动 Token 登录被触发")
            LogManager.d("LoginViewModel", "输入 Token 前缀: ${token.take(20)}...")
            _state.value = Loading
            try {
                // 先检查是否已有相同 Token 的账号
                val existingAccounts = accountStore.accounts.first()
                LogManager.d("LoginViewModel", "已有账号数量: ${existingAccounts.size}")
                val existingSameTokenAccount = existingAccounts.firstOrNull { 
                    it.token == token 
                }
                
                if (existingSameTokenAccount != null) {
                    LogManager.d("LoginViewModel", "Token 已存在，直接返回")
                    _state.value = Error("该 Token 已存在于账号列表中，请直接切换账号使用")
                    return@launch
                }
                
                // 验证 Token
                LogManager.d("LoginViewModel", "开始调用 TokenLoginManager.verifyToken...")
                val result = TokenLoginManager.verifyToken(token)
                
                when (result) {
                    is TokenLoginResult.Success -> {
                        // 检查是否已有同一用户的 OAuth 账号
                        val existingOAuthAccount = existingAccounts.firstOrNull { 
                            it.login == result.login && it.authType == AuthType.OAUTH 
                        }
                        
                        if (existingOAuthAccount != null) {
                            // 检测到 OAuth 账号，询问用户是否撤销
                            _state.value = ConfirmReplaceOAuth(
                                login = result.login,
                                token = token,
                                name = result.name,
                                email = result.email,
                                avatarUrl = result.avatarUrl,
                                existingOAuthToken = existingOAuthAccount.token
                            )
                        } else {
                            // 没有 OAuth 账号，直接登录
                            completeTokenLogin(
                                token = token,
                                login = result.login,
                                name = result.name,
                                email = result.email,
                                avatarUrl = result.avatarUrl
                            )
                        }
                    }
                    is TokenLoginResult.Failure -> {
                        _state.value = Error(result.message)
                    }
                    is TokenLoginResult.InsufficientScopes -> {
                        _state.value = Error("权限不全，缺少：${result.missingScopes.joinToString(", ")}")
                    }
                }
            } catch (e: Exception) {
                _state.value = Error(e.message ?: "认证失败")
            }
        }
    }
    
    /** 取消 Token 登录操作，回到 Idle 状态 */
    fun cancelTokenLogin() {
        _state.value = Idle
    }
    
    /** 确认替换 OAuth 账号并撤销原 token */
    fun confirmReplaceOAuth(
        token: String,
        login: String,
        name: String,
        email: String,
        avatarUrl: String,
        existingOAuthToken: String
    ) {
        viewModelScope.launch {
            _state.value = Loading
            try {
                // 先撤销 OAuth token（失败不影响后续流程）
                OAuthManager.revokeToken(existingOAuthToken)
                
                // 完成 Token 登录
                completeTokenLogin(
                    token = token,
                    login = login,
                    name = name,
                    email = email,
                    avatarUrl = avatarUrl
                )
            } catch (e: Exception) {
                // 即使撤销失败，也继续完成登录
                completeTokenLogin(
                    token = token,
                    login = login,
                    name = name,
                    email = email,
                    avatarUrl = avatarUrl
                )
            }
        }
    }
    
    /** 跳过撤销，直接使用 Token 登录（保留原 OAuth token） */
    fun skipRevokeAndLogin(
        token: String,
        login: String,
        name: String,
        email: String,
        avatarUrl: String
    ) {
        viewModelScope.launch {
            completeTokenLogin(
                token = token,
                login = login,
                name = name,
                email = email,
                avatarUrl = avatarUrl
            )
        }
    }
    
    /** 完成 Token 登录的内部函数 */
    private suspend fun completeTokenLogin(
        token: String,
        login: String,
        name: String,
        email: String,
        avatarUrl: String
    ) {
        val authType = AccountStore.identifyAuthType(token)
        val info = AccountInfo(
            login     = login,
            name      = name,
            email     = email,
            avatarUrl = avatarUrl,
            token     = token,
            authType  = authType
        )
        accountStore.addOrUpdateAccount(info)
        tokenStorage.syncActiveAccount(info)
        ApiClient.rebuild()

        _state.value = Success(login)
    }
}
