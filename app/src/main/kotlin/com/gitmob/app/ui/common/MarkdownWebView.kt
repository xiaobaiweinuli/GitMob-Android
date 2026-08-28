package com.gitmob.app.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
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
) {
    val cssFileName = if (backgroundColor.luminance() < 0.5f) {
        "github-markdown-dark.css"
    } else {
        "github-markdown-light.css"
    }
    val backgroundCss = backgroundColor.toCssHex()

    // Markdown 正文可能很大，只有正文或主题实际变化时才重新拼装完整文档。
    val fullHtml = remember(
        bodyHtml,
        cssFileName,
        backgroundCss,
    ) {
        buildMarkdownDocument(
            bodyHtml = bodyHtml,
            cssFileName = cssFileName,
            backgroundCss = backgroundCss,
        )
    }
    // AndroidView 的 update 会因无关重组反复执行，用稳定 key 防止 WebView 整页重载。
    val pageKey = remember(fullHtml) { Any() }
    // onGloballyPositioned 回调里需要拿到 factory 创建的 WebView 实例。
    val webViewHolder = remember { WebViewHolder() }

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
                webViewHolder.webView?.postInvalidateOnAnimation()
            },
        factory = { context ->
            val webView = NoVerticalScrollWebView(context).apply {
                settings.javaScriptEnabled = false // 渲染 Markdown 不需要执行 JS，默认关闭更安全
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
            createMarkdownWebViewHost(webView)
        },
        update = { host ->
            val webView = host.getChildAt(0) as? WebView ?: return@AndroidView
            if (webView.tag !== pageKey) {
                webView.tag = pageKey
                webView.loadDataWithBaseURL(
                    "file:///android_asset/",
                    fullHtml,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        },
        onRelease = { host ->
            webViewHolder.webView = null
            releaseMarkdownWebViewHost(host)
        },
    )
}

/** 供 onGloballyPositioned 回调访问 factory 里创建的 WebView，不参与重组。 */
private class WebViewHolder {
    var webView: WebView? = null
}

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
    cssFileName: String,
    backgroundCss: String,
): String =
    """
        <!doctype html>
        <html lang="zh">
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <link rel="stylesheet" href="file:///android_asset/$cssFileName">
            <style>
                html, body, .markdown-body {
                    background-color: $backgroundCss !important;
                }
                body { margin: 0; }
                .markdown-body { padding: 0 16px 16px; box-sizing: border-box; }
                .markdown-body img { max-width: 100%; }
            </style>
        </head>
        <body><article class="markdown-body">$bodyHtml</article></body>
        </html>
    """.trimIndent()

private fun Color.toCssHex(): String =
    String.format(Locale.ROOT, "#%06X", toArgb() and 0x00FFFFFF)
