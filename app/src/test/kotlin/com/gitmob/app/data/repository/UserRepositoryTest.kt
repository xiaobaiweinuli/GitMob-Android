package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.RepoVisibility
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
import java.time.Instant

private fun buildApolloClient(baseUrl: String): ApolloClient =
    ApolloClient.Builder()
        .serverUrl("$baseUrl/graphql")
        .okHttpClient(OkHttpClient())
        .addHttpInterceptor(
            AuthHttpInterceptor(
                object : AccessTokenProvider {
                    override suspend fun getToken() = "fake-token"
                },
            ),
        )
        .build()

/**
 * 用 MockWebServer 模拟真实 HTTP 响应，不 Mock 内部逻辑，纯 JVM 跑，
 * 仅作请求契约与边界的辅助回归，不能替代本轮 APK 的原生验收。
 */
class UserRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repository = UserRepository(buildApolloClient(baseUrl))
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
            "isBountyHunter":false,"isCampusExpert":false,"isGitHubStar":false,
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
                {"__typename":"Repository","name":"repo1","url":"https://github.com/octocat/repo1","description":"desc :rocket:",
                 "stargazerCount":100,"forkCount":4,"primaryLanguage":{"name":"Kotlin","color":"#A97BFF"},
                 "owner":{"__typename":"User","login":"octocat","avatarUrl":"https://x/a.png"}}
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
        assertEquals("desc :rocket:", profile.pinnedRepos.first().description)
        assertTrue(profile.followState.isViewer)
        assertEquals(3, profile.involvedIssueCount)
        assertEquals(1, profile.involvedPrCount)
        assertEquals(2, profile.involvedDiscussionCount)
        assertEquals(1, server.requestCount)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.SOCIAL_ACCOUNTS), variables["socialAccountsFirst"])
        assertEquals(JsonPrimitive(PageSize.PINNED_ITEMS), variables["pinnedItemsFirst"])
        assertEquals(result, repository.getViewerProfile())
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
        val variables = takeVariables()
        assertEquals(JsonPrimitive("octocat"), variables["login"])
        assertEquals(JsonPrimitive(PageSize.SOCIAL_ACCOUNTS), variables["socialAccountsFirst"])
        assertEquals(JsonPrimitive(PageSize.PINNED_ITEMS), variables["pinnedItemsFirst"])
    }

    @Test
    fun `GraphQL返回401时映射为Unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))

        val result = repository.getViewerProfile()

        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
    }

    @Test
    fun `独立用户仓库查询同步可见性日期议题开关及容量参数`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"data":{"user":{"repositories":{"totalCount":1,"nodes":[{
                "name":"repo","owner":{"__typename":"User","login":"octocat","avatarUrl":"https://x/a.png"},
                "description":null,"homepageUrl":null,"visibility":"PRIVATE","isArchived":false,"isFork":false,
                "hasIssuesEnabled":true,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z","pushedAt":null,
                "parent":null,"primaryLanguage":null,"stargazerCount":0,"forkCount":0,"issues":{"totalCount":0},
                "repositoryTopics":{"nodes":[]},"defaultBranchRef":null
            }],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
        """.trimIndent()))

        val result = repository.getUserRepos("octocat", "previous") as ApiResult.Success

        val repo = result.data.items.single()
        assertEquals(RepoVisibility.PRIVATE, repo.visibility)
        assertTrue(repo.hasIssuesEnabled)
        assertEquals(0, repo.openIssueCount)
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), repo.updatedAt)
        assertEquals(null, repo.pushedAt)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.REPOS), variables["first"])
        assertEquals(JsonPrimitive(PageSize.TOPICS_PER_REPO), variables["topicsFirst"])
        assertEquals(JsonPrimitive("previous"), variables["after"])
    }

    @Test
    fun `关注和组织分页请求统一容量并保留游标`() = runTest {
        val loaders = listOf<Triple<String, String, suspend () -> ApiResult<*>>>(
            Triple("user", "followers", suspend { repository.getFollowers("octocat", "previous") }),
            Triple("user", "following", suspend { repository.getFollowing("octocat", "previous") }),
            Triple("user", "organizations", suspend { repository.getUserOrgs("octocat", "previous") }),
            Triple("organization", "membersWithRole", suspend { repository.getOrgMembers("github", "previous") }),
        )
        for ((root, field, load) in loaders) {
            server.enqueue(MockResponse().setBody("""{"data":{"$root":{"$field":{"totalCount":0,"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}"""))

            assertTrue(load() is ApiResult.Success)
            val variables = takeVariables()
            assertEquals(JsonPrimitive(if (field == "organizations") PageSize.ORGS else PageSize.USER_LIST), variables["first"])
            assertEquals(JsonPrimitive("previous"), variables["after"])
            assertEquals(JsonPrimitive(if (root == "organization") "github" else "octocat"), variables[if (root == "organization") "orgLogin" else "login"])
        }
    }

    @Test
    fun `viewer和user组织预览保持固定容量而不伪造分页`() = runTest {
        for (login in listOf(null, "octocat")) {
            val root = if (login == null) "viewer" else "user"
            server.enqueue(MockResponse().setBody("""{"data":{"$root":{"organizations":{"nodes":[]}}}}"""))

            assertTrue((repository.getOrganizations(login) as ApiResult.Success).data.isEmpty())
            val variables = takeVariables()
            assertEquals(JsonPrimitive(PageSize.ORGS_PREVIEW), variables["first"])
            assertEquals(login?.let(::JsonPrimitive), variables["login"])
            assertFalse("after" in variables)
        }
    }

    @Test
    fun `独立用户星标同时传入列表和话题容量`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"user":{"starredRepositories":{"totalCount":0,"edges":[],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}"""))

        val page = (repository.getUserStars("octocat", "previous") as ApiResult.Success).data

        assertTrue(page.items.isEmpty())
        assertTrue(page.hasNextPage)
        assertEquals("next", page.endCursor)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.STARRED_REPOS), variables["first"])
        assertEquals(JsonPrimitive(PageSize.TOPICS_PER_REPO), variables["topicsFirst"])
        assertEquals(JsonPrimitive("octocat"), variables["login"])
        assertEquals(JsonPrimitive("previous"), variables["after"])
    }

    @Test
    fun `缺失用户或组织根节点不伪造成空分页`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setBody("""{"data":{"user":null}}""")) }
        assertTrue(repository.getUserProfile("missing") is ApiResult.Failure)
        assertTrue(repository.getUserStars("missing") is ApiResult.Failure)
        assertTrue(repository.getUserOrgs("missing") is ApiResult.Failure)
        server.enqueue(MockResponse().setBody("""{"data":{"organization":null}}"""))
        assertTrue(repository.getOrgMembers("missing") is ApiResult.Failure)
    }

    private fun takeVariables() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
}

class UserRepositoryProfileOwnerTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repository = UserRepository(buildApolloClient(baseUrl))
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
                {"data":{"viewer":{"login":"octocat","gists":{"totalCount":9}},"repositoryOwner":{
                    "__typename":"User","id":"U_kg1","login":"octocat","avatarUrl":"https://x/a.png","url":"https://github.com/octocat",
                    "name":"The Octocat","bio":"hi","company":"@github","location":"SF","email":"octo@example.com",
                    "pronouns":"he/him","status":{"emoji":"🌙","message":"Coding"},
                    "isDeveloperProgramMember":false,"isBountyHunter":false,
                    "isCampusExpert":false,"isGitHubStar":false,
                    "socialAccounts":{"nodes":[{"displayName":"@octocat","provider":"TWITTER","url":"https://x.com/octocat"}]},
                    "isViewer":false,
                    "viewerCanFollow":true,"viewerIsFollowing":false,
                    "followers":{"totalCount":10},"following":{"totalCount":2},
                    "organizations":{"totalCount":1},"starredRepositories":{"totalCount":8},
                    "gists":{"totalCount":6},
                    "websiteUrl":"https://x.dev","repositories":{"totalCount":5},
                    "pinnedItems":{"nodes":[{
                        "__typename":"Repository","name":"repo1","url":"https://github.com/octocat/repo1","description":"desc",
                        "stargazerCount":10,"forkCount":2,"primaryLanguage":{"name":"Kotlin","color":"#A97BFF"},
                        "owner":{"__typename":"User","login":"octocat","avatarUrl":"https://x/a.png"}
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
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.SOCIAL_ACCOUNTS), variables["socialAccountsFirst"])
        assertEquals(JsonPrimitive(PageSize.PINNED_ITEMS), variables["pinnedItemsFirst"])
        assertEquals(JsonPrimitive("octocat"), variables["login"])
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
                        "avatarUrl":"https://x/a.png","url":"https://github.com/octocat",
                        "name":null,"bio":null,"company":null,"location":null,"email":"",
                        "pronouns":null,"status":null,
                        "socialAccounts":{"nodes":[]},
                        "isDeveloperProgramMember":false,"isBountyHunter":false,
                        "isCampusExpert":false,"isGitHubStar":false,
                        "isViewer":true,"viewerCanFollow":false,"viewerIsFollowing":false,
                        "followers":{"totalCount":0},"following":{"totalCount":0},
                        "organizations":{"totalCount":0},"starredRepositories":{"totalCount":0},
                        "websiteUrl":null,"repositories":{"totalCount":0},
                        "gists":{"totalCount":6},
                        "pinnedItems":{"nodes":[]}
                    }
                }}
                """.trimIndent(),
            ),
        )

        val result = repository.getProfileOwner("octocat")

        val owner = (result as ApiResult.Success).data as com.gitmob.app.data.model.ProfileOwner.Person
        assertEquals(9, owner.gistCount)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.SOCIAL_ACCOUNTS), variables["socialAccountsFirst"])
        assertEquals(JsonPrimitive(PageSize.PINNED_ITEMS), variables["pinnedItemsFirst"])
    }

    @Test
    fun `repositoryOwner返回Organization时分流为Org`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"login":"octocat","gists":{"totalCount":9}},"repositoryOwner":{
                    "__typename":"Organization","id":"O_kg1","login":"github","avatarUrl":"https://x/o.png","url":"https://github.com/github",
                    "name":"GitHub","description":"desc","isVerified":true,
                    "viewerCanCreateRepositories":true,"viewerIsFollowing":false,
                    "websiteUrl":"https://github.com","membersWithRole":{"totalCount":100},
                    "repositories":{"totalCount":300},
                    "pinnedItems":{"nodes":[{
                        "__typename":"Repository","name":"docs","url":"https://github.com/github/docs","description":"docs",
                        "stargazerCount":20,"forkCount":3,"primaryLanguage":null,
                        "owner":{"__typename":"Organization","login":"github","avatarUrl":"https://x/o.png"}
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
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.SOCIAL_ACCOUNTS), variables["socialAccountsFirst"])
        assertEquals(JsonPrimitive(PageSize.PINNED_ITEMS), variables["pinnedItemsFirst"])
        assertEquals(JsonPrimitive("github"), variables["login"])
    }

    @Test
    fun `统一资料查询缺失owner时仍返回失败`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"viewer":{"login":"octocat","gists":{"totalCount":0}},"repositoryOwner":null}}"""))
        assertTrue(repository.getProfileOwner("missing") is ApiResult.Failure)
    }

    private fun takeVariables() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
}
