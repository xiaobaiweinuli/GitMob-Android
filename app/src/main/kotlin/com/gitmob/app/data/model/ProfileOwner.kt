package com.gitmob.app.data.model

/** 简化版组织信息，用于"选择组织"底部弹窗列表，不需要完整资料 */
data class SimpleOrg(
    val login: String,
    val name: String?,
    val avatarUrl: String?,
)

/**
 * 个人主页 / 组织主页统一模型。只有一个 login 字符串时无法预先判断是用户还是组织，
 * 用 repositoryOwner(login:) 查询 + __typename 分流，一次查询、不重试。
 * 见 references/architecture.md 里"个人主页/组织主页统一查询"一节。
 */
sealed class ProfileOwner {
    abstract val login: String
    abstract val avatarUrl: String?
    abstract val name: String?
    abstract val repoCount: Int
    abstract val websiteUrl: String?
    abstract val pinnedRepos: List<PinnedRepo>

    data class Person(
        val id: String,
        override val login: String,
        override val avatarUrl: String?,
        override val name: String?,
        override val repoCount: Int,
        override val websiteUrl: String?,
        val bio: String?,
        val pronouns: String?,
        val isViewer: Boolean,
        val followState: FollowState,
        val followersCount: Int,
        val followingCount: Int,
        /** 个人资料统计行：组织数量（对应 HomeScreen ViewerProfile 的 organizationsCount） */
        val organizationsCount: Int,
        /** 个人资料统计行：星标数量（对应 HomeScreen ViewerProfile 的 starredCount） */
        val starredCount: Int,
        /** 公开 Gist 数量；当前登录用户自己由 viewer 查询显示公开和秘密总数。 */
        val gistCount: Int = 0,
        /** 徽章 1/4：开发者计划成员（对应 Octicon BADGE_DEVELOPER_PROGRAM） */
        val isDeveloperProgramMember: Boolean,
        /** 徽章 2/4：安全赏金猎人（对应 Octicon BADGE_SECURITY_BOUNTY_HUNTER） */
        val isBountyHunter: Boolean,
        /** 徽章 3/4：校园专家（对应 Octicon BADGE_CAMPUS_EXPERT） */
        val isCampusExpert: Boolean,
        /** 徽章 4/4：GitHub Star 贡献者（对应 Octicon BADGE_GITHUB_STAR，无则不显示，不造假空占位） */
        val isGitHubStar: Boolean,
        val email: String? = null,
        val company: String? = null,
        val location: String? = null,
        val status: UserStatus? = null,
        val socialAccounts: List<SocialAccount> = emptyList(),
        override val pinnedRepos: List<PinnedRepo> = emptyList(),
    ) : ProfileOwner()

    /**
     * 组织资料信息
     *
     * @property id GraphQL 全局 ID，用于 mutation 操作（如 followOrganization）
     * @property login 组织登录名（唯一标识）
     * @property avatarUrl 头像 URL
     * @property name 组织显示名称
     * @property repoCount 仓库数量
     * @property websiteUrl 官网链接
     * @property description 组织简介纯文本
     * @property isVerified 是否已验证
     * @property membersCount 成员数量
     * @property viewerIsFollowing 当前登录用户是否已关注该组织
     */
    data class Org(
        val id: String,
        override val login: String,
        override val avatarUrl: String?,
        override val name: String?,
        override val repoCount: Int,
        override val websiteUrl: String?,
        val description: String?,
        val isVerified: Boolean,
        val membersCount: Int,
        val viewerIsFollowing: Boolean,
        override val pinnedRepos: List<PinnedRepo> = emptyList(),
    ) : ProfileOwner()
}
