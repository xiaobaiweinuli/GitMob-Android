package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.data.model.RepoComparisonResult
import com.gitmob.app.data.model.RepoForkSyncInfo
import com.gitmob.app.data.model.RepoForkSyncStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val tokenProvider = object : AccessTokenProvider { override suspend fun getToken() = "token" }
        repository = RepoGitRepository(
            GHApiClient(
                OkHttpClient(),
                Json { ignoreUnknownKeys = true },
                tokenProvider,
                baseUrl,
            ),
            ApolloClient.Builder()
                .serverUrl("$baseUrl/graphql")
                .okHttpClient(OkHttpClient())
                .addHttpInterceptor(AuthHttpInterceptor(tokenProvider))
                .build(),
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
            "/repos/octo/repo/compare/release%2Fv2...feature%2Flogin?page=1&per_page=${PageSize.COMPARE_COMMITS}",
            server.takeRequest().path,
        )
    }

    @Test
    fun `cross repository comparison namespaces both refs`() = runTest {
        server.enqueue(MockResponse().setBody(compareJson()))

        repository.compare("upstream", "repo", "main", "viewer", "fork", "feature/login")

        assertEquals(
            "/repos/upstream/repo/compare/upstream:main...viewer:feature%2Flogin?page=1&per_page=${PageSize.COMPARE_COMMITS}",
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

    @Test
    fun `fork sync compares captured upstream and fork SHAs with correct direction`() = runTest {
        server.enqueue(MockResponse().setBody(forkGraphJson()))
        server.enqueue(MockResponse().setBody(compareJson(ahead = 2, behind = 3)))

        val result = repository.getForkSync("viewer", "fork", "main")

        val sync = (result as ApiResult.Success).data
        assertEquals(RepoForkSyncStatus.READY, sync.status)
        assertEquals(2, sync.aheadBy)
        assertEquals(3, sync.behindBy)
        assertEquals("/graphql", server.takeRequest().path)
        assertEquals(
            "/repos/upstream/repo/compare/upstream:upsha...viewer:forksha?page=1&per_page=${PageSize.COMPARE_COMMITS}",
            server.takeRequest().path,
        )
    }

    @Test
    fun `fork sync normalizes qualified branch before comparison`() = runTest {
        server.enqueue(MockResponse().setBody(forkGraphJson()))
        server.enqueue(MockResponse().setBody(compareJson()))

        val result = repository.getForkSync("viewer", "fork", "refs/heads/main")

        assertEquals("main", (result as ApiResult.Success).data.branch)
        server.takeRequest()
        assertEquals(
            "/repos/upstream/repo/compare/upstream:upsha...viewer:forksha?page=1&per_page=${PageSize.COMPARE_COMMITS}",
            server.takeRequest().path,
        )
    }

    @Test
    fun `force align rechecks both heads and sends guarded ref update`() = runTest {
        server.enqueue(MockResponse().setBody(forkGraphJson()))
        server.enqueue(MockResponse().setBody("""{"data":{"updateRefs":{"clientMutationId":"gitmob"}}}"""))

        val result = repository.forceAlignFork("viewer", "fork", syncInfo())

        assertTrue(result is ApiResult.Success)
        server.takeRequest()
        val mutation = server.takeRequest().body.readUtf8()
        assertTrue(mutation.contains("refs/heads/main"))
        assertTrue(mutation.contains("forksha"))
        assertTrue(mutation.contains("upsha"))
        assertTrue(mutation.contains("\"force\":true"))
    }

    @Test
    fun `force align does not mutate when either head changed`() = runTest {
        server.enqueue(MockResponse().setBody(forkGraphJson(currentOid = "new-forksha")))

        val result = repository.forceAlignFork("viewer", "fork", syncInfo())

        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is ApiError.UserVisible)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `merge upstream does not mislabel validation failure as a conflict`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Validation failed"}"""))

        val result = repository.mergeUpstream("viewer", "fork", "main") as ApiResult.Failure

        assertTrue(result.error is ApiError.Unknown)
        assertTrue((result.error as ApiError.Unknown).message.contains("422"))
    }

    @Test
    fun `archived code tree has no write capabilities`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"data":{"repository":{"id":"R1","isFork":true,"isArchived":true,"viewerPermission":"ADMIN",
            "ref":{"name":"main","target":{"__typename":"Commit","oid":"sha"}},
            "object":{"__typename":"Tree","entries":[]}}}}
        """.trimIndent()))

        val tree = (repository.getCodeTree("viewer", "fork", "main") as ApiResult.Success).data

        assertTrue(tree.isArchived)
        assertFalse(tree.capabilities.canPush)
        assertTrue(tree.capabilities.canArchiveRepo)
    }

    @Test
    fun `force align also refuses a changed upstream SHA`() = runTest {
        server.enqueue(MockResponse().setBody(forkGraphJson(upstreamOid = "new-upsha")))

        val result = repository.forceAlignFork("viewer", "fork", syncInfo())

        assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.repo_sync_head_changed)), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `merge upstream maps conflict to user visible error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"Conflict"}"""))

        val result = repository.mergeUpstream("viewer", "fork", "main")

        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiError.UserVisible(R.string.repo_sync_conflict), (result as ApiResult.Failure).error)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/repos/viewer/fork/merge-upstream", request.path)
        assertTrue(request.body.readUtf8().contains("main"))
    }

    @Test
    fun `compare文件容量前一项等于及超过上限保持裁剪和截断语义`() = runTest {
        val limit = PageSize.COMPARE_FILES_LIMIT
        for (count in listOf(limit - 1, limit, limit + 1)) {
            val files = (1..count).joinToString(",") { """{"filename":"file-$it.kt","status":"modified","additions":1,"deletions":2}""" }
            server.enqueue(MockResponse().setBody(compareJson(ahead = PageSize.COMPARE_COMMITS * 2 + 1, files = files)))

            val result = repository.compare("octo", "repo", "main", "octo", "repo", "feature", page = 2) as ApiResult.Success
            val comparison = (result.data as RepoComparisonResult.Available).comparison
            val kept = minOf(count, limit)

            assertEquals(kept, comparison.files.size)
            assertEquals("file-$kept.kt", comparison.files.last().filename)
            assertEquals(count >= limit, comparison.filesTruncated)
            assertEquals(kept, comparison.additions)
            assertEquals(kept * 2, comparison.deletions)
            assertEquals(2, comparison.commitsPage)
            assertTrue(comparison.commitsHasNextPage)
            val request = server.takeRequest()
            assertEquals(PageSize.COMPARE_COMMITS.toString(), request.requestUrl?.queryParameter("per_page"))
            assertEquals("2", request.requestUrl?.queryParameter("page"))
        }
    }

    private fun compareJson(
        ahead: Int = 1,
        behind: Int = 0,
        files: String = """{"filename":"app.kt","status":"modified","additions":3,"deletions":1,"changes":4,"patch":"@@ -1 +1 @@"}""",
    ) = """
        {
          "status":"ahead",
          "ahead_by":$ahead,
          "behind_by":$behind,
          "total_commits":${ahead + behind},
          "commits":[{
            "sha":"1234567890",
            "commit":{"message":"Fix login\n\nDetails","author":{"date":"2026-08-01T00:00:00Z"},"committer":{"date":"2026-08-01T00:00:00Z"}},
            "author":{"login":"viewer"}
          }],
          "files":[$files]
        }
    """.trimIndent()

    private fun forkGraphJson(currentOid: String = "forksha", upstreamOid: String = "upsha") = """
        {
          "data": {
            "repository": {
              "id": "fork-id",
              "isFork": true,
              "ref": {"target": {"__typename": "Commit", "oid": "$currentOid"}},
              "parent": {
                "id": "upstream-id",
                "name": "repo",
                "owner": {"__typename": "User", "login": "upstream"},
                "ref": {"target": {"__typename": "Commit", "oid": "$upstreamOid"}}
              }
            }
          }
        }
    """.trimIndent()

    private fun syncInfo() = RepoForkSyncInfo(
        repositoryId = "fork-id",
        branch = "main",
        currentOid = "forksha",
        upstreamRepositoryId = "upstream-id",
        upstreamOwner = "upstream",
        upstreamName = "repo",
        upstreamOid = "upsha",
        status = RepoForkSyncStatus.READY,
        aheadBy = 1,
        behindBy = 1,
    )
}
