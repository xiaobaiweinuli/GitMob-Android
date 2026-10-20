package com.gitmob.app.data.model

import kotlinx.serialization.Serializable

/** GraphQL 原始响应结构（贴合 query 形状），只在 Repository 内部使用，不传到 UI 层 */

@Serializable
data class TotalCountNode(val totalCount: Int)

@Serializable
data class LanguageNode(val name: String, val color: String? = null)

@Serializable
data class OwnerNode(val login: String, val avatarUrl: String? = null)

// ---- 关注者/关注列表分页 ----

@Serializable
data class PagedUserConnection(
    val totalCount: Int,
    val nodes: List<SimpleUserNode>,
    val pageInfo: PageInfoNode,
)

@Serializable
data class SimpleUserNode(val login: String, val name: String? = null, val avatarUrl: String? = null, val bio: String? = null, val id: String? = null)

@Serializable
data class PageInfoNode(val hasNextPage: Boolean = false, val endCursor: String? = null)

// ---- Follow/Unfollow mutation ----
// 对齐 GitHub 官方 App 做法：mutation 只查询 clientMutationId 验证请求成功，
// UI 状态（viewerIsFollowing、followersCount）由调用方做本地乐观更新。
// 原因：GitHub API 的 followers.totalCount 是最终一致的聚合计数，mutation
// 返回值会慢一拍，不可用于直接更新 UI。

@Serializable
data class OrganizationsQueryData(val viewer: OrgListHolder)

/**
 * 统一的组织查询 DTO：同时暴露 viewer（自己）和 user（任意指定用户）两个 nullable 根，
 * 配合 UserRepository.getOrganizations(login:) 使用——哪种模式查询，哪个根字段会被
 * GraphQL 服务端实际填充，另一个保持 null。
 *
 * 不分页，返回简单的 nodes 列表（与 OrganizationsBottomSheet 配合，容量由 PageSize.ORGS_PREVIEW 决定）。
 */
@Serializable
data class OrgListHolder(val organizations: OrgConnection)

@Serializable
data class OrgConnection(val nodes: List<OrgNode>)

/** 用户组织列表（带分页 totalCount + pageInfo，区别于 OrganizationsQueryData 中 viewer.organizations 的简单 OrgConnection） */
@Serializable
data class PagedOrgConnection(
    val totalCount: Int,
    val nodes: List<OrgNode>,
    val pageInfo: PageInfoNode,
)

@Serializable
data class OrgNode(val login: String, val name: String? = null, val avatarUrl: String? = null)

// ---- 用户维度列表（他人主页：仓库/星标/组织）----

@Serializable
data class UserRepoListQueryData(val user: UserRepoListHolder? = null)

@Serializable
data class UserRepoListHolder(val repositories: RepoListConnection)

@Serializable
data class ViewerRepoListQueryData(val viewer: RepoListHolder)

 /**
 * 统一的仓库列表查询 DTO：
 * - viewer：自己的「仓库」Tab（login == null）
 * - repositoryOwner：任意 login（个人或组织），一次查询兼容 User / Organization
 *
 * 内部 repositories 字段结构 100% 一致，共用 RepoListConnection / RepoListItemNode。
 */
@Serializable
data class UnifiedRepoListQueryData(
    val viewer: RepoListViewerHolder? = null,
    val repositoryOwner: RepoListOwnerHolder? = null,
)

@Serializable
data class RepoListViewerHolder(
    val id: String = "",
    val login: String = "",
    val name: String? = null,
    val avatarUrl: String? = null,
    val repositories: RepoListConnection = RepoListConnection(),
)

@Serializable
data class RepoListOwnerHolder(
    val __typename: String = "",
    val id: String = "",
    val login: String = "",
    val name: String? = null,
    val avatarUrl: String? = null,
    val isViewer: Boolean = false,
    val viewerCanCreateRepositories: Boolean = false,
    val repositories: RepoListConnection = RepoListConnection(),
)

@Serializable
data class RepoListHolder(val repositories: RepoListConnection)

@Serializable
data class RepoListConnection(
    val totalCount: Int = 0,
    val nodes: List<RepoListItemNode> = emptyList(),
    val pageInfo: PageInfoNode = PageInfoNode(),
)

@Serializable
data class RepositoryCreateOwnersQueryData(
    val viewer: RepositoryCreateViewerNode = RepositoryCreateViewerNode(),
)

@Serializable
data class RepositoryCreateViewerNode(
    val id: String = "",
    val login: String = "",
    val name: String? = null,
    val avatarUrl: String? = null,
    val organizations: RepositoryCreateOrganizationConnection = RepositoryCreateOrganizationConnection(),
)

@Serializable
data class RepositoryCreateOrganizationConnection(
    val nodes: List<RepositoryCreateOrganizationNode> = emptyList(),
    val pageInfo: PageInfoNode = PageInfoNode(),
)

@Serializable
data class RepositoryCreateOrganizationNode(
    val id: String = "",
    val login: String = "",
    val name: String? = null,
    val avatarUrl: String? = null,
    val viewerCanCreateRepositories: Boolean = false,
)

@Serializable
data class RepoListItemNode(
    val name: String,
    val owner: OwnerNode,
    val description: String? = null,
    val homepageUrl: String? = null,
    val isPrivate: Boolean = false,
    val isArchived: Boolean = false,
    val isFork: Boolean = false,
    val parent: ForkParentNode? = null,
    val primaryLanguage: LanguageNode? = null,
    val stargazerCount: Int = 0,
    val forkCount: Int = 0,
    val issues: TotalCountNode,
    val repositoryTopics: TopicConnection,
    val defaultBranchRef: BranchRefNode? = null,
)

@Serializable
data class ForkParentNode(val name: String, val owner: OwnerNode)

@Serializable
data class TopicConnection(val nodes: List<TopicNode>)

@Serializable
data class TopicNode(val topic: TopicNameNode)

@Serializable
data class TopicNameNode(val name: String)

@Serializable
data class BranchRefNode(val name: String)

// ---- 仓库详情 ----

@Serializable
data class RepoDetailQueryData(val repository: RepoDetailNode? = null)

@Serializable
data class RepoDetailNode(
    val id: String,
    val name: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val owner: OwnerNode,
    val isPrivate: Boolean = false,
    val isArchived: Boolean = false,
    val isTemplate: Boolean = false,
    val isFork: Boolean = false,
    val parent: ForkParentNode? = null,
    val stargazerCount: Int = 0,
    val viewerHasStarred: Boolean = false,
    val forkCount: Int = 0,
    val issues: TotalCountNode,
    val pullRequests: TotalCountNode,
    val watchers: TotalCountNode,
    val viewerSubscription: String = "UNSUBSCRIBED",
    val licenseInfo: LicenseNode? = null,
    val refs: TotalCountNode,
    val defaultBranchRef: BranchRefNode? = null,
    val releases: ReleaseConnectionNode,
    val primaryLanguage: LanguageNode? = null,
    val repositoryTopics: TopicConnection,
    val viewerPermission: String? = null,
    val viewerCanCreateIssues: Boolean = false,
    val hasIssuesEnabled: Boolean = false,
    val isBlankIssuesEnabled: Boolean = false,
    val issueCreationPolicy: String? = null,
    val hasPullRequestsEnabled: Boolean = false,
    val pullRequestCreationPolicy: String? = null,
    val hasDiscussionsEnabled: Boolean = false,
    val discussions: TotalCountNode = TotalCountNode(0),
)

@Serializable
data class LicenseNode(val name: String? = null, val spdxId: String? = null)

@Serializable
data class LatestReleaseNode(val name: String? = null, val tagName: String)

@Serializable
data class ReleaseConnectionNode(val totalCount: Int, val nodes: List<LatestReleaseNode> = emptyList())

// README（object(expression:) 方式，Repository.readme 不存在于公开 Schema，见 markdown-rendering.md）
@Serializable
data class RepoReadmeQueryData(val repository: RepoReadmeHolder? = null)

@Serializable
data class RepoReadmeHolder(val `object`: BlobNode? = null)

@Serializable
data class BlobNode(val text: String? = null, val isTruncated: Boolean = false, val isBinary: Boolean = false)

// 分支列表
@Serializable
data class RepoBranchesQueryData(val repository: RepoBranchesHolder? = null)

@Serializable
data class RepoBranchesHolder(val refs: RefConnection? = null, val defaultBranchRef: BranchRefIdNode? = null)

@Serializable
data class RefConnection(val nodes: List<RefNode>, val pageInfo: PageInfoNode)

@Serializable
data class RefNode(val id: String, val name: String, val target: RefTargetNode? = null)

@Serializable
data class RefTargetNode(val oid: String? = null)

@Serializable
data class BranchRefIdNode(val id: String, val name: String)

@Serializable
data class RepoWatchersQueryData(val repository: RepoWatchersHolder? = null)

@Serializable
data class RepoWatchersHolder(val watchers: PagedUserConnection? = null)

@Serializable
data class DeleteRefMutationData(val deleteRef: DeleteRefPayload? = null)

@Serializable
data class DeleteRefPayload(val clientMutationId: String? = null)

// ---- 主页事务入口行：议题/PR/讨论 involves:@me 计数 ----

@Serializable
data class SearchIssueCountNode(val issueCount: Int? = null)

@Serializable
data class SearchDiscussionCountNode(val discussionCount: Int? = null)

// ---- 议题/PR/讨论 involves:@me 分页列表 ----

@Serializable
data class WorkSearchQueryData(val search: WorkSearchConnection)

@Serializable
data class WorkSearchConnection(
    val issueCount: Int = 0,
    val discussionCount: Int = 0,
    val nodes: List<WorkSearchNode>,
    val pageInfo: PageInfoNode,
)

@Serializable
data class WorkSearchNode(
    val __typename: String,
    val id: String,
    val number: Int,
    val title: String,
    val state: String? = null,
    val stateReason: String? = null,
    val isDraft: Boolean = false,
    val isAnswered: Boolean = false,
    val locked: Boolean = false,
    val updatedAt: String,
    val repository: WorkRepoRefNode,
)

@Serializable
data class WorkRepoRefNode(val name: String, val owner: OwnerNode)
