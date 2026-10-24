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
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RepoRepositoryTest {
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
    fun `解析仓库列表包含fork来源和topics`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "viewer": {
                      "id": "u1",
                      "login": "cmliu",
                      "name": "Cmliu",
                      "avatarUrl": "https://avatars.example.com/cmliu",
                      "repositories": {
                        "totalCount": 1,
                        "nodes": [
                          {
                            "name": "edgetunnel",
                            "owner": { "__typename": "User", "login": "cmliu", "avatarUrl": "https://avatars.example.com/cmliu" },
                            "description": "desc",
                            "homepageUrl": null,
                            "visibility": "PRIVATE",
                            "isArchived": false,
                            "isFork": true,
                            "hasIssuesEnabled": true,
                            "createdAt": "2026-01-01T00:00:00Z",
                            "updatedAt": "2026-02-01T00:00:00Z",
                            "pushedAt": null,
                            "parent": { "name": "edgetunnel", "owner": { "__typename": "User", "login": "zizifn", "avatarUrl": "https://avatars.example.com/zizifn" } },
                            "primaryLanguage": { "name": "JavaScript", "color": "#f1e05a" },
                            "stargazerCount": 40624,
                            "forkCount": 100,
                            "issues": { "totalCount": 2 },
                            "repositoryTopics": { "nodes": [ { "topic": { "name": "cloudflare" } } ] },
                            "defaultBranchRef": { "name": "main" }
                          }
                        ],
                        "pageInfo": { "hasNextPage": false, "endCursor": null }
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = repository.getRepos() // login=null → viewer.repositories（和原来 getViewerRepos 行为一致）

        assertTrue(result is ApiResult.Success<RepoList>)
        val list = (result as ApiResult.Success<RepoList>).data
        assertEquals(1, list.totalCount)
        val item = list.items.first()
        assertEquals("edgetunnel", item.name)
        assertEquals("desc", item.description)
        assertEquals("cmliu", item.ownerLogin)
        assertTrue(item.isFork)
        assertEquals("zizifn", item.forkedFromOwner)
        assertEquals(listOf("cloudflare"), item.topics)
        assertEquals("main", item.defaultBranchName)
        assertEquals(40624, item.stargazerCount)
        assertEquals(RepoVisibility.PRIVATE, item.visibility)
        assertTrue(item.hasIssuesEnabled)
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), item.updatedAt)
        assertEquals(null, item.pushedAt)
    }

    @Test
    fun `创建个人仓库使用user repos并显式发送false默认值`() = runTest {
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
    fun `创建组织仓库使用组织repos并映射owner`() = runTest {
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
    fun `license和gitignore模板路径正确映射`() = runTest {
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
    fun `创建仓库所有者查询使用PageSize并传递下一页cursor`() = runTest {
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
    fun `组织仓库列表返回真实创建权限上下文`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"repositoryOwner":{"__typename":"Organization","id":"o1","login":"acme","name":"Acme","avatarUrl":"https://avatars.example.com/acme","viewerCanCreateRepositories":true,"repositories":{"totalCount":0,"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
                """.trimIndent(),
            ),
        )

        val result = repository.getRepos(login = "acme")

        val owner = (result as ApiResult.Success).data.ownerContext?.owner
        assertEquals("acme", owner?.login)
        assertEquals(RepositoryCreateOwnerType.ORGANIZATION, owner?.type)
        assertTrue(owner?.canCreateRepository == true)
    }

    @Test
    fun `默认查询省略四维中性条件并限定所有者和PageSize`() = runTest {
        server.enqueue(MockResponse().setBody(response()))
        repository.getRepos()

        val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val variables = request.getValue("variables").jsonObject
        listOf("visibility", "isArchived", "isFork", "hasIssuesEnabled", "after").forEach {
            assertFalse(variables.containsKey(it))
        }
        assertEquals(JsonPrimitive(PageSize.REPOS), variables["first"])
        assertEquals(JsonPrimitive(PageSize.TOPICS_PER_REPO), variables["topicsFirst"])
        val query = request.getValue("query").jsonPrimitive.content.replace(Regex("\\s+"), "")
        assertTrue(query.contains("ownerAffiliations:[OWNER]"))
    }

    @Test
    fun `viewer用户和组织发送四维交集且false不丢失`() = runTest {
        for (login in listOf(null, "me", "acme")) {
            server.enqueue(MockResponse().setBody(response(owner = login, organization = login == "acme")))
            val filters = RepoFilters(visibility = RepoVisibility.INTERNAL, isArchived = false, isFork = false, hasIssuesEnabled = false)
            val result = repository.getRepos(login = login, filters = filters) as ApiResult.Success
            val variables = takeVariables()
            assertEquals(JsonPrimitive("INTERNAL"), variables["visibility"])
            listOf("isArchived", "isFork", "hasIssuesEnabled").forEach { assertEquals(JsonPrimitive(false), variables[it]) }
            assertEquals(login?.let(::JsonPrimitive), variables["login"])
            assertEquals(JsonPrimitive(PageSize.REPOS), variables["first"])
            assertEquals(JsonPrimitive(PageSize.TOPICS_PER_REPO), variables["topicsFirst"])
            assertEquals(login ?: "me", result.data.ownerContext?.owner?.login)
            assertEquals(login != "acme", result.data.ownerContext?.owner?.canCreateRepository)
        }
    }

    @Test
    fun `十种主排序映射到真实字段及方向`() = runTest {
        val expected = listOf(
            RepoSort.UPDATED_DESC to ("UPDATED_AT" to "DESC"),
            RepoSort.CREATED_ASC to ("CREATED_AT" to "ASC"),
            RepoSort.CREATED_DESC to ("CREATED_AT" to "DESC"),
            RepoSort.UPDATED_ASC to ("UPDATED_AT" to "ASC"),
            RepoSort.PUSHED_ASC to ("PUSHED_AT" to "ASC"),
            RepoSort.PUSHED_DESC to ("PUSHED_AT" to "DESC"),
            RepoSort.STARS_ASC to ("STARGAZERS" to "ASC"),
            RepoSort.STARS_DESC to ("STARGAZERS" to "DESC"),
            RepoSort.NAME_DESC to ("NAME" to "DESC"),
            RepoSort.NAME_ASC to ("NAME" to "ASC"),
        )
        for ((sort, order) in expected) {
            server.enqueue(MockResponse().setBody(response()))
            repository.getRepos(filters = RepoFilters(sort = sort))
            val variables = takeVariables().getValue("orderBy").jsonObject
            assertEquals(JsonPrimitive(order.first), variables["field"])
            assertEquals(JsonPrimitive(order.second), variables["direction"])
        }
    }

    @Test
    fun `可见性精确映射且零议题仍可开启功能`() = runTest {
        server.enqueue(MockResponse().setBody(response(RepoVisibility.entries.map { node(it.name, visibility = it.name) })))
        val repos = (repository.getRepos() as ApiResult.Success).data.items
        assertEquals(RepoVisibility.entries, repos.map { it.visibility })
        assertTrue(repos.all { it.hasIssuesEnabled && it.openIssueCount == 0 })
    }

    @Test
    fun `未知可见性缺失必需字段和非法日期均失败`() = runTest {
        for (invalid in listOf(
            node("repo", visibility = "FUTURE"),
            node("repo").replace("\"createdAt\":\"2026-01-01T00:00:00Z\"", "\"createdAt\":\"invalid\""),
            node("repo").replace("\"hasIssuesEnabled\":true,", ""),
            node("repo").replace("\"updatedAt\":\"2026-02-01T00:00:00Z\"", "\"updatedAt\":null"),
        )) {
            server.enqueue(MockResponse().setBody(response(listOf(invalid))))
            assertTrue(repository.getRepos() is ApiResult.Failure)
        }
    }

    @Test
    fun `搜索匹配名称或简介且不解释搜索语法`() = runTest {
        server.enqueue(MockResponse().setBody(response(listOf(
            node("Needle-repo"), node("description-match", "Some NEEDLE text"), node("unrelated"),
        ))))
        val result = (repository.getRepos(filters = RepoFilters(query = "  nEEdle  ")) as ApiResult.Success).data
        assertEquals(listOf("Needle-repo", "description-match"), result.items.map { it.name })
        assertEquals(1, server.requestCount)
        assertFalse(result.hasNextPage)

        server.enqueue(MockResponse().setBody(response(listOf(node("literal", "is:fork a.*b"), node("not-regex", "aXXb")))))
        val literal = repository.getRepos(filters = RepoFilters(query = "a.*b")) as ApiResult.Success
        assertEquals("literal", literal.data.items.single().name)
    }

    @Test
    fun `累计命中达到目标时完整处理最后一页`() = runTest {
        server.enqueue(MockResponse().setBody(response(
            (1 until PageSize.REPOS).map { node("hit-$it") } + node("other"), cursor = "c1", total = PageSize.REPOS * 4,
        )))
        server.enqueue(MockResponse().setBody(response((PageSize.REPOS until PageSize.REPOS * 2).map { node("hit-$it") }, cursor = "c2", total = PageSize.REPOS * 4)))

        val batch = (repository.getRepos(filters = RepoFilters(query = "hit")) as ApiResult.Success).data

        assertEquals(PageSize.REPOS * 2 - 1, batch.items.size)
        assertEquals("hit-${PageSize.REPOS * 2 - 1}", batch.items.last().name)
        assertEquals("c2", batch.endCursor)
        assertFalse(batch.requiresManualContinue)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `空搜索批次达到预算后从完整末页游标继续`() = runTest {
        val budget = PageSize.REPO_SEARCH_SCAN_PAGE_LIMIT
        val total = PageSize.REPOS * budget + 1
        repeat(budget) { page ->
            server.enqueue(MockResponse().setBody(response(
                (1..PageSize.REPOS).map { node("other-$page-$it") }, cursor = "c${page + 1}", total = total,
            )))
        }
        val filters = RepoFilters(query = "target")
        val first = (repository.getRepos(filters = filters) as ApiResult.Success).data
        assertTrue(first.items.isEmpty())
        assertEquals("c$budget", first.endCursor)
        assertTrue(first.requiresManualContinue)
        assertEquals(budget, server.requestCount)

        server.enqueue(MockResponse().setBody(response(listOf(node("TaRgEt")), total = total)))
        val last = (repository.getRepos(after = first.endCursor, filters = filters) as ApiResult.Success).data
        assertEquals("TaRgEt", last.items.single().name)
        assertFalse(last.hasNextPage)
        assertFalse(last.requiresManualContinue)
        assertEquals(budget + 1, server.requestCount)
        val requests = List(budget + 1) { takeVariables() }
        assertEquals(listOf(null) + (1..budget).map { "c$it" }, requests.map { it["after"]?.jsonPrimitive?.content })
        requests.forEach {
            assertEquals(JsonPrimitive(PageSize.REPOS), it["first"])
            assertEquals(JsonPrimitive(PageSize.TOPICS_PER_REPO), it["topicsFirst"])
        }
    }

    @Test
    fun `预算耗尽保留非空命中并要求手动继续`() = runTest {
        repeat(PageSize.REPO_SEARCH_SCAN_PAGE_LIMIT) { page ->
            server.enqueue(MockResponse().setBody(response(listOf(node("hit-$page")), cursor = "c$page")))
        }
        val batch = (repository.getRepos(filters = RepoFilters(query = "hit")) as ApiResult.Success).data
        assertEquals(PageSize.REPO_SEARCH_SCAN_PAGE_LIMIT, batch.items.size)
        assertTrue(batch.requiresManualContinue)
        assertEquals("c${PageSize.REPO_SEARCH_SCAN_PAGE_LIMIT - 1}", batch.endCursor)
        assertEquals(PageSize.REPO_SEARCH_SCAN_PAGE_LIMIT, server.requestCount)
    }

    @Test
    fun `预算前一页收满目标不要求手动继续`() = runTest {
        val pages = PageSize.REPO_SEARCH_SCAN_PAGE_LIMIT - 1
        repeat(pages) { page ->
            val nodes = if (page == pages - 1) (1..PageSize.REPOS).map { node("hit-$it") } else emptyList()
            server.enqueue(MockResponse().setBody(response(nodes, cursor = "c${page + 1}")))
        }

        val batch = (repository.getRepos(filters = RepoFilters(query = "hit")) as ApiResult.Success).data

        assertEquals(PageSize.REPOS, batch.items.size)
        assertTrue(batch.hasNextPage)
        assertFalse(batch.requiresManualContinue)
        assertEquals("c$pages", batch.endCursor)
        assertEquals(pages, server.requestCount)
    }

    @Test
    fun `无搜索只取一页且空页暂停自动翻页`() = runTest {
        server.enqueue(MockResponse().setBody(response(cursor = "next", total = PageSize.REPOS)))
        val batch = (repository.getRepos() as ApiResult.Success).data
        assertEquals(1, server.requestCount)
        assertTrue(batch.requiresManualContinue)
        assertTrue(batch.items.isEmpty())
        assertEquals("next", batch.endCursor)
    }

    @Test
    fun `批内按忽略大小写的地址去重并保留最后的数据`() = runTest {
        server.enqueue(MockResponse().setBody(response(listOf(node("hit")), cursor = "next")))
        server.enqueue(MockResponse().setBody(response(listOf(node("HIT", "new description"), node("hit-two")))))
        val batch = (repository.getRepos(filters = RepoFilters(query = "hit")) as ApiResult.Success).data
        assertEquals(2, batch.items.size)
        assertEquals("new description", batch.items.first().description)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `缺失空白停滞及批内循环游标返回失败`() = runTest {
        for ((after, cursor) in listOf(null to null, null to " ", "same" to "same")) {
            server.enqueue(MockResponse().setBody(response(cursor = cursor, hasNext = true)))
            assertTrue(repository.getRepos(after = after, filters = RepoFilters(query = "hit")) is ApiResult.Failure)
        }
        listOf("c1", "c2", "c1").forEach {
            server.enqueue(MockResponse().setBody(response(cursor = it)))
        }
        assertTrue(repository.getRepos(filters = RepoFilters(query = "hit")) is ApiResult.Failure)
    }

    @Test
    fun `扫描中主体改变时不提交混合账号结果`() = runTest {
        server.enqueue(MockResponse().setBody(response(listOf(node("hit")), cursor = "next")))
        server.enqueue(MockResponse().setBody(response(listOf(node("hit-two")), id = "another-account")))
        assertTrue(repository.getRepos(filters = RepoFilters(query = "hit")) is ApiResult.Failure)
    }

    @Test
    fun `批内失败原子丢弃临时结果并从原游标重试`() = runTest {
        val first = response(listOf(node("hit-one")), cursor = "middle")
        server.enqueue(MockResponse().setBody(first))
        server.enqueue(MockResponse().setResponseCode(403))
        val filters = RepoFilters(query = "hit")
        assertEquals(ApiResult.Failure(ApiError.Forbidden), repository.getRepos(after = "start", filters = filters))

        server.enqueue(MockResponse().setBody(first))
        server.enqueue(MockResponse().setBody(response(listOf(node("hit-two")))))
        val retried = (repository.getRepos(after = "start", filters = filters) as ApiResult.Success).data
        assertEquals(listOf("hit-one", "hit-two"), retried.items.map { it.name })
        assertEquals(4, server.requestCount)
        assertEquals(listOf("start", "middle", "start", "middle"), List(4) { takeVariables().getValue("after").jsonPrimitive.content })
    }

    @Test
    fun `仅viewer默认首页缓存且强制刷新替换缓存`() = runTest {
        server.enqueue(MockResponse().setBody(response(listOf(node("cached")))))
        repository.getRepos()
        assertEquals("cached", (repository.getRepos() as ApiResult.Success).data.items.single().name)
        assertEquals(1, server.requestCount)

        for (filters in listOf(RepoFilters(query = "hit"), RepoFilters(isArchived = false), RepoFilters(sort = RepoSort.NAME_ASC))) {
            repeat(2) {
                server.enqueue(MockResponse().setBody(response()))
                repository.getRepos(filters = filters)
            }
        }
        repeat(2) {
            server.enqueue(MockResponse().setBody(response(owner = "other")))
            repository.getRepos(login = "other")
            server.enqueue(MockResponse().setBody(response()))
            repository.getRepos(after = "next")
        }
        assertEquals(11, server.requestCount)
        server.enqueue(MockResponse().setBody(response(listOf(node("fresh")))))
        repository.getRepos(forceRefresh = true)
        assertEquals("fresh", (repository.getRepos() as ApiResult.Success).data.items.single().name)
        assertEquals(12, server.requestCount)
    }

    @Test
    fun `失效后的迟到响应不能重新写入缓存`() = runTest { assertLateCacheWriteIgnored(forceRefresh = false) }

    @Test
    fun `强制刷新后的迟到响应不能覆盖新缓存`() = runTest { assertLateCacheWriteIgnored(forceRefresh = true) }

    private suspend fun assertLateCacheWriteIgnored(forceRefresh: Boolean) = coroutineScope {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (requests.incrementAndGet() == 1) {
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
        assertEquals(2, server.requestCount)
    }

    private fun takeVariables(): JsonObject =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject

    private fun node(name: String, description: String? = null, visibility: String = "PUBLIC") = """
        {"name":${JsonPrimitive(name)},"owner":{"__typename":"User","login":"me","avatarUrl":"https://example.test/me"},
        "description":${description?.let(::JsonPrimitive) ?: JsonNull},"homepageUrl":null,"visibility":"$visibility",
        "isArchived":false,"isFork":false,"hasIssuesEnabled":true,
        "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-02-01T00:00:00Z","pushedAt":null,
        "parent":null,"primaryLanguage":null,"stargazerCount":0,"forkCount":0,"issues":{"totalCount":0},
        "repositoryTopics":{"nodes":[]},"defaultBranchRef":null}
    """.trimIndent()

    private fun response(
        nodes: List<String> = emptyList(),
        cursor: String? = null,
        hasNext: Boolean = cursor != null,
        total: Int = nodes.size,
        owner: String? = null,
        organization: Boolean = false,
        id: String = "u1",
    ): String {
        val root = if (owner == null) "viewer" else "repositoryOwner"
        val type = if (organization) "Organization" else "User"
        val permissions = if (organization) "\"viewerCanCreateRepositories\":false" else "\"isViewer\":${owner == null || owner == "me"}"
        return """
            {"data":{"$root":{"__typename":"$type","id":"$id","login":"${owner ?: "me"}","name":"Owner",
            "avatarUrl":"https://example.test/avatar",$permissions,"repositories":{
            "totalCount":$total,"nodes":[${nodes.joinToString(",")}],
            "pageInfo":{"hasNextPage":$hasNext,"endCursor":${cursor?.let(::JsonPrimitive) ?: JsonNull}}}}}}
        """.trimIndent()
    }
}
