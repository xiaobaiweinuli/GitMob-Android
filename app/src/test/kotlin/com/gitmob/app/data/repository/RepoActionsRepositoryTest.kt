package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoActionArtifact
import com.gitmob.app.data.model.RepoActionStepLogKey
import com.gitmob.app.data.model.ActionLogAvailability
import com.gitmob.app.data.model.RepoWorkflow
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertNull
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule

class RepoActionsRepositoryTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoActionsRepository
    private val signedRequests = CopyOnWriteArrayList<Request>()
    private lateinit var downloadLauncher: ExternalDownloadLauncher

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        downloadLauncher = mockk()
        every { downloadLauncher.open(any()) } returns Unit
        repository = RepoActionsRepository(
            GHApiClient(
                okHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
                    if (chain.request().url.host == "logs.example") {
                        signedRequests += chain.request()
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body("first\r\n中文\n".toResponseBody()).build()
                    } else chain.proceed(chain.request())
                }.build(),
                json = Json { ignoreUnknownKeys = true },
                tokenProvider = object : AccessTokenProvider {
                    override suspend fun getToken() = "fake-token"
                },
                restBaseUrl = baseUrl,
                ),
            ApolloClient.Builder()
                .serverUrl("$baseUrl/graphql")
                .okHttpClient(OkHttpClient())
                .addHttpInterceptor(
                    AuthHttpInterceptor(
                        object : AccessTokenProvider {
                            override suspend fun getToken() = "fake-token"
                        },
                    ),
                )
                .build(),
            downloadLauncher,
        )
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `repository permission comes from GraphQL viewerPermission`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"data":{"repository":{"viewerPermission":"WRITE"}}}"""))

        val result = repository.getRepositoryPermission("octo", "repo")

        assertEquals(RepoPermission.WRITE, (result as ApiResult.Success).data)
        val request = server.takeRequest()
        assertEquals("/graphql", request.path)
        assertTrue(request.body.readUtf8().contains("viewerPermission"))
    }

    @Test
    fun `actions page maps workflows and runs and forwards page`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """{"total_count":1,"workflows":[{"id":10,"node_id":"W10","name":"CI","path":".github/workflows/ci.yml","state":"active"}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"total_count":31,"workflow_runs":[{"id":20,"name":"CI","display_title":"Build","event":"push","status":"completed","conclusion":"success","workflow_id":10,"run_number":7,"run_attempt":1,"head_branch":"main","head_sha":"abc","actor":{"login":"octocat","avatar_url":"https://example/avatar"},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:01:00Z","html_url":"https://github.com/octo/repo/actions/runs/20"}]}""",
            ),
        )

        val result = repository.getActions("octo", "repo", page = 2)

        val page = (result as ApiResult.Success).data
        assertEquals(31, page.totalCount)
        assertEquals("CI", page.workflows.single().name)
        assertEquals("Build", page.runs.single().displayTitle)
        assertEquals("octocat", page.runs.single().actor?.login)
        assertEquals(2, page.page)
        assertEquals("/repos/octo/repo/actions/workflows?per_page=100&page=1", server.takeRequest().path)
        assertEquals("/repos/octo/repo/actions/runs?per_page=30&page=2", server.takeRequest().path)
    }

    @Test
    fun `dispatch inputs read workflow yaml from selected ref through GraphQL`() = runTest(accounts.context) {
        val yaml = """
            name: Manual
            on:
              workflow_dispatch:
                inputs:
                  environment:
                    type: choice
                    required: true
                    options: [staging, production]
        """.trimIndent()
        server.enqueue(MockResponse().setBody("""
            {"data":{"repository":{"object":{"__typename":"Blob","text":${JsonPrimitive(yaml)},"isBinary":false,"isTruncated":false,"byteSize":${yaml.encodeToByteArray().size}}}}}
        """.trimIndent()))
        val workflow = RepoWorkflow(10, "W10", "Manual", ".github/workflows/manual.yml", "active", null, null)

        val result = repository.getDispatchInputs("octo", "repo", workflow, "release/v2")

        assertEquals(listOf("staging", "production"), (result as ApiResult.Success).data.single().options)
        val request = server.takeRequest()
        assertEquals("/graphql", request.path)
        val requestBody = request.body.readUtf8()
        assertTrue(requestBody.contains("release/v2:.github/workflows/manual.yml"))
        assertTrue(requestBody.contains("... on Blob"))
    }

    @Test
    fun `cancel run accepts empty success response`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.cancel("octo", "repo", runId = 42)

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/repos/octo/repo/actions/runs/42/cancel", request.path)
    }

    @Test
    fun `delete run accepts empty success response`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.deleteRun("octo", "repo", runId = 42)

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/repos/octo/repo/actions/runs/42", request.path)
    }

    @Test
    fun `artifact download resolves redirect and opens external browser`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://objects.example/build.zip"))
        val artifact = RepoActionArtifact(42, "build", 1024, expired = false, expiresAt = null)

        val result = repository.downloadArtifact("octo", "repo", artifact)

        assertTrue(result is ApiResult.Success)
        verify(exactly = 1) { downloadLauncher.open("https://objects.example/build.zip") }
        val request = server.takeRequest()
        assertEquals("Bearer fake-token", request.getHeader("Authorization"))
    }

    @Test
    fun `artifact download rejects non https redirect`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://objects.example/build.zip"))
        val artifact = RepoActionArtifact(42, "build", 1024, expired = false, expiresAt = null)

        val result = repository.downloadArtifact("octo", "repo", artifact)

        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.UserVisible)
        verify(exactly = 0) { downloadLauncher.open(any()) }
    }
    @Test
    fun `jobs are paged within the captured attempt and keep logical step numbers`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"id":42,"run_attempt":3}"""))
        val jobs = (1..100).joinToString(",") { """{"id":$it,"run_id":42,"run_attempt":3}""" }
        server.enqueue(MockResponse().setBody("""{"total_count":101,"jobs":[$jobs]}"""))
        server.enqueue(MockResponse().setBody("""{"total_count":101,"jobs":[{"id":101,"run_id":42,"run_attempt":3,"steps":[{"number":1},{"number":2},{"number":8},{"number":16},{"number":17}]}]}"""))
        server.enqueue(MockResponse().setBody("""{"artifacts":[]}"""))
        val detail = (repository.getRun("octo", "repo", 42) as ApiResult.Success).data
        assertEquals(101, detail.jobs.size)
        assertEquals(listOf(1, 2, 8, 16, 17), detail.jobs.last().steps.map { it.number })
        assertEquals("/repos/octo/repo/actions/runs/42", server.takeRequest().path)
        assertEquals("/repos/octo/repo/actions/runs/42/attempts/3/jobs?per_page=100&page=1", server.takeRequest().path)
        assertEquals("/repos/octo/repo/actions/runs/42/attempts/3/jobs?per_page=100&page=2", server.takeRequest().path)
        server.takeRequest()
    }

    @Test
    fun `missing and invalid attempts never become attempt one`() = runTest(accounts.context) {
        for (field in listOf("", ",\"run_attempt\":0", ",\"run_attempt\":-1")) {
            server.enqueue(MockResponse().setBody("{\"id\":42$field}"))
            server.enqueue(MockResponse().setBody("""{"jobs":[]}"""))
            server.enqueue(MockResponse().setBody("""{"artifacts":[]}"""))
            assertNull((repository.getRun("octo", "repo", 42) as ApiResult.Success).data.run.runAttempt)
            server.takeRequest()
            assertEquals("/repos/octo/repo/actions/runs/42/jobs?per_page=100&page=1", server.takeRequest().path)
            server.takeRequest()
        }
        val before = server.requestCount
        assertTrue(repository.downloadRunLogs("octo", "repo", 42, null) is ApiResult.Failure)
        assertTrue(repository.downloadRunLogs("octo", "repo", 42, 0) is ApiResult.Failure)
        assertEquals(before, server.requestCount)
        verify(exactly = 0) { downloadLauncher.open(any()) }
    }

    @Test
    fun `step logs use the real number per job and second hop has no credentials`() = runTest(accounts.context) {
        for (jobId in listOf(101L, 202L)) {
            server.enqueue(MockResponse().setBody("""{"id":42,"run_attempt":3}"""))
            server.enqueue(MockResponse().setBody("""{"id":$jobId,"name":"Build","run_id":42,"run_attempt":3,"steps":[{"number":8,"name":"Compile","status":"completed"}]}"""))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://logs.example/step-$jobId.txt"))
            val result = repository.getStepLog(RepoActionStepLogKey("octo", "repo", 42, 3, jobId, 8)) as ApiResult.Success
            assertEquals("first\r\n中文\n", result.data.text)
            assertEquals("Compile", result.data.stepName)
            server.takeRequest(); server.takeRequest()
            val request = server.takeRequest()
            assertEquals("/repos/octo/repo/actions/jobs/$jobId/steps/8/logs", request.path)
            assertEquals("Bearer fake-token", request.getHeader("Authorization"))
        }
        assertEquals(2, signedRequests.size)
        signedRequests.forEach {
            assertNull(it.header("Authorization")); assertNull(it.header("Cookie")); assertNull(it.header("X-GitHub-Api-Version"))
        }
    }

    @Test
    fun `restored log route rejects a changed attempt before fetching a job`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"id":42,"run_attempt":4}"""))
        assertTrue(repository.getStepLog(RepoActionStepLogKey("octo", "repo", 42, 3, 101, 8)) is ApiResult.Failure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `skipped and waiting steps do not request a signed log URL`() = runTest(accounts.context) {
        for ((status, conclusion, availability) in listOf(Triple("queued", "", ActionLogAvailability.WAITING), Triple("completed", "skipped", ActionLogAvailability.SKIPPED))) {
            server.enqueue(MockResponse().setBody("""{"id":42,"run_attempt":3}"""))
            server.enqueue(MockResponse().setBody("""{"id":101,"run_id":42,"run_attempt":3,"steps":[{"number":8,"status":"$status","conclusion":"$conclusion"}]}"""))
            val result = repository.getStepLog(RepoActionStepLogKey("octo", "repo", 42, 3, 101, 8)) as ApiResult.Success
            assertEquals(availability, result.data.availability)
        }
        assertEquals(4, server.requestCount)
        assertTrue(signedRequests.isEmpty())
    }

    @Test
    fun `zip uses encoded repository segments and a fresh attempt URL every time`() = runTest(accounts.context) {
        repeat(2) { index ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://logs.example/$index.zip"))
            assertTrue(repository.downloadRunLogs("owner space", "repo#name", 42, 3) is ApiResult.Success)
            val request = server.takeRequest()
            assertEquals("/repos/owner%20space/repo%23name/actions/runs/42/attempts/3/logs", request.path)
            assertEquals("Bearer fake-token", request.getHeader("Authorization"))
            verify { downloadLauncher.open("https://logs.example/$index.zip") }
        }
    }

    @Test
    fun `log 404 is unavailable and rate limited 403 is not a permission error`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ApiError.UserVisible(com.gitmob.app.R.string.actions_logs_unavailable), (repository.downloadRunLogs("octo", "repo", 42, 3) as ApiResult.Failure).error)
        for (header in listOf("Retry-After" to "60", "X-RateLimit-Remaining" to "0")) {
            server.enqueue(MockResponse().setResponseCode(403).setHeader(header.first, header.second))
            assertEquals(ApiError.RateLimited, (repository.downloadRunLogs("octo", "repo", 42, 3) as ApiResult.Failure).error)
        }
        for ((code, expected) in listOf(401 to ApiError.Unauthorized, 403 to ApiError.Forbidden, 429 to ApiError.RateLimited)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertEquals(expected, (repository.downloadRunLogs("octo", "repo", 42, 3) as ApiResult.Failure).error)
        }
        verify(exactly = 0) { downloadLauncher.open(any()) }
    }

    @Test
    fun `missing and unsafe signed addresses never launch the browser`() = runTest(accounts.context) {
        for (location in listOf(null, "http://logs.example/logs", "https://user:password@logs.example/logs", "https:///missing-host")) {
            val response = MockResponse().setResponseCode(302)
            if (location != null) response.setHeader("Location", location)
            server.enqueue(response)
            assertTrue(repository.downloadRunLogs("octo", "repo", 42, 3) is ApiResult.Failure)
        }
        verify(exactly = 0) { downloadLauncher.open(any()) }
    }

    @Test
    fun `both rerun endpoints serialize the selected debug value`() = runTest(accounts.context) {
        for (failedOnly in listOf(false, true)) for (debug in listOf(false, true)) {
            server.enqueue(MockResponse().setResponseCode(201))
            assertTrue(repository.rerun("octo", "repo", 42, failedOnly, debug) is ApiResult.Success)
            val request = server.takeRequest()
            assertEquals("/repos/octo/repo/actions/runs/42/${if (failedOnly) "rerun-failed-jobs" else "rerun"}", request.path)
            assertEquals("""{"enable_debug_logging":$debug}""", request.body.readUtf8())
        }
    }


    @Test
    fun `rate limiting during step metadata reads remains a retryable rate limit`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0"))
        val result = repository.getStepLog(RepoActionStepLogKey("octo", "repo", 42, 3, 101, 8)) as ApiResult.Failure
        assertEquals(ApiError.RateLimited, result.error)
        assertTrue(signedRequests.isEmpty())
    }

    @Test
    fun `a server repeating a full jobs page fails instead of looping forever`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"id":42,"run_attempt":3}"""))
        val jobs = (1..100).joinToString(",") { """{"id":$it,"run_id":42,"run_attempt":3}""" }
        repeat(2) { server.enqueue(MockResponse().setBody("""{"total_count":101,"jobs":[$jobs]}""")) }
        assertTrue(repository.getRun("octo", "repo", 42) is ApiResult.Failure)
        assertEquals(3, server.requestCount)
    }

}
