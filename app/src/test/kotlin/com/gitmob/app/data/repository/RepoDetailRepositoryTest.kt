package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
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

class RepoDetailRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoDetailRepository

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
        repository = RepoDetailRepository(api)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `getRepoDetail解析权限并派生RepoCapabilities`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"repository":{
                    "id":"R1","name":"GitMob-Android","description":"desc","homepageUrl":null,
                    "owner":{"login":"xiaobaiweinuli","avatarUrl":null},
                    "isPrivate":false,"isArchived":false,"isTemplate":true,"isFork":false,"parent":null,
                    "stargazerCount":20,"viewerHasStarred":false,"forkCount":2,
                    "issues":{"totalCount":2},"pullRequests":{"totalCount":0},
                    "watchers":{"totalCount":1},"viewerSubscription":"IGNORED",
                    "licenseInfo":{"name":"Apache License 2.0","spdxId":"Apache-2.0"},
                    "refs":{"totalCount":6},"defaultBranchRef":{"name":"claude"},
                    "releases":{"totalCount":43,"nodes":[{"name":"GitMob v1.7.8","tagName":"v1.7.8"}]},
                    "primaryLanguage":{"name":"Kotlin","color":"#A97BFF"},
                    "repositoryTopics":{"nodes":[{"topic":{"name":"github"}},{"topic":{"name":"android"}}]},
                    "viewerPermission":"ADMIN","viewerCanCreateIssues":true,"hasIssuesEnabled":true,
                    "isBlankIssuesEnabled":false,"issueCreationPolicy":"COLLABORATORS_ONLY"
                }}}
                """.trimIndent(),
            ),
        )

        val result = repository.getRepoDetail("xiaobaiweinuli", "GitMob-Android")

        assertTrue(result is ApiResult.Success)
        val detail = (result as ApiResult.Success).data
        assertEquals("GitMob-Android", detail.name)
        assertEquals("desc", detail.description)
        assertEquals("claude", detail.defaultBranchName)
        assertEquals("v1.7.8", detail.latestReleaseTag)
        assertEquals(listOf("github", "android"), detail.topics)
        assertTrue(detail.capabilities.canDeleteRepo) // ADMIN 应该派生出可删除仓库
        assertTrue(detail.capabilities.canManageBranchProtection)
        assertTrue(detail.capabilities.canDeleteIssues)
        assertTrue(detail.viewerCanCreateIssues)
        assertTrue(detail.hasIssuesEnabled)
    }

    @Test
    fun `deleteBranch成功时返回Unit`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"deleteRef":{"clientMutationId":null}}}"""))

        val result = repository.deleteBranch("REF_123", "xiaobaiweinuli", "GitMob-Android")

        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `setDefaultBranch走REST并正确解析完整仓库响应`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"name":"GitMob-Android","default_branch":"claude"}""",
            ),
        )

        val result = repository.setDefaultBranch("xiaobaiweinuli", "GitMob-Android", "claude")

        assertTrue(result is ApiResult.Success)
    }
}
