package com.gitmob.app.data.translation

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class TranslationDocumentTest {
    private fun html(text: String) = TranslationDocuments.create(TranslationTarget("document", text, html = true), TranslationProvider.EDGE)

    @Test fun utf8ChunksRejoinExactlyWithoutSplittingSurrogates() {
        val source = "你好 world 😀. ".repeat(100)
        val parts = TranslationDocuments.splitUtf8(source, 31)
        assertEquals(source, parts.joinToString(""))
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 31 })
        assertTrue(parts.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
    }

    @Test fun markupLinksCodeUrlsAndImagesRemainReadable() {
        val document = html("""<p title="a > b">Hello <a href="docs/page.md">Read more</a><img src="image.png" alt="Preview"></p><pre><code>val password = "secret"</code></pre><p>https://example.test/path someone@example.test</p>""")
        assertEquals(listOf("Hello ", "Read more"), document.parts)
        val dom = Jsoup.parse(document.safeHtml!!)
        assertEquals("a > b", dom.selectFirst("p")!!.attr("title"))
        assertEquals("docs/page.md", dom.selectFirst("a")!!.attr("href"))
        assertEquals("image.png", dom.selectFirst("img")!!.attr("src"))
        assertEquals("val password = \"secret\"", dom.selectFirst("code")!!.text())
        assertTrue(dom.text().contains("https://example.test/path someone@example.test"))
    }

    @Test fun activeContentAndSpoofedMarkersAreRemovedBeforeScriptIsEnabled() {
        val source = """<base href="https://evil.test"><script>alert(1)</script><style>body{display:none}</style>
            <p id="gm-root" data-gm-unit="999" onclick="alert(1)" style="background:url(x)">Hello
            <a href="javascript:alert(1)">link</a><img src="https://example.test/x.png" onerror="alert(1)"></p>
            <svg><script>alert(1)</script></svg><iframe srcdoc="evil"></iframe><form><input value="secret"></form>"""
        val document = html(source)
        val dom = Jsoup.parse(document.safeHtml!!)
        assertTrue(dom.select("script,style,base,iframe,svg,form,[onclick],[onerror],[style],#gm-root").isEmpty())
        assertEquals("", dom.selectFirst("a")!!.attr("href"))
        assertEquals(listOf("0", "1"), dom.select("[data-gm-unit]").map { it.attr("data-gm-unit") })
        assertFalse(document.parts.any { "alert" in it || "secret" in it })
    }

    @Test fun tablesDetailsTaskListsAndAnchorsSurviveCleaning() {
        val document = html("""<details open><summary>More</summary><p id="section">Body</p></details><table><tr><td>Cell</td></tr></table><ul><li><input type="checkbox" checked>Task</li></ul><a href="#section">Jump</a>""")
        val dom = Jsoup.parse(document.safeHtml!!)
        assertTrue(dom.selectFirst("details")!!.hasAttr("open"))
        assertEquals("#section", dom.selectFirst("a")!!.attr("href"))
        assertTrue(dom.selectFirst("input")!!.hasAttr("disabled"))
        assertNotNull(dom.selectFirst("table td"))
        assertEquals(5, dom.select("[data-gm-output]").size)
    }

    @Test fun longParagraphProducesIndependentStableUnitsAndNoTranslatedOriginalCopies() {
        val source = "word ".repeat(2000)
        val document = html("<p>$source</p><pre>untouched</pre><img src='a.png'>")
        val dom = Jsoup.parse(document.safeHtml!!)
        assertTrue(document.parts.size > 1)
        assertEquals(source, document.parts.joinToString(""))
        assertEquals(document.parts.indices.map(Int::toString), dom.select("[data-gm-unit]").map { it.attr("data-gm-unit") })
        assertTrue(dom.select("[data-gm-output]").all { it.hasAttr("hidden") && it.text().isEmpty() })
        assertEquals(1, dom.select("img").size)
        assertEquals(1, dom.select("pre").size)
    }

    @Test fun nativePartialTranslationIsVisibleAndBilingualContainsOnlyResults() {
        val target = TranslationTarget("title", "word ".repeat(1000))
        val document = TranslationDocuments.create(target, TranslationProvider.BAIDU)
        val result = mapOf(0 to "第一部分 ")
        assertTrue(document.renderText(result).startsWith("第一部分 "))
        assertTrue(document.renderText(result).contains("word"))
        assertEquals("第一部分 ", document.renderText(result, translatedOnly = true))
        assertEquals(target.content, document.renderText(emptyMap()))
    }

    @Test fun bilingualInlineStructureRetainsLiteralsWithoutPendingSourceOrDuplicateIds() {
        val document = html("""<p id="intro">Run <code id="cmd">npm install</code> before <a href="guide.md" id="guide"><strong>starting</strong></a>.<br>Visit https://example.test or contact dev@example.test <kbd>Ctrl+C</kbd> <b>123</b><img src="badge.png"></p>""")
        val dom = Jsoup.parse(document.safeHtml!!)
        val output = dom.selectFirst("[data-gm-output]")!!
        assertEquals("npm install", output.selectFirst("code")!!.text())
        assertEquals("Ctrl+C", output.selectFirst("kbd")!!.text())
        assertEquals("123", output.selectFirst("b")!!.text())
        assertEquals("guide.md", output.selectFirst("a")!!.attr("href"))
        assertEquals(1, output.select("br").size)
        assertTrue(output.select("img,[id],[data-gm-unit]").isEmpty())
        assertTrue(output.select("[data-gm-result]").all { it.text().isEmpty() })
        assertFalse(output.text().contains("starting"))
        assertFalse(document.parts.any { "npm install" in it || "https://" in it || "@" in it })
        output.select("[data-gm-result]").forEach { slot ->
            slot.text("译文" + slot.attr("data-gm-result"))
        }
        assertTrue(output.text().contains("https://example.test"))
        assertTrue(output.text().contains("dev@example.test"))
        assertTrue(output.selectFirst("a strong")!!.text().startsWith("译文"))
    }

    @Test fun bilingualNestedBlocksDoNotCopyOtherParagraphsOrBlockCode() {
        val document = html("""<li>Parent <code>one</code><p>Child <code>two</code></p><pre><code>block</code></pre></li>""")
        val dom = Jsoup.parse(document.safeHtml!!)
        val parent = dom.selectFirst("li > [data-gm-output]")!!
        val child = dom.selectFirst("p > [data-gm-output]")!!
        assertEquals("one", parent.selectFirst("code")!!.text())
        assertEquals("two", child.selectFirst("code")!!.text())
        assertTrue(parent.select("p,pre").isEmpty())
        assertEquals(2, dom.select("[data-gm-result]").size)
    }

    @Test fun bilingualSlotsCoverInlineCitationsAndAllSanitizedBlockContainers() {
        val document = html("""<section>Section <cite>citation</cite><article>Article</article></section><details>Details <summary>Summary</summary></details><table><caption>Caption</caption><tr><td>Cell</td></tr></table>""")
        val dom = Jsoup.parse(document.safeHtml!!)
        assertEquals(document.parts.indices.map(Int::toString).toSet(),
            dom.select("[data-gm-result]").map { it.attr("data-gm-result") }.toSet())
        assertEquals(document.parts.size, dom.select("[data-gm-result]").size)
        assertNotNull(dom.selectFirst("section > [data-gm-output] cite"))
        assertNotNull(dom.selectFirst("article > [data-gm-output]"))
        assertNotNull(dom.selectFirst("details > [data-gm-output]"))
        assertNotNull(dom.selectFirst("caption > [data-gm-output]"))
    }

    @Test fun nativeBilingualRetainsUrlsEmailsAndSeparatorsWithoutPendingWords() {
        val target = TranslationTarget("description", "Read https://example.test then contact dev@example.test!")
        val document = TranslationDocuments.create(target, TranslationProvider.EDGE)
        assertEquals(listOf("Read ", " then contact "), document.parts)
        val partial = document.renderText(mapOf(0 to "阅读 "), translatedOnly = true)
        assertTrue(partial.contains("阅读 ") && partial.contains("https://example.test") && partial.contains("dev@example.test!"))
        assertFalse(partial.contains("then contact"))
        assertEquals("阅读 https://example.test 然后联系 dev@example.test!",
            document.renderText(mapOf(0 to "阅读 ", 1 to " 然后联系 "), translatedOnly = true))
        assertEquals("", document.renderText(emptyMap(), translatedOnly = true))
    }

    @Test fun emptyAndOversizeDocumentsProduceUsefulErrors() {
        assertEquals(TranslationProblem.EMPTY, assertThrows(TranslationException::class.java) { html("<pre>code</pre><img src='x.png'>") }.problem)
        assertEquals(TranslationProblem.TOO_LONG, assertThrows(TranslationException::class.java) { html("x".repeat(200001)) }.problem)
    }
}
