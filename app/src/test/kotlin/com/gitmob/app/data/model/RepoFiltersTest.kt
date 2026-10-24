package com.gitmob.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class RepoFiltersTest {
    @Test
    fun `名称与简介按忽略大小写的普通连续子串匹配`() {
        val repo = repo("Apollo-Android").copy(description = "Kotlin a.*b sample")
        assertTrue(RepoFilters(query = "  ANDROID  ").matchesSearch(repo))
        assertTrue(RepoFilters(query = "kotLIN").matchesSearch(repo))
        assertTrue(RepoFilters(query = "a.*b").matchesSearch(repo))
        assertFalse(RepoFilters(query = "a.*Android").matchesSearch(repo))
        assertFalse(RepoFilters(query = "Kotlin sample").matchesSearch(repo))
        assertFalse(RepoFilters(query = "Kotlin").matchesSearch(repo.copy(description = null)))
        assertTrue(RepoFilters(query = "  ").matchesSearch(repo.copy(description = null)))
    }

    @Test
    fun `查询规范化不随系统语言改变且保留其他条件`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val filters = RepoFilters(query = "  ISSUE  ", visibility = RepoVisibility.PRIVATE, isFork = false, sort = RepoSort.NAME_ASC)
            assertEquals(filters.copy(query = "issue"), filters.normalized())
            assertEquals("me/issue", repo("ISSUE").key)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `十种排序使用正确主字段并以名称正序打破并列`() {
        val repos = listOf(
            repo("zulu").copy(createdAt = instant(1), updatedAt = instant(3), pushedAt = instant(2), stargazerCount = 1),
            repo("BETA").copy(createdAt = instant(1), updatedAt = instant(1), pushedAt = null, stargazerCount = 1),
            repo("alpha").copy(createdAt = instant(2), updatedAt = instant(1), pushedAt = instant(3), stargazerCount = 2),
        )
        val expected = mapOf(
            RepoSort.UPDATED_DESC to listOf("zulu", "alpha", "BETA"),
            RepoSort.CREATED_ASC to listOf("BETA", "zulu", "alpha"),
            RepoSort.CREATED_DESC to listOf("alpha", "BETA", "zulu"),
            RepoSort.UPDATED_ASC to listOf("alpha", "BETA", "zulu"),
            RepoSort.PUSHED_ASC to listOf("zulu", "alpha", "BETA"),
            RepoSort.PUSHED_DESC to listOf("alpha", "zulu", "BETA"),
            RepoSort.STARS_ASC to listOf("BETA", "zulu", "alpha"),
            RepoSort.STARS_DESC to listOf("alpha", "BETA", "zulu"),
            RepoSort.NAME_DESC to listOf("zulu", "BETA", "alpha"),
            RepoSort.NAME_ASC to listOf("alpha", "BETA", "zulu"),
        )
        expected.forEach { (sort, names) -> assertEquals(sort.name, names, sort.sorted(repos).map { it.name }) }
    }

    @Test
    fun `推送空时间两个方向均置末且空值间按名称排序`() {
        val repos = listOf(repo("z-null"), repo("A-null"), repo("pushed").copy(pushedAt = instant(1)))
        for (sort in listOf(RepoSort.PUSHED_ASC, RepoSort.PUSHED_DESC)) {
            assertEquals(listOf("pushed", "A-null", "z-null"), sort.sorted(repos).map { it.name })
        }
    }

    @Test
    fun `名称采用忽略大小写的字符序而非自然数字序`() {
        val repos = listOf(repo("repo2"), repo("Repo10"), repo("ALPHA"))
        assertEquals(listOf("ALPHA", "Repo10", "repo2"), RepoSort.NAME_ASC.sorted(repos).map { it.name })
        assertEquals(listOf("repo2", "Repo10", "ALPHA"), RepoSort.NAME_DESC.sorted(repos).map { it.name })
        assertEquals(repo("Repo10").key, repo("REPO10").copy(ownerLogin = "ME").key)
    }

    private fun instant(seconds: Long) = Instant.ofEpochSecond(seconds)

    private fun repo(name: String) = RepoListItem(
        name = name, ownerLogin = "me", description = null, homepageUrl = null,
        visibility = RepoVisibility.PUBLIC, isArchived = false, isFork = false, hasIssuesEnabled = true,
        createdAt = instant(0), updatedAt = instant(0), pushedAt = null,
        forkedFromOwner = null, forkedFromName = null, languageName = null, languageColor = null,
        stargazerCount = 0, forkCount = 0, openIssueCount = 0, topics = emptyList(), defaultBranchName = null,
    )
}
