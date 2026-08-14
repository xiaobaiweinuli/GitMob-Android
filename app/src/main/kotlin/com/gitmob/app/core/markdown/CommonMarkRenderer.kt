package com.gitmob.app.core.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MarkdownRenderer 的 commonmark-java 实现（当前唯一实现）。
 * Parser/HtmlRenderer 本身是线程安全、可复用的（官方文档明确说明"you can re-use
 * parser and renderer instances"），构造一次持有，不要每次渲染都重新 build。
 *
 * 开启的扩展对应 GitHub Flavored Markdown 常用语法：表格、删除线、自动链接、
 * 任务列表（`- [ ]`/`- [x]`）。原始 HTML 标签（<div>/<details> 等）是 CommonMark
 * 规范本身就支持透传的，不需要额外扩展，会原样进最终 HTML，配合 WebView 显示，
 * 见 references/markdown-rendering.md。
 */
@Singleton
class CommonMarkRenderer @Inject constructor() : MarkdownRenderer {

    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        AutolinkExtension.create(),
        TaskListItemsExtension.create(),
    )

    private val parser = Parser.builder().extensions(extensions).build()
    private val htmlRenderer = HtmlRenderer.builder().extensions(extensions).build()

    override fun renderToHtml(markdown: String): String {
        val document = parser.parse(markdown)
        return htmlRenderer.render(document)
    }
}
