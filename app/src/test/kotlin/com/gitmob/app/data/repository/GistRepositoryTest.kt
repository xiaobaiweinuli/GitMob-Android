package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.GistPage
import com.gitmob.app.data.model.GistSort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
        repository = GistRepository(
            GHApiClient(
                okHttpClient = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
                tokenProvider = object : AccessTokenProvider {
                    override suspend fun getToken() = "fake-token"
                },
                restBaseUrl = baseUrl,
                graphQLUrl = "$baseUrl/graphql",
            ),
        )
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
    fun `收集满20项时停在最后检查的edge并保留未检查数据`() = runTest {
        val edges = (1..21).map { index ->
            edge("c$index", gistNode("g$index", isFork = false))
        }
        enqueuePage(edges = edges, hasNextPage = false, endCursor = "c21")

        val result = repository.getGists(category = GistCategory.ORIGINAL, sort = GistSort.RECENTLY_UPDATED)

        val page = (result as ApiResult.Success).data
        assertEquals(20, page.items.size)
        assertEquals("c20", page.nextCursor)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `达到扫描批次上限时允许空页但保留下一页`() = runTest {
        repeat(5) { batch ->
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
        assertEquals("c5", page.nextCursor)
        assertEquals(5, server.requestCount)
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
              "owner":{"login":"$ownerLogin"},
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
