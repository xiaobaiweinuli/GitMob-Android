package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.gitmob.app.R
import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.data.model.PagedStarredRepos
import com.gitmob.app.data.model.StarredRepo
import com.gitmob.app.data.model.UserListSummary
import com.gitmob.app.graphql.AddStarMutation
import com.gitmob.app.graphql.CreateListMutation
import com.gitmob.app.graphql.DeleteListMutation
import com.gitmob.app.graphql.ListItemsQuery
import com.gitmob.app.graphql.ListsContainingRepoQuery
import com.gitmob.app.graphql.RemoveStarMutation
import com.gitmob.app.graphql.UpdateListMutation
import com.gitmob.app.graphql.UpdateListsForItemMutation
import com.gitmob.app.graphql.UserStarredReposQuery
import com.gitmob.app.graphql.ViewerListsQuery
import com.gitmob.app.graphql.ViewerStarredReposQuery
import com.gitmob.app.graphql.fragment.StarredRepoFields
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StarRepository @Inject constructor(
    private val apolloClient: ApolloClient,
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
    suspend fun getListsFresh(): ApiResult<List<UserListSummary>> =
        when (val result = apolloClient.query(ViewerListsQuery()).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                // 每个列表这里只读取 totalCount；单节点容量不是列表分页。
                val lists = result.data.viewer.lists.nodes.orEmpty().filterNotNull().map { it.toSummary() }
                listsCache.set(Unit, lists)
                lists
            }
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

    private suspend fun getAllStarredInternal(login: String?, after: String?): ApiResult<PagedStarredRepos> {
        // viewer/user 两个根字段结构 100% 一致，共用 StarredRepoFields 片段做映射。
        if (login == null) {
            return when (val result = apolloClient.query(
                ViewerStarredReposQuery(Optional.presentIfNotNull(after)),
            ).executeAsApiResult()) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> safeCall {
                    val conn = result.data.viewer.starredRepositories
                    PagedStarredRepos(
                        totalCount = conn.totalCount,
                        items = conn.edges.orEmpty().filterNotNull().mapNotNull { it.node }
                            .map { it.starredRepoFields.toDomain() },
                        hasNextPage = conn.pageInfo.hasNextPage,
                        endCursor = conn.pageInfo.endCursor,
                    )
                }
            }
        }
        return when (val result = apolloClient.query(
            UserStarredReposQuery(login, Optional.presentIfNotNull(after)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val conn = result.data.user?.starredRepositories
                    ?: throw IllegalStateException("星标查询根字段缺失（user 为 null）")
                PagedStarredRepos(
                    totalCount = conn.totalCount,
                    items = conn.edges.orEmpty().filterNotNull().mapNotNull { it.node }
                        .map { it.starredRepoFields.toDomain() },
                    hasNextPage = conn.pageInfo.hasNextPage,
                    endCursor = conn.pageInfo.endCursor,
                )
            }
        }
    }

    /** 某个具体列表内的仓库，用 node(id:) 通用查询（UserList 实现了 Node 接口，已核实） */
    suspend fun getListItems(listId: String, after: String? = null): ApiResult<PagedStarredRepos> =
        when (val result = apolloClient.query(
            ListItemsQuery(listId, Optional.presentIfNotNull(after)),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val conn = result.data.node?.onUserList?.items
                    ?: throw UserVisibleException(R.string.error_list_not_found)
                PagedStarredRepos(
                    totalCount = conn.totalCount,
                    items = conn.nodes.orEmpty().filterNotNull()
                        .mapNotNull { it.onRepository?.starredRepoFields }
                        .map { it.toDomain() },
                    hasNextPage = conn.pageInfo.hasNextPage,
                    endCursor = conn.pageInfo.endCursor,
                )
            }
        }

    /**
     * "添加到列表"弹窗打开时，需要知道这个仓库当前已经在哪些列表里（预勾选）。
     * 公开 API 没有从仓库反查所属清单的字段（Repository.lists 不存在，已核实过），
     * 只能反过来：拉出全部列表 + 每个列表的 items，客户端自己算交集。
     */
    suspend fun getListsContaining(repoId: String): ApiResult<Set<String>> =
        when (val result = apolloClient.query(ListsContainingRepoQuery()).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                result.data.viewer.lists.nodes.orEmpty().filterNotNull()
                    .filter { list ->
                        list.items.nodes.orEmpty().filterNotNull()
                            .any { it.onRepository?.id == repoId }
                    }
                    .map { it.id }
                    .toSet()
            }
        }

    suspend fun createList(name: String, description: String?, isPrivate: Boolean): ApiResult<UserListSummary> {
        // 新建列表后，横排 Chip 列表数量变了，主动失效缓存
        listsCache.invalidate(Unit)
        // Mutation 返回体只读取列表项目总数，不加载列表项目。
        return when (val result = apolloClient.mutation(
            CreateListMutation(
                name,
                Optional.presentIfNotNull(description),
                Optional.present(isPrivate),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val list = result.data.createUserList?.list
                    ?: throw UserVisibleException(R.string.error_list_create_failed)
                list.toSummary()
            }
        }
    }

    /**
     * 给某个仓库分配所属列表——注意这是"全量覆盖"语义（UpdateUserListsForItemInput.listIds
     * 传什么它就变成什么，不是增量 add/remove），调用方要把最终应该保留的全部列表 id 一起传。
     */
    suspend fun updateListsForItem(itemId: String, listIds: Set<String>): ApiResult<Unit> =
        when (val result = apolloClient.mutation(
            UpdateListsForItemMutation(itemId, listIds.toList()),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall { }
        }

    /** 编辑列表：改名/改描述/切私有，只传要改的字段，其余传 null 表示不改 */
    suspend fun updateList(
        listId: String,
        name: String?,
        description: String?,
        isPrivate: Boolean?,
    ): ApiResult<UserListSummary> =
        when (val result = apolloClient.mutation(
            UpdateListMutation(
                listId,
                Optional.presentIfNotNull(name),
                Optional.presentIfNotNull(description),
                Optional.presentIfNotNull(isPrivate),
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                // Mutation 返回体只读取列表项目总数，不加载列表项目。
                val list = result.data.updateUserList?.list
                    ?: throw UserVisibleException(R.string.error_list_update_failed)
                // 改完列表（名/描述/私有性）后主动失效缓存
                listsCache.invalidate(Unit)
                list.toSummary()
            }
        }

    suspend fun deleteList(listId: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(DeleteListMutation(listId)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                // 删除列表后，主动失效列表缓存
                listsCache.invalidate(Unit)
            }
        }

    /** 取消星标（不影响该仓库在已有列表里的归属，GitHub 服务端自己决定，客户端不用管） */
    suspend fun unstarRepo(repoId: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(RemoveStarMutation(repoId)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                // 取消星标后，viewer.starredRepositories 的 totalCount 和节点列表都会变，失效缓存
                viewerStarredCache.invalidate(Unit)
            }
        }

    suspend fun starRepo(repoId: String): ApiResult<Unit> =
        when (val result = apolloClient.mutation(AddStarMutation(repoId)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                // 加星标后失效缓存
                viewerStarredCache.invalidate(Unit)
            }
        }

    private fun ViewerListsQuery.Node.toSummary() = UserListSummary(
        id = id, name = name, slug = slug, description = description,
        isPrivate = isPrivate, itemCount = items.totalCount,
    )

    private fun CreateListMutation.List.toSummary() = UserListSummary(
        id = id, name = name, slug = slug, description = description,
        isPrivate = isPrivate, itemCount = items.totalCount,
    )

    private fun UpdateListMutation.List.toSummary() = UserListSummary(
        id = id, name = name, slug = slug, description = description,
        isPrivate = isPrivate, itemCount = items.totalCount,
    )
}

/**
 * StarredRepoFields → StarredRepo 公共映射。
 * "全部星标"、列表内仓库、他人星标页三处字段结构一致，统一走片段映射。
 */
internal fun StarredRepoFields.toDomain() = StarredRepo(
    id = id, name = name, url = url, description = description, homepageUrl = homepageUrl,
    ownerLogin = owner.login, ownerAvatarUrl = owner.avatarUrl,
    isPrivate = isPrivate, isArchived = isArchived,
    isFork = isFork, forkedFromOwner = parent?.owner?.login, forkedFromName = parent?.name,
    languageName = primaryLanguage?.name, languageColor = primaryLanguage?.color,
    stargazerCount = stargazerCount, forkCount = forkCount, openIssueCount = issues.totalCount,
    topics = repositoryTopics.nodes.orEmpty().filterNotNull().map { it.topic.name },
    defaultBranchName = defaultBranchRef?.name,
)
