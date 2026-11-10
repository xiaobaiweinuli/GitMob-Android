package com.gitmob.app.core.network

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.testutil.TestAccessTokenProvider
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.data.model.BranchCreationSpec
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.graphql.RepositoryPermissionQuery
import com.gitmob.app.testutil.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AccountNetworkIsolationTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var server: MockWebServer
    private lateinit var session: AccountSession
    private val first = GitHubAccount("1", 1, "U_1", "first")
    private val second = GitHubAccount("2", 2, "U_2", "second")
    private val client = OkHttpClient()
    private val provider = object : TestAccessTokenProvider {
        override suspend fun getToken() = "token-${AccountContext.ensureCurrent().accountKey}"
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        session = AccountSession().apply { replace(listOf(first, second), first.key) }
    }

    @After
    fun tearDown() {
        session.epoch.job.cancel()
        server.shutdown()
    }

    private fun api(tokens: TestAccessTokenProvider = provider) = GHApiClient(
        client, Json { ignoreUnknownKeys = true }, tokens, server.url("/").toString().removeSuffix("/"),
    )

    private fun apollo(tokens: TestAccessTokenProvider = provider) = ApolloClient.Builder()
        .serverUrl(server.url("/graphql").toString()).okHttpClient(client)
        .addHttpInterceptor(AuthHttpInterceptor(tokens)).build()

    @Test
    fun `REST和Apollo拒绝无上下文且不借用活动账号`() = runTest {
        var rejected = false
        try { api().get<Unit>("/user") } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        apollo().use { graph ->
            assertTrue(graph.query(RepositoryPermissionQuery("owner", "repo")).executeAsApiResult() is ApiResult.Failure)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `REST令牌等待期间切换后不能发出旧请求`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokens = object : TestAccessTokenProvider {
            override suspend fun getToken(): String {
                waiting.complete(Unit)
                release.await()
                return "token-1"
            }
        }
        val old = session.epoch
        val work = async(AccountContext(old)) { api(tokens).get<Unit>("/user") }
        waiting.await()
        session.replace(listOf(first, second), second.key)
        release.complete(Unit)
        var cancelled = false
        try { work.await() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `Apollo令牌等待期间切换后不能发出旧请求`() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokens = object : TestAccessTokenProvider {
            override suspend fun getToken(): String {
                waiting.complete(Unit)
                release.await()
                return "token-1"
            }
        }
        server.enqueue(MockResponse().setBody("""{"data":{"repository":{"viewerPermission":"READ"}}}"""))
        apollo(tokens).use { graph ->
            val work = async(AccountContext(session.epoch)) {
                graph.query(RepositoryPermissionQuery("owner", "repo")).executeAsApiResult()
            }
            waiting.await()
            session.replace(listOf(first, second), second.key)
            release.complete(Unit)
            var cancelled = false
            try { work.await() } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `创建分支第二步前切换不使用新账号提交旧操作`() = runTest {
        var reads = 0
        val tokens = object : TestAccessTokenProvider {
            override suspend fun getToken(): String {
                val captured = AccountContext.ensureCurrent()
                if (++reads == 2) session.replace(listOf(first, second), second.key)
                return "token-${captured.accountKey}"
            }
        }
        server.enqueue(MockResponse().setBody("""{"ref":"refs/heads/main","object":{"sha":"head"}}"""))
        apollo(tokens).use { graph ->
            val repository = RepoDetailRepository(api(tokens), graph)
            var cancelled = false
            try {
                withContext(AccountContext(session.epoch)) {
                    repository.createBranch("owner", "repo", "new", BranchCreationSpec.FromExisting("main"))
                }
            } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals(1, server.requestCount)
            assertEquals("Bearer token-1", server.takeRequest().getHeader("Authorization"))
        }
    }
}
