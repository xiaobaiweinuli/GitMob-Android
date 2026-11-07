package com.gitmob.app.ui.common

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.gitmob.app.core.markdown.REPO_DOCUMENT_HOST
import com.gitmob.app.core.markdown.RepoDocumentLink
import com.gitmob.app.core.markdown.resolveLink
import com.gitmob.app.core.markdown.imageResourcePath
import com.gitmob.app.data.model.RepoDocumentContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

internal class RepoDocumentWebViewClient(
    private val resources: RepoDocumentResources,
    private val onDocumentLink: (RepoDocumentContext) -> Unit,
    private val onExternalLink: (String) -> Unit,
    private val onPageReady: (WebView) -> Unit = {},
    private val onPageError: (WebView) -> Unit = {},
) : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) { onPageReady(view) }
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onPageError(view)
    }
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) onPageError(view)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        if (request.isForMainFrame) openLink(request.url.toString()) else true

    @Deprecated("Used by older WebView implementations")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = openLink(url)

    internal fun openLink(url: String): Boolean {
        if (!resources.isActive) return true
        return when (val link = resources.document.resolveLink(url)) {
            RepoDocumentLink.Anchor -> false
            RepoDocumentLink.Blocked -> true
            is RepoDocumentLink.File -> { onDocumentLink(link.document); true }
            is RepoDocumentLink.External -> { onExternalLink(link.url); true }
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!resources.isActive) return unavailable()
        val url = request.url
        val path = resources.document.imageResourcePath(url.toString())
        if (path == null && !url.host.equals(REPO_DOCUMENT_HOST, ignoreCase = true)) {
            // External images are handled by WebView without GitHub authentication.
            return if (url.scheme in setOf("https", "http", "data")) null else unavailable()
        }
        if (request.method != "GET" || path == null) return unavailable()
        // WebView calls this method on its worker thread. The cancellable HTTP request runs
        // in the shared repository; closing the page cancels this worker's wait.
        val resource = try { runBlocking { resources.load(path) } }
            catch (_: CancellationException) { null }
        return resource?.let {
            WebResourceResponse(it.mimeType, null, 200, "OK", mapOf("Cache-Control" to "no-store"), it.bytes.inputStream())
        } ?: unavailable()
    }

    private fun unavailable() = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), byteArrayOf().inputStream(),
    )
}
