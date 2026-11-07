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
}
