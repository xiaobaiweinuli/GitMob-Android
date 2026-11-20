package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.HttpStatusException
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.GraphQLErrorMapper
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.IssueCreationPolicy
import com.gitmob.app.data.model.PullRequestCreationPolicy
import com.gitmob.app.data.model.PagedBranches
import com.gitmob.app.data.model.PagedUsers
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepoForkSort
import com.gitmob.app.data.model.RepoForkSortDirection
import com.gitmob.app.data.model.RepoForkSortField
import com.gitmob.app.data.model.RepoBranch
import com.gitmob.app.data.model.BranchCreationSpec
import com.gitmob.app.data.model.RepoDetail
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.model.RepoReadme
import com.gitmob.app.data.model.SimpleUser
import com.gitmob.app.data.model.CreatedRepository
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerType
import com.gitmob.app.data.model.RepositorySubscription
import com.gitmob.app.data.model.RepositoryTransferTarget
import com.gitmob.app.data.model.isValidRepositoryName
import com.gitmob.app.data.model.isValidRepositoryWebsite
import com.gitmob.app.data.model.areValidRepositoryTopics
import com.gitmob.app.graphql.ArchiveRepoMutation
import com.gitmob.app.graphql.UnarchiveRepoMutation
import com.gitmob.app.graphql.UpdateRepoSettingsMutation
import com.gitmob.app.graphql.UpdateRepoTopicsMutation
import com.gitmob.app.graphql.UpdateRepoSubscriptionMutation
import com.gitmob.app.graphql.RepoManagementTopicsQuery
import com.gitmob.app.graphql.RepoManagementIdentityQuery
import com.gitmob.app.data.model.RepositoryFork
import com.gitmob.app.data.model.RepositoryForkCheck
import com.gitmob.app.graphql.RepoForkIdentityQuery
import com.gitmob.app.graphql.RepoForkTargetQuery
import com.gitmob.app.graphql.fragment.ForkIdentity
import com.gitmob.app.graphql.RepoTransferTargetQuery
import com.gitmob.app.graphql.type.OrderDirection
import com.gitmob.app.graphql.type.RepositoryOrder
import com.gitmob.app.graphql.type.RepositoryOrderField
import com.gitmob.app.graphql.type.UpdateRepositoryInput
import com.gitmob.app.graphql.type.UpdateTopicsInput
import com.gitmob.app.graphql.type.UpdateSubscriptionInput
import com.gitmob.app.graphql.type.SubscriptionState
import com.gitmob.app.graphql.DeleteBranchMutation
import com.gitmob.app.graphql.RepoBranchesQuery
import com.gitmob.app.graphql.RepoDetailQuery
import com.gitmob.app.graphql.RepoWatchersQuery
import com.gitmob.app.graphql.RepoStargazersQuery
import com.gitmob.app.graphql.RepoForksQuery
import java.util.Base64
import java.nio.ByteBuffer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoDetailRepository @Inject constructor(
    private val api: GHApiClient,
    private val apolloClient: ApolloClient,
) {

    // ── 缓存实例 ──────────────────────────────────────────────
    /** 仓库分支列表（第一页 after=null），Key = "$owner/$name"，TTL 10 min */
    private val branchesCache = MemoryCache<String, PagedBranches>(ttlMs = 10 * 60_000L)

    /**
     * 公开：登出时由 AuthRepository 统一调用，清空当前 Repository 的全部内存缓存。
     */
    fun invalidateAllCaches() {
        branchesCache.invalidateAll()
    }
    suspend fun getRepoDetail(owner: String, name: String): ApiResult<RepoDetail> =
        when (val result = apolloClient.query(
            RepoDetailQuery(owner, name, PageSize.TOPICS_PER_REPO),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val node = result.data.repository ?: throw UserVisibleException(R.string.error_repo_not_found)
                val permission = RepoIssueRepository.parsePermission(node.viewerPermission?.rawValue)
                val latestRelease = node.releases.nodes.orEmpty().filterNotNull().firstOrNull()

                RepoDetail(
                    id = node.id,
                    name = node.name,
                    ownerLogin = node.owner.login,
                    ownerAvatarUrl = node.owner.avatarUrl,
                    description = node.description,
                    homepageUrl = node.homepageUrl,
                    isPrivate = node.isPrivate,
                    isArchived = node.isArchived,
                    isTemplate = node.isTemplate,
                    isFork = node.isFork,
                    forkedFromOwner = node.parent?.owner?.login,
                    forkedFromName = node.parent?.name,
                    stargazerCount = node.stargazerCount,
                    viewerHasStarred = node.viewerHasStarred,
                    forkCount = node.forkCount,
                    openIssueCount = node.issues.totalCount,
                    openPrCount = node.pullRequests.totalCount,
                    watcherCount = node.watchers.totalCount,
                    viewerSubscription = node.viewerSubscription?.rawValue ?: "UNSUBSCRIBED",
                    licenseName = node.licenseInfo?.name,
                    licenseSpdxId = node.licenseInfo?.spdxId,
                    branchCount = node.refs?.totalCount ?: 0,
                    defaultBranchName = node.defaultBranchRef?.name,
                    releaseCount = node.releases.totalCount,
                    latestReleaseName = latestRelease?.name,
                    latestReleaseTag = latestRelease?.tagName,
                    languageName = node.primaryLanguage?.name,
                    languageColor = node.primaryLanguage?.color,
                    topics = node.repositoryTopics.nodes.orEmpty().filterNotNull().map { it.topic.name },
                    permission = permission,
                    viewerCanCreateIssues = node.viewerCanCreateIssues,
                    hasIssuesEnabled = node.hasIssuesEnabled,
                    isBlankIssuesEnabled = node.isBlankIssuesEnabled,
                    issueCreationPolicy = node.issueCreationPolicy?.let {
                        runCatching { IssueCreationPolicy.valueOf(it.rawValue) }.getOrDefault(IssueCreationPolicy.UNKNOWN)
                    } ?: IssueCreationPolicy.UNKNOWN,
                    hasPullRequestsEnabled = node.hasPullRequestsEnabled,
                    pullRequestCreationPolicy = node.pullRequestCreationPolicy?.let {
                        runCatching { PullRequestCreationPolicy.valueOf(it.rawValue) }
                            .getOrDefault(PullRequestCreationPolicy.UNKNOWN)
                    } ?: PullRequestCreationPolicy.UNKNOWN,
                    hasDiscussionsEnabled = node.hasDiscussionsEnabled,
                    openDiscussionCount = node.discussions.totalCount,
                    capabilities = permission.toCapabilities(node.isArchived),
                    url = node.url,
                    visibility = node.visibility.rawValue,
                    ownerIsOrganization = node.owner.onOrganization != null,
                    viewerCanSubscribe = node.viewerCanSubscribe,
                    ownerIsViewer = node.owner.onUser?.isViewer == true,
                    forkingAllowed = node.forkingAllowed,
                    ownerId = node.owner.id,
                    viewerId = result.data.viewer.id,
                    viewerLogin = result.data.viewer.login,
                    viewerFork = node.viewerForks.nodes.orEmpty().filterNotNull().firstOrNull()?.forkIdentity?.toFork(),
                )
            }
        }

    /** GitHub selects the preferred README and returns its actual path with the content. */
    suspend fun getReadme(owner: String, name: String, ref: String): ApiResult<RepoReadme?> = safeCall {
        val response = try {
            api.get<ReadmeResponse>(
                "/repos/${encodePathSegment(owner)}/${encodePathSegment(name)}/readme?ref=${encodePathSegment(ref)}",
                accept = "application/vnd.github+json",
            )
        } catch (error: HttpStatusException) {
            if (error.code == 404) return@safeCall null
            throw error
        }
        if (response.type != "file" || response.path.isNullOrBlank() || response.encoding != "base64" ||
            response.content == null || response.size == null || response.size < 0
        ) throw UserVisibleException(R.string.repo_readme_invalid)
        val path = response.path
        if (path.startsWith('/') || path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw UserVisibleException(R.string.repo_readme_invalid)
        }
        val fileName = path.substringAfterLast('/').lowercase()
        if (fileName != "readme" && fileName.substringAfterLast('.', "") !in
            setOf("md", "markdown", "mdown", "mkd", "mkdn", "mdwn", "mkdown")
        ) throw UserVisibleException(R.string.repo_readme_unsupported)
        val markdown = withContext(Dispatchers.Default) {
            val text = try {
                val bytes = Base64.getDecoder().decode(response.content.filterNot { it in " \t\r\n" })
                if (bytes.size.toLong() != response.size) throw IllegalArgumentException("Incomplete README")
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: IllegalArgumentException) {
                throw UserVisibleException(R.string.repo_readme_invalid)
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw UserVisibleException(R.string.repo_readme_invalid)
            }
            if ('\u0000' in text) throw UserVisibleException(R.string.repo_readme_unsupported)
            text
        }
        if (markdown.isBlank()) return@safeCall null
        RepoReadme(markdown, RepoDocumentContext(owner, name, ref, path), isTruncated = false)
    }

    /**
     * 仓库分支列表。
     * 仅对「第一页（after=null）」启用缓存（TTL 10 min）；
     * 第二页及以后不走缓存（分页游标随时间变化，缓存无意义）。
     * 删除/设默认分支后主动失效缓存。
     */
    suspend fun getBranches(owner: String, name: String, after: String? = null): ApiResult<PagedBranches> {
        val cacheKey = "$owner/$name"
        if (after == null) {
            branchesCache.get(cacheKey)?.let { return ApiResult.Success(it) }
            val result = getBranchesInternal(owner, name, null)
            if (result is ApiResult.Success) branchesCache.set(cacheKey, result.data)
            return result
        }
        return getBranchesInternal(owner, name, after)
    }

    /**
     * 下拉刷新专用：强制重新拉取分支第一页，不走缓存。
     */
    suspend fun getBranchesFresh(owner: String, name: String): ApiResult<PagedBranches> {
        val cacheKey = "$owner/$name"
        val result = getBranchesInternal(owner, name, null)
        if (result is ApiResult.Success) branchesCache.set(cacheKey, result.data)
        return result
    }

    private suspend fun getBranchesInternal(owner: String, name: String, after: String?): ApiResult<PagedBranches> =
        when (val result = apolloClient.query(
            RepoBranchesQuery(owner, name, Optional.presentIfNotNull(after), PageSize.BRANCHES),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val data = result.data.repository
                    ?: throw UserVisibleException(R.string.error_repo_not_found)
                val defaultName = data.defaultBranchRef?.name
                PagedBranches(
                    items = data.refs?.nodes.orEmpty().filterNotNull().map {
                        RepoBranch(id = it.id, name = it.name, isDefault = it.name == defaultName, commitOid = it.target?.oid)
                    },
                    hasNextPage = data.refs?.pageInfo?.hasNextPage ?: false,
                    endCursor = data.refs?.pageInfo?.endCursor,
                )
            }
        }

    /** 仓库关注者（Watchers），复用和 followers/following 一样的 PagedUsers 形状 */
    suspend fun getWatchers(owner: String, name: String, after: String? = null): ApiResult<PagedUsers> =
        when (val result = apolloClient.query(
            RepoWatchersQuery(owner, name, Optional.presentIfNotNull(after), PageSize.USER_LIST),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val conn = result.data.repository?.watchers
                    ?: throw UserVisibleException(R.string.error_repo_not_found)
                PagedUsers(
                    totalCount = conn.totalCount,
                    users = conn.nodes.orEmpty().filterNotNull().map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio) },
                    hasNextPage = conn.pageInfo.hasNextPage,
                    endCursor = conn.pageInfo.endCursor,
                )
            }
        }

    suspend fun getStargazers(owner: String, name: String, after: String? = null): ApiResult<PagedUsers> =
        when (val result = apolloClient.query(
            RepoStargazersQuery(owner, name, Optional.presentIfNotNull(after), PageSize.USER_LIST),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val repository = result.data.repository
                    ?: throw UserVisibleException(R.string.error_repo_not_found)
                val connection = repository.stargazers
                val users = connection.nodes.orEmpty().filterNotNull().map {
                    SimpleUser(it.login, it.name, it.avatarUrl, it.bio)
                }
                if (after == null && repository.stargazerCount > 0 && users.isEmpty()) {
                    throw UserVisibleException(R.string.repo_stargazers_unavailable)
                }
                PagedUsers(
                    totalCount = repository.stargazerCount,
                    users = users,
                    hasNextPage = connection.pageInfo.hasNextPage,
                    endCursor = connection.pageInfo.endCursor,
                )
            }
        }

    suspend fun getForks(
        owner: String,
        name: String,
        after: String? = null,
        sort: RepoForkSort = RepoForkSort(),
    ): ApiResult<RepoList> =
        when (val result = apolloClient.query(
            RepoForksQuery(
                owner,
                name,
                Optional.presentIfNotNull(after),
                PageSize.REPOS,
                PageSize.TOPICS_PER_REPO,
                orderBy = RepositoryOrder(
                    field = when (sort.field) {
                        RepoForkSortField.CREATED -> RepositoryOrderField.CREATED_AT
                        RepoForkSortField.STARS -> RepositoryOrderField.STARGAZERS
                        RepoForkSortField.NAME -> RepositoryOrderField.NAME
                        RepoForkSortField.UPDATED -> RepositoryOrderField.UPDATED_AT
                        RepoForkSortField.PUSHED -> RepositoryOrderField.PUSHED_AT
                    },
                    direction = when (sort.direction) {
                        RepoForkSortDirection.ASC -> OrderDirection.ASC
                        RepoForkSortDirection.DESC -> OrderDirection.DESC
                    },
                ),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val connection = result.data.repository?.forks
                    ?: throw UserVisibleException(R.string.error_repo_not_found)
                RepoList(
                    totalCount = connection.totalCount,
                    items = connection.nodes.orEmpty().filterNotNull().map { it.repoListFields.toDomain() },
                    hasNextPage = connection.pageInfo.hasNextPage,
                    endCursor = connection.pageInfo.endCursor,
                )
            }
        }

    /** A negative result is returned only after every visible owned fork page was checked. */
    suspend fun checkForkTarget(
        source: RepoDetail,
        target: RepositoryCreateOwner,
        requestedName: String,
    ): ApiResult<RepositoryForkCheck> {
        if (target.id == source.ownerId || target.login.equals(source.ownerLogin, true)) {
            return ApiResult.Failure(com.gitmob.app.core.error.ApiError.UserVisible(R.string.repo_transfer_same_owner))
        }
        val root = when (val result = safeCall { readForkRepository(source.ownerLogin, source.name) }) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data
        }
        if (root.nodeId != source.id) return forkCheckFailure()
        val networkId = root.networkId ?: return forkCheckFailure()
        val networkNodeId = root.source?.nodeId ?: root.nodeId
        suspend fun matches(identity: ForkIdentity, isFork: Boolean, parentId: String?, parentIsFork: Boolean?): ApiResult<Boolean> {
            if (!isFork || identity.owner.id != target.id) return ApiResult.Success(false)
            if (parentId == source.id || parentId == networkNodeId) return ApiResult.Success(true)
            if (parentIsFork == false) return ApiResult.Success(false)
            return safeCall {
                val candidate = readForkRepository(identity.owner.login, identity.name)
                if (candidate.nodeId != identity.id || candidate.networkId == null) {
                    throw UserVisibleException(R.string.repo_fork_check_failed)
                }
                candidate.fork && candidate.networkId == networkId
            }
        }
        var after: String? = null
        val cursors = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        var conflict = false
        while (true) {
            val result = try {
                val epoch = AccountContext.ensureCurrent()
                val response = apolloClient.query(RepoForkTargetQuery(target.login, requestedName, Optional.presentIfNotNull(after), PageSize.REPOS)).execute()
                epoch.ensureCurrent()
                val account = response.data?.repositoryOwner
                val expectedMissing = account?.id == target.id && account.named == null &&
                    response.errors.orEmpty().all { it.path == listOf("repositoryOwner", "named") && GraphQLErrorMapper.errorType(it) == "NOT_FOUND" }
                GraphQLErrorMapper.toApiResult(response.data, if (expectedMissing) null else response.errors, response.exception)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return ApiResult.Failure(GraphQLErrorMapper.toApiError(error))
            }
            when (result) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> {
                    val account = result.data.repositoryOwner ?: return forkCheckFailure()
                    if (account.id != target.id || (account.onUser?.isViewer != true && account.onOrganization == null)) return forkCheckFailure()
                    // Existing forks remain openable even when the organization no longer permits creation.
                    account.named?.let { candidate ->
                        when (val match = matches(candidate.forkIdentity, candidate.isFork, candidate.parent?.id, candidate.parent?.isFork)) {
                            is ApiResult.Failure -> return match
                            is ApiResult.Success -> if (match.data) return ApiResult.Success(RepositoryForkCheck(candidate.forkIdentity.toFork(), false))
                        }
                        conflict = true
                    }
                    val connection = account.repositories
                    val nodes = connection.nodes ?: return forkCheckFailure()
                    if (nodes.any { it == null }) return forkCheckFailure()
                    for (candidate in nodes.filterNotNull()) {
                        if (!seen.add(candidate.forkIdentity.id)) continue
                        when (val match = matches(candidate.forkIdentity, candidate.isFork, candidate.parent?.id, candidate.parent?.isFork)) {
                            is ApiResult.Failure -> return match
                            is ApiResult.Success -> if (match.data) return ApiResult.Success(RepositoryForkCheck(candidate.forkIdentity.toFork(), false))
                        }
                    }
                    if (!connection.pageInfo.hasNextPage) {
                        if (seen.size != connection.totalCount) return forkCheckFailure()
                        if (account.onOrganization?.viewerCanCreateRepositories == false) {
                            return ApiResult.Failure(com.gitmob.app.core.error.ApiError.Forbidden)
                        }
                        return ApiResult.Success(RepositoryForkCheck(null, conflict))
                    }
                    val next = connection.pageInfo.endCursor
                    if (next.isNullOrBlank() || !cursors.add(next)) return forkCheckFailure()
                    after = next
                }
            }
        }
    }

    suspend fun createFork(source: RepoDetail, target: RepositoryCreateOwner, requestedName: String): ApiResult<RepositoryFork> = safeCall {
        if (!isValidRepositoryName(requestedName)) throw UserVisibleException(R.string.repo_manage_invalid_name)
        require(target.canCreateRepository && !target.login.equals(source.ownerLogin, true))
        val response = try {
            api.post<RestForkRepository, CreateForkBody>(
                "/repos/${encodePathSegment(source.ownerLogin)}/${encodePathSegment(source.name)}/forks",
                CreateForkBody(organization = target.login.takeIf { target.type == RepositoryCreateOwnerType.ORGANIZATION }, name = requestedName),
            )
        } catch (error: HttpStatusException) {
            if (error.code == 404 || error.code == 422) throw UserVisibleException(R.string.repo_fork_rejected)
            throw error
        }
        if (!response.fork || response.owner.nodeId != target.id) throw UserVisibleException(R.string.repo_fork_check_failed)
        response.toFork()
    }

    /** A 404 here means the accepted repository is not readable yet, never a new POST. */
    suspend fun confirmFork(source: RepoDetail, target: RepositoryCreateOwner, accepted: RepositoryFork): ApiResult<RepositoryFork?> {
        val verified = safeCall {
            val actual = try { readForkRepository(accepted.owner, accepted.name) } catch (error: HttpStatusException) {
                if (error.code == 404) return@safeCall null
                throw error
            }
            val upstream = readForkRepository(source.ownerLogin, source.name)
            if (upstream.nodeId != source.id || actual.nodeId != accepted.id || actual.owner.nodeId != target.id ||
                !actual.fork || actual.networkId == null || actual.networkId != upstream.networkId
            ) throw UserVisibleException(R.string.repo_fork_check_failed)
            actual
        }
        val actual = when (verified) {
            is ApiResult.Failure -> return verified
            is ApiResult.Success -> verified.data ?: return ApiResult.Success(null)
        }
        // Confirm the repository node identity through GraphQL.
        return when (val result = apolloClient.query(RepoForkIdentityQuery(actual.nodeId)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val identity = result.data.node?.onRepository?.forkIdentity
                if (identity == null || identity.id != actual.nodeId || identity.owner.id != target.id) forkCheckFailure()
                else ApiResult.Success(identity.toFork())
            }
        }
    }

    private suspend fun readForkRepository(owner: String, name: String): RestForkRepository = api.get(
        "/repos/${encodePathSegment(owner)}/${encodePathSegment(name)}",
    )

    private fun forkCheckFailure() = ApiResult.Failure(com.gitmob.app.core.error.ApiError.UserVisible(R.string.repo_fork_check_failed))

    /**
     * 删除分支：DeleteRefInput 只需要 refId（Ref 节点自己的 id，不是分支名），已用 introspection 核实。
     * 删除后主动失效该仓库的分支缓存。
     *
     * @param refId   要删除的 Ref 节点的 GraphQL 全局 id
     * @param owner   仓库 owner login（用于失效对应缓存 key）
     * @param name    仓库名（用于失效对应缓存 key）
     */
    suspend fun deleteBranch(refId: String, owner: String, name: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(DeleteBranchMutation(refId)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                // 删除分支后，分支列表顺序、默认分支高亮都会变化，失效缓存
                branchesCache.invalidate("$owner/$name")
            }
        }

    /**
     * 设默认分支——公开 GraphQL API 没有对应能力（UpdateRepositoryInput 没有 defaultBranch
     * 字段，已用 introspection 核实过，之前"大概率走 updateRepository"的猜测是错的），
     * 只能走 REST：PATCH /repos/{owner}/{repo}，body 带 default_branch 字段。
     * 见 references/api-verification.md 的方法论修正。
     * 设置后主动失效该仓库的分支缓存（默认分支高亮会变）。
     */
    suspend fun setDefaultBranch(owner: String, name: String, branchName: String): ApiResult<Unit> = safeCall {
        // 这个 REST 接口成功时会返回完整的仓库 JSON（不是空响应），
        // 用 Unit 直接接会导致 kotlinx.serialization 解码失败，
        // 用一个只取需要字段的最小 DTO 接住、丢弃其余内容即可。
        with(api.patch<SetDefaultBranchResponse, SetDefaultBranchBody>(
            path = "/repos/$owner/$name",
            body = SetDefaultBranchBody(defaultBranch = branchName),
        )) { }
        // 设默认分支后，isDefault 标记会变化，失效缓存
        branchesCache.invalidate("$owner/$name")
    }

    /** Create a new branch at the source branch's current head commit. */
    suspend fun createBranch(
        owner: String,
        name: String,
        newBranchName: String,
        spec: BranchCreationSpec,
    ): ApiResult<Unit> = safeCall {
        when (spec) {
            is BranchCreationSpec.FromExisting -> {
                val sourceName = spec.sourceBranch
                val source = api.get<RestGitRefResponse>(
                    "/repos/$owner/$name/git/ref/heads/${encodePathSegment(sourceName)}",
                )
                val sourceSha = source.objectInfo?.sha
                    ?: throw IllegalStateException("Source branch commit is unavailable")
                api.post<RestGitRefResponse, CreateRefBody>(
                    path = "/repos/$owner/$name/git/refs",
                    body = CreateRefBody(ref = "refs/heads/$newBranchName", sha = sourceSha),
                )
            }
            BranchCreationSpec.Empty -> {
                val tree = api.post<RestGitTreeResponse, CreateTreeBody>(
                    path = "/repos/$owner/$name/git/trees",
                    body = CreateTreeBody(tree = emptyList()),
                )
                val treeSha = tree.sha ?: throw IllegalStateException("Empty tree was not created")
                val commit = api.post<RestGitCommitResponse, CreateCommitBody>(
                    path = "/repos/$owner/$name/git/commits",
                    body = CreateCommitBody(
                        message = "Create empty branch",
                        tree = treeSha,
                        parents = emptyList(),
                    ),
                )
                val commitSha = commit.sha ?: throw IllegalStateException("Root commit was not created")
                api.post<RestGitRefResponse, CreateRefBody>(
                    path = "/repos/$owner/$name/git/refs",
                    body = CreateRefBody(ref = "refs/heads/$newBranchName", sha = commitSha),
                )
            }
        }
        branchesCache.invalidate("$owner/$name")
    }

    /** Rename a branch through the REST endpoint verified in the bundled OpenAPI schema. */
    suspend fun renameBranch(
        owner: String,
        name: String,
        branchName: String,
        newBranchName: String,
    ): ApiResult<Unit> = safeCall {
        with(api.post<RestBranchResponse, RenameBranchBody>(
            path = "/repos/$owner/$name/branches/${encodePathSegment(branchName)}/rename",
            body = RenameBranchBody(newName = newBranchName),
        )) { }
        branchesCache.invalidate("$owner/$name")
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")

    suspend fun getManagementIdentity(id: String): ApiResult<CreatedRepository> =
        when (val result = apolloClient.query(RepoManagementIdentityQuery(id)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val repository = result.data.node?.onRepository
                    ?: throw UserVisibleException(R.string.error_repo_not_found)
                CreatedRepository(repository.owner.login, repository.name)
            }
        }

    suspend fun getAllTopics(owner: String, name: String): ApiResult<List<String>> {
        val topics = mutableListOf<String>()
        var after: String? = null
        do {
            when (val result = apolloClient.query(
                RepoManagementTopicsQuery(owner, name, Optional.presentIfNotNull(after), PageSize.TOPICS_PER_REPO),
            ).executeAsApiResult()) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> {
                    val connection = result.data.repository?.repositoryTopics
                        ?: return safeCall { throw UserVisibleException(R.string.error_repo_not_found) }
                    topics += connection.nodes.orEmpty().filterNotNull().map { it.topic.name }
                    if (!connection.pageInfo.hasNextPage) break
                    val next = connection.pageInfo.endCursor
                    if (next == null || next == after) return safeCall { error("Topics cursor is missing or unchanged") }
                    after = next
                }
            }
        } while (true)
        return ApiResult.Success(topics.distinct())
    }

    suspend fun updateSettings(
        id: String,
        newName: String? = null,
        description: String? = null,
        homepageUrl: String? = null,
        hasIssuesEnabled: Boolean? = null,
        hasDiscussionsEnabled: Boolean? = null,
    ): ApiResult<CreatedRepository> {
        if (newName != null && !isValidRepositoryName(newName)) return safeCall { throw UserVisibleException(R.string.repo_manage_invalid_name) }
        if (homepageUrl != null && !isValidRepositoryWebsite(homepageUrl)) return safeCall { throw UserVisibleException(R.string.repo_manage_invalid_website) }
        val input = UpdateRepositoryInput(
            repositoryId = id,
            name = Optional.presentIfNotNull(newName),
            description = Optional.presentIfNotNull(description),
            homepageUrl = Optional.presentIfNotNull(homepageUrl),
            hasIssuesEnabled = Optional.presentIfNotNull(hasIssuesEnabled),
            hasDiscussionsEnabled = Optional.presentIfNotNull(hasDiscussionsEnabled),
        )
        return when (val result = apolloClient.mutation(UpdateRepoSettingsMutation(input)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val repository = result.data.updateRepository?.repository ?: error("Repository update payload is missing")
                CreatedRepository(repository.owner.login, repository.name)
            }
        }
    }

    suspend fun updateTopics(id: String, topics: List<String>): ApiResult<List<String>> {
        if (!areValidRepositoryTopics(topics)) return safeCall { throw UserVisibleException(R.string.repo_manage_invalid_topics) }
        return when (val result = apolloClient.mutation(
            UpdateRepoTopicsMutation(UpdateTopicsInput(repositoryId = id, topicNames = topics)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val payload = result.data.updateTopics ?: error("Topics update payload is missing")
                payload.invalidTopicNames.orEmpty()
            }
        }
    }

    suspend fun setArchived(id: String, archived: Boolean): ApiResult<Unit> = if (archived) {
        when (val result = apolloClient.mutation(ArchiveRepoMutation(id)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall { check(result.data.archiveRepository?.repository?.isArchived == true) }
        }
    } else {
        when (val result = apolloClient.mutation(UnarchiveRepoMutation(id)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall { check(result.data.unarchiveRepository?.repository?.isArchived == false) }
        }
    }

    suspend fun setSubscription(id: String, state: String): ApiResult<RepositorySubscription> {
        val subscription = SubscriptionState.entries.firstOrNull { it.rawValue == state && it != SubscriptionState.UNKNOWN__ }
            ?: return safeCall { error("Unsupported subscription state") }
        return when (val result = apolloClient.mutation(
            UpdateRepoSubscriptionMutation(UpdateSubscriptionInput(subscribableId = id, state = subscription)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val node = result.data.updateSubscription?.subscribable?.onRepository
                    ?: error("Subscription update payload is missing")
                RepositorySubscription(node.viewerSubscription?.rawValue ?: error("Subscription state is missing"), node.watchers.totalCount)
            }
        }
    }

    suspend fun setVisibility(owner: String, name: String, visibility: String): ApiResult<Unit> = safeCall {
        require(visibility == "public" || visibility == "private")
        val response = api.patch<ManagedRepoResponse, RepoVisibilityBody>("/repos/$owner/$name", RepoVisibilityBody(visibility))
        check(response.visibility == visibility) { "Repository visibility was not updated" }
    }

    suspend fun getTransferTarget(login: String, newName: String): ApiResult<RepositoryTransferTarget> {
        val result = try {
            val epoch = AccountContext.ensureCurrent()
            val response = apolloClient.query(RepoTransferTargetQuery(login, newName)).execute()
            epoch.ensureCurrent()
            val account = response.data?.repositoryOwner
            // Only this existence check accepts a missing repository alongside a resolved account.
            val expectedMissingRepository = account != null && account.id.isNotBlank() && account.login.isNotBlank() &&
                (account.onOrganization != null || account.onUser != null) && account.repository == null &&
                response.errors.orEmpty().all { error ->
                    error.path == listOf("repositoryOwner", "repository") && GraphQLErrorMapper.errorType(error) == "NOT_FOUND"
                }
            GraphQLErrorMapper.toApiResult(
                response.data,
                if (expectedMissingRepository) null else response.errors,
                response.exception,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return ApiResult.Failure(GraphQLErrorMapper.toApiError(error))
        }
        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val account = result.data.repositoryOwner ?: throw UserVisibleException(R.string.repo_transfer_account_missing)
                val organization = account.onOrganization
                RepositoryTransferTarget(
                    account = RepositoryCreateOwner(
                        id = account.id,
                        login = account.login,
                        name = organization?.name ?: account.onUser?.name,
                        avatarUrl = account.avatarUrl,
                        type = if (organization != null) RepositoryCreateOwnerType.ORGANIZATION else RepositoryCreateOwnerType.USER,
                        canCreateRepository = organization?.viewerCanCreateRepositories ?: true,
                    ),
                    hasNameConflict = account.repository != null,
                )
            }
        }
    }

    suspend fun transferRepository(owner: String, name: String, target: String, newName: String): ApiResult<Unit> = safeCall {
        if (!isValidRepositoryName(newName)) throw UserVisibleException(R.string.repo_manage_invalid_name)
        require(target.isNotBlank() && !target.equals(owner, true) && '/' !in target)
        with(api.post<ManagedRepoResponse, RepoTransferBody>(
            "/repos/$owner/$name/transfer",
            RepoTransferBody(target, newName.takeUnless { it == name }),
        )) { }
        // 202 is acceptance, not a new repository address.
    }

    suspend fun deleteRepository(owner: String, name: String): ApiResult<Unit> = safeCall {
        api.delete<Unit>("/repos/$owner/$name")
        branchesCache.invalidate("$owner/$name")
    }
}

@kotlinx.serialization.Serializable
private data class RepoVisibilityBody(val visibility: String)

@kotlinx.serialization.Serializable
private data class RepoTransferBody(
    @kotlinx.serialization.SerialName("new_owner") val newOwner: String,
    @kotlinx.serialization.SerialName("new_name") val newName: String? = null,
)

@kotlinx.serialization.Serializable
private data class CreateForkBody(val organization: String? = null, val name: String)

private fun ForkIdentity.toFork() = RepositoryFork(
    id, owner.id, owner.login, name,
)

@kotlinx.serialization.Serializable
private data class RestForkRepository(
    val id: Long,
    @kotlinx.serialization.SerialName("node_id") val nodeId: String,
    @kotlinx.serialization.SerialName("full_name") val fullName: String,
    val name: String,
    val owner: RestForkOwner,
    val fork: Boolean,
    val source: RestForkSource? = null,
) {
    val networkId: Long? get() = if (fork) source?.id else id
    fun toFork(): RepositoryFork {
        require(nodeId.isNotBlank() && fullName == "${owner.login}/$name")
        return RepositoryFork(nodeId, owner.nodeId, owner.login, name)
    }
}

@kotlinx.serialization.Serializable
private data class RestForkOwner(@kotlinx.serialization.SerialName("node_id") val nodeId: String, val login: String)

@kotlinx.serialization.Serializable
private data class RestForkSource(val id: Long, @kotlinx.serialization.SerialName("node_id") val nodeId: String)


@kotlinx.serialization.Serializable
private data class ManagedRepoResponse(val visibility: String? = null)

@kotlinx.serialization.Serializable
private data class SetDefaultBranchBody(
    @kotlinx.serialization.SerialName("default_branch") val defaultBranch: String,
)

@kotlinx.serialization.Serializable
private data class SetDefaultBranchResponse(
    @kotlinx.serialization.SerialName("default_branch") val defaultBranch: String? = null,
)

@kotlinx.serialization.Serializable
private data class CreateRefBody(
    val ref: String,
    val sha: String,
)

@kotlinx.serialization.Serializable
private data class CreateTreeBody(
    val tree: List<RestGitTreeEntry>,
)

@kotlinx.serialization.Serializable
private data class RestGitTreeEntry(
    val path: String = "",
    val mode: String = "100644",
    val type: String = "blob",
    val sha: String? = null,
    val content: String? = null,
)

@kotlinx.serialization.Serializable
private data class RestGitTreeResponse(
    val sha: String? = null,
)

@kotlinx.serialization.Serializable
private data class CreateCommitBody(
    val message: String,
    val tree: String,
    val parents: List<String>,
)

@kotlinx.serialization.Serializable
private data class RestGitCommitResponse(
    val sha: String? = null,
)

@kotlinx.serialization.Serializable
private data class RenameBranchBody(
    @kotlinx.serialization.SerialName("new_name") val newName: String,
)

@kotlinx.serialization.Serializable
private data class RestGitRefResponse(
    val ref: String? = null,
    @kotlinx.serialization.SerialName("object") val objectInfo: RestGitObject? = null,
)

@kotlinx.serialization.Serializable
private data class RestGitObject(
    val sha: String? = null,
)

@kotlinx.serialization.Serializable
private data class RestBranchResponse(
    val name: String? = null,
)

@kotlinx.serialization.Serializable
private data class ReadmeResponse(
    val type: String? = null,
    val path: String? = null,
    val encoding: String? = null,
    val content: String? = null,
    val size: Long? = null,
)
