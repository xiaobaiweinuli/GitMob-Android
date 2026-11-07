package com.gitmob.app.data.translation

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/** Plain text offsets only; HTML units have stable IDs in the safe display document. */
data class TranslationSpan(val start: Int, val end: Int, val partIndices: List<Int>)
data class TranslationDocument(
    val target: TranslationTarget,
    val parts: List<String>,
    val spans: List<TranslationSpan> = emptyList(),
    val safeHtml: String? = null,
) {
    /** Native titles/descriptions support partial results without waiting for the entire span. */
    fun renderText(translations: Map<Int, String>, translatedOnly: Boolean = false): String = buildString {
        if (translatedOnly && translations.isEmpty()) return@buildString
        var cursor = 0
        for (span in spans) {
            append(target.content.substring(cursor, span.start))
            span.partIndices.forEach { index ->
                val value = translations[index]
                if (value != null) append(value) else if (!translatedOnly) append(parts[index])
            }
            cursor = span.end
        }
        append(target.content.substring(cursor))
    }
}

/** Immutable snapshot. Network progress does not participate in the HTML loading identity. */
data class TranslationPresentation(
    val document: TranslationDocument,
    val translations: Map<Int, String>,
    val version: Long,
    val mode: String,
    val generation: Long,
)

object TranslationDocuments {
    private val protected = setOf("pre", "code", "kbd", "samp")
    private val inlineTags = setOf("a", "b", "br", "cite", "code", "del", "em", "i", "kbd", "q", "s", "samp", "small", "span", "strike", "strong", "sub", "sup", "u")
    private val urls = Regex("(?:https?://|www\\.)[^\\s<>]+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}")
    private const val PARSING_BASE = "https://translation.invalid/"

    fun create(target: TranslationTarget, provider: TranslationProvider): TranslationDocument {
        if (target.content.length > 200_000) throw TranslationException(TranslationProblem.TOO_LONG)
        val arrayService = provider == TranslationProvider.EDGE || provider == TranslationProvider.TRANSMART
        val parts = mutableListOf<String>()
        val spans = mutableListOf<TranslationSpan>()
        fun chunks(text: String) = splitUtf8(text, if (arrayService) 12_000 else 1800, if (arrayService) 4000 else Int.MAX_VALUE)
        fun fragments(text: String, visit: (Int, Int, Boolean) -> Unit) {
            var cursor = 0
            urls.findAll(text).forEach { match ->
                if (match.range.first > cursor) visit(cursor, match.range.first, true)
                visit(match.range.first, match.range.last + 1, false)
                cursor = match.range.last + 1
            }
            if (cursor < text.length) visit(cursor, text.length, true)
        }
        if (!target.html) {
            fragments(target.content) { start, end, translate ->
                val text = target.content.substring(start, end)
                if (translate && text.any(Char::isLetter)) {
                    val indices = chunks(text).map { parts.add(it); parts.lastIndex }
                    spans.add(TranslationSpan(start, end, indices))
                }
            }
            requireParts(parts)
            return TranslationDocument(target, parts, spans)
        }

        val dirty = Jsoup.parse(target.content, PARSING_BASE)
        // Remove active subtrees before cleaning; their fallback text must not become translation units.
        dirty.select("script,style,iframe,frame,frameset,object,embed,svg,math,form,button,select,textarea,template,noscript,meta,link,base").remove()
        dirty.select("input").forEach { input ->
            if (!input.attr("type").equals("checkbox", true)) input.remove()
            else input.attr("disabled", "")
        }
        val safelist = Safelist.relaxed()
            .addTags("details", "summary", "section", "article", "div", "span", "s", "del", "kbd", "samp", "input", "picture")
            .addAttributes(":all", "id", "title", "dir", "lang", "align")
            .addAttributes("a", "name")
            .addAttributes("details", "open")
            .addAttributes("input", "type", "checked", "disabled")
            .addProtocols("a", "href", "#")
            .preserveRelativeLinks(true)
        val clean = Cleaner(safelist).clean(dirty)
        clean.outputSettings().prettyPrint(false)
        // Keep anchor IDs but never allow a source document to impersonate our script or markers.
        clean.select("[id]").forEach { if (it.id().startsWith("gm-")) it.removeAttr("id") }
        val root = clean.body()
        val textNodes = mutableListOf<TextNode>()
        fun collect(element: Element, skip: Boolean = false) {
            val excluded = skip || element.normalName() in protected
            element.childNodes().toList().forEach { node ->
                if (node is Element) collect(node, excluded)
                else if (node is TextNode && !excluded && node.wholeText.any(Char::isLetter)) textNodes.add(node)
            }
        }
        collect(root)
        val groups = linkedSetOf<Element>()
        textNodes.forEach { node ->
            val text = node.wholeText
            val parent = node.parent() ?: return@forEach
            var block = parent
            while (block.normalName() in inlineTags && block.parent() != null) block = block.parent()!!
            fragments(text) { start, end, translate ->
                val fragment = text.substring(start, end)
                if (!translate || !fragment.any(Char::isLetter)) node.before(TextNode(fragment))
                else chunks(fragment).forEach { chunk ->
                    val index = parts.size
                    parts.add(chunk)
                    groups.add(block)
                    node.before(Element("span").attr("data-gm-unit", index.toString()).text(chunk))
                }
            }
            node.remove()
        }
        requireParts(parts)
        val outputs = groups.mapIndexed { id, element ->
            // Preserve inline literals/formatting, without copying images, block code or nested paragraphs.
            // Empty result slots never contain pending source text or duplicate anchor IDs.
            val output = Element("span").attr("data-gm-output", id.toString()).attr("hidden", "")
            element.childNodes().mapNotNull(::bilingualInline).forEach(output::appendChild)
            element to output
        }
        outputs.forEach { (element, output) -> element.appendChild(output) }
        return TranslationDocument(target, parts, safeHtml = root.html())
    }

    private fun bilingualInline(node: Node): Node? = when {
        node is TextNode -> node.clone()
        node is Element && node.normalName() in inlineTags -> {
            val copy = Element(node.normalName())
            node.attributes().forEach { attribute ->
                if (attribute.key !in setOf("id", "name", "data-gm-unit")) copy.attr(attribute.key, attribute.value)
            }
            if (node.hasAttr("data-gm-unit")) copy.attr("data-gm-result", node.attr("data-gm-unit"))
            else node.childNodes().mapNotNull(::bilingualInline).forEach(copy::appendChild)
            copy
        }
        else -> null
    }

    private fun requireParts(parts: List<String>) {
        if (parts.isEmpty()) throw TranslationException(TranslationProblem.EMPTY)
        if (parts.size > 4000) throw TranslationException(TranslationProblem.TOO_LONG)
    }

    /** Rejoins exactly, never splits a surrogate pair, and prefers a sentence/word boundary. */
    fun splitUtf8(text: String, limit: Int, maxCodePoints: Int = Int.MAX_VALUE): List<String> {
        require(limit >= 4)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = start
            var bytes = 0
            var codePoints = 0
            var boundary = start
            while (end < text.length) {
                val cp = text.codePointAt(end)
                val size = when { cp <= 0x7f -> 1; cp <= 0x7ff -> 2; cp <= 0xffff -> 3; else -> 4 }
                if (bytes + size > limit || codePoints >= maxCodePoints) break
                codePoints++
                bytes += size
                end += Character.charCount(cp)
                if (Character.isWhitespace(cp) || cp in intArrayOf(46, 33, 63, 0x3002, 0xff01, 0xff1f)) boundary = end
            }
            if (end < text.length && boundary > start && boundary - start >= (end - start) / 2) end = boundary
            result.add(text.substring(start, end))
            start = end
        }
        return result
    }
}
