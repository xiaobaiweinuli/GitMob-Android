package com.gitmob.app.data.repository

import com.gitmob.app.data.model.SearchCategory
import org.junit.Assert.*
import org.junit.Test

class SearchQueryTest {
    @Test fun repositoryCodeQueryKeepsTheRepositoryHiddenAndPreservesSearchSyntax() {
        assertEquals("repo:owner/repo SearchSyntax path:app language:kotlin",
            repositoryCodeSearchQuery(" SearchSyntax path:app language:kotlin ", "owner", "repo", isFork = false))
        assertNull(repositoryCodeSearchQuery(" ", "owner", "repo", isFork = false))
        assertEquals("repo:owner/repo \"repo:other/project\"", repositoryCodeSearchQuery("\"repo:other/project\"", "owner", "repo", isFork = false))
        listOf("repo:other/project", "user:someone", "-org:team", "REPO:\"other/project\"").forEach {
            assertNull(repositoryCodeSearchQuery("needle $it", "owner", "repo", isFork = false))
        }
    }

    @Test fun repositoryForkQueriesAppendTheQualifierAndStillRejectBlankOrChangedScope() {
        assertEquals("repo:owner/repo path:app keyword fork:true",
            repositoryCodeSearchQuery(" path:app keyword ", "owner", "repo", isFork = true))
        assertEquals("repo:owner/repo path:app fork:true",
            repositoryCodeSearchQuery("path:app", "owner", "repo", isFork = true))
        assertNull(repositoryCodeSearchQuery(" ", "owner", "repo", isFork = true))
        assertNull(repositoryCodeSearchQuery("needle repo:other/project", "owner", "repo", isFork = true))
    }

    @Test fun repositoryDirectoryInputPreservesTheActualPathAndQuotesSpaces() {
        assertEquals("", repositoryCodeSearchInput(""))
        assertEquals("path:app", repositoryCodeSearchInput("app"))
        assertEquals("path:app/src/main", repositoryCodeSearchInput("app/src/main"))
        assertEquals("path:\"docs/my folder\"", repositoryCodeSearchInput("docs/my folder"))
        assertEquals("path:\"docs/\\\"quoted\\\"\"", repositoryCodeSearchInput("docs/\"quoted\""))
    }

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
