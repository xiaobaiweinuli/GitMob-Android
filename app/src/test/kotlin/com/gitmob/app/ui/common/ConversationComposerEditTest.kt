package com.gitmob.app.ui.common

import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.input.TextFieldState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationComposerEditTest {
    @Test
    fun `bold wraps selected text and preserves selection`() {
        val result = TextFieldState("hello world", TextRange(6, 11))
        applyMarkdownEdit(result, MarkdownEditAction.BOLD)

        assertEquals("hello **world**", result.text.toString())
        assertEquals(TextRange(8, 13), result.selection)
    }

    @Test
    fun `italic inserts editable placeholder without selection`() {
        val result = TextFieldState("", TextRange.Zero)
        applyMarkdownEdit(result, MarkdownEditAction.ITALIC)

        assertEquals("*italic*", result.text.toString())
        assertEquals(TextRange(1, 7), result.selection)
    }

    @Test
    fun `list action prefixes every selected line`() {
        val result = TextFieldState("one\ntwo", TextRange(0, 7))
        applyMarkdownEdit(result, MarkdownEditAction.BULLET)

        assertEquals("- one\n- two", result.text.toString())
        assertEquals(TextRange(2, 11), result.selection)
    }

    @Test
    fun `code block surrounds selected text`() {
        val result = TextFieldState("println()", TextRange(0, 9))
        applyMarkdownEdit(result, MarkdownEditAction.CODE_BLOCK)

        assertEquals("```\nprintln()\n```", result.text.toString())
        assertEquals(TextRange(4, 13), result.selection)
    }
}
