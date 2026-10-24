package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.data.model.CommentAuthorAssociation
import com.gitmob.app.data.model.RepoDiscussionFilter
import com.gitmob.app.data.model.RepoDiscussionState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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

        val result = repository.getDiscussion("octo", "repo", 7, after = "comment-next")

        assertTrue(result is ApiResult.Success)
        val detail = (result as ApiResult.Success).data
        assertEquals(RepoDiscussionState.CLOSED, detail.discussion.state)
        assertEquals("https://github.com/octo/repo/discussions/7", detail.discussion.url)
        assertEquals(CommentAuthorAssociation.OWNER, detail.discussion.authorAssociation)
        assertEquals(CommentAuthorAssociation.CONTRIBUTOR, detail.comments.single().authorAssociation)

        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains("DiscussionFields"))
        assertTrue(requestBody.contains("stateReason"))
        assertTrue(requestBody.contains("comments(first: \$commentsFirst, after:"))
        assertFalse(requestBody.contains("comments { totalCount }"))
        val variables = Json.parseToJsonElement(requestBody).jsonObject.getValue("variables").jsonObject
        assertEquals(JsonPrimitive(PageSize.ISSUE_COMMENTS), variables["commentsFirst"])
        assertEquals(JsonPrimitive(PageSize.DISCUSSION_CATEGORIES), variables["categoriesFirst"])
        assertEquals(JsonPrimitive(PageSize.METADATA), variables["metadataFirst"])
        assertEquals(JsonPrimitive("comment-next"), variables["after"])
    }

    @Test
    fun `讨论列表分类和标签容量来自PageSize`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"repository":{"id":"R1","viewerPermission":"READ","hasDiscussionsEnabled":true,"categories":{"nodes":[]},"discussions":{"totalCount":0,"nodes":[],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}"""))

        val result = repository.getDiscussions("o", "r", RepoDiscussionFilter(), after = "start")

        assertTrue(result is ApiResult.Success)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.REPO_ISSUES), variables["first"])
        assertEquals(JsonPrimitive(PageSize.DISCUSSION_CATEGORIES), variables["categoriesFirst"])
        assertEquals(JsonPrimitive(PageSize.METADATA), variables["metadataFirst"])
        assertEquals(JsonPrimitive("start"), variables["after"])
    }

    @Test
    fun `讨论复用标签查询每页均传PageSize`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"repository":{"labels":{"nodes":[],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"repository":{"labels":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}"""))

        assertTrue(repository.getLabels("o", "r") is ApiResult.Success)
        val first = takeVariables()
        val second = takeVariables()
        assertEquals(JsonPrimitive(PageSize.METADATA), first["first"])
        assertEquals(JsonPrimitive(PageSize.METADATA), second["first"])
        assertFalse("after" in first)
        assertEquals(JsonPrimitive("next"), second["after"])
    }

    @Test
    fun `全部讨论mutation共享片段容量不混入写入input`() = runTest {
        val node = Json.parseToJsonElement(detailJson()).jsonObject.getValue("data").jsonObject
            .getValue("repository").jsonObject.getValue("discussion")
        val mutations = listOf<Pair<String, suspend () -> ApiResult<*>>>(
            "createDiscussion" to suspend { repository.createDiscussion("R1", "Title", "Body", "CAT1") },
            "updateDiscussion" to suspend { repository.updateDiscussion("D1", "Title", "Body", null) },
            "closeDiscussion" to suspend { repository.closeDiscussion("D1") },
            "reopenDiscussion" to suspend { repository.reopenDiscussion("D1") },
        )
        for ((field, mutate) in mutations) {
            server.enqueue(MockResponse().setBody("""{"data":{"$field":{"discussion":$node}}}"""))
            assertTrue(mutate() is ApiResult.Success)
            val variables = takeVariables()
            assertEquals(JsonPrimitive(PageSize.METADATA), variables["metadataFirst"])
            assertFalse("metadataFirst" in variables.getValue("input").jsonObject)
        }
    }

    @Test
    fun `缺失仓库与权限失败不伪造成空讨论`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"repository":null}}"""))
        assertTrue(repository.getDiscussions("o", "r", RepoDiscussionFilter()) is ApiResult.Failure)
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(repository.getDiscussion("o", "r", 7) is ApiResult.Failure)
    }

    private fun takeVariables() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject

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
