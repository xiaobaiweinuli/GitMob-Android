package com.gitmob.app.ui.common

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView
import com.gitmob.app.data.model.DocumentImage
import com.gitmob.app.data.model.DocumentImageGallery
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup

/** Native hit testing keeps image links distinct from ordinary document links. */
internal class MarkdownImageTapHandler(
    private val webView: WebView,
    private val onImage: (DocumentImageGallery) -> Unit,
) {
    private var documentImages = lazy { emptyList<DocumentImage>() }

    fun setDocument(html: String, baseUrl: String) {
        documentImages = lazy { imagesFromHtml(html, baseUrl) }
    }

    private val detector = GestureDetector(webView.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            val hit = webView.hitTestResult
            val x = event.x / webView.resources.displayMetrics.density
            val y = event.y / webView.resources.displayMetrics.density
            if (hit.type == WebView.HitTestResult.IMAGE_TYPE) {
                return imageFromHit(hit.type, hit.extra, null, null)?.let { open(it, x, y); true } ?: false
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

    private fun open(image: DocumentImage, x: Float, y: Float) {
        if (!webView.settings.javaScriptEnabled) {
            onImage(imageGallery(documentImages.value, image))
            return
        }
        val page = webView.tag
        val script = """
            (() => {
                const root = document.querySelector('.markdown-body');
                const target = document.elementFromPoint($x, $y);
                if (!root || !target || target.tagName !== 'IMG') return null;
                const source = ${JSONObject.quote(image.source)};
                const link = ${image.originalLink?.let(JSONObject::quote) ?: "null"};
                const href = img => img.closest('a[href]')?.href || null;
                if ((target.currentSrc !== source && target.src !== source) || (link && href(target) !== link)) return null;
                const images = Array.from(root.querySelectorAll('img')).filter(img =>
                    (img.currentSrc || img.getAttribute('src')) && img.getClientRects().length &&
                    getComputedStyle(img).visibility !== 'hidden');
                const index = images.indexOf(target);
                if (index < 0) return null;
                return JSON.stringify({ index, images: images.map(img => ({
                    source: img.currentSrc || img.src, originalLink: href(img)
                })) });
            })()
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            if (webView.tag === page && webView.isAttachedToWindow) {
                val gallery = runCatching {
                    val json = JSONObject(JSONTokener(result).nextValue() as String)
                    val items = json.getJSONArray("images")
                    val images = List(items.length()) { index ->
                        val item = items.getJSONObject(index)
                        DocumentImage(item.getString("source"), item.optString("originalLink").takeUnless { it.isBlank() || it == "null" })
                    }
                    DocumentImageGallery(images, json.getInt("index").also { require(it in images.indices) })
                }.getOrNull()
                onImage(gallery ?: imageGallery(documentImages.value, image))
            }
        }
    }

    fun onTouch(event: MotionEvent): Boolean = detector.onTouchEvent(event)
}

internal fun imagesFromHtml(html: String, baseUrl: String): List<DocumentImage> =
    Jsoup.parse(html, baseUrl).select(".markdown-body img[src]").mapNotNull { image ->
        image.absUrl("src").takeIf { image.attr("src").isNotBlank() && it.isNotBlank() }?.let { source ->
            DocumentImage(source, image.closest("a[href]")?.absUrl("href")?.takeIf(String::isNotBlank))
        }
    }

internal fun imageGallery(images: List<DocumentImage>, selected: DocumentImage): DocumentImageGallery {
    val matches = images.indices.filter { images[it] == selected }
    // Native hit testing cannot distinguish repeated images with the same source and link.
    return if (matches.size == 1) DocumentImageGallery(images, matches.single())
    else DocumentImageGallery(listOf(selected), 0)
}

internal fun imageFromHit(type: Int, extra: String?, source: String?, href: String?): DocumentImage? = when (type) {
    WebView.HitTestResult.IMAGE_TYPE -> extra?.takeIf { it.isNotBlank() }?.let { DocumentImage(it) }
    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> source?.takeIf { it.isNotBlank() }?.let {
        DocumentImage(it, originalLink = (href ?: extra)?.takeIf(String::isNotBlank))
    }
    else -> null
}
