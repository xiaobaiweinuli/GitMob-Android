package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RepoIssueRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoIssueRepository

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repository = RepoIssueRepository(GHApiClient(OkHttpClient(), Json { ignoreUnknownKeys = true }, object : AccessTokenProvider { override suspend fun getToken() = "token" }, baseUrl, "$baseUrl/graphql"))
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
        server.enqueue(MockResponse().setBody("{\"data\":{\"repository\":{\"id\":\"R1\",\"viewerCanCreateIssues\":true,\"isBlankIssuesEnabled\":true,\"issueTemplates\":[{\"name\":\"Bug\",\"about\":\"Report\",\"title\":\"[Bug] \",\"body\":\"Body\",\"filename\":\"bug.md\",\"labels\":[\"bug\"],\"assignees\":[]}]}}}"))
        val templates = repository.getIssueTemplates("o", "r")
        assertEquals("bug.md", (templates as ApiResult.Success).data.second.single().filename)

        server.enqueue(MockResponse().setBody(mutationJson("createIssue")))
        server.enqueue(MockResponse().setBody(mutationJson("updateIssue")))
        server.enqueue(MockResponse().setBody(mutationJson("closeIssue")))
        server.enqueue(MockResponse().setBody(mutationJson("reopenIssue")))
        assertTrue(repository.createIssue(CreateRepoIssueInput("R1", "T", "B", issueTemplate = "bug.md")) is ApiResult.Success)
        assertTrue(repository.updateIssue(UpdateRepoIssueInput("ISSUE_1", "T2", "B2")) is ApiResult.Success)
        assertTrue(repository.closeIssue("ISSUE_1", IssueStateReason.COMPLETED) is ApiResult.Success)
        assertTrue(repository.reopenIssue("ISSUE_1") is ApiResult.Success)
    }

    @Test
    fun `graphql errors become api failure`() = runTest {
        server.enqueue(MockResponse().setBody("{\"data\":null,\"errors\":[{\"message\":\"denied\",\"type\":\"FORBIDDEN\"}]}"))
        assertTrue(repository.getIssues("o", "r", RepoIssueFilter()) is ApiResult.Failure)
    }

    private fun pageJson(cursor: String?, hasNext: Boolean, number: Int = 1) = """
        {"data":{"repository":{"id":"REPO_1","viewerPermission":"ADMIN","viewerCanCreateIssues":true,"hasIssuesEnabled":true,"issues":{"totalCount":2,"nodes":[${issueNodeJson(number)}],"pageInfo":{"hasNextPage":$hasNext,"endCursor":${cursor?.let { "\"$it\"" } ?: "null"}}}}}}
    """.trimIndent()

    private fun mutationJson(name: String) = "{\"data\":{\"$name\":{\"issue\":${issueNodeJson(1)}}}}"

    private fun issueNodeJson(number: Int) = """{
        "id":"ISSUE_$number","number":$number,"title":"Issue $number","body":"body","bodyHTML":"<p>body</p>","state":"OPEN","stateReason":null,
        "author":{"id":"USER_1","login":"octo","name":null,"avatarUrl":null,"bio":null},"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z","locked":false,
        "comments":{"totalCount":1,"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}},"labels":{"nodes":[]},"assignees":{"nodes":[]},"milestone":null,
        "viewerCanClose":true,"viewerCanDelete":true,"viewerCanLabel":true,"viewerCanSetMilestone":true,"viewerCanUpdate":true,"viewerCanSubscribe":true,"viewerCanReopen":false,"viewerSubscription":"UNSUBSCRIBED"
    }""".trimIndent()
}
