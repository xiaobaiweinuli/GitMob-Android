package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.RepoFileContent
import com.gitmob.app.data.model.RepoPathContent
import com.gitmob.app.testutil.AccountSessionRule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RepoGitContentTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var apollo: ApolloClient
    private lateinit var repository: RepoGitRepository

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val client = OkHttpClient()
        apollo = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).okHttpClient(client).build()
        val tokenProvider = object : AccessTokenProvider { override suspend fun getToken() = "token" }
        repository = RepoGitRepository(GHApiClient(client, Json, tokenProvider, server.url("/").toString().trimEnd('/')), apollo)
    }

    @After fun cleanup() { apollo.close(); server.shutdown() }

    @Test fun directoryWithoutSlashIsIdentifiedByItsObjectTypeInOneQuery() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody(response("""{"__typename":"Tree"}""")))
        val result = repository.getPathContent("owner", "repo", "feature/docs", "docs") as ApiResult.Success
        assertEquals(RepoPathContent.Directory, result.data)
        assertEquals(1, server.requestCount)
        val variables = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
        assertEquals("feature/docs:docs", variables.getValue("expression").jsonPrimitive.content)
        assertEquals("refs/heads/feature/docs", variables.getValue("qualifiedRef").jsonPrimitive.content)
    }

    @Test fun filesStillReturnTheirTextAndMetadataForPreviewAndEditing() = runTest(accounts.context) {
        repeat(2) { server.enqueue(MockResponse().setBody(response(blob))) }
        val preview = (repository.getPathContent("owner", "repo", "feature/docs", "README.md") as ApiResult.Success).data as RepoFileContent
        val editor = (repository.getFileContent("owner", "repo", "feature/docs", "README.md") as ApiResult.Success).data
        assertEquals(preview, editor)
        assertEquals("# Guide", preview.text)
        assertEquals("README.md", preview.path)
        assertEquals("feature/docs", preview.ref)
        assertEquals("head", preview.headOid)
        assertFalse(preview.isBinary)
    }

    @Test fun missingAndUnsupportedObjectsRemainFailures() = runTest(accounts.context) {
        for (target in listOf("null", """{"__typename":"Commit"}""")) {
            server.enqueue(MockResponse().setBody(response(target)))
            assertTrue(repository.getPathContent("owner", "repo", "main", "missing") is ApiResult.Failure)
        }
    }

    @Test fun editorCannotTreatDirectoryAsEditableFile() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody(response("""{"__typename":"Tree"}""")))
        assertTrue(repository.getFileContent("owner", "repo", "main", "docs") is ApiResult.Failure)
    }

    @Test fun failedQueryDoesNotProduceDirectoryNavigation() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        assertTrue(repository.getPathContent("owner", "repo", "main", "docs") is ApiResult.Failure)
    }

    private val blob = """{"__typename":"Blob","oid":"blob","byteSize":7,"isBinary":false,"isTruncated":false,"text":"# Guide"}"""

    private fun response(target: String) = """
        {"data":{"repository":{
          "id":"R_repo","viewerPermission":"READ","isArchived":false,
          "ref":{"name":"feature/docs","target":{"__typename":"Commit","oid":"head"}},
          "object":$target
        }}}
    """.trimIndent()
}
