package com.gitmob.app.data.model

import com.gitmob.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDraftTest {
    @Test
    fun `七文本字段原样往返且不改其他字段或工作开关`() {
        val draft = ProfileDraft(hireable = true)
        ProfileField.entries.forEach { field ->
            val value = "  $field 😀\n  "
            val changed = draft.withValue(field, value)
            assertEquals(value, changed.value(field))
            assertTrue(changed.hireable)
            ProfileField.entries.filter { it != field }.forEach { assertEquals("", changed.value(it)) }
        }
    }

    @Test
    fun `简介按code point校验160边界不按UTF16长度截断`() {
        val valid = ProfileDraft(bio = "😀".repeat(160))
        assertEquals(160, valid.bioLength)
        assertTrue(valid.validationErrors().isEmpty())
        assertEquals(R.string.profile_bio_invalid, valid.copy(bio = valid.bio + "a").validationErrors()[ProfileField.BIO])
        assertEquals(2, ProfileDraft(bio = "e\u0301").bioLength)
    }

    @Test
    fun `空值允许清空且URL邮箱社交账号不隐式修正`() {
        assertTrue(ProfileDraft().validationErrors().isEmpty())
        assertTrue(ProfileDraft(blog = "HTTPS://example.com/path?q=1", email = "me+tag@example.com", twitterUsername = "Name_123").validationErrors().isEmpty())
        listOf("example.com", "ftp://example.com", "https://", " https://example.com", "https://example.com/a b").forEach {
            assertEquals(it, R.string.profile_blog_invalid, ProfileDraft(blog = it).validationErrors()[ProfileField.BLOG])
        }
        listOf("broken", "me@example", " me@example.com", "me @example.com").forEach {
            assertEquals(it, R.string.profile_email_invalid, ProfileDraft(email = it).validationErrors()[ProfileField.EMAIL])
        }
        listOf("@name", "name-dash", " name", "名字", "https://x.com/name").forEach {
            assertEquals(it, R.string.profile_twitter_invalid, ProfileDraft(twitterUsername = it).validationErrors()[ProfileField.TWITTER_USERNAME])
        }
    }
}
