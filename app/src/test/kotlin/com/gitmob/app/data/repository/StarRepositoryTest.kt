package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.testutil.AccountSessionRule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import org.junit.Rule

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
 * 用 MockWebServer 模拟真实 HTTP 响应。
 * 注意：Apollo 对非空字段与接口/联合类型有严格校验——owner、parent.owner 是
 * RepositoryOwner 接口，items 内嵌 ... on Repository，都必须带 __typename；
 * avatarUrl 在 schema 中是非空 String!，mock 不能给 null。
 */
class StarRepositoryTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var repository: StarRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        repository = StarRepository(buildApolloClient(baseUrl))
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `getLists解析列表摘要`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"lists":{"nodes":[
                    {"id":"L1","name":"博客","slug":"blog","description":null,"isPrivate":false,"items":{"totalCount":1}},
                    {"id":"L2","name":"APP","slug":"app","description":null,"isPrivate":false,"items":{"totalCount":2}}
                ]}}}}
                """.trimIndent(),
            ),
        )

        val result = repository.getLists()

        assertTrue(result is ApiResult.Success)
        val lists = (result as ApiResult.Success).data
        assertEquals(2, lists.size)
        assertEquals("博客", lists[0].name)
        assertEquals(1, lists[0].itemCount)
        assertEquals(JsonPrimitive(PageSize.STAR_LISTS), takeVariables()["first"])
        assertEquals(result, repository.getLists())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `getListsContaining通过反向索引算出仓库所属列表`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"lists":{"nodes":[
                    {"id":"L1","items":{"nodes":[
                        {"__typename":"Repository","id":"R1"},
                        {"__typename":"Repository","id":"R2"}
                    ],"pageInfo":{"hasNextPage":false,"endCursor":null}}},
                    {"id":"L2","items":{"nodes":[{"__typename":"Repository","id":"R3"}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}
                ]}}}}
                """.trimIndent(),
            ),
        )

        val result = repository.getListsContaining("R1")

        assertTrue(result is ApiResult.Success)
        val containingLists = (result as ApiResult.Success).data
        assertEquals(setOf("L1"), containingLists)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.STAR_LISTS), variables["listsFirst"])
        assertEquals(JsonPrimitive(PageSize.LIST_ITEMS_SCAN_LIMIT), variables["itemsFirst"])
    }

    @Test
    fun `分组摘要和归属查询都覆盖第32个分组`() = runTest(accounts.context) {
        val lists = (1..32).joinToString(",") { index ->
            """{"id":"L$index","name":"List $index","slug":"list-$index","description":null,"isPrivate":false,"items":{"totalCount":1}}"""
        }
        server.enqueue(MockResponse().setBody("""{"data":{"viewer":{"lists":{"nodes":[$lists]}}}}"""))
        val summaries = repository.getLists() as ApiResult.Success
        assertEquals(32, summaries.data.size)
        assertEquals("L32", summaries.data.last().id)
        assertEquals(JsonPrimitive(32), takeVariables()["first"])

        enqueueMembershipLists(*(1..32).map { index ->
            "L$index" to membershipItems(if (index == 32) listOf("target") else emptyList())
        }.toTypedArray())
        assertEquals(ApiResult.Success(setOf("L32")), repository.getListsContaining("target"))
        assertEquals(JsonPrimitive(32), takeVariables()["listsFirst"])
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `归属扫描跨过100项和空页并在找到目标后停止该分组`() = runTest(accounts.context) {
        enqueueMembershipLists(
            "L1" to membershipItems(List(PageSize.LIST_ITEMS_SCAN_LIMIT) { "R$it" }, true, "page-1"),
            "L2" to membershipItems(listOf("target"), true, "unused"),
            "L3" to membershipItems(emptyList()),
        )
        enqueueMembershipPage(membershipItems(emptyList(), true, "page-2"))
        enqueueMembershipPage(membershipItems(listOf("target"), true, "unused"))

        assertEquals(ApiResult.Success(setOf("L1", "L2")), repository.getListsContaining("target"))
        val first = takeVariables()
        assertEquals(JsonPrimitive(PageSize.STAR_LISTS), first["listsFirst"])
        assertEquals(JsonPrimitive(PageSize.LIST_ITEMS_SCAN_LIMIT), first["itemsFirst"])
        for (cursor in listOf("page-1", "page-2")) {
            val next = takeVariables()
            assertEquals(JsonPrimitive("L1"), next["listId"])
            assertEquals(JsonPrimitive(cursor), next["after"])
            assertEquals(JsonPrimitive(PageSize.LIST_ITEMS_SCAN_LIMIT), next["first"])
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `完整扫描未找到目标才返回未归属`() = runTest(accounts.context) {
        enqueueMembershipLists("L1" to membershipItems(listOf("other"), true, "next"))
        enqueueMembershipPage(membershipItems(listOf("another")))

        assertEquals(ApiResult.Success(emptySet<String>()), repository.getListsContaining("target"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `续页失败不返回部分归属并允许重新完整查询`() = runTest(accounts.context) {
        val lists = arrayOf(
            "L1" to membershipItems(listOf("target")),
            "L2" to membershipItems(emptyList(), true, "next"),
        )
        enqueueMembershipLists(*lists)
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ApiResult.Failure(ApiError.Forbidden), repository.getListsContaining("target"))

        enqueueMembershipLists(*lists)
        enqueueMembershipPage(membershipItems(listOf("target")))
        assertEquals(ApiResult.Success(setOf("L1", "L2")), repository.getListsContaining("target"))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `续页分组消失不伪造成未归属`() = runTest(accounts.context) {
        enqueueMembershipLists("L1" to membershipItems(emptyList(), true, "next"))
        server.enqueue(MockResponse().setBody("""{"data":{"node":null}}"""))

        assertEquals(
            ApiResult.Failure(ApiError.UserVisible(com.gitmob.app.R.string.error_list_not_found)),
            repository.getListsContaining("target"),
        )
    }

    @Test
    fun `缺失或未推进的归属游标终止查询且不返回部分结果`() = runTest(accounts.context) {
        enqueueMembershipLists("L1" to membershipItems(emptyList(), true))
        assertTrue(repository.getListsContaining("target") is ApiResult.Failure)
        assertEquals(1, server.requestCount)

        enqueueMembershipLists("L1" to membershipItems(emptyList(), true, "same"))
        enqueueMembershipPage(membershipItems(emptyList(), true, "same"))
        assertTrue(repository.getListsContaining("target") is ApiResult.Failure)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `getViewerStarred解析星标仓库分页数据`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"starredRepositories":{
                    "totalCount":1,
                    "edges":[{"node":{
                        "__typename":"Repository",
                        "id":"R1","name":"edgetunnel","url":"https://github.com/cmliu/edgetunnel",
                        "description":"desc","homepageUrl":null,
                        "owner":{"__typename":"User","login":"cmliu","avatarUrl":"https://a/1.png"},
                        "isPrivate":false,"isArchived":false,"isFork":true,
                        "parent":{"name":"origin","owner":{"__typename":"User","login":"upstream","avatarUrl":"https://a/2.png"}},
                        "primaryLanguage":{"name":"JavaScript","color":"#f1e05a"},
                        "stargazerCount":40624,"forkCount":100,
                        "issues":{"totalCount":1},
                        "repositoryTopics":{"nodes":[]},
                        "defaultBranchRef":{"name":"main"}
                    }}],
                    "pageInfo":{"hasNextPage":false,"endCursor":null}
                }}}}
                """.trimIndent(),
            ),
        )

        val result = repository.getViewerStarred()

        assertTrue(result is ApiResult.Success)
        val page = (result as ApiResult.Success).data
        assertEquals(1, page.totalCount)
        val repo = page.items.first()
        assertEquals("desc", repo.description)
        assertEquals("cmliu", repo.ownerLogin)
        assertTrue(repo.isFork)
        assertEquals("upstream", repo.forkedFromOwner)
        assertEquals("origin", repo.forkedFromName)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.STARRED_REPOS), variables["first"])
        assertEquals(JsonPrimitive(PageSize.TOPICS_PER_REPO), variables["topicsFirst"])
        assertFalse("after" in variables)
        assertEquals(result, repository.getViewerStarred())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `viewer和user星标续页分别传入容量和游标`() = runTest(accounts.context) {
        for (login in listOf(null, "octocat")) {
            val root = if (login == null) "viewer" else "user"
            server.enqueue(MockResponse().setBody("""{"data":{"$root":{"starredRepositories":{"totalCount":0,"edges":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}"""))

            val result = if (login == null) repository.getViewerStarred("previous") else repository.getUserStarred(login, "previous")

            assertTrue(result is ApiResult.Success)
            assertTrue((result as ApiResult.Success).data.items.isEmpty())
            val variables = takeVariables()
            assertEquals(JsonPrimitive(PageSize.STARRED_REPOS), variables["first"])
            assertEquals(JsonPrimitive(PageSize.TOPICS_PER_REPO), variables["topicsFirst"])
            assertEquals(JsonPrimitive("previous"), variables["after"])
            assertEquals(login?.let(::JsonPrimitive), variables["login"])
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `清单项目使用独立分页容量和共享话题容量`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"data":{"node":{"__typename":"UserList","items":{"totalCount":0,"nodes":[],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}"""))

        val result = repository.getListItems("L1", "previous") as ApiResult.Success

        assertTrue(result.data.items.isEmpty())
        assertTrue(result.data.hasNextPage)
        assertEquals("next", result.data.endCursor)
        val variables = takeVariables()
        assertEquals(JsonPrimitive("L1"), variables["listId"])
        assertEquals(JsonPrimitive(PageSize.LIST_ITEMS), variables["first"])
        assertEquals(JsonPrimitive(PageSize.TOPICS_PER_REPO), variables["topicsFirst"])
        assertEquals(JsonPrimitive("previous"), variables["after"])
    }

    @Test
    fun `缺失根节点和权限失败不伪造成空星标或清单`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"data":{"user":null}}"""))
        assertTrue(repository.getUserStarred("missing") is ApiResult.Failure)
        server.enqueue(MockResponse().setBody("""{"data":{"node":null}}"""))
        assertTrue(repository.getListItems("missing") is ApiResult.Failure)
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ApiResult.Failure(ApiError.Forbidden), repository.getViewerStarred())
    }

    @Test
    fun `updateList解析更新后的列表`() = runTest(accounts.context) {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"updateUserList":{"list":
                    {"id":"L1","name":"新名字","slug":"blog","description":"desc","isPrivate":true,"items":{"totalCount":1}}
                }}}
                """.trimIndent(),
            ),
        )

        val result = repository.updateList("L1", "新名字", "desc", true)

        assertTrue(result is ApiResult.Success)
        assertEquals("新名字", (result as ApiResult.Success).data.name)
    }

    @Test
    fun `deleteList成功时返回Unit`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"data":{"deleteUserList":{"clientMutationId":null}}}"""))

        val result = repository.deleteList("L1")

        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `unstarRepo成功时返回Unit`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"data":{"removeStar":{"clientMutationId":null}}}"""))

        val result = repository.unstarRepo("R1")

        assertTrue(result is ApiResult.Success)
    }

    private fun membershipItems(
        repoIds: List<String>,
        hasNextPage: Boolean = false,
        endCursor: String? = null,
    ) = buildJsonObject {
        put("nodes", JsonArray(repoIds.map { id ->
            buildJsonObject {
                put("__typename", "Repository")
                put("id", id)
            }
        }))
        put("pageInfo", buildJsonObject {
            put("hasNextPage", hasNextPage)
            put("endCursor", endCursor)
        })
    }

    private fun enqueueMembershipLists(vararg lists: Pair<String, JsonObject>) {
        val nodes = JsonArray(lists.map { (id, items) ->
            buildJsonObject {
                put("id", id)
                put("items", items)
            }
        })
        server.enqueue(MockResponse().setBody("""{"data":{"viewer":{"lists":{"nodes":$nodes}}}}"""))
    }

    private fun enqueueMembershipPage(items: JsonObject) {
        server.enqueue(MockResponse().setBody("""{"data":{"node":{"__typename":"UserList","items":$items}}}"""))
    }

    private fun takeVariables() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
}
