package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.gitmob.app.R
import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.data.model.FollowState
import com.gitmob.app.data.model.GHUser
import com.gitmob.app.data.model.PagedOrgs
import com.gitmob.app.data.model.PagedUsers
import com.gitmob.app.data.model.PagedStarredRepos
import com.gitmob.app.data.model.PinnedRepo
import com.gitmob.app.data.model.ProfileExtra
import com.gitmob.app.data.model.ProfileOwner
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.model.SimpleOrg
import com.gitmob.app.data.model.SimpleUser
import com.gitmob.app.data.model.SocialAccount
import com.gitmob.app.data.model.StarredRepo
import com.gitmob.app.data.model.UserStatus
import com.gitmob.app.data.model.ViewerProfile
import com.gitmob.app.graphql.FollowOrganizationMutation
import com.gitmob.app.graphql.FollowUserMutation
import com.gitmob.app.graphql.FollowersQuery
import com.gitmob.app.graphql.FollowingQuery
import com.gitmob.app.graphql.OrgMembersQuery
import com.gitmob.app.graphql.ProfileOwnerQuery
import com.gitmob.app.graphql.UnfollowOrganizationMutation
import com.gitmob.app.graphql.UnfollowUserMutation
import com.gitmob.app.graphql.UserOrganizationsQuery
import com.gitmob.app.graphql.UserOrgsQuery
import com.gitmob.app.graphql.UserProfileQuery
import com.gitmob.app.graphql.UserReposQuery
import com.gitmob.app.graphql.UserStarsQuery
import com.gitmob.app.graphql.ViewerOrganizationsQuery
import com.gitmob.app.graphql.ViewerProfileQuery
import com.gitmob.app.graphql.fragment.PinnedRepoFields
import com.gitmob.app.graphql.fragment.ProfileFields
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 只用 GraphQL，不用 REST——本项目主页/用户资料统一走 GraphQL 单一查询，
 * 一次往返拿全部数据（基础资料 + pronouns/status/socialAccounts/置顶仓库/关注状态 + 统计数字）。
 *
 * B1 起切换 Apollo：查询语句与模型由 .graphql 文件 + 官方 schema 生成，
 * 手写 query 字符串与手写 DTO 已全部移除。
 */
@Singleton
class UserRepository @Inject constructor(
    private val apolloClient: ApolloClient,
) {

    // ── 缓存实例 ──────────────────────────────────────────────
    /** viewer 自己的完整资料（底部 Tab「主页」+ 登录校验用），TTL 5 min */
    private val viewerProfileCache = MemoryCache<Unit, ViewerProfile>(ttlMs = 5 * 60_000L)

    /**
     * 公开：登出时由 AuthRepository 统一调用，清空当前 Repository 的全部内存缓存，
     * 避免账号切换后残留上一账号数据。
     */
    fun invalidateAllCaches() {
        viewerProfileCache.invalidateAll()
    }

    /**
     * 主页（当前登录用户自己）用这个，同时也是登录校验用的那次请求（成功即 token 有效）。
     * 带缓存：冷启动/进程被杀后第一次打开「主页」直接命中缓存，跳过网络等待。
     * 下拉刷新请调用 [getViewerProfileFresh]（绕过缓存，强制走网络）。
     */
    suspend fun getViewerProfile(): ApiResult<ViewerProfile> {
        // 1. 命中缓存直接返回（不发网络）——冷启动/切换 Tab 后首次打开主页走这条分支
        viewerProfileCache.get(Unit)?.let { return ApiResult.Success(it) }

        // 2. 未命中，走网络，成功后写缓存
        return getViewerProfileFresh()
    }

    /**
     * 下拉刷新专用：绕过缓存，强制走网络，成功后刷新缓存时间戳。
     * 与 [getViewerProfile] 的唯一区别就是跳过缓存 get 这一步。
     */
    suspend fun getViewerProfileFresh(): ApiResult<ViewerProfile> =
        when (val result = apolloClient.query(ViewerProfileQuery(
            socialAccountsFirst = PageSize.SOCIAL_ACCOUNTS,
            pinnedItemsFirst = PageSize.PINNED_ITEMS,
        )).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val data = result.data
                val profile = data.viewer.profileFields.toDomain(
                    gistCount = data.viewer.gists.totalCount,
                ).copy(
                    involvedIssueCount = data.issuesInvolvingMe.issueCount,
                    involvedPrCount = data.prsInvolvingMe.issueCount,
                    involvedDiscussionCount = data.discussionsInvolvingMe.discussionCount,
                )
                viewerProfileCache.set(Unit, profile)
                profile
            }
        }

    /** 查看别人（或用 login 查自己也可以）资料用这个，Follow 按钮要用到的 viewerCanFollow 等字段同一套 */
    suspend fun getUserProfile(login: String): ApiResult<ViewerProfile> =
        when (val result = apolloClient.query(UserProfileQuery(
            login = login,
            socialAccountsFirst = PageSize.SOCIAL_ACCOUNTS,
            pinnedItemsFirst = PageSize.PINNED_ITEMS,
        )).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val node = result.data.user ?: throw UserVisibleException(R.string.error_user_not_found)
                node.profileFields.toDomain(gistCount = node.gists.totalCount)
            }
        }

    /**
     * 关注用户
     *
     * 对齐 GitHub 官方 App 做法：mutation 只查询 clientMutationId 验证请求成功，
     * UI 状态（viewerIsFollowing、followersCount）由调用方做本地乐观更新。
     * 原因：GitHub API 的 followers.totalCount 是最终一致的聚合计数，mutation
     * 返回值会慢一拍，不可用于直接更新 UI。
     *
     * @param userId 用户的 GraphQL 全局 ID
     */
    suspend fun followUser(userId: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(FollowUserMutation(userId)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                // 关注后 viewer.following.totalCount 可能变化，主动失效 profile 缓存，下次打开主页重新拉
                viewerProfileCache.invalidate(Unit)
                ApiResult.Success(Unit)
            }
        }

    /**
     * 取消关注用户
     *
     * 同 followUser，对齐官方 App 做法：clientMutationId 模式 + 客户端本地更新 UI
     *
     * @param userId 用户的 GraphQL 全局 ID
     */
    suspend fun unfollowUser(userId: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(UnfollowUserMutation(userId)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                // 取消关注后 viewer.following.totalCount 可能变化，主动失效 profile 缓存
                viewerProfileCache.invalidate(Unit)
                ApiResult.Success(Unit)
            }
        }

    /**
     * 关注组织
     *
     * @param organizationId 组织的 GraphQL 全局 ID
     */
    suspend fun followOrganization(organizationId: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(FollowOrganizationMutation(organizationId)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(Unit)
        }

    /**
     * 取消关注组织
     *
     * @param organizationId 组织的 GraphQL 全局 ID
     */
    suspend fun unfollowOrganization(organizationId: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(UnfollowOrganizationMutation(organizationId)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(Unit)
        }

    suspend fun getFollowers(login: String, after: String? = null): ApiResult<PagedUsers> =
        when (val result = apolloClient.query(
            FollowersQuery(login, Optional.presentIfNotNull(after), PageSize.USER_LIST),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val conn = result.data.user?.followers
                    ?: throw UserVisibleException(R.string.error_user_not_found)
                PagedUsers(
                    totalCount = conn.totalCount,
                    users = conn.nodes.orEmpty().filterNotNull()
                        .map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio) },
                    hasNextPage = conn.pageInfo.hasNextPage,
                    endCursor = conn.pageInfo.endCursor,
                )
            }
        }

    suspend fun getFollowing(login: String, after: String? = null): ApiResult<PagedUsers> =
        when (val result = apolloClient.query(
            FollowingQuery(login, Optional.presentIfNotNull(after), PageSize.USER_LIST),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val conn = result.data.user?.following
                    ?: throw UserVisibleException(R.string.error_user_not_found)
                PagedUsers(
                    totalCount = conn.totalCount,
                    users = conn.nodes.orEmpty().filterNotNull()
                        .map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio) },
                    hasNextPage = conn.pageInfo.hasNextPage,
                    endCursor = conn.pageInfo.endCursor,
                )
            }
        }

    /**
     * 统一的组织列表查询入口：自己的主页弹底部 OrganizationsBottomSheet + 他人个人主页弹底部 OrganizationsBottomSheet。
     *
     * BottomSheet 设计是轻量预览（容量由 PageSize.ORGS_PREVIEW 决定），不是独立分页列表页，
     * 因此只需要 nodes（login/name/avatarUrl）不需要 totalCount/pageInfo 分页结构。
     *
     * @param login `null` 时走 `viewer.organizations`（当前登录用户，HomeScreen 底部弹窗）；
     *              非空时走 `user(login:).organizations`（他人 ProfileScreen 底部弹窗）。
     */
    suspend fun getOrganizations(login: String? = null): ApiResult<List<SimpleOrg>> {
        if (login == null) {
            return when (val result = apolloClient.query(ViewerOrganizationsQuery(PageSize.ORGS_PREVIEW)).executeAsApiResult()) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> ApiResult.Success(
                    result.data.viewer.organizations.nodes.orEmpty().filterNotNull()
                        .map { SimpleOrg(it.login, it.name, it.avatarUrl) },
                )
            }
        }
        return when (val result = apolloClient.query(UserOrganizationsQuery(login, PageSize.ORGS_PREVIEW)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(
                result.data.user?.organizations?.nodes.orEmpty().filterNotNull()
                    .map { SimpleOrg(it.login, it.name, it.avatarUrl) },
            )
        }
    }

    /**
     * 用户/组织统一资料查询——只有一个 login 时无法预先判断类型，
     * 用 repositoryOwner(login:) + __typename 分流，一次查询搞定两种情况。
     */
    suspend fun getProfileOwner(login: String): ApiResult<ProfileOwner> =
        when (val result = apolloClient.query(ProfileOwnerQuery(
            login = login,
            socialAccountsFirst = PageSize.SOCIAL_ACCOUNTS,
            pinnedItemsFirst = PageSize.PINNED_ITEMS,
        )).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val data = result.data
                val node = data.repositoryOwner
                    ?: throw UserVisibleException(R.string.error_owner_not_found)
                val onUser = node.onUser
                val onOrg = node.onOrganization
                when {
                    onUser != null -> ProfileOwner.Person(
                        id = node.id,
                        login = node.login,
                        avatarUrl = node.avatarUrl,
                        name = onUser.name,
                        repoCount = onUser.repositories.totalCount,
                        websiteUrl = onUser.websiteUrl,
                        bio = onUser.bio,
                        email = onUser.email,
                        company = onUser.company,
                        location = onUser.location,
                        pronouns = onUser.pronouns,
                        status = onUser.status?.let { UserStatus(it.emoji, it.message) },
                        socialAccounts = onUser.socialAccounts.nodes.orEmpty().filterNotNull().map {
                            SocialAccount(it.displayName, it.provider.rawValue, it.url)
                        },
                        isViewer = onUser.isViewer,
                        followState = FollowState(onUser.isViewer, onUser.viewerCanFollow, onUser.viewerIsFollowing),
                        followersCount = onUser.followers.totalCount,
                        followingCount = onUser.following.totalCount,
                        organizationsCount = onUser.organizations.totalCount,
                        starredCount = onUser.starredRepositories.totalCount,
                        gistCount = if (onUser.isViewer) {
                            data.viewer.gists.totalCount
                        } else {
                            onUser.gists.totalCount
                        },
                        isDeveloperProgramMember = onUser.isDeveloperProgramMember,
                        isBountyHunter = onUser.isBountyHunter,
                        isCampusExpert = onUser.isCampusExpert,
                        isGitHubStar = onUser.isGitHubStar,
                        pinnedRepos = onUser.pinnedItems.toPinnedRepos(),
                    )
                    onOrg != null -> ProfileOwner.Org(
                        id = node.id,
                        login = node.login,
                        avatarUrl = node.avatarUrl,
                        name = onOrg.name,
                        repoCount = onOrg.repositories.totalCount,
                        websiteUrl = onOrg.websiteUrl,
                        description = onOrg.description,
                        isVerified = onOrg.isVerified,
                        membersCount = onOrg.membersWithRole.totalCount,
                        viewerIsFollowing = onOrg.viewerIsFollowing,
                        viewerCanCreateRepositories = onOrg.viewerCanCreateRepositories,
                        pinnedRepos = onOrg.pinnedItems.toPinnedRepos(),
                    )
                    else -> throw IllegalStateException("未知的 owner 类型: ${node.__typename}")
                }
            }
        }

    private fun ProfileFields.toDomain(gistCount: Int): ViewerProfile = ViewerProfile(
        user = GHUser(
            id = id,
            login = login,
            name = name,
            email = email,
            avatarUrl = avatarUrl,
            followers = followers.totalCount,
            following = following.totalCount,
            organizationsCount = organizations.totalCount,
            bio = bio,
            websiteUrl = websiteUrl,
            location = location,
            company = company,
            isDeveloperProgramMember = isDeveloperProgramMember,
            isBountyHunter = isBountyHunter,
            isCampusExpert = isCampusExpert,
            isGitHubStar = isGitHubStar,
        ),
        extra = ProfileExtra(
            pronouns = pronouns,
            status = status?.let { UserStatus(it.emoji, it.message) },
            socialAccounts = socialAccounts.nodes.orEmpty().filterNotNull().map {
                SocialAccount(it.displayName, it.provider.rawValue, it.url)
            },
        ),
        repoCount = repositories.totalCount,
        starredCount = starredRepositories.totalCount,
        gistCount = gistCount,
        pinnedRepos = pinnedItems.toPinnedRepos(),
        followState = FollowState(
            isViewer = isViewer,
            viewerCanFollow = viewerCanFollow,
            viewerIsFollowing = viewerIsFollowing,
        ),
    )

    private fun com.gitmob.app.graphql.fragment.ProfileFields.PinnedItems.toPinnedRepos(): List<PinnedRepo> =
        nodes.orEmpty().filterNotNull().mapNotNull { it.pinnedRepoFields }.map { it.toPinnedRepo() }

    private fun ProfileOwnerQuery.PinnedItems.toPinnedRepos(): List<PinnedRepo> =
        nodes.orEmpty().filterNotNull().mapNotNull { it.pinnedRepoFields }.map { it.toPinnedRepo() }

    private fun ProfileOwnerQuery.PinnedItems1.toPinnedRepos(): List<PinnedRepo> =
        nodes.orEmpty().filterNotNull().mapNotNull { it.pinnedRepoFields }.map { it.toPinnedRepo() }

    private fun PinnedRepoFields.toPinnedRepo() = PinnedRepo(
        name = name,
        url = url,
        description = description,
        stargazerCount = stargazerCount,
        forkCount = forkCount,
        languageName = primaryLanguage?.name,
        languageColor = primaryLanguage?.color,
        ownerLogin = owner.login,
        ownerAvatarUrl = owner.avatarUrl,
    )

    // ================================
    // 他人主页统计行跳转：4 个列表查询
    // ================================

    /**
     * 指定用户的仓库列表（他人主页 → 点击"仓库"统计项）。
     * 与 RepoRepository.getRepos() 的区别：根字段是 user(login:) 不是 viewer，
     * 可以查任意 login 的公开仓库；ownerAffiliations 只传 OWNER（组织仓库不属于个人名下）。
     */
    suspend fun getUserRepos(login: String, after: String? = null): ApiResult<RepoList> =
        when (val result = apolloClient.query(
            UserReposQuery(login, Optional.presentIfNotNull(after), PageSize.REPOS, PageSize.TOPICS_PER_REPO),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val conn = result.data.user?.repositories
                    ?: throw UserVisibleException(R.string.error_user_not_found)
                RepoList(
                    totalCount = conn.totalCount,
                    items = conn.nodes.orEmpty().filterNotNull().map { it.toRepoListItem() },
                    hasNextPage = conn.pageInfo.hasNextPage,
                    endCursor = conn.pageInfo.endCursor,
                )
            }
        }

    private fun UserReposQuery.Node.toRepoListItem() = RepoListItem(
        name = name,
        ownerLogin = owner.login,
        description = description,
        homepageUrl = homepageUrl,
        visibility = RepoVisibility.valueOf(visibility.rawValue),
        isArchived = isArchived,
        isFork = isFork,
        hasIssuesEnabled = hasIssuesEnabled,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
        pushedAt = pushedAt?.let(Instant::parse),
        forkedFromOwner = parent?.owner?.login,
        forkedFromName = parent?.name,
        languageName = primaryLanguage?.name,
        languageColor = primaryLanguage?.color,
        stargazerCount = stargazerCount,
        forkCount = forkCount,
        openIssueCount = issues.totalCount,
        topics = repositoryTopics.nodes.orEmpty().filterNotNull().map { it.topic.name },
        defaultBranchName = defaultBranchRef?.name,
    )

    /**
     * 指定用户的星标仓库列表（他人主页 → 点击"星标"统计项）。
     * 与 StarRepository.getViewerStarred() 的区别：根字段是 user(login:) 不是 viewer，
     * 可以查任意 login 的公开星标仓库；orderBy 走 STARRED_AT DESC 与 GitHub 官方一致。
     */
    suspend fun getUserStars(login: String, after: String? = null): ApiResult<PagedStarredRepos> =
        when (val result = apolloClient.query(
            UserStarsQuery(login, Optional.presentIfNotNull(after), PageSize.STARRED_REPOS, PageSize.TOPICS_PER_REPO),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val conn = result.data.user?.starredRepositories
                    ?: throw UserVisibleException(R.string.error_user_not_found)
                PagedStarredRepos(
                    totalCount = conn.totalCount,
                    items = conn.edges.orEmpty().filterNotNull().mapNotNull { it.node }.map { it.toStarredRepo() },
                    hasNextPage = conn.pageInfo.hasNextPage,
                    endCursor = conn.pageInfo.endCursor,
                )
            }
        }

    private fun UserStarsQuery.Node.toStarredRepo() = StarredRepo(
        id = id,
        name = name,
        url = url,
        description = description,
        homepageUrl = homepageUrl,
        ownerLogin = owner.login,
        ownerAvatarUrl = owner.avatarUrl,
        isPrivate = isPrivate,
        isArchived = isArchived,
        isFork = isFork,
        forkedFromOwner = parent?.owner?.login,
        forkedFromName = parent?.name,
        languageName = primaryLanguage?.name,
        languageColor = primaryLanguage?.color,
        stargazerCount = stargazerCount,
        forkCount = forkCount,
        openIssueCount = issues.totalCount,
        topics = repositoryTopics.nodes.orEmpty().filterNotNull().map { it.topic.name },
        defaultBranchName = defaultBranchRef?.name,
    )

    /**
     * 指定用户的组织列表（他人主页 → 点击"组织"统计项）。
     * 与 getOrganizations() 的区别：根字段是 user(login:) 不是 viewer，
     * 带 totalCount + pageInfo 分页，支持查任意 login 的公开组织。
     */
    suspend fun getUserOrgs(login: String, after: String? = null): ApiResult<PagedOrgs> =
        when (val result = apolloClient.query(
            UserOrgsQuery(login, Optional.presentIfNotNull(after), PageSize.ORGS),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val conn = result.data.user?.organizations
                    ?: throw UserVisibleException(R.string.error_user_not_found)
                PagedOrgs(
                    totalCount = conn.totalCount,
                    orgs = conn.nodes.orEmpty().filterNotNull().map { SimpleOrg(it.login, it.name, it.avatarUrl) },
                    hasNextPage = conn.pageInfo.hasNextPage,
                    endCursor = conn.pageInfo.endCursor,
                )
            }
        }

    /**
     * 指定组织的成员列表（组织主页 → 点击"成员"统计项）。
     * 通过 organization(login:) 根字段查 membersWithRole，返回 User 节点列表；
     * 数据形状与关注者/关注列表一致，因此复用 PagedUsers/SimpleUser。
     */
    suspend fun getOrgMembers(orgLogin: String, after: String? = null): ApiResult<PagedUsers> =
        when (val result = apolloClient.query(
            OrgMembersQuery(orgLogin, Optional.presentIfNotNull(after), PageSize.USER_LIST),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val conn = result.data.organization?.membersWithRole
                    ?: throw UserVisibleException(R.string.error_org_not_found)
                PagedUsers(
                    totalCount = conn.totalCount,
                    users = conn.nodes.orEmpty().filterNotNull()
                        .map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio) },
                    hasNextPage = conn.pageInfo.hasNextPage,
                    endCursor = conn.pageInfo.endCursor,
                )
            }
        }
}
