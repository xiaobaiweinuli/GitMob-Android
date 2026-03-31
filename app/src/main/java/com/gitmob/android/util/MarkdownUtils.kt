package com.gitmob.android.util

import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet

/**
 * Markdown 转 HTML 工具类
 * 使用 Flexmark 库进行解析
 */
object MarkdownUtils {

    private val options = MutableDataSet().apply {
        set(Parser.EXTENSIONS, listOf(AutolinkExtension.create()))
    }

    private val parser: Parser by lazy {
        Parser.builder(options).build()
    }

    private val renderer: HtmlRenderer by lazy {
        HtmlRenderer.builder(options).build()
    }

    /**
     * 将 Markdown 文本转换为 HTML
     */
    fun markdownToHtml(markdown: String): String {
        val document = parser.parse(markdown)
        return renderer.render(document)
    }

    /**
     * 将 Markdown 包装在完整的 HTML 页面中，包含 github-markdown-css 样式
     */
    fun wrapMarkdownInHtml(markdown: String, isDarkTheme: Boolean = false): String {
        val htmlContent = markdownToHtml(markdown)
        
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    html, body {
                        margin: 0;
                        padding: 0;
                        background-color: transparent;
                    }
                    
                    /* 允许内部元素滚动 */
                    .markdown-body pre,
                    .markdown-body table {
                        overflow-x: auto !important;
                    }
                    
                    ${getGithubMarkdownCss(isDarkTheme)}
                    
                    /* 移动端适配 - 透明背景 */
                    .markdown-body {
                        box-sizing: border-box;
                        min-width: 200px;
                        max-width: 100%;
                        margin: 0;
                        padding: 0;
                        background-color: transparent !important;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif, "Apple Color Emoji", "Segoe UI Emoji";
                    }
                    
                    .markdown-body img {
                        max-width: 100%;
                        height: auto;
                        box-sizing: content-box;
                        background-color: transparent;
                    }
                    
                    .markdown-body pre {
                        overflow-x: auto;
                    }
                    
                    .markdown-body code {
                        word-wrap: break-word;
                    }
                    
                    .markdown-body table {
                        display: block;
                        width: 100%;
                        overflow: auto;
                    }
                    
                    /* 移除 body 的背景色 */
                    body {
                        background-color: transparent !important;
                    }
                </style>
            </head>
            <body>
                <div class="markdown-body">
                    $htmlContent
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * 获取 GitHub Markdown CSS 样式
     */
    private fun getGithubMarkdownCss(isDarkTheme: Boolean): String {
        return if (isDarkTheme) {
            """
                :root {
                    --color-canvas-default: #0d1117;
                    --color-canvas-subtle: #161b22;
                    --color-border-default: #30363d;
                    --color-border-muted: #21262d;
                    --color-fg-default: #c9d1d9;
                    --color-fg-muted: #8b949e;
                    --color-fg-subtle: #6e7681;
                    --color-accent-fg: #58a6ff;
                    --color-accent-emphasis: #1f6feb;
                    --color-success-fg: #3fb950;
                    --color-attention-fg: #d29922;
                    --color-severe-fg: #db6d28;
                    --color-danger-fg: #f85149;
                    --color-done-fg: #a371f7;
                }
                
                .markdown-body {
                    color-scheme: dark;
                    -ms-text-size-adjust: 100%;
                    -webkit-text-size-adjust: 100%;
                    margin: 0;
                    color: #c9d1d9;
                    background-color: transparent !important;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif, "Apple Color Emoji", "Segoe UI Emoji";
                    font-size: 16px;
                    line-height: 1.5;
                    word-wrap: break-word;
                }
                
                .markdown-body .octicon {
                    display: inline-block;
                    fill: currentColor;
                    vertical-align: text-bottom;
                }
                
                .markdown-body h1,
                .markdown-body h2,
                .markdown-body h3,
                .markdown-body h4,
                .markdown-body h5,
                .markdown-body h6 {
                    margin-top: 24px;
                    margin-bottom: 16px;
                    font-weight: 600;
                    line-height: 1.25;
                }
                
                .markdown-body h1 {
                    padding-bottom: 0.3em;
                    font-size: 2em;
                    border-bottom: 1px solid #21262d;
                }
                
                .markdown-body h2 {
                    padding-bottom: 0.3em;
                    font-size: 1.5em;
                    border-bottom: 1px solid #21262d;
                }
                
                .markdown-body h3 {
                    font-size: 1.25em;
                }
                
                .markdown-body h4 {
                    font-size: 1em;
                }
                
                .markdown-body h5 {
                    font-size: 0.875em;
                }
                
                .markdown-body h6 {
                    font-size: 0.85em;
                    color: #8b949e;
                }
                
                .markdown-body p {
                    margin-top: 0;
                    margin-bottom: 10px;
                }
                
                .markdown-body ul,
                .markdown-body ol {
                    margin-top: 0;
                    margin-bottom: 0;
                    padding-left: 2em;
                }
                
                .markdown-body li {
                    margin-top: 0.25em;
                }
                
                .markdown-body code {
                    padding: 0.2em 0.4em;
                    margin: 0;
                    font-size: 85%;
                    white-space: break-spaces;
                    background-color: #161b22;
                    border-radius: 6px;
                }
                
                .markdown-body pre {
                    padding: 16px;
                    overflow: auto;
                    font-size: 85%;
                    line-height: 1.45;
                    color: #c9d1d9;
                    background-color: #161b22;
                    border-radius: 6px;
                }
                
                .markdown-body pre > code {
                    padding: 0;
                    margin: 0;
                    font-size: 100%;
                    word-break: normal;
                    white-space: pre;
                    background: transparent;
                    border: 0;
                }
                
                .markdown-body blockquote {
                    padding: 0 1em;
                    color: #8b949e;
                    border-left: 0.25em solid #30363d;
                    margin: 0;
                }
                
                .markdown-body table {
                    border-spacing: 0;
                    border-collapse: collapse;
                    display: block;
                    width: max-content;
                    max-width: 100%;
                    overflow: auto;
                }
                
                .markdown-body table th,
                .markdown-body table td {
                    padding: 6px 13px;
                    border: 1px solid #30363d;
                }
                
                .markdown-body table tr {
                    background-color: transparent !important;
                    border-top: 1px solid #21262d;
                }
                
                .markdown-body img {
                    max-width: 100%;
                    box-sizing: content-box;
                    background-color: transparent !important;
                }
                
                .markdown-body a {
                    color: #58a6ff;
                    text-decoration: none;
                }
                
                .markdown-body a:hover {
                    text-decoration: underline;
                }
                
                .markdown-body hr {
                    height: 0.25em;
                    padding: 0;
                    margin: 24px 0;
                    background-color: #21262d;
                    border: 0;
                }
            """.trimIndent()
        } else {
            """
                :root {
                    --color-canvas-default: #ffffff;
                    --color-canvas-subtle: #f6f8fa;
                    --color-border-default: #d0d7de;
                    --color-border-muted: hsla(210,18%,87%,1);
                    --color-fg-default: #1f2328;
                    --color-fg-muted: #656d76;
                    --color-fg-subtle: #6e7781;
                    --color-accent-fg: #0969da;
                    --color-accent-emphasis: #0969da;
                    --color-success-fg: #1a7f37;
                    --color-attention-fg: #9a6700;
                    --color-severe-fg: #cf222e;
                    --color-danger-fg: #d1242f;
                    --color-done-fg: #8250df;
                }
                
                .markdown-body {
                    color-scheme: light;
                    -ms-text-size-adjust: 100%;
                    -webkit-text-size-adjust: 100%;
                    margin: 0;
                    color: #1f2328;
                    background-color: transparent !important;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif, "Apple Color Emoji", "Segoe UI Emoji";
                    font-size: 16px;
                    line-height: 1.5;
                    word-wrap: break-word;
                }
                
                .markdown-body .octicon {
                    display: inline-block;
                    fill: currentColor;
                    vertical-align: text-bottom;
                }
                
                .markdown-body h1,
                .markdown-body h2,
                .markdown-body h3,
                .markdown-body h4,
                .markdown-body h5,
                .markdown-body h6 {
                    margin-top: 24px;
                    margin-bottom: 16px;
                    font-weight: 600;
                    line-height: 1.25;
                }
                
                .markdown-body h1 {
                    padding-bottom: 0.3em;
                    font-size: 2em;
                    border-bottom: 1px solid hsla(210,18%,87%,1);
                }
                
                .markdown-body h2 {
                    padding-bottom: 0.3em;
                    font-size: 1.5em;
                    border-bottom: 1px solid hsla(210,18%,87%,1);
                }
                
                .markdown-body h3 {
                    font-size: 1.25em;
                }
                
                .markdown-body h4 {
                    font-size: 1em;
                }
                
                .markdown-body h5 {
                    font-size: 0.875em;
                }
                
                .markdown-body h6 {
                    font-size: 0.85em;
                    color: #656d76;
                }
                
                .markdown-body p {
                    margin-top: 0;
                    margin-bottom: 10px;
                }
                
                .markdown-body ul,
                .markdown-body ol {
                    margin-top: 0;
                    margin-bottom: 0;
                    padding-left: 2em;
                }
                
                .markdown-body li {
                    margin-top: 0.25em;
                }
                
                .markdown-body code {
                    padding: 0.2em 0.4em;
                    margin: 0;
                    font-size: 85%;
                    white-space: break-spaces;
                    background-color: #f6f8fa;
                    border-radius: 6px;
                }
                
                .markdown-body pre {
                    padding: 16px;
                    overflow: auto;
                    font-size: 85%;
                    line-height: 1.45;
                    color: #1f2328;
                    background-color: #f6f8fa;
                    border-radius: 6px;
                }
                
                .markdown-body pre > code {
                    padding: 0;
                    margin: 0;
                    font-size: 100%;
                    word-break: normal;
                    white-space: pre;
                    background: transparent;
                    border: 0;
                }
                
                .markdown-body blockquote {
                    padding: 0 1em;
                    color: #656d76;
                    border-left: 0.25em solid #d0d7de;
                    margin: 0;
                }
                
                .markdown-body table {
                    border-spacing: 0;
                    border-collapse: collapse;
                    display: block;
                    width: max-content;
                    max-width: 100%;
                    overflow: auto;
                }
                
                .markdown-body table th,
                .markdown-body table td {
                    padding: 6px 13px;
                    border: 1px solid #d0d7de;
                }
                
                .markdown-body table tr {
                    background-color: transparent !important;
                    border-top: 1px solid hsla(210,18%,87%,1);
                }
                
                .markdown-body img {
                    max-width: 100%;
                    box-sizing: content-box;
                    background-color: transparent !important;
                }
                
                .markdown-body a {
                    color: #0969da;
                    text-decoration: none;
                }
                
                .markdown-body a:hover {
                    text-decoration: underline;
                }
                
                .markdown-body hr {
                    height: 0.25em;
                    padding: 0;
                    margin: 24px 0;
                    background-color: hsla(210,18%,87%,1);
                    border: 0;
                }
            """.trimIndent()
        }
    }
}
