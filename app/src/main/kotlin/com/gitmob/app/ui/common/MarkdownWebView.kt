package com.gitmob.app.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import com.gitmob.app.data.model.DocumentImage
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import com.gitmob.app.core.markdown.baseUrl
import com.gitmob.app.data.model.RepoDocumentContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import com.gitmob.app.data.translation.TranslationPresentation
import kotlin.math.abs

/**
 * 共用的 Markdown/HTML 渲染显示组件——README、Issue/PR/讨论评论都走这个，
 * 不要每个功能各自拼一遍 WebView 逻辑。配合 core/markdown/MarkdownRenderer
 * 把 Markdown 转成 HTML 正文后传进来，这个组件只负责"显示"这一步。
 *
 * CSS 来自 github-markdown-css（app/src/main/assets/github-markdown-{light,dark}.css），
 * 不用跟随系统 prefers-color-scheme 自动切换的版本——主题要跟 App 自己的
 * MaterialTheme 状态走，不能依赖系统媒体查询，否则会和 App 整体深浅色不同步。
 * 见 references/markdown-rendering.md。
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun MarkdownWebView(
    bodyHtml: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    documentResources: RepoDocumentResources? = null,
    onDocumentLink: (RepoDocumentContext) -> Unit = {},
    onExternalLink: ((String) -> Unit)? = null,
    translation: TranslationPresentation? = null,
    displayAttempt: Int = 0,
    onTranslationDisplayError: () -> Unit = {},
) {
    val cssFileName = if (backgroundColor.luminance() < 0.5f) {
        "github-markdown-dark.css"
    } else {
        "github-markdown-light.css"
    }
    val backgroundCss = backgroundColor.toCssHex()
    val context = LocalContext.current
    val stylesheet = remember(context, cssFileName) {
        context.assets.open(cssFileName).bufferedReader().use { it.readText() }
    }
    var selectedImage by remember(bodyHtml, documentResources) { mutableStateOf<DocumentImage?>(null) }
    val currentDocumentLink by rememberUpdatedState(onDocumentLink)
    val uriHandler = LocalUriHandler.current
    val currentExternalLink by rememberUpdatedState<(String) -> Unit> { url ->
        if (onExternalLink != null) onExternalLink(url)
        else runCatching { uriHandler.openUri(url) }
    }
    val controlled = translation?.document?.safeHtml != null
    val currentDisplayError by rememberUpdatedState(onTranslationDisplayError)
    // Resources belong to the logical document, not to an individual WebView instance.
    DisposableEffect(documentResources) { onDispose { documentResources?.close() } }
    val retainedSize = remember(bodyHtml, documentResources) { intArrayOf(0) }
    val webViewHolder = remember(bodyHtml, documentResources, controlled) { MarkdownTranslationController { currentDisplayError() } }
    val client = remember(documentResources, webViewHolder) {
        documentResources?.let { resources ->
            RepoDocumentWebViewClient(resources, { currentDocumentLink(it) }, { currentExternalLink(it) }, webViewHolder::pageReady, webViewHolder::pageError)
        } ?: ExternalLinkWebViewClient(webViewHolder::pageReady, webViewHolder::pageError) { currentExternalLink(it) }
    }
    val script = remember(context, controlled) {
        if (controlled) context.assets.open("markdown-translation.js").bufferedReader().use { it.readText() } else null
    }
    val sourceCss = MaterialTheme.colorScheme.onSurfaceVariant.toCssHex()
    val borderCss = MaterialTheme.colorScheme.outlineVariant.toCssHex()
    // Results and progress never participate in the HTML loading identity.
    val documentId = remember(bodyHtml, translation?.document, cssFileName, backgroundCss, sourceCss, borderCss, displayAttempt) { UUID.randomUUID().toString() }
    val nonce = remember(documentId) { UUID.randomUUID().toString() }
    val fullHtml = remember(documentId) {
        buildMarkdownDocument(translation?.document?.safeHtml ?: bodyHtml, stylesheet, backgroundCss,
            script = script, nonce = nonce, documentId = documentId, sourceCss = sourceCss, borderCss = borderCss)
    }
    val pageKey = remember(fullHtml, documentResources) { Any() }

    // A new controlled instance never enables scripts on previously loaded raw HTML.
    key(bodyHtml, documentResources, controlled) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clipToBounds()
            // Compose 的滚动只平移 display list，不会像经典 ScrollView 那样让子 View
            // 重走 onDraw，Chromium 因此无法感知自己被滚进了屏幕（表现为 WebView
            // 从底部进入时有一条"死区"空白，静止也不恢复）。位置一变就强制重绘，
            // 把"滚动即重绘"的 View 世界契约还给 WebView。
            .onGloballyPositioned {
                if (it.size.height > 0) retainedSize[0] = it.size.height
                webViewHolder.webView?.postInvalidateOnAnimation()
            },
        factory = { context ->
            val webView = NoVerticalScrollWebView(context).apply {
                onImageClick = { selectedImage = it }
                settings.javaScriptEnabled = controlled
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.domStorageEnabled = false
                // Chromium 默认不为"已 attach 但不在屏幕上"的 WebView 光栅化，从屏幕外
                // 滚入时会先露出空白。官方 GitHub App 的 README WebView 也靠这个开关
                // 让像素在滚入前就准备好（反编译 InternalWebView 确认）。
                settings.offscreenPreRaster = true
                // 官方同款近全透明底色：加载完成前透出 Compose 背景，规避硬件层白闪；
                // 页面自身的 CSS 会绘制真正的主题底色。
                setBackgroundColor(android.graphics.Color.argb(1, 0, 0, 0))
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setOnTouchListener(object : View.OnTouchListener {
                    private var downX = 0f
                    private var downY = 0f

                    override fun onTouch(view: View, event: MotionEvent): Boolean {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = event.x
                                downY = event.y
                                // 这里不要 requestDisallowInterceptTouchEvent(true)：外层是
                                // 纵向滚动容器，不会在 DOWN 抢手势；提前禁止拦截只会扩大
                                // Chromium 积累垂直 fling 速度的窗口。横向保护推迟到 MOVE
                                // 判定方向后再开启。
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val isHorizontal = abs(event.x - downX) > abs(event.y - downY)
                                view.parent.requestDisallowInterceptTouchEvent(isHorizontal)
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                view.parent.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        return false
                    }
                })
            }
            webViewHolder.webView = webView
            createMarkdownWebViewHost(webView).apply { minimumHeight = retainedSize[0] }
        },
        update = { host ->
            val webView = host.getChildAt(0) as? WebView ?: return@AndroidView
            (webView as? NoVerticalScrollWebView)?.onImageClick = { selectedImage = it }
            if (webView.tag !== pageKey) {
                webView.tag = pageKey
                webViewHolder.begin(pageKey, if (controlled) documentId else null)
                webView.setWebViewClient(client)
                webView.settings.allowFileAccess = false
                webView.settings.allowContentAccess = !controlled && documentResources == null
                webView.settings.cacheMode = if (documentResources == null) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
                webView.loadDataWithBaseURL(
                    documentResources?.document?.baseUrl()?.toString() ?: if (controlled) "https://translation.invalid/" else "file:///android_asset/",
                    fullHtml,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            webViewHolder.submit(translation)
        },
        onRelease = { host ->
            webViewHolder.release()
            (webViewHolder.webView as? NoVerticalScrollWebView)?.onImageClick = null
            webViewHolder.webView = null
            releaseMarkdownWebViewHost(host)
        },
    )
    }
    selectedImage?.let { image ->
        DocumentImageViewer(image, documentResources?.document, { selectedImage = null }, { link ->
            if (client is RepoDocumentWebViewClient) {
                if (!client.openLink(link)) {
                    documentResources?.document?.baseUrl()?.resolve(link)?.let {
                        webViewHolder.webView?.loadUrl(it.toString())
                    }
                }
            } else {
                val uri = link.toUri()
                if (uri.scheme in setOf("https", "http") && uri.userInfo.isNullOrEmpty()) {
                    currentExternalLink(link)
                }
            }
        })
    }
}

/** Generic Markdown delegates links to the system instead of replacing its embedded document. */
internal class ExternalLinkWebViewClient(
    private val onPageReady: (WebView) -> Unit = {},
    private val onPageError: (WebView) -> Unit = {},
    private val onExternalLink: (String) -> Unit,
) : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) { onPageReady(view) }
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onPageError(view)
    }
    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) onPageError(view)
    }
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        if (request.isForMainFrame) openLink(view, request.url.toString()) else true

    @Deprecated("Used by older WebView implementations")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = openLink(view, url)

    private fun openLink(view: WebView, url: String): Boolean {
        if ('#' in url && url.substringBefore('#') == view.url?.substringBefore('#')) return false
        val uri = url.toUri()
        if (uri.scheme in setOf("https", "http", "mailto") && uri.userInfo.isNullOrEmpty()) onExternalLink(url)
        return true
    }
}

/** View-local display coordination; business results remain exclusively in the ViewModel. */
internal class MarkdownTranslationController(private val onError: () -> Unit) {
    var webView: WebView? = null
    private var pageKey: Any? = null
    private var documentId: String? = null
    private var ready = false
    private var failed = false
    private var applying = false
    private var pending: TranslationPresentation? = null
    private var confirmed: Pair<Long, Long>? = null
    private val timeout = Runnable { fail() }

    fun begin(key: Any, id: String?) {
        webView?.removeCallbacks(timeout)
        pageKey = key; documentId = id; ready = false; failed = false; applying = false; confirmed = null
        if (id != null) webView?.postDelayed(timeout, 15000)
    }
    fun submit(value: TranslationPresentation?) { pending = value; flush() }
    fun pageReady(view: WebView) {
        if (view !== webView) return
        if (documentId == null) { (view.parent as? View)?.minimumHeight = 0; view.requestLayout(); return }
        if (ready || failed) return
        val expected = pageKey
        runCatching {
            view.evaluateJavascript("window.gmTranslation && window.gmTranslation.ready()") { result ->
                if (!current(view, expected)) return@evaluateJavascript
                val ack = parse(result)
                // A late page-finished callback can still observe the previous document.
                // Keep waiting for the current document; the bounded handshake timeout reports a missing script.
                if (ack?.optString("documentId") != documentId) return@evaluateJavascript
                ready = true; view.removeCallbacks(timeout); remeasure(view); flush()
            }
        }.onFailure { fail() }
    }
    fun pageError(view: WebView) { if (view === webView && documentId != null) fail() }
    private fun flush() {
        val view = webView ?: return
        val value = pending ?: return
        val id = documentId ?: return
        if (!ready || failed || applying || confirmed == (value.generation to value.version)) return
        val expected = pageKey
        applying = true
        view.removeCallbacks(timeout); view.postDelayed(timeout, 15000)
        runCatching {
            view.evaluateJavascript("window.gmTranslation && window.gmTranslation.apply(" + translationPatch(id, value) + ")") { result ->
                if (!current(view, expected)) return@evaluateJavascript
                applying = false; view.removeCallbacks(timeout)
                val ack = parse(result)
                if (ack?.optString("documentId") != id || ack.optLong("generation", -1) != value.generation || ack.optLong("version", -1) != value.version) {
                    fail(); return@evaluateJavascript
                }
                confirmed = value.generation to value.version
                remeasure(view)
                flush()
            }
        }.onFailure { fail() }
    }
    private fun current(view: WebView, key: Any?) = view === webView && pageKey === key && view.tag === key && !failed
    private fun parse(value: String?): JSONObject? = runCatching { JSONObject(value.orEmpty()) }.getOrNull()
    private fun remeasure(view: WebView) {
        (view.parent as? View)?.let { it.minimumHeight = 0; it.requestLayout() }
        view.requestLayout(); view.postInvalidateOnAnimation()
    }
    private fun fail() {
        if (failed || documentId == null) return
        failed = true; applying = false; webView?.removeCallbacks(timeout); onError()
    }
    fun release() { webView?.removeCallbacks(timeout); ready = false; failed = true; pending = null; pageKey = null }
}

internal fun translationPatch(documentId: String, value: TranslationPresentation): String = JSONObject().apply {
    put("documentId", documentId); put("generation", value.generation); put("version", value.version); put("mode", value.mode)
    put("values", JSONObject().apply { value.translations.forEach { (id, text) -> put(id.toString(), text) } })
}.toString()

/**
 * 本组件里 WebView 被撑到完整内容高度、由外层 Compose 容器滚动，按设计自身不应滚动；
 * 但图片等资源晚于首次测量加载完成时，contentHeight 会暂时大于视图高度，产生内部
 * 可滚动余量——起手落在 WebView 上的滑动（尤其 UP 之后仍在继续的惯性 fling）会把
 * 文档在视图内部推上去，顶部内容被裁掉，看起来像被上方列表"遮挡"且不断累积。
 *
 * 修正点选在 onScrollChanged：Chromium 胶水层滚动容器时走 super_scrollTo/raw 字段
 * 直写，不经过可重写的 scrollTo；但所有路径（程序调用、触摸拖动、惯性 fling）最终
 * 都会触发 onScrollChanged——在这个唯一必经点把非零垂直偏移立即拉回 0。
 * 代码块/表格的横向滑动是页面内元素级滚动，不产生 View 级垂直偏移，不受影响。
 */
internal class NoVerticalScrollWebView(context: Context) : WebView(context) {
    var onImageClick: ((DocumentImage) -> Unit)? = null
    private val images = MarkdownImageTapHandler(this) { onImageClick?.invoke(it) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onImageClick != null && images.onTouch(event) && event.actionMasked == MotionEvent.ACTION_UP) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.onTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t != 0) scrollTo(l, 0)
    }
}

internal fun createMarkdownWebViewHost(webView: WebView): FrameLayout =
    FrameLayout(webView.context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        addView(webView)
    }

internal fun releaseMarkdownWebViewHost(host: FrameLayout) {
    val webView = host.getChildAt(0) as? WebView ?: return
    host.removeView(webView)
    webView.stopLoading()
    webView.destroy()
}

internal fun buildMarkdownDocument(
    bodyHtml: String,
    stylesheet: String,
    backgroundCss: String,
    script: String? = null,
    nonce: String = "",
    documentId: String = "",
    sourceCss: String = "#656d76",
    borderCss: String = "#d0d7de",
): String {
    val policy = if (script == null) "" else """
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; img-src https: http: data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
    """.trimIndent()
    val translationStyle = if (script == null) "" else """
        .markdown-body .gm-source { color: $sourceCss; }
        .markdown-body [data-gm-output] { display: block; border-inline-start: 3px solid $borderCss; padding-inline-start: 12px; margin: 8px 0; font-weight: normal; white-space: pre-wrap; }
        .markdown-body [data-gm-output][hidden] { display: none; }
    """.trimIndent()
    val articleAttributes = if (script == null) "" else " id=\"gm-root\" data-gm-document=\"$documentId\""
    val updater = if (script == null) "" else "<script nonce=\"$nonce\">$script</script>"
    return """
        <!doctype html>
        <html lang="zh">
        <head>
            $policy
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$stylesheet</style>
            <style>
                html, body, .markdown-body { background-color: $backgroundCss !important; }
                body { margin: 0; }
                .markdown-body { padding: 0 16px 16px; box-sizing: border-box; }
                .markdown-body img { max-width: 100%; }
                $translationStyle
            </style>
        </head>
        <body><article class="markdown-body"$articleAttributes>$bodyHtml</article>$updater</body>
        </html>
    """.trimIndent()
}

private fun Color.toCssHex(): String =
    String.format(Locale.ROOT, "#%06X", toArgb() and 0x00FFFFFF)
