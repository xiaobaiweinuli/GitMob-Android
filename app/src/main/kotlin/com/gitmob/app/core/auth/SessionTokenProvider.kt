package com.gitmob.app.core.auth

import androidx.annotation.StringRes
import com.gitmob.app.R
import com.gitmob.app.core.error.HttpStatusException
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.UnauthorizedException
import kotlinx.coroutines.CancellationException
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

    override suspend fun onUnauthorized(token: String, epoch: AccountEpoch) {
        session.changes.withLock {
            val key = epoch.accountKey ?: return
            if (!epoch.isCurrent) return
            val current = localAccountOperation(R.string.auth_storage_read_failed) { storage.credentials(key) }
            // 旧请求的 401 不能使已刷新或重新登录后的凭据失效。
            if (current.accessToken == token) invalidate(key)
        }
    }

    /** 调用方持有 session.changes；切换目标账号与活动请求复用相同刷新逻辑。 */
    internal suspend fun credentialsFor(key: String, started: AccountEpoch): AccountCredentials {
        started.ensureCurrent()
        val credentials = localAccountOperation(R.string.auth_storage_read_failed) { storage.credentials(key) }
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
            if (next.refreshToken == null) throw UserVisibleException(R.string.auth_response_invalid)
            // 先持久化旋转后的令牌对；身份验证断网时也不能丢掉已失效旧对的替代品。
            localAccountOperation(R.string.auth_storage_write_failed) { storage.saveCredentials(key, next) }
            val user = try {
                identity.verify(next.accessToken)
            } catch (e: HttpStatusException) {
                if (e.code == 401) invalidate(key)
                throw e
            }
            if (user.id.toString() != key) {
                invalidate(key)
                throw UnauthorizedException()
            }
            localAccountOperation(R.string.auth_storage_write_failed) { storage.updateIdentity(user) }
            session.updateAccounts(localAccountOperation(R.string.auth_storage_read_failed) { storage.snapshot() }.accounts)
            next
        }
    }

    /** 调用方持有 session.changes；失效候选账号时保留当前活动账号。 */
    internal suspend fun invalidate(key: String) {
        withContext(NonCancellable) {
            localAccountOperation(R.string.auth_storage_write_failed) { storage.requireReauthentication(key) }
            val stored = localAccountOperation(R.string.auth_storage_read_failed) { storage.snapshot() }
            if (session.epoch.accountKey == key) session.replace(stored.accounts, stored.activeKey)
            else session.updateAccounts(stored.accounts)
        }
    }
}

/** 仅包裹本地账号操作，避免磁盘 IO、密钥或损坏数据被误报为网络失败。 */
internal suspend inline fun <T> localAccountOperation(@StringRes messageRes: Int, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: UnauthorizedException) {
    throw e
} catch (_: Exception) {
    // 原始异常可能包含持久化内容，不挂到会被 safeCall 打印的 cause 上。
    throw UserVisibleException(messageRes)
}
