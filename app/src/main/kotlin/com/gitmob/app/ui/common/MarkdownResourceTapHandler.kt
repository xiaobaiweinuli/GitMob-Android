package com.gitmob.app.ui.common

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView
import com.gitmob.app.data.model.DocumentResource
import com.gitmob.app.data.model.DocumentResourceGallery
import com.gitmob.app.data.model.DocumentResourceKind
import com.gitmob.app.data.model.resourceKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup

/** Native hit testing preserves ordinary links and distinguishes an image from its enclosing link. */
internal class MarkdownResourceTapHandler(
    private val webView: WebView,
    private val onResource: (DocumentResourceGallery) -> Unit,
) {
    private var documentResources = lazy { emptyList<DocumentResource>() }

    fun setDocument(html: String, baseUrl: String) {
        documentResources = lazy { resourcesFromHtml(html, baseUrl) }
    }

    private val detector = GestureDetector(webView.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            val hit = webView.hitTestResult
            val x = event.x / webView.resources.displayMetrics.density
            val y = event.y / webView.resources.displayMetrics.density
            if (hit.type == WebView.HitTestResult.IMAGE_TYPE) {
                return imageFromHit(hit.type, hit.extra, null, null)?.let { open(it, x, y); true } ?: false
            }
            if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                val resource = documentResources.value.firstOrNull { it.source == hit.extra && it.originalLink == null }
                    ?: return false
                open(resource, x, y)
                return true
            }
            if (hit.type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false
            val page = webView.tag
            val reply = Message.obtain(Handler(Looper.getMainLooper()) { message ->
                if (webView.tag === page && webView.isAttachedToWindow) {
                    imageFromHit(hit.type, hit.extra, message.data.getString("src"), message.data.getString("url"))
                        ?.let { open(it, x, y) }
                }
                true
            })
            webView.requestFocusNodeHref(reply)
            return true
        }
    })

    private fun open(resource: DocumentResource, x: Float, y: Float) {
        if (!webView.settings.javaScriptEnabled) {
            onResource(resourceGallery(documentResources.value, resource))
            return
        }
        val page = webView.tag
        val candidates = JSONArray().apply {
            documentResources.value.filter { it.originalLink == null }.forEach { item ->
                put(JSONObject().put("source", item.source).put("kind", item.kind.name)
                    .put("mimeType", item.mimeType).put("description", item.description))
            }
        }
        val script = """
            (() => {
                const root = document.querySelector('.markdown-body');
                const target = document.elementFromPoint($x, $y)?.closest('img, a[href]');
                if (!root || !target || !root.contains(target)) return null;
                const candidates = $candidates;
                const href = img => img.closest('a[href]')?.href || null;
                const item = el => el.tagName === 'IMG'
                    ? {source: el.currentSrc || el.src, originalLink: href(el), kind: 'IMAGE'}
                    : !el.querySelector('img') && candidates.find(it => it.source === el.href);
                const selected = item(target);
                if (!selected || selected.source !== ${JSONObject.quote(resource.source)}) return null;
                const elements = Array.from(root.querySelectorAll('img, a[href]')).filter(el =>
                    !el.closest('[data-gm-output]') && el.getClientRects().length &&
                    getComputedStyle(el).visibility !== 'hidden' && item(el));
                const index = elements.indexOf(target);
                if (index < 0) return null;
                return JSON.stringify({index, resources: elements.map(item)});
            })()
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            if (webView.tag === page && webView.isAttachedToWindow) {
                val gallery = runCatching {
                    val json = JSONObject(JSONTokener(result).nextValue() as String)
                    val items = json.getJSONArray("resources")
                    val resources = List(items.length()) { index ->
                        val item = items.getJSONObject(index)
                        fun optional(key: String) = item.optString(key).takeUnless { it.isBlank() || it == "null" }
                        DocumentResource(item.getString("source"), optional("originalLink"), optional("description"),
                            DocumentResourceKind.valueOf(item.getString("kind")), optional("mimeType"))
                    }
                    DocumentResourceGallery(resources, json.getInt("index").also { require(it in resources.indices) })
                }.getOrNull()
                onResource(gallery ?: resourceGallery(documentResources.value, resource))
            }
        }
    }

    fun onTouch(event: MotionEvent): Boolean = detector.onTouchEvent(event)
}

internal fun resourcesFromHtml(html: String, baseUrl: String): List<DocumentResource> =
    Jsoup.parse(html, baseUrl).select(".markdown-body img[src], .markdown-body a[href]").mapNotNull { element ->
        if (element.closest("[data-gm-output]") != null) return@mapNotNull null
        if (element.normalName() == "img") {
            element.absUrl("src").takeIf { element.attr("src").isNotBlank() && it.isNotBlank() }?.let { source ->
                DocumentResource(source, element.closest("a[href]")?.absUrl("href")?.takeIf(String::isNotBlank),
                    kind = DocumentResourceKind.IMAGE)
            }
        } else {
            if (element.selectFirst("img") != null) return@mapNotNull null
            val source = element.absUrl("href")
            val url = source.toHttpUrlOrNull() ?: return@mapNotNull null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return@mapNotNull null
            // GitHub file pages retain their deep-link navigation into the repository browser.
            if (url.host == "github.com" && url.pathSegments.getOrNull(2) in setOf("blob", "tree")) return@mapNotNull null
            val kind = when (element.attr("data-gm-media")) {
                "video" -> DocumentResourceKind.VIDEO
                "audio" -> DocumentResourceKind.AUDIO
                else -> resourceKind(source)
            }
            val opaqueAttachment = url.host == "github.com" && url.encodedPath.startsWith("/user-attachments/assets/")
            if (kind == DocumentResourceKind.UNKNOWN && !opaqueAttachment) return@mapNotNull null
            DocumentResource(source, description = element.text().takeIf(String::isNotBlank), kind = kind,
                mimeType = element.attr("data-gm-mime").takeIf(String::isNotBlank))
        }
    }

internal fun resourceGallery(resources: List<DocumentResource>, selected: DocumentResource): DocumentResourceGallery {
    val matches = resources.indices.filter { resources[it].source == selected.source && resources[it].originalLink == selected.originalLink }
    // Native hit testing cannot distinguish repeated occurrences of the same source and link.
    return if (matches.size == 1) DocumentResourceGallery(resources, matches.single())
    else DocumentResourceGallery(listOf(selected), 0)
}

internal fun imageFromHit(type: Int, extra: String?, source: String?, href: String?): DocumentResource? = when (type) {
    WebView.HitTestResult.IMAGE_TYPE -> extra?.takeIf { it.isNotBlank() }?.let { DocumentResource(it, kind = DocumentResourceKind.IMAGE) }
    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> source?.takeIf { it.isNotBlank() }?.let {
        DocumentResource(it, originalLink = (href ?: extra)?.takeIf(String::isNotBlank), kind = DocumentResourceKind.IMAGE)
    }
    else -> null
}
