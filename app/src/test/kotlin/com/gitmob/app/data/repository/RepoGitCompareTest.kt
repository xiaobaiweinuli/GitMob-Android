package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.RepoComparisonResult
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

class RepoGitCompareTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoGitRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repository = RepoGitRepository(
            GHApiClient(
                OkHttpClient(),
                Json { ignoreUnknownKeys = true },
                object : AccessTokenProvider { override suspend fun getToken() = "token" },
                baseUrl,
                "$baseUrl/graphql",
            ),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `same repository comparison encodes slashes in refs`() = runTest {
        server.enqueue(MockResponse().setBody(compareJson()))

        val result = repository.compare("octo", "repo", "release/v2", "octo", "repo", "feature/login")

        assertTrue(result is ApiResult.Success)
        assertTrue((result as ApiResult.Success).data is RepoComparisonResult.Available)
        assertEquals(
            "/repos/octo/repo/compare/release%2Fv2...feature%2Flogin?page=1&per_page=30",
            server.takeRequest().path,
        )
    }

    @Test
    fun `cross repository comparison namespaces both refs`() = runTest {
        server.enqueue(MockResponse().setBody(compareJson()))

        repository.compare("upstream", "repo", "main", "viewer", "fork", "feature/login")

        assertEquals(
            "/repos/upstream/repo/compare/upstream:main...viewer:feature%2Flogin?page=1&per_page=30",
            server.takeRequest().path,
        )
    }

    @Test
    fun `no common ancestor is a domain result`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{"message":"No common ancestor between upstream:main and viewer:feature."}""",
            ),
        )

        val result = repository.compare("upstream", "repo", "main", "viewer", "fork", "feature")

        assertEquals(RepoComparisonResult.NoCommonAncestor, (result as ApiResult.Success).data)
    }

    private fun compareJson() = """
        {
          "status":"ahead",
          "ahead_by":1,
          "behind_by":0,
          "total_commits":1,
          "commits":[{
            "sha":"1234567890",
            "commit":{"message":"Fix login\n\nDetails","author":{"date":"2026-08-01T00:00:00Z"},"committer":{"date":"2026-08-01T00:00:00Z"}},
            "author":{"login":"viewer"}
          }],
          "files":[{"filename":"app.kt","status":"modified","additions":3,"deletions":1,"changes":4,"patch":"@@ -1 +1 @@"}]
        }
    """.trimIndent()
}
