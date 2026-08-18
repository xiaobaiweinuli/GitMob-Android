package com.gitmob.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationContentCardTest {
    @Test
    fun `quote markdown prefixes every line and leaves reply spacing`() {
        assertEquals("> first\n> second\n\n", quoteMarkdown("first\nsecond"))
    }

    @Test
    fun `quote markdown truncates oversized content`() {
        assertEquals("> abcd\n> …\n\n", quoteMarkdown("abcdef", maxLength = 4))
    }

    @Test
    fun `quote markdown ignores blank content`() {
        assertEquals("", quoteMarkdown("  \n "))
    }
}
