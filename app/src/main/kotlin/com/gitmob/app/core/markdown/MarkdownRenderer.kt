package com.gitmob.app.core.markdown

/**
 * Markdown → HTML 渲染的统一入口。README 预览、Issue/PR/讨论评论的正文渲染、
 * 编辑器实时预览——所有需要"把一段 Markdown 变成能显示的东西"的场景都调这个接口，
 * 不要在各自的 Repository/ViewModel 里各自实例化具体的 Markdown 解析库。
 *
 * 协议屏蔽：调用方只认这个接口，不知道背后具体用的是 commonmark-java 还是别的库，
 * 换库（比如以后要切到 flexmark）只需要新写一个实现类、改 DI 绑定，
 * 所有调用方代码不用动，参照 core/network/GHApiClient 的协议屏蔽思路。
 */
interface MarkdownRenderer {
    /**
     * 把原始 Markdown 转成 HTML **正文片段**（不包含 <html>/<head>/<body> 这层外壳，
     * 调用方自己决定怎么包——渲染 README 时套 <article class="markdown-body">，
     * 渲染一条评论时可能直接内嵌进 Compose 的某个容器）。
     */
    fun renderToHtml(markdown: String): String
}
