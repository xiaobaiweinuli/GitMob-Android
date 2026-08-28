package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.CommentAuthorAssociation
import com.gitmob.app.data.model.CreateRepoPullRequestInput
import com.gitmob.app.data.model.RepoPullRequestFilter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
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
        val query = request.getValue("query").jsonPrimitive.content
        assertFalse("baseRefName:" in query)
        assertFalse("headRefName:" in query)
        assertTrue("states" in variables)
        assertTrue("orderBy" in variables)
    }

    @Test
    fun `getLabels 解析仓库标签列表`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "data": {
                "repository": {
                  "labels": {
                    "nodes": [
                      {"id":"LA_1","name":"bug","color":"d73a4a","description":"Something isn't working"},
                      {"id":"LA_2","name":"enhancement","color":"a2eeef","description":"New feature or request"}
                    ]
                  }
                }
              }
            }
        """.trimIndent()))

        val result = repository.getLabels("octo", "repo")

        assertTrue(result is ApiResult.Success)
        val labels = (result as ApiResult.Success).data
        assertEquals(2, labels.size)
        assertEquals("bug", labels[0].name)
        assertEquals("d73a4a", labels[0].color)
        assertEquals("LA_1", labels[0].id)
    }

    @Test
    fun `getLabels 错误响应映射为失败`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))

        val result = repository.getLabels("octo", "repo")

        assertTrue(result is ApiResult.Failure)
    }

    @Test
    fun `getLabels 在 Repository 内遍历 cursor`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"repository":{"labels":{"nodes":[{"id":"L1","name":"one","color":"111111"}],"pageInfo":{"hasNextPage":true,"endCursor":"C1"}}}}}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"repository":{"labels":{"nodes":[{"id":"L2","name":"two","color":"222222"}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}"""))

        val result = repository.getLabels("octo", "repo")

        assertEquals(listOf("one", "two"), (result as ApiResult.Success).data.map { it.name })
        val firstVariables = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
        val secondVariables = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
        assertFalse("after" in firstVariables)
        assertEquals("C1", secondVariables.getValue("after").jsonPrimitive.content)
    }

    @Test
    fun `cross repository create namespaces head ref and sends head repository id`() = runTest {
        server.enqueue(MockResponse().setBody(createPullRequestJson()))

        val result = repository.createPullRequest(
            CreateRepoPullRequestInput(
                repositoryId = "BASE",
                baseRefName = "main",
                headRefName = "feature/login",
                headRepositoryId = "HEAD",
                title = "Feature",
                body = "Body",
                draft = false,
                headOwner = "viewer",
            ),
        )

        assertTrue(result is ApiResult.Success)
        val request = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val input = request.getValue("variables").jsonObject.getValue("input").jsonObject
        assertEquals("BASE", input.getValue("repositoryId").jsonPrimitive.content)
        assertEquals("HEAD", input.getValue("headRepositoryId").jsonPrimitive.content)
        assertEquals("viewer:feature/login", input.getValue("headRefName").jsonPrimitive.content)
    }

    @Test
    fun `requestReviews sends schema verified user id variables`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"requestReviews":{"clientMutationId":null}}}"""))

        val result = repository.requestReviews("PR1", listOf("U1", "U2"))

        assertTrue(result is ApiResult.Success)
        val request = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val input = request.getValue("variables").jsonObject.getValue("input").jsonObject
        assertEquals("PR1", input.getValue("pullRequestId").jsonPrimitive.content)
        assertEquals(listOf("U1", "U2"), input.getValue("userIds").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(input.getValue("botIds").jsonArray.isEmpty())
        assertTrue(input.getValue("teamIds").jsonArray.isEmpty())
        assertFalse(input.getValue("union").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `findOpenPullRequest maps summary fields and encodes fork head`() = runTest {
        server.enqueue(MockResponse().setBody("""
            [{
              "number":7,
              "title":"Feature",
              "html_url":"https://github.com/upstream/repo/pull/7",
              "state":"open",
              "draft":true,
              "user":{"login":"viewer","node_id":"U1","avatar_url":"https://example.com/viewer.png"},
              "updated_at":"2026-08-20T10:00:00Z",
              "base":{"ref":"main"},
              "head":{"ref":"feature/login"},
              "comments":3,
              "labels":[{"node_id":"L1","name":"bug","color":"d73a4a","description":"Bug"}]
            }]
        """.trimIndent()))

        val result = repository.findOpenPullRequest("upstream", "repo", "main", "viewer", "feature/login")

        assertTrue(result is ApiResult.Success)
        val pullRequest = (result as ApiResult.Success).data
        assertEquals(7, pullRequest?.number)
        assertEquals("Feature", pullRequest?.title)
        assertTrue(pullRequest?.isDraft == true)
        assertEquals("viewer", pullRequest?.author?.login)
        assertEquals("main", pullRequest?.baseRefName)
        assertEquals("feature/login", pullRequest?.headRefName)
        assertEquals(3, pullRequest?.commentCount)
        assertEquals("bug", pullRequest?.labels?.single()?.name)

        val request = server.takeRequest()
        assertEquals("open", request.requestUrl?.queryParameter("state"))
        assertEquals("main", request.requestUrl?.queryParameter("base"))
        assertEquals("viewer:feature/login", request.requestUrl?.queryParameter("head"))
        assertEquals("1", request.requestUrl?.queryParameter("per_page"))
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

    private fun createPullRequestJson() = """
        {"data":{"createPullRequest":{"pullRequest":{
          "id":"PR1","number":1,"title":"Feature","body":"Body","bodyHTML":"<p>Body</p>",
          "state":"OPEN","createdAt":"2026-08-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z",
          "baseRefName":"main","headRefName":"feature/login"
        }}}}
    """.trimIndent()
}
