package com.gitmob.app.ui.search

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class SearchSyntaxTest {
    private fun bare(token: String, text: String, selection: TextRange = TextRange(text.length)) =
        insertSyntax(TextFieldValue(text, selection), repoCodeSyntax.first { it.token == token }, bare = true)

    @Test fun repositorySyntaxUsesBareTokensAndCompletesOnlyTheChosenPrefix() {
        val expected = listOf("path:", "language:", "filename:", "extension:", "in:", "size:", "AND", "NOT", "\"\"", "-path:", "-language:")
        assertEquals(expected, repoCodeSyntax.map { it.token })
        assertEquals("path:app language:", bare("language:", "path:app").text)
        assertEquals("path:app language:", bare("language:", "path:app lang").text)
        assertEquals("path:app lang filename:", bare("filename:", "path:app lang").text)
        assertEquals("path:app -path:", bare("-path:", "path:app -pa").text)
    }

    @Test fun repositorySyntaxKeepsTheCursorAfterColonAndInsidePairedQuotes() {
        val colon = bare("language:", "path:app OLD filename:Search.kt", TextRange(9, 12))
        assertEquals("path:app language: filename:Search.kt", colon.text)
        assertEquals(TextRange(18), colon.selection)
        val quotes = bare("\"\"", "path:app")
        assertEquals("path:app \"\"", quotes.text)
        assertEquals(TextRange(10), quotes.selection)
        val and = bare("AND", "path:app needle")
        assertEquals("path:app needle AND ", and.text)
        assertEquals(TextRange(and.text.length), and.selection)
    }

    @Test fun repositorySyntaxPreservesOtherCompositionAndDoesNotReplaceQuotedContent() {
        val token = repoCodeSyntax.first { it.token == "language:" }
        val composing = TextFieldValue("输入 ", TextRange(3), TextRange(0, 2))
        assertEquals(TextRange(0, 2), insertSyntax(composing, token, bare = true).composition)
        assertEquals("\"search lang\" language:", bare("language:", "\"search lang\"").text)
        assertNull(insertSyntax(TextFieldValue("输入", TextRange(0, 2), TextRange(0, 2)), token, bare = true).composition)
    }

    @Test fun pathHighlightsOnlyPositiveLiteralKeywords() {
        assertEquals(listOf("SearchSyntax"), repoCodePathTerms("SearchSyntax path:app language:kotlin AND NOT Test -Sample"))
        assertTrue(repoCodePathTerms("path:app").isEmpty())
        assertTrue(repoCodePathTerms("\"Search Syntax\"").isEmpty())
    }

    private val language = searchSyntax.first { it.token == "language:kotlin" }

    @Test fun insertsAtSelectionAndSelectsOnlyExampleValue() {
        val result = insertSyntax(TextFieldValue("android OLD repo:owner/name", TextRange(8, 11)), language)
        assertEquals("android language:kotlin repo:owner/name", result.text)
        assertEquals("kotlin", result.text.substring(result.selection.min, result.selection.max))
    }

    @Test fun completesPrefixInsteadOfAppendingDuplicate() {
        val input = TextFieldValue("android lang", TextRange(12))
        assertTrue(syntaxSuggestions(input).all { it.token.startsWith("lang") })
        assertEquals("android language:kotlin ", insertSyntax(input, language).text)
    }

    @Test fun unrelatedInputAndQuotedPhrasesArePreserved() {
        val input = TextFieldValue("\"language guide\"", TextRange(16))
        assertEquals("\"language guide\" language:kotlin ", insertSyntax(input, language).text)
        assertTrue(syntaxSuggestions(TextFieldValue("中文", TextRange(2))).size > 20)
    }

    @Test fun compositionIsOnlyClearedWhenExplicitlyReplaced() {
        val prefix = TextFieldValue("输入 ", TextRange(3), TextRange(0, 2))
        assertEquals(TextRange(0, 2), insertSyntax(prefix, language).composition)
        val replaced = TextFieldValue("输入", TextRange(0, 2), TextRange(0, 2))
        assertNull(insertSyntax(replaced, language).composition)
    }

    @Test fun customPropertiesRequireOneExplicitOrganization() {
        fun hasProps(text: String) = syntaxSuggestions(TextFieldValue(text, TextRange(text.length))).any { it.singleOrganization }
        assertFalse(hasProps("android "))
        assertTrue(hasProps("org:github "))
        assertFalse(hasProps("org:github org:android "))
        assertFalse(hasProps("\"org:github\" "))
    }
}
