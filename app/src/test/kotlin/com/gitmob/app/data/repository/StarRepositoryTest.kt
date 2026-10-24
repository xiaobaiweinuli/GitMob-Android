package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
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
    fun `getLists解析列表摘要`() = runTest {
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
    fun `getListsContaining通过反向索引算出仓库所属列表`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"lists":{"nodes":[
                    {"id":"L1","items":{"nodes":[
                        {"__typename":"Repository","id":"R1"},
                        {"__typename":"Repository","id":"R2"}
                    ]}},
                    {"id":"L2","items":{"nodes":[{"__typename":"Repository","id":"R3"}]}}
                ]}}}}
                """.trimIndent(),
            ),
        )

        val result = repository.getListsContaining("R1")

        assertTrue(result is ApiResult.Success)
        val containingLists = (result as ApiResult.Success).data
        assertEquals(setOf("L1"), containingLists)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.STAR_LISTS_SCAN_LIMIT), variables["listsFirst"])
        assertEquals(JsonPrimitive(PageSize.LIST_ITEMS_SCAN_LIMIT), variables["itemsFirst"])
    }

    @Test
    fun `getViewerStarred解析星标仓库分页数据`() = runTest {
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
    fun `viewer和user星标续页分别传入容量和游标`() = runTest {
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
    fun `清单项目使用独立分页容量和共享话题容量`() = runTest {
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
    fun `缺失根节点和权限失败不伪造成空星标或清单`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"user":null}}"""))
        assertTrue(repository.getUserStarred("missing") is ApiResult.Failure)
        server.enqueue(MockResponse().setBody("""{"data":{"node":null}}"""))
        assertTrue(repository.getListItems("missing") is ApiResult.Failure)
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ApiResult.Failure(ApiError.Forbidden), repository.getViewerStarred())
    }

    @Test
    fun `updateList解析更新后的列表`() = runTest {
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
    fun `deleteList成功时返回Unit`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"deleteUserList":{"clientMutationId":null}}}"""))

        val result = repository.deleteList("L1")

        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `unstarRepo成功时返回Unit`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":{"removeStar":{"clientMutationId":null}}}"""))

        val result = repository.unstarRepo("R1")

        assertTrue(result is ApiResult.Success)
    }

    private fun takeVariables() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
}
