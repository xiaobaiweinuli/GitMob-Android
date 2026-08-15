package com.gitmob.app.data.model

import kotlinx.serialization.Serializable

/** GraphQL 原始响应结构（贴合 query 形状），只在 Repository 内部使用，不传到 UI 层 */

@Serializable
data class ViewerProfileQueryData(val viewer: ProfileNode)

@Serializable
data class UserProfileQueryData(val user: ProfileNode? = null)

@Serializable
data class ProfileNode(
    val id: String,
    val login: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val company: String? = null,
    val location: String? = null,
    val websiteUrl: String? = null,
    val email: String? = null,
    val pronouns: String? = null,
    /** 徽章 1/4：开发者计划成员（对应 Octicon BADGE_DEVELOPER_PROGRAM，cpu-16） */
    val isDeveloperProgramMember: Boolean = false,
    /** 徽章 2/4：安全赏金猎人（对应 Octicon BADGE_SECURITY_BOUNTY_HUNTER，lock-16） */
    val isBountyHunter: Boolean = false,
    /** 徽章 3/4：校园专家（对应 Octicon BADGE_CAMPUS_EXPERT，mortar-board-16） */
    val isCampusExpert: Boolean = false,
    /** 徽章 4/4：GitHub Star（对应 Octicon BADGE_GITHUB_STAR，star-fill-16，紫粉 tint） */
    val isGitHubStar: Boolean = false,
    val isViewer: Boolean = false,
    val viewerCanFollow: Boolean = false,
    val viewerIsFollowing: Boolean = false,
    val status: UserStatusNode? = null,
    val socialAccounts: SocialAccountConnection,
    val followers: TotalCountNode,
    val following: TotalCountNode,
    val organizations: TotalCountNode,
    val repositories: TotalCountNode,
    val starredRepositories: TotalCountNode,
    val gists: TotalCountNode? = null,
    val pinnedItems: PinnedItemsConnection,
)

@Serializable
data class UserStatusNode(val emoji: String? = null, val message: String? = null)

@Serializable
data class SocialAccountConnection(val nodes: List<SocialAccountNode>)

@Serializable
data class SocialAccountNode(val displayName: String, val provider: String, val url: String)

@Serializable
data class TotalCountNode(val totalCount: Int)

@Serializable
data class PinnedItemsConnection(val nodes: List<PinnedRepoNode>)

// 注意：不查 isDiscussionsEnabled / lists（Repository 上这两个字段公开 API 不支持，
// 已在之前的抓包分析里确认过），也不查 achievements（User 上这个字段公开 API 不支持）
@Serializable
data class PinnedRepoNode(
    val name: String,
    val url: String,
    val shortDescriptionHTML: String? = null,
    val stargazerCount: Int = 0,
    val forkCount: Int = 0,
    val primaryLanguage: LanguageNode? = null,
    val owner: OwnerNode,
)

@Serializable
data class LanguageNode(val name: String, val color: String? = null)

@Serializable
data class OwnerNode(val login: String, val avatarUrl: String? = null)

// ---- 关注者/关注列表分页 ----

@Serializable
data class UserListQueryData(val user: UserListConnectionHolder? = null)

@Serializable
data class UserListConnectionHolder(val followers: PagedUserConnection? = null, val following: PagedUserConnection? = null)

@Serializable
data class PagedUserConnection(
    val totalCount: Int,
    val nodes: List<SimpleUserNode>,
    val pageInfo: PageInfoNode,
)

@Serializable
data class SimpleUserNode(val login: String, val name: String? = null, val avatarUrl: String? = null, val bio: String? = null, val id: String? = null)

@Serializable
data class PageInfoNode(val hasNextPage: Boolean, val endCursor: String? = null)

// ---- Follow/Unfollow mutation ----
// 对齐 GitHub 官方 App 做法：mutation 只查询 clientMutationId 验证请求成功，
// UI 状态（viewerIsFollowing、followersCount）由调用方做本地乐观更新。
// 原因：GitHub API 的 followers.totalCount 是最终一致的聚合计数，mutation
// 返回值会慢一拍，不可用于直接更新 UI。

@Serializable
data class FollowUserMutationData(val followUser: FollowUserPayload? = null)

@Serializable
data class FollowUserPayload(val clientMutationId: String? = null)

@Serializable
data class UnfollowUserMutationData(val unfollowUser: UnfollowUserPayload? = null)

@Serializable
data class UnfollowUserPayload(val clientMutationId: String? = null)

@Serializable
data class FollowOrganizationMutationData(val followOrganization: FollowOrganizationPayload? = null)

@Serializable
data class FollowOrganizationPayload(val clientMutationId: String? = null)

@Serializable
data class UnfollowOrganizationMutationData(val unfollowOrganization: UnfollowOrganizationPayload? = null)

@Serializable
data class UnfollowOrganizationPayload(val clientMutationId: String? = null)

// ---- 组织列表 ----

@Serializable
data class OrganizationsQueryData(val viewer: OrgListHolder)

/**
 * 统一的组织查询 DTO：同时暴露 viewer（自己）和 user（任意指定用户）两个 nullable 根，
 * 配合 UserRepository.getOrganizations(login:) 使用——哪种模式查询，哪个根字段会被
 * GraphQL 服务端实际填充，另一个保持 null。
 *
 * 不分页，返回简单的 nodes 列表（与 OrganizationsBottomSheet 配合，BottomSheet 只需要 first:30）。
 */
@Serializable
data class UnifiedOrganizationsQueryData(
    val viewer: OrgListHolder? = null,
    val user: OrgListHolder? = null,
)

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
data class UserStarsQueryData(val user: UserStarsHolder? = null)

@Serializable
data class UserStarsHolder(val starredRepositories: StarredRepoConnection)

@Serializable
data class UserOrgsQueryData(val user: UserOrgsHolder? = null)

@Serializable
data class UserOrgsHolder(val organizations: PagedOrgConnection)

// ---- 组织维度列表（组织主页：成员）----

@Serializable
data class OrgMembersQueryData(val organization: OrgMembersHolder? = null)

@Serializable
data class OrgMembersHolder(val membersWithRole: PagedUserConnection)

// ---- 用户/组织统一资料查询（repositoryOwner） ----

@Serializable
data class RepositoryOwnerQueryData(
    val viewer: ProfileViewerGistCountNode? = null,
    val repositoryOwner: RepositoryOwnerNode? = null,
)

@Serializable
data class ProfileViewerGistCountNode(
    val login: String,
    val gists: TotalCountNode,
)

@Serializable
data class RepositoryOwnerNode(
    val __typename: String,
    val id: String,
    val login: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val url: String? = null,
    // ================================
    // User 分支字段
    // ================================
    val bio: String? = null,
    val company: String? = null,
    val location: String? = null,
    val email: String? = null,
    val pronouns: String? = null,
    val status: UserStatusNode? = null,
    val socialAccounts: SocialAccountConnection? = null,
    /** User 分支：徽章 1/4（开发者计划成员） */
    val isDeveloperProgramMember: Boolean = false,
    /** User 分支：徽章 2/4（安全赏金猎人） */
    val isBountyHunter: Boolean = false,
    /** User 分支：徽章 3/4（校园专家） */
    val isCampusExpert: Boolean = false,
    /** User 分支：徽章 4/4（GitHub Star 贡献者） */
    val isGitHubStar: Boolean = false,
    val isViewer: Boolean = false,
    val viewerCanFollow: Boolean = false,
    val followers: TotalCountNode? = null,
    val following: TotalCountNode? = null,
    /** User 分支：组织数量统计（用于 ProfileScreen 个人资料统计行） */
    val organizations: TotalCountNode? = null,
    /** User 分支：星标仓库数量统计（用于 ProfileScreen 个人资料统计行） */
    val starredRepositories: TotalCountNode? = null,
    val gists: TotalCountNode? = null,
    // ================================
    // Organization 分支字段
    // ================================
    val description: String? = null,
    val isVerified: Boolean = false,
    val membersWithRole: TotalCountNode? = null,
    // ================================
    // 两者共有
    // ================================
    /** viewerIsFollowing：User 和 Organization 根级别都有此字段（Actor 接口不包含，但两个类型各自有） */
    val viewerIsFollowing: Boolean = false,
    val repositories: TotalCountNode? = null,
    val websiteUrl: String? = null,
    /** User / Organization 各自 fragment 中查询的真正置顶仓库；RepositoryOwner 接口本身不暴露该字段。 */
    val pinnedItems: PinnedItemsConnection? = null,
)

// ---- 仓库列表（"仓库" Tab 用） ----

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
    val viewer: RepoListHolder? = null,
    val repositoryOwner: RepoListHolder? = null,
)

@Serializable
data class RepoListHolder(val repositories: RepoListConnection)

@Serializable
data class RepoListConnection(
    val totalCount: Int,
    val nodes: List<RepoListItemNode>,
    val pageInfo: PageInfoNode,
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

// ---- Gist 列表 ----

@Serializable
data class ViewerGistsQueryData(
    val viewer: ViewerGistsNode,
    val user: UserGistsNode? = null,
)

@Serializable
data class ViewerGistsNode(
    val login: String,
    val gists: GistConnectionNode? = null,
)

@Serializable
data class UserGistsNode(
    val login: String,
    val gists: GistConnectionNode,
)

@Serializable
data class GistConnectionNode(
    val edges: List<GistEdgeNode?>? = null,
    val pageInfo: PageInfoNode,
)

@Serializable
data class GistEdgeNode(
    val cursor: String,
    val node: GistNode? = null,
)

@Serializable
data class GistNode(
    val id: String,
    val name: String,
    val description: String? = null,
    val owner: OwnerNode? = null,
    val isPublic: Boolean = true,
    val isFork: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    val stargazerCount: Int = 0,
    val comments: TotalCountNode,
    val url: String,
    val previewFiles: List<GistFileNode?>? = null,
    val fileMetadata: List<GistFileNode?>? = null,
)

@Serializable
data class GistFileNode(
    val name: String? = null,
    val text: String? = null,
    val size: Int? = null,
    val isTruncated: Boolean = false,
    val isImage: Boolean = false,
    val language: LanguageNode? = null,
)

// ---- 星标列表功能 ----

@Serializable
data class ViewerListsQueryData(val viewer: ListsHolder)

@Serializable
data class ListsHolder(val lists: ListConnection)

@Serializable
data class ListConnection(val nodes: List<UserListNode>)

@Serializable
data class UserListNode(
    val id: String,
    val name: String,
    val slug: String,
    val description: String? = null,
    val isPrivate: Boolean = false,
    val items: TotalCountNode,
)

@Serializable
data class StarredReposQueryData(val viewer: StarredReposHolder)

/**
 * 统一的星标仓库查询 DTO：同时暴露 viewer（底部 Tab 星标页，带列表管理功能）和
 * user（他人星标页，只读轻量页）两个 nullable 根，配合 StarRepository 的 viewer/user 分离入口
 * 使用。两种模式下内部的 starredRepositories 字段结构 100% 一致，共用 StarredRepoConnection。
 */
@Serializable
data class UnifiedStarredReposQueryData(
    val viewer: StarredReposHolder? = null,
    val user: StarredReposHolder? = null,
)

@Serializable
data class StarredReposHolder(val starredRepositories: StarredRepoConnection)

@Serializable
data class StarredRepoConnection(
    val totalCount: Int,
    val edges: List<StarredRepoEdge>,
    val pageInfo: PageInfoNode,
)

@Serializable
data class StarredRepoEdge(val node: StarredRepoNode)

@Serializable
data class StarredRepoNode(
    val id: String,
    val name: String,
    val url: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val owner: OwnerNode,
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
data class ListItemsQueryData(val node: ListItemsNodeHolder? = null)

@Serializable
data class ListItemsNodeHolder(val items: ListItemsConnection? = null)

@Serializable
data class ListItemsConnection(
    val totalCount: Int,
    val nodes: List<StarredRepoNode>,
    val pageInfo: PageInfoNode,
)

@Serializable
data class ListsContainingQueryData(val viewer: ListsContainingHolder)

@Serializable
data class ListsContainingHolder(val lists: ListsContainingConnection)

@Serializable
data class ListsContainingConnection(val nodes: List<ListWithItemIdsNode>)

@Serializable
data class ListWithItemIdsNode(val id: String, val items: ItemIdsConnection)

@Serializable
data class ItemIdsConnection(val nodes: List<RepoIdNode>)

@Serializable
data class RepoIdNode(val id: String? = null)

@Serializable
data class CreateListMutationData(val createUserList: CreateListPayload? = null)

@Serializable
data class CreateListPayload(val list: UserListNode? = null)

@Serializable
data class UpdateListsForItemMutationData(val updateUserListsForItem: UpdateListsForItemPayload? = null)

@Serializable
data class UpdateListsForItemPayload(val clientMutationId: String? = null)

@Serializable
data class UpdateListMutationData(val updateUserList: UpdateListPayload? = null)

@Serializable
data class UpdateListPayload(val list: UserListNode? = null)

@Serializable
data class DeleteListMutationData(val deleteUserList: DeleteListPayload? = null)

@Serializable
data class DeleteListPayload(val clientMutationId: String? = null)

@Serializable
data class RemoveStarMutationData(val removeStar: RemoveStarPayload? = null)

@Serializable
data class RemoveStarPayload(val clientMutationId: String? = null)

@Serializable
data class AddStarMutationData(val addStar: AddStarPayload? = null)

@Serializable
data class AddStarPayload(val clientMutationId: String? = null)

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

// ---- Repository issues ----

@Serializable
data class RepoIssuesQueryData(val repository: RepoIssuesRepositoryNode? = null)

@Serializable
data class RepoIssuesRepositoryNode(
    val id: String,
    val viewerPermission: String? = null,
    val viewerCanCreateIssues: Boolean = false,
    val hasIssuesEnabled: Boolean = false,
    val issues: RepoIssueConnectionNode,
)

@Serializable
data class RepoIssueConnectionNode(
    val totalCount: Int = 0,
    val nodes: List<RepoIssueNode> = emptyList(),
    val pageInfo: PageInfoNode,
)

@Serializable
data class RepoIssueNode(
    val id: String,
    val number: Int,
    val title: String,
    val body: String? = null,
    val bodyHTML: String? = null,
    val state: String,
    val stateReason: String? = null,
    val author: SimpleUserNode? = null,
    val createdAt: String,
    val updatedAt: String,
    val comments: RepoIssueCommentConnectionNode,
    val labels: RepoIssueLabelConnectionNode? = null,
    val assignees: RepoIssueAssigneeConnectionNode? = null,
    val milestone: RepoIssueMilestoneNode? = null,
    val locked: Boolean = false,
    val viewerCanClose: Boolean = false,
    val viewerCanDelete: Boolean = false,
    val viewerCanLabel: Boolean = false,
    val viewerCanSetMilestone: Boolean = false,
    val viewerCanUpdate: Boolean = false,
    val viewerCanSubscribe: Boolean = false,
    val viewerCanReopen: Boolean = false,
    val viewerSubscription: String? = null,
)

@Serializable
data class RepoIssueCommentConnectionNode(
    val totalCount: Int = 0,
    val nodes: List<RepoIssueCommentNode> = emptyList(),
    val pageInfo: PageInfoNode? = null,
)

@Serializable
data class RepoIssueCommentNode(
    val id: String,
    val author: SimpleUserNode? = null,
    val body: String? = null,
    val bodyHTML: String = "",
    val createdAt: String,
    val updatedAt: String,
    val viewerDidAuthor: Boolean = false,
    val viewerCanUpdate: Boolean = false,
    val viewerCanDelete: Boolean = false,
    val viewerCanReact: Boolean = false,
)

@Serializable data class RepoIssueLabelConnectionNode(val nodes: List<RepoIssueLabelNode> = emptyList())
@Serializable data class RepoIssueLabelNode(val id: String, val name: String, val color: String, val description: String? = null)
@Serializable data class RepoIssueAssigneeConnectionNode(val nodes: List<SimpleUserNode> = emptyList())
@Serializable data class RepoIssueMilestoneNode(val id: String, val number: Int, val title: String, val state: String, val dueOn: String? = null)

@Serializable
data class RepoIssueDetailQueryData(val repository: RepoIssueDetailRepositoryNode? = null)

@Serializable
data class RepoIssueDetailRepositoryNode(
    val id: String,
    val viewerPermission: String? = null,
    val issue: RepoIssueNode? = null,
)

@Serializable data class RepoLabelsQueryData(val repository: RepoLabelsRepositoryNode? = null)
@Serializable data class RepoLabelsRepositoryNode(val labels: RepoIssueLabelConnectionNode? = null)
@Serializable data class RepoMilestonesQueryData(val repository: RepoMilestonesRepositoryNode? = null)
@Serializable data class RepoMilestonesRepositoryNode(val milestones: RepoMilestoneConnectionNode? = null)
@Serializable data class RepoMilestoneConnectionNode(val nodes: List<RepoIssueMilestoneNode> = emptyList())
@Serializable data class RepoAssignableUsersQueryData(val repository: RepoAssignableUsersRepositoryNode? = null)
@Serializable data class RepoAssignableUsersRepositoryNode(val assignableUsers: RepoIssueAssigneeConnectionNode? = null)
@Serializable data class RepoIssueTemplatesQueryData(val repository: RepoIssueTemplatesRepositoryNode? = null)
@Serializable data class RepoIssueTemplatesRepositoryNode(
    val id: String,
    val viewerCanCreateIssues: Boolean = false,
    val isBlankIssuesEnabled: Boolean = false,
    val issueTemplates: List<RepoIssueTemplateNode> = emptyList(),
)
@Serializable data class RepoIssueTemplateNode(
    val name: String,
    val about: String? = null,
    val title: String? = null,
    val body: String? = null,
    val filename: String,
    val labels: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
)

@Serializable data class CreateIssueMutationData(val createIssue: IssueMutationPayload? = null)
@Serializable data class UpdateIssueMutationData(val updateIssue: IssueMutationPayload? = null)
@Serializable data class CloseIssueMutationData(val closeIssue: IssueMutationPayload? = null)
@Serializable data class ReopenIssueMutationData(val reopenIssue: IssueMutationPayload? = null)
@Serializable data class IssueMutationPayload(val issue: RepoIssueNode? = null)
@Serializable data class DeleteIssueMutationData(val deleteIssue: ClientMutationPayload? = null)
@Serializable data class AddIssueCommentMutationData(val addComment: IssueCommentMutationPayload? = null)
@Serializable data class UpdateIssueCommentMutationData(val updateIssueComment: IssueCommentMutationPayload? = null)
@Serializable data class IssueCommentMutationPayload(val commentEdge: IssueCommentEdgeNode? = null, val issueComment: RepoIssueCommentNode? = null)
@Serializable data class IssueCommentEdgeNode(val node: RepoIssueCommentNode? = null)
@Serializable data class DeleteIssueCommentMutationData(val deleteIssueComment: ClientMutationPayload? = null)
@Serializable data class UpdateIssueSubscriptionMutationData(val updateSubscription: SubscriptionMutationPayload? = null)
@Serializable data class SubscriptionMutationPayload(val subscribable: SubscriptionNode? = null)
@Serializable data class SubscriptionNode(val viewerSubscription: String? = null)
@Serializable data class ClientMutationPayload(val clientMutationId: String? = null)

// ---- 主页事务入口行：议题/PR/讨论 involves:@me 计数 ----

@Serializable
data class ViewerProfileWithWorkQueryData(
    val viewer: ProfileNode,
    val issuesInvolvingMe: SearchIssueCountNode,
    val prsInvolvingMe: SearchIssueCountNode,
    val discussionsInvolvingMe: SearchDiscussionCountNode,
)

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
