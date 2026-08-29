package com.gitmob.app.data.repository

import com.gitmob.app.R
import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.FollowOrganizationMutationData
import com.gitmob.app.data.model.FollowState
import com.gitmob.app.data.model.FollowUserMutationData
import com.gitmob.app.data.model.GHUser
import com.gitmob.app.data.model.OrgMembersQueryData
import com.gitmob.app.data.model.OrganizationsQueryData
import com.gitmob.app.data.model.PagedOrgs
import com.gitmob.app.data.model.PagedUsers
import com.gitmob.app.data.model.PagedStarredRepos
import com.gitmob.app.data.model.PinnedRepo
import com.gitmob.app.data.model.PinnedItemsConnection
import com.gitmob.app.data.model.ProfileExtra
import com.gitmob.app.data.model.ProfileNode
import com.gitmob.app.data.model.ProfileOwner
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepositoryOwnerQueryData
import com.gitmob.app.data.model.SimpleOrg
import com.gitmob.app.data.model.SimpleUser
import com.gitmob.app.data.model.SocialAccount
import com.gitmob.app.data.model.UnfollowOrganizationMutationData
import com.gitmob.app.data.model.UnifiedOrganizationsQueryData
import com.gitmob.app.data.model.UnfollowUserMutationData
import com.gitmob.app.data.model.UserListQueryData
import com.gitmob.app.data.model.UserOrgsQueryData
import com.gitmob.app.data.model.UserProfileQueryData
import com.gitmob.app.data.model.UserRepoListQueryData
import com.gitmob.app.data.model.UserStarsQueryData
import com.gitmob.app.data.model.UserStatus
import com.gitmob.app.data.model.ViewerProfile
import com.gitmob.app.data.model.ViewerProfileWithWorkQueryData
import com.gitmob.app.data.model.toDomain
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 只用 GraphQL，不用 REST——本项目主页/用户资料统一走 GraphQL 单一查询，
 * 一次往返拿全部数据（基础资料 + pronouns/status/socialAccounts/置顶仓库/关注状态 + 统计数字）。
 */
@Singleton
class UserRepository @Inject constructor(
    private val api: GHApiClient,
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
    private val pinnedRepositoryFields = """
        pinnedItems(first: ${PageSize.PINNED_ITEMS}, types: [REPOSITORY]) {
            nodes {
                ... on Repository {
                    name
                    url
                    description
                    stargazerCount
                    forkCount
                    primaryLanguage { name color }
                    owner { login avatarUrl }
                }
            }
        }
    """.trimIndent()

    // 公共字段片段：viewer{} 和 user(login:){} 两个根字段返回的都是 User 类型，字段集合完全一致。
    // 不查 achievements（User 上不存在于公开 Schema）、不查 Repository.lists/isDiscussionsEnabled
    // （公开 Schema 不支持），这几个是之前抓包分析里确认过的官方 App 内部专属字段。
    // 徽章公开 Schema 只有 4 个 is* 布尔字段，严格只查这 4 个，不造假、不查不存在字段：
    //   isDeveloperProgramMember → BADGE_DEVELOPER_PROGRAM
    //   isBountyHunter          → BADGE_SECURITY_BOUNTY_HUNTER
    //   isCampusExpert          → BADGE_CAMPUS_EXPERT
    //   isGitHubStar            → BADGE_GITHUB_STAR
    private val profileFields = """
        id
        login
        name
        avatarUrl
        bio
        company
        location
        websiteUrl
        email
        pronouns
        isDeveloperProgramMember
        isBountyHunter
        isCampusExpert
        isGitHubStar
        isViewer
        viewerCanFollow
        viewerIsFollowing
        status { emoji message }
        socialAccounts(first: ${PageSize.SOCIAL_ACCOUNTS}) { nodes { displayName provider url } }
        followers { totalCount }
        following { totalCount }
        organizations { totalCount }
        repositories(ownerAffiliations: [OWNER]) { totalCount }
        starredRepositories { totalCount }
        $pinnedRepositoryFields
    """.trimIndent()

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
    suspend fun getViewerProfileFresh(): ApiResult<ViewerProfile> = safeCall {
        val query = """
            query ViewerProfile {
                viewer {
                    $profileFields
                    gists(privacy: ALL) { totalCount }
                }
                issuesInvolvingMe: search(query: "involves:@me is:issue is:open", type: ISSUE) { issueCount }
                prsInvolvingMe: search(query: "involves:@me is:pr is:open", type: ISSUE) { issueCount }
                discussionsInvolvingMe: search(query: "involves:@me", type: DISCUSSION) { discussionCount }
            }
        """.trimIndent()
        val data = api.graphQL<ViewerProfileWithWorkQueryData>(query)
        val profile = data.viewer.toDomain().copy(
            involvedIssueCount = data.issuesInvolvingMe.issueCount ?: 0,
            involvedPrCount = data.prsInvolvingMe.issueCount ?: 0,
            involvedDiscussionCount = data.discussionsInvolvingMe.discussionCount ?: 0,
        )
        viewerProfileCache.set(Unit, profile)
        profile
    }

    /** 查看别人（或用 login 查自己也可以）资料用这个，Follow 按钮要用到的 viewerCanFollow 等字段同一套 */
    suspend fun getUserProfile(login: String): ApiResult<ViewerProfile> = safeCall {
        val query = """
            query UserProfile(${'$'}login: String!) {
                user(login: ${'$'}login) {
                    $profileFields
                    gists(privacy: PUBLIC) { totalCount }
                }
            }
        """.trimIndent()
        val data = api.graphQL<UserProfileQueryData>(query, mapOf("login" to JsonPrimitive(login)))
        val node = data.user ?: throw UserVisibleException(R.string.error_user_not_found)
        node.toDomain()
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
    suspend fun followUser(userId: String): ApiResult<Unit> = safeCall {
        val mutation = """
            mutation FollowUser(${'$'}userId: ID!) {
                followUser(input: { userId: ${'$'}userId }) { clientMutationId }
            }
        """.trimIndent()
        with(api.graphQL<FollowUserMutationData>(mutation, mapOf("userId" to JsonPrimitive(userId)))) { }
        // 关注后 viewer.following.totalCount 可能变化，主动失效 profile 缓存，下次打开主页重新拉
        viewerProfileCache.invalidate(Unit)
    }

    /**
     * 取消关注用户
     *
     * 同 followUser，对齐官方 App 做法：clientMutationId 模式 + 客户端本地更新 UI
     *
     * @param userId 用户的 GraphQL 全局 ID
     */
    suspend fun unfollowUser(userId: String): ApiResult<Unit> = safeCall {
        val mutation = """
            mutation UnfollowUser(${'$'}userId: ID!) {
                unfollowUser(input: { userId: ${'$'}userId }) { clientMutationId }
            }
        """.trimIndent()
        with(api.graphQL<UnfollowUserMutationData>(mutation, mapOf("userId" to JsonPrimitive(userId)))) { }
        // 取消关注后 viewer.following.totalCount 可能变化，主动失效 profile 缓存
        viewerProfileCache.invalidate(Unit)
    }

    /**
     * 关注组织
     *
     * @param organizationId 组织的 GraphQL 全局 ID
     */
    suspend fun followOrganization(organizationId: String): ApiResult<Unit> = safeCall {
        val mutation = """
            mutation FollowOrganization(${'$'}organizationId: ID!) {
                followOrganization(input: { organizationId: ${'$'}organizationId }) {
                    clientMutationId
                }
            }
        """.trimIndent()
        with(
            api.graphQL<FollowOrganizationMutationData>(
                mutation,
                mapOf("organizationId" to JsonPrimitive(organizationId)),
            ),
        ) { }
    }

    /**
     * 取消关注组织
     *
     * @param organizationId 组织的 GraphQL 全局 ID
     */
    suspend fun unfollowOrganization(organizationId: String): ApiResult<Unit> = safeCall {
        val mutation = """
            mutation UnfollowOrganization(${'$'}organizationId: ID!) {
                unfollowOrganization(input: { organizationId: ${'$'}organizationId }) {
                    clientMutationId
                }
            }
        """.trimIndent()
        with(
            api.graphQL<UnfollowOrganizationMutationData>(
                mutation,
                mapOf("organizationId" to JsonPrimitive(organizationId)),
            ),
        ) { }
    }

    suspend fun getFollowers(login: String, after: String? = null): ApiResult<PagedUsers> = safeCall {
        val query = """
            query Followers(${'$'}login: String!, ${'$'}after: String) {
                user(login: ${'$'}login) {
                    followers(first: ${PageSize.USER_LIST}, after: ${'$'}after) {
                        totalCount
                        nodes { login name avatarUrl bio }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            }
        """.trimIndent()
        val data = api.graphQL<UserListQueryData>(
            query,
            buildMap {
                put("login", JsonPrimitive(login))
                after?.let { put("after", JsonPrimitive(it)) }
            },
        )
        val conn = data.user?.followers ?: throw UserVisibleException(R.string.error_user_not_found)
        PagedUsers(
            totalCount = conn.totalCount,
            users = conn.nodes.map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio) },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    suspend fun getFollowing(login: String, after: String? = null): ApiResult<PagedUsers> = safeCall {
        val query = """
            query Following(${'$'}login: String!, ${'$'}after: String) {
                user(login: ${'$'}login) {
                    following(first: ${PageSize.USER_LIST}, after: ${'$'}after) {
                        totalCount
                        nodes { login name avatarUrl bio }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            }
        """.trimIndent()
        val data = api.graphQL<UserListQueryData>(
            query,
            buildMap {
                put("login", JsonPrimitive(login))
                after?.let { put("after", JsonPrimitive(it)) }
            },
        )
        val conn = data.user?.following ?: throw UserVisibleException(R.string.error_user_not_found)
        PagedUsers(
            totalCount = conn.totalCount,
            users = conn.nodes.map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio) },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    /**
     * 统一的组织列表查询入口：自己的主页弹底部 OrganizationsBottomSheet + 他人个人主页弹底部 OrganizationsBottomSheet。
     *
     * BottomSheet 设计是轻量预览（不分页，最多 first:30 够了），不是独立分页列表页，
     * 因此只需要 nodes（login/name/avatarUrl）不需要 totalCount/pageInfo 分页结构。
     *
     * @param login `null` 时走 `viewer.organizations`（当前登录用户，HomeScreen 底部弹窗）；
     *              非空时走 `user(login:).organizations`（他人 ProfileScreen 底部弹窗）。
     */
    suspend fun getOrganizations(login: String? = null): ApiResult<List<SimpleOrg>> = safeCall {
        val query = if (login == null) {
            """
                query ViewerOrganizations {
                    viewer { organizations(first: ${PageSize.ORGS_PREVIEW}) { nodes { login name avatarUrl } } }
                }
            """.trimIndent()
        } else {
            """
                query UserOrganizations(${'$'}login: String!) {
                    user(login: ${'$'}login) { organizations(first: ${PageSize.ORGS_PREVIEW}) { nodes { login name avatarUrl } } }
                }
            """.trimIndent()
        }
        val variables = login?.let { mapOf("login" to JsonPrimitive(it)) } ?: emptyMap()

        // 两种模式共用 DTO：viewer/user 两个 nullable 根，实际查询到的那个非 null。
        val data = api.graphQL<UnifiedOrganizationsQueryData>(query, variables)
        val nodes = data.viewer?.organizations?.nodes
            ?: data.user?.organizations?.nodes
            ?: emptyList()
        nodes.map { SimpleOrg(it.login, it.name, it.avatarUrl) }
    }

    /**
     * 用户/组织统一资料查询——只有一个 login 时无法预先判断类型，
     * 用 repositoryOwner(login:) + __typename 分流，一次查询搞定两种情况。
     */
    suspend fun getProfileOwner(login: String): ApiResult<ProfileOwner> = safeCall {
        val query = """
            query ProfileOwner(${'$'}login: String!) {
                viewer {
                    login
                    gists(privacy: ALL) { totalCount }
                }
                repositoryOwner(login: ${'$'}login) {
                    __typename
                    id
                    login
                    avatarUrl
                    url
                    ... on User {
                        name
                        bio
                        company
                        location
                        email
                        pronouns
                        status { emoji message }
                        socialAccounts(first: ${PageSize.SOCIAL_ACCOUNTS}) {
                            nodes { displayName provider url }
                        }
                        # 徽章：公开 Schema 真实存在的 4 个 is* 布尔字段
                        isDeveloperProgramMember
                        isBountyHunter
                        isCampusExpert
                        isGitHubStar
                        isViewer
                        viewerCanFollow
                        viewerIsFollowing
                        followers { totalCount }
                        following { totalCount }
                        organizations { totalCount }
                        starredRepositories { totalCount }
                        gists(privacy: PUBLIC) { totalCount }
                        websiteUrl
                        repositories(ownerAffiliations: [OWNER]) { totalCount }
                        $pinnedRepositoryFields
                    }
                    ... on Organization {
                        name
                        description
                        isVerified
                        websiteUrl
                        membersWithRole { totalCount }
                        repositories(ownerAffiliations: [OWNER]) { totalCount }
                        viewerIsFollowing
                        $pinnedRepositoryFields
                    }
                }
            }
        """.trimIndent()
        val data = api.graphQL<RepositoryOwnerQueryData>(query, mapOf("login" to JsonPrimitive(login)))
        val node = data.repositoryOwner ?: throw UserVisibleException(R.string.error_owner_not_found)
        when (node.__typename) {
            "User" -> ProfileOwner.Person(
                id = node.id,
                login = node.login,
                avatarUrl = node.avatarUrl,
                name = node.name,
                repoCount = node.repositories?.totalCount ?: 0,
                websiteUrl = node.websiteUrl,
                bio = node.bio,
                email = node.email,
                company = node.company,
                location = node.location,
                pronouns = node.pronouns,
                status = node.status?.let { UserStatus(it.emoji, it.message) },
                socialAccounts = node.socialAccounts?.nodes?.map {
                    SocialAccount(it.displayName, it.provider, it.url)
                }.orEmpty(),
                isViewer = node.isViewer,
                followState = FollowState(node.isViewer, node.viewerCanFollow, node.viewerIsFollowing),
                followersCount = node.followers?.totalCount ?: 0,
                followingCount = node.following?.totalCount ?: 0,
                organizationsCount = node.organizations?.totalCount ?: 0,
                starredCount = node.starredRepositories?.totalCount ?: 0,
                gistCount = if (node.isViewer) {
                    data.viewer?.gists?.totalCount ?: node.gists?.totalCount ?: 0
                } else {
                    node.gists?.totalCount ?: 0
                },
                isDeveloperProgramMember = node.isDeveloperProgramMember,
                isBountyHunter = node.isBountyHunter,
                isCampusExpert = node.isCampusExpert,
                isGitHubStar = node.isGitHubStar,
                pinnedRepos = node.pinnedItems.toPinnedRepos(),
            )
            "Organization" -> ProfileOwner.Org(
                id = node.id,
                login = node.login,
                avatarUrl = node.avatarUrl,
                name = node.name,
                repoCount = node.repositories?.totalCount ?: 0,
                websiteUrl = node.websiteUrl,
                description = node.description,
                isVerified = node.isVerified,
                membersCount = node.membersWithRole?.totalCount ?: 0,
                viewerIsFollowing = node.viewerIsFollowing,
                pinnedRepos = node.pinnedItems.toPinnedRepos(),
            )
            else -> throw IllegalStateException("未知的 owner 类型: ${node.__typename}")
        }
    }

    private fun ProfileNode.toDomain(): ViewerProfile = ViewerProfile(
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
            socialAccounts = socialAccounts.nodes.map { SocialAccount(it.displayName, it.provider, it.url) },
        ),
        repoCount = repositories.totalCount,
        starredCount = starredRepositories.totalCount,
        gistCount = gists?.totalCount ?: 0,
        pinnedRepos = pinnedItems.toPinnedRepos(),
        followState = FollowState(
            isViewer = isViewer,
            viewerCanFollow = viewerCanFollow,
            viewerIsFollowing = viewerIsFollowing,
        ),
    )

    private fun PinnedItemsConnection?.toPinnedRepos(): List<PinnedRepo> = this?.nodes.orEmpty().map {
        PinnedRepo(
            name = it.name,
            url = it.url,
            description = it.description,
            stargazerCount = it.stargazerCount,
            forkCount = it.forkCount,
            languageName = it.primaryLanguage?.name,
            languageColor = it.primaryLanguage?.color,
            ownerLogin = it.owner.login,
            ownerAvatarUrl = it.owner.avatarUrl,
        )
    }

    // ================================
    // 他人主页统计行跳转：4 个列表查询
    // ================================

    /**
     * 指定用户的仓库列表（他人主页 → 点击"仓库"统计项）。
     * 与 RepoRepository.getViewerRepos() 的区别：根字段是 user(login:) 不是 viewer，
     * 可以查任意 login 的公开仓库；ownerAffiliations 只传 OWNER（组织仓库不属于个人名下）。
     */
    suspend fun getUserRepos(login: String, after: String? = null): ApiResult<RepoList> = safeCall {
        val query = """
            query UserRepos(${'$'}login: String!, ${'$'}after: String) {
                user(login: ${'$'}login) {
                    repositories(first: ${PageSize.REPOS}, after: ${'$'}after, ownerAffiliations: [OWNER]) {
                        totalCount
                        nodes {
                            name
                            owner { login avatarUrl }
                            description
                            homepageUrl
                            isPrivate
                            isArchived
                            isFork
                            parent { name owner { login avatarUrl } }
                            primaryLanguage { name color }
                            stargazerCount
                            forkCount
                            issues(states: [OPEN]) { totalCount }
                            repositoryTopics(first: 10) { nodes { topic { name } } } # TOPICS_PER_REPO
                            defaultBranchRef { name }
                        }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            }
        """.trimIndent()
        val data = api.graphQL<UserRepoListQueryData>(
            query,
            buildMap {
                put("login", JsonPrimitive(login))
                after?.let { put("after", JsonPrimitive(it)) }
            },
        )
        val conn = data.user?.repositories ?: throw UserVisibleException(R.string.error_user_not_found)
        RepoList(
            totalCount = conn.totalCount,
            items = conn.nodes.map { it.toDomain() },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    /**
     * 指定用户的星标仓库列表（他人主页 → 点击"星标"统计项）。
     * 与 StarRepository.getViewerStarred() 的区别：根字段是 user(login:) 不是 viewer，
     * 可以查任意 login 的公开星标仓库；orderBy 走 STARRED_AT DESC 与 GitHub 官方一致。
     */
    suspend fun getUserStars(login: String, after: String? = null): ApiResult<PagedStarredRepos> = safeCall {
        val query = """
            query UserStars(${'$'}login: String!, ${'$'}after: String) {
                user(login: ${'$'}login) {
                    starredRepositories(first: ${PageSize.STARRED_REPOS}, after: ${'$'}after, orderBy: { field: STARRED_AT, direction: DESC }) {
                        totalCount
                        edges {
                            node {
                                id
                                name
                                url
                                description
                                homepageUrl
                                owner { login avatarUrl }
                                isPrivate
                                isArchived
                                isFork
                                parent { name owner { login avatarUrl } }
                                primaryLanguage { name color }
                                stargazerCount
                                forkCount
                                issues(states: [OPEN]) { totalCount }
                                repositoryTopics(first: 10) { nodes { topic { name } } } # TOPICS_PER_REPO
                                defaultBranchRef { name }
                            }
                        }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            }
        """.trimIndent()
        val data = api.graphQL<UserStarsQueryData>(
            query,
            buildMap {
                put("login", JsonPrimitive(login))
                after?.let { put("after", JsonPrimitive(it)) }
            },
        )
        val conn = data.user?.starredRepositories ?: throw UserVisibleException(R.string.error_user_not_found)
        PagedStarredRepos(
            totalCount = conn.totalCount,
            items = conn.edges.map { it.node.toDomain() },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    /**
     * 指定用户的组织列表（他人主页 → 点击"组织"统计项）。
     * 与 getOrganizations() 的区别：根字段是 user(login:) 不是 viewer，
     * 带 totalCount + pageInfo 分页，支持查任意 login 的公开组织。
     */
    suspend fun getUserOrgs(login: String, after: String? = null): ApiResult<PagedOrgs> = safeCall {
        val query = """
            query UserOrgs(${'$'}login: String!, ${'$'}after: String) {
                user(login: ${'$'}login) {
                    organizations(first: ${PageSize.ORGS}, after: ${'$'}after) {
                        totalCount
                        nodes { login name avatarUrl }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            }
        """.trimIndent()
        val data = api.graphQL<UserOrgsQueryData>(
            query,
            buildMap {
                put("login", JsonPrimitive(login))
                after?.let { put("after", JsonPrimitive(it)) }
            },
        )
        val conn = data.user?.organizations ?: throw UserVisibleException(R.string.error_user_not_found)
        PagedOrgs(
            totalCount = conn.totalCount,
            orgs = conn.nodes.map { SimpleOrg(it.login, it.name, it.avatarUrl) },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    /**
     * 指定组织的成员列表（组织主页 → 点击"成员"统计项）。
     * 通过 organization(login:) 根字段查 membersWithRole，返回 User 节点列表；
     * 数据形状与关注者/关注列表一致，因此复用 PagedUsers/SimpleUser。
     */
    suspend fun getOrgMembers(orgLogin: String, after: String? = null): ApiResult<PagedUsers> = safeCall {
        val query = """
            query OrgMembers(${'$'}orgLogin: String!, ${'$'}after: String) {
                organization(login: ${'$'}orgLogin) {
                    membersWithRole(first: ${PageSize.USER_LIST}, after: ${'$'}after) {
                        totalCount
                        nodes { login name avatarUrl bio }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            }
        """.trimIndent()
        val data = api.graphQL<OrgMembersQueryData>(
            query,
            buildMap {
                put("orgLogin", JsonPrimitive(orgLogin))
                after?.let { put("after", JsonPrimitive(it)) }
            },
        )
        val conn = data.organization?.membersWithRole ?: throw UserVisibleException(R.string.error_org_not_found)
        PagedUsers(
            totalCount = conn.totalCount,
            users = conn.nodes.map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio) },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }
}
