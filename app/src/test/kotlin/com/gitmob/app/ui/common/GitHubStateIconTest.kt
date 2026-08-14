package com.gitmob.app.ui.common

import com.gitmob.app.data.model.DiscussionStateReason
import com.gitmob.app.data.model.IssueState
import com.gitmob.app.data.model.IssueStateReason
import com.gitmob.app.data.model.PullRequestState
import com.gitmob.app.ui.icons.OcticonName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubStateIconTest {

    @Test
    fun `Issue开放和重新开放使用不同图标`() {
        val opened = issueStateVisual(IssueState.OPEN, null)
        val reopened = issueStateVisual(IssueState.OPEN, IssueStateReason.REOPENED)

        assertEquals(OcticonName.ISSUE_OPENED, opened.icon)
        assertEquals(OcticonName.ISSUE_REOPENED, reopened.icon)
        assertEquals(GitHubStateColor.OPEN, opened.color)
        assertEquals(GitHubStateColor.OPEN, reopened.color)
    }

    @Test
    fun `Issue关闭原因映射完成不计划和重复`() {
        assertEquals(
            OcticonName.ISSUE_CLOSED,
            issueStateVisual(IssueState.CLOSED, IssueStateReason.COMPLETED).icon,
        )
        assertEquals(
            OcticonName.ISSUE_NOT_PLANNED,
            issueStateVisual(IssueState.CLOSED, IssueStateReason.NOT_PLANNED).icon,
        )
        assertEquals(
            OcticonName.ISSUE_DUPLICATE,
            issueStateVisual(IssueState.CLOSED, IssueStateReason.DUPLICATE).icon,
        )
    }

    @Test
    fun `PullRequest状态优先级为合并关闭草稿开放`() {
        assertEquals(
            OcticonName.PULL_REQUEST_MERGED,
            pullRequestStateVisual(PullRequestState.MERGED, isDraft = true).icon,
        )
        assertEquals(
            OcticonName.PULL_REQUEST_CLOSED,
            pullRequestStateVisual(PullRequestState.CLOSED, isDraft = true).icon,
        )
        assertEquals(
            OcticonName.PULL_REQUEST_DRAFT,
            pullRequestStateVisual(PullRequestState.OPEN, isDraft = true).icon,
        )
        assertEquals(
            OcticonName.PULL_REQUEST_OPENED,
            pullRequestStateVisual(PullRequestState.OPEN, isDraft = false).icon,
        )
    }

    @Test
    fun `Discussion状态和已回答标记分别映射`() {
        assertEquals(
            OcticonName.DISCUSSION_OPENED,
            discussionStateVisual(null, isAnswered = false).icon,
        )
        assertEquals(
            OcticonName.DISCUSSION_OPENED,
            discussionStateVisual(DiscussionStateReason.REOPENED, isAnswered = false).icon,
        )
        assertEquals(
            OcticonName.DISCUSSION_RESOLVED,
            discussionStateVisual(DiscussionStateReason.RESOLVED, isAnswered = false).icon,
        )
        assertEquals(
            OcticonName.DISCUSSION_DUPLICATE,
            discussionStateVisual(DiscussionStateReason.DUPLICATE, isAnswered = false).icon,
        )
        assertEquals(
            OcticonName.DISCUSSION_OUTDATED,
            discussionStateVisual(DiscussionStateReason.OUTDATED, isAnswered = false).icon,
        )
        assertTrue(
            GitHubStateBadge.ANSWERED in discussionStateVisual(null, isAnswered = true).badges,
        )
    }

    @Test
    fun `Release草稿优先于预发布且最新是辅助标记`() {
        val draft = releaseStateVisual(isDraft = true, isPrerelease = true)
        val prerelease = releaseStateVisual(isDraft = false, isPrerelease = true)
        val published = releaseStateVisual(isDraft = false, isPrerelease = false, isLatest = true)

        assertEquals(GitHubStateColor.NEUTRAL, draft.color)
        assertEquals(GitHubStateColor.ATTENTION, prerelease.color)
        assertEquals(GitHubStateColor.OPEN, published.color)
        assertTrue(GitHubStateBadge.LATEST in published.badges)
    }

    @Test
    fun `locked始终是辅助标记`() {
        val issue = issueStateVisual(IssueState.OPEN, null, locked = true)
        val pullRequest = pullRequestStateVisual(PullRequestState.OPEN, false, locked = true)

        assertTrue(GitHubStateBadge.LOCKED in issue.badges)
        assertTrue(GitHubStateBadge.LOCKED in pullRequest.badges)
        assertEquals(OcticonName.ISSUE_OPENED, issue.icon)
        assertEquals(OcticonName.PULL_REQUEST_OPENED, pullRequest.icon)
    }
}
