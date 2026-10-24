package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.AuthHttpInterceptor
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.GistPage
import com.gitmob.app.data.model.GistSort
import com.gitmob.app.data.model.GistVisibility
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
    private lateinit var repository: GistRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val okHttpClient = OkHttpClient()
        val apolloClient = ApolloClient.Builder()
            .serverUrl("$baseUrl/graphql")
            .okHttpClient(okHttpClient)
            .addHttpInterceptor(
                AuthHttpInterceptor(
                    object : AccessTokenProvider {
                        override suspend fun getToken() = "fake-token"
                    },
                ),
            )
            .build()
        repository = GistRepository(apolloClient)
    }

    @After
    fun teardown() {
        server.shutdown()
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
            val result = repository.getGists(category = category, sort = GistSort.RECENTLY_UPDATED, visibility = visibility)
            val page = (result as ApiResult.Success).data
            assertEquals(expectedIds, page.items.map { it.id })
            assertFalse(page.hasNextPage)
        }
        assertEquals(6, server.requestCount)
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

    private fun takeVariables() =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject

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
