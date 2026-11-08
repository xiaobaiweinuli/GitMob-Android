package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.data.model.RepoFilters
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepoSort
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.model.RepositoryCreateInput
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerType
import com.gitmob.app.testutil.AccountSessionRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RepoRepositoryTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var repository: RepoRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val tokenProvider = object : AccessTokenProvider {
            override suspend fun getToken() = "fake-token"
        }
        val api = GHApiClient(
            okHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            tokenProvider = tokenProvider,
            restBaseUrl = baseUrl,
        )
        val apollo = ApolloClient.Builder()
            .serverUrl("$baseUrl/graphql")
            .okHttpClient(OkHttpClient())
            .addHttpInterceptor(AuthHttpInterceptor(tokenProvider))
            .build()
        repository = RepoRepository(api, apollo)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `创建个人仓库使用user repos并显式发送false默认值`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """
                {"name":"new-repo","owner":{"login":"octocat"}}
                """.trimIndent(),
            ),
        )

        val result = repository.createRepository(
            RepositoryCreateInput(
                owner = RepositoryCreateOwner("u1", "octocat", "Octocat", null, RepositoryCreateOwnerType.USER, true),
                name = "new-repo",
                description = null,
                isPrivate = false,
                addReadme = false,
                licenseTemplate = null,
                gitignoreTemplate = null,
            ),
        )

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("/user/repos", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"private\":false"))
        assertTrue(body.contains("\"auto_init\":false"))
        assertTrue(!body.contains("license_template"))
    }

    @Test
    fun `创建组织仓库使用组织repos并映射owner`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """
                {"name":"org-repo","owner":{"login":"acme"}}
                """.trimIndent(),
            ),
        )

        val result = repository.createRepository(
            RepositoryCreateInput(
                owner = RepositoryCreateOwner("o1", "acme", null, null, RepositoryCreateOwnerType.ORGANIZATION, true),
                name = "org-repo",
                description = "description",
                isPrivate = true,
                addReadme = true,
                licenseTemplate = "mit",
                gitignoreTemplate = "Kotlin",
            ),
        )

        assertEquals("acme", (result as ApiResult.Success).data.owner)
        val request = server.takeRequest()
        assertEquals("/orgs/acme/repos", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"private\":true"))
        assertTrue(body.contains("\"auto_init\":true"))
        assertTrue(body.contains("\"license_template\":\"mit\""))
        assertTrue(body.contains("\"gitignore_template\":\"Kotlin\""))
    }

    @Test
    fun `license和gitignore模板路径正确映射`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("[{\"key\":\"mit\",\"name\":\"MIT License\"}]"))
        server.enqueue(MockResponse().setBody("[\"Kotlin\",\"Android\"]"))

        val licenses = repository.getLicenseTemplates()
        val gitignore = repository.getGitignoreTemplates()

        assertEquals("mit", (licenses as ApiResult.Success).data.single().key)
        assertEquals(listOf("Kotlin", "Android"), (gitignore as ApiResult.Success).data)
        assertEquals("/licenses", server.takeRequest().path)
        assertEquals("/gitignore/templates", server.takeRequest().path)
    }

    @Test
    fun `创建仓库所有者查询使用PageSize并传递下一页cursor`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"id":"u1","login":"octocat","name":"Octocat","avatarUrl":"https://avatars.example.com/octocat","organizations":{"nodes":[{"id":"o1","login":"acme","name":"Acme","avatarUrl":"https://avatars.example.com/acme","viewerCanCreateRepositories":true}],"pageInfo":{"hasNextPage":true,"endCursor":"cursor-1"}}}}}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"id":"u1","login":"octocat","name":"Octocat","avatarUrl":"https://avatars.example.com/octocat","organizations":{"nodes":[{"id":"o2","login":"beta","name":"Beta","avatarUrl":"https://avatars.example.com/beta","viewerCanCreateRepositories":false}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
                """.trimIndent(),
            ),
        )

        val first = repository.getRepositoryCreateOwners()
        val second = repository.getRepositoryCreateOwners("cursor-1")

        assertEquals("cursor-1", (first as ApiResult.Success).data.endCursor)
        assertEquals("beta", (second as ApiResult.Success).data.organizations.single().login)
        val firstBody = server.takeRequest().body.readUtf8()
        val secondBody = server.takeRequest().body.readUtf8()
        assertEquals(JsonPrimitive(PageSize.ORGS), Json.parseToJsonElement(firstBody).jsonObject.getValue("variables").jsonObject["first"])
        assertTrue(!firstBody.contains("\"after\":\"cursor-1\""))
        assertTrue(secondBody.contains("\"after\":\"cursor-1\""))
    }

    @Test
    fun `解析搜索结果保留卡片字段和实际可见性`() = runTest(accounts.context) {
        enqueueOwner()
        server.enqueue(MockResponse().setBody(response(RepoVisibility.entries.map { node(it.name, visibility = it.name) })))
        val page = (repository.getRepos() as ApiResult.Success).data
        assertEquals(RepoVisibility.entries, page.items.map { it.visibility })
        assertTrue(page.items.all { it.hasIssuesEnabled && it.openIssueCount == 0 })
        assertEquals("me", page.ownerContext?.owner?.login)
        assertEquals(3, page.totalCount)
        assertEquals(3, page.receivedCount)
        val first = takeRequest()
        assertFalse(first.getValue("query").jsonPrimitive.content.contains("repositories("))
        val variables = takeVariables()
        assertEquals("user:me fork:true sort:updated-desc", variables.getValue("query").jsonPrimitive.content)
        assertEquals(JsonPrimitive(PageSize.REPOS), variables["first"])
        assertEquals(JsonPrimitive(PageSize.TOPICS_PER_REPO), variables["topicsFirst"])
        assertFalse(variables.containsKey("after"))
    }

    @Test
    fun `viewer用户和组织在同一搜索中发送文字及筛选交集`() = runTest(accounts.context) {
        for (login in listOf(null, "other", "acme")) {
            enqueueOwner(login, organization = login == "acme", canCreate = login != "other")
            server.enqueue(MockResponse().setBody(response(listOf(node("server-match")))))
            val filters = RepoFilters(query = "ANDROID", visibility = RepoVisibility.PRIVATE,
                isArchived = false, isFork = true, sort = RepoSort.STARS_DESC)
            val page = (repository.getRepos(login, filters = filters) as ApiResult.Success).data
            takeRequest()
            val prefix = if (login == "acme") "org:acme" else "user:${login ?: "me"}"
            assertEquals("$prefix \"android\" in:name,description is:private archived:false fork:only sort:stars-desc",
                takeVariables().getValue("query").jsonPrimitive.content)
            // The server's result must not be filtered a second time by the client.
            assertEquals("server-match", page.items.single().name)
            assertEquals(login != "other", page.ownerContext?.owner?.canCreateRepository)
        }
    }

    @Test
    fun `owner不存在或权限请求失败不发送无范围搜索`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"data":{"repositoryOwner":null}}"""))
        assertTrue(repository.getRepos("missing") is ApiResult.Failure)
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertTrue(repository.getRepos() is ApiResult.Failure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `组织无创建权限仍可读取仓库且保留权限上下文`() = runTest(accounts.context) {
        enqueueOwner("acme", organization = true, canCreate = false)
        server.enqueue(MockResponse().setBody(response()))
        val page = (repository.getRepos("acme") as ApiResult.Success).data
        assertEquals(RepositoryCreateOwnerType.ORGANIZATION, page.ownerContext?.owner?.type)
        assertFalse(page.ownerContext!!.owner.canCreateRepository)
    }

    @Test
    fun `游标分页只请求一页且owner元数据复用`() = runTest(accounts.context) {
        enqueueOwner()
        server.enqueue(MockResponse().setBody(response(listOf(node("z"), node("a")), cursor = "c1", total = 40)))
        val first = (repository.getRepos(filters = RepoFilters(query = "text")) as ApiResult.Success).data
        server.enqueue(MockResponse().setBody(response(listOf(node("a"), node("b")), total = 40)))
        val second = (repository.getRepos(after = first.endCursor, filters = RepoFilters(query = "text"), received = first.receivedCount) as ApiResult.Success).data
        assertEquals(listOf("z", "a"), first.items.map { it.name })
        assertEquals(4, second.receivedCount)
        assertEquals(3, server.requestCount)
        takeRequest(); takeRequest()
        assertEquals(JsonPrimitive("c1"), takeVariables()["after"])
    }

    @Test
    fun `累计数量按原始节点而非去重数量计算并在一千条停止`() = runTest(accounts.context) {
        enqueueOwner()
        server.enqueue(MockResponse().setBody(response(List(PageSize.REPOS) { node("same") }, cursor = "last", total = 1500)))
        val page = (repository.getRepos(after = "previous", received = 980) as ApiResult.Success).data
        assertEquals(1000, page.receivedCount)
        assertEquals(1500, page.totalCount)
        assertTrue(page.limitReached)
        assertFalse(page.hasNextPage)
        assertEquals(null, page.endCursor)
    }

    @Test
    fun `最后一次请求按剩余容量缩小且恰好一千条无截断提示`() = runTest(accounts.context) {
        enqueueOwner()
        server.enqueue(MockResponse().setBody(response(List(5) { node("r$it") }, total = 1000)))
        val page = (repository.getRepos(after = "previous", received = 995) as ApiResult.Success).data
        takeRequest()
        assertEquals(JsonPrimitive(5), takeVariables()["first"])
        assertFalse(page.hasNextPage)
        assertFalse(page.limitReached)
    }

    @Test
    fun `空页或游标不推进作为失败返回供重试而非自动循环`() = runTest(accounts.context) {
        enqueueOwner()
        for ((nodes, cursor) in listOf(emptyList<String>() to "next", listOf(node("r")) to "same", listOf(node("r")) to null)) {
            server.enqueue(MockResponse().setBody(response(nodes, cursor = cursor, hasNext = true)))
            assertTrue(repository.getRepos(after = "same") is ApiResult.Failure)
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `搜索失败和缺失必需卡片字段返回失败`() = runTest(accounts.context) {
        enqueueOwner()
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        assertTrue(repository.getRepos() is ApiResult.Failure)
        server.enqueue(MockResponse().setBody(response(listOf(node("bad").replace("\"hasIssuesEnabled\":true,", "")))))
        assertTrue(repository.getRepos() is ApiResult.Failure)
    }

    @Test
    fun `仅viewer默认首页缓存且强制刷新替换owner和搜索缓存`() = runTest(accounts.context) {
        enqueueOwner()
        server.enqueue(MockResponse().setBody(response(listOf(node("cached")))))
        repository.getRepos()
        assertEquals("cached", (repository.getRepos() as ApiResult.Success).data.items.single().name)
        assertEquals(2, server.requestCount)
        for (filters in listOf(RepoFilters(query = "hit"), RepoFilters(isArchived = false), RepoFilters(sort = RepoSort.NAME_ASC))) {
            repeat(2) {
                server.enqueue(MockResponse().setBody(response()))
                repository.getRepos(filters = filters)
            }
        }
        assertEquals(8, server.requestCount)
        enqueueOwner()
        server.enqueue(MockResponse().setBody(response(listOf(node("fresh")))))
        repository.getRepos(forceRefresh = true)
        assertEquals("fresh", (repository.getRepos() as ApiResult.Success).data.items.single().name)
        assertEquals(10, server.requestCount)
    }

    @Test
    fun `失效后的迟到响应不能重新写入缓存`() = runTest(accounts.context) { assertLateCacheWriteIgnored(false) }

    @Test
    fun `强制刷新后的迟到响应不能覆盖新缓存`() = runTest(accounts.context) { assertLateCacheWriteIgnored(true) }

    private suspend fun assertLateCacheWriteIgnored(forceRefresh: Boolean) = coroutineScope {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val searches = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val operation = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["operationName"]?.jsonPrimitive?.content
                if (operation == "ViewerRepos") return MockResponse().setBody(ownerResponse())
                if (searches.incrementAndGet() == 1) {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return MockResponse().setBody(response(listOf(node("old"))))
                }
                return MockResponse().setBody(response(listOf(node("new"))))
            }
        }
        val old = async(Dispatchers.IO) { repository.getRepos() }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
            if (forceRefresh) repository.getRepos(forceRefresh = true) else repository.invalidateAllCaches()
        } finally {
            release.countDown()
        }
        assertEquals("old", (old.await() as ApiResult.Success).data.items.single().name)
        assertEquals("new", (repository.getRepos() as ApiResult.Success).data.items.single().name)
        assertEquals(2, searches.get())
    }

    private fun takeRequest(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    private fun takeVariables(): JsonObject = takeRequest().getValue("variables").jsonObject

    private fun enqueueOwner(owner: String? = null, organization: Boolean = false, canCreate: Boolean = true) {
        server.enqueue(MockResponse().setBody(ownerResponse(owner, organization, canCreate)))
    }

    private fun ownerResponse(owner: String? = null, organization: Boolean = false, canCreate: Boolean = true): String {
        val root = if (owner == null) "viewer" else "repositoryOwner"
        val type = if (organization) "Organization" else "User"
        val permission = if (organization) "\"viewerCanCreateRepositories\":$canCreate" else "\"isViewer\":$canCreate"
        return """{"data":{"$root":{"__typename":"$type","id":"id-${owner ?: "me"}","login":"${owner ?: "me"}","name":"Owner","avatarUrl":"https://example.test/avatar",$permission}}}"""
    }

    private fun node(name: String, description: String? = null, visibility: String = "PUBLIC") = """
        {"__typename":"Repository","name":${JsonPrimitive(name)},"owner":{"__typename":"User","login":"me","avatarUrl":"https://example.test/me"},
        "description":${description?.let(::JsonPrimitive) ?: JsonNull},"homepageUrl":null,"visibility":"$visibility",
        "isArchived":false,"isFork":false,"hasIssuesEnabled":true,
        "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z","pushedAt":null,
        "parent":null,"primaryLanguage":null,"stargazerCount":0,"forkCount":0,"issues":{"totalCount":0},
        "repositoryTopics":{"nodes":[]},"defaultBranchRef":null}
    """.trimIndent()

    private fun response(nodes: List<String> = emptyList(), cursor: String? = null,
        hasNext: Boolean = cursor != null, total: Int = nodes.size): String = """
        {"data":{"search":{"repositoryCount":$total,"nodes":[${nodes.joinToString(",")}],
        "pageInfo":{"hasNextPage":$hasNext,"endCursor":${cursor?.let(::JsonPrimitive) ?: JsonNull}}}}}
    """.trimIndent()
}
