package com.gitmob.app.ui.common

import android.net.Uri
import android.webkit.WebResourceRequest
import android.view.View
import android.view.ViewGroup
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
import android.webkit.ValueCallback
import org.json.JSONObject
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkdownWebViewTest {
    @get:Rule val composeRule = createComposeRule()

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
        TranslationDocuments.create(TranslationTarget("doc", "<p>Hello</p><p>World</p>", html = true), TranslationProvider.EDGE),
        values, version, "bilingual", 7,
    )

    @Test fun controlledDocumentUsesNonceAndTextPatchEscapesTranslations() {
        val html = buildMarkdownDocument("<p>Safe</p>", "", "#ffffff", "window.test = true", "unique", "doc")
        assertTrue(html.contains("script-src 'nonce-unique'"))
        assertTrue(html.contains("connect-src 'none'"))
        assertTrue(html.contains("""<script nonce="unique">"""))
        assertFalse(buildMarkdownDocument("<p>Static</p>", "", "#ffffff").contains("<script"))
        val hostile = "\"</script><img onerror=alert(1)>\\\n"
        val patch = JSONObject(translationPatch("doc", snapshot(values = mapOf(0 to hostile))))
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
        callbacks[1].onReceiveValue("""{"documentId":"doc","generation":7,"version":1}""")
        assertEquals(3, scripts.size)
        assertTrue(scripts[2].contains("世界"))
        callbacks[2].onReceiveValue("""{"documentId":"doc","generation":7,"version":2}""")
        controller.submit(snapshot(2))
        assertEquals(3, scripts.size)
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
        controller.pageReady(view)
        callbacks[2].onReceiveValue("""{"documentId":"retry"}""")
        assertEquals(4, callbacks.size)
        callbacks[3].onReceiveValue("""{"documentId":"retry","generation":7,"version":1}""")
        assertEquals(1, failures)
        controller.release()
    }

    @Test fun enablingTranslationReplacesStaticViewWithoutClosingSharedResources() {
        val resources = mockk<RepoDocumentResources>(relaxed = true) {
            every { document } returns RepoDocumentContext("owner", "repo", "main", "README.md")
        }
        val presentation = mutableStateOf<TranslationPresentation?>(null)
        val shown = mutableStateOf(true)
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
                MarkdownWebView("<p>Hello</p><p>World</p>", documentResources = resources, translation = presentation.value)
            }
        }
        composeRule.runOnIdle {
            staticView = requireNotNull(find(root))
            assertFalse(staticView.settings.javaScriptEnabled)
            presentation.value = snapshot()
        }
        composeRule.runOnIdle {
            controlledView = requireNotNull(find(root))
            assertTrue(controlledView !== staticView)
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
            shown.value = false
        }
        composeRule.runOnIdle { verify(exactly = 1) { resources.close() } }
    }

}
