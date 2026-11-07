package com.gitmob.app.ui.search

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class SearchSyntaxTest {
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
