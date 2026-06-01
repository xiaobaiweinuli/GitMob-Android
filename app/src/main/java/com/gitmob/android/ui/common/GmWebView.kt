package com.gitmob.android.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.gitmob.android.ui.theme.LocalGmColors
import com.gitmob.android.util.MarkdownUtils

/**
 * Markdown 渲染组件
 * 使用 Flexmark 将 Markdown 转换为 HTML，然后用 WebView 配合 github-markdown-css 渲染
 */
@Composable
fun GmMarkdownWebView(
    markdown: String,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
    fontSize: Int = 16,
) {
    val c = LocalGmColors.current
    val isDarkTheme = c.isDark
    
    val htmlContent = remember(markdown, isDarkTheme, fontSize) {
        MarkdownUtils.wrapMarkdownInHtml(markdown, isDarkTheme, fontSize)
    }
    
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val url = request.url.toString()
                        if (onLinkClick != null) {
                            onLinkClick(url)
                        } else {
                            openUrl(context, url)
                        }
                        return true
                    }
                }
                
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                
                loadDataWithBaseURL(
                    null,
                    htmlContent,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                null,
                htmlContent,
                "text/html",
                "UTF-8",
                null
            )
        },
        modifier = modifier
    )
}

/**
 * HTML 文件原生渲染组件。
 *
 * 与 GmMarkdownWebView 的区别：
 * - 输入已经是 HTML 原文（从 GitHub API 获取的 .html / .htm 文件内容）
 * - 不经过 Flexmark 转换，不注入 GitHub Markdown CSS
 * - 完全尊重 HTML 文件自带的样式和脚本
 * - 支持缩放、链接点击拦截
 */
@Composable
fun GmHtmlWebView(
    html: String,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val url = request.url.toString()
                        if (onLinkClick != null) {
                            onLinkClick(url)
                        } else {
                            openUrl(context, url)
                        }
                        return true
                    }
                }

                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = true

                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        },
        modifier = modifier
    )
}

/**
 * 在外部浏览器打开 URL
 */
private fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
