package com.gitmob.app.data.repository

import com.gitmob.app.data.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RepoListSearchQueryTest {
    @Test fun `discussion passes literal parentheses through and preserves repository scope`() {
        val query = "Actions (beta)"
        assertTrue(validListSearch(query, "community", "community", "discussion"))
        assertEquals("repo:community/community Actions (beta) sort:updated-desc", repoDiscussionSearchQuery(
            "community", "community", query, RepoDiscussionFilter(state = RepoDiscussionStateFilter.ALL),
        ))
        assertFalse(validListSearch("Actions (repo:other/project)", "community", "community", "discussion"))
        assertFalse(validListSearch("Actions (beta", "community", "community", "discussion"))
    }

    @Test
    fun `issue combines literal terms with all supported filters`() {
        val query = repoIssueSearchQuery("octo", "repo", "发布 崩溃", RepoIssueFilter(
            state = RepoIssueStateFilter.CLOSED, sort = RepoIssueSort.COMMENTS_ASC,
            labels = linkedSetOf("bug", "help wanted"), milestone = RepoMilestoneFilter.Number(7, "Release 7"),
            assignee = RepoAssigneeFilter.Login("alice"), author = RepoAuthorFilter.Login("bob"),
            mentionedMe = true, updatedSince = Instant.parse("2026-11-17T03:04:05.123Z"),
        ), "viewer")
        assertEquals(
            "repo:octo/repo is:issue 发布 崩溃 is:closed label:\"bug\",\"help wanted\" milestone:\"Release 7\" assignee:\"alice\" author:\"bob\" mentions:\"viewer\" updated:>=2026-11-17T03:04:05.123Z sort:comments-asc",
            query,
        )
    }

    @Test
    fun `manual and link queries project only lossless controls`() {
        val open = issueListSearch("is:issue state:open 发布", "o", "r", RepoIssueFilter(state = RepoIssueStateFilter.ALL))
        assertEquals("发布", open.text)
        assertEquals(RepoIssueStateFilter.OPEN, open.filter.state)
        val labels = issueListSearch("label:bug label:verified", "o", "r", open.filter)
        assertEquals("label:bug label:verified", labels.text)
        assertTrue(labels.filter.labels.isEmpty())
        val any = issueListSearch("label:bug,\"help wanted\" milestone:\"Release 1\"", "o", "r", open.filter)
        assertEquals(setOf("bug", "help wanted"), any.filter.labels)
        assertEquals(RepoMilestoneFilter.Title("Release 1"), any.filter.milestone)
        val conflicting = "milestone:next no:milestone assignee:bob no:assignee"
        assertEquals(conflicting, issueListSearch(conflicting, "o", "r", open.filter).text)
        val quoted = "\"part (A OR B)\""
        assertEquals(quoted, issueListSearch("state:closed $quoted", "o", "r", open.filter).text)
        val merged = pullListSearch("is:merged", "o", "r", RepoPullRequestFilter())
        assertEquals("is:merged", merged.text)
        assertEquals(RepoPullRequestStateFilter.ALL, merged.filter.state)
        val discussion = discussionListSearch("category:\"Q & A\" is:answered sort:date_created-asc", "o", "r", RepoDiscussionFilter())
        assertEquals("", discussion.text)
        assertEquals("Q & A", discussion.filter.categoryTitle)
        assertEquals(true, discussion.filter.answered)
        assertEquals(RepoDiscussionSort.CREATED_ASC, discussion.filter.sort)
    }

    @Test fun `advanced terms stay within the fixed repository and type`() {
        val expression = "(is:merged OR is:open) \"release notes\""
        assertTrue(validListSearch(expression, "o", "r", "pr"))
        assertFalse(validListSearch("is:merged OR (repo:other/project)", "o", "r", "pr"))
        assertFalse(validListSearch("(org:other OR release)", "o", "r", "pr"))
        assertFalse(validListSearch("is:issue", "o", "r", "pr"))
        assertFalse(validListSearch("x) OR (y", "o", "r", "pr"))
        assertTrue(repoPullRequestSearchQuery("o", "r", expression, RepoPullRequestFilter(state = RepoPullRequestStateFilter.ALL))
            .startsWith("repo:o/r is:pr ($expression)"))
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
        assertEquals("repo:o/r help is:open category:\"Q & A\" is:unanswered sort:date_created-asc",
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
