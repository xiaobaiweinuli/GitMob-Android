package com.gitmob.app.core.network

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountCredentials
import com.gitmob.app.core.auth.SessionTokenProvider
import com.gitmob.app.core.auth.StoredAccounts
import com.gitmob.app.core.auth.TokenStorage
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.graphql.RepositoryPermissionQuery
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class AuthenticationFailureTest {
    private val account = GitHubAccount("1", 1, "U_1", "current")
    private val other = GitHubAccount("2", 2, "U_2", "other")
    @get:Rule val accounts = AccountSessionRule(account)
    private val storage = mockk<TokenStorage>(relaxed = true)
    private var stored = StoredAccounts(listOf(account, other), account.key)
    private lateinit var server: MockWebServer
    private lateinit var provider: SessionTokenProvider
    private lateinit var api: GHApiClient
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        accounts.session.updateAccounts(stored.accounts)
        coEvery { storage.snapshot() } coAnswers { stored }
        coEvery { storage.credentials(account.key) } returns AccountCredentials("current-access")
        coEvery { storage.requireReauthentication(account.key) } coAnswers {
            stored = StoredAccounts(listOf(account.copy(needsReauthentication = true), other), null)
        }
        provider = SessionTokenProvider(storage, accounts.session, mockk(), mockk())
        server = MockWebServer().apply { start() }
        api = GHApiClient(client, Json, provider, server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() { server.shutdown() }

    private fun assertReauthentication() {
        assertNull(accounts.session.state.value.activeAccount)
        assertEquals(listOf(account.copy(needsReauthentication = true), other), accounts.session.state.value.accounts)
        coVerify(exactly = 1) { storage.requireReauthentication(account.key) }
        coVerify(exactly = 0) { storage.clearActiveAccount(any()) }
    }

    @Test
    fun `REST写请求401退出活动会话并且不重放请求`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ApiResult.Failure(ApiError.Unauthorized), safeCall { api.patchNoBody<Unit>("/notifications/threads/1") })
        assertReauthentication()
        assertEquals(1, server.requestCount)
        assertEquals("Bearer current-access", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `下载地址接口401同样进入重新授权`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ApiResult.Failure(ApiError.Unauthorized), safeCall { api.resolveRestDownloadUrl("/repos/o/r/actions/runs/1/logs") })
        assertReauthentication()
    }

    @Test
    fun `文件上传401同样进入重新授权且不重传`() = runTest(accounts.context) {
        val file = File.createTempFile("auth-upload", ".txt").apply { writeText("release asset") }
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            assertEquals(ApiResult.Failure(ApiError.Unauthorized), safeCall {
                api.readFileUpload(server.url("/assets").toString(), "text/plain", file, file.length()) { _, _ -> }
            })
            assertReauthentication()
            assertEquals(1, server.requestCount)
        } finally { file.delete() }
    }

    @Test
    fun `匿名请求及跨域重定向的401不改变登录状态`() = runTest(accounts.context) {
        val epoch = accounts.session.epoch
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ApiResult.Failure(ApiError.Unauthorized), safeCall { api.getPublic<Unit>("/public") })
        assertNull(server.takeRequest().getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ApiResult.Failure(ApiError.Unauthorized), safeCall { api.getPublicImageBytes(server.url("/image").toString(), 1024) })
        assertNull(server.takeRequest().getHeader("Authorization"))
        MockWebServer().use { external ->
            external.start()
            external.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", external.url("/download")))
            assertEquals(ApiResult.Failure(ApiError.Unauthorized), safeCall { api.get<Unit>("/redirect") })
            assertNull(external.takeRequest().getHeader("Authorization"))
        }
        assertSame(epoch, accounts.session.epoch)
        assertEquals(account, accounts.session.state.value.activeAccount)
        coVerify(exactly = 0) { storage.requireReauthentication(any()) }
    }

    @Test
    fun `REST权限限流及服务错误不退出登录`() = runTest(accounts.context) {
        val epoch = accounts.session.epoch
        for (code in listOf(403, 404, 429, 500, 503)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertTrue(safeCall { api.get<Unit>("/user") } is ApiResult.Failure)
        }
        assertSame(epoch, accounts.session.epoch)
        coVerify(exactly = 0) { storage.requireReauthentication(any()) }
    }

    @Test
    fun `GraphQL字段错误保留会话而HTTP401触发重新授权`() = runTest(accounts.context) {
        val epoch = accounts.session.epoch
        ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).okHttpClient(client)
            .addHttpInterceptor(AuthHttpInterceptor(provider)).build().use { graph ->
                server.enqueue(MockResponse().setBody("""{"errors":[{"message":"Resource not accessible","extensions":{"type":"FORBIDDEN"}}]}"""))
                assertTrue(graph.query(RepositoryPermissionQuery("owner", "repo")).executeAsApiResult() is ApiResult.Failure)
                assertSame(epoch, accounts.session.epoch)
                coVerify(exactly = 0) { storage.requireReauthentication(any()) }
                server.enqueue(MockResponse().setResponseCode(401))
                try {
                    graph.query(RepositoryPermissionQuery("owner", "repo")).executeAsApiResult()
                    fail("Old account request must be cancelled")
                } catch (_: CancellationException) { }
            }
        assertReauthentication()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `切换账号后迟到的401不会清除新账号`() = runTest {
        val requested = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested.complete(Unit)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return MockResponse().setResponseCode(401)
            }
        }
        val old = accounts.session.epoch
        val request = async(AccountContext(old)) { api.get<Unit>("/user") }
        try {
            requested.await()
            accounts.session.replace(stored.accounts, other.key)
        } finally { release.countDown() }
        try { request.await(); fail("Old request must be cancelled") } catch (_: CancellationException) { }
        assertEquals(other, accounts.session.state.value.activeAccount)
        coVerify(exactly = 0) { storage.requireReauthentication(any()) }
    }
}
