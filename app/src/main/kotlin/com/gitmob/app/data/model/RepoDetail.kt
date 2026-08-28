package com.gitmob.app.data.model

import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission

data class RepoDetail(
    val id: String,
    val name: String,
    val ownerLogin: String,
    val ownerAvatarUrl: String?,
    val description: String?,
    val homepageUrl: String?,
    val isPrivate: Boolean,
    val isArchived: Boolean,
    val isTemplate: Boolean,
    val isFork: Boolean,
    val forkedFromOwner: String?,
    val forkedFromName: String?,
    val stargazerCount: Int,
    val viewerHasStarred: Boolean,
    val forkCount: Int,
    val openIssueCount: Int,
    val openPrCount: Int,
    val watcherCount: Int,
    /** SUBSCRIBED / UNSUBSCRIBED / IGNORED，对应 Watch 按钮的三态 */
    val viewerSubscription: String,
    val licenseName: String?,
    val licenseSpdxId: String?,
    val branchCount: Int,
    val defaultBranchName: String?,
    val releaseCount: Int,
    val latestReleaseName: String?,
    val latestReleaseTag: String?,
    val languageName: String?,
    val languageColor: String?,
    val topics: List<String>,
    val capabilities: RepoCapabilities,
    val permission: RepoPermission = RepoPermission.NONE,
    val viewerCanCreateIssues: Boolean = false,
    val hasIssuesEnabled: Boolean = false,
    val isBlankIssuesEnabled: Boolean = false,
    val issueCreationPolicy: IssueCreationPolicy = IssueCreationPolicy.UNKNOWN,
    val hasPullRequestsEnabled: Boolean = false,
    val pullRequestCreationPolicy: PullRequestCreationPolicy = PullRequestCreationPolicy.UNKNOWN,
    val hasDiscussionsEnabled: Boolean = false,
    val openDiscussionCount: Int = 0,
)

enum class IssueCreationPolicy { ALL, COLLABORATORS_ONLY, UNKNOWN }

enum class PullRequestCreationPolicy { ALL, COLLABORATORS_ONLY, UNKNOWN }

data class RepoReadme(
    val markdown: String?,
    val isTruncated: Boolean,
)

data class RepoBranch(
    val id: String,
    val name: String,
    val isDefault: Boolean,
    val commitOid: String?,
)

sealed interface BranchCreationSpec {
    data class FromExisting(val sourceBranch: String) : BranchCreationSpec
    data object Empty : BranchCreationSpec
}

/**
 * 分支分页结果包装。
 * 不含 totalCount：getBranches 查询的 refs 未查 totalCount，
 * （totalCount 已在 getRepoDetail 里用 refs(first:0) 单独取过）。
 */
data class PagedBranches(
    val items: List<RepoBranch>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)
