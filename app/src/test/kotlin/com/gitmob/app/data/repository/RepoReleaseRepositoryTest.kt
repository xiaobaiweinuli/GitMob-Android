package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoReleaseAsset
import com.gitmob.app.data.model.SaveRepoReleaseInput
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

class RepoReleaseRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoReleaseRepository
    private lateinit var downloadLauncher: ExternalDownloadLauncher

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        downloadLauncher = mockk()
        every { downloadLauncher.open(any()) } returns Unit
        repository = RepoReleaseRepository(
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
        server.enqueue(MockResponse().setBody("""{"data":{"repository":{"viewerPermission":"ADMIN"}}}"""))

        val result = repository.getRepositoryPermission("octo", "repo")

        assertEquals(RepoPermission.ADMIN, (result as ApiResult.Success).data)
        assertEquals("/graphql", server.takeRequest().path)
    }

    @Test
    fun `create release sends draft and prerelease state`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"node_id":"R7","tag_name":"v1.0.0","target_commitish":"main","name":"Version 1","body":"Notes","draft":true,"prerelease":false,"created_at":"2026-01-01T00:00:00Z","assets":[]}""",
            ),
        )

        val result = repository.createRelease(
            "octo",
            "repo",
            SaveRepoReleaseInput("v1.0.0", "main", "Version 1", "Notes", draft = true, prerelease = false),
        )

        val release = (result as ApiResult.Success).data
        assertTrue(release.draft)
        assertEquals("v1.0.0", release.tagName)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/repos/octo/repo/releases", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"draft\":true"))
        assertTrue(body.contains("\"prerelease\":false"))
    }

    @Test
    fun `delete release accepts empty success response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.deleteRelease("octo", "repo", releaseId = 7)

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/repos/octo/repo/releases/7", request.path)
    }

    @Test
    fun `release asset download resolves redirect and opens external browser`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://objects.example/gitmob.apk"))
        val asset = RepoReleaseAsset(8, "gitmob.apk", null, "application/vnd.android.package-archive", "uploaded", 2048, 3, "", "")

        val result = repository.downloadAsset("octo", "repo", asset)

        assertTrue(result is ApiResult.Success)
        verify(exactly = 1) { downloadLauncher.open("https://objects.example/gitmob.apk") }
    }
}
