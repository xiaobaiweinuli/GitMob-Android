package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.auth.TokenStorage
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.GistPage
import com.gitmob.app.data.model.GistRevision
import com.gitmob.app.data.model.GistSort
import com.gitmob.app.data.model.GistVisibility
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GistRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var restServer: MockWebServer
    private lateinit var repository: GistRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        restServer = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val okHttpClient = OkHttpClient()
        val tokenProvider = object : AccessTokenProvider {
            override suspend fun getToken() = "fake-token"
        }
        val apolloClient = ApolloClient.Builder()
            .serverUrl("$baseUrl/graphql")
            .okHttpClient(okHttpClient)
            .addHttpInterceptor(
                AuthHttpInterceptor(
                    tokenProvider,
                ),
            )
            .build()
        val api = GHApiClient(
            okHttpClient = okHttpClient,
            json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true },
            tokenProvider = tokenProvider,
            restBaseUrl = restServer.url("/").toString().removeSuffix("/"),
        )
        val tokenStorage = mockk<TokenStorage>()
        every { tokenStorage.activeLogin } returns flowOf("viewer")
        repository = GistRepository(api, apolloClient, tokenStorage)
    }

    @After
    fun teardown() {
        server.shutdown()
        restServer.shutdown()
    }

    @Test
    fun `原创列表过滤复刻并映射代码预览与秘密状态`() = runTest {
        enqueuePage(
            edges = listOf(
                edge("c1", gistNode("g1", isFork = true)),
                edge(
                    "c2",
                    gistNode(
                        id = "g2",
                        isFork = false,
                        isPublic = false,
                        description = "sample gist",
                        fileName = "Main.kt",
                        fileText = "fun main() = Unit",
                        fileCount = 2,
                    ),
                ),
            ),
            hasNextPage = false,
            endCursor = "c2",
        )

        val result = repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)

        assertTrue(result is ApiResult.Success<GistPage>)
        val page = (result as ApiResult.Success).data
        assertEquals(1, page.items.size)
        val gist = page.items.single()
        assertEquals("g2", gist.id)
        assertEquals("sample gist", gist.description)
        assertFalse(gist.isPublic)
        assertTrue(gist.isOwnedByViewer)
        assertEquals(7, gist.stargazerCount)
        assertEquals(3, gist.commentCount)
        assertEquals("Main.kt", gist.previewFile?.name)
        assertEquals("Kotlin", gist.previewFile?.languageName)
        assertEquals(2, gist.fileCount)
        assertFalse(gist.isFileCountCapped)
        assertFalse(page.hasNextPage)
        assertEquals(0, restServer.requestCount)
        val variables = takeVariables()
        assertEquals(JsonPrimitive(PageSize.GISTS), variables["first"])
        assertEquals(JsonPrimitive(PageSize.GIST_FILE_METADATA_LIMIT), variables["fileMetadataLimit"])
    }

    @Test
    fun `目标项不足时继续扫描下一底层页`() = runTest {
        enqueuePage(
            edges = listOf(edge("c1", gistNode("fork", isFork = true))),
            hasNextPage = true,
            endCursor = "c1",
        )
        enqueuePage(
            edges = listOf(edge("c2", gistNode("original", isFork = false))),
            hasNextPage = false,
            endCursor = "c2",
        )

        val result = repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)

        val page = (result as ApiResult.Success).data
        assertEquals(listOf("original"), page.items.map { it.id })
        assertEquals("c2", page.nextCursor)
        assertFalse(page.hasNextPage)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `收集满目标项时停在最后检查的edge并保留未检查数据`() = runTest {
        val edges = (1..PageSize.GISTS + 1).map { index ->
            edge("c$index", gistNode("g$index", isFork = false))
        }
        enqueuePage(edges = edges, hasNextPage = false, endCursor = "c${PageSize.GISTS + 1}")

        val result = repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)

        val page = (result as ApiResult.Success).data
        assertEquals(PageSize.GISTS, page.items.size)
        assertEquals("c${PageSize.GISTS}", page.nextCursor)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `达到扫描批次上限时允许空页但保留下一页`() = runTest {
        repeat(PageSize.GIST_SCAN_BATCH_LIMIT) { batch ->
            val cursor = "c${batch + 1}"
            enqueuePage(
                edges = listOf(edge(cursor, gistNode("fork-$batch", isFork = true))),
                hasNextPage = true,
                endCursor = cursor,
            )
        }

        val result = repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)

        val page = (result as ApiResult.Success).data
        assertTrue(page.items.isEmpty())
        assertTrue(page.hasNextPage)
        assertEquals("c${PageSize.GIST_SCAN_BATCH_LIMIT}", page.nextCursor)
        assertEquals(PageSize.GIST_SCAN_BATCH_LIMIT, server.requestCount)
    }

    @Test
    fun `四种排序只生成校验过的GraphQL枚举值`() = runTest {
        GistSort.entries.forEach {
            enqueuePage(edges = emptyList(), hasNextPage = false, endCursor = null)
            repository.getGistsFresh(category = GistCategory.ORIGINAL, sort = it)
        }

        val bodies = GistSort.entries.map { server.takeRequest().body.readUtf8() }
        assertTrue(bodies[0].contains("CREATED_AT"))
        assertTrue(bodies[0].contains("DESC"))
        assertTrue(bodies[1].contains("UPDATED_AT"))
        assertTrue(bodies[1].contains("DESC"))
        assertTrue(bodies[2].contains("CREATED_AT"))
        assertTrue(bodies[2].contains("ASC"))
        assertTrue(bodies[3].contains("UPDATED_AT"))
        assertTrue(bodies[3].contains("ASC"))
    }

    @Test
    fun `第一页命中缓存而fresh强制重新请求`() = runTest {
        enqueuePage(
            edges = listOf(edge("c1", gistNode("cached", isFork = false))),
            hasNextPage = false,
            endCursor = "c1",
        )
        enqueuePage(
            edges = listOf(edge("c2", gistNode("fresh", isFork = false))),
            hasNextPage = false,
            endCursor = "c2",
        )

        val first = repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)
        val cached = repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)
        val fresh = repository.getGistsFresh(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)

        assertEquals("cached", (first as ApiResult.Success).data.items.single().id)
        assertEquals("cached", (cached as ApiResult.Success).data.items.single().id)
        assertEquals("fresh", (fresh as ApiResult.Success).data.items.single().id)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `他人列表使用user根和PUBLIC且不会误判为viewer所有`() = runTest {
        enqueueUserPage(
            targetLogin = "octocat",
            edges = listOf(edge("c1", gistNode("public-gist", isFork = false, ownerLogin = "octocat"))),
            hasNextPage = false,
            endCursor = "c1",
        )

        val result = repository.getGists(
            login = "octocat",
            category = GistCategory.ORIGINAL,
            sort = GistSort.RECENTLY_UPDATED,
        )

        val item = (result as ApiResult.Success).data.items.single()
        assertFalse(item.isOwnedByViewer)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("user(login:"))
        assertTrue(body.contains("privacy: PUBLIC"))
        assertFalse(body.contains("privacy: ALL"))
        val variables = Json.parseToJsonElement(body).jsonObject.getValue("variables").jsonObject
        assertEquals(JsonPrimitive(PageSize.GISTS), variables["first"])
        assertEquals(JsonPrimitive(PageSize.GIST_FILE_METADATA_LIMIT), variables["fileMetadataLimit"])
    }

    @Test
    fun `viewer与不同用户的第一页缓存互不串用`() = runTest {
        enqueuePage(
            edges = listOf(edge("v1", gistNode("viewer-gist", isFork = false))),
            hasNextPage = false,
            endCursor = "v1",
        )
        enqueueUserPage(
            targetLogin = "alice",
            edges = listOf(edge("a1", gistNode("alice-gist", isFork = false, ownerLogin = "alice"))),
            hasNextPage = false,
            endCursor = "a1",
        )
        enqueueUserPage(
            targetLogin = "bob",
            edges = listOf(edge("b1", gistNode("bob-gist", isFork = false, ownerLogin = "bob"))),
            hasNextPage = false,
            endCursor = "b1",
        )

        repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)
        repository.getGists(login = "alice", category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)
        repository.getGists(login = "bob", category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)
        repository.getGists(login = "alice", category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)

        assertEquals(3, server.requestCount)
    }

    @Test
    fun `GraphQL权限错误映射到统一错误类型`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"errors":[{"message":"Resource not accessible","type":"FORBIDDEN"}]}""",
            ),
        )

        val result = repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)

        assertTrue(result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error
        assertTrue(error is ApiError.GraphQLError)
    }

    @Test
    fun `类别与可见性共同筛选且全部保留两种可见性`() = runTest {
        val cases = listOf(
            Triple(GistCategory.ALL, GistVisibility.ALL, listOf("original-public", "original-private", "fork-public", "fork-private")),
            Triple(GistCategory.ALL, GistVisibility.PUBLIC, listOf("original-public", "fork-public")),
            Triple(GistCategory.ALL, GistVisibility.PRIVATE, listOf("original-private", "fork-private")),
            Triple(GistCategory.ORIGINAL, GistVisibility.ALL, listOf("original-public", "original-private")),
            Triple(GistCategory.ORIGINAL, GistVisibility.PUBLIC, listOf("original-public")),
            Triple(GistCategory.ORIGINAL, GistVisibility.PRIVATE, listOf("original-private")),
            Triple(GistCategory.FORKED, GistVisibility.ALL, listOf("fork-public", "fork-private")),
            Triple(GistCategory.FORKED, GistVisibility.PUBLIC, listOf("fork-public")),
            Triple(GistCategory.FORKED, GistVisibility.PRIVATE, listOf("fork-private")),
        )
        for ((category, visibility, expectedIds) in cases) {
            enqueuePage(
                edges = listOf(
                    edge("c1", gistNode("original-public", isFork = false)),
                    edge("c2", gistNode("original-private", isFork = false, isPublic = false)),
                    edge("c3", gistNode("fork-public", isFork = true)),
                    edge("c4", gistNode("fork-private", isFork = true, isPublic = false)),
                ),
                hasNextPage = false,
                endCursor = "c4",
            )
            expectedIds.filter { it.startsWith("fork") }.forEach(::enqueueForkSource)
            val result = repository.getGists(category = category, sort = GistSort.RECENTLY_UPDATED, visibility = visibility)
            val page = (result as ApiResult.Success).data
            assertEquals(expectedIds, page.items.map { it.id })
            assertFalse(page.hasNextPage)
        }
        assertEquals(9, server.requestCount)
        assertEquals(8, restServer.requestCount)
    }

    @Test
    fun `复刻项通过REST补全来源`() = runTest {
        enqueuePage(
            edges = listOf(edge("c1", gistNode("fork", isFork = true))),
            hasNextPage = false,
            endCursor = "c1",
        )
        enqueueForkSource("fork", sourceId = "source-id", sourceOwner = "octocat")

        val page = (repository.getGists(
            category = GistCategory.FORKED,
            sort = GistSort.RECENTLY_UPDATED,
        ) as ApiResult.Success).data

        val gist = page.items.single()
        assertEquals("source-id", gist.sourceId)
        assertEquals("octocat", gist.sourceOwnerLogin)
        assertEquals("https://gist.github.com/octocat/source-id", gist.sourceUrl)
        assertEquals("/gists/fork", restServer.takeRequest().path)
    }

    @Test
    fun `来源REST失败不影响复刻列表`() = runTest {
        enqueuePage(
            edges = listOf(edge("c1", gistNode("fork", isFork = true))),
            hasNextPage = false,
            endCursor = "c1",
        )
        restServer.enqueue(MockResponse().setResponseCode(500))

        val result = repository.getGists(
            category = GistCategory.FORKED,
            sort = GistSort.RECENTLY_UPDATED,
        )

        assertTrue(result is ApiResult.Success)
        val gist = (result as ApiResult.Success).data.items.single()
        assertNull(gist.sourceId)
        assertNull(gist.sourceOwnerLogin)
        assertNull(gist.sourceUrl)
    }

    @Test
    fun `可见性过滤跨越不匹配批次与空批次并正确推进游标`() = runTest {
        enqueuePage(
            edges = listOf(
                "null",
                edge("c0", "null"),
                edge("c1", gistNode("public", isFork = false)),
                edge("c2", gistNode("private-fork", isFork = true, isPublic = false)),
            ),
            hasNextPage = true,
            endCursor = "c2",
        )
        enqueuePage(emptyList(), hasNextPage = true, endCursor = "c3")
        enqueuePage(
            listOf(edge("c4", gistNode("private-original", isFork = false, isPublic = false))),
            hasNextPage = false,
            endCursor = "c4",
        )

        val page = (repository.getGists(
            category = GistCategory.ORIGINAL,
            sort = GistSort.RECENTLY_UPDATED,
            visibility = GistVisibility.PRIVATE,
        ) as ApiResult.Success).data

        assertEquals(listOf("private-original"), page.items.map { it.id })
        assertEquals("c4", page.nextCursor)
        assertFalse(page.hasNextPage)
        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("\"after\":\"c2\""))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"after\":\"c3\""))
    }

    @Test
    fun `可见性筛选页中截断后下一页从最后检查的edge续接`() = runTest {
        val edges = (1..PageSize.GISTS + 1).flatMap { index ->
            listOf(
                edge("skip$index", gistNode("public$index", isFork = false)),
                edge("c$index", gistNode("private$index", isFork = false, isPublic = false)),
            )
        }
        enqueuePage(edges, hasNextPage = false, endCursor = "c${PageSize.GISTS + 1}")
        enqueuePage(edges.takeLast(2), hasNextPage = false, endCursor = "c${PageSize.GISTS + 1}")

        val first = (repository.getGists(
            category = GistCategory.ORIGINAL,
            sort = GistSort.RECENTLY_UPDATED,
            visibility = GistVisibility.PRIVATE,
        ) as ApiResult.Success).data
        assertEquals((1..PageSize.GISTS).map { "private$it" }, first.items.map { it.id })
        assertEquals("c${PageSize.GISTS}", first.nextCursor)
        assertTrue(first.hasNextPage)

        val next = (repository.getGists(
            null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, first.nextCursor, GistVisibility.PRIVATE,
        ) as ApiResult.Success).data
        assertEquals(listOf("private${PageSize.GISTS + 1}"), next.items.map { it.id })
        assertFalse(next.hasNextPage)
        server.takeRequest()
        assertEquals(JsonPrimitive("c${PageSize.GISTS}"), takeVariables()["after"])
        val cached = (repository.getGists(
            category = GistCategory.ORIGINAL,
            sort = GistSort.RECENTLY_UPDATED,
            visibility = GistVisibility.PRIVATE,
        ) as ApiResult.Success).data
        assertEquals(first, cached)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `可见性扫描达到预算后保留继续查找能力`() = runTest {
        val budget = PageSize.GIST_SCAN_BATCH_LIMIT
        repeat(budget) { batch ->
            val cursor = "c${batch + 1}"
            enqueuePage(listOf(edge(cursor, gistNode("public$batch", isFork = false))), true, cursor)
        }
        val first = (repository.getGists(
            category = GistCategory.ORIGINAL,
            sort = GistSort.RECENTLY_UPDATED,
            visibility = GistVisibility.PRIVATE,
        ) as ApiResult.Success).data
        assertTrue(first.items.isEmpty())
        assertTrue(first.hasNextPage)
        assertEquals("c$budget", first.nextCursor)
        assertEquals(budget, server.requestCount)

        enqueuePage(listOf(edge("c${budget + 1}", gistNode("private", isFork = false, isPublic = false))), false, "c${budget + 1}")
        val next = (repository.getGists(
            null, GistCategory.ORIGINAL, GistSort.RECENTLY_UPDATED, first.nextCursor, GistVisibility.PRIVATE,
        ) as ApiResult.Success).data
        assertEquals(listOf("private"), next.items.map { it.id })
        assertFalse(next.hasNextPage)
        assertEquals(budget + 1, server.requestCount)
        val requests = List(budget + 1) { takeVariables() }
        assertEquals(listOf(null) + (1..budget).map { "c$it" }, requests.map { it["after"]?.jsonPrimitive?.content })
        requests.forEach {
            assertEquals(JsonPrimitive(PageSize.GISTS), it["first"])
            assertEquals(JsonPrimitive(PageSize.GIST_FILE_METADATA_LIMIT), it["fileMetadataLimit"])
        }
    }

    @Test
    fun `预算前一批收满目标不再请求`() = runTest {
        val batches = PageSize.GIST_SCAN_BATCH_LIMIT - 1
        repeat(batches) { batch ->
            val edges = if (batch == batches - 1) {
                (1..PageSize.GISTS).map { edge("hit-$it", gistNode("original-$it", isFork = false)) }
            } else {
                listOf(edge("skip-$batch", gistNode("fork-$batch", isFork = true)))
            }
            enqueuePage(edges, true, "source-$batch")
        }

        val page = (repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED) as ApiResult.Success).data

        assertEquals(PageSize.GISTS, page.items.size)
        assertEquals("hit-${PageSize.GISTS}", page.nextCursor)
        assertTrue(page.hasNextPage)
        assertEquals(batches, server.requestCount)
    }

    @Test
    fun `文件元数据容量前一项与达到容量使用同源封顶判断`() = runTest {
        for (count in listOf(PageSize.GIST_FILE_METADATA_LIMIT - 1, PageSize.GIST_FILE_METADATA_LIMIT)) {
            enqueuePage(listOf(edge("last", gistNode("gist-$count", isFork = false, fileCount = count))), false, "last")

            val gist = (repository.getGistsFresh(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED) as ApiResult.Success).data.items.single()

            assertEquals(count, gist.fileCount)
            assertEquals(count >= PageSize.GIST_FILE_METADATA_LIMIT, gist.isFileCountCapped)
            assertEquals(JsonPrimitive(PageSize.GIST_FILE_METADATA_LIMIT), takeVariables()["fileMetadataLimit"])
        }
    }

    @Test
    fun `扫描失败不提交半批结果且重试沿用原游标`() = runTest {
        enqueuePage(listOf(edge("middle", gistNode("first", isFork = false))), true, "middle")
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ApiResult.Failure(ApiError.Forbidden), repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED, after = "start"))

        enqueuePage(listOf(edge("middle", gistNode("first", isFork = false))), true, "middle")
        enqueuePage(listOf(edge("end", gistNode("second", isFork = false))), false, "end")
        val page = (repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED, after = "start") as ApiResult.Success).data

        assertEquals(listOf("first", "second"), page.items.map { it.id })
        assertEquals(listOf("start", "middle", "start", "middle"), List(4) { takeVariables().getValue("after").jsonPrimitive.content })
    }

    @Test
    fun `空列表成功但缺失根节点仍失败`() = runTest {
        enqueuePage(emptyList(), false, null)
        val empty = repository.getGistsFresh(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED) as ApiResult.Success
        assertTrue(empty.data.items.isEmpty())
        server.enqueue(MockResponse().setBody("""{"data":{"viewer":{"login":"viewer"},"user":null}}"""))
        assertTrue(repository.getGists(login = "missing", category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED) is ApiResult.Failure)
        server.enqueue(MockResponse().setBody("""{"data":{"viewer":{"login":"viewer"}}}"""))
        assertTrue(repository.getGistsFresh(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED) is ApiResult.Failure)
    }

    @Test
    fun `三种可见性首屏缓存隔离且刷新只更新对应缓存`() = runTest {
        val expected = mapOf(
            GistVisibility.ALL to listOf("public", "private"),
            GistVisibility.PUBLIC to listOf("public"),
            GistVisibility.PRIVATE to listOf("private"),
        )
        repeat(3) {
            enqueuePage(
                listOf(
                    edge("c1", gistNode("public", isFork = false)),
                    edge("c2", gistNode("private", isFork = false, isPublic = false)),
                ),
                false, "c2",
            )
        }
        repeat(2) {
            for ((visibility, ids) in expected) {
                val result = repository.getGists(
                    category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED, visibility = visibility,
                ) as ApiResult.Success
                assertEquals(ids, result.data.items.map { it.id })
            }
        }
        assertEquals(3, server.requestCount)
        enqueuePage(listOf(edge("fresh", gistNode("fresh-private", isFork = false, isPublic = false))), false, "fresh")
        repository.getGistsFresh(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED, visibility = GistVisibility.PRIVATE)
        for ((visibility, ids) in expected) {
            val result = repository.getGists(
                category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED, visibility = visibility,
            ) as ApiResult.Success
            assertEquals(if (visibility == GistVisibility.PRIVATE) listOf("fresh-private") else ids, result.data.items.map { it.id })
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `他人私有筛选仍使用公开查询并返回最终无匹配`() = runTest {
        enqueueUserPage("octocat", listOf(edge("c1", gistNode("public", false, ownerLogin = "octocat"))), false, "c1")
        val result = repository.getGists(
            login = "octocat", category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED,
            visibility = GistVisibility.PRIVATE,
        ) as ApiResult.Success
        assertTrue(result.data.items.isEmpty())
        assertFalse(result.data.hasNextPage)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("privacy: PUBLIC"))
        assertFalse(body.contains("privacy: ALL"))
    }

    @Test
    fun `详情合并GraphQL社交数据`() = runTest {
        restServer.enqueue(MockResponse().setBody(detailJson(
            forkOf = """{
                "id":"source-id",
                "html_url":"https://gist.github.com/octocat/source-id",
                "owner":{"login":"octocat"},
                "files":{}
            }""",
        )))
        enqueueGistSocial(starred = true, stars = 4, forks = 2)

        val detail = (repository.getGist("abc") as ApiResult.Success).data

        assertEquals("abc", detail.id)
        assertTrue(detail.isOwnedByViewer)
        assertTrue(detail.isStarred)
        assertEquals(4, detail.stargazerCount)
        assertEquals(2, detail.forkCount)
        assertEquals("main.txt", detail.files.single().filename)
        assertEquals("source-id", detail.forkOf?.id)
        assertEquals("octocat", detail.forkOf?.owner?.login)
        assertEquals("https://gist.github.com/octocat/source-id", detail.forkOf?.url)
        assertEquals("sha-new", detail.latestVersion)
        assertEquals("/gists/abc", restServer.takeRequest().path)
        assertEquals(JsonPrimitive("node-abc"), takeVariables()["id"])
    }

    @Test
    fun `详情历史缺失时从文件原始地址获取当前版本`() = runTest {
        val version = "0123456789abcdef0123456789abcdef01234567"
        restServer.enqueue(MockResponse().setBody(detailJson(
            history = "[]",
            files = """{"main.txt":{"filename":"main.txt","raw_url":"https://gist.githubusercontent.com/viewer/abc/raw/$version/main.txt","size":5,"content":"hello"}}""",
        )))
        enqueueGistSocial(starred = false, stars = 0, forks = 0)

        val detail = (repository.getGist("abc") as ApiResult.Success).data

        assertEquals(version, detail.latestVersion)
    }

    @Test
    fun `编辑文件只读取详情REST并映射完整内容`() = runTest {
        restServer.enqueue(MockResponse().setBody(detailJson()))

        val file = (repository.getGistFile("abc", "main.txt") as ApiResult.Success).data

        assertEquals("main.txt", file.filename)
        assertEquals("hello", file.content)
        assertEquals("/gists/abc", restServer.takeRequest().path)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `星标写操作使用无请求体PUT并接受204`() = runTest {
        restServer.enqueue(MockResponse().setResponseCode(204))

        assertEquals(ApiResult.Success(true), repository.setStarred("abc", true))

        val request = restServer.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/gists/abc/star", request.path)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `创建Gist发送布尔公开值和两个字符串内容并使列表缓存失效`() = runTest {
        enqueuePage(listOf(edge("old", gistNode("old", isFork = false))), false, "old")
        repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)
        restServer.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"created-id"}"""))

        val result = repository.createGist(
            description = "sample",
            isPublic = false,
            files = linkedMapOf("empty.txt" to "", "readme.md" to "# hello"),
        )

        assertEquals(ApiResult.Success("created-id"), result)
        val request = restServer.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/gists", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive("sample"), body["description"])
        assertEquals(JsonPrimitive(false), body["public"])
        val files = body.getValue("files").jsonObject
        assertEquals(JsonPrimitive(""), files.getValue("empty.txt").jsonObject["content"])
        assertEquals(JsonPrimitive("# hello"), files.getValue("readme.md").jsonObject["content"])

        enqueuePage(listOf(edge("fresh", gistNode("fresh", isFork = false))), false, "fresh")
        val fresh = repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)
        assertEquals("fresh", (fresh as ApiResult.Success).data.items.single().id)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `复刻与评论写操作使用正确端点和正文`() = runTest {
        val commentJson = """{"id":7,"node_id":"comment-node","html_url":"https://gist.github.com/viewer/abc#comment-7","body":"hello","body_html":"<p>hello</p>","user":{"login":"viewer"},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z","author_association":"OWNER"}"""
        restServer.enqueue(MockResponse().setBody(detailJson()))
        restServer.enqueue(MockResponse().setBody(commentJson))
        restServer.enqueue(MockResponse().setBody(commentJson.replace("hello", "updated")))
        restServer.enqueue(MockResponse().setResponseCode(204))

        assertTrue(repository.fork("abc") is ApiResult.Success)
        assertTrue(repository.createComment("abc", "hello") is ApiResult.Success)
        assertTrue(repository.updateComment("abc", 7, "updated") is ApiResult.Success)
        assertTrue(repository.deleteComment("abc", 7) is ApiResult.Success)

        val fork = restServer.takeRequest()
        assertEquals("POST", fork.method)
        assertEquals("/gists/abc/forks", fork.path)
        val create = restServer.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/gists/abc/comments", create.path)
        assertEquals("hello", Json.parseToJsonElement(create.body.readUtf8()).jsonObject.getValue("body").jsonPrimitive.content)
        val update = restServer.takeRequest()
        assertEquals("PATCH", update.method)
        assertEquals("/gists/abc/comments/7", update.path)
        assertEquals("updated", Json.parseToJsonElement(update.body.readUtf8()).jsonObject.getValue("body").jsonPrimitive.content)
        val delete = restServer.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/gists/abc/comments/7", delete.path)
    }

    @Test
    fun `更新文件只提交原文件键且删除提交null`() = runTest {
        restServer.enqueue(MockResponse().setBody("{}"))
        restServer.enqueue(MockResponse().setBody("{}"))

        assertTrue(repository.updateFile("abc", "old.txt", "new.txt", "hello") is ApiResult.Success)
        assertTrue(repository.deleteFile("abc", "new.txt") is ApiResult.Success)

        val update = Json.parseToJsonElement(restServer.takeRequest().body.readUtf8()).jsonObject
        val updateFile = update.getValue("files").jsonObject.getValue("old.txt").jsonObject
        assertEquals("new.txt", updateFile.getValue("filename").jsonPrimitive.content)
        assertEquals("hello", updateFile.getValue("content").jsonPrimitive.content)
        val deletion = restServer.takeRequest().body.readUtf8()
        assertTrue(deletion.contains("\"new.txt\":null"))
    }

    @Test
    fun `修订复刻评论列表使用集中分页容量`() = runTest {
        restServer.enqueue(MockResponse().setBody("""[{"version":"sha","committed_at":"2026-01-01T00:00:00Z","change_status":{"total":2,"additions":1,"deletions":1}}]"""))
        restServer.enqueue(MockResponse().setBody("""[{"id":"fork","owner":{"login":"octocat"},"created_at":"2026-01-01T00:00:00Z"}]"""))
        restServer.enqueue(MockResponse().setBody("""[{"id":7,"node_id":"node","html_url":"https://gist.github.com/a/b#comment-7","body":"hello","body_html":"<p>hello</p>","user":{"login":"viewer"},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z","author_association":"OWNER"}]"""))

        assertEquals("sha", (repository.getRevisions("abc", 2) as ApiResult.Success).data.items.single().version)
        assertEquals("fork", (repository.getForks("abc", 3) as ApiResult.Success).data.items.single().id)
        assertEquals(7L, (repository.getComments("abc", 4) as ApiResult.Success).data.items.single().id)

        assertEquals("/gists/abc/commits?per_page=${PageSize.GIST_REVISIONS}&page=2", restServer.takeRequest().path)
        assertEquals("/gists/abc/forks?per_page=${PageSize.GIST_FORKS}&page=3", restServer.takeRequest().path)
        val commentRequest = restServer.takeRequest()
        assertEquals("/gists/abc/comments?per_page=${PageSize.GIST_COMMENTS}&page=4", commentRequest.path)
        assertEquals("application/vnd.github.full+json", commentRequest.getHeader("Accept"))
    }

    @Test
    fun `相邻修订只比较完整文本并标记截断内容`() = runTest {
        restServer.enqueue(MockResponse().setBody(detailJson(
            history = """[{"version":"older-than-selected","committed_at":"2026-01-15T00:00:00Z"}]""",
            files = """{
                "main.txt":{"filename":"main.txt","raw_url":"https://example/main","size":5,"truncated":false,"content":"a\\nb"},
                "large.txt":{"filename":"large.txt","raw_url":"https://example/large","size":20,"truncated":true}
            }""",
        )))
        restServer.enqueue(MockResponse().setBody(detailJson(
            history = """[{"version":"old","committed_at":"2026-01-01T00:00:00Z"}]""",
            files = """{
                "main.txt":{"filename":"main.txt","raw_url":"https://example/main","size":1,"truncated":false,"content":"a"},
                "large.txt":{"filename":"large.txt","raw_url":"https://example/large","size":20,"truncated":true}
            }""",
        )))

        val selected = GistRevision("new", null, "2026-02-01T00:00:00Z", 1, 0, 1)
        val comparison = (repository.compareRevisions("abc", selected, "old") as ApiResult.Success).data

        assertEquals(selected, comparison.revision)
        assertEquals(listOf("large.txt", "main.txt"), comparison.files.map { it.filename })
        assertEquals(com.gitmob.app.data.model.GistDiffUnavailableReason.TRUNCATED, comparison.files.first().unavailableReason)
        assertEquals(1, comparison.files.last().diff?.hunks?.single()?.lines?.count { it.type == com.gitmob.app.core.diff.DiffLineType.ADDITION })
    }

    @Test
    fun `最早修订扫描全部REST分页`() = runTest {
        val firstPage = (1..PageSize.GIST_REVISION_SCAN).joinToString(",") { index ->
            """{"version":"sha-$index","committed_at":"2026-01-01T00:00:00Z","change_status":{}}"""
        }
        restServer.enqueue(MockResponse().setBody("[$firstPage]"))
        restServer.enqueue(MockResponse().setBody(
            """[{"version":"oldest","committed_at":"2020-01-01T00:00:00Z","change_status":{}}]""",
        ))

        assertEquals("oldest", (repository.getOldestVersion("abc") as ApiResult.Success).data)
        assertEquals(
            "/gists/abc/commits?per_page=${PageSize.GIST_REVISION_SCAN}&page=1",
            restServer.takeRequest().path,
        )
        assertEquals(
            "/gists/abc/commits?per_page=${PageSize.GIST_REVISION_SCAN}&page=2",
            restServer.takeRequest().path,
        )
    }

    @Test
    fun `添加文件先拒绝同名再只提交新文件`() = runTest {
        restServer.enqueue(MockResponse().setBody(detailJson()))
        assertTrue(repository.addFile("abc", "main.txt", "new") is ApiResult.Failure)
        assertEquals("GET", restServer.takeRequest().method)

        restServer.enqueue(MockResponse().setBody(detailJson()))
        restServer.enqueue(MockResponse().setBody("{}"))
        assertTrue(repository.addFile("abc", "extra.txt", "hello") is ApiResult.Success)

        assertEquals("GET", restServer.takeRequest().method)
        val patch = restServer.takeRequest()
        assertEquals("PATCH", patch.method)
        val file = Json.parseToJsonElement(patch.body.readUtf8()).jsonObject
            .getValue("files").jsonObject.getValue("extra.txt").jsonObject
        assertEquals("extra.txt", file.getValue("filename").jsonPrimitive.content)
        assertEquals("hello", file.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `Gist标星用户使用GraphQL游标分页`() = runTest {
        server.enqueue(MockResponse().setBody(
            """
            {
              "data": {
                "node": {
                  "__typename": "Gist",
                  "stargazers": {
                    "totalCount": 1,
                    "nodes": [{"login":"fan","name":"Fan","avatarUrl":"https://example/avatar","bio":"bio"}],
                    "pageInfo": {"hasNextPage":false,"endCursor":null}
                  }
                }
              }
            }
            """.trimIndent(),
        ))

        val users = (repository.getStargazers("node-id") as ApiResult.Success).data

        assertEquals("fan", users.users.single().login)
        assertEquals(1, users.totalCount)
        val variables = takeVariables()
        assertEquals(JsonPrimitive("node-id"), variables["id"])
        assertEquals(JsonPrimitive(PageSize.USER_LIST), variables["first"])
    }

    private fun takeVariables() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject

    private fun detailJson(
        history: String = """[{"version":"sha-new","committed_at":"2026-02-01T00:00:00Z"}]""",
        files: String = """{"main.txt":{"filename":"main.txt","type":"text/plain","language":"Text","raw_url":"https://gist.githubusercontent.com/viewer/abc/raw/main.txt","size":5,"truncated":false,"content":"hello"}}""",
        forkOf: String? = null,
    ) = """
        {
          "id":"abc",
          "node_id":"node-abc",
          "html_url":"https://gist.github.com/viewer/abc",
          "description":"sample",
          "public":false,
          "owner":{"login":"viewer","avatar_url":"https://example/avatar"},
          "files":$files,
          "comments":1,
          "comments_enabled":true,
          "created_at":"2026-01-01T00:00:00Z",
          "updated_at":"2026-02-01T00:00:00Z",
          "history":$history${forkOf?.let { ",\"fork_of\":$it" }.orEmpty()}
        }
    """.trimIndent()

    private fun enqueuePage(
        edges: List<String>,
        hasNextPage: Boolean,
        endCursor: String?,
    ) {
        val cursorJson = endCursor?.let { "\"$it\"" } ?: "null"
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "viewer": {
                      "login": "viewer",
                      "gists": {
                        "edges": [${edges.joinToString(",")}],
                        "pageInfo": {
                          "hasNextPage": $hasNextPage,
                          "endCursor": $cursorJson
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
    }

    private fun enqueueUserPage(
        targetLogin: String,
        edges: List<String>,
        hasNextPage: Boolean,
        endCursor: String?,
    ) {
        val cursorJson = endCursor?.let { "\"$it\"" } ?: "null"
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "viewer": { "login": "viewer" },
                    "user": {
                      "login": "$targetLogin",
                      "gists": {
                        "edges": [${edges.joinToString(",")}],
                        "pageInfo": {
                          "hasNextPage": $hasNextPage,
                          "endCursor": $cursorJson
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
    }

    private fun enqueueForkSource(
        gistId: String,
        sourceId: String = "source-$gistId",
        sourceOwner: String = "source-owner",
    ) {
        restServer.enqueue(
            MockResponse().setBody(
                """
                {
                  "id": "$gistId",
                  "fork_of": {
                    "id": "$sourceId",
                    "owner": { "login": "$sourceOwner" },
                    "html_url": "https://gist.github.com/$sourceOwner/$sourceId",
                    "files": {}
                  }
                }
                """.trimIndent(),
            ),
        )
    }

    private fun enqueueGistSocial(starred: Boolean, stars: Int, forks: Int) {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "node": {
                      "__typename": "Gist",
                      "stargazerCount": $stars,
                      "viewerHasStarred": $starred,
                      "forks": { "totalCount": $forks }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
    }

    private fun edge(cursor: String, node: String): String =
        """{"cursor":"$cursor","node":$node}"""

    private fun gistNode(
        id: String,
        isFork: Boolean,
        isPublic: Boolean = true,
        description: String? = null,
        fileName: String = "$id.txt",
        fileText: String? = "content",
        fileCount: Int = 1,
        ownerLogin: String = "viewer",
    ): String {
        val descriptionJson = description?.let { "\"$it\"" } ?: "null"
        val textJson = fileText?.let { "\"$it\"" } ?: "null"
        val metadata = (1..fileCount).joinToString(",") { index ->
            """{"name":"file-$index.txt"}"""
        }
        return """
            {
              "id":"$id",
              "name":"$id",
              "description":$descriptionJson,
              "owner":{"__typename":"User","login":"$ownerLogin"},
              "isPublic":$isPublic,
              "isFork":$isFork,
              "createdAt":"2026-01-01T00:00:00Z",
              "updatedAt":"2026-02-01T00:00:00Z",
              "stargazerCount":7,
              "comments":{"totalCount":3},
              "url":"https://gist.github.com/viewer/$id",
              "previewFiles":[{
                "name":"$fileName",
                "text":$textJson,
                "size":18,
                "isTruncated":false,
                "isImage":false,
                "language":{"name":"Kotlin","color":"#A97BFF"}
              }],
              "fileMetadata":[$metadata]
            }
        """.trimIndent()
    }
}
