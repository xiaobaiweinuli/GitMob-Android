package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.CommentAuthorAssociation
import com.gitmob.app.data.model.RepoPullRequestFilter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RepoPullRequestRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoPullRequestRepository
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repository = RepoPullRequestRepository(
            GHApiClient(
                OkHttpClient(),
                json,
                object : AccessTokenProvider { override suspend fun getToken() = "token" },
                baseUrl,
                "$baseUrl/graphql",
            ),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `default list filter omits optional empty variables`() = runTest {
        server.enqueue(MockResponse().setBody(pageJson()))

        val result = repository.getPullRequests("octo", "repo", RepoPullRequestFilter())

        assertTrue(result is ApiResult.Success)
        val page = (result as ApiResult.Success).data
        assertEquals(1, page.totalCount)
        assertEquals("https://github.com/octo/repo/pull/1", page.items.single().url)
        assertEquals(CommentAuthorAssociation.OWNER, page.items.single().authorAssociation)

        val request = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val variables = request.getValue("variables").jsonObject
        assertFalse("labels" in variables)
        assertFalse("baseRefName" in variables)
        assertFalse("headRefName" in variables)
        assertTrue("states" in variables)
        assertTrue("orderBy" in variables)
    }

    private fun pageJson() = """
        {
          "data": {
            "repository": {
              "id":"R1",
              "viewerPermission":"READ",
              "hasPullRequestsEnabled":true,
              "pullRequestCreationPolicy":"ALL",
              "defaultBranchRef":{"name":"main"},
              "mergeCommitAllowed":true,
              "squashMergeAllowed":true,
              "rebaseMergeAllowed":true,
              "pullRequests": {
                "totalCount":1,
                "nodes":[
                  {
                    "id":"PR1",
                    "url":"https://github.com/octo/repo/pull/1",
                    "number":1,
                    "title":"Fix",
                    "body":"body",
                    "bodyHTML":"<p>body</p>",
                    "state":"OPEN",
                    "isDraft":false,
                    "locked":false,
                    "author":{"login":"octo","avatarUrl":"https://example.com/octo.png"},
                    "authorAssociation":"OWNER",
                    "createdAt":"2026-01-01T00:00:00Z",
                    "updatedAt":"2026-01-02T00:00:00Z",
                    "baseRefName":"main",
                    "headRefName":"feature",
                    "headRepository":{"nameWithOwner":"octo/repo"},
                    "totalCommentsCount":0,
                    "labels":{"nodes":[]},
                    "assignees":{"nodes":[]},
                    "milestone":null
                  }
                ],
                "pageInfo":{"hasNextPage":false,"endCursor":null}
              }
            }
          }
        }
    """.trimIndent()
}
