package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.testutil.TestAccessTokenProvider
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.markdown.RepoDocumentLink
import com.gitmob.app.core.markdown.resolveLink
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.model.RepoFileContent
import com.gitmob.app.data.model.RepoPathContent
import com.gitmob.app.data.model.RepoLinkKind
import com.gitmob.app.testutil.AccountSessionRule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
        val tokenProvider = object : TestAccessTokenProvider { override suspend fun getToken() = "token" }
        repository = RepoGitRepository(GHApiClient(client, Json, tokenProvider, server.url("/").toString().trimEnd('/')), apollo, io.mockk.mockk())
    }

    @After fun cleanup() { apollo.close(); server.close() }

    @Test fun directoryWithoutSlashIsIdentifiedByItsObjectTypeInOneQuery() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = response("""{"__typename":"Tree"}""")))
        val result = repository.getPathContent("owner", "repo", "feature/docs", "docs") as ApiResult.Success
        assertEquals(RepoPathContent.Directory, result.data)
        assertEquals(1, server.requestCount)
        val variables = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject.getValue("variables").jsonObject
        assertEquals("docs", variables.getValue("path").jsonPrimitive.content)
        assertEquals("refs/heads/feature/docs", variables.getValue("qualifiedRef").jsonPrimitive.content)
    }

    @Test fun filesStillReturnTheirTextAndMetadataForPreviewAndEditing() = runTest(accounts.context) {
        repeat(2) { server.enqueue(MockResponse(body = response(blob))) }
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
            server.enqueue(MockResponse(body = response(target)))
            assertTrue(repository.getPathContent("owner", "repo", "main", "missing") is ApiResult.Failure)
        }
    }

    @Test fun editorCannotTreatDirectoryAsEditableFile() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = response("""{"__typename":"Tree"}""")))
        assertTrue(repository.getFileContent("owner", "repo", "main", "docs") is ApiResult.Failure)
    }

    @Test fun failedQueryDoesNotProduceDirectoryNavigation() = runTest(accounts.context) {
        server.enqueue(MockResponse(code = 403, body = "forbidden"))
        assertTrue(repository.getPathContent("owner", "repo", "main", "docs") is ApiResult.Failure)
    }

    @Test fun commitFileUsesSharedReadersWithoutAnExistingBranch() = runTest(accounts.context) {
        val commit = "a".repeat(40)
        repeat(2) { server.enqueue(MockResponse(body = response(blob, branch = null, commit = commit, permission = "WRITE"))) }
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
            val variables = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject.getValue("variables").jsonObject
            assertEquals("README.md", variables.getValue("path").jsonPrimitive.content)
            assertEquals(commit, variables.getValue("revision").jsonPrimitive.content)
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
                server.enqueue(MockResponse(body = response("""{"__typename":"Tree"}""", branch = null, commit = commit)))
                assertEquals(RepoPathContent.Directory,
                    (repository.getPathContent(link.owner, link.name, link.ref, path) as ApiResult.Success).data)
                val variables = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject.getValue("variables").jsonObject
                assertEquals("docs", variables.getValue("path").jsonPrimitive.content)
            assertEquals(commit, variables.getValue("revision").jsonPrimitive.content)
            }
            val target = """{"__typename":"Tree","entries":[
                {"name":"guide.md","path":"docs/guide.md","type":"blob","oid":"blob","size":7,"extension":".md","language":null}
            ]}"""
            server.enqueue(MockResponse(body = response(target, branch = null, commit = commit, permission = "ADMIN")))
            val tree = (repository.getCodeTree(link.owner, link.name, link.ref, path) as ApiResult.Success).data
            assertEquals(commit, tree.ref)
            assertEquals(commit, tree.headOid)
            assertTrue(tree.isCommitSnapshot)
            assertTrue(tree.capabilities.canPush)
            val treeVariables = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject.getValue("variables").jsonObject
            assertEquals("docs", treeVariables.getValue("path").jsonPrimitive.content)
            assertEquals(commit, treeVariables.getValue("revision").jsonPrimitive.content)

            server.enqueue(MockResponse(body = response(blob, branch = null, commit = commit)))
            val file = (repository.getFileContent(link.owner, link.name, tree.ref, tree.entries.single().path) as ApiResult.Success).data
            assertEquals(commit, file.ref)
            assertTrue(file.isCommitSnapshot)
            val fileVariables = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject.getValue("variables").jsonObject
            assertEquals("docs/guide.md", fileVariables.getValue("path").jsonPrimitive.content)
            assertEquals(commit, fileVariables.getValue("revision").jsonPrimitive.content)
        }
        assertEquals(5, server.requestCount)
    }

    @Test fun commitRootAndNestedDirectoriesKeepTheSameCommit() = runTest(accounts.context) {
        val commit = "b".repeat(40)
        for (path in listOf("", "docs", "docs/nested")) {
            server.enqueue(MockResponse(body = response("""{"__typename":"Tree","entries":[]}""", branch = null, commit = commit)))
            val tree = (repository.getCodeTree("owner", "repo", commit, path) as ApiResult.Success).data
            assertEquals(commit, tree.ref)
            assertEquals(path, tree.path)
            assertTrue(tree.isCommitSnapshot)
            val variables = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject.getValue("variables").jsonObject
            assertEquals(path, variables.getValue("path").jsonPrimitive.content)
            assertEquals(commit, variables.getValue("revision").jsonPrimitive.content)
        }
    }

    @Test fun branchTreeKeepsWritableBranchAndHeadMetadata() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = response("""{"__typename":"Tree","entries":[]}""", permission = "WRITE")))
        val tree = (repository.getCodeTree("owner", "repo", "feature/docs", "docs") as ApiResult.Success).data
        assertEquals("feature/docs", tree.ref)
        assertEquals("head", tree.headOid)
        assertFalse(tree.isCommitSnapshot)
        assertTrue(tree.capabilities.canPush)
    }

    @Test fun absentCommitAndBranchRemainFailuresEvenWithAPathObject() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = response(blob, branch = null)))
        assertTrue(repository.getPathContent("owner", "repo", "missing", "README.md") is ApiResult.Failure)
        server.enqueue(MockResponse(body = response("""{"__typename":"Tree","entries":[]}""", branch = null)))
        assertTrue(repository.getCodeTree("owner", "repo", "missing", "docs") is ApiResult.Failure)
    }

    @Test fun resolvedBranchKeepsItsContentWhenSameNamedTagHasDifferentContent() = runTest(accounts.context) {
        for (branch in listOf("collision", "repro/collision", "deadbee")) {
            server.enqueue(MockResponse(body = """{"data":{"repository":{
                "branch":{"target":{"__typename":"Commit","oid":"branch-head"}},
                "tag":{"target":{"__typename":"Commit","oid":"tag-head"}}
            }}}"""))
            val link = (repository.resolveLink("owner", "repo", RepoLinkKind.FILE,
                branch.split('/') + "ref-collision.txt") as ApiResult.Success).data
            server.takeRequest()
            val branchBlob = blob.replace("# Guide", "BRANCH_SIDE")
            val tagBlob = blob.replace("# Guide", "TAG_SIDE")
            repeat(2) {
                server.enqueue(MockResponse(body = response(branchBlob, branch = branch, commit = "tag-head", permission = "WRITE", snapshotTarget = tagBlob)))
            }
            val preview = (repository.getFileContent("owner", "repo", link.ref, link.path) as ApiResult.Success).data
            val editor = (repository.getFileContent("owner", "repo", preview.ref, preview.path) as ApiResult.Success).data
            assertEquals("BRANCH_SIDE", preview.text)
            assertEquals(preview, editor)
            assertEquals(branch, preview.ref)
            assertEquals("head", preview.headOid)
            assertFalse(preview.isCommitSnapshot)
            repeat(2) {
                val variables = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject.getValue("variables").jsonObject
                assertEquals("refs/heads/$branch", variables.getValue("qualifiedRef").jsonPrimitive.content)
                assertEquals("ref-collision.txt", variables.getValue("path").jsonPrimitive.content)
            }
        }
    }

    @Test fun abbreviatedCommitRemainsReadableWhenThereIsNoMatchingBranch() = runTest(accounts.context) {
        val oid = "deadbee" + "a".repeat(33)
        server.enqueue(MockResponse(body = response(blob, branch = null, commit = oid)))
        val file = (repository.getFileContent("owner", "repo", "deadbee", "README.md") as ApiResult.Success).data
        assertEquals("# Guide", file.text)
        assertEquals(oid, file.headOid)
        assertEquals("deadbee", file.ref)
        assertTrue(file.isCommitSnapshot)
    }

    @Test fun resolvedBranchDirectoryAndHistoryKeepBranchIdentity() = runTest(accounts.context) {
        for (branch in listOf("collision", "repro/collision")) {
            server.enqueue(MockResponse(body = """{"data":{"repository":{
                "branch":{"target":{"__typename":"Commit","oid":"head"}},
                "tag":{"target":{"__typename":"Commit","oid":"tag-head"}}
            }}}"""))
            val link = (repository.resolveLink("owner", "repo", RepoLinkKind.DIRECTORY, branch.split('/')) as ApiResult.Success).data
            server.takeRequest()
            val branchTree = """{"__typename":"Tree","entries":[
                {"name":"branch.txt","path":"branch.txt","type":"blob","oid":"branch-blob","size":12,"extension":".txt","language":null}
            ]}"""
            server.enqueue(MockResponse(body = response(branchTree, branch = branch, commit = "tag-head",
                snapshotTarget = """{"__typename":"Tree","entries":[]}""")))
            val tree = (repository.getCodeTree("owner", "repo", link.ref, link.path) as ApiResult.Success).data
            assertEquals("branch.txt", tree.entries.single().path)
            assertEquals("head", tree.headOid)
            assertEquals(branch, tree.ref)
            assertFalse(tree.isCommitSnapshot)
            val treeVariables = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject.getValue("variables").jsonObject
            assertEquals("refs/heads/$branch", treeVariables.getValue("qualifiedRef").jsonPrimitive.content)
            assertEquals("", treeVariables.getValue("path").jsonPrimitive.content)
            server.enqueue(MockResponse(body = """{"data":{"repository":{
                "id":"R_repo","viewerPermission":"WRITE","isArchived":false,
                "ref":{"name":"$branch","target":{"__typename":"Commit","oid":"head","history":{
                    "totalCount":0,"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}
                }}}
            }}}"""))
            val history = (repository.getCommitHistory("owner", "repo", tree.ref) as ApiResult.Success).data
            assertEquals(tree.ref, history.ref)
            assertEquals(tree.headOid, history.headOid)
            val historyVariables = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject.getValue("variables").jsonObject
            assertEquals("refs/heads/$branch", historyVariables.getValue("qualifiedRef").jsonPrimitive.content)
        }
    }

    @Test fun missingBranchFileNeverFallsBackToSameNamedTagContent() = runTest(accounts.context) {
        val body = response("null", commit = "tag-head", snapshotTarget = blob)
        server.enqueue(MockResponse(body = body))
        assertTrue(repository.getFileContent("owner", "repo", "feature/docs", "only-in-tag.md") is ApiResult.Failure)
    }

    @Test fun commitSerializesHeadlineAndMultilineBodySeparately() = runTest(accounts.context) {
        server.enqueue(MockResponse(body = commitResponse))
        val body = "First paragraph\n\n第二段\n- detail\n"
        val result = repository.createCommit("owner", "repo", "main", "Update README.md",
            emptyList(), emptyList(), "head", body = body)
        assertTrue(result is ApiResult.Success)
        val input = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8())
            .jsonObject.getValue("variables").jsonObject.getValue("input").jsonObject
        val message = input.getValue("message").jsonObject
        assertEquals("Update README.md", message.getValue("headline").jsonPrimitive.content)
        assertEquals(body, message.getValue("body").jsonPrimitive.content)
        assertEquals("head", input.getValue("expectedHeadOid").jsonPrimitive.content)
        assertEquals("main", input.getValue("branch").jsonObject.getValue("branchName").jsonPrimitive.content)
    }

    @Test fun titleOnlyCommitCallersStillOmitTheOptionalBody() = runTest(accounts.context) {
        repeat(2) { server.enqueue(MockResponse(body = commitResponse)) }
        assertTrue(repository.createCommit("owner", "repo", "main", "Delete a.txt",
            emptyList(), emptyList(), "head") is ApiResult.Success)
        assertTrue(repository.createCommit("owner", "repo", "main", "Update a.txt",
            emptyList(), emptyList(), "head", body = "  \n") is ApiResult.Success)
        repeat(2) {
            val input = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8())
                .jsonObject.getValue("variables").jsonObject.getValue("input").jsonObject
            assertFalse(input.getValue("message").jsonObject.containsKey("body"))
        }
    }

    private val commitResponse = """{"data":{"createCommitOnBranch":{"commit":{
        "__typename":"Commit","oid":"new-head","abbreviatedOid":"new",
        "messageHeadline":"Update README.md","messageBody":"",
        "authoredDate":"2026-11-23T00:00:00Z","committedDate":"2026-11-23T00:00:00Z",
        "author":null,"committer":null,"additions":1,"deletions":0,"changedFilesIfAvailable":1,
        "url":"https://github.com/owner/repo/commit/new-head"
    }}}}"""

    private val blob = """{"__typename":"Blob","oid":"blob","byteSize":7,"isBinary":false,"isTruncated":false,"text":"# Guide"}"""

    private fun response(target: String, branch: String? = "feature/docs", commit: String? = null, permission: String = "READ", snapshotTarget: String = target): String {
        val refJson = branch?.let { """{"name":"$it","target":{"__typename":"Commit","oid":"head","file":{"object":$target}}}""" } ?: "null"
        val commitJson = commit?.let { """{"__typename":"Commit","oid":"$it","file":{"object":$snapshotTarget}}""" } ?: "null"
        return """
            {"data":{"repository":{
              "id":"R_repo","viewerPermission":"$permission","isArchived":false,"isFork":false,
              "ref":$refJson,"commit":$commitJson
            }}}
        """.trimIndent()
    }
}
