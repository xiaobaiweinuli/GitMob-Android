package com.gitmob.app.data.repository

import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.*
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoRepository @Inject constructor(
    private val api: GHApiClient,
) {

    // ── 缓存实例 ──────────────────────────────────────────────
    /** viewer 自己的仓库第一页（login=null, after=null），TTL 5 min */
    private val viewerReposCache = MemoryCache<Unit, RepoList>(ttlMs = 5 * 60_000L)

    /**
     * 公开：登出时由 AuthRepository 统一调用，清空当前 Repository 的全部内存缓存。
     */
    fun invalidateAllCaches() {
        viewerReposCache.invalidateAll()
    }

    /**
     * 统一的仓库列表查询入口：当前登录用户 + 任意指定用户都走这一个方法。
     *
     * @param login 要查询的 owner login（用户或组织）。
     *              `null` 时走 `viewer.repositories`（当前登录用户，「仓库」Tab）；
     *              非空时走 `repositoryOwner(login:).repositories`（个人/组织主页点「仓库」。
 
     * @param after 分页游标，`null` 表示第一页。
     * @return 仓库列表，两种模式下返回的 `RepoListConnection` 字段结构完全一致（GraphQL Schema 已核实）。
     */
    /**
     * 仓库列表。
     * 仅对「viewer 自己 + 第一页（login=null, after=null）」启用缓存（TTL 5 min）；
     * 他人仓库/第二页及以后不走缓存。
     */
    suspend fun getRepos(login: String? = null, after: String? = null): ApiResult<RepoList> {
        if (login == null && after == null) {
            viewerReposCache.get(Unit)?.let { return ApiResult.Success(it) }
            val result = getReposInternal(login, after)
            if (result is ApiResult.Success) viewerReposCache.set(Unit, result.data)
            return result
        }
        return getReposInternal(login, after)
    }

    /**
     * 下拉刷新专用：强制重新拉取 viewer 自己的仓库第一页，不走缓存。
     */
    suspend fun getViewerReposFresh(): ApiResult<RepoList> {
        val result = getReposInternal(login = null, after = null)
        if (result is ApiResult.Success) viewerReposCache.set(Unit, result.data)
        return result
    }

    private suspend fun getReposInternal(login: String?, after: String?): ApiResult<RepoList> = safeCall {
        // 仓库公共字段片段：viewer.repositories 和 repositoryOwner(login:).repositories 下的字段集合 100% 一致
        // 直接复用一份字符串避免两处漂移（技能文档第三节「协议屏蔽」统一查询思想）。
        val repoFields = """
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
        """.trimIndent()

        val query = if (login == null) {
            """
                query ViewerRepos(${'$'}after: String) {
                    viewer {
                        repositories(first: ${PageSize.REPOS}, after: ${'$'}after, ownerAffiliations: [OWNER]) {
                            totalCount
                            nodes { $repoFields }
                            pageInfo { hasNextPage endCursor }
                        }
                    }
                }
            """.trimIndent()
        } else {
            """
                query OwnerRepos(${'$'}login: String!, ${'$'}after: String) {
                    repositoryOwner(login: ${'$'}login) {
                        repositories(first: ${PageSize.REPOS}, after: ${'$'}after, ownerAffiliations: [OWNER]) {
                            totalCount
                            nodes { $repoFields }
                            pageInfo { hasNextPage endCursor }
                        }
                    }
                }
            """.trimIndent()
        }

        val variables = buildMap {
            after?.let { put("after", JsonPrimitive(it)) }
            login?.let { put("login", JsonPrimitive(it)) }
        }

        // 两种模式共用同一个 DTO：同时暴露 viewer 和 repositoryOwner 两个 nullable 根
        // GraphQL 服务端只会返回对应查询的那一个，另一个保持 null，
        // 这样就不需要维护两份结构完全重复的 ViewerRepoListQueryData / UserRepoListQueryData。
        val data = api.graphQL<UnifiedRepoListQueryData>(query, variables)
        val conn = data.viewer?.repositories
            ?: data.repositoryOwner?.repositories
            ?: throw IllegalStateException("仓库查询根字段缺失（viewer/repositoryOwner 均为 null）")
        RepoList(
            totalCount = conn.totalCount,
            items = conn.nodes.map { it.toDomain() },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }
}
