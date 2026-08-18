package com.gitmob.app.ui.common

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationComposerEditTest {
    @Test
    fun `bold wraps selected text and preserves selection`() {
        val result = applyMarkdownEdit(
            TextFieldValue("hello world", TextRange(6, 11)),
            MarkdownEditAction.BOLD,
        )

        assertEquals("hello **world**", result.text)
        assertEquals(TextRange(8, 13), result.selection)
    }

    @Test
    fun `italic inserts editable placeholder without selection`() {
        val result = applyMarkdownEdit(
            TextFieldValue("", TextRange.Zero),
            MarkdownEditAction.ITALIC,
        )

        assertEquals("*italic*", result.text)
        assertEquals(TextRange(1, 7), result.selection)
    }

    @Test
    fun `list action prefixes every selected line`() {
        val result = applyMarkdownEdit(
            TextFieldValue("one\ntwo", TextRange(0, 7)),
            MarkdownEditAction.BULLET,
        )

        assertEquals("- one\n- two", result.text)
        assertEquals(TextRange(2, 11), result.selection)
    }

    @Test
    fun `code block surrounds selected text`() {
        val result = applyMarkdownEdit(
            TextFieldValue("println()", TextRange(0, 9)),
            MarkdownEditAction.CODE_BLOCK,
        )

        assertEquals("```\nprintln()\n```", result.text)
        assertEquals(TextRange(4, 13), result.selection)
    }
}
