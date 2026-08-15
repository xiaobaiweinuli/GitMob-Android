package com.gitmob.app.data.repository

import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.DeleteRefMutationData
import com.gitmob.app.data.model.IssueCreationPolicy
import com.gitmob.app.data.model.PagedBranches
import com.gitmob.app.data.model.PagedUsers
import com.gitmob.app.data.model.RepoBranch
import com.gitmob.app.data.model.RepoBranchesQueryData
import com.gitmob.app.data.model.RepoDetail
import com.gitmob.app.data.model.RepoDetailQueryData
import com.gitmob.app.data.model.RepoReadme
import com.gitmob.app.data.model.RepoReadmeQueryData
import com.gitmob.app.data.model.RepoWatchersQueryData
import com.gitmob.app.data.model.SimpleUser
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoDetailRepository @Inject constructor(
    private val api: GHApiClient,
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
    suspend fun getRepoDetail(owner: String, name: String): ApiResult<RepoDetail> = safeCall {
        val query = """
            query RepoDetail(${'$'}owner: String!, ${'$'}name: String!) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    id
                    name
                    description
                    homepageUrl
                    owner { login avatarUrl }
                    isPrivate
                    isArchived
                    isTemplate
                    isFork
                    parent { name owner { login avatarUrl } }
                    stargazerCount
                    viewerHasStarred
                    forkCount
                    issues(states: [OPEN]) { totalCount }
                    pullRequests(states: [OPEN]) { totalCount }
                    watchers(first: 0) { totalCount }
                    viewerSubscription
                    licenseInfo { name spdxId }
                    refs(refPrefix: "refs/heads/", first: 0) { totalCount }
                    defaultBranchRef { name }
                    releases(first: 1, orderBy: { field: CREATED_AT, direction: DESC }) {
                        totalCount
                        nodes { name tagName }
                    }
                    primaryLanguage { name color }
                    repositoryTopics(first: 10) { nodes { topic { name } } } # TOPICS_PER_REPO
                    viewerPermission
                    viewerCanCreateIssues
                    hasIssuesEnabled
                    isBlankIssuesEnabled
                    issueCreationPolicy
                }
            }
        """.trimIndent()
        val node = api.graphQL<RepoDetailQueryData>(
            query,
            mapOf("owner" to JsonPrimitive(owner), "name" to JsonPrimitive(name)),
        ).repository ?: throw IllegalStateException("仓库不存在")

        val permission = node.viewerPermission?.let {
            runCatching { RepoPermission.valueOf(it) }.getOrDefault(RepoPermission.NONE)
        } ?: RepoPermission.NONE

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
            viewerSubscription = node.viewerSubscription,
            licenseName = node.licenseInfo?.name,
            licenseSpdxId = node.licenseInfo?.spdxId,
            branchCount = node.refs.totalCount,
            defaultBranchName = node.defaultBranchRef?.name,
            releaseCount = node.releases.totalCount,
            latestReleaseName = node.releases.nodes.firstOrNull()?.name,
            latestReleaseTag = node.releases.nodes.firstOrNull()?.tagName,
            languageName = node.primaryLanguage?.name,
            languageColor = node.primaryLanguage?.color,
            topics = node.repositoryTopics.nodes.map { it.topic.name },
            permission = permission,
            viewerCanCreateIssues = node.viewerCanCreateIssues,
            hasIssuesEnabled = node.hasIssuesEnabled,
            isBlankIssuesEnabled = node.isBlankIssuesEnabled,
            issueCreationPolicy = node.issueCreationPolicy?.let {
                runCatching { IssueCreationPolicy.valueOf(it) }.getOrDefault(IssueCreationPolicy.UNKNOWN)
            } ?: IssueCreationPolicy.UNKNOWN,
            capabilities = permission.toCapabilities(),
        )
    }

    /** README 用 object(expression:) 取原始 Markdown，Repository.readme 不存在于公开 Schema */
    suspend fun getReadme(owner: String, name: String, ref: String): ApiResult<RepoReadme> = safeCall {
        val query = """
            query RepoReadme(${'$'}owner: String!, ${'$'}name: String!, ${'$'}expression: String!) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    object(expression: ${'$'}expression) {
                        ... on Blob { text isTruncated isBinary }
                    }
                }
            }
        """.trimIndent()
        val blob = api.graphQL<RepoReadmeQueryData>(
            query,
            mapOf(
                "owner" to JsonPrimitive(owner),
                "name" to JsonPrimitive(name),
                "expression" to JsonPrimitive("$ref:README.md"),
            ),
        ).repository?.`object`
        RepoReadme(markdown = blob?.text, isTruncated = blob?.isTruncated ?: false)
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

    private suspend fun getBranchesInternal(owner: String, name: String, after: String?): ApiResult<PagedBranches> = safeCall {
        val query = """
            query RepoBranches(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    defaultBranchRef { id name }
                    refs(refPrefix: "refs/heads/", first: ${PageSize.BRANCHES}, after: ${'$'}after) {
                        nodes { id name target { oid } }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            }
        """.trimIndent()
        val variables = buildMap {
            put("owner", JsonPrimitive(owner))
            put("name", JsonPrimitive(name))
            after?.let { put("after", JsonPrimitive(it)) }
        }
        val data = api.graphQL<RepoBranchesQueryData>(query, variables).repository
            ?: throw IllegalStateException("仓库不存在")

        val defaultName = data.defaultBranchRef?.name
        PagedBranches(
            items = data.refs?.nodes.orEmpty().map {
                RepoBranch(id = it.id, name = it.name, isDefault = it.name == defaultName, commitOid = it.target?.oid)
            },
            hasNextPage = data.refs?.pageInfo?.hasNextPage ?: false,
            endCursor = data.refs?.pageInfo?.endCursor,
        )
    }

    /** 仓库关注者（Watchers），复用和 followers/following 一样的 PagedUsers 形状 */
    suspend fun getWatchers(owner: String, name: String, after: String? = null): ApiResult<PagedUsers> = safeCall {
        val query = """
            query RepoWatchers(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    watchers(first: ${PageSize.USER_LIST}, after: ${'$'}after) {
                        totalCount
                        nodes { login name avatarUrl bio }
                        pageInfo { hasNextPage endCursor }
                    }
                }
            }
        """.trimIndent()
        val variables = buildMap {
            put("owner", JsonPrimitive(owner))
            put("name", JsonPrimitive(name))
            after?.let { put("after", JsonPrimitive(it)) }
        }
        val conn = api.graphQL<RepoWatchersQueryData>(query, variables).repository?.watchers
            ?: throw IllegalStateException("仓库不存在")
        PagedUsers(
            totalCount = conn.totalCount,
            users = conn.nodes.map { SimpleUser(it.login, it.name, it.avatarUrl, it.bio) },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    /**
     * 删除分支：DeleteRefInput 只需要 refId（Ref 节点自己的 id，不是分支名），已用 introspection 核实。
     * 删除后主动失效该仓库的分支缓存。
     *
     * @param refId   要删除的 Ref 节点的 GraphQL 全局 id
     * @param owner   仓库 owner login（用于失效对应缓存 key）
     * @param name    仓库名（用于失效对应缓存 key）
     */
    suspend fun deleteBranch(refId: String, owner: String, name: String): ApiResult<Unit> = safeCall {
        val mutation = """
            mutation DeleteBranch(${'$'}refId: ID!) {
                deleteRef(input: { refId: ${'$'}refId }) { clientMutationId }
            }
        """.trimIndent()
        with(api.graphQL<DeleteRefMutationData>(mutation, mapOf("refId" to JsonPrimitive(refId)))) { }
        // 删除分支后，分支列表顺序、默认分支高亮都会变化，失效缓存
        branchesCache.invalidate("$owner/$name")
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
}

@kotlinx.serialization.Serializable
private data class SetDefaultBranchBody(
    @kotlinx.serialization.SerialName("default_branch") val defaultBranch: String,
)

@kotlinx.serialization.Serializable
private data class SetDefaultBranchResponse(
    @kotlinx.serialization.SerialName("default_branch") val defaultBranch: String? = null,
)
