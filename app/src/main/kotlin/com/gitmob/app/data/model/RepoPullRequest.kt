package com.gitmob.app.data.model

import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission

enum class RepoPullRequestState { OPEN, CLOSED, MERGED }
enum class RepoPullRequestStateFilter { OPEN, CLOSED, MERGED, ALL }
enum class RepoPullRequestSort { UPDATED_DESC, UPDATED_ASC, CREATED_DESC, CREATED_ASC, COMMENTS_DESC, COMMENTS_ASC }
enum class RepoPullRequestMergeMethod { MERGE, SQUASH, REBASE }
enum class RepoPullRequestReviewEvent { COMMENT, APPROVE, REQUEST_CHANGES }

data class RepoPullRequestFilter(
    val state: RepoPullRequestStateFilter = RepoPullRequestStateFilter.OPEN,
    val sort: RepoPullRequestSort = RepoPullRequestSort.UPDATED_DESC,
    val labels: Set<String> = emptySet(),
    val baseRefName: String? = null,
    val headRefName: String? = null,
)

data class RepoPullRequest(
    val id: String,
    val number: Int,
    val title: String,
    val body: String,
    val bodyHtml: String,
    val state: RepoPullRequestState,
    val isDraft: Boolean,
    val locked: Boolean,
    val author: SimpleUser?,
    val createdAt: String,
    val updatedAt: String,
    val baseRefName: String,
    val headRefName: String,
    val headRepositoryNameWithOwner: String?,
    val commentCount: Int,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    val labels: List<IssueLabel>,
    val assignees: List<SimpleUser>,
    val milestone: IssueMilestone?,
    val mergeable: String,
    val mergeStateStatus: String,
    val reviewDecision: String?,
    val statusCheckState: String?,
    val viewerCanClose: Boolean,
    val viewerCanReopen: Boolean,
    val viewerCanUpdate: Boolean,
    val viewerCanLabel: Boolean,
    val viewerCanAssign: Boolean,
    val viewerCanSubscribe: Boolean,
    val viewerCanEnableAutoMerge: Boolean,
    val viewerCanDisableAutoMerge: Boolean,
    val viewerCanUpdateBranch: Boolean,
    val viewerSubscription: String?,
    val autoMergeEnabled: Boolean,
)

data class RepoPullRequestPage(
    val repositoryId: String,
    val permission: RepoPermission,
    val capabilities: RepoCapabilities,
    val hasPullRequestsEnabled: Boolean,
    val creationPolicy: PullRequestCreationPolicy,
    val defaultBranchName: String?,
    val allowedMergeMethods: Set<RepoPullRequestMergeMethod>,
    val totalCount: Int,
    val items: List<RepoPullRequest>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

data class RepoPullRequestComment(
    val id: String,
    val author: SimpleUser?,
    val body: String,
    val bodyHtml: String,
    val createdAt: String,
    val updatedAt: String,
    val viewerCanUpdate: Boolean,
    val viewerCanDelete: Boolean,
    val viewerCanReact: Boolean,
)

data class RepoPullRequestReview(
    val id: String,
    val author: SimpleUser?,
    val bodyHtml: String,
    val state: String,
    val submittedAt: String?,
    val viewerCanUpdate: Boolean,
    val viewerCanDelete: Boolean,
)

data class RepoPullRequestReviewComment(
    val id: String,
    val author: SimpleUser?,
    val body: String,
    val bodyHtml: String,
    val path: String,
    val line: Int?,
    val originalLine: Int?,
    val outdated: Boolean,
    val createdAt: String,
    val viewerCanUpdate: Boolean,
    val viewerCanDelete: Boolean,
)

data class RepoPullRequestReviewThread(
    val id: String,
    val path: String,
    val line: Int?,
    val isResolved: Boolean,
    val isOutdated: Boolean,
    val viewerCanReply: Boolean,
    val viewerCanResolve: Boolean,
    val viewerCanUnresolve: Boolean,
    val comments: List<RepoPullRequestReviewComment>,
)

data class RepoPullRequestCommit(
    val oid: String,
    val headline: String,
    val committedAt: String,
    val authorLogin: String?,
    val authorAvatarUrl: String?,
)

data class RepoPullRequestFile(
    val path: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val changes: Int,
    val patch: String?,
    val blobUrl: String?,
)

data class RepoPullRequestDetail(
    val repositoryId: String,
    val permission: RepoPermission,
    val capabilities: RepoCapabilities,
    val allowedMergeMethods: Set<RepoPullRequestMergeMethod>,
    val pullRequest: RepoPullRequest,
    val comments: List<RepoPullRequestComment>,
    val reviews: List<RepoPullRequestReview>,
    val reviewThreads: List<RepoPullRequestReviewThread>,
    val commits: List<RepoPullRequestCommit>,
    val files: List<RepoPullRequestFile>,
    val commentsHasNextPage: Boolean,
    val commentsEndCursor: String?,
)

data class RepoPullRequestHeadRepository(
    val id: String,
    val owner: String,
    val name: String,
    val branches: List<RepoBranch>,
)

data class RepoPullRequestCreateMetadata(
    val repositoryId: String,
    val viewerLogin: String,
    val defaultBranchName: String?,
    val repositories: List<RepoPullRequestHeadRepository>,
    val labels: List<IssueLabel>,
    val milestones: List<IssueMilestone>,
    val assignees: List<SimpleUser>,
    val reviewers: List<SimpleUser>,
)

data class CreateRepoPullRequestInput(
    val repositoryId: String,
    val baseRefName: String,
    val headRefName: String,
    val headRepositoryId: String?,
    val title: String,
    val body: String,
    val draft: Boolean,
)

data class UpdateRepoPullRequestInput(
    val id: String,
    val title: String,
    val body: String,
    val baseRefName: String,
    val labelIds: List<String>,
    val assigneeIds: List<String>,
    val milestoneId: String?,
)
