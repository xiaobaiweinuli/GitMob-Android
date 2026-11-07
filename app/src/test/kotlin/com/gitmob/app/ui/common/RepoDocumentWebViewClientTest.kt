package com.gitmob.app.ui.common

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.markdown.baseUrl
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.repository.RepoDocumentRepository
import com.gitmob.app.data.repository.RepoDocumentResource
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepoDocumentWebViewClientTest {
    @get:Rule val accounts = AccountSessionRule()
    private val document = RepoDocumentContext("owner", "repo", "feature/docs", "docs/guide.md")
    private val repository = mockk<RepoDocumentRepository>()
    private val resources by lazy { RepoDocumentResources(document, accounts.session.epoch, repository) }
    private val documents = mutableListOf<RepoDocumentContext>()
    private val externalLinks = mutableListOf<String>()
    private val client by lazy { RepoDocumentWebViewClient(resources, { documents.add(it) }, { externalLinks.add(it) }) }
    private val view by lazy { WebView(RuntimeEnvironment.getApplication()) }

    @After fun cleanup() { resources.close(); view.destroy() }

    private fun request(url: String, mainFrame: Boolean = false, method: String = "GET") = mockk<WebResourceRequest> {
        every { this@mockk.url } returns Uri.parse(url)
        every { isForMainFrame } returns mainFrame
        every { this@mockk.method } returns method
    }

    @Test fun `resolved document links open previews with the original repository and slash ref`() {
        val url = document.baseUrl().resolve("../translations/翻译本页.md")!!.toString()
        assertTrue(client.shouldOverrideUrlLoading(view, request(url, mainFrame = true)))
        assertEquals(listOf(document.copy(filePath = "translations/翻译本页.md")), documents)
        assertTrue(externalLinks.isEmpty())
        assertFalse(client.openLink("#section"))
    }

    @Test fun `external GitHub Gist and web links reach the existing external router`() {
        val urls = listOf("https://github.com/owner/repo/issues/1", "https://gist.github.com/user/123", "https://example.com/docs")
        urls.forEach { assertTrue(client.openLink(it)) }
        assertEquals(urls, externalLinks)
        assertTrue(documents.isEmpty())
        assertTrue(client.openLink("file:///android_asset/docs/test.md"))
        assertTrue(client.shouldOverrideUrlLoading(view, request(document.baseUrl().toString())))
        assertTrue(documents.isEmpty())
    }

    @Test fun `internal images return unchanged binary bytes with their MIME type`() {
        val bytes = byteArrayOf(0, -1, 10, 127)
        coEvery { repository.load(document, "images/图 100%.png") } returns ApiResult.Success(RepoDocumentResource(bytes, "image/png"))
        val url = document.baseUrl().resolve("../images/图%20100%25.png")!!.toString()
        val response = requireNotNull(client.shouldInterceptRequest(view, request(url)))
        assertEquals(200, response.statusCode)
        assertEquals("image/png", response.mimeType)
        assertEquals("no-store", response.responseHeaders["Cache-Control"])
        assertArrayEquals(bytes, response.data.use { it.readBytes() })
        coVerify(exactly = 1) { repository.load(document, "images/图 100%.png") }
    }

    @Test fun `external images bypass the authenticated repository and local files are blocked`() {
        assertNull(client.shouldInterceptRequest(view, request("https://example.com/image.png")))
        assertEquals(404, client.shouldInterceptRequest(view, request("file:///private/image.png"))!!.statusCode)
        assertEquals(404, client.shouldInterceptRequest(view, request("https://repo-docs.gitmob.invalid:444/image.png"))!!.statusCode)
        assertEquals(404, client.shouldInterceptRequest(view, request(document.baseUrl().toString(), method = "POST"))!!.statusCode)
        coVerify(exactly = 0) { repository.load(any(), any()) }
    }

    @Test fun `closing a page blocks late callbacks and resource requests`() {
        val activeClient = client
        resources.close()
        assertTrue(activeClient.openLink("next.md"))
        assertTrue(activeClient.openLink("https://example.com/"))
        assertTrue(documents.isEmpty())
        assertTrue(externalLinks.isEmpty())
        assertEquals(404, activeClient.shouldInterceptRequest(view, request(document.baseUrl().toString()))!!.statusCode)
        coVerify(exactly = 0) { repository.load(any(), any()) }
    }

    @Test fun `account replacement blocks callbacks from the previous document`() {
        val activeClient = client
        accounts.session.replace(emptyList(), null)
        assertTrue(activeClient.openLink("next.md"))
        assertTrue(activeClient.openLink("https://example.com/"))
        assertTrue(documents.isEmpty())
        assertTrue(externalLinks.isEmpty())
    }

    @Test fun qualifiedRawImagesUseTheSameRepositoryPath() {
        val bytes = byteArrayOf(1, 2, 3)
        coEvery { repository.load(document, "images/a.png") } returns ApiResult.Success(RepoDocumentResource(bytes, "image/png"))
        val url = "https://raw.githubusercontent.com/owner/repo/refs/heads/feature/docs/images/a.png"
        val response = requireNotNull(client.shouldInterceptRequest(view, request(url)))
        assertEquals(200, response.statusCode)
        assertArrayEquals(bytes, response.data.use { it.readBytes() })
        assertNull(client.shouldInterceptRequest(view, request(url.replace("feature/docs", "feature/other"))))
        coVerify(exactly = 1) { repository.load(document, "images/a.png") }
    }

    @Test fun matchingRawImagesUseTheRepositoryWhileOtherRefsRemainExternal() {
        val bytes = byteArrayOf(1, 2, 3)
        coEvery { repository.load(document, "images/a.png") } returns ApiResult.Success(RepoDocumentResource(bytes, "image/png"))
        val url = "https://raw.githubusercontent.com/owner/repo/feature/docs/images/a.png"
        val response = requireNotNull(client.shouldInterceptRequest(view, request(url)))
        assertEquals(200, response.statusCode)
        assertArrayEquals(bytes, response.data.use { it.readBytes() })
        assertNull(client.shouldInterceptRequest(view, request(url.replace("feature/docs", "feature/other"))))
        assertNull(client.shouldInterceptRequest(view, request(url.replace("owner/repo", "other/repo"))))
        assertEquals(404, client.shouldInterceptRequest(view, request(url, method = "POST"))!!.statusCode)
        coVerify(exactly = 1) { repository.load(document, "images/a.png") }
    }


    @Test fun pageReadinessAndOnlyMainFrameFailuresReachDisplayController() {
        var ready = 0
        var failed = 0
        val observing = RepoDocumentWebViewClient(resources, {}, {}, { ready++ }, { failed++ })
        observing.onPageFinished(view, document.baseUrl().toString())
        assertEquals(1, ready)
        observing.onReceivedError(view, request("https://example.com/image.png"), mockk(relaxed = true))
        observing.onReceivedHttpError(view, request("https://example.com/image.png"), mockk(relaxed = true))
        assertEquals(0, failed)
        observing.onReceivedError(view, request(document.baseUrl().toString(), true), mockk(relaxed = true))
        observing.onReceivedHttpError(view, request(document.baseUrl().toString(), true), mockk(relaxed = true))
        assertEquals(2, failed)
    }
}
