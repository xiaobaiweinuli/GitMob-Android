package com.gitmob.app.ui.common

import android.annotation.SuppressLint
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
fun MarkdownWebView(bodyHtml: String, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val background = colorScheme.background
    val cssFileName = if (background.luminance() < 0.5f) {
        "github-markdown-dark.css"
    } else {
        "github-markdown-light.css"
    }
    val backgroundCss = background.toCssHex()

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

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clipToBounds(),
        factory = { context ->
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = false // 渲染 Markdown 不需要执行 JS，默认关闭更安全
                setBackgroundColor(background.toArgb())
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
                                view.parent.requestDisallowInterceptTouchEvent(true)
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
            createMarkdownWebViewHost(webView)
        },
        update = { host ->
            val webView = host.getChildAt(0) as? WebView ?: return@AndroidView
            // CSS 加载完成前也保持与 Compose 相同的底色，避免白/黑闪烁。
            webView.setBackgroundColor(background.toArgb())
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
        onRelease = ::releaseMarkdownWebViewHost,
    )
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
