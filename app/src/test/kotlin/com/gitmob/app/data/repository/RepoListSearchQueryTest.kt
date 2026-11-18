package com.gitmob.app.data.repository

import com.gitmob.app.data.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RepoListSearchQueryTest {
    @Test
    fun `issue combines literal terms with all supported filters`() {
        val query = repoIssueSearchQuery("octo", "repo", "  发布  崩溃  ", RepoIssueFilter(
            state = RepoIssueStateFilter.CLOSED, sort = RepoIssueSort.COMMENTS_ASC,
            labels = linkedSetOf("bug", "help wanted"), milestone = RepoMilestoneFilter.Number(7, "Release 7"),
            assignee = RepoAssigneeFilter.Login("alice"), author = RepoAuthorFilter.Login("bob"),
            mentionedMe = true, updatedSince = Instant.parse("2026-11-17T03:04:05.123Z"),
        ), "viewer")
        assertEquals(
            "repo:octo/repo is:issue \"发布\" \"崩溃\" is:closed label:\"bug\",\"help wanted\" milestone:\"Release 7\" assignee:\"alice\" author:\"bob\" mentions:\"viewer\" updated:>=2026-11-17T03:04:05.123Z sort:comments-asc",
            query,
        )
    }

    @Test
    fun `keywords cannot override scope state or become boolean operators`() {
        val query = repoPullRequestSearchQuery("o", "r", "is:closed repo:other/project OR", RepoPullRequestFilter())
        assertEquals("repo:o/r is:pr \"is:closed\" \"repo:other/project\" \"OR\" is:open sort:updated-desc", query)
        val escaped = repoIssueSearchQuery("o", "r", "a\"b c\\d", RepoIssueFilter(), null)
        assertTrue(escaped.contains("\"a\\\"b\""))
        assertTrue(escaped.contains("\"c\\\\d\""))
    }

    @Test
    fun `all none and any filters preserve their meanings`() {
        val any = repoIssueSearchQuery("o", "r", "bug", RepoIssueFilter(
            state = RepoIssueStateFilter.ALL, assignee = RepoAssigneeFilter.ANY, milestone = RepoMilestoneFilter.NONE,
        ), null)
        assertTrue(any.contains("assignee:*"))
        assertTrue(any.contains("no:milestone"))
        assertFalse(any.contains("is:open"))
        assertFalse(any.contains("mentions:"))
        val none = repoIssueSearchQuery("o", "r", "bug", RepoIssueFilter(assignee = RepoAssigneeFilter.NONE), null)
        assertTrue(none.contains("no:assignee"))
        assertFalse(none.contains("milestone:"))
        val closed = repoPullRequestSearchQuery("o", "r", "fix", RepoPullRequestFilter(state = RepoPullRequestStateFilter.CLOSED))
        assertTrue(closed.contains("is:closed"))
        assertFalse(closed.contains("is:unmerged"))
    }

    @Test
    fun `discussion uses category name answered state and date created sort`() {
        val category = RepoDiscussionCategory("CAT1", "Q & A", "", null, true)
        assertEquals("repo:o/r \"help\" is:open category:\"Q & A\" is:unanswered sort:date_created-asc",
            repoDiscussionSearchQuery("o", "r", "help", RepoDiscussionFilter(category = category, answered = false, sort = RepoDiscussionSort.CREATED_ASC)))
        assertTrue(repoDiscussionSearchQuery("o", "r", "help", RepoDiscussionFilter(answered = true)).contains("is:answered"))
        val all = repoDiscussionSearchQuery("o", "r", "help", RepoDiscussionFilter(state = RepoDiscussionStateFilter.ALL))
        assertFalse(all.contains("category:"))
        assertFalse(all.contains("answered"))
        assertFalse(all.contains("is:open"))
    }

    @Test
    fun `every sort is mapped for its entity type`() {
        val issueSorts = listOf("updated-desc", "updated-asc", "created-desc", "created-asc", "comments-desc", "comments-asc")
        RepoIssueSort.entries.forEachIndexed { index, sort ->
            assertTrue(repoIssueSearchQuery("o", "r", "x", RepoIssueFilter(sort = sort), null).endsWith("sort:" + issueSorts[index]))
        }
        RepoPullRequestSort.entries.forEachIndexed { index, sort ->
            assertTrue(repoPullRequestSearchQuery("o", "r", "x", RepoPullRequestFilter(sort = sort)).endsWith("sort:" + issueSorts[index]))
        }
        val discussionSorts = listOf("updated-desc", "updated-asc", "date_created-desc", "date_created-asc")
        RepoDiscussionSort.entries.forEachIndexed { index, sort ->
            assertTrue(repoDiscussionSearchQuery("o", "r", "x", RepoDiscussionFilter(sort = sort)).endsWith("sort:" + discussionSorts[index]))
        }
    }
}
