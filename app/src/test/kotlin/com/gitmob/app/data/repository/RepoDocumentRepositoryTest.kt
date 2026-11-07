package com.gitmob.app.data.repository

import com.gitmob.app.core.auth.AccessTokenProvider
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.ui.common.RepoDocumentResources
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.testutil.AccountSessionRule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RepoDocumentRepositoryTest {
    @get:Rule val accounts = AccountSessionRule()
    private lateinit var server: MockWebServer
    private lateinit var api: GHApiClient
    private lateinit var repository: RepoDocumentRepository
    private val updates = RepoUpdateEventBus()
    private val document = RepoDocumentContext("owner", "repo", "feature/中文 docs", "README.md")

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        api = GHApiClient(OkHttpClient(), Json, object : AccessTokenProvider {
            override suspend fun getToken() = "test-token"
        }, server.url("/").toString().removeSuffix("/"))
        repository = RepoDocumentRepository(api, updates)
    }
    @After fun cleanup() { server.shutdown() }

    @Test fun `loads raw binary using encoded repository path and ref with account credentials`() = runTest(accounts.context) {
        val bytes = byteArrayOf(0, -1, 10, 127, -128)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val result = repository.load(document, "docs/图片 100%+.png") as ApiResult.Success
        assertArrayEquals(bytes, result.data.bytes)
        assertEquals("image/png", result.data.mimeType)
        val request = server.takeRequest()
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("application/vnd.github.raw+json", request.getHeader("Accept"))
        assertEquals("2026-03-10", request.getHeader("X-GitHub-Api-Version"))
        assertEquals(document.ref, request.requestUrl!!.queryParameter("ref"))
        assertEquals(listOf("repos", "owner", "repo", "contents", "docs", "图片 100%+.png"), request.requestUrl!!.pathSegments)
    }

    @Test fun `SVG is served with an image MIME type and missing images fail normally`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("<svg/>"))
        assertEquals("image/svg+xml", (repository.load(document, "assets/logo.svg") as ApiResult.Success).data.mimeType)
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        assertTrue(repository.load(document, "missing.png") is ApiResult.Failure)
    }

    @Test fun `streaming byte limit rejects oversized chunked response before reading it all`() = runTest(accounts.context) {
        server.enqueue(MockResponse().setChunkedBody("123456789", 2))
        var rejected = false
        try { api.getRawBytes("/large", "application/octet-stream", maxBytes = 4) }
        catch (_: java.io.IOException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun publicImageRequestsNeverSendAccountHeadersIncludingRedirects() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/actual.png")))
        server.enqueue(MockResponse().setBody("image-bytes"))
        val result = repository.loadImage(server.url("/redirect.png").toString(), document) as ApiResult.Success
        assertEquals("image-bytes", result.data.bytes.decodeToString())
        repeat(2) {
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-GitHub-Api-Version"))
            assertEquals("image/*", request.getHeader("Accept"))
        }
    }

    @Test fun relativeImageSourcesUseRepositoryContext() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("image"))
        val result = repository.loadImage("../images/photo.png", document.copy(filePath = "docs/guide.md"))
        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("/repos/owner/repo/contents/images/photo.png", request.requestUrl!!.encodedPath)
        assertEquals(document.ref, request.requestUrl!!.queryParameter("ref"))
    }

    @Test fun dataImagesDecodeLocallyAndPreservePlusSigns() = runTest(accounts.context) {
        val base64 = repository.loadImage("data:image/png;base64,AP8Kfw==", null) as ApiResult.Success
        assertArrayEquals(byteArrayOf(0, -1, 10, 127), base64.data.bytes)
        val svg = repository.loadImage("data:image/svg+xml,%3Csvg%3Ea+b%3C%2Fsvg%3E", document) as ApiResult.Success
        assertEquals("<svg>a+b</svg>", svg.data.bytes.decodeToString())
        assertEquals("image/svg+xml", svg.data.mimeType)
        assertEquals(0, server.requestCount)
    }

    @Test fun base64DataImagesAcceptOptionalPadding() = runTest(accounts.context) {
        listOf("", "AA==", "AP8=", "AP8K", "AP8Kfw==").forEach { encoded ->
            val expected = java.util.Base64.getDecoder().decode(encoded)
            listOf(encoded, encoded.trimEnd('=')).forEach { payload ->
                val result = repository.loadImage("data:image/png;base64,$payload", null) as ApiResult.Success
                assertArrayEquals(expected, result.data.bytes)
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun base64DataImagesAcceptExactByteLimitWithAndWithoutPadding() = runTest(accounts.context) {
        // Both one- and two-character padding must be excluded from the decoded size.
        listOf(MAX_DOCUMENT_RESOURCE_BYTES - 1, MAX_DOCUMENT_RESOURCE_BYTES).forEach { size ->
            val expected = ByteArray(size) { it.toByte() }
            val encoded = java.util.Base64.getEncoder().encodeToString(expected)
            listOf(encoded, encoded.trimEnd('=')).forEach { payload ->
                val result = repository.loadImage("data:image/png;base64,$payload", null) as ApiResult.Success
                assertArrayEquals(expected, result.data.bytes)
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun base64DataImagesRejectOutputBeyondByteLimit() = runTest(accounts.context) {
        listOf(MAX_DOCUMENT_RESOURCE_BYTES + 1, MAX_DOCUMENT_RESOURCE_BYTES + 2).forEach { size ->
            val encoded = java.util.Base64.getEncoder().encodeToString(ByteArray(size))
            listOf(encoded, encoded.trimEnd('=')).forEach { payload ->
                val result = repository.loadImage("data:image/png;base64,$payload", null) as ApiResult.Failure
                assertEquals(com.gitmob.app.core.error.ApiError.UserVisible(com.gitmob.app.R.string.image_preview_too_large), result.error)
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun percentEncodedDataImagesPreserveBinaryAndUtf8Bytes() = runTest(accounts.context) {
        val result = repository.loadImage("data:image/svg+xml,+é中文😀%2B%00%fF%E4%B8%AD%25", null) as ApiResult.Success
        val expected = "+é中文😀+".toByteArray() + byteArrayOf(0, -1) + "中%".toByteArray()
        assertArrayEquals(expected, result.data.bytes)
        assertEquals(0, server.requestCount)
    }

    @Test fun percentEncodedDataImagesAcceptExactByteLimit() = runTest(accounts.context) {
        val result = repository.loadImage("data:image/png," + "%FF".repeat(MAX_DOCUMENT_RESOURCE_BYTES), null) as ApiResult.Success
        assertEquals(MAX_DOCUMENT_RESOURCE_BYTES, result.data.bytes.size)
        assertTrue(result.data.bytes.all { it == (-1).toByte() })
        assertEquals(0, server.requestCount)
    }

    @Test fun literalPlusDataImagesAcceptExactByteLimit() = runTest(accounts.context) {
        val result = repository.loadImage("data:image/svg+xml," + "+".repeat(MAX_DOCUMENT_RESOURCE_BYTES), null) as ApiResult.Success
        assertEquals(MAX_DOCUMENT_RESOURCE_BYTES, result.data.bytes.size)
        assertTrue(result.data.bytes.all { it == '+'.code.toByte() })
        assertEquals(0, server.requestCount)
    }

    @Test fun percentEncodedDataImagesRejectOutputBeyondByteLimit() = runTest(accounts.context) {
        val result = repository.loadImage("data:image/png," + "%00".repeat(MAX_DOCUMENT_RESOURCE_BYTES + 1), null) as ApiResult.Failure
        assertEquals(com.gitmob.app.core.error.ApiError.UserVisible(com.gitmob.app.R.string.image_preview_too_large), result.error)
        assertEquals(0, server.requestCount)
    }

    @Test fun literalPlusDataImagesRejectOversizedPayloads() = runTest(accounts.context) {
        listOf(MAX_DOCUMENT_RESOURCE_BYTES + 1, MAX_DOCUMENT_RESOURCE_BYTES * 3).forEach { size ->
            val result = repository.loadImage("data:image/svg+xml," + "+".repeat(size), null) as ApiResult.Failure
            assertEquals(com.gitmob.app.core.error.ApiError.UserVisible(com.gitmob.app.R.string.image_preview_too_large), result.error)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun unicodeDataImageLimitCountsUtf8Bytes() = runTest(accounts.context) {
        val text = "é中😀".repeat(MAX_DOCUMENT_RESOURCE_BYTES / 9) + "+".repeat(MAX_DOCUMENT_RESOURCE_BYTES % 9)
        val result = repository.loadImage("data:image/svg+xml,$text", null) as ApiResult.Success
        assertEquals(MAX_DOCUMENT_RESOURCE_BYTES, result.data.bytes.size)
        assertArrayEquals(text.toByteArray(), result.data.bytes)
        val oversized = repository.loadImage("data:image/svg+xml,$text+", null) as ApiResult.Failure
        assertEquals(com.gitmob.app.core.error.ApiError.UserVisible(com.gitmob.app.R.string.image_preview_too_large), oversized.error)
        assertEquals(0, server.requestCount)
    }

    @Test fun unsafeAndMalformedSourcesFailWithoutNetworkAccess() = runTest(accounts.context) {
        listOf("file:///secret.png", "content://private/photo", "javascript:alert(1)",
            "https://user:password@example.test/image.png", "https://repo-docs.gitmob.invalid/image.png",
            "data:image/png;base64,not base64", "data:image/png",
            "data:image/png;base64,A", "data:image/png;base64,A===",
            "data:image/png;base64,AP8Kfw=", "data:image/png;base64,AP8Kfw===",
            "data:image/png;base64,AP8Kfw==AA",
            "data:image/png,%", "data:image/png,%0", "data:image/png,%GG", "data:image/png,%-1").forEach {
            assertTrue(it, repository.loadImage(it, null) is ApiResult.Failure)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun serverDeclaredOversizeShowsLimitInsteadOfNetworkError() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", MAX_DOCUMENT_RESOURCE_BYTES + 1))
        val result = repository.loadImage(server.url("/large.png").toString(), null) as ApiResult.Failure
        assertEquals(com.gitmob.app.core.error.ApiError.UserVisible(com.gitmob.app.R.string.image_preview_too_large), result.error)
    }

    @Test fun relativeRawAndFilePreviewShareCachedBytes() = runTest(accounts.context) {
        server.enqueue(MockResponse().setBody("image"))
        val first = repository.loadImage("images/a.png", document) as ApiResult.Success
        val raw = "https://raw.githubusercontent.com/owner/repo/feature/%E4%B8%AD%E6%96%87%20docs/images/a.png"
        val qualified = repository.loadImage(raw.replace("/feature/", "/refs/heads/feature/"), document) as ApiResult.Success
        assertSame(first.data, qualified.data)
        val viewer = repository.loadImage(raw, document) as ApiResult.Success
        val file = repository.load(document.copy(filePath = "images/a.png"), "images/a.png") as ApiResult.Success
        assertSame(first.data, viewer.data)
        assertSame(first.data, file.data)
        assertEquals(1, server.requestCount)
    }

    @Test fun repositoryAndRefArePartOfTheCacheIdentity() = runTest(accounts.context) {
        repeat(4) { server.enqueue(MockResponse().setBody("image-$it")) }
        val initial = repository.load(document, "a.png")
        assertEquals(initial, repository.load(document.copy(owner = "OWNER", name = "REPO"), "a.png"))
        repository.load(document.copy(ref = "other"), "a.png")
        repository.load(document.copy(name = "other"), "a.png")
        repository.load(document, "A.png")
        assertEquals(4, server.requestCount)
    }

    @Test fun failuresAreNotCachedAndRetryCanRecover() = runTest(accounts.context) {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("recovered"))
        assertTrue(repository.load(document, "a.png") is ApiResult.Failure)
        assertEquals("recovered", (repository.load(document, "a.png") as ApiResult.Success).data.bytes.decodeToString())
        assertEquals(2, server.requestCount)
    }

    @Test fun concurrentReadersShareDownloadAndCancellingOneDoesNotCancelOthers() = runBlocking(accounts.context) {
        withTimeout(5_000) {
            val mockApi = mockk<GHApiClient>()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { mockApi.getRawBytes(any(), any(), any()) } coAnswers {
                started.complete(Unit); release.await(); byteArrayOf(1, 2)
            }
            val shared = RepoDocumentRepository(mockApi, updates)
            val first = async(start = CoroutineStart.UNDISPATCHED) { shared.load(document, "a.png") }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { shared.load(document, "a.png") }
            first.cancel()
            first.join()
            release.complete(Unit)
            val result = second.await() as ApiResult.Success
            assertSame(result.data, (shared.load(document, "a.png") as ApiResult.Success).data)
            coVerify(exactly = 1) { mockApi.getRawBytes(any(), any(), any()) }
        }
    }

    @Test fun closingDocumentPageDoesNotCancelViewerDownload() = runBlocking(accounts.context) {
        withTimeout(5_000) {
            val mockApi = mockk<GHApiClient>()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { mockApi.getRawBytes(any(), any(), any()) } coAnswers {
                started.complete(Unit); release.await(); byteArrayOf(1)
            }
            val shared = RepoDocumentRepository(mockApi, updates)
            val page = RepoDocumentResources(document, accounts.session.epoch, shared)
            val pageRead = async { page.load("a.png") }
            started.await()
            val viewer = async(start = CoroutineStart.UNDISPATCHED) { shared.loadImage("a.png", document) }
            page.close()
            try { pageRead.await(); fail("Expected page cancellation") } catch (_: CancellationException) { }
            release.complete(Unit)
            assertTrue(viewer.await() is ApiResult.Success)
            coVerify(exactly = 1) { mockApi.getRawBytes(any(), any(), any()) }
        }
    }

    @Test fun invalidatedDownloadCannotOverwriteRefreshedBytes() = runBlocking(accounts.context) {
        withTimeout(5_000) {
            val mockApi = mockk<GHApiClient>()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            coEvery { mockApi.getRawBytes(any(), any(), any()) } coAnswers {
                if (calls.incrementAndGet() == 1) {
                    started.complete(Unit); release.await(); "old".toByteArray()
                } else "new".toByteArray()
            }
            val shared = RepoDocumentRepository(mockApi, updates)
            val old = async { shared.load(document, "a.png") }
            started.await()
            shared.invalidate(document)
            val fresh = shared.load(document, "a.png") as ApiResult.Success
            release.complete(Unit)
            old.await()
            assertEquals("new", fresh.data.bytes.decodeToString())
            assertSame(fresh.data, (shared.load(document, "a.png") as ApiResult.Success).data)
            assertEquals(2, calls.get())
        }
    }

    @Test fun accountReplacementClearsCacheAndCancelsOldDownload() = runBlocking {
        val mockApi = mockk<GHApiClient>()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        coEvery { mockApi.getRawBytes(any(), any(), any()) } coAnswers {
            if ("pending.png" in firstArg<String>()) {
                started.complete(Unit)
                try { CompletableDeferred<ByteArray>().await() } finally { stopped.complete(Unit) }
            } else byteArrayOf(1)
        }
        val shared = RepoDocumentRepository(mockApi, updates)
        withTimeout(5_000) {
            val oldContext = accounts.context
            withContext(oldContext) { shared.load(document, "cached.png") }
            val pending = async(oldContext) { shared.load(document, "pending.png") }
            started.await()
            accounts.session.replace(emptyList(), null)
            stopped.await()
            try { pending.await(); fail("Expected account cancellation") } catch (_: CancellationException) { }
            withContext(accounts.context) { shared.load(document, "cached.png") }
            coVerify(exactly = 2) { mockApi.getRawBytes(match { "cached.png" in it }, any(), any()) }
        }
    }

    @Test fun cacheEvictsLeastRecentlyUsedResourcesWithinByteAndEntryBudgets() = runBlocking(accounts.context) {
        val mockApi = mockk<GHApiClient>()
        coEvery { mockApi.getRawBytes(any(), any(), any()) } answers { ByteArray(6 * 1024 * 1024) }
        val shared = RepoDocumentRepository(mockApi, updates)
        listOf("1.png", "2.png", "1.png", "3.png", "2.png").forEach { shared.load(document, it) }
        coVerify(exactly = 1) { mockApi.getRawBytes(match { "1.png" in it }, any(), any()) }
        coVerify(exactly = 2) { mockApi.getRawBytes(match { "2.png" in it }, any(), any()) }
        shared.invalidate(document)
        coEvery { mockApi.getRawBytes(any(), any(), any()) } returns byteArrayOf(1)
        repeat(65) { shared.load(document, "small-$it.png") }
        shared.load(document, "small-0.png")
        coVerify(exactly = 2) { mockApi.getRawBytes(match { "small-0.png" in it }, any(), any()) }
    }

    @Test fun downloadsAcrossPagesHaveAtMostFourConcurrentRequests() = runBlocking(accounts.context) {
        withTimeout(5_000) {
            val mockApi = mockk<GHApiClient>()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val fourStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { mockApi.getRawBytes(any(), any(), any()) } coAnswers {
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                if (count == 4) fourStarted.complete(Unit)
                try { release.await(); byteArrayOf(1) } finally { active.decrementAndGet() }
            }
            val shared = RepoDocumentRepository(mockApi, updates)
            val requests = (1..8).map { index -> async(start = CoroutineStart.UNDISPATCHED) { shared.load(document, "$index.png") } }
            fourStarted.await()
            assertEquals(4, active.get())
            release.complete(Unit)
            requests.awaitAll()
            assertEquals(4, peak.get())
            coVerify(exactly = 8) { mockApi.getRawBytes(any(), any(), any()) }
        }
    }

    @Test fun fileUpdateInvalidatesOnlyChangedImagesEvenAfterPageCloses() = runBlocking(accounts.context) {
        withTimeout(5_000) {
            val mockApi = mockk<GHApiClient>()
            coEvery { mockApi.getRawBytes(any(), any(), any()) } returns "old".toByteArray()
            val shared = RepoDocumentRepository(mockApi, updates)
            RepoDocumentResources(document, accounts.session.epoch, shared).use { it.load("a.png") }
            val unchanged = shared.load(document, "b.png") as ApiResult.Success
            coEvery { mockApi.getRawBytes(any(), any(), any()) } returns "new".toByteArray()
            updates.emit(RepoUpdateEvent.CodeChanged(document.owner, document.name, document.ref, "commit", listOf("a.png")))
            while ((shared.load(document, "a.png") as ApiResult.Success).data.bytes.decodeToString() == "old") kotlinx.coroutines.yield()
            assertSame(unchanged.data, (shared.load(document, "b.png") as ApiResult.Success).data)
            updates.emit(RepoUpdateEvent.BranchSynced(document.owner, document.name, document.ref))
            while ((shared.load(document, "b.png") as ApiResult.Success).data.bytes.decodeToString() == "old") kotlinx.coroutines.yield()
            coVerify(exactly = 2) { mockApi.getRawBytes(match { "a.png" in it }, any(), any()) }
            coVerify(exactly = 2) { mockApi.getRawBytes(match { "b.png" in it }, any(), any()) }
        }
    }

}
