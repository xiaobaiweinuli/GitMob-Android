package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.testutil.TestAccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.data.model.CommentAuthorAssociation
import com.gitmob.app.data.model.RepoDiscussionFilter
import com.gitmob.app.data.model.RepoDiscussionSort
import com.gitmob.app.data.model.RepoDiscussionState
import com.gitmob.app.testutil.AccountSessionRule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule

class RepoDiscussionRepositoryTest {
    @get:Rule val accounts = AccountSessionRule()
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
                .addHttpInterceptor(AuthHttpInterceptor(object : TestAccessTokenProvider { override suspend fun getToken() = "token" }))
                .build(),
        )
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `detail uses closed and maps author metadata`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = detailJson()))

        val result = repository.getDiscussion("octo", "repo", 7, after = "comment-next")

        assertTrue(result is ApiResult.Success)
        val detail = (result as ApiResult.Success).data
        assertEquals(RepoDiscussionState.CLOSED, detail.discussion.state)
        assertEquals("https://github.com/octo/repo/discussions/7", detail.discussion.url)
        assertEquals(CommentAuthorAssociation.OWNER, detail.discussion.authorAssociation)
        assertEquals(CommentAuthorAssociation.CONTRIBUTOR, detail.comments.single().authorAssociation)

        val requestBody = requireNotNull(server.takeRequest().body).utf8()
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
    fun `讨论列表分类和标签容量来自PageSize`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = """{"data":{"repository":{"id":"R1","viewerPermission":"READ","hasDiscussionsEnabled":true,"categories":{"nodes":[]},"discussions":{"totalCount":0,"nodes":[],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}"""))

        val result = repository.getDiscussions("o", "r", RepoDiscussionFilter(), after = "start")

        assertTrue(result is ApiResult.Success)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.REPO_ISSUES), variables["first"])
        assertEquals(JsonPrimitive(PageSize.DISCUSSION_CATEGORIES), variables["categoriesFirst"])
        assertEquals(JsonPrimitive(PageSize.METADATA), variables["metadataFirst"])
        assertEquals(JsonPrimitive("start"), variables["after"])
    }

    @Test
    fun `讨论复用标签查询每页均传PageSize`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = """{"data":{"repository":{"labels":{"nodes":[],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}"""))
        server.enqueue(MockResponse(body = """{"data":{"repository":{"labels":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}"""))

        assertTrue(repository.getLabels("o", "r") is ApiResult.Success)
        val first = takeVariables()
        val second = takeVariables()
        assertEquals(JsonPrimitive(PageSize.METADATA), first["first"])
        assertEquals(JsonPrimitive(PageSize.METADATA), second["first"])
        assertFalse("after" in first)
        assertEquals(JsonPrimitive("next"), second["after"])
    }

    @Test
    fun `全部讨论mutation共享片段容量不混入写入input`() = runTest(accounts.context) {
        val node = Json.parseToJsonElement(detailJson()).jsonObject.getValue("data").jsonObject
            .getValue("repository").jsonObject.getValue("discussion")
        val mutations = listOf<Pair<String, suspend () -> ApiResult<*>>>(
            "createDiscussion" to suspend { repository.createDiscussion("R1", "Title", "Body", "CAT1") },
            "updateDiscussion" to suspend { repository.updateDiscussion("D1", "Title", "Body", null) },
            "closeDiscussion" to suspend { repository.closeDiscussion("D1") },
            "reopenDiscussion" to suspend { repository.reopenDiscussion("D1") },
        )
        for ((field, mutate) in mutations) {
            server.enqueue(MockResponse(body = """{"data":{"$field":{"discussion":$node}}}"""))
            assertTrue(mutate() is ApiResult.Success)
            val variables = takeVariables()
            assertEquals(JsonPrimitive(PageSize.METADATA), variables["metadataFirst"])
            assertFalse("metadataFirst" in variables.getValue("input").jsonObject)
        }
    }

    @Test
    fun `缺失仓库与权限失败不伪造成空讨论`() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = """{"data":{"repository":null}}"""))
        assertTrue(repository.getDiscussions("o", "r", RepoDiscussionFilter()) is ApiResult.Failure)
        server.enqueue(MockResponse(code = 403))
        assertTrue(repository.getDiscussion("o", "r", 7) is ApiResult.Failure)
    }

    @Test
    fun `repo search uses selected category name and connection retains category id`() = runTest(accounts.context) {
        val detail = Json.parseToJsonElement(detailJson()).jsonObject.getValue("data").jsonObject.getValue("repository").jsonObject
        val categoryNodes = detail.getValue("categories")
        val node = detail.getValue("discussion").toString().replaceFirst("{", "{\"__typename\":\"Discussion\",")
        val metadata = """"id":"R1","viewerPermission":"READ","hasDiscussionsEnabled":true,"categories":$categoryNodes"""
        val connection = """"pageInfo":{"hasNextPage":false,"endCursor":null}"""
        server.enqueue(MockResponse(body = """{"data":{"repository":{$metadata,"discussions":{"totalCount":0,"nodes":[],$connection}}}}"""))
        val categories = (repository.getDiscussions("o", "r", RepoDiscussionFilter()) as ApiResult.Success).data.categories
        takeVariables()
        val filter = RepoDiscussionFilter(category = categories.single(), sort = RepoDiscussionSort.CREATED_DESC, answered = false)
        server.enqueue(MockResponse(body = """{"data":{"repository":{$metadata},"search":{"discussionCount":1,"nodes":[$node],$connection}}}"""))
        val page = (repository.searchDiscussions("o", "r", "help", filter) as ApiResult.Success).data
        assertEquals(7, page.items.single().number)
        assertTrue(page.categories.single().isAnswerable)
        assertEquals(JsonPrimitive("repo:o/r help is:open category:\"General\" is:unanswered sort:date_created-desc"), takeVariables()["searchQuery"])
        server.enqueue(MockResponse(body = """{"data":{"repository":{$metadata,"discussions":{"totalCount":0,"nodes":[],$connection}}}}"""))
        repository.getDiscussions("o", "r", filter)
        assertEquals(JsonPrimitive("CAT1"), takeVariables()["categoryId"])
    }

    private fun takeVariables() =
        Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject.getValue("variables").jsonObject

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
