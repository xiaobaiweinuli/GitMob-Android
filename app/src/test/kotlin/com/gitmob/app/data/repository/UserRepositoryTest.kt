package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
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

/**
 * 用 MockWebServer 模拟真实 HTTP 响应，不 Mock 内部逻辑，纯 JVM 跑，
 * 不需要打包 APK / 装真机，见 references/testing.md。
 */
class UserRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: UserRepository

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
        repository = UserRepository(api)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private val sampleNodeJson = """
        {
            "id":"U_kg1","login":"octocat","name":"The Octocat","avatarUrl":"https://x/a.png",
            "bio":"hello","company":"@github","location":"SF","websiteUrl":"https://x.dev",
            "email":"o@x.com","pronouns":"they/them","isDeveloperProgramMember":true,
            "isViewer":true,"viewerCanFollow":false,"viewerIsFollowing":false,
            "status":null,
            "socialAccounts":{"nodes":[{"displayName":"@oct","provider":"TWITTER","url":"https://x.com/oct"}]},
            "followers":{"totalCount":10},
            "following":{"totalCount":3},
            "organizations":{"totalCount":2},
            "repositories":{"totalCount":5},
            "starredRepositories":{"totalCount":20},
            "gists":{"totalCount":4},
            "pinnedItems":{"nodes":[
                {"name":"repo1","url":"https://github.com/octocat/repo1","shortDescriptionHTML":"desc",
                 "stargazerCount":100,"forkCount":4,"primaryLanguage":{"name":"Kotlin","color":"#A97BFF"},
                 "owner":{"login":"octocat","avatarUrl":"https://x/a.png"}}
            ]}
        }
    """.trimIndent()

    @Test
    fun `getViewerProfile 一次GraphQL请求拿全部数据`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{
                    "viewer":$sampleNodeJson,
                    "issuesInvolvingMe":{"issueCount":3},
                    "prsInvolvingMe":{"issueCount":1},
                    "discussionsInvolvingMe":{"discussionCount":2}
                }}
                """.trimIndent(),
            ),
        )

        val result = repository.getViewerProfile()

        assertTrue(result is ApiResult.Success)
        val profile = (result as ApiResult.Success).data
        assertEquals("octocat", profile.user.login)
        assertEquals("they/them", profile.extra?.pronouns)
        assertEquals(10, profile.user.followers)
        assertEquals(2, profile.user.organizationsCount)
        assertEquals(5, profile.repoCount)
        assertEquals(20, profile.starredCount)
        assertEquals(4, profile.gistCount)
        assertEquals(1, profile.pinnedRepos.size)
        assertEquals("repo1", profile.pinnedRepos.first().name)
        assertTrue(profile.followState.isViewer)
        assertEquals(3, profile.involvedIssueCount)
        assertEquals(1, profile.involvedPrCount)
        assertEquals(2, profile.involvedDiscussionCount)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `别人的资料 isViewer为false且展示关注状态`() = runTest {
        val othersJson = sampleNodeJson
            .replace("\"isViewer\":true", "\"isViewer\":false")
            .replace("\"viewerCanFollow\":false", "\"viewerCanFollow\":true")
        server.enqueue(MockResponse().setBody("""{"data":{"user":$othersJson}}"""))

        val result = repository.getUserProfile("octocat")

        assertTrue(result is ApiResult.Success)
        val profile = (result as ApiResult.Success).data
        assertFalse(profile.followState.isViewer)
        assertTrue(profile.followState.viewerCanFollow)
    }

    @Test
    fun `GraphQL返回401时映射为Unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))

        val result = repository.getViewerProfile()

        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
    }
}

class UserRepositoryProfileOwnerTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: UserRepository

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
        repository = UserRepository(api)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `repositoryOwner返回User时分流为Person`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"repositoryOwner":{
                    "__typename":"User","id":"U_kg1","login":"octocat","avatarUrl":"https://x/a.png","url":"https://github.com/octocat",
                    "name":"The Octocat","bio":"hi","company":"@github","location":"SF","email":"octo@example.com",
                    "pronouns":"he/him","status":{"emoji":"🌙","message":"Coding"},
                    "socialAccounts":{"nodes":[{"displayName":"@octocat","provider":"TWITTER","url":"https://x.com/octocat"}]},
                    "isViewer":false,
                    "viewerCanFollow":true,"viewerIsFollowing":false,
                    "followers":{"totalCount":10},"following":{"totalCount":2},
                    "organizations":{"totalCount":1},"starredRepositories":{"totalCount":8},
                    "gists":{"totalCount":6},
                    "websiteUrl":"https://x.dev","repositories":{"totalCount":5},
                    "pinnedItems":{"nodes":[{
                        "name":"repo1","url":"https://github.com/octocat/repo1","shortDescriptionHTML":"desc",
                        "stargazerCount":10,"forkCount":2,"primaryLanguage":{"name":"Kotlin","color":"#A97BFF"},
                        "owner":{"login":"octocat","avatarUrl":"https://x/a.png"}
                    }]}
                }}}
                """.trimIndent(),
            ),
        )

        val result = repository.getProfileOwner("octocat")

        assertTrue(result is ApiResult.Success)
        val owner = (result as ApiResult.Success).data
        assertTrue(owner is com.gitmob.app.data.model.ProfileOwner.Person)
        owner as com.gitmob.app.data.model.ProfileOwner.Person
        assertEquals("octocat", owner.login)
        assertTrue(owner.followState.viewerCanFollow)
        assertFalse(owner.isViewer)
        assertEquals("octo@example.com", owner.email)
        assertEquals("@github", owner.company)
        assertEquals("SF", owner.location)
        assertEquals("Coding", owner.status?.message)
        assertEquals(6, owner.gistCount)
        assertEquals(1, owner.socialAccounts.size)
        assertEquals("@octocat", owner.socialAccounts.first().displayName)
        assertEquals(1, owner.pinnedRepos.size)
        assertEquals("octocat", owner.pinnedRepos.first().ownerLogin)
        assertEquals("repo1", owner.pinnedRepos.first().name)
    }

    @Test
    fun `repositoryOwner是viewer时Gist数量使用viewer的ALL统计`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{
                    "viewer":{"login":"octocat","gists":{"totalCount":9}},
                    "repositoryOwner":{
                        "__typename":"User","id":"U_kg1","login":"octocat",
                        "isViewer":true,
                        "gists":{"totalCount":6}
                    }
                }}
                """.trimIndent(),
            ),
        )

        val result = repository.getProfileOwner("octocat")

        val owner = (result as ApiResult.Success).data as com.gitmob.app.data.model.ProfileOwner.Person
        assertEquals(9, owner.gistCount)
    }

    @Test
    fun `repositoryOwner返回Organization时分流为Org`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"repositoryOwner":{
                    "__typename":"Organization","id":"O_kg1","login":"github","avatarUrl":"https://x/o.png","url":"https://github.com/github",
                    "name":"GitHub","description":"desc","isVerified":true,
                    "websiteUrl":"https://github.com","membersWithRole":{"totalCount":100},
                    "repositories":{"totalCount":300},
                    "pinnedItems":{"nodes":[{
                        "name":"docs","url":"https://github.com/github/docs","shortDescriptionHTML":"docs",
                        "stargazerCount":20,"forkCount":3,"primaryLanguage":null,
                        "owner":{"login":"github","avatarUrl":"https://x/o.png"}
                    }]}
                }}}
                """.trimIndent(),
            ),
        )

        val result = repository.getProfileOwner("github")

        assertTrue(result is ApiResult.Success)
        val owner = (result as ApiResult.Success).data
        assertTrue(owner is com.gitmob.app.data.model.ProfileOwner.Org)
        owner as com.gitmob.app.data.model.ProfileOwner.Org
        assertTrue(owner.isVerified)
        assertEquals("desc", owner.description)
        assertEquals(100, owner.membersCount)
        assertEquals(300, owner.repoCount)
        assertEquals(1, owner.pinnedRepos.size)
        assertEquals("github", owner.pinnedRepos.first().ownerLogin)
        assertEquals("docs", owner.pinnedRepos.first().name)
    }
}
