package com.gitmob.app.ui.common

import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubEmojiTest {
    @Test
    fun `github standard shortcode is converted to unicode`() {
        assertFalse(emojizeGitHubText(":mega:").contains(":mega:"))
    }
}
