package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.data.model.CommentAuthorAssociation
import com.gitmob.app.data.model.RepoDiscussionState
import kotlinx.coroutines.test.runTest
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
            ApolloClient.Builder()
                .serverUrl("$baseUrl/graphql")
                .okHttpClient(OkHttpClient())
                .addHttpInterceptor(AuthHttpInterceptor(object : AccessTokenProvider { override suspend fun getToken() = "token" }))
                .build(),
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
        assertTrue(requestBody.contains("DiscussionFields"))
        assertTrue(requestBody.contains("stateReason"))
        assertTrue(requestBody.contains("comments(first: 20, after:"))
        assertFalse(requestBody.contains("comments { totalCount }"))
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
                "stateReason": null,
                "category": {"id":"CAT1","name":"General","emoji":"💬","description":null,"isAnswerable":true},
                "author": {"__typename":"User","login":"octo","avatarUrl":"https://example.com/octo.png"},
                "authorAssociation": "OWNER",
                "createdAt": "2026-01-01T00:00:00Z",
                "updatedAt": "2026-01-02T00:00:00Z",
                "includesCreatedEdit": false,
                "lastEditedAt": null,
                "editor": null,
                "comments": {
                  "totalCount": 1,
                  "nodes": [
                    {
                      "id":"C1",
                      "url":"https://github.com/octo/repo/discussions/7#discussioncomment-1",
                      "body":"reply",
                      "bodyHTML":"<p>reply</p>",
                      "author":{"__typename":"User","login":"helper","avatarUrl":"https://example.com/helper.png"},
                      "authorAssociation":"CONTRIBUTOR",
                      "createdAt":"2026-01-02T00:00:00Z",
                      "updatedAt":"2026-01-02T00:00:00Z",
                      "includesCreatedEdit":false,
                      "lastEditedAt":null,
                      "editor":null,
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
