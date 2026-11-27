package com.gitmob.app.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import com.gitmob.app.data.model.DocumentResourceGallery
import com.gitmob.app.data.translation.TranslationDocuments
import com.gitmob.app.data.translation.TranslationTarget
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import com.gitmob.app.data.translation.TranslationDocument
import com.gitmob.app.data.translation.TranslationPresentation
import kotlin.math.abs

/** Embedded documents let their parent scroll; viewport documents scroll inside WebView. */
enum class MarkdownLayoutMode { Embedded, Viewport }

val LocalMarkdownLinkHandler = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

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
    preparedDocument: TranslationDocument? = null,
    translation: TranslationPresentation? = null,
    displayAttempt: Int = 0,
    onTranslationDisplayError: () -> Unit = {},
    onTranslationDisplayed: (TranslationPresentation?) -> Unit = {},
    navigationDocument: NavigationDocument? = null,
    layoutMode: MarkdownLayoutMode = MarkdownLayoutMode.Embedded,
    viewportState: MarkdownViewportState? = null,
    linkColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
) {
    val cssFileName = if (backgroundColor.luminance() < 0.5f) {
        "github-markdown-dark.css"
    } else {
        "github-markdown-light.css"
    }
    val backgroundCss = backgroundColor.toCssHex()
    val linkCss = linkColor?.toCssHex()
    val layoutDirection = LocalLayoutDirection.current
    val contentPaddingCss = listOf(contentPadding.calculateTopPadding(),
        contentPadding.calculateRightPadding(layoutDirection), contentPadding.calculateBottomPadding(),
        contentPadding.calculateLeftPadding(layoutDirection)).joinToString(" ") { "${it.value}px" }
    val context = LocalContext.current
    val stylesheet = remember(context, cssFileName) {
        context.assets.open(cssFileName).bufferedReader().use { it.readText() }
    }
    var selectedResource by remember(bodyHtml, documentResources) { mutableStateOf<DocumentResourceGallery?>(null) }
    val currentDocumentLink by rememberUpdatedState(onDocumentLink)
    val uriHandler = LocalUriHandler.current
    val linkHandler = onExternalLink ?: LocalMarkdownLinkHandler.current
    val currentExternalLink by rememberUpdatedState<(String) -> Unit> { url ->
        if (linkHandler != null) linkHandler(url)
        else runCatching { uriHandler.openUri(url) }
    }
    val controlled = preparedDocument?.safeHtml != null
    val currentDisplayError by rememberUpdatedState(onTranslationDisplayError)
    val currentDisplayed by rememberUpdatedState(onTranslationDisplayed)
    DisposableEffect(documentResources, navigationDocument) {
        onDispose { if (navigationDocument == null) documentResources?.close() }
    }
    val viewState = remember(bodyHtml, documentResources, controlled, navigationDocument, layoutMode) {
        navigationDocument?.renderer(layoutMode) ?: MarkdownWebViewState(documentResources)
    }
    val webViewHolder = viewState.controller
    val client = viewState.client
    val script = remember(context, controlled) {
        if (controlled) context.assets.open("markdown-translation.js").bufferedReader().use { it.readText() } else null
    }
    val sourceCss = MaterialTheme.colorScheme.onSurfaceVariant.toCssHex()
    val borderCss = MaterialTheme.colorScheme.outlineVariant.toCssHex()
    // Results and progress never participate in the HTML loading identity.
    val page = viewState.pageFor(listOf(bodyHtml, preparedDocument?.safeHtml, cssFileName,
        backgroundCss, sourceCss, borderCss, linkCss, contentPaddingCss))
    val fullHtml = remember(page) {
        val safeHtml = preparedDocument?.safeHtml ?: TranslationDocuments.prepareHtml(
            TranslationTarget("media-preview", bodyHtml, html = true)).safeHtml.orEmpty()
        buildMarkdownDocument(safeHtml, stylesheet, backgroundCss,
            script = script, nonce = page.nonce, documentId = page.documentId, sourceCss = sourceCss, borderCss = borderCss,
            linkCss = linkCss, contentPaddingCss = contentPaddingCss)
    }

    key(viewState) {
    AndroidView(
        modifier = if (layoutMode == MarkdownLayoutMode.Viewport) modifier.fillMaxSize().clipToBounds()
        else modifier.fillMaxWidth().wrapContentHeight().clipToBounds()
            .remeasureOnMarkdownResize {
                (webViewHolder.webView as? MarkdownContentWebView)?.layoutSize?.height ?: 0
            }
            // Embedded WebViews need a redraw when Compose moves them into the viewport.
            .onGloballyPositioned { webViewHolder.webView?.postInvalidateOnAnimation() },
        factory = { context ->
            val webView = webViewHolder.webView ?: (if (layoutMode == MarkdownLayoutMode.Viewport) MarkdownViewportWebView(context, viewportState)
                else NoVerticalScrollWebView(context)).apply {
                settings.javaScriptEnabled = controlled
                if (controlled) addJavascriptInterface(MarkdownReadyBridge(this, webViewHolder), "gmTranslationHost")
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.domStorageEnabled = false
                // Chromium 默认不为"已 attach 但不在屏幕上"的 WebView 光栅化，从屏幕外
                // 滚入时会先露出空白。官方 GitHub App 的 README WebView 也靠这个开关
                // 让像素在滚入前就准备好（反编译 InternalWebView 确认）。
                settings.offscreenPreRaster = layoutMode == MarkdownLayoutMode.Embedded
                // 官方同款近全透明底色：加载完成前透出 Compose 背景，规避硬件层白闪；
                // 页面自身的 CSS 会绘制真正的主题底色。
                setBackgroundColor(android.graphics.Color.argb(1, 0, 0, 0))
                isVerticalScrollBarEnabled = layoutMode == MarkdownLayoutMode.Viewport
                overScrollMode = View.OVER_SCROLL_NEVER
                if (layoutMode == MarkdownLayoutMode.Embedded) setOnTouchListener(object : View.OnTouchListener {
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
            webView.onResume()
            createMarkdownWebViewHost(webView, layoutMode)
        },
        update = { host ->
            val webView = host.getChildAt(0) as? WebView ?: return@AndroidView
            viewState.onDocumentLink = { currentDocumentLink(it) }
            viewState.onExternalLink = { currentExternalLink(it) }
            viewState.bindDisplayCallbacks({ currentDisplayError() }, { currentDisplayed(it) })
            (webView as? MarkdownContentWebView)?.onResourceClick = { selectedResource = it }
            (webView as? MarkdownViewportWebView)?.bindViewportState(viewportState)
            if (webView.tag !== page) {
                webView.tag = page
                webViewHolder.begin(page, if (controlled) page.documentId else null)
                webView.setWebViewClient(client)
                webView.settings.allowFileAccess = false
                webView.settings.allowContentAccess = !controlled && documentResources == null
                webView.settings.cacheMode = if (documentResources == null) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
                val baseUrl = documentResources?.document?.baseUrl()?.toString()
                    ?: if (controlled) "https://translation.invalid/" else "file:///android_asset/"
                (webView as? MarkdownContentWebView)?.setResourceDocument(fullHtml, baseUrl)
                webView.loadDataWithBaseURL(
                    baseUrl,
                    fullHtml,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            webViewHolder.submit(translation, displayAttempt)
        },
        onRelease = { host ->
            viewState.detach(host)
            if (navigationDocument == null) viewState.close()
        },
    )
    }
    selectedResource?.let { gallery ->
        DocumentResourceViewer(gallery, documentResources?.document, { selectedResource = null }, { link ->
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

/** Renderer state survives composition only when a navigation document owns it. */
internal class MarkdownWebViewState(resources: RepoDocumentResources?) {
    var onDocumentLink: ((RepoDocumentContext) -> Unit)? = null
    var onExternalLink: ((String) -> Unit)? = null
    private var onDisplayError: (() -> Unit)? = null
    private var onDisplayed: ((TranslationPresentation?) -> Unit)? = null
    private var pendingDisplayState: Boolean? = null
    private var displayedPresentation: TranslationPresentation? = null
    private var pageInputs: List<Any?>? = null
    private var page by mutableStateOf(MarkdownPage())
    val controller = MarkdownTranslationController(
        onReload = { page = MarkdownPage() },
        onDisplayed = {
            displayedPresentation = it
            val handler = onDisplayed
            if (handler == null) pendingDisplayState = false else handler(it)
        },
        onError = {
            val handler = onDisplayError
            if (handler == null) pendingDisplayState = true else handler()
        },
    )
    val client: WebViewClient = resources?.let {
        RepoDocumentWebViewClient(it, { onDocumentLink?.invoke(it) }, { onExternalLink?.invoke(it) },
            controller::pageReady, controller::pageError)
    } ?: ExternalLinkWebViewClient(controller::pageReady, controller::pageError) { onExternalLink?.invoke(it) }
    fun pageFor(inputs: List<Any?>): MarkdownPage {
        if (pageInputs != inputs) {
            pageInputs = inputs
            page = MarkdownPage()
        }
        return page
    }

    fun bindDisplayCallbacks(error: () -> Unit, displayed: (TranslationPresentation?) -> Unit) {
        onDisplayError = error
        onDisplayed = displayed
        val pending = pendingDisplayState
        pendingDisplayState = null
        if (pending == true) error() else if (pending == false) displayed(displayedPresentation)
    }

    fun detach(host: FrameLayout) {
        val view = controller.webView ?: return
        if (view.parent !== host) return
        onDocumentLink = null
        onExternalLink = null
        onDisplayError = null
        onDisplayed = null
        (view as? MarkdownContentWebView)?.onResourceClick = null
        host.removeView(view)
        view.onPause()
    }

    fun close() {
        onDocumentLink = null
        onExternalLink = null
        onDisplayError = null
        onDisplayed = null
        controller.release()
        val view = controller.webView ?: return
        controller.webView = null
        (view as? MarkdownContentWebView)?.onResourceClick = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }
}

internal class MarkdownPage {
    val documentId: String = UUID.randomUUID().toString()
    val nonce: String = UUID.randomUUID().toString()
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
internal class MarkdownTranslationController(
    private val onReload: () -> Unit = {},
    private val onDisplayed: (TranslationPresentation?) -> Unit = {},
    private val onError: () -> Unit,
) {
    var webView: WebView? by mutableStateOf(null)
    private var pageKey: Any? = null
    private var documentId: String? = null
    private var ready = false
    private var failed = false
    private var applying = false
    private var pending: TranslationPresentation? = null
    private var submitted = false
    private var revision = 0L
    private var confirmed = -1L
    private var displayAttempt = 0
    private var operation = 0L
    private var timeout: Runnable? = null

    fun begin(key: Any, id: String?) {
        cancelOperation()
        pageKey = key; documentId = id; ready = false; failed = false; applying = false
        pending = null; submitted = false; revision = 0; confirmed = -1
        if (id != null) startOperation { fail() }
    }

    fun submit(value: TranslationPresentation?, retry: Int = 0) {
        val retryRequested = submitted && retry != 0 && retry != displayAttempt
        if (retry != 0) displayAttempt = retry
        if (submitted && pending == value && !retryRequested) return
        val original = value == null || value.mode == "original"
        val generationChanged = pending?.generation != value?.generation
        val recover = retryRequested || failed && (original || generationChanged)
        pending = value; submitted = true; revision++
        when {
            recover -> probe(reloadIfUnavailable = true)
            failed -> onError()
            else -> flush()
        }
    }

    fun scriptReady(view: WebView, id: String) {
        if (!current(view, pageKey) || id != documentId || ready) return
        cancelOperation()
        ready = true; failed = false; applying = false
        remeasure(view)
        flush()
    }

    fun pageReady(view: WebView) {
        if (view !== webView) return
        if (documentId == null) { view.requestLayout(); return }
        if (!ready && !applying) probe(reloadIfUnavailable = false)
    }

    private fun probe(reloadIfUnavailable: Boolean) {
        val view = webView ?: return
        val id = documentId ?: return
        val expected = pageKey
        fun unavailable() {
            fail()
            if (reloadIfUnavailable) onReload()
        }
        applying = true
        val attempt = startOperation { unavailable() }
        runCatching {
            view.evaluateJavascript("window.gmTranslation && window.gmTranslation.ready()") { result ->
                if (!current(view, expected) || operation != attempt) return@evaluateJavascript
                if (parse(result)?.optString("documentId") != id) {
                    // An old page-finished event may still observe the previous document.
                    if (reloadIfUnavailable) unavailable()
                    return@evaluateJavascript
                }
                cancelOperation()
                ready = true; failed = false; applying = false
                remeasure(view)
                flush()
            }
        }.onFailure { if (operation == attempt) unavailable() }
    }

    fun pageError(view: WebView) {
        if (view === webView && documentId != null) { ready = false; fail() }
    }

    private fun flush() {
        val view = webView ?: return
        val value = pending
        val id = documentId ?: return
        if (!submitted || !ready || failed || applying || confirmed == revision) return
        val expectedRevision = revision
        val expected = pageKey
        fun rejected() {
            cancelOperation(); applying = false
            if (revision != expectedRevision) flush() else fail()
        }
        applying = true
        val attempt = startOperation { rejected() }
        runCatching {
            view.evaluateJavascript("window.gmTranslation && window.gmTranslation.apply(" + translationPatch(id, value, expectedRevision) + ")") { result ->
                if (!current(view, expected) || operation != attempt) return@evaluateJavascript
                val ack = parse(result)
                if (ack?.optString("documentId") != id || ack.optLong("generation", -1) != 0L || ack.optLong("version", -1) != expectedRevision) {
                    rejected(); return@evaluateJavascript
                }
                cancelOperation(); applying = false
                confirmed = expectedRevision
                remeasure(view)
                if (revision == expectedRevision) onDisplayed(value)
                flush()
            }
        }.onFailure { if (operation == attempt) rejected() }
    }

    private fun current(view: WebView, key: Any?) = key != null && view === webView && pageKey === key && view.tag === key
    private fun parse(value: String?): JSONObject? = runCatching { JSONObject(value.orEmpty()) }.getOrNull()
    private fun remeasure(view: WebView) {
        view.requestLayout(); view.postInvalidateOnAnimation()
    }
    private fun startOperation(expired: () -> Unit): Long {
        cancelOperation()
        val attempt = operation
        timeout = Runnable { if (operation == attempt) expired() }.also { webView?.postDelayed(it, 15000) }
        return attempt
    }
    private fun cancelOperation() {
        timeout?.let { webView?.removeCallbacks(it) }
        timeout = null
        operation++
    }
    private fun fail() {
        cancelOperation(); applying = false
        if (failed || documentId == null) return
        failed = true; onError()
    }
    fun release() {
        cancelOperation(); ready = false; failed = true; pending = null; pageKey = null
    }
}

/** Only the sanitized document's nonce-authorized script can notify readiness. */
internal class MarkdownReadyBridge(private val view: WebView, private val controller: MarkdownTranslationController) {
    @JavascriptInterface
    fun ready(documentId: String) { view.post { controller.scriptReady(view, documentId) } }
}

internal fun translationPatch(documentId: String, value: TranslationPresentation?, revision: Long): String = JSONObject().apply {
    put("documentId", documentId); put("generation", 0); put("version", revision); put("mode", value?.mode ?: "original")
    put("values", JSONObject().apply { value?.translations?.forEach { (id, text) -> put(id.toString(), text) } })
}.toString()

/** Shared resource interaction for embedded documents and independently scrolling previews. */
internal open class MarkdownContentWebView(context: Context) : WebView(context) {
    var layoutSize by mutableStateOf(IntSize.Zero)
        private set
    var onResourceClick: ((DocumentResourceGallery) -> Unit)? = null
    private val resources = MarkdownResourceTapHandler(this) { onResourceClick?.invoke(it) }

    fun setResourceDocument(html: String, baseUrl: String) = resources.setDocument(html, baseUrl)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onResourceClick != null && resources.onTouch(event) && event.actionMasked == MotionEvent.ACTION_UP) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            super.onTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutSize = IntSize(w, h)
    }

}

/** Reapplies saved scroll only after Chromium has enough content, including late image layout. */
// Created programmatically with document state; not inflated from XML.
@SuppressLint("ViewConstructor")
internal open class MarkdownViewportWebView(
    context: Context,
    private var viewportState: MarkdownViewportState?,
) : MarkdownContentWebView(context) {
    private var restorePending = viewportState?.let { it.xDp != 0f || it.yDp != 0f } == true

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var touchStart: android.graphics.PointF? = null

    fun bindViewportState(state: MarkdownViewportState?) {
        if (viewportState === state) return
        val previous = viewportState
        viewportState = state
        if (previous != null && state?.documentId == previous.documentId) {
            if (restorePending) {
                state.xDp = previous.xDp
                state.yDp = previous.yDp
            } else savePosition(scrollX, scrollY)
        } else {
            restorePending = state != null
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!restorePending || width == 0 || height == 0) return
        val saved = viewportState ?: return
        val density = resources.displayMetrics.density
        val x = (saved.xDp * density).toInt()
        val y = (saved.yDp * density).toInt()
        val maxX = (computeHorizontalScrollRange() - width).coerceAtLeast(0)
        val maxY = (computeVerticalScrollRange() - height).coerceAtLeast(0)
        restorePending = maxX < x || maxY < y
        scrollTo(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchStart = android.graphics.PointF(event.x, event.y)
            MotionEvent.ACTION_MOVE -> touchStart?.let { start ->
                if (kotlin.math.abs(event.x - start.x) > touchSlop || kotlin.math.abs(event.y - start.y) > touchSlop) {
                    touchStart = null
                    cancelScrollRestore()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchStart = null
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_SPACE -> cancelScrollRestore()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && (
            event.getAxisValue(MotionEvent.AXIS_VSCROLL) != 0f ||
                event.getAxisValue(MotionEvent.AXIS_HSCROLL) != 0f ||
                event.getAxisValue(MotionEvent.AXIS_SCROLL) != 0f
            )) cancelScrollRestore()
        return super.onGenericMotionEvent(event)
    }

    private fun cancelScrollRestore() {
        restorePending = false
        savePosition(scrollX, scrollY)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!restorePending) savePosition(l, t)
    }

    private fun savePosition(x: Int, y: Int) {
        viewportState?.let {
            it.xDp = x / resources.displayMetrics.density
            it.yDp = y / resources.displayMetrics.density
        }
    }
}

/** Embedded documents are measured to content height and scrolled by their Compose parent. */
internal class NoVerticalScrollWebView(context: Context) : MarkdownContentWebView(context) {
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t != 0) scrollTo(l, 0)
    }
}

private fun Modifier.remeasureOnMarkdownResize(contentHeight: () -> Int): Modifier =
    layout { measurable, constraints ->
        val observedHeight = contentHeight()
        // Measure naturally so a previous height cannot prevent WebView content from growing or shrinking.
        val placeable = measurable.measure(constraints)
        val height = observedHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(placeable.width, height) { placeable.placeRelative(0, 0) }
    }

internal fun createMarkdownWebViewHost(
    webView: WebView,
    layoutMode: MarkdownLayoutMode = MarkdownLayoutMode.Embedded,
): FrameLayout =
    FrameLayout(webView.context).apply {
        val height = if (layoutMode == MarkdownLayoutMode.Viewport) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height,
        )
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            height,
        )
        (webView.parent as? ViewGroup)?.removeView(webView)
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
    linkCss: String? = null,
    contentPaddingCss: String = "0 16px 16px",
): String {
    val linkStyle = linkCss?.let { ".markdown-body a { color: $it; text-decoration: underline; }" }.orEmpty()
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
                .markdown-body { padding: $contentPaddingCss; box-sizing: border-box; }
                $linkStyle
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
