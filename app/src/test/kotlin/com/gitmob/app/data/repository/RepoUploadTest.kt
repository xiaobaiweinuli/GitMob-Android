package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.storage.SafFile
import com.gitmob.app.core.storage.SafFileReader
import com.gitmob.app.core.storage.SafSelection
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*

class RepoUploadTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var apollo: ApolloClient
    private lateinit var repository: RepoGitRepository
    private val reader = mockk<SafFileReader>()
    private val selection = SafSelection.Documents(emptyList())
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        apollo = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).okHttpClient(OkHttpClient()).build()
        repository = RepoGitRepository(mockk(), apollo, reader)
    }
    @After fun cleanup() { apollo.close(); server.close() }

    @Test fun allFilesArePreparedBeforeOneCommitWithCapturedBranchPathAndHead() = runTest(accounts.context) {
        coEvery { reader.readEach(selection, any()) } coAnswers {
            val consume = secondArg<suspend (SafFile) -> Unit>()
            consume(SafFile("guide.md", byteArrayOf(1)))
            assertEquals(0, server.requestCount)
            consume(SafFile("nested/empty", byteArrayOf()))
            assertEquals(0, server.requestCount)
        }
        server.enqueue(MockResponse(body = """{"data":{"createCommitOnBranch":{"commit":{"__typename":"Commit","oid":"new-head","abbreviatedOid":"new","messageHeadline":"Upload","messageBody":"","authoredDate":"2026-11-20T00:00:00Z","committedDate":"2026-11-20T00:00:00Z","author":null,"committer":null,"additions":1,"deletions":0,"changedFilesIfAvailable":2,"url":"https://github.com/o/r/commit/new-head"}}}}"""))
        assertTrue(repository.uploadFiles("o", "r", "feature/docs", "docs", "old-head", selection, "Upload") is ApiResult.Success)
        assertEquals(1, server.requestCount)
        val input = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject.getValue("variables").jsonObject.getValue("input").jsonObject
        assertEquals("feature/docs", input.getValue("branch").jsonObject.getValue("branchName").jsonPrimitive.content)
        assertEquals("old-head", input.getValue("expectedHeadOid").jsonPrimitive.content)
        val additions = input.getValue("fileChanges").jsonObject.getValue("additions").jsonArray
        assertEquals("docs/guide.md", additions[0].jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals("AQ==", additions[0].jsonObject.getValue("contents").jsonPrimitive.content)
        assertEquals("docs/nested/empty", additions[1].jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals("", additions[1].jsonObject.getValue("contents").jsonPrimitive.content)
    }

    @Test fun anyPreparationFailurePreventsTheEntireCommit() = runTest(accounts.context) {
        for (reason in listOf(R.string.repo_upload_too_large, R.string.repo_upload_too_many_files, R.string.repo_upload_read_failed, R.string.repo_upload_duplicate_path)) {
            coEvery { reader.readEach(selection, any()) } coAnswers {
                secondArg<suspend (SafFile) -> Unit>()(SafFile("first", byteArrayOf(1)))
                throw UserVisibleException(reason)
            }
            assertEquals(ApiResult.Failure(ApiError.UserVisible(reason)), repository.uploadFiles("o", "r", "main", "", "head", selection, "Upload"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun cancellationPropagatesWithoutCommittingPreparedFiles() = runTest(accounts.context) {
        coEvery { reader.readEach(selection, any()) } coAnswers {
            secondArg<suspend (SafFile) -> Unit>()(SafFile("first", byteArrayOf(1)))
            throw CancellationException("account changed")
        }
        val failure = runCatching { repository.uploadFiles("o", "r", "main", "", "head", selection, "Upload") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, server.requestCount)
    }
}
