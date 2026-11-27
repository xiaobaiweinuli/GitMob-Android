package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.data.model.RepoLinkKind
import com.gitmob.app.data.model.RepoLinkTarget
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*

class RepoLinkResolutionTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var apollo: ApolloClient
    private lateinit var repository: RepoGitRepository
    private val oid = "a".repeat(40)
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        apollo = ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).okHttpClient(OkHttpClient()).build()
        repository = RepoGitRepository(mockk(), apollo, mockk())
    }
    @After fun cleanup() { apollo.close(); server.close() }
    private fun refs(branch: String? = null, tag: String? = null) {
        fun ref(type: String?) = type?.let { """{"target":{"__typename":"$it","oid":"$oid"}}""" } ?: "null"
        server.enqueue(MockResponse(body = """{"data":{"repository":{"branch":${ref(branch)},"tag":${ref(tag)}}}}"""))
    }
    private fun variables() = Json.parseToJsonElement(requireNotNull(server.takeRequest().body).utf8()).jsonObject.getValue("variables").jsonObject

    @Test fun comparisonFullShasNeedNoRefQueries() = runTest(accounts.context) {
        val head = "b".repeat(40)
        val refs = (repository.resolveComparisonRefs("o", "r", oid, head) as ApiResult.Success).data
        assertEquals(oid, refs.baseRef)
        assertEquals(head, refs.headRef)
        assertEquals(0, server.requestCount)
    }

    @Test fun comparisonBranchesWinCollisionsAndUseExactSlashNames() = runTest(accounts.context) {
        refs(branch = "Commit", tag = "Tag")
        val refs = (repository.resolveComparisonRefs("o", "r", "feature/docs", "feature/docs") as ApiResult.Success).data
        assertEquals(oid, refs.baseRef)
        assertEquals(oid, refs.headRef)
        val vars = variables()
        assertEquals("refs/heads/feature/docs", vars.getValue("branch").jsonPrimitive.content)
        assertEquals("refs/tags/feature/docs", vars.getValue("tag").jsonPrimitive.content)
        assertEquals(1, server.requestCount)
    }

    @Test fun comparisonExplicitTagsOverrideBranchesAndPeelAnnotatedTags() = runTest(accounts.context) {
        for (type in listOf("Commit", "Tag")) {
            refs(branch = "Commit", tag = type)
            val tagOid = "b".repeat(40)
            server.enqueue(MockResponse(body = """{"data":{"repository":{"object":{"__typename":"Commit","oid":"$tagOid"}}}}"""))
            val resolved = (repository.resolveComparisonRefs("o", "r", oid, "tags/release/v1") as ApiResult.Success).data
            assertEquals(oid, resolved.baseRef)
            assertEquals(tagOid, resolved.headRef)
            assertEquals("refs/tags/release/v1", variables().getValue("tag").jsonPrimitive.content)
            assertEquals("refs/tags/release/v1^{commit}", variables().getValue("expression").jsonPrimitive.content)
        }
    }

    @Test fun comparisonShortShaResolvesAfterExactRefLookup() = runTest(accounts.context) {
        refs()
        server.enqueue(MockResponse(body = """{"data":{"repository":{"object":{"__typename":"Commit","oid":"$oid"}}}}"""))
        val resolved = (repository.resolveComparisonRefs("o", "r", "abcd123", oid) as ApiResult.Success).data
        assertEquals(oid, resolved.baseRef)
        assertEquals("refs/heads/abcd123", variables().getValue("branch").jsonPrimitive.content)
        assertEquals("abcd123^{commit}", variables().getValue("expression").jsonPrimitive.content)
    }

    @Test fun comparisonOrdinaryTagUsesQualifiedTagExpression() = runTest(accounts.context) {
        refs(tag = "Tag")
        server.enqueue(MockResponse(body = """{"data":{"repository":{"object":{"__typename":"Commit","oid":"$oid"}}}}"""))
        assertTrue(repository.resolveComparisonRefs("o", "r", "v1.0", oid) is ApiResult.Success)
        variables()
        assertEquals("refs/tags/v1.0^{commit}", variables().getValue("expression").jsonPrimitive.content)
    }

    @Test fun comparisonMissingRefDoesNotScanShorterNamesOrResolveTheOtherEnd() = runTest(accounts.context) {
        refs()
        assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.deep_link_ref_not_found)),
            repository.resolveComparisonRefs("o", "r", "feature/missing", "main"))
        assertEquals(1, server.requestCount)
    }

    @Test fun comparisonApiFailureDoesNotFallbackToCommitExpression() = runTest(accounts.context) {
        server.enqueue(MockResponse(code = 403, body = "forbidden"))
        assertTrue(repository.resolveComparisonRefs("o", "r", "abcd123", oid) is ApiResult.Failure)
        assertEquals(1, server.requestCount)
    }

    @Test fun comparisonAmbiguousOrMissingShortShaRemainsFailure() = runTest(accounts.context) {
        refs()
        server.enqueue(MockResponse(body = """{"data":{"repository":{"object":null}}}"""))
        assertTrue(repository.resolveComparisonRefs("o", "r", "abcd", oid) is ApiResult.Failure)
        assertEquals(2, server.requestCount)
    }

    @Test fun longestExactRefWinsAndUnmatchedSuffixBecomesPath() = runTest(accounts.context) {
        refs()
        refs(branch = "Commit")
        assertEquals(ApiResult.Success(RepoLinkTarget("feature/docs", "src/App.kt")),
            repository.resolveLink("o", "r", RepoLinkKind.FILE, listOf("feature", "docs", "src", "App.kt")))
        assertEquals("refs/heads/feature/docs/src", variables().getValue("branch").jsonPrimitive.content)
        val matched = variables()
        assertEquals("refs/heads/feature/docs", matched.getValue("branch").jsonPrimitive.content)
        assertEquals("refs/tags/feature/docs", matched.getValue("tag").jsonPrimitive.content)
        assertEquals(2, server.requestCount)
    }

    @Test fun encodedSlashIsKeptInsideAnIndivisibleRefSegment() = runTest(accounts.context) {
        refs(branch = "Commit")
        assertEquals(ApiResult.Success(RepoLinkTarget("feature/docs", "guide.md")),
            repository.resolveLink("o", "r", RepoLinkKind.FILE, listOf("feature/docs", "guide.md")))
        assertEquals("refs/heads/feature/docs", variables().getValue("branch").jsonPrimitive.content)
    }

    @Test fun branchWinsOverSameNamedTagAndDirectoryCanHaveEmptyPath() = runTest(accounts.context) {
        refs(branch = "Commit", tag = "Tag")
        assertEquals(ApiResult.Success(RepoLinkTarget("feature/docs", "")),
            repository.resolveLink("o", "r", RepoLinkKind.DIRECTORY, listOf("feature", "docs")))
        assertEquals(1, server.requestCount)
    }

    @Test fun tagsArePeeledToReadOnlyCommitTargets() = runTest(accounts.context) {
        for (type in listOf("Commit", "Tag")) {
            refs(tag = type)
            server.enqueue(MockResponse(body = """{"data":{"repository":{"object":{"__typename":"Commit","oid":"$oid"}}}}"""))
            assertEquals(ApiResult.Success(RepoLinkTarget(oid, "guide.md")),
                repository.resolveLink("o", "r", RepoLinkKind.FILE, listOf("release", "v1", "guide.md")))
            variables()
            assertEquals("refs/tags/release/v1^{commit}", variables().getValue("expression").jsonPrimitive.content)
        }
    }

    @Test fun missingReferencesNeverGuessTheFirstSegment() = runTest(accounts.context) {
        repeat(2) { refs() }
        assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.deep_link_ref_not_found)),
            repository.resolveLink("o", "r", RepoLinkKind.FILE, listOf("feature", "docs", "guide.md")))
        assertEquals(2, server.requestCount)
    }

    @Test fun apiFailuresDoNotFallbackToShorterCandidates() = runTest(accounts.context) {
        server.enqueue(MockResponse(code = 403, body = "forbidden"))
        assertTrue(repository.resolveLink("o", "r", RepoLinkKind.DIRECTORY, listOf("feature", "docs")) is ApiResult.Failure)
        assertEquals(1, server.requestCount)
    }

    @Test fun fullShaNeedsNoRefQueryAndHistoryDoesNotBecomeAWritableBranch() = runTest(accounts.context) {
        assertEquals(ApiResult.Success(RepoLinkTarget(oid, "docs/a.md")),
            repository.resolveLink("o", "r", RepoLinkKind.FILE, listOf(oid, "docs", "a.md")))
        assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.deep_link_history_unsupported)),
            repository.resolveLink("o", "r", RepoLinkKind.HISTORY, listOf(oid)))
        assertEquals(0, server.requestCount)
        refs(tag = "Tag")
        assertEquals(ApiResult.Failure(ApiError.UserVisible(R.string.deep_link_history_unsupported)),
            repository.resolveLink("o", "r", RepoLinkKind.HISTORY, listOf("v1")))
    }
}
