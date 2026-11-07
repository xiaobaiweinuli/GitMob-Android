package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.markdown.RepoDocumentLink
import com.gitmob.app.core.markdown.resolveLink
import com.gitmob.app.data.model.RepoDocumentContext
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
        assertFalse(preview.isCommitSnapshot)
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

    @Test fun commitFileUsesSharedReadersWithoutAnExistingBranch() = runTest(accounts.context) {
        val commit = "a".repeat(40)
        repeat(2) { server.enqueue(MockResponse().setBody(response(blob, branch = null, commit = commit, permission = "WRITE"))) }
        val file = (repository.getPathContent("owner", "repo", commit, "README.md") as ApiResult.Success).data as RepoFileContent
        val direct = (repository.getFileContent("owner", "repo", commit, "README.md") as ApiResult.Success).data
        assertEquals(file, direct)
        assertEquals(commit, file.ref)
        assertEquals(commit, file.headOid)
        assertEquals("blob", file.oid)
        assertTrue(file.isCommitSnapshot)
        assertTrue(file.capabilities.canPush)
        assertEquals(2, server.requestCount)
        repeat(2) {
            val variables = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
            assertEquals("$commit:README.md", variables.getValue("expression").jsonPrimitive.content)
            assertEquals(commit, variables.getValue("revision").jsonPrimitive.content)
        }
    }

    @Test fun commitDocumentLinksReachDirectoryAndChildFileAtTheSameVersion() = runTest(accounts.context) {
        val commit = "a".repeat(40)
        val document = RepoDocumentContext("owner", "repo", commit, "README.md")
        for (href in listOf("docs", "docs/")) {
            val link = (document.resolveLink(href) as RepoDocumentLink.File).document
            assertEquals(commit, link.ref)
            val path = link.filePath.trimEnd('/')
            if (!link.filePath.endsWith('/')) {
                server.enqueue(MockResponse().setBody(response("""{"__typename":"Tree"}""", branch = null, commit = commit)))
                assertEquals(RepoPathContent.Directory,
                    (repository.getPathContent(link.owner, link.name, link.ref, path) as ApiResult.Success).data)
                val variables = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
                assertEquals("$commit:docs", variables.getValue("expression").jsonPrimitive.content)
            }
            val target = """{"__typename":"Tree","entries":[
                {"name":"guide.md","path":"docs/guide.md","type":"blob","oid":"blob","size":7,"extension":".md","language":null}
            ]}"""
            server.enqueue(MockResponse().setBody(response(target, branch = null, commit = commit, permission = "ADMIN")))
            val tree = (repository.getCodeTree(link.owner, link.name, link.ref, path) as ApiResult.Success).data
            assertEquals(commit, tree.ref)
            assertEquals(commit, tree.headOid)
            assertTrue(tree.isCommitSnapshot)
            assertTrue(tree.capabilities.canPush)
            val treeVariables = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
            assertEquals("$commit:docs", treeVariables.getValue("expression").jsonPrimitive.content)

            server.enqueue(MockResponse().setBody(response(blob, branch = null, commit = commit)))
            val file = (repository.getFileContent(link.owner, link.name, tree.ref, tree.entries.single().path) as ApiResult.Success).data
            assertEquals(commit, file.ref)
            assertTrue(file.isCommitSnapshot)
            val fileVariables = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
            assertEquals("$commit:docs/guide.md", fileVariables.getValue("expression").jsonPrimitive.content)
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun commitRootAndNestedDirectoriesKeepTheSameCommit() = runTest(accounts.context) {
        val commit = "b".repeat(40)
        for (path in listOf("", "docs", "docs/nested")) {
            server.enqueue(MockResponse().setBody(response("""{"__typename":"Tree","entries":[]}""", branch = null, commit = commit)))
            val tree = (repository.getCodeTree("owner", "repo", commit, path) as ApiResult.Success).data
            assertEquals(commit, tree.ref)
            assertEquals(path, tree.path)
            assertTrue(tree.isCommitSnapshot)
            val variables = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("variables").jsonObject
            assertEquals("$commit:$path", variables.getValue("expression").jsonPrimitive.content)
        }
    }

    @Test fun branchTreeKeepsWritableBranchAndHeadMetadata() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody(response("""{"__typename":"Tree","entries":[]}""", permission = "WRITE")))
        val tree = (repository.getCodeTree("owner", "repo", "feature/docs", "docs") as ApiResult.Success).data
        assertEquals("feature/docs", tree.ref)
        assertEquals("head", tree.headOid)
        assertFalse(tree.isCommitSnapshot)
        assertTrue(tree.capabilities.canPush)
    }

    @Test fun absentCommitAndBranchRemainFailuresEvenWithAPathObject() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody(response(blob, branch = null)))
        assertTrue(repository.getPathContent("owner", "repo", "missing", "README.md") is ApiResult.Failure)
        server.enqueue(MockResponse().setBody(response("""{"__typename":"Tree","entries":[]}""", branch = null)))
        assertTrue(repository.getCodeTree("owner", "repo", "missing", "docs") is ApiResult.Failure)
    }

    private val blob = """{"__typename":"Blob","oid":"blob","byteSize":7,"isBinary":false,"isTruncated":false,"text":"# Guide"}"""

    private fun response(target: String, branch: String? = "feature/docs", commit: String? = null, permission: String = "READ"): String {
        val refJson = branch?.let { """{"name":"$it","target":{"__typename":"Commit","oid":"head"}}""" } ?: "null"
        val commitJson = commit?.let { """{"__typename":"Commit","oid":"$it"}""" } ?: "null"
        return """
            {"data":{"repository":{
              "id":"R_repo","viewerPermission":"$permission","isArchived":false,"isFork":false,
              "ref":$refJson,"commit":$commitJson,"object":$target
            }}}
        """.trimIndent()
    }
}
