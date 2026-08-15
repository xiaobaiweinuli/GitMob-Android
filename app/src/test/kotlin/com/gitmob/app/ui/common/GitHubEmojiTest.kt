package com.gitmob.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubEmojiTest {
    @Test
    fun `standard shortcodes become unicode emoji`() {
        assertEquals("🎯", emojizeGitHubText(":dart:"))
        assertEquals("🐠", emojizeGitHubText(":tropical_fish:"))
    }

    @Test
    fun `unicode and unknown shortcodes stay unchanged`() {
        assertEquals("🎯", emojizeGitHubText("🎯"))
        assertEquals(":not_a_real_emoji:", emojizeGitHubText(":not_a_real_emoji:"))
    }

    @Test
    fun `all GitHub custom aliases resolve with or without colons`() {
        customAliases.forEach { alias ->
            assertNotNull(alias, githubCustomEmojiDrawable(alias))
            assertNotNull(":$alias:", githubCustomEmojiDrawable(":$alias:"))
        }
    }

    @Test
    fun `standard and unknown aliases do not use custom images`() {
        assertNull(githubCustomEmojiDrawable(":dart:"))
        assertNull(githubCustomEmojiDrawable(":not_a_real_emoji:"))
    }

    private val customAliases = listOf(
        "accessibility",
        "atom",
        "basecamp",
        "basecampy",
        "bowtie",
        "copilot",
        "dependabot",
        "electron",
        "feelsgood",
        "finnadie",
        "fishsticks",
        "goberserk",
        "godmode",
        "hurtrealbad",
        "neckbeard",
        "octocat",
        "rage1",
        "rage2",
        "rage3",
        "rage4",
        "shipit",
        "suspect",
        "trollface",
    )
}
