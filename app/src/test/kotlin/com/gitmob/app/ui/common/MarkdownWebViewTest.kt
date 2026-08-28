package com.gitmob.app.ui.common

import android.view.ViewGroup
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
            cssFileName = "github-markdown-light.css",
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
            cssFileName = "github-markdown-dark.css",
            backgroundCss = "#202124",
        )

        assertTrue(document.contains("background-color: #202124 !important;"))
        assertTrue(document.contains("github-markdown-dark.css"))
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
