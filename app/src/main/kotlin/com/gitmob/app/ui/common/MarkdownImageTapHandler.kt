package com.gitmob.app.ui.common

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView
import com.gitmob.app.data.model.DocumentImage

/** Uses WebView's native hit testing; Markdown remains JavaScript-disabled. */
internal class MarkdownImageTapHandler(
    private val webView: WebView,
    private val onImage: (DocumentImage) -> Unit,
) {
    private val detector = GestureDetector(webView.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            val hit = webView.hitTestResult
            if (hit.type == WebView.HitTestResult.IMAGE_TYPE) {
                return imageFromHit(hit.type, hit.extra, null, null)?.let { onImage(it); true } ?: false
            }
            if (hit.type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false
            val page = webView.tag
            val reply = Message.obtain(Handler(Looper.getMainLooper()) { message ->
                if (webView.tag === page && webView.isAttachedToWindow) {
                    imageFromHit(hit.type, hit.extra, message.data.getString("src"), message.data.getString("url"))?.let(onImage)
                }
                true
            })
            // For an image wrapped in <a>, hit.extra is the LINK. The focused node supplies src.
            webView.requestFocusNodeHref(reply)
            return true
        }
    })

    fun onTouch(event: MotionEvent): Boolean = detector.onTouchEvent(event)
}

internal fun imageFromHit(type: Int, extra: String?, source: String?, href: String?): DocumentImage? = when (type) {
    WebView.HitTestResult.IMAGE_TYPE -> extra?.takeIf { it.isNotBlank() }?.let { DocumentImage(it) }
    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> source?.takeIf { it.isNotBlank() }?.let {
        DocumentImage(it, originalLink = (href ?: extra)?.takeIf(String::isNotBlank))
    }
    else -> null
}
