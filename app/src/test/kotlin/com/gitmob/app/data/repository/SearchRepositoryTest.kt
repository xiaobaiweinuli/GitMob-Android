package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.*
import com.gitmob.app.testutil.AccountSessionRule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SearchRepositoryTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var apollo: ApolloClient
    private lateinit var repository: SearchRepository
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val client = OkHttpClient()
        apollo = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).okHttpClient(client).build()
        val tokens = object : AccessTokenProvider { override suspend fun getToken() = "token" }
        repository = SearchRepository(apollo, GHApiClient(client, Json { ignoreUnknownKeys = true }, tokens, server.url("/").toString().trimEnd('/')))
    }
    @After fun close() { apollo.close(); server.shutdown() }
    private fun codeItem(index: Int, extraFields: String = "") = """
        {"name":"File$index.kt","path":"src/File$index.kt","sha":"blob-not-a-ref",
        "html_url":"https://github.com/owner/repo/blob/feature/search/src/File$index.kt",
        "repository":{"name":"repo","owner":{"login":"owner"}}$extraFields}
    """.trimIndent()
    private fun codeResponse(count: Int, items: String, incomplete: Boolean = false) =
        """{"total_count":$count,"incomplete_results":$incomplete,"items":[$items]}"""

    @Test fun previewAndFullCategoryStartWithDifferentCapacitiesAtPageOne() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody(codeResponse(100, (1..5).joinToString { codeItem(it) })))
        server.enqueue(MockResponse().setBody(codeResponse(100, (1..20).joinToString { codeItem(it) })))
        val preview = (repository.search("a + b", SearchCategory.CODE, true) as ApiResult.Success).data
        val full = (repository.search("a + b", SearchCategory.CODE, false) as ApiResult.Success).data
        assertEquals(5, preview.items.size)
        assertEquals(20, full.items.size)
        assertEquals("src/File6.kt", (full.items[5] as SearchItem.Code).path)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("5", first.requestUrl!!.queryParameter("per_page"))
        assertEquals("20", second.requestUrl!!.queryParameter("per_page"))
        assertEquals("1", second.requestUrl!!.queryParameter("page"))
        assertEquals("a + b", second.requestUrl!!.queryParameter("q"))
        assertEquals("application/vnd.github.text-match+json", first.getHeader("Accept"))
        assertEquals(2, full.next!!.page)
    }

    @Test fun snippetsValidateHighlightRangesAndPreserveFileTarget() = runTest(accounts.context) {
        val items = listOf(codeItem(1),
            codeItem(2, """, "text_matches":[{"property":"content","fragment":"fun main","matches":[{"text":"main","indices":[4,8]},{"indices":[-1,2]},{"indices":[0,99]}]}]"""))
        server.enqueue(MockResponse().setBody(codeResponse(items.size, items.joinToString())))
        val page = (repository.search("main", SearchCategory.CODE, true) as ApiResult.Success).data
        val files = page.items.map { it as SearchItem.Code }
        assertEquals(listOf(SearchMatch(4, 8)), files[1].snippets.single().matches)
        assertEquals("feature/search", files[1].target!!.ref)
        assertTrue(files[0].snippets.isEmpty())
    }

    @Test fun chineseCodeMatchUsesUtf8OffsetsFromScreenshot() = runTest(accounts.context) {
        val item = codeItem(1, """, "text_matches":[{
            "property":"content","fragment":"```py\n# 查看前五个测试 Y 值\nlist(y_test)[0:5]",
            "matches":[{"text":"测试","indices":[23,29]}]
        }]""")
        server.enqueue(MockResponse().setBody(codeResponse(1, item)))
        val page = (repository.search("测试", SearchCategory.CODE, true) as ApiResult.Success).data
        val snippet = (page.items.single() as SearchItem.Code).snippets.single()
        assertEquals(listOf(SearchMatch(13, 15)), snippet.matches)
        assertEquals("测试", snippet.text.substring(snippet.matches.single().start, snippet.matches.single().end))
    }

    @Test fun codeMatchesKeepAllFragmentsAndHandleEmojiBeforeMultipleHits() = runTest(accounts.context) {
        val item = codeItem(1, """, "text_matches":[{
            "property":"content","fragment":"🚀 测试 main 测试",
            "matches":[{"text":"测试","indices":[5,11]},{"text":"main","indices":[12,16]},{"text":"测试","indices":[17,23]}]
        },{
            "property":"content","fragment":"第二段测试",
            "matches":[{"text":"测试","indices":[9,15]}]
        }]""")
        server.enqueue(MockResponse().setBody(codeResponse(1, item)))
        val page = (repository.search("测试 main", SearchCategory.CODE, true) as ApiResult.Success).data
        val snippets = (page.items.single() as SearchItem.Code).snippets
        assertEquals(listOf("🚀 测试 main 测试", "第二段测试"), snippets.map { it.text })
        assertEquals(listOf(SearchMatch(3, 5), SearchMatch(6, 10), SearchMatch(11, 13)), snippets[0].matches)
        assertEquals(listOf(SearchMatch(3, 5)), snippets[1].matches)
    }

    @Test fun invalidByteBoundariesAndMismatchedTextDoNotHighlightUnrelatedCharacters() = runTest(accounts.context) {
        val item = codeItem(1, """, "text_matches":[{
            "property":"content","fragment":"中测试 tail",
            "matches":[{"text":"测试","indices":[1,7]},{"text":"测试","indices":[3,8]},
                {"text":"错误","indices":[3,9]},{"indices":[-1,3]},{"indices":[3,99]},
                {"indices":[9,3]},{"indices":[3,3]},{"indices":[]}]
        }]""")
        server.enqueue(MockResponse().setBody(codeResponse(1, item)))
        val page = (repository.search("测试", SearchCategory.CODE, true) as ApiResult.Success).data
        val snippet = (page.items.single() as SearchItem.Code).snippets.single()
        assertEquals("中测试 tail", snippet.text)
        assertTrue(snippet.matches.isEmpty())
    }

    @Test fun incompleteResultsRemainUsableAndThousandResultCapStopsContinuation() = runTest(accounts.context) {
        val items = (1..20).joinToString { codeItem(it) }
        server.enqueue(MockResponse().setBody(codeResponse(1200, items, incomplete = true)))
        val page = (repository.search("main", SearchCategory.CODE, false,
            SearchContinuation(page = 50, received = 980)) as ApiResult.Success).data
        assertEquals(20, page.items.size)
        assertTrue(page.incomplete)
        assertTrue(page.limitReached)
        assertNull(page.next)
    }

    @Test fun invalidCodeQueryIsLocalFailureAndScopeConflictDoesNotMakeRequest() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(422).setBody("invalid query"))
        val failure = repository.search("bad", SearchCategory.CODE, false) as ApiResult.Failure
        assertEquals(ApiError.UserVisible(R.string.search_invalid_query), failure.error)
        assertTrue(repository.search("is:pr", SearchCategory.ISSUES, false) is ApiResult.Failure)
        assertEquals(1, server.requestCount)
    }

    @Test fun graphQlUsesAccountScopeAndCentralPageSize() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("""{"data":{"search":{"userCount":0,"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}"""))
        assertTrue(repository.search("github", SearchCategory.ORGANIZATIONS, false) is ApiResult.Success)
        val variables = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
        assertEquals("github type:org", variables.getValue("query").jsonPrimitive.content)
        assertEquals(PageSize.SEARCH_RESULTS, variables.getValue("first").jsonPrimitive.int)
    }
}
