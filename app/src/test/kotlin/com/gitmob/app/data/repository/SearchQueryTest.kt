package com.gitmob.app.data.repository

import com.gitmob.app.data.model.SearchCategory
import org.junit.Assert.*
import org.junit.Test

class SearchQueryTest {
    @Test fun scopesRespectQuotedTextAndExplicitConflicts() {
        assertEquals("bug is:issue", scopedSearchQuery("bug", SearchCategory.ISSUES))
        assertEquals("bug type:pr", scopedSearchQuery("bug type:pr", SearchCategory.PULL_REQUESTS))
        assertEquals("\"is:pr\" is:issue", scopedSearchQuery("\"is:pr\"", SearchCategory.ISSUES))
        assertNull(scopedSearchQuery("bug is:pr", SearchCategory.ISSUES))
        assertNull(scopedSearchQuery("bug -is:issue", SearchCategory.ISSUES))
        assertNull(scopedSearchQuery("octocat type:org", SearchCategory.USERS))
        assertEquals("github type:org", scopedSearchQuery("github", SearchCategory.ORGANIZATIONS))
        assertEquals("language:kotlin", scopedSearchQuery("language:kotlin", SearchCategory.CODE))
    }

    @Test fun permalinkUsesKnownPathSuffixAndPreservesSlashRefs() {
        val target = searchFileTarget("https://github.com/owner/repo/blob/feature/search/src/Main.kt", "owner", "repo", "src/Main.kt")!!
        assertEquals("feature/search", target.ref)
        val sha = "a".repeat(40)
        assertEquals(sha, searchFileTarget("https://github.com/owner/repo/blob/$sha/src/Main.kt", "owner", "repo", "src/Main.kt")!!.ref)
        assertEquals("feature/docs", searchFileTarget("https://github.com/owner/repo/blob/feature/docs/My%20File.kt", "owner", "repo", "My File.kt")!!.ref)
        assertNull(searchFileTarget("https://example.com/owner/repo/blob/main/a.kt", "owner", "repo", "a.kt"))
        assertNull(searchFileTarget("https://github.com/owner/repo/blob/main/a.kt", "owner", "repo", "b.kt"))
    }
}
