package com.gitmob.app.core.markdown

import org.junit.Assert.assertTrue
import org.junit.Test

class CommonMarkRendererTest {
    private val renderer = CommonMarkRenderer()

    @Test
    fun `渲染基础Markdown标题和段落`() {
        val html = renderer.renderToHtml("# Hello\n\nworld")
        assertTrue(html.contains("<h1>Hello</h1>"))
        assertTrue(html.contains("<p>world</p>"))
    }

    @Test
    fun `原始HTML标签原样透传（不需要额外扩展）`() {
        val html = renderer.renderToHtml("<div align=\"center\">centered</div>")
        assertTrue(html.contains("<div align=\"center\">centered</div>"))
    }

    @Test
    fun `GFM表格扩展生效`() {
        val markdown = "| a | b |\n| - | - |\n| 1 | 2 |"
        val html = renderer.renderToHtml(markdown)
        assertTrue(html.contains("<table>"))
    }

    @Test
    fun `任务列表扩展生效`() {
        val html = renderer.renderToHtml("- [x] done\n- [ ] todo")
        assertTrue(html.contains("checked") || html.contains("checkbox"))
    }
}
