package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.data.model.RepoIssueFilter
import com.gitmob.app.data.model.RepoIssueStateFilter
import com.gitmob.app.data.model.RepoIssueSort
import com.gitmob.app.data.model.RepoAssigneeFilter
import com.gitmob.app.data.model.RepoMilestoneFilter
import com.gitmob.app.data.model.CreateRepoIssueInput
import com.gitmob.app.data.model.IssueStateReason
import com.gitmob.app.data.model.UpdateRepoIssueInput
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RepoIssueRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoIssueRepository

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repository = RepoIssueRepository(
            ApolloClient.Builder()
                .serverUrl("$baseUrl/graphql")
                .okHttpClient(OkHttpClient())
                .addHttpInterceptor(AuthHttpInterceptor(object : AccessTokenProvider { override suspend fun getToken() = "token" }))
                .build(),
        )
    }
    @After fun tearDown() = server.shutdown()

    @Test
    fun `issue filters and order are encoded in repository query`() = runTest {
        server.enqueue(MockResponse().setBody(pageJson(cursor = "CURSOR", hasNext = true)))
        val result = repository.getIssues("octo", "repo", RepoIssueFilter(state = RepoIssueStateFilter.ALL, sort = RepoIssueSort.COMMENTS_ASC, assignee = RepoAssigneeFilter.ANY, milestone = RepoMilestoneFilter.Number(7), subscribed = true))
        assertTrue(result is ApiResult.Success)
        assertEquals(1, (result as ApiResult.Success).data.items.size)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"states\":[\"OPEN\",\"CLOSED\"]"))
        assertTrue(body.contains("\"field\":\"COMMENTS\""))
        assertTrue(body.contains("\"assignee\":\"*\""))
        assertTrue(body.contains("\"milestoneNumber\":\"7\""))
        assertTrue(body.contains("\"viewerSubscribed\":true"))
        val variables = Json.parseToJsonElement(body).jsonObject.getValue("variables").jsonObject
        assertEquals(JsonPrimitive(PageSize.REPO_ISSUES), variables["first"])
        assertEquals(JsonPrimitive(PageSize.METADATA), variables["metadataFirst"])
    }

    @Test
    fun `issue pagination forwards end cursor`() = runTest {
        server.enqueue(MockResponse().setBody(pageJson(cursor = "NEXT", hasNext = true)))
        server.enqueue(MockResponse().setBody(pageJson(cursor = null, hasNext = false, number = 2)))
        val filter = RepoIssueFilter()
        repository.getIssues("octo", "repo", filter)
        val second = repository.getIssues("octo", "repo", filter, after = "NEXT")
        assertTrue(second is ApiResult.Success)
        val firstRequest = server.takeRequest().body.readUtf8()
        val secondRequest = server.takeRequest().body.readUtf8()
        assertTrue(firstRequest.contains("\"after\":null") || !firstRequest.contains("\"after\""))
        assertTrue(secondRequest.contains("\"after\":\"NEXT\""))
        for (body in listOf(firstRequest, secondRequest)) {
            val variables = Json.parseToJsonElement(body).jsonObject.getValue("variables").jsonObject
            assertEquals(JsonPrimitive(PageSize.REPO_ISSUES), variables["first"])
            assertEquals(JsonPrimitive(PageSize.METADATA), variables["metadataFirst"])
        }
    }

    @Test
    fun `详情评论和共享元数据容量来自PageSize且保留游标`() = runTest {
        val comments = """{"totalCount":0,"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}"""
        server.enqueue(MockResponse().setBody("""{"data":{"repository":{"id":"R1","viewerPermission":"READ","issue":${issueNodeJson(1, comments)}}}}"""))

        val result = repository.getIssue("o", "r", 1, commentsAfter = "comment-next")

        assertTrue(result is ApiResult.Success)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.ISSUE_COMMENTS), variables["commentsFirst"])
        assertEquals(JsonPrimitive(PageSize.METADATA), variables["metadataFirst"])
        assertEquals(JsonPrimitive("comment-next"), variables["after"])
    }

    @Test
    fun `三个元数据连接所有页均引用统一容量`() = runTest {
        val loaders = listOf<Pair<String, suspend () -> ApiResult<*>>>(
            "labels" to suspend { repository.getLabels("o", "r") },
            "milestones" to suspend { repository.getMilestones("o", "r") },
            "assignableUsers" to suspend { repository.getAssignableUsers("o", "r") },
        )
        for ((field, load) in loaders) {
            server.enqueue(MockResponse().setBody("""{"data":{"repository":{"$field":{"nodes":[],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"repository":{"$field":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}"""))

            assertTrue(load() is ApiResult.Success)
            val first = takeVariables()
            val second = takeVariables()
            assertEquals(JsonPrimitive(PageSize.METADATA), first["first"])
            assertEquals(JsonPrimitive(PageSize.METADATA), second["first"])
            assertFalse("after" in first)
            assertEquals(JsonPrimitive("next"), second["after"])
        }
    }

    @Test
    fun `元数据下一页失败不缓存部分结果`() = runTest {
        val first = """{"data":{"repository":{"labels":{"nodes":[{"id":"L1","name":"bug","color":"ff0000"}],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}"""
        server.enqueue(MockResponse().setBody(first))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ApiResult.Failure(ApiError.Forbidden), repository.getLabels("o", "r"))

        server.enqueue(MockResponse().setBody(first))
        server.enqueue(MockResponse().setBody("""{"data":{"repository":{"labels":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}"""))
        assertEquals(listOf("bug"), (repository.getLabels("o", "r") as ApiResult.Success).data.map { it.name })
        assertEquals(listOf(null, "next", null, "next"), List(4) { takeVariables()["after"]?.jsonPrimitive?.content })
    }

    @Test
    fun `delete issue uses issue id mutation`() = runTest {
        server.enqueue(MockResponse().setBody("{\"data\":{\"deleteIssue\":{\"clientMutationId\":null}}}"))
        assertTrue(repository.deleteIssue("ISSUE_1") is ApiResult.Success)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("deleteIssue"))
        assertTrue(body.contains("\"issueId\":\"ISSUE_1\""))
    }

    @Test
    fun `templates and issue mutations are parsed`() = runTest {
        val yaml = """
            name: Bug report
            description: Report a bug
            title: "[Bug] "
            labels: [bug]
            assignees: [octo]
            body:
              - type: input
                id: version
                attributes:
                  label: Version
                validations:
                  required: true
        """.trimIndent()
        val config = "blank_issues_enabled: false"
        server.enqueue(MockResponse().setBody("{\"data\":{\"repository\":{\"id\":\"R1\",\"viewerCanCreateIssues\":true,\"isBlankIssuesEnabled\":true,\"defaultBranchRef\":{\"name\":\"main\"}}}}"))
        server.enqueue(MockResponse().setBody("""
            {"data":{"repository":{"object":{"__typename":"Tree","entries":[
              {"name":"bug.yml","path":".github/ISSUE_TEMPLATE/bug.yml","type":"blob","object":{"__typename":"Blob","text":${JsonPrimitive(yaml)},"isBinary":false,"isTruncated":false,"byteSize":${yaml.encodeToByteArray().size}}},
              {"name":"config.yml","path":".github/ISSUE_TEMPLATE/config.yml","type":"blob","object":{"__typename":"Blob","text":${JsonPrimitive(config)},"isBinary":false,"isTruncated":false,"byteSize":${config.length}}},
              {"name":"legacy.md","path":".github/ISSUE_TEMPLATE/legacy.md","type":"blob","object":{"__typename":"Blob","text":"ignored","isBinary":false,"isTruncated":false,"byteSize":7}}
            ]}}}}
        """.trimIndent()))
        val templates = repository.getIssueTemplates("o", "r")
        val result = (templates as ApiResult.Success).data
        val template = result.templates.single()
        assertFalse(result.blankIssuesEnabled)
        assertEquals("bug.yml", template.filename)
        assertEquals(listOf("bug"), template.labels)
        assertEquals(listOf("octo"), template.assignees)
        assertEquals(1, template.fields.size)
        val contextQuery = server.takeRequest().body.readUtf8()
        val formsQuery = server.takeRequest().body.readUtf8()
        assertTrue(contextQuery.contains("defaultBranchRef"))
        assertTrue(formsQuery.contains("main:.github/ISSUE_TEMPLATE"))
        assertTrue(formsQuery.contains("... on Blob"))
        assertFalse(formsQuery.contains("issueTemplates"))

        server.enqueue(MockResponse().setBody(mutationJson("createIssue")))
        server.enqueue(MockResponse().setBody(mutationJson("updateIssue")))
        server.enqueue(MockResponse().setBody(mutationJson("closeIssue")))
        server.enqueue(MockResponse().setBody(mutationJson("reopenIssue")))
        assertTrue(repository.createIssue(CreateRepoIssueInput("R1", "T", "B")) is ApiResult.Success)
        val createBody = server.takeRequest().body.readUtf8()
        assertFalse(createBody.contains("issueTemplate"))
        assertEquals(JsonPrimitive(PageSize.METADATA), Json.parseToJsonElement(createBody).jsonObject.getValue("variables").jsonObject["metadataFirst"])
        assertTrue(repository.updateIssue(UpdateRepoIssueInput("ISSUE_1", "T2", "B2")) is ApiResult.Success)
        val update = takeVariables()
        assertEquals(JsonPrimitive(PageSize.METADATA), update["metadataFirst"])
        val input = update.getValue("input").jsonObject
        assertEquals(JsonArray(emptyList()), input["labelIds"])
        assertEquals(JsonArray(emptyList()), input["assigneeIds"])
        assertEquals(JsonNull, input["milestoneId"])
        assertTrue(repository.closeIssue("ISSUE_1", IssueStateReason.COMPLETED) is ApiResult.Success)
        assertEquals(JsonPrimitive(PageSize.METADATA), takeVariables()["metadataFirst"])
        assertTrue(repository.reopenIssue("ISSUE_1") is ApiResult.Success)
        assertEquals(JsonPrimitive(PageSize.METADATA), takeVariables()["metadataFirst"])
    }

    @Test
    fun `graphql errors become api failure`() = runTest {
        server.enqueue(MockResponse().setBody("{\"data\":null,\"errors\":[{\"message\":\"denied\",\"type\":\"FORBIDDEN\"}]}"))
        assertTrue(repository.getIssues("o", "r", RepoIssueFilter()) is ApiResult.Failure)
    }

    @Test
    fun `缺失仓库根节点不伪造成空列表或详情`() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"data":{"repository":null}}""")) }
        assertTrue(repository.getIssues("o", "r", RepoIssueFilter()) is ApiResult.Failure)
        assertTrue(repository.getIssue("o", "r", 1) is ApiResult.Failure)
    }

    private fun takeVariables() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject

    private fun pageJson(cursor: String?, hasNext: Boolean, number: Int = 1) = """
        {"data":{"repository":{"id":"REPO_1","viewerPermission":"ADMIN","viewerCanCreateIssues":true,"hasIssuesEnabled":true,"issues":{"totalCount":2,"nodes":[${issueNodeJson(number)}],"pageInfo":{"hasNextPage":$hasNext,"endCursor":${cursor?.let { "\"$it\"" } ?: "null"}}}}}}
    """.trimIndent()

    private fun mutationJson(name: String) = "{\"data\":{\"$name\":{\"issue\":${issueNodeJson(1)}}}}"

    private fun issueNodeJson(number: Int, comments: String = """{"totalCount":1}""") = """{
        "id":"ISSUE_$number","url":"https://github.com/octo/repo/issues/$number","number":$number,"title":"Issue $number","body":"body","bodyHTML":"<p>body</p>","state":"OPEN","stateReason":null,
        "author":{"__typename":"User","login":"octo","avatarUrl":"https://a/u1.png"},"authorAssociation":"NONE",
        "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z",
        "includesCreatedEdit":false,"lastEditedAt":null,"editor":null,"locked":false,
        "comments":$comments,"labels":{"nodes":[]},"assignees":{"nodes":[]},"milestone":null,
        "viewerCanClose":true,"viewerCanDelete":true,"viewerCanLabel":true,"viewerCanSetMilestone":true,"viewerCanUpdate":true,"viewerCanSubscribe":true,"viewerCanReopen":false,"viewerSubscription":"UNSUBSCRIBED"
    }""".trimIndent()
}
