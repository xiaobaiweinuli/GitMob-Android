package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.CommentAuthorAssociation
import com.gitmob.app.data.model.RepoDiscussionState
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

class RepoDiscussionRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoDiscussionRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repository = RepoDiscussionRepository(
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
    fun `detail uses closed and maps author metadata`() = runTest {
        server.enqueue(MockResponse().setBody(detailJson()))

        val result = repository.getDiscussion("octo", "repo", 7)

        assertTrue(result is ApiResult.Success)
        val detail = (result as ApiResult.Success).data
        assertEquals(RepoDiscussionState.CLOSED, detail.discussion.state)
        assertEquals("https://github.com/octo/repo/discussions/7", detail.discussion.url)
        assertEquals(CommentAuthorAssociation.OWNER, detail.discussion.authorAssociation)
        assertEquals(CommentAuthorAssociation.CONTRIBUTOR, detail.comments.single().authorAssociation)

        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains("bodyHTML closed category"))
        assertFalse(requestBody.contains("bodyHTML state category"))
        assertFalse(requestBody.contains("comments { totalCount }"))
        assertTrue(requestBody.contains("comments(first: 20, after: ${'$'}after)"))
    }

    private fun detailJson() = """
        {
          "data": {
            "repository": {
              "id": "R1",
              "viewerPermission": "READ",
              "categories": {
                "nodes": [
                  {"id":"CAT1","name":"General","emoji":"💬","description":null,"isAnswerable":true}
                ]
              },
              "discussion": {
                "id": "D1",
                "url": "https://github.com/octo/repo/discussions/7",
                "number": 7,
                "title": "Discussion",
                "body": "root",
                "bodyHTML": "<p>root</p>",
                "closed": true,
                "category": {"id":"CAT1","name":"General","emoji":"💬","description":null,"isAnswerable":true},
                "author": {"login":"octo","avatarUrl":"https://example.com/octo.png"},
                "authorAssociation": "OWNER",
                "createdAt": "2026-01-01T00:00:00Z",
                "updatedAt": "2026-01-02T00:00:00Z",
                "comments": {
                  "totalCount": 1,
                  "nodes": [
                    {
                      "id":"C1",
                      "url":"https://github.com/octo/repo/discussions/7#discussioncomment-1",
                      "body":"reply",
                      "bodyHTML":"<p>reply</p>",
                      "author":{"login":"helper","avatarUrl":null},
                      "authorAssociation":"CONTRIBUTOR",
                      "createdAt":"2026-01-02T00:00:00Z",
                      "updatedAt":"2026-01-02T00:00:00Z",
                      "isAnswer":false,
                      "replyTo":null,
                      "viewerCanUpdate":false,
                      "viewerCanDelete":false,
                      "viewerCanReact":true,
                      "viewerCanMarkAsAnswer":false,
                      "viewerCanUnmarkAsAnswer":false
                    }
                  ],
                  "pageInfo":{"hasNextPage":false,"endCursor":null}
                },
                "labels":{"nodes":[]},
                "locked":false,
                "answerChosenAt":null,
                "viewerCanUpdate":false,
                "viewerCanDelete":false,
                "viewerCanClose":false,
                "viewerCanReopen":false,
                "viewerCanSubscribe":true,
                "viewerSubscription":"UNSUBSCRIBED"
              }
            }
          }
        }
    """.trimIndent()
}
