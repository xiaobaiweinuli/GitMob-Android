package com.gitmob.app.core.auth

import androidx.datastore.preferences.core.emptyPreferences
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.GitHubIdentityClient
import com.gitmob.app.data.model.AccountAuthMethod
import com.gitmob.app.data.model.AuthenticatedUser
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTokenProviderTest {
    private val account = GitHubAccount("1", 1, "U_1", "current", authMethod = AccountAuthMethod.DEVICE_FLOW)
    @get:Rule val accounts = AccountSessionRule(account)

    private val storage = mockk<TokenStorage>(relaxed = true)
    private val oauth = mockk<GitHubOAuthClient>()
    private val identity = mockk<GitHubIdentityClient>()
    private val user = AuthenticatedUser(1, "U_1", "renamed", "Name", null)
    private var credentials = expiredCredentials()
    private var stored = StoredAccounts(listOf(account), account.key)

    private fun provider(): SessionTokenProvider {
        coEvery { storage.credentials(account.key) } coAnswers { credentials }
        coEvery { storage.snapshot() } coAnswers { stored }
        coEvery { storage.saveCredentials(account.key, any()) } coAnswers {
            credentials = secondArg()
            emptyPreferences()
        }
        coEvery { storage.updateIdentity(any()) } coAnswers {
            val updated = firstArg<AuthenticatedUser>()
            stored = stored.copy(accounts = listOf(account.copy(login = updated.login, name = updated.name)))
        }
        coEvery { storage.requireReauthentication(account.key) } coAnswers {
            stored = StoredAccounts(listOf(account.copy(needsReauthentication = true)), null)
        }
        coEvery { identity.verify("new-access") } returns user
        return SessionTokenProvider(storage, accounts.session, oauth, identity)
    }

    @Test
    fun `并发请求只刷新一次且先持久化旋转令牌对再验证身份`() = runTest(accounts.context) {
        val provider = provider()
        val response = CompletableDeferred<OAuthTokenResponse>()
        coEvery { oauth.refresh("old-refresh") } coAnswers { response.await() }
        val epoch = accounts.session.epoch
        val first = async { provider.getToken() }
        val second = async { provider.getToken() }
        runCurrent()
        coVerify(exactly = 1) { oauth.refresh("old-refresh") }

        response.complete(rotatedResponse())
        assertEquals(listOf("new-access", "new-access"), awaitAll(first, second))

        assertSame(epoch, accounts.session.epoch)
        assertEquals("renamed", epoch.login)
        assertEquals("new-access", credentials.accessToken)
        assertEquals("new-refresh", credentials.refreshToken)
        assertEquals(AccountAuthMethod.DEVICE_FLOW, credentials.authMethod)
        assertTrue(credentials.expiresAtMillis!! > System.currentTimeMillis())
        coVerify(exactly = 1) { storage.saveCredentials("1", any()) }
        coVerify(exactly = 1) { identity.verify("new-access") }
        coVerifyOrder {
            storage.saveCredentials("1", any())
            identity.verify("new-access")
            storage.updateIdentity(user)
        }
    }

    @Test
    fun `旋转令牌持久化失败不发送业务PATCH也不借用未保存令牌`() = runTest(accounts.context) {
        val provider = provider()
        coEvery { oauth.refresh("old-refresh") } returns rotatedResponse()
        coEvery { storage.saveCredentials("1", any()) } throws IOException("Storage unavailable")
        val epoch = accounts.session.epoch
        val server = MockWebServer().apply { start() }
        try {
            val api = GHApiClient(OkHttpClient(), Json, provider, server.url("/").toString().removeSuffix("/"))
            val result = safeCall { api.patch<Unit, kotlinx.serialization.json.JsonObject>("/user", buildJsonObject { put("name", "Changed") }) }

            assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.auth_storage_write_failed)), result)
            assertEquals(0, server.requestCount)
            assertEquals("old-access", credentials.accessToken)
            assertEquals("old-refresh", credentials.refreshToken)
            assertSame(epoch, accounts.session.epoch)
            coVerify(exactly = 0) { identity.verify(any()) }
            coVerify(exactly = 0) { storage.requireReauthentication(any()) }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `刷新成功身份验证断网仍保留已旋转令牌对且不删除账号`() = runTest(accounts.context) {
        val provider = provider()
        coEvery { oauth.refresh("old-refresh") } returns rotatedResponse()
        coEvery { identity.verify("new-access") } throws IOException("Offline")
        val epoch = accounts.session.epoch

        assertEquals(ApiResult.Failure(ApiError.NetworkError), safeCall { provider.getToken() })

        assertEquals("new-access", credentials.accessToken)
        assertEquals("new-refresh", credentials.refreshToken)
        assertSame(epoch, accounts.session.epoch)
        assertEquals(account, accounts.session.state.value.activeAccount)
        coVerify(exactly = 0) { storage.clearActiveAccount(any()) }
        coVerify(exactly = 0) { storage.requireReauthentication(any()) }
        coVerify(exactly = 0) { storage.updateIdentity(any()) }
    }

    @Test
    fun `bad_refresh_token标记重新授权但保留本地凭据和账号`() = runTest(accounts.context) {
        val provider = provider()
        coEvery { oauth.refresh("old-refresh") } returns OAuthTokenResponse(error = "bad_refresh_token")
        val epoch = accounts.session.epoch

        assertEquals(ApiResult.Failure(ApiError.Unauthorized), safeCall { provider.getToken() })

        assertFalse(epoch.isCurrent)
        assertEquals(null, accounts.session.state.value.activeAccount)
        assertTrue(accounts.session.state.value.accounts.single().needsReauthentication)
        assertEquals("old-access", credentials.accessToken)
        coVerify(exactly = 0) { storage.saveCredentials(any(), any()) }
        coVerify(exactly = 0) { storage.clearActiveAccount(any()) }
        coVerify(exactly = 0) { identity.verify(any()) }
    }

    @Test
    fun `刷新返回另一numeric身份拒绝令牌即使login与原账号相同`() = runTest(accounts.context) {
        val provider = provider()
        coEvery { oauth.refresh("old-refresh") } returns rotatedResponse()
        coEvery { identity.verify("new-access") } returns user.copy(id = 2, login = account.login)

        assertEquals(ApiResult.Failure(ApiError.Unauthorized), safeCall { provider.getToken() })

        assertEquals(null, accounts.session.state.value.activeAccount)
        assertTrue(accounts.session.state.value.accounts.single().needsReauthentication)
        coVerify(exactly = 1) { storage.saveCredentials("1", any()) }
        coVerify(exactly = 0) { storage.updateIdentity(any()) }
    }

    @Test
    fun `未过期和无期限凭据不刷新而不可刷新过期凭据要求重新授权`() = runTest(accounts.context) {
        val provider = provider()
        for (valid in listOf(AccountCredentials("no-expiry"), AccountCredentials("future", expiresAtMillis = System.currentTimeMillis() + 60_000))) {
            credentials = valid
            assertEquals(valid.accessToken, provider.getToken())
        }
        credentials = expiredCredentials().let { AccountCredentials(it.accessToken, expiresAtMillis = it.expiresAtMillis) }
        assertEquals(ApiResult.Failure(ApiError.Unauthorized), safeCall { provider.getToken() })
        coVerify(exactly = 0) { oauth.refresh(any()) }
        coVerify(exactly = 0) { identity.verify(any()) }
        coVerify(exactly = 1) { storage.requireReauthentication("1") }
    }

    @Test
    fun `刷新缺少新refresh_token拒绝保存且不发送业务请求`() = runTest(accounts.context) {
        val provider = provider()
        coEvery { oauth.refresh("old-refresh") } returns OAuthTokenResponse(accessToken = "new-access", tokenType = "bearer", expiresIn = 3600)

        assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.auth_response_invalid)), safeCall { provider.getToken() })

        assertEquals("old-access", credentials.accessToken)
        coVerify(exactly = 0) { storage.saveCredentials(any(), any()) }
        coVerify(exactly = 0) { identity.verify(any()) }
    }

    @Test
    fun `发送前刷新只发一次PATCH且拒绝响应不盲目重放写入`() = runTest(accounts.context) {
        val provider = provider()
        coEvery { oauth.refresh("old-refresh") } returns rotatedResponse()
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(403))
            val api = GHApiClient(OkHttpClient(), Json, provider, server.url("/").toString().removeSuffix("/"))

            assertEquals(ApiResult.Failure(ApiError.Forbidden), safeCall {
                api.patch<Unit, kotlinx.serialization.json.JsonObject>("/user", buildJsonObject { put("name", "Changed") })
            })

            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("Bearer new-access", request.getHeader("Authorization"))
            coVerify(exactly = 1) { oauth.refresh("old-refresh") }
        } finally {
            server.shutdown()
        }
    }

    private fun expiredCredentials() = AccountCredentials(
        "old-access", "old-refresh", System.currentTimeMillis() - 1,
        System.currentTimeMillis() + 86_400_000, AccountAuthMethod.DEVICE_FLOW,
    )

    private fun rotatedResponse() = OAuthTokenResponse(
        accessToken = "new-access", refreshToken = "new-refresh", tokenType = "bearer",
        expiresIn = 3600, refreshExpiresIn = 86_400,
    )

    @Test
    fun `本地读取IO或密钥失败不触发刷新也不使账号失效`() = runTest(accounts.context) {
        val provider = provider()
        val epoch = accounts.session.epoch
        for (error in listOf(IOException("disk"), java.security.GeneralSecurityException("key"), IllegalArgumentException("corrupt"))) {
            coEvery { storage.credentials("1") } throws error
            assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.auth_storage_read_failed)), safeCall { provider.getToken() })
        }
        assertSame(epoch, accounts.session.epoch)
        coVerify(exactly = 0) { oauth.refresh(any()) }
        coVerify(exactly = 0) { storage.requireReauthentication(any()) }
        coVerify(exactly = 0) { identity.verify(any()) }
    }

    @Test
    fun `本地操作取消继续传播且授权失效保留原分类`() = runTest {
        try {
            localAccountOperation<Unit>(R.string.auth_storage_read_failed) { throw kotlinx.coroutines.CancellationException("closed") }
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(ApiResult.Failure(ApiError.Unauthorized), safeCall {
            localAccountOperation<Unit>(R.string.auth_storage_read_failed) { throw com.gitmob.app.core.error.UnauthorizedException() }
        })
    }

}
