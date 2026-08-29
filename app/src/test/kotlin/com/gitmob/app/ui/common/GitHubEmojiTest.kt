package com.gitmob.app.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class GitHubEmojiTest {
    @Test
    fun `github standard shortcode is converted to unicode`() {
        assertFalse(emojizeGitHubText(":mega:").contains(":mega:"))
    }

    @Test
    fun `github custom shortcode resolves to an image resource`() {
        assertNotNull(githubCustomEmojiDrawable(":octocat:"))
    }
}
