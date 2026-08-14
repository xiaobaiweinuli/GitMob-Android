package com.gitmob.app.data.repository

import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

// 星标仓库卡片公共字段片段，"全部星标"和"某个列表内"两个查询字段结构完全一样
private const val STARRED_REPO_FIELDS = """
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
"""

@Singleton
class StarRepository @Inject constructor(
    private val api: GHApiClient,
) {

    // ── 缓存实例 ──────────────────────────────────────────────
    /** viewer 自己的全部列表（横排 Chip），TTL 3 min */
    private val listsCache = MemoryCache<Unit, List<UserListSummary>>(ttlMs = 3 * 60_000L)
    /** viewer 自己的星标仓库第一页（after=null），TTL 3 min */
    private val viewerStarredCache = MemoryCache<Unit, PagedStarredRepos>(ttlMs = 3 * 60_000L)

    /**
     * 公开：登出时由 AuthRepository 统一调用，清空当前 Repository 的全部内存缓存。
     */
    fun invalidateAllCaches() {
        listsCache.invalidateAll()
        viewerStarredCache.invalidateAll()
    }

    /**
     * "我的列表"横排 chip 用。
     * 带缓存：冷启动直接显示上次数据，TTL 3 分钟内不重复请求。
     * 下拉刷新/列表增删改后调用 [getListsFresh]。
     */
    suspend fun getLists(): ApiResult<List<UserListSummary>> {
        listsCache.get(Unit)?.let { return ApiResult.Success(it) }
        return getListsFresh()
    }

    /** 下拉刷新/增删改后强制重新拉取列表，不走缓存 */
    suspend fun getListsFresh(): ApiResult<List<UserListSummary>> = safeCall {
        val query = """
            query ViewerLists {
                viewer {
                    lists(first: ${PageSize.STAR_LISTS}) {
                        nodes { id name slug description isPrivate items(first: 1) { totalCount } }
                    }
                }
            }
        """.trimIndent()
        val lists = api.graphQL<ViewerListsQueryData>(query).viewer.lists.nodes.map { it.toSummary() }
        listsCache.set(Unit, lists)
        lists
    }

    /**
     * 当前登录用户自己的星标仓库。
     * 第一页启用缓存（TTL 3 min），后续页直接按 [after] 游标请求。
     * 独立方法可避免两个 nullable String 参数发生位置错配，将分页游标误当成 login。
     */
    suspend fun getViewerStarred(after: String? = null): ApiResult<PagedStarredRepos> {
        if (after == null) {
            viewerStarredCache.get(Unit)?.let { return ApiResult.Success(it) }
            val result = getAllStarredInternal(login = null, after = null)
            if (result is ApiResult.Success) viewerStarredCache.set(Unit, result.data)
            return result
        }
        return getAllStarredInternal(login = null, after = after)
    }

    /** 指定用户的公开星标仓库；他人星标页使用，不启用 viewer 缓存。 */
    suspend fun getUserStarred(login: String, after: String? = null): ApiResult<PagedStarredRepos> =
        getAllStarredInternal(login = login, after = after)

    /**
     * 下拉刷新专用：强制重新拉取 viewer 自己的星标第一页，不走缓存。
     */
    suspend fun getAllViewerStarredFresh(): ApiResult<PagedStarredRepos> {
        val result = getAllStarredInternal(login = null, after = null)
        if (result is ApiResult.Success) viewerStarredCache.set(Unit, result.data)
        return result
    }

    private suspend fun getAllStarredInternal(login: String?, after: String?): ApiResult<PagedStarredRepos> = safeCall {
        val query = if (login == null) {
            """
                query StarredRepos(${'$'}after: String) {
                    viewer {
                        starredRepositories(first: ${PageSize.STARRED_REPOS}, after: ${'$'}after, orderBy: { field: STARRED_AT, direction: DESC }) {
                            totalCount
                            edges { node { $STARRED_REPO_FIELDS } }
                            pageInfo { hasNextPage endCursor }
                        }
                    }
                }
            """.trimIndent()
        } else {
            """
                query UserStarredRepos(${'$'}login: String!, ${'$'}after: String) {
                    user(login: ${'$'}login) {
                        starredRepositories(first: ${PageSize.STARRED_REPOS}, after: ${'$'}after, orderBy: { field: STARRED_AT, direction: DESC }) {
                            totalCount
                            edges { node { $STARRED_REPO_FIELDS } }
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

        // 两种模式共用同一个 DTO，viewer/user 两个 nullable 根哪个实际查询到就用哪个。
        val data = api.graphQL<UnifiedStarredReposQueryData>(query, variables)
        val conn = data.viewer?.starredRepositories
            ?: data.user?.starredRepositories
            ?: throw IllegalStateException("星标查询根字段缺失（viewer/user 均为 null）")
        PagedStarredRepos(
            totalCount = conn.totalCount,
            items = conn.edges.map { it.node.toDomain() },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    /** 某个具体列表内的仓库，用 node(id:) 通用查询（UserList 实现了 Node 接口，已核实） */
    suspend fun getListItems(listId: String, after: String? = null): ApiResult<PagedStarredRepos> = safeCall {
        val query = """
            query ListItems(${'$'}listId: ID!, ${'$'}after: String) {
                node(id: ${'$'}listId) {
                    ... on UserList {
                        items(first: ${PageSize.LIST_ITEMS}, after: ${'$'}after) {
                            totalCount
                            nodes { ... on Repository { $STARRED_REPO_FIELDS } }
                            pageInfo { hasNextPage endCursor }
                        }
                    }
                }
            }
        """.trimIndent()
        val variables = buildMap {
            put("listId", JsonPrimitive(listId))
            after?.let { put("after", JsonPrimitive(it)) }
        }
        val conn = api.graphQL<ListItemsQueryData>(query, variables).node?.items
            ?: throw IllegalStateException("列表不存在")
        PagedStarredRepos(
            totalCount = conn.totalCount,
            items = conn.nodes.map { it.toDomain() },
            hasNextPage = conn.pageInfo.hasNextPage,
            endCursor = conn.pageInfo.endCursor,
        )
    }

    /**
     * "添加到列表"弹窗打开时，需要知道这个仓库当前已经在哪些列表里（预勾选）。
     * 公开 API 没有从仓库反查所属清单的字段（Repository.lists 不存在，已核实过），
     * 只能反过来：拉出全部列表 + 每个列表的 items，客户端自己算交集。
     */
    suspend fun getListsContaining(repoId: String): ApiResult<Set<String>> = safeCall {
        val query = """
            query ListsContainingRepo {
                viewer {
                    lists(first: ${PageSize.STAR_LISTS_SCAN_LIMIT}) {
                        nodes { id items(first: ${PageSize.LIST_ITEMS_SCAN_LIMIT}) { nodes { ... on Repository { id } } } }
                    }
                }
            }
        """.trimIndent()
        api.graphQL<ListsContainingQueryData>(query).viewer.lists.nodes
            .filter { list -> list.items.nodes.any { it.id == repoId } }
            .map { it.id }
            .toSet()
    }

    suspend fun createList(name: String, description: String?, isPrivate: Boolean): ApiResult<UserListSummary> = safeCall {
        // 新建列表后，横排 Chip 列表数量变了，主动失效缓存
        listsCache.invalidate(Unit)
        val mutation = """
            mutation CreateList(${'$'}name: String!, ${'$'}description: String, ${'$'}isPrivate: Boolean) {
                createUserList(input: { name: ${'$'}name, description: ${'$'}description, isPrivate: ${'$'}isPrivate }) {
                    list { id name slug description isPrivate items(first: 1) { totalCount } }
                }
            }
        """.trimIndent()
        val variables = buildMap {
            put("name", JsonPrimitive(name))
            description?.let { put("description", JsonPrimitive(it)) }
            put("isPrivate", JsonPrimitive(isPrivate))
        }
        val list = api.graphQL<CreateListMutationData>(mutation, variables).createUserList?.list
            ?: throw IllegalStateException("创建列表失败")
        list.toSummary()
    }

    /**
     * 给某个仓库分配所属列表——注意这是"全量覆盖"语义（UpdateUserListsForItemInput.listIds
     * 传什么它就变成什么，不是增量 add/remove），调用方要把最终应该保留的全部列表 id 一起传。
     */
    suspend fun updateListsForItem(itemId: String, listIds: Set<String>): ApiResult<Unit> = safeCall {
        val mutation = """
            mutation UpdateListsForItem(${'$'}itemId: ID!, ${'$'}listIds: [ID!]!) {
                updateUserListsForItem(input: { itemId: ${'$'}itemId, listIds: ${'$'}listIds }) { clientMutationId }
            }
        """.trimIndent()
        with(api.graphQL<UpdateListsForItemMutationData>(
            mutation,
            mapOf(
                "itemId" to JsonPrimitive(itemId),
                "listIds" to JsonArray(listIds.map { JsonPrimitive(it) }),
            ),
        )) { }
    }

    /** 编辑列表：改名/改描述/切私有，只传要改的字段，其余传 null 表示不改 */
    suspend fun updateList(
        listId: String,
        name: String?,
        description: String?,
        isPrivate: Boolean?,
    ): ApiResult<UserListSummary> = safeCall {
        val mutation = """
            mutation UpdateList(${'$'}listId: ID!, ${'$'}name: String, ${'$'}description: String, ${'$'}isPrivate: Boolean) {
                updateUserList(input: { listId: ${'$'}listId, name: ${'$'}name, description: ${'$'}description, isPrivate: ${'$'}isPrivate }) {
                    list { id name slug description isPrivate items(first: 1) { totalCount } }
                }
            }
        """.trimIndent()
        val variables = buildMap {
            put("listId", JsonPrimitive(listId))
            name?.let { put("name", JsonPrimitive(it)) }
            description?.let { put("description", JsonPrimitive(it)) }
            isPrivate?.let { put("isPrivate", JsonPrimitive(it)) }
        }
        val list = api.graphQL<UpdateListMutationData>(mutation, variables).updateUserList?.list
            ?: throw IllegalStateException("更新列表失败")
        // 改完列表（名/描述/私有性）后主动失效缓存
        listsCache.invalidate(Unit)
        list.toSummary()
    }

    suspend fun deleteList(listId: String): ApiResult<Unit> = safeCall {
        val mutation = """
            mutation DeleteList(${'$'}listId: ID!) {
                deleteUserList(input: { listId: ${'$'}listId }) { clientMutationId }
            }
        """.trimIndent()
        with(api.graphQL<DeleteListMutationData>(mutation, mapOf("listId" to JsonPrimitive(listId)))) { }
        // 删除列表后，主动失效列表缓存
        listsCache.invalidate(Unit)
    }

    /** 取消星标（不影响该仓库在已有列表里的归属，GitHub 服务端自己决定，客户端不用管） */
    suspend fun unstarRepo(repoId: String): ApiResult<Unit> = safeCall {
        val mutation = """
            mutation RemoveStar(${'$'}starrableId: ID!) {
                removeStar(input: { starrableId: ${'$'}starrableId }) { clientMutationId }
            }
        """.trimIndent()
        with(api.graphQL<RemoveStarMutationData>(mutation, mapOf("starrableId" to JsonPrimitive(repoId)))) { }
        // 取消星标后，viewer.starredRepositories 的 totalCount 和节点列表都会变，失效缓存
        viewerStarredCache.invalidate(Unit)
    }

    suspend fun starRepo(repoId: String): ApiResult<Unit> = safeCall {
        val mutation = """
            mutation AddStar(${'$'}starrableId: ID!) {
                addStar(input: { starrableId: ${'$'}starrableId }) { clientMutationId }
            }
        """.trimIndent()
        with(api.graphQL<AddStarMutationData>(mutation, mapOf("starrableId" to JsonPrimitive(repoId)))) { }
        // 加星标后失效缓存
        viewerStarredCache.invalidate(Unit)
    }

    private fun UserListNode.toSummary() = UserListSummary(
        id = id, name = name, slug = slug, description = description,
        isPrivate = isPrivate, itemCount = items.totalCount,
    )
}
