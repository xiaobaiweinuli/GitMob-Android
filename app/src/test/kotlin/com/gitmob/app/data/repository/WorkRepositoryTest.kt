package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.DiscussionStateReason
import com.gitmob.app.data.model.IssueState
import com.gitmob.app.data.model.IssueStateReason
import com.gitmob.app.data.model.PullRequestState
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

class WorkRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: WorkRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val api = GHApiClient(
            okHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            tokenProvider = object : AccessTokenProvider {
                override suspend fun getToken() = "fake-token"
            },
            restBaseUrl = baseUrl,
            graphQLUrl = "$baseUrl/graphql",
        )
        repository = WorkRepository(api)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `getInvolvedIssues解析search结果并标记isPullRequest为false`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"search":{
                    "issueCount":1,
                    "nodes":[{
                        "__typename":"Issue","id":"I_1","number":42,"title":"bug",
                        "state":"OPEN","stateReason":"REOPENED","locked":true,
                        "updatedAt":"2026-01-01T00:00:00Z",
                        "repository":{"name":"repo","owner":{"login":"octocat","avatarUrl":null}}
                    }],
                    "pageInfo":{"hasNextPage":false,"endCursor":null}
                }}}
                """.trimIndent(),
            ),
        )

        val result = repository.getInvolvedIssues()

        assertTrue(result is ApiResult.Success)
        val page = (result as ApiResult.Success).data
        assertEquals(1, page.totalCount)
        val item = page.items.first()
        assertEquals(42, item.number)
        assertEquals(false, item.isPullRequest)
        assertEquals("octocat", item.repoOwner)
        assertEquals(IssueState.OPEN, item.issueState)
        assertEquals(IssueStateReason.REOPENED, item.issueStateReason)
        assertTrue(item.locked)
    }

    @Test
    fun `getInvolvedPullRequests解析草稿和锁定状态`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"search":{
                    "issueCount":1,
                    "nodes":[{
                        "__typename":"PullRequest","id":"PR_1","number":18,"title":"draft",
                        "state":"OPEN","isDraft":true,"locked":false,
                        "updatedAt":"2026-01-01T00:00:00Z",
                        "repository":{"name":"repo","owner":{"login":"octocat","avatarUrl":null}}
                    }],
                    "pageInfo":{"hasNextPage":false,"endCursor":null}
                }}}
                """.trimIndent(),
            ),
        )

        val result = repository.getInvolvedPullRequests()

        assertTrue(result is ApiResult.Success)
        val item = (result as ApiResult.Success).data.items.first()
        assertTrue(item.isPullRequest)
        assertEquals(PullRequestState.OPEN, item.pullRequestState)
        assertTrue(item.isDraft)
        assertEquals(false, item.locked)
    }

    @Test
    fun `getInvolvedDiscussions用discussionCount而不是issueCount`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"search":{
                    "discussionCount":2,
                    "nodes":[{
                        "__typename":"Discussion","id":"D_1","number":7,"title":"讨论标题",
                        "stateReason":"RESOLVED","isAnswered":true,"locked":false,
                        "updatedAt":"2026-01-01T00:00:00Z",
                        "repository":{"name":"repo","owner":{"login":"octocat","avatarUrl":null}}
                    }],
                    "pageInfo":{"hasNextPage":false,"endCursor":null}
                }}}
                """.trimIndent(),
            ),
        )

        val result = repository.getInvolvedDiscussions()

        assertTrue(result is ApiResult.Success)
        val page = (result as ApiResult.Success).data
        assertEquals(2, page.totalCount)
        val item = page.items.first()
        assertEquals("讨论标题", item.title)
        assertEquals(DiscussionStateReason.RESOLVED, item.stateReason)
        assertTrue(item.isAnswered)
    }
}
