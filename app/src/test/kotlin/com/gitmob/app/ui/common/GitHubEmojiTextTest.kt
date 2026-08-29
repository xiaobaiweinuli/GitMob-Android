package com.gitmob.app.ui.common

import com.gitmob.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEmojiTextTest {

    @Test
    fun `完整字符串中的 GitHub 图片 emoji 与文字保持顺序`() {
        val parts = parseGitHubEmojiText(":octocat:Github主页 :shipit:")

        assertEquals(3, parts.size)
        assertEquals(":octocat:", (parts[0] as GitHubEmojiTextPart.CustomEmojiPart).shortcode)
        assertEquals(R.drawable.github_emoji_octocat, (parts[0] as GitHubEmojiTextPart.CustomEmojiPart).drawableRes)
        assertEquals("Github主页 ", (parts[1] as GitHubEmojiTextPart.TextPart).value)
        assertEquals(":shipit:", (parts[2] as GitHubEmojiTextPart.CustomEmojiPart).shortcode)
    }

    @Test
    fun `普通 shortcode 转为 Unicode 且未知 shortcode 原样保留`() {
        val parts = parseGitHubEmojiText("状态 :rocket: :not_a_known_emoji:")
        val text = parts.filterIsInstance<GitHubEmojiTextPart.TextPart>().joinToString("") { it.value }

        assertTrue(text.contains("🚀"))
        assertTrue(text.contains(":not_a_known_emoji:"))
    }

    @Test
    fun `换行和紧贴文字的图片 emoji 不丢失`() {
        val parts = parseGitHubEmojiText(":octocat:主页\n下一行:shipit:")
        val text = parts.filterIsInstance<GitHubEmojiTextPart.TextPart>().joinToString("") { it.value }

        assertEquals("主页\n下一行", text)
        assertEquals(2, parts.count { it is GitHubEmojiTextPart.CustomEmojiPart })
    }
}
