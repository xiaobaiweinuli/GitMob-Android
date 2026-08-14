package com.gitmob.app.data.model

enum class IssueState { OPEN, CLOSED }

enum class IssueStateReason { REOPENED, NOT_PLANNED, COMPLETED, DUPLICATE }

enum class PullRequestState { OPEN, CLOSED, MERGED }

enum class DiscussionStateReason { RESOLVED, OUTDATED, DUPLICATE, REOPENED }

/** "议题"/"拉取请求" 入口列表用（search type: ISSUE，Issue/PullRequest 用 isPullRequest 区分）。 */
data class WorkIssueItem(
    val id: String,
    val number: Int,
    val title: String,
    val repoOwner: String,
    val repoName: String,
    val isPullRequest: Boolean,
    val issueState: IssueState? = null,
    val issueStateReason: IssueStateReason? = null,
    val pullRequestState: PullRequestState? = null,
    val isDraft: Boolean = false,
    val locked: Boolean = false,
    val updatedAt: String,
)

data class WorkDiscussionItem(
    val id: String,
    val number: Int,
    val title: String,
    val repoOwner: String,
    val repoName: String,
    val stateReason: DiscussionStateReason? = null,
    val isAnswered: Boolean = false,
    val locked: Boolean = false,
    val updatedAt: String,
)

data class PagedWorkIssues(
    val totalCount: Int,
    val items: List<WorkIssueItem>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

data class PagedWorkDiscussions(
    val totalCount: Int,
    val items: List<WorkDiscussionItem>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)
