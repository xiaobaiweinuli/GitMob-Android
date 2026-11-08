package com.gitmob.app.data.repository

import android.os.SystemClock

import com.gitmob.app.data.local.library.LibraryAccountMigration
import com.gitmob.app.core.auth.TokenStorage
import com.gitmob.app.core.auth.AccountCredentials
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.core.auth.GitHubOAuthClient
import com.gitmob.app.core.auth.SessionTokenProvider
import com.gitmob.app.core.auth.StoredAccounts
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.data.model.AuthenticatedUser
import com.gitmob.app.data.model.AccountState
import com.gitmob.app.data.model.DeviceFlowState
import com.gitmob.app.data.model.DeviceFlowStatus
import com.gitmob.app.core.network.GitHubIdentityClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 候选凭据先验证，成功后再提交；失败不删除当前账号。 */
@Singleton
class AuthRepository @Inject constructor(
    private val tokenStorage: TokenStorage,
    private val identity: GitHubIdentityClient,
    private val session: AccountSession,
    private val oauth: GitHubOAuthClient,
    private val tokens: SessionTokenProvider,
    private val libraryMigration: LibraryAccountMigration,
) {
    val accountState: StateFlow<AccountState> = session.state

    suspend fun initialize(started: AccountEpoch): ApiResult<Unit> = session.changes.withLock {
        safeCall {
            started.ensureCurrent()
            if (accountState.value.isLoaded) return@safeCall
            var stored = tokenStorage.snapshot()
            val active = stored.accounts.firstOrNull { it.key == stored.activeKey }
            if (active != null && !active.needsReauthentication) {
                val result = safeCall {
                    val credentials = tokenStorage.credentials(active.key)
                    if (active.id == null) {
                        val user = identity.verify(credentials.accessToken)
                        commit(user, credentials, started, previousKey = active.key)
                    }
                }
                if (result is ApiResult.Failure) {
                    started.ensureCurrent()
                    if (result.error == ApiError.Unauthorized) tokenStorage.requireReauthentication(active.key)
                    stored = tokenStorage.snapshot()
                    publish(stored.copy(activeKey = null))
                    return@safeCall
                }
                stored = tokenStorage.snapshot()
                if (!started.isCurrent) return@safeCall
            }
            started.ensureCurrent()
            publish(stored.copy(activeKey = stored.activeKey.takeIf { active?.needsReauthentication != true }))
        }
    }

    suspend fun loginWithToken(tokenInput: String): ApiResult<AuthenticatedUser> {
        val started = AccountContext.ensureCurrent()
        return session.changes.withLock {
            safeCall {
                started.ensureCurrent()
                val credentials = AccountCredentials(tokenInput.trim())
                val user = identity.verify(credentials.accessToken)
                commit(user, credentials, started)
                user
            }
        }
    }

    suspend fun switchAccount(key: String): ApiResult<Unit> {
        val started = AccountContext.ensureCurrent()
        return session.changes.withLock {
            safeCall {
                started.ensureCurrent()
                val selected = tokenStorage.snapshot().accounts.first { it.key == key }
                if (selected.needsReauthentication) throw com.gitmob.app.core.error.UnauthorizedException()
                val credentials = tokens.credentialsFor(key, started)
                val user = identity.verify(credentials.accessToken)
                check(selected.id == null || selected.id == user.id) { "Stored credential belongs to another identity" }
                commit(user, credentials, started, previousKey = key)
            }
        }
    }

    /** 不持有提交锁等待用户授权；关闭面板取消网络/轮询，提交阶段才短暂不可取消。 */
    fun authorizeDevice(): Flow<DeviceFlowState> = flow {
        val started = AccountContext.ensureCurrent()
        emit(DeviceFlowState.Requesting)
        val code = when (val result = safeCall { oauth.startDeviceFlow() }) {
            is ApiResult.Failure -> { emit(DeviceFlowState.Failed(result.error)); return@flow }
            is ApiResult.Success -> result.data
        }
        if (code.error != null) {
            emit(DeviceFlowState.Failed(deviceError(code.error)))
            return@flow
        }
        if (code.deviceCode.isBlank() || code.userCode.isBlank() ||
            code.verificationUri != "https://github.com/login/device" ||
            code.expiresIn !in 1..86_400 || code.interval !in 1..86_400
        ) {
            emit(DeviceFlowState.Failed(ApiError.Unknown("Device response is incomplete")))
            return@flow
        }
        val deadline = SystemClock.elapsedRealtime() + code.expiresIn * 1000
        var interval = code.interval
        var networkDelay = interval
        var status = DeviceFlowStatus.WAITING
        while (true) {
            started.ensureCurrent()
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                emit(DeviceFlowState.Failed(deviceError("expired_token")))
                return@flow
            }
            emit(DeviceFlowState.Waiting(code.userCode, code.verificationUri, deadline, status))
            delay(minOf(networkDelay * 1000, remaining))
            if (SystemClock.elapsedRealtime() >= deadline) continue
            val response = when (val result = safeCall { oauth.poll(code.deviceCode) }) {
                is ApiResult.Failure -> {
                    if (result.error == ApiError.NetworkError || result.error == ApiError.RateLimited) {
                        networkDelay = maxOf(interval, minOf(networkDelay * 2, 60))
                        status = DeviceFlowStatus.NETWORK_RETRY
                        continue
                    }
                    emit(DeviceFlowState.Failed(result.error))
                    return@flow
                }
                is ApiResult.Success -> result.data
            }
            networkDelay = interval
            when (response.error) {
                "authorization_pending" -> status = DeviceFlowStatus.WAITING
                "slow_down" -> {
                    interval = maxOf(interval + 5, response.interval ?: 0)
                    networkDelay = interval
                    status = DeviceFlowStatus.SLOW_DOWN
                }
                null -> {
                    val result = session.changes.withLock {
                        safeCall {
                            started.ensureCurrent()
                            val credentials = response.credentials()
                            val user = identity.verify(credentials.accessToken)
                            commit(user, credentials, started)
                            user
                        }
                    }
                    emit(when (result) {
                        is ApiResult.Success -> DeviceFlowState.Complete(result.data)
                        is ApiResult.Failure -> DeviceFlowState.Failed(result.error)
                    })
                    return@flow
                }
                else -> {
                    emit(DeviceFlowState.Failed(deviceError(response.error)))
                    return@flow
                }
            }
        }
    }

    private fun deviceError(error: String): ApiError = ApiError.UserVisible(when (error) {
        "expired_token" -> R.string.auth_device_expired
        "access_denied" -> R.string.auth_device_denied
        "device_flow_disabled" -> R.string.auth_device_disabled
        "incorrect_client_credentials", "incorrect_device_code", "unsupported_grant_type" -> R.string.auth_device_invalid
        else -> R.string.error_request_failed
    })

    private suspend fun commit(user: AuthenticatedUser, credentials: AccountCredentials, started: AccountEpoch, previousKey: String? = null) {
        currentCoroutineContext().ensureActive()
        started.ensureCurrent()
        withContext(NonCancellable) {
            started.ensureCurrent()
            libraryMigration.migrateLocked(previousKey, user.id.toString())
            tokenStorage.saveAccount(user, credentials, previousKey)
            publish(tokenStorage.snapshot())
        }
    }

    suspend fun isLoggedIn(): Boolean = session.state.value.activeAccount != null

    /** 持久化失败不改变会话；只移除当前账号，不自动切换到剩余账号。 */
    suspend fun logout(): ApiResult<Unit> {
        val started = AccountContext.ensureCurrent()
        return session.changes.withLock {
            safeCall {
                currentCoroutineContext().ensureActive()
                started.ensureCurrent()
                val key = checkNotNull(started.accountKey) { "An active account is required for logout" }
                withContext(NonCancellable) {
                    started.ensureCurrent()
                    tokenStorage.clearActiveAccount(key)
                    publish(tokenStorage.snapshot())
                }
            }
        }
    }

    private fun publish(stored: StoredAccounts) {
        session.replace(stored.accounts, stored.activeKey)
    }
}
