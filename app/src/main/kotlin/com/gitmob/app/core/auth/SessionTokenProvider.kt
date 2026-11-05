package com.gitmob.app.core.auth

import com.gitmob.app.core.error.UnauthorizedException
import com.gitmob.app.core.network.GitHubIdentityClient
import com.gitmob.app.data.model.AccountAuthMethod
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 刷新在账号提交锁内合并；只在发送前刷新，绝不盲目重放写请求。 */
@Singleton
class SessionTokenProvider @Inject constructor(
    private val storage: TokenStorage,
    private val session: AccountSession,
    private val oauth: GitHubOAuthClient,
    private val identity: GitHubIdentityClient,
) : AccessTokenProvider {
    override suspend fun getToken(): String? {
        val captured = AccountContext.ensureCurrent()
        val key = captured.accountKey ?: throw UnauthorizedException()
        return session.changes.withLock {
            captured.ensureCurrent()
            credentialsFor(key, captured).accessToken.also { captured.ensureCurrent() }
        }
    }

    /** 调用方持有 session.changes；切换目标账号与活动请求复用相同刷新逻辑。 */
    internal suspend fun credentialsFor(key: String, started: AccountEpoch): AccountCredentials {
        started.ensureCurrent()
        val credentials = storage.credentials(key)
        val now = System.currentTimeMillis()
        if (credentials.expiresAtMillis == null || credentials.expiresAtMillis > now + 30_000) return credentials
        if (credentials.authMethod != AccountAuthMethod.DEVICE_FLOW || credentials.refreshToken == null ||
            credentials.refreshExpiresAtMillis?.let { it <= now } == true
        ) {
            invalidate(key)
            throw UnauthorizedException()
        }
        return withContext(NonCancellable) {
            started.ensureCurrent()
            val response = oauth.refresh(credentials.refreshToken)
            if (response.error == "bad_refresh_token") {
                invalidate(key)
                throw UnauthorizedException()
            }
            val next = response.credentials()
            check(next.refreshToken != null) { "Refresh response is missing its new refresh token" }
            // 先持久化旋转后的令牌对；身份验证断网时也不能丢掉已失效旧对的替代品。
            storage.saveCredentials(key, next)
            val user = identity.verify(next.accessToken)
            if (user.id.toString() != key) {
                invalidate(key)
                throw UnauthorizedException()
            }
            storage.updateIdentity(user)
            session.updateAccounts(storage.snapshot().accounts)
            next
        }
    }

    private suspend fun invalidate(key: String) {
        withContext(NonCancellable) {
            storage.requireReauthentication(key)
            val stored = storage.snapshot()
            if (session.epoch.accountKey == key) session.replace(stored.accounts, stored.activeKey)
            else session.updateAccounts(stored.accounts)
        }
    }
}
