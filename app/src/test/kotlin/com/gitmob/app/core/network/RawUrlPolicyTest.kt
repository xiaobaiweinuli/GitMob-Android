package com.gitmob.app.core.network

import org.junit.Assert.*
import org.junit.Test

class RawUrlPolicyTest {
    private val policy = RawUrlPolicy()
    private val raw = "https://raw.githubusercontent.com/owner/repo/main/a%20b.png"

    @Test fun normalizesHostProtocolPathAndTrailingSlash() {
        assertEquals("https://proxy.example/", policy.normalizePrefix(" Proxy.Example "))
        assertEquals("https://proxy.example/path/", policy.normalizePrefix("https://proxy.example:443/path///"))
        for (invalid in listOf("", "http://proxy.example", "https://a@proxy.example", "https://@proxy.example",
            "https://proxy.example/{url}", "https://proxy.example/\$url", "https://proxy.example/https://raw.githubusercontent.com/o/r/main/a",
            "https://proxy.example?key=x", "https://proxy.example/#hash", "https://raw.githubusercontent.com/")) {
            assertNull(invalid, policy.normalizePrefix(invalid))
        }
    }

    @Test fun prependsWholeRawUrlOnceAndPreservesOriginalWhenDisabled() {
        assertEquals("https://proxy.example/path/$raw", policy.rewrite(raw, "proxy.example/path"))
        assertEquals(raw, policy.rewrite(raw, null))
        val rewritten = policy.rewrite(raw, "proxy.example")
        assertEquals(rewritten, policy.rewrite(rewritten, "proxy.example"))
        assertEquals("https://proxy.example/$raw#image", policy.rewrite("$raw#image", "proxy.example"))
        assertTrue(policy.accepts(raw, "HEAD"))
    }

    @Test fun onlyExactPublicRawReadsQualify() {
        for (url in listOf("http://raw.githubusercontent.com/a", "https://raw.githubusercontent.com:444/a",
            "https://raw.githubusercontent.com.evil.test/a", "https://api.github.com/repos/o/r/contents/a",
            "https://release-assets.githubusercontent.com/a?signature=x", "$raw?token=secret", "$raw?",
            "https://user@raw.githubusercontent.com/a", "https://@raw.githubusercontent.com/a")) {
            assertFalse(url, policy.accepts(url))
            assertEquals(url, policy.rewrite(url, "proxy.example"))
        }
        assertFalse(policy.accepts(raw, "POST"))
        for (header in listOf("Authorization", "cookie", "PROXY-AUTHORIZATION")) {
            assertEquals(raw, policy.rewrite(raw, "proxy.example", headers = mapOf(header to "secret")))
        }
    }
}
