package com.gitmob.app.data.model

/**
 * 领域模型（不是网络层 DTO）。全部数据来自 GraphQL，不再走 REST。
 * 徽章字段共 4 个，均公开 Schema 可查（见 github_graphql_schema.json），
 * Pro 和 Security Advisory Credit 在公开 Schema 不存在对应 is* 字段，
 * 不造假、不显示空占位，只维护以下真实 4 个：
 *   isDeveloperProgramMember → BADGE_DEVELOPER_PROGRAM (cpu-16)
 *   isBountyHunter          → BADGE_SECURITY_BOUNTY_HUNTER (lock-16)
 *   isCampusExpert          → BADGE_CAMPUS_EXPERT (mortar-board-16)
 *   isGitHubStar            → BADGE_GITHUB_STAR (star-fill-16)
 */
data class GHUser(
    val id: String,
    val login: String,
    val name: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    val followers: Int = 0,
    val following: Int = 0,
    val organizationsCount: Int = 0,
    val bio: String? = null,
    val websiteUrl: String? = null,
    val location: String? = null,
    val company: String? = null,
    /** 徽章 1/4：开发者计划成员（公开 Schema User.isDeveloperProgramMember） */
    val isDeveloperProgramMember: Boolean = false,
    /** 徽章 2/4：安全赏金猎人（公开 Schema User.isBountyHunter） */
    val isBountyHunter: Boolean = false,
    /** 徽章 3/4：校园专家（公开 Schema User.isCampusExpert） */
    val isCampusExpert: Boolean = false,
    /** 徽章 4/4：GitHub Star 贡献者（公开 Schema User.isGitHubStar） */
    val isGitHubStar: Boolean = false,
)

data class ProfileExtra(
    val pronouns: String?,
    val status: UserStatus?,
    val socialAccounts: List<SocialAccount>,
)

data class UserStatus(
    val emoji: String?,
    val message: String?,
)

data class SocialAccount(
    val displayName: String,
    val provider: String,
    val url: String,
)

data class PinnedRepo(
    val name: String,
    val url: String,
    val description: String?,
    val stargazerCount: Int,
    val forkCount: Int,
    val languageName: String?,
    val languageColor: String?,
    val ownerLogin: String,
    val ownerAvatarUrl: String?,
)

/**
 * 关注状态。isViewer 为 true 时代表"这就是当前登录用户自己"，不显示关注按钮；
 * 否则用 viewerCanFollow/viewerIsFollowing 驱动关注按钮的可见性和文案。
 */
data class FollowState(
    val isViewer: Boolean,
    val viewerCanFollow: Boolean,
    val viewerIsFollowing: Boolean,
)

/** 主页/资料页要展示的一次性聚合结果，全部来自单次 GraphQL 查询 */
data class ViewerProfile(
    val user: GHUser,
    val extra: ProfileExtra?,
    val repoCount: Int,
    val starredCount: Int,
    val gistCount: Int = 0,
    val pinnedRepos: List<PinnedRepo>,
    val followState: FollowState,
    // 下面三个只有 getViewerProfile()（主页自己）会真正赋值，getUserProfile()（查看别人）
    // 永远是 0——@me 语义只对当前登录用户有意义，见 UserRepository.getViewerProfile 的注释
    val involvedIssueCount: Int = 0,
    val involvedPrCount: Int = 0,
    val involvedDiscussionCount: Int = 0,
)

data class SimpleUser(
    val login: String,
    val name: String?,
    val avatarUrl: String?,
    val bio: String?,
    val id: String? = null,
)

enum class CommentAuthorAssociation {
    OWNER,
    MEMBER,
    COLLABORATOR,
    CONTRIBUTOR,
    FIRST_TIME_CONTRIBUTOR,
    FIRST_TIMER,
    MANNEQUIN,
    NONE,
}

data class PagedUsers(
    val totalCount: Int,
    val users: List<SimpleUser>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

/**
 * 用户组织分页列表（与 PagedUsers 对称），用于"他人主页 → 组织"点击跳转的列表页。
 * 注意与"选择组织"底部弹窗的 List<SimpleOrg> 区分：后者不分页、只查当前登录用户自己的组织；
 * 前者按 login 查任意用户的组织、带 totalCount + 分页游标。
 */
data class PagedOrgs(
    val totalCount: Int,
    val orgs: List<SimpleOrg>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)
