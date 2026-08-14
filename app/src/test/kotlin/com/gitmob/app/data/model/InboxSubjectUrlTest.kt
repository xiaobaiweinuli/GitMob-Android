package com.gitmob.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboxSubjectUrlTest {

    @Test
    fun `解析issues类型的subject url`() {
        val ref = parseInboxSubjectUrl("https://api.github.com/repos/octocat/hello-world/issues/42")
        assertEquals(InboxSubjectRef("octocat", "hello-world", 42), ref)
    }

    @Test
    fun `解析discussions类型的subject url`() {
        val ref = parseInboxSubjectUrl("https://api.github.com/repos/octocat/hello-world/discussions/7")
        assertEquals(InboxSubjectRef("octocat", "hello-world", 7), ref)
    }

    @Test
    fun `不认识的URL格式返回null而不是抛异常`() {
        val ref = parseInboxSubjectUrl("https://api.github.com/repos/octocat/hello-world/releases/1")
        assertNull(ref)
    }
}
