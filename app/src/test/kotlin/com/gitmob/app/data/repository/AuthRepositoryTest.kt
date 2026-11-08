package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.TokenStorage
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.SessionTokenProvider
import com.gitmob.app.core.auth.StoredAccounts
import com.gitmob.app.core.network.GitHubIdentityClient
import com.gitmob.app.data.model.AuthenticatedUser
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.data.model.AccountAuthMethod
import com.gitmob.app.core.auth.AccountCredentials
import io.mockk.coEvery
import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.testutil.AccountSessionRule
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {
    @get:Rule val accounts = AccountSessionRule(null)

    private fun repository(storage: TokenStorage, api: GitHubIdentityClient) = AuthRepository(
        storage, api, accounts.session, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
    )

    private fun client(server: MockWebServer) = GitHubIdentityClient(
        OkHttpClient(), Json { ignoreUnknownKeys = true },
        server.url("/").toString().removeSuffix("/"),
    )

    @Test
    fun `启动读取已保存账号并发布统一会话状态`() = runTest {
        val storage = mockk<TokenStorage>(relaxed = true)
        val account = GitHubAccount("42", 42, "U_42", "candidate")
        coEvery { storage.snapshot() } returns StoredAccounts(listOf(account), account.key)
        coEvery { storage.credentials(account.key) } returns AccountCredentials("candidate-token")
        val identity = mockk<GitHubIdentityClient>()
        val auth = repository(storage, identity)
        assertEquals(ApiResult.Success(Unit), auth.initialize(accounts.session.epoch))
        assertEquals(account, auth.accountState.value.activeAccount)
        assertTrue(auth.accountState.value.isLoaded)
        coVerify(exactly = 0) { identity.verify(any()) }
    }

    @Test
    fun `旧账号启动断网保留凭据并回账号选择态`() = runTest {
        val storage = mockk<TokenStorage>(relaxed = true)
        val account = GitHubAccount("legacy:old", null, null, "old", authMethod = AccountAuthMethod.LEGACY)
        coEvery { storage.snapshot() } returns StoredAccounts(listOf(account), account.key)
        coEvery { storage.credentials(account.key) } returns AccountCredentials("legacy-token")
        val identity = mockk<GitHubIdentityClient>()
        coEvery { identity.verify("legacy-token") } throws java.io.IOException("Offline")
        val auth = repository(storage, identity)
        assertEquals(ApiResult.Success(Unit), auth.initialize(accounts.session.epoch))
        assertEquals(listOf(account), auth.accountState.value.accounts)
        assertEquals(null, auth.accountState.value.activeAccount)
        coVerify(exactly = 0) { storage.saveAccount(any(), any(), any(), any()) }
        coVerify(exactly = 0) { storage.clearActiveAccount(any()) }
        coVerify(exactly = 0) { storage.requireReauthentication(any()) }
    }

    @Test
    fun `候选Token真实验证后才保存且不读取旧主页缓存`() = runTest(accounts.context) {
        val server = MockWebServer().apply { start() }
        try {
            val storage = mockk<TokenStorage>(relaxed = true)
            server.enqueue(MockResponse().setBody("""{"id":42,"node_id":"U_42","login":"candidate","name":null}"""))
            val result = repository(storage, client(server)).loginWithToken(" candidate-token ") as ApiResult.Success
            assertEquals(42L, result.data.id)
            val request = server.takeRequest()
            assertEquals("/user", request.path)
            assertEquals("Bearer candidate-token", request.getHeader("Authorization"))
            assertEquals("2026-03-10", request.getHeader("X-GitHub-Api-Version"))
            coVerify(exactly = 1) {
                storage.saveAccount(match { it.id == 42L && it.login == "candidate" },
                    match { it.accessToken == "candidate-token" }, null, true)
            }
            coVerify(exactly = 0) { storage.credentials(any()) }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `身份缺失或请求失败不修改当前账号`() = runTest(accounts.context) {
        val server = MockWebServer().apply { start() }
        try {
            val storage = mockk<TokenStorage>(relaxed = true)
            val auth = repository(storage, client(server))
            server.enqueue(MockResponse().setResponseCode(401))
            assertEquals(ApiResult.Failure(ApiError.Unauthorized), auth.loginWithToken("candidate"))
            server.enqueue(MockResponse().setResponseCode(403))
            assertEquals(ApiResult.Failure(ApiError.Forbidden), auth.loginWithToken("candidate"))
            server.enqueue(MockResponse().setBody("""{"login":"candidate"}"""))
            assertTrue(auth.loginWithToken("candidate") is ApiResult.Failure)
            coVerify(exactly = 0) { storage.saveAccount(any(), any(), any(), any()) }
            coVerify(exactly = 0) { storage.clearActiveAccount(any()) }
            coVerify(exactly = 0) { storage.requireReauthentication(any()) }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `凭据持久化失败不清理旧账号缓存`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val storage = mockk<TokenStorage>(relaxed = true)
            coEvery { storage.saveAccount(any(), any(), any(), any()) } throws java.io.IOException("Storage unavailable")
            val account = GitHubAccount("1", 1, "U_1", "existing")
            accounts.session.replace(listOf(account), account.key)
            val started = accounts.session.epoch
            val cache = MemoryCache<String, String>(60_000)
            val auth = repository(storage, client(server))
            server.enqueue(MockResponse().setBody("""{"id":42,"node_id":"U_42","login":"candidate"}"""))
            kotlinx.coroutines.withContext(accounts.context) {
                cache.set("viewer", "existing")
                assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.auth_storage_write_failed)), auth.loginWithToken("candidate"))
                assertEquals("existing", cache.get("viewer"))
            }
            assertTrue(started.isCurrent)
            assertEquals(account, auth.accountState.value.activeAccount)
            coVerify(exactly = 0) { storage.clearActiveAccount(any()) }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `退出持久化失败不清理当前账号缓存`() = runTest {
        val storage = mockk<TokenStorage>(relaxed = true)
        coEvery { storage.clearActiveAccount("1") } throws java.io.IOException("Storage unavailable")
        val account = GitHubAccount("1", 1, "U_1", "existing")
        accounts.session.replace(listOf(account), account.key)
        val started = accounts.session.epoch
        val cache = MemoryCache<String, String>(60_000)
        val auth = repository(storage, mockk())
        kotlinx.coroutines.withContext(accounts.context) {
            cache.set("viewer", "existing")
            assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.auth_storage_write_failed)), auth.logout())
            assertEquals("existing", cache.get("viewer"))
        }
        assertTrue(started.isCurrent)
        assertEquals(account, auth.accountState.value.activeAccount)
    }

    @Test
    fun `退出仅结束当前会话删除当前凭据且不自动激活剩余账号`() = runTest {
        val tokenStorage = mockk<TokenStorage>(relaxed = true)
        val current = GitHubAccount("1", 1, "U_1", "current")
        val remaining = GitHubAccount("2", 2, "U_2", "remaining")
        accounts.session.replace(listOf(current, remaining), current.key)
        val started = accounts.session.epoch
        coEvery { tokenStorage.snapshot() } returns StoredAccounts(listOf(remaining), null)
        val auth = repository(tokenStorage, mockk())

        val result = kotlinx.coroutines.withContext(accounts.context) { auth.logout() }

        assertEquals(ApiResult.Success(Unit), result)
        assertFalse(started.isCurrent)
        assertEquals(listOf(remaining), auth.accountState.value.accounts)
        assertEquals(null, auth.accountState.value.activeAccount)
        coVerify(exactly = 1) { tokenStorage.clearActiveAccount(current.key) }
        coVerify(exactly = 0) { tokenStorage.activate(any()) }
    }

    @Test
    fun `旧账号启动验证numeric身份后迁移原key而不按旧标签猜测`() = runTest {
        val storage = mockk<TokenStorage>(relaxed = true)
        val legacy = GitHubAccount("legacy:old-label", null, null, "old-label", authMethod = AccountAuthMethod.LEGACY)
        val user = AuthenticatedUser(42, "U_42", "real-login", null, null)
        val migrated = GitHubAccount("42", 42, "U_42", "real-login", authMethod = AccountAuthMethod.LEGACY)
        val credentials = AccountCredentials("legacy-token", authMethod = AccountAuthMethod.LEGACY)
        coEvery { storage.snapshot() } returns StoredAccounts(listOf(legacy), legacy.key) andThen StoredAccounts(listOf(migrated), migrated.key)
        coEvery { storage.credentials(legacy.key) } returns credentials
        val identity = mockk<GitHubIdentityClient>()
        coEvery { identity.verify("legacy-token") } returns user
        val started = accounts.session.epoch

        assertEquals(ApiResult.Success(Unit), repository(storage, identity).initialize(started))

        assertFalse(started.isCurrent)
        assertEquals(migrated, accounts.session.state.value.activeAccount)
        coVerify(exactly = 1) { identity.verify("legacy-token") }
        coVerify(exactly = 1) { storage.saveAccount(user, credentials, legacy.key, true) }
        coVerify(exactly = 0) { storage.clearActiveAccount(any()) }
    }

    @Test
    fun `旧凭据401只标记重新授权保留账号而403或迁移写入失败不作失效删除`() = runTest {
        for (failure in listOf("unauthorized", "forbidden", "storage")) {
            val session = AccountSession()
            val storage = mockk<TokenStorage>(relaxed = true)
            val legacy = GitHubAccount("legacy:old", null, null, "old", authMethod = AccountAuthMethod.LEGACY)
            var stored = StoredAccounts(listOf(legacy), legacy.key)
            coEvery { storage.snapshot() } coAnswers { stored }
            coEvery { storage.credentials(legacy.key) } returns AccountCredentials("legacy-token")
            coEvery { storage.requireReauthentication(legacy.key) } coAnswers {
                stored = StoredAccounts(listOf(legacy.copy(needsReauthentication = true)), null)
            }
            val identity = mockk<GitHubIdentityClient>()
            when (failure) {
                "unauthorized" -> coEvery { identity.verify(any()) } throws com.gitmob.app.core.error.UnauthorizedException()
                "forbidden" -> coEvery { identity.verify(any()) } throws com.gitmob.app.core.error.HttpStatusException(403, "Forbidden")
                else -> {
                    coEvery { identity.verify(any()) } returns AuthenticatedUser(42, "U_42", "real-login", null, null)
                    coEvery { storage.saveAccount(any(), any(), any(), any()) } throws java.io.IOException("Storage unavailable")
                }
            }
            val auth = AuthRepository(storage, identity, session, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
            try {
                assertEquals(ApiResult.Success(Unit), auth.initialize(session.epoch))
                assertEquals(null, auth.accountState.value.activeAccount)
                assertEquals(legacy.key, auth.accountState.value.accounts.single().key)
                assertEquals(failure == "unauthorized", auth.accountState.value.accounts.single().needsReauthentication)
                coVerify(exactly = if (failure == "unauthorized") 1 else 0) { storage.requireReauthentication(legacy.key) }
                coVerify(exactly = 0) { storage.clearActiveAccount(any()) }
            } finally {
                session.epoch.job.cancel()
            }
        }
    }

    @Test
    fun `迟到启动快照不能替换已建立的新账号会话`() = runTest {
        val storage = mockk<TokenStorage>(relaxed = true)
        val snapshot = CompletableDeferred<StoredAccounts>()
        coEvery { storage.snapshot() } coAnswers { withContext(NonCancellable) { snapshot.await() } }
        val started = accounts.session.epoch
        val auth = repository(storage, mockk())
        val initialization = async {
            try {
                auth.initialize(started)
                false
            } catch (_: CancellationException) { true }
        }
        runCurrent()
        val current = GitHubAccount("2", 2, "U_2", "current")
        accounts.session.replace(listOf(current), current.key)
        snapshot.complete(StoredAccounts(emptyList(), null))
        assertTrue(initialization.await())
        assertEquals(current, accounts.session.state.value.activeAccount)
    }

    @Test
    fun `切换numeric账号拒绝同login不同身份且保留当前会话`() = runTest {
        val current = GitHubAccount("1", 1, "U_1", "current")
        val selected = GitHubAccount("2", 2, "U_2", "same-login")
        accounts.session.replace(listOf(current, selected), current.key)
        val epoch = accounts.session.epoch
        val storage = mockk<TokenStorage>(relaxed = true)
        coEvery { storage.snapshot() } returns StoredAccounts(listOf(current, selected), current.key)
        val tokens = mockk<SessionTokenProvider>()
        coEvery { tokens.credentialsFor(selected.key, epoch) } returns AccountCredentials("selected-token")
        val identity = mockk<GitHubIdentityClient>()
        coEvery { identity.verify("selected-token") } returns AuthenticatedUser(3, "U_3", selected.login, null, null)
        val auth = AuthRepository(storage, identity, accounts.session, mockk(relaxed = true), tokens, mockk(relaxed = true))

        assertTrue(withContext(AccountContext(epoch)) { auth.switchAccount(selected.key) } is ApiResult.Failure)

        assertSame(epoch, accounts.session.epoch)
        assertEquals(current, accounts.session.state.value.activeAccount)
        coVerify(exactly = 0) { storage.saveAccount(any(), any(), any(), any()) }
        coVerify(exactly = 0) { storage.clearActiveAccount(any()) }
    }

    @Test
    fun `凭据加密失败提示本地保存错误且保留当前会话`() = runTest(accounts.context) {
        val storage = mockk<TokenStorage>(relaxed = true)
        val identity = mockk<GitHubIdentityClient>()
        coEvery { identity.verify("candidate") } returns AuthenticatedUser(2, "U_2", "candidate", null, null)
        coEvery { storage.saveAccount(any(), any(), any(), any()) } throws java.security.GeneralSecurityException("Keystore unavailable")
        val started = accounts.session.epoch
        assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.auth_storage_write_failed)), repository(storage, identity).loginWithToken("candidate"))
        assertSame(started, accounts.session.epoch)
        coVerify(exactly = 0) { storage.clearActiveAccount(any()) }
    }

    @Test
    fun `切换账号快照读取失败不请求身份且网络失败不保存`() = runTest(accounts.context) {
        val storage = mockk<TokenStorage>()
        val identity = mockk<GitHubIdentityClient>()
        coEvery { storage.snapshot() } throws java.io.IOException("disk")
        val auth = repository(storage, identity)
        assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.auth_storage_read_failed)), auth.switchAccount("2"))
        coVerify(exactly = 0) { identity.verify(any()) }
        coEvery { identity.verify("candidate") } throws java.io.IOException("offline")
        assertEquals(ApiResult.Failure(ApiError.NetworkError), auth.loginWithToken("candidate"))
        coVerify(exactly = 0) { storage.saveAccount(any(), any(), any(), any()) }
    }

}
