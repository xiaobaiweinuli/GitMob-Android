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

// ---- Gist 列表 ----

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
    val url: String = "",
    val number: Int,
    val title: String,
    val body: String? = null,
    val bodyHTML: String? = null,
    val state: String,
    val stateReason: String? = null,
    val author: SimpleUserNode? = null,
    val authorAssociation: String = "NONE",
    val createdAt: String,
    val updatedAt: String,
    val includesCreatedEdit: Boolean = false,
    val lastEditedAt: String? = null,
    val editor: SimpleUserNode? = null,
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
    val url: String = "",
    val author: SimpleUserNode? = null,
    val authorAssociation: String = "NONE",
    val body: String? = null,
    val bodyHTML: String = "",
    val createdAt: String,
    val updatedAt: String,
    val includesCreatedEdit: Boolean = false,
    val lastEditedAt: String? = null,
    val editor: SimpleUserNode? = null,
    val viewerDidAuthor: Boolean = false,
    val viewerCanUpdate: Boolean = false,
    val viewerCanDelete: Boolean = false,
    val viewerCanReact: Boolean = false,
)

@Serializable data class RepoIssueLabelConnectionNode(val nodes: List<RepoIssueLabelNode> = emptyList(), val pageInfo: PageInfoNode = PageInfoNode(false, null))
@Serializable data class RepoIssueLabelNode(val id: String, val name: String, val color: String, val description: String? = null)
@Serializable data class RepoIssueAssigneeConnectionNode(val nodes: List<SimpleUserNode> = emptyList(), val pageInfo: PageInfoNode = PageInfoNode(false, null))
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
@Serializable data class RepoMilestoneConnectionNode(val nodes: List<RepoIssueMilestoneNode> = emptyList(), val pageInfo: PageInfoNode = PageInfoNode(false, null))
@Serializable data class RepoAssignableUsersQueryData(val repository: RepoAssignableUsersRepositoryNode? = null)
@Serializable data class RepoAssignableUsersRepositoryNode(val assignableUsers: RepoIssueAssigneeConnectionNode? = null)
@Serializable data class RepoIssueFormContextQueryData(val repository: RepoIssueFormContextRepositoryNode? = null)
@Serializable data class RepoIssueFormContextRepositoryNode(
    val id: String,
    val viewerCanCreateIssues: Boolean = false,
    val isBlankIssuesEnabled: Boolean = false,
    val defaultBranchRef: BranchRefNode? = null,
)
@Serializable data class RepoIssueFormsQueryData(val repository: RepoIssueFormsRepositoryNode? = null)
@Serializable data class RepoIssueFormsRepositoryNode(
    @kotlinx.serialization.SerialName("object") val objectNode: RepoIssueFormsTreeNode? = null,
)
@Serializable data class RepoIssueFormsTreeNode(
    val entries: List<RepoIssueFormTreeEntryNode> = emptyList(),
)
@Serializable data class RepoIssueFormTreeEntryNode(
    val name: String,
    val path: String? = null,
    val type: String,
    @kotlinx.serialization.SerialName("object") val objectNode: RepoIssueFormBlobNode? = null,
)
@Serializable data class RepoIssueFormBlobNode(
    val text: String? = null,
    val isBinary: Boolean = false,
    val isTruncated: Boolean = false,
    val byteSize: Int = 0,
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
