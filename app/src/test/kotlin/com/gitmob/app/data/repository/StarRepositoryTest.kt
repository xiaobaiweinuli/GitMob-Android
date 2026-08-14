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

class StarRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: StarRepository

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
        repository = StarRepository(api)
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
    }

    @Test
    fun `getListsContaining通过反向索引算出仓库所属列表`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"lists":{"nodes":[
                    {"id":"L1","items":{"nodes":[{"id":"R1"},{"id":"R2"}]}},
                    {"id":"L2","items":{"nodes":[{"id":"R3"}]}}
                ]}}}}
                """.trimIndent(),
            ),
        )

        val result = repository.getListsContaining("R1")

        assertTrue(result is ApiResult.Success)
        val containingLists = (result as ApiResult.Success).data
        assertEquals(setOf("L1"), containingLists)
    }

    @Test
    fun `getViewerStarred解析星标仓库分页数据`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":{"viewer":{"starredRepositories":{
                    "totalCount":1,
                    "edges":[{"node":{
                        "id":"R1","name":"edgetunnel","url":"https://github.com/cmliu/edgetunnel",
                        "description":"desc","homepageUrl":null,
                        "owner":{"login":"cmliu","avatarUrl":null},
                        "isPrivate":false,"isArchived":false,"isFork":true,
                        "parent":{"name":"origin","owner":{"login":"upstream","avatarUrl":null}},
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
}
