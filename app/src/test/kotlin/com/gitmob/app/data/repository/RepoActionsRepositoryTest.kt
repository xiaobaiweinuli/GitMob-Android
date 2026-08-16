package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoActionArtifact
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RepoActionsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoActionsRepository
    private lateinit var downloadLauncher: ExternalDownloadLauncher

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        downloadLauncher = mockk()
        every { downloadLauncher.open(any()) } returns Unit
        repository = RepoActionsRepository(
            GHApiClient(
                okHttpClient = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
                tokenProvider = object : AccessTokenProvider {
                    override suspend fun getToken() = "fake-token"
                },
                restBaseUrl = baseUrl,
                graphQLUrl = "$baseUrl/graphql",
            ),
            downloadLauncher,
        )
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `repository permission comes from GraphQL viewerPermission`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"repository":{"viewerPermission":"WRITE"}}}"""))

        val result = repository.getRepositoryPermission("octo", "repo")

        assertEquals(RepoPermission.WRITE, (result as ApiResult.Success).data)
        val request = server.takeRequest()
        assertEquals("/graphql", request.path)
        assertTrue(request.body.readUtf8().contains("viewerPermission"))
    }

    @Test
    fun `actions page maps workflows and runs and forwards page`() = runTest {
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
    fun `cancel run accepts empty success response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.cancel("octo", "repo", runId = 42)

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/repos/octo/repo/actions/runs/42/cancel", request.path)
    }

    @Test
    fun `artifact download resolves redirect and opens external browser`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://objects.example/build.zip"))
        val artifact = RepoActionArtifact(42, "build", 1024, expired = false, expiresAt = null)

        val result = repository.downloadArtifact("octo", "repo", artifact)

        assertTrue(result is ApiResult.Success)
        verify(exactly = 1) { downloadLauncher.open("https://objects.example/build.zip") }
        val request = server.takeRequest()
        assertEquals("Bearer fake-token", request.getHeader("Authorization"))
    }

    @Test
    fun `artifact download rejects non https redirect`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://objects.example/build.zip"))
        val artifact = RepoActionArtifact(42, "build", 1024, expired = false, expiresAt = null)

        val result = repository.downloadArtifact("octo", "repo", artifact)

        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.UserVisible)
        verify(exactly = 0) { downloadLauncher.open(any()) }
    }
}
