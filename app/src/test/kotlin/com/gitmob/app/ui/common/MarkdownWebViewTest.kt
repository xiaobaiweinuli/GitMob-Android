package com.gitmob.app.ui.common

import android.view.MotionEvent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.saveable.SaverScope
import android.webkit.WebResourceRequest
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import kotlinx.coroutines.runBlocking
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.translation.TranslationDocuments
import com.gitmob.app.data.translation.TranslationPresentation
import com.gitmob.app.data.translation.TranslationProvider
import com.gitmob.app.data.translation.TranslationTarget
import com.gitmob.app.data.translation.TranslationConfiguration
import com.gitmob.app.data.translation.TranslationPreferences
import com.gitmob.app.ui.translation.LocalTranslationController
import com.gitmob.app.ui.translation.TranslationController
import com.gitmob.app.ui.translation.TranslationEntry
import com.gitmob.app.ui.translation.TranslationHtml
import com.gitmob.app.ui.translation.TranslationUiState
import com.gitmob.app.ui.translation.TranslationViewModel
import android.webkit.ValueCallback
import org.json.JSONObject
import io.mockk.Called
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.robolectric.Shadows.shadowOf
import android.webkit.WebView
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowWebView
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [MarkdownWebViewTest.CountingWebViewShadow::class])
class MarkdownWebViewTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun embeddedAndFullScreenPreviewsBindTheCurrentImageGallery() {
        val mode = mutableStateOf(MarkdownLayoutMode.Embedded)
        val body = mutableStateOf("<p><img src='https://example.test/first.png'><img src='https://example.test/second.png'></p>")
        lateinit var root: View
        composeRule.setContent {
            root = androidx.compose.ui.platform.LocalView.current
            MaterialTheme { MarkdownWebView(body.value, layoutMode = mode.value) }
        }
        fun imageSources(): List<String> {
            val view = requireNotNull(findWebView(root)) as MarkdownContentWebView
            val handler = ReflectionHelpers.getField<MarkdownImageTapHandler>(view, "images")
            val images = ReflectionHelpers.getField<Lazy<List<com.gitmob.app.data.model.DocumentImage>>>(handler, "documentImages")
            return images.value.map { it.source }
        }
        val initial = listOf("https://example.test/first.png", "https://example.test/second.png")
        composeRule.runOnIdle { assertEquals(initial, imageSources()) }
        composeRule.runOnIdle { mode.value = MarkdownLayoutMode.Viewport }
        composeRule.runOnIdle {
            assertTrue(findWebView(root) is MarkdownViewportWebView)
            assertEquals(initial, imageSources())
        }
        composeRule.runOnIdle { body.value = "<p><img src='https://example.test/third.png'></p>" }
        composeRule.runOnIdle { assertEquals(listOf("https://example.test/third.png"), imageSources()) }
        composeRule.runOnIdle { mode.value = MarkdownLayoutMode.Embedded }
        composeRule.runOnIdle { assertEquals(listOf("https://example.test/third.png"), imageSources()) }
    }
    @Test fun bindingAnotherDocumentAtZeroClearsThePreviousScroll() {
        val previous = MarkdownViewportState("old")
        val view = ScrollRestoreWebView(previous)
        val bitmap = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888)
        try {
            view.scrollTo(0, 200)
            val next = MarkdownViewportState("new")
            view.bindViewportState(next)
            view.drawFrame(Canvas(bitmap))
            assertEquals(0, view.scrollY)
            assertEquals(0f, next.yDp)
            assertEquals(200f / view.resources.displayMetrics.density, previous.yDp)
        } finally { bitmap.recycle(); view.destroy() }
    }

    @Test fun tapsAndCanceledTouchesKeepDelayedScrollRestoration() {
        for (finish in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            val saved = MarkdownViewportState("file", 0f, 800f)
            val view = ScrollRestoreWebView(saved)
            val bitmap = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888)
            try {
                view.documentHeight = 380
                view.drawFrame(Canvas(bitmap))
                assertEquals(200, view.scrollY)
                view.touch(MotionEvent.ACTION_DOWN, 20f)
                view.touch(MotionEvent.ACTION_MOVE, 21f)
                view.touch(finish, 21f)
                assertEquals(800f, saved.yDp)
                val rebound = MarkdownViewportState("file")
                view.bindViewportState(rebound)
                assertEquals(800f, rebound.yDp)
                view.documentHeight = 10_000
                view.drawFrame(Canvas(bitmap))
                assertEquals((800 * view.resources.displayMetrics.density).toInt(), view.scrollY)
            } finally { bitmap.recycle(); view.destroy() }
        }
    }

    @Test fun draggingTakesPriorityOverDelayedScrollRestoration() {
        val saved = MarkdownViewportState("file", 0f, 800f)
        val view = ScrollRestoreWebView(saved)
        val bitmap = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888)
        try {
            view.documentHeight = 380
            view.drawFrame(Canvas(bitmap))
            view.touch(MotionEvent.ACTION_DOWN, 20f)
            view.touch(MotionEvent.ACTION_MOVE, 150f)
            view.scrollTo(0, 100)
            view.touch(MotionEvent.ACTION_UP, 150f)
            view.documentHeight = 10_000
            view.drawFrame(Canvas(bitmap))
            assertEquals(100, view.scrollY)
            assertEquals(100f / view.resources.displayMetrics.density, saved.yDp)
        } finally { bitmap.recycle(); view.destroy() }
    }

    @Test fun keyboardScrollTakesPriorityOverUnreachableSavedPosition() {
        val saved = MarkdownViewportState("file", 0f, 800f)
        val view = ScrollRestoreWebView(saved)
        val bitmap = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888)
        try {
            view.documentHeight = 380
            view.drawFrame(Canvas(bitmap))
            view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PAGE_UP))
            // ShadowWebView has no Chromium keyboard handler; apply its resulting native scroll.
            view.scrollTo(0, 100)
            view.drawFrame(Canvas(bitmap))
            assertEquals(100, view.scrollY)
            assertEquals(100f / view.resources.displayMetrics.density, saved.yDp)
            view.documentHeight = 10_000
            view.drawFrame(Canvas(bitmap))
            assertEquals(100, view.scrollY)
        } finally { bitmap.recycle(); view.destroy() }
    }

    @Test fun mouseWheelScrollTakesPriorityOverDelayedRestoration() {
        val saved = MarkdownViewportState("file", 0f, 800f)
        val view = ScrollRestoreWebView(saved)
        val bitmap = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888)
        val event = MotionEvent.obtain(0, 10, MotionEvent.ACTION_SCROLL, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
            arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_VSCROLL, 1f) }),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
        try {
            view.documentHeight = 380
            view.drawFrame(Canvas(bitmap))
            view.onGenericMotionEvent(event)
            view.scrollTo(0, 100)
            view.documentHeight = 10_000
            view.drawFrame(Canvas(bitmap))
            assertEquals(100, view.scrollY)
            assertEquals(100f / view.resources.displayMetrics.density, saved.yDp)
        } finally { event.recycle(); bitmap.recycle(); view.destroy() }
    }

    @Test fun nonScrollingKeysKeepDelayedRestoration() {
        val saved = MarkdownViewportState("file", 0f, 800f)
        val view = ScrollRestoreWebView(saved)
        val bitmap = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888)
        try {
            view.documentHeight = 380
            view.drawFrame(Canvas(bitmap))
            view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_SHIFT_LEFT))
            view.documentHeight = 10_000
            view.drawFrame(Canvas(bitmap))
            assertEquals((800 * view.resources.displayMetrics.density).toInt(), view.scrollY)
        } finally { bitmap.recycle(); view.destroy() }
    }

    private class ScrollRestoreWebView(state: MarkdownViewportState) : MarkdownViewportWebView(RuntimeEnvironment.getApplication(), state) {
        var documentHeight = 10_000
        init { right = 260; bottom = 180 }
        override fun computeVerticalScrollRange() = documentHeight
        override fun computeHorizontalScrollRange() = width
        fun drawFrame(canvas: Canvas) = onDraw(canvas)
        fun touch(action: Int, y: Float) {
            val event = MotionEvent.obtain(0, 10, action, 20f, y, 0)
            try { onTouchEvent(event) } finally { event.recycle() }
        }
    }

    @Test fun genericMarkdownDelegatesLinksExternallyAfterRepositoryContent() {
        val resources = mockk<RepoDocumentResources>(relaxed = true) {
            every { document } returns RepoDocumentContext("owner", "repo", "main", "README.md")
        }
        val externalLinks = mutableListOf<String>()
        val currentResources = mutableStateOf<RepoDocumentResources?>(null)
        lateinit var root: View
        composeRule.setContent {
            val view = LocalView.current
            SideEffect { root = view.rootView }
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { externalLinks.add(uri) }
            }) {
                MaterialTheme {
                    MarkdownWebView(
                        "<a href='https://example.com'>External</a>",
                        documentResources = currentResources.value,
                    )
                }
            }
        }
        fun findWebView(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        composeRule.runOnIdle {
            assertTrue(shadowOf(requireNotNull(findWebView(root))).webViewClient is ExternalLinkWebViewClient)
            currentResources.value = resources
        }
        composeRule.runOnIdle {
            assertTrue(shadowOf(requireNotNull(findWebView(root))).webViewClient is RepoDocumentWebViewClient)
            currentResources.value = null
        }
        composeRule.runOnIdle {
            val webView = requireNotNull(findWebView(root))
            val request = mockk<WebResourceRequest> {
                every { url } returns Uri.parse("https://example.com")
                every { isForMainFrame } returns true
            }
            assertTrue(requireNotNull(shadowOf(webView).webViewClient).shouldOverrideUrlLoading(webView, request))
            assertEquals(listOf("https://example.com"), externalLinks)
            assertTrue(shadowOf(webView).lastLoadDataWithBaseURL.data.contains("External"))
        }
    }


    @Test fun embeddedMarkdownUpdatesLinkThemeAndPaddingWithoutReplacingWebView() {
        val linkColor = mutableStateOf(Color(0xFF123456))
        val padding = mutableStateOf(PaddingValues(0.dp))
        lateinit var root: View
        composeRule.setContent {
            val view = LocalView.current
            SideEffect { root = view.rootView }
            MaterialTheme {
                MarkdownWebView("<a href='https://example.com'>Guide</a>",
                    linkColor = linkColor.value, contentPadding = padding.value)
            }
        }
        fun findWebView(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        lateinit var original: WebView
        lateinit var originalPage: Any
        composeRule.runOnIdle {
            original = requireNotNull(findWebView(root))
            originalPage = original.tag
            val html = shadowOf(original).lastLoadDataWithBaseURL.data
            assertTrue(html.contains("color: #123456; text-decoration: underline;"))
            assertTrue(html.contains("padding: 0.0px 0.0px 0.0px 0.0px"))
            linkColor.value = Color(0xFF876543)
        }
        composeRule.runOnIdle {
            assertSame(original, findWebView(root))
            assertTrue(originalPage !== original.tag)
            assertTrue(shadowOf(original).lastLoadDataWithBaseURL.data.contains("color: #876543;"))
            originalPage = original.tag
            padding.value = PaddingValues(start = 3.dp, top = 1.dp, end = 4.dp, bottom = 2.dp)
        }
        composeRule.runOnIdle {
            assertSame(original, findWebView(root))
            assertTrue(originalPage !== original.tag)
            assertTrue(shadowOf(original).lastLoadDataWithBaseURL.data.contains("padding: 1.0px 4.0px 2.0px 3.0px"))
        }
    }

    @Test fun genericMarkdownDelegatesEmailLinksThroughBothCallbacks() {
        val webView = mockk<WebView> { every { url } returns "file:///android_asset/" }
        val links = mutableListOf<String>()
        val client = ExternalLinkWebViewClient { links.add(it) }
        val email = "mailto:dev@example.com?subject=Feedback&body=Hello%20there"
        val request = mockk<WebResourceRequest> {
            every { url } returns Uri.parse(email)
            every { isForMainFrame } returns true
        }

        assertTrue(client.shouldOverrideUrlLoading(webView, request))
        @Suppress("DEPRECATION")
        assertTrue(client.shouldOverrideUrlLoading(webView, email))
        assertEquals(listOf(email, email), links)
    }

    @Test fun genericMarkdownStillBlocksUnsafeLinksAndSubframeNavigation() {
        val webView = mockk<WebView> { every { url } returns "file:///android_asset/" }
        val links = mutableListOf<String>()
        val client = ExternalLinkWebViewClient { links.add(it) }
        listOf("javascript:alert(1)", "data:text/html,example", "file:///private.txt",
            "content://example/private", "intent://example", "https://user:secret@example.com").forEach { link ->
            val request = mockk<WebResourceRequest> {
                every { url } returns Uri.parse(link)
                every { isForMainFrame } returns true
            }
            assertTrue(client.shouldOverrideUrlLoading(webView, request))
        }
        val frameRequest = mockk<WebResourceRequest> {
            every { url } returns Uri.parse("mailto:dev@example.com")
            every { isForMainFrame } returns false
        }
        assertTrue(client.shouldOverrideUrlLoading(webView, frameRequest))
        assertTrue(links.isEmpty())
    }

    @Test fun genericMarkdownKeepsPageAnchorsInsideTheDocument() {
        val webView = mockk<WebView> {
            every { url } returns "file:///android_asset/"
        }
        val links = mutableListOf<String>()
        val client = ExternalLinkWebViewClient { links.add(it) }
        val request = mockk<WebResourceRequest> {
            every { url } returns Uri.parse("file:///android_asset/#section")
            every { isForMainFrame } returns true
        }
        assertFalse(client.shouldOverrideUrlLoading(webView, request))
        assertTrue(links.isEmpty())
    }

    @Test
    fun `WebView由单子节点FrameLayout托管`() {
        val webView = WebView(RuntimeEnvironment.getApplication())

        val host = createMarkdownWebViewHost(webView)

        assertEquals(FrameLayout::class.java, host.javaClass)
        assertEquals(1, host.childCount)
        assertSame(webView, host.getChildAt(0))
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, host.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, host.layoutParams.height)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, webView.layoutParams.height)

        releaseMarkdownWebViewHost(host)
    }

    @Test
    fun `释放时先从FrameLayout移除WebView`() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        val host = createMarkdownWebViewHost(webView)

        releaseMarkdownWebViewHost(host)

        assertEquals(0, host.childCount)
        assertNull(webView.parent)
    }

    @Test
    fun `生成的Markdown文档使用Material主题覆盖GitHub固定背景`() {
        val document = buildMarkdownDocument(
            bodyHtml = "<h1>Hello</h1>",
            stylesheet = ".markdown-body { color: black; }",
            backgroundCss = "#123456",
        )

        assertTrue(document.startsWith("<!doctype html>"))
        assertTrue(document.contains("background-color: #123456 !important;"))
        assertTrue(document.contains("padding: 0 16px 16px"))
        assertTrue(document.contains("<article class=\"markdown-body\"><h1>Hello</h1></article>"))
        assertFalse(document.contains("border-color:"))
        assertFalse(document.contains("background-color: #ffffff"))
        assertFalse(document.contains("background-color: #0d1117"))
    }

    @Test
    fun `生成的Markdown文档使用调用方传入的容器背景`() {
        val document = buildMarkdownDocument(
            bodyHtml = "<p>Comment</p>",
            stylesheet = ".markdown-body { color: white; }",
            backgroundCss = "#202124",
        )

        assertTrue(document.contains("background-color: #202124 !important;"))
        assertTrue(document.contains(".markdown-body { color: white; }"))
    }

    @Test
    fun `内部垂直滚动被钳死为零`() {
        val webView = NoVerticalScrollWebView(RuntimeEnvironment.getApplication())

        webView.scrollTo(0, 100)
        assertEquals(0, webView.scrollY)

        webView.scrollBy(0, 50)
        assertEquals(0, webView.scrollY)
    }

    @Test
    fun `钳死垂直滚动不影响横向滚动`() {
        val webView = NoVerticalScrollWebView(RuntimeEnvironment.getApplication())

        webView.scrollTo(30, 100)

        assertEquals(30, webView.scrollX)
        assertEquals(0, webView.scrollY)
    }

    @Test
    fun `Chromium直写滚动位置后由onScrollChanged拉回零`() {
        val webView = NoVerticalScrollWebView(RuntimeEnvironment.getApplication())

        // 模拟 Chromium 胶水层绕过 scrollTo 直写垂直偏移（fling 的真实路径），
        // 然后触发必经的 onScrollChanged 回调。
        ReflectionHelpers.setField(webView, "mScrollY", 120)
        ReflectionHelpers.callInstanceMethod<Unit>(
            webView,
            "onScrollChanged",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 120),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0),
        )

        assertEquals(0, webView.scrollY)
    }

    private fun snapshot(version: Long = 1, values: Map<Int, String> = mapOf(0 to "你好")) = TranslationPresentation(
        values, version, "bilingual", 7,
    )

    @Test fun controlledDocumentUsesNonceAndTextPatchEscapesTranslations() {
        val html = buildMarkdownDocument("<p>Safe</p>", "", "#ffffff", "window.test = true", "unique", "doc")
        assertTrue(html.contains("script-src 'nonce-unique'"))
        assertTrue(html.contains("connect-src 'none'"))
        assertTrue(html.contains("""<script nonce="unique">"""))
        assertFalse(buildMarkdownDocument("<p>Static</p>", "", "#ffffff").contains("<script"))
        val hostile = "\"</script><img onerror=alert(1)>\\\n"
        val patch = JSONObject(translationPatch("doc", snapshot(values = mapOf(0 to hostile)), 1))
        assertEquals(hostile, patch.getJSONObject("values").getString("0"))
    }

    @Test fun controllerQueuesLatestSnapshotAndAppliesBatchesWithoutLoadingDocument() {
        val view = mockk<WebView>(relaxed = true)
        val callbacks = mutableListOf<ValueCallback<String>>()
        val scripts = mutableListOf<String>()
        every { view.evaluateJavascript(any(), any()) } answers {
            scripts.add(firstArg()); callbacks.add(secondArg())
        }
        val key = Any()
        every { view.tag } returns key
        var failures = 0
        val controller = MarkdownTranslationController { failures++ }.apply { webView = view }
        controller.begin(key, "doc")
        controller.submit(snapshot())
        assertTrue(scripts.isEmpty())
        controller.pageReady(view)
        callbacks[0].onReceiveValue("""{"documentId":"doc"}""")
        assertEquals(2, scripts.size)
        assertTrue(scripts[1].contains("你好"))
        controller.submit(snapshot(2, mapOf(0 to "你好", 1 to "世界")))
        assertEquals(2, scripts.size)
        callbacks[1].onReceiveValue("""{"documentId":"doc","generation":0,"version":1}""")
        assertEquals(3, scripts.size)
        assertTrue(scripts[2].contains("世界"))
        callbacks[2].onReceiveValue("""{"documentId":"doc","generation":0,"version":2}""")
        controller.submit(snapshot(2, mapOf(0 to "你好", 1 to "世界")))
        assertEquals(3, scripts.size)
        assertEquals(0, failures)
        verify(exactly = 0) { view.loadDataWithBaseURL(any(), any(), any(), any(), any()) }
        controller.release()
    }

    @Test fun clearedTaskRestoresOriginalAfterAnOutstandingTranslationAck() {
        val view = mockk<WebView>(relaxed = true)
        val scripts = mutableListOf<String>()
        val callbacks = mutableListOf<ValueCallback<String>>()
        every { view.evaluateJavascript(any(), any()) } answers {
            scripts += firstArg<String>(); callbacks += secondArg<ValueCallback<String>>()
        }
        val key = Any()
        every { view.tag } returns key
        var failures = 0
        val controller = MarkdownTranslationController { failures++ }.apply { webView = view }
        controller.begin(key, "doc")
        controller.submit(snapshot(99).copy(generation = 100))
        controller.pageReady(view)
        callbacks[0].onReceiveValue("""{"documentId":"doc"}""")
        controller.submit(null)
        callbacks[1].onReceiveValue("""{"documentId":"doc","generation":0,"version":1}""")
        val restored = JSONObject(scripts.last().substringAfter("apply(").dropLast(1))
        assertEquals("original", restored.getString("mode"))
        assertEquals(0, restored.getJSONObject("values").length())
        assertEquals(2L, restored.getLong("version"))
        callbacks[2].onReceiveValue("""{"documentId":"doc","generation":0,"version":2}""")
        controller.submit(snapshot().copy(generation = 1))
        val next = JSONObject(scripts.last().substringAfter("apply(").dropLast(1))
        assertEquals(3L, next.getLong("version"))
        callbacks[3].onReceiveValue("""{"documentId":"doc","generation":0,"version":3}""")
        assertEquals(0, failures)
        verify(exactly = 0) { view.loadDataWithBaseURL(any(), any(), any(), any(), any()) }
        controller.release()
    }

    @Test fun controllerIgnoresOldPageAckAndCanReplayAfterDisplayFailure() {
        val view = mockk<WebView>(relaxed = true)
        val callbacks = mutableListOf<ValueCallback<String>>()
        every { view.evaluateJavascript(any(), any()) } answers { callbacks.add(secondArg()) }
        var key = Any()
        every { view.tag } answers { key }
        var failures = 0
        val controller = MarkdownTranslationController { failures++ }.apply { webView = view }
        controller.begin(key, "old")
        controller.submit(snapshot())
        controller.pageReady(view)
        key = Any(); controller.begin(key, "new")
        callbacks[0].onReceiveValue("""{"documentId":"old"}""")
        assertEquals(0, failures)
        assertEquals(1, callbacks.size)
        controller.pageReady(view)
        callbacks[1].onReceiveValue("""{"documentId":"old"}""")
        assertEquals(0, failures)
        controller.pageError(view)
        assertEquals(1, failures)
        key = Any(); controller.begin(key, "retry")
        controller.submit(snapshot())
        controller.pageReady(view)
        callbacks[2].onReceiveValue("""{"documentId":"retry"}""")
        assertEquals(4, callbacks.size)
        callbacks[3].onReceiveValue("""{"documentId":"retry","generation":0,"version":1}""")
        assertEquals(1, failures)
        controller.release()
    }

    private class DisplayHarness {
        val view = mockk<WebView>(relaxed = true)
        val callbacks = mutableListOf<ValueCallback<String>>()
        val scripts = mutableListOf<String>()
        val timers = mutableListOf<Runnable>()
        var failures = 0
        var reloads = 0
        val displayed = mutableListOf<TranslationPresentation?>()
        val controller = MarkdownTranslationController({ reloads++ }, { displayed.add(it) }, { failures++ })
        init {
            every { view.tag } returns Any()
            every { view.evaluateJavascript(any(), any()) } answers {
                scripts.add(firstArg()); callbacks.add(secondArg())
            }
            every { view.postDelayed(any(), any()) } answers { timers.add(firstArg()); true }
            controller.webView = view
            controller.begin(view.tag, "doc")
        }
        fun ready() = controller.scriptReady(view, "doc")
        fun patch() = JSONObject(scripts.last().substringAfter("apply(").dropLast(1))
        fun ack() = callbacks.last().onReceiveValue(patch().apply { remove("mode"); remove("values") }.toString())
        fun probeReady() = callbacks.last().onReceiveValue("""{"documentId":"doc"}""")
        fun assertNoReload() {
            assertEquals(0, reloads)
            verify(exactly = 0) { view.loadDataWithBaseURL(any(), any(), any(), any(), any()) }
        }
    }

    @Test fun scriptReadinessAppliesBeforeImagesFinishAndLateReadinessClearsFailure() {
        for (late in listOf(false, true)) {
            val h = DisplayHarness()
            val value = snapshot()
            h.controller.submit(value)
            if (late) h.timers.last().run()
            assertEquals(if (late) 1 else 0, h.failures)
            h.ready()
            assertEquals(1, h.scripts.size)
            assertEquals("bilingual", h.patch().getString("mode"))
            h.ack()
            assertEquals(listOf(value), h.displayed)
            h.controller.pageReady(h.view)
            assertEquals(1, h.scripts.size)
            h.assertNoReload()
            h.controller.release()
        }
    }

    @Test fun failedApplyCanRestoreOriginalOrDisableTranslationInTheSameDocument() {
        for (timeout in listOf(false, true)) for (disable in listOf(false, true)) {
            val h = DisplayHarness()
            h.controller.submit(snapshot()); h.ready()
            val obsolete = h.callbacks.last()
            if (timeout) h.timers.last().run() else obsolete.onReceiveValue("null")
            assertEquals(1, h.failures)
            val original = if (disable) null else snapshot(2).copy(mode = "original")
            h.controller.submit(original)
            h.probeReady()
            assertEquals("original", h.patch().getString("mode"))
            h.ack()
            obsolete.onReceiveValue("null")
            assertEquals(1, h.failures)
            assertEquals(listOf(original), h.displayed)
            h.assertNoReload()
            h.controller.release()
        }
    }

    @Test fun originalRequestedWhileTranslationIsPendingStillAppliesAfterItsTimeout() {
        val h = DisplayHarness()
        h.controller.submit(snapshot()); h.ready()
        h.controller.submit(null)
        h.timers.last().run()
        assertEquals("original", h.patch().getString("mode"))
        h.ack()
        assertEquals(0, h.failures)
        assertEquals(listOf<TranslationPresentation?>(null), h.displayed)
        h.assertNoReload()
        h.controller.release()
    }

    @Test fun displayRetryAndProviderSwitchReuseTheWorkingDocument() {
        for (retry in listOf(false, true)) {
            val h = DisplayHarness()
            val initial = snapshot()
            h.controller.submit(initial); h.ready()
            h.callbacks.last().onReceiveValue("null")
            val next = if (retry) initial else initial.copy(generation = 8)
            h.controller.submit(next, if (retry) 1 else 0)
            h.probeReady(); h.ack()
            assertEquals(listOf(next), h.displayed)
            h.assertNoReload()
            h.controller.release()
        }
    }

    @Test fun recoveryReloadsOnlyWhenTheCurrentDocumentCannotAnswer() {
        for (timeout in listOf(false, true)) {
            val h = DisplayHarness()
            h.controller.submit(snapshot()); h.ready()
            h.callbacks.last().onReceiveValue("null")
            assertEquals(0, h.reloads)
            h.controller.submit(snapshot(), 1)
            if (timeout) h.timers.last().run() else h.callbacks.last().onReceiveValue("null")
            assertEquals(1, h.reloads)
            h.timers.last().run()
            assertEquals(1, h.reloads)
            h.controller.release()
        }
    }

    @Test fun detachedRendererDeliversLatestDisplayOutcomeAfterRebinding() {
        val state = MarkdownWebViewState(null)
        val view = mockk<WebView>(relaxed = true)
        val host = mockk<FrameLayout>(relaxed = true)
        val callbacks = mutableListOf<ValueCallback<String>>()
        every { view.tag } returns Any()
        every { view.parent } returns host
        every { view.evaluateJavascript(any(), any()) } answers { callbacks.add(secondArg()) }
        state.controller.webView = view
        state.controller.begin(view.tag, "doc")
        var errors = 0
        var successes = 0
        state.bindDisplayCallbacks({ errors++ }, { successes++ })
        state.controller.submit(snapshot())
        state.controller.scriptReady(view, "doc")
        state.detach(host)
        callbacks.last().onReceiveValue("null")
        state.controller.submit(null)
        callbacks.last().onReceiveValue("""{"documentId":"doc"}""")
        callbacks.last().onReceiveValue("""{"documentId":"doc","generation":0,"version":2}""")
        assertEquals(0, errors)
        assertEquals(0, successes)
        state.bindDisplayCallbacks({ errors++ }, { successes++ })
        assertEquals(0, errors)
        assertEquals(1, successes)
        state.close()
    }

    @Test fun staleReadinessAndCallbacksCannotUpdateAnotherDocument() {
        val h = DisplayHarness()
        h.controller.submit(snapshot()); h.ready()
        val stale = h.callbacks.last()
        val key = Any()
        every { h.view.tag } returns key
        h.controller.begin(key, "new")
        h.controller.submit(snapshot())
        h.controller.scriptReady(h.view, "doc")
        stale.onReceiveValue("null")
        assertEquals(1, h.scripts.size)
        assertEquals(0, h.failures)
        h.controller.scriptReady(h.view, "new")
        assertEquals(2, h.scripts.size)
        h.ack()
        h.controller.release()
    }

    @Test fun translationKeepsRendererAndOuterScrollWhileContentResizes() {
        val body = mutableStateOf("<p>Hello</p><p>World</p>")
        val document = TranslationDocuments.create(TranslationTarget("readme", body.value, true), TranslationProvider.EDGE)
        val presentation = mutableStateOf<TranslationPresentation?>(null)
        lateinit var root: View
        lateinit var scroll: ScrollState
        lateinit var original: WebView
        composeRule.setContent {
            val local = LocalView.current
            SideEffect { root = local.rootView }
            scroll = rememberScrollState()
            MaterialTheme {
                Column(Modifier.height(200.dp).verticalScroll(scroll)) {
                    MarkdownWebView(body.value, preparedDocument = document.takeIf { it.target.content == body.value }, translation = presentation.value)
                }
            }
        }
        composeRule.runOnIdle {
            original = requireNotNull(findWebView(root))
            notifyContentHeight(original, 3000)
        }
        composeRule.runOnIdle { runBlocking { scroll.scrollTo(1000) } }
        composeRule.runOnIdle {
            assertEquals(1000, scroll.value)
            presentation.value = TranslationPresentation(emptyMap(), 0, "translation", 1)
        }
        composeRule.runOnIdle {
            val replacement = requireNotNull(findWebView(root))
            assertSame(original, replacement)
            assertEquals(1, (shadowOf(original) as CountingWebViewShadow).pageLoads)
            assertEquals(1000, scroll.value)
            assertEquals(3000 - scroll.viewportSize, scroll.maxValue)
            notifyContentHeight(replacement, 4000)
        }
        composeRule.runOnIdle {
            assertEquals(1000, scroll.value)
            assertEquals(4000 - scroll.viewportSize, scroll.maxValue)
            notifyContentHeight(requireNotNull(findWebView(root)), 2000)
        }
        composeRule.runOnIdle {
            assertEquals(1000, scroll.value)
            assertEquals(2000 - scroll.viewportSize, scroll.maxValue)
            notifyContentHeight(requireNotNull(findWebView(root)), 600)
        }
        composeRule.runOnIdle {
            assertEquals(600 - scroll.viewportSize, scroll.maxValue)
            assertEquals(scroll.maxValue, scroll.value)
            body.value = "<p>A different document</p>"
            presentation.value = null
        }
        composeRule.runOnIdle {
            assertEquals(0, scroll.maxValue)
            notifyContentHeight(requireNotNull(findWebView(root)), 600)
        }
        composeRule.runOnIdle { assertEquals(600 - scroll.viewportSize, scroll.maxValue) }
    }

    @Test fun displayFailureLeavesTheCurrentBodyAndScrollInPlace() {
        val body = "<p>Original README</p>"
        val document = TranslationDocuments.prepareHtml(TranslationTarget("readme", body, true))
        val navigation = NavigationDocument(null)
        val failed = mutableStateOf(false)
        val shown = mutableStateOf(true)
        lateinit var root: View
        lateinit var scroll: ScrollState
        lateinit var view: WebView
        lateinit var loaded: String
        composeRule.setContent {
            val local = LocalView.current
            SideEffect { root = local.rootView }
            scroll = rememberScrollState()
            MaterialTheme {
                if (shown.value) Column(Modifier.height(200.dp).verticalScroll(scroll)) {
                    if (failed.value) Spacer(Modifier.height(40.dp))
                    MarkdownWebView(body, preparedDocument = document, navigationDocument = navigation,
                        translation = snapshot(), onTranslationDisplayError = { failed.value = true })
                }
            }
        }
        composeRule.runOnIdle {
            view = requireNotNull(findWebView(root))
            loaded = shadowOf(view).lastLoadDataWithBaseURL.data
            notifyContentHeight(view, 3000)
        }
        composeRule.runOnIdle { runBlocking { scroll.scrollTo(1000) } }
        composeRule.runOnIdle { navigation.renderer().controller.pageError(view) }
        composeRule.runOnIdle {
            assertTrue(failed.value)
            assertSame(view, findWebView(root))
            assertEquals(loaded, shadowOf(view).lastLoadDataWithBaseURL.data)
            assertEquals(1, (shadowOf(view) as CountingWebViewShadow).pageLoads)
            assertEquals(3000, (view as NoVerticalScrollWebView).layoutSize.height)
            assertEquals(1000, scroll.value)
            shown.value = false
        }
        composeRule.runOnIdle { navigation.close() }
    }

    @Test fun translationKeepsNavigationRendererAndLazyListItemOffset() {
        val body = "<p>Hello</p><p>World</p>"
        val document = TranslationDocuments.create(TranslationTarget("readme", body, true), TranslationProvider.EDGE)
        val presentation = mutableStateOf<TranslationPresentation?>(null)
        lateinit var root: View
        lateinit var scroll: LazyListState
        lateinit var original: WebView
        composeRule.setContent {
            val local = LocalView.current
            SideEffect { root = local.rootView }
            scroll = rememberLazyListState()
            MaterialTheme {
                LazyColumn(Modifier.height(200.dp), state = scroll) {
                    item(key = "body") {
                        val navigationDocument = rememberNavigationDocument(body, null, 0) { error("No resources needed") }
                        Column {
                            Spacer(Modifier.height(48.dp))
                            MarkdownWebView(body, preparedDocument = document, translation = presentation.value, navigationDocument = navigationDocument)
                        }
                    }
                    items(5, key = { "following:$it" }) { Spacer(Modifier.height(100.dp)) }
                }
            }
        }
        composeRule.runOnIdle {
            original = requireNotNull(findWebView(root))
            notifyContentHeight(original, 3000)
        }
        composeRule.runOnIdle { runBlocking { scroll.scrollToItem(0, 1000) } }
        composeRule.runOnIdle {
            assertEquals(0, scroll.firstVisibleItemIndex)
            assertEquals(1000, scroll.firstVisibleItemScrollOffset)
            presentation.value = TranslationPresentation(emptyMap(), 0, "translation", 1)
        }
        composeRule.runOnIdle {
            val replacement = requireNotNull(findWebView(root))
            assertSame(original, replacement)
            assertEquals(1, (shadowOf(original) as CountingWebViewShadow).pageLoads)
            assertEquals(0, scroll.firstVisibleItemIndex)
            assertEquals(1000, scroll.firstVisibleItemScrollOffset)
            notifyContentHeight(replacement, 4000)
        }
        composeRule.runOnIdle {
            assertEquals(0, scroll.firstVisibleItemIndex)
            assertEquals(1000, scroll.firstVisibleItemScrollOffset)
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    // Robolectric does not lay out HTML; deliver Chromium's size notification explicitly.
    private fun notifyContentHeight(view: WebView, height: Int) {
        ReflectionHelpers.callInstanceMethod<Unit>(view, "onSizeChanged",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 360),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, height),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0))
    }

    @Test fun allTranslationStatesKeepTheInitialPageAndSharedResources() {
        val resources = mockk<RepoDocumentResources>(relaxed = true) {
            every { document } returns RepoDocumentContext("owner", "repo", "main", "README.md")
        }
        val presentation = mutableStateOf<TranslationPresentation?>(null)
        val shown = mutableStateOf(true)
        val retry = mutableStateOf(0)
        val document = TranslationDocuments.prepareHtml(TranslationTarget("doc", "<p>Hello</p><p>World</p>", true))
        lateinit var root: View
        lateinit var staticView: WebView
        lateinit var controlledView: WebView
        lateinit var loadedHtml: String
        fun find(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        composeRule.setContent {
            val local = LocalView.current
            SideEffect { root = local.rootView }
            if (shown.value) MaterialTheme {
                MarkdownWebView("<p>Hello</p><p>World</p>", preparedDocument = document, documentResources = resources,
                    translation = presentation.value, displayAttempt = retry.value)
            }
        }
        composeRule.runOnIdle {
            staticView = requireNotNull(find(root))
            assertTrue(staticView.settings.javaScriptEnabled)
            assertEquals(1, (shadowOf(staticView) as CountingWebViewShadow).pageLoads)
            presentation.value = snapshot()
        }
        composeRule.runOnIdle {
            controlledView = requireNotNull(find(root))
            assertSame(staticView, controlledView)
            assertTrue(controlledView.settings.javaScriptEnabled)
            assertFalse(controlledView.settings.allowFileAccess)
            assertFalse(controlledView.settings.allowContentAccess)
            loadedHtml = shadowOf(controlledView).lastLoadDataWithBaseURL.data
            verify(exactly = 0) { resources.close() }
            presentation.value = snapshot(2, mapOf(0 to "你好", 1 to "世界"))
        }
        composeRule.runOnIdle {
            assertSame(controlledView, find(root))
            assertEquals(loadedHtml, shadowOf(controlledView).lastLoadDataWithBaseURL.data)
        }
        val states = listOf(
            snapshot(3).copy(mode = "original"),
            snapshot(4).copy(mode = "bilingual"),
            snapshot(1).copy(generation = 8),
            snapshot(1).copy(generation = 9),
            null,
        )
        for (state in states) {
            composeRule.runOnIdle { presentation.value = state }
            composeRule.runOnIdle {
                assertSame(controlledView, find(root))
                assertEquals(1, (shadowOf(controlledView) as CountingWebViewShadow).pageLoads)
            }
        }
        composeRule.runOnIdle { retry.value = 1 }
        composeRule.runOnIdle {
            assertSame(controlledView, find(root))
            assertEquals(1, (shadowOf(controlledView) as CountingWebViewShadow).pageLoads)
            retry.value = 0
        }
        composeRule.runOnIdle {
            assertEquals(1, (shadowOf(controlledView) as CountingWebViewShadow).pageLoads)
            shown.value = false
        }
        composeRule.runOnIdle { verify(exactly = 1) { resources.close() } }
    }

    @Test fun htmlHostPreparesOnceBeforeDisplayAndRetainsPageAcrossTaskAndNavigationChanges() {
        val body = "<p onclick='evil()'>Hello</p><p>World</p>"
        val target = TranslationTarget("readme", body, true)
        val navigation = NavigationDocument(null)
        val vm = mockk<TranslationViewModel>(relaxed = true)
        val enabled = TranslationConfiguration(TranslationPreferences(enabled = true))
        val state = mutableStateOf(TranslationUiState(configuration = enabled))
        val shown = mutableStateOf(true)
        lateinit var root: View
        lateinit var original: WebView
        composeRule.setContent {
            val local = LocalView.current
            SideEffect { root = local.rootView }
            MaterialTheme {
                CompositionLocalProvider(LocalTranslationController provides TranslationController(state.value, vm) {}) {
                    if (shown.value) TranslationHtml(target.key, body, navigationDocument = navigation)
                }
            }
        }
        composeRule.waitUntil(5000) { findWebView(root) != null }
        composeRule.runOnIdle {
            original = requireNotNull(findWebView(root))
            val html = shadowOf(original).lastLoadDataWithBaseURL.data
            assertTrue(original.settings.javaScriptEnabled)
            assertTrue(html.contains("data-gm-unit"))
            assertFalse(html.contains("evil()"))
            verify { vm wasNot Called }
        }
        val prepared = requireNotNull(navigation.translationDocument)
        val entry = TranslationEntry(document = prepared, translations = mapOf(0 to "你好"), generation = 7, version = 10)
        for (entryState in listOf(entry, entry.copy(showOriginal = true), entry.copy(preferences = enabled.preferences.copy(bilingual = true)), null)) {
            composeRule.runOnIdle {
                state.value = state.value.copy(entries = entryState?.let { mapOf(target.identity to it) }.orEmpty())
            }
            composeRule.runOnIdle {
                assertSame(original, findWebView(root))
                assertSame(prepared, navigation.translationDocument)
                assertEquals(1, (shadowOf(original) as CountingWebViewShadow).pageLoads)
            }
        }
        composeRule.runOnIdle { shown.value = false }
        composeRule.runOnIdle { shown.value = true }
        composeRule.runOnIdle {
            assertSame(original, findWebView(root))
            assertEquals(1, (shadowOf(original) as CountingWebViewShadow).pageLoads)
            assertSame(prepared, navigation.translationDocument)
            shown.value = false
        }
        composeRule.runOnIdle { navigation.close() }
    }

    @Test fun fullscreenTranslationKeepsABoundedScrollingWebViewAcrossNavigation() {
        val body = "<p>long document</p>".repeat(30_000)
        val target = TranslationTarget("file-preview", body, true)
        val navigation = NavigationDocument(null)
        val vm = mockk<TranslationViewModel>(relaxed = true)
        val state = mutableStateOf(TranslationUiState())
        val shown = mutableStateOf(true)
        lateinit var root: View
        lateinit var original: WebView
        composeRule.setContent {
            root = LocalView.current.rootView
            MaterialTheme {
                CompositionLocalProvider(LocalTranslationController provides TranslationController(state.value, vm) {}) {
                    Box(Modifier.size(260.dp, 180.dp)) {
                        if (shown.value) TranslationHtml(target.key, body,
                            modifier = Modifier.fillMaxSize(), navigationDocument = navigation,
                            layoutMode = MarkdownLayoutMode.Viewport)
                    }
                }
            }
        }
        composeRule.waitUntil(10_000) { findWebView(root) != null }
        composeRule.runOnIdle {
            original = requireNotNull(findWebView(root))
            assertTrue(original is MarkdownContentWebView)
            assertFalse(original is NoVerticalScrollWebView)
            // Chromium is not run by Robolectric; the real Compose host supplies the bounded viewport.
            val host = original.parent as View
            assertTrue(host.height in 1..1_000)
            assertEquals((180 * host.resources.displayMetrics.density).toInt(), host.height)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, original.layoutParams.height)
            assertTrue(original.isVerticalScrollBarEnabled)
            original.scrollTo(0, 1200)
            assertEquals(1200, original.scrollY)
            val prepared = requireNotNull(navigation.translationDocument)
            state.value = TranslationUiState(entries = mapOf(target.identity to TranslationEntry(
                document = prepared, translations = mapOf(0 to "译文"), generation = 1, version = 1)))
        }
        composeRule.runOnIdle {
            assertSame(original, findWebView(root))
            assertEquals(1200, original.scrollY)
            assertEquals(1, (shadowOf(original) as CountingWebViewShadow).pageLoads)
            shown.value = false
        }
        composeRule.runOnIdle { shown.value = true }
        composeRule.runOnIdle {
            assertSame(original, findWebView(root))
            assertEquals(1200, original.scrollY)
            assertEquals(1, (shadowOf(original) as CountingWebViewShadow).pageLoads)
            shown.value = false
        }
        composeRule.runOnIdle { navigation.close() }
    }

    @Test fun viewportScrollRestoresOnlyWhenContentCanAccommodateIt() {
        val saved = MarkdownViewportState("file", 0f, 300f)
        val view = object : MarkdownViewportWebView(RuntimeEnvironment.getApplication(), saved) {
            var documentHeight = 100
            override fun computeVerticalScrollRange() = documentHeight
            override fun computeHorizontalScrollRange() = width
            fun drawFrame(canvas: Canvas) = onDraw(canvas)
        }
        // ShadowWebView has no Chromium provider to apply setFrame during layout.
        view.right = 260
        view.bottom = 180
        assertEquals(260, view.width)
        assertEquals(180, view.height)
        val bitmap = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888)
        try {
            // Drive a Chromium draw callback explicitly; ShadowWebView has no renderer.
            view.drawFrame(Canvas(bitmap))
            assertEquals(0, view.scrollY)
            assertEquals(300f, saved.yDp)
            view.documentHeight = 280
            view.drawFrame(Canvas(bitmap))
            assertEquals(100, view.scrollY)
            assertEquals(300f, saved.yDp)
            view.documentHeight = 10_000
            // Drive a Chromium draw callback explicitly; ShadowWebView has no renderer.
            view.drawFrame(Canvas(bitmap))
            assertEquals((300 * view.resources.displayMetrics.density).toInt(), view.scrollY)
            view.scrollTo(0, 100)
            val saver = MarkdownViewportState.saver("file")
            val data = with(saver) { SaverScope { true }.save(saved) }!!
            assertEquals(saved.yDp, saver.restore(data)!!.yDp)
            assertEquals(0f, MarkdownViewportState.saver("other-file").restore(data)!!.yDp)
            val reentered = MarkdownViewportState("file")
            view.bindViewportState(reentered)
            assertEquals(saved.yDp, reentered.yDp)
            view.scrollTo(0, 200)
            assertEquals(200f / view.resources.displayMetrics.density, reentered.yDp)
        } finally {
            bitmap.recycle()
            view.destroy()
        }
    }

    @Test fun layoutModesHaveSeparateRenderersWithOneDocumentLifetime() {
        val navigation = NavigationDocument(null)
        val embedded = navigation.renderer()
        val viewport = navigation.renderer(MarkdownLayoutMode.Viewport)
        assertTrue(embedded !== viewport)
        assertSame(viewport, navigation.renderer(MarkdownLayoutMode.Viewport))
        val first = NoVerticalScrollWebView(RuntimeEnvironment.getApplication())
        val second = MarkdownContentWebView(RuntimeEnvironment.getApplication())
        embedded.controller.webView = first
        viewport.controller.webView = second
        navigation.close()
        assertTrue(shadowOf(first).wasDestroyCalled())
        assertTrue(shadowOf(second).wasDestroyCalled())
    }

    @Implements(WebView::class)
    class CountingWebViewShadow : ShadowWebView() {
        var pageLoads = 0
            private set

        @Implementation
        public override fun loadDataWithBaseURL(baseUrl: String?, data: String?, mimeType: String?, encoding: String?, historyUrl: String?) {
            pageLoads++
            super.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl)
        }
    }

}
