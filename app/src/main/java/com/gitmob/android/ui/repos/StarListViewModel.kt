package com.gitmob.android.ui.repos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.android.api.GraphQLClient
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.data.RepoRepository
import com.gitmob.android.data.RepoUpdateEvent
import com.gitmob.android.data.RepoUpdateEventBus
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

import com.gitmob.android.util.LanguageEntry

private const val TAG = "StarListVM"

// ─── 缓存配置 ──────────────────────────────────────────────────────────────────
private const val LISTS_TTL_MS  = 5 * 60 * 1000L   // UserList 缓存 5 分钟
private const val REPOS_TTL_MS  = 3 * 60 * 1000L   // 星标仓库缓存 3 分钟

/** 仓库缓存条目 */
private data class RepoCacheEntry(
    val repos: List<StarredRepo>,
    val hasNextPage: Boolean,
    val endCursor: String?,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    fun isExpired() = System.currentTimeMillis() - timestampMs > REPOS_TTL_MS
}

data class StarListState(
    val starModeActive: Boolean = false,
    val starSearchQuery: String = "",
    val userLists: List<UserList> = emptyList(),
    val listsLoading: Boolean = false,
    val listsExpanded: Boolean = false,
    val selectedListId: String? = null,
    val starredRepos: List<StarredRepo> = emptyList(),
    val reposLoading: Boolean = false,
    val hasNextPage: Boolean = false,
    val endCursor: String? = null,
    val filterState: StarFilterState = StarFilterState(),
    val toast: String? = null,
    /** 当前展示的是登录用户自己的星标列表（为 false 时隐藏取消星标/书签/添加列表操作） */
    val isOwnList: Boolean = true,
)

class StarListViewModel(app: Application) : AndroidViewModel(app) {

    private val tokenStorage = TokenStorage(app)
    private val repo = RepoRepository()
    private val _state = MutableStateFlow(StarListState())
    val state = _state.asStateFlow()

    // ─── 内存缓存 ──────────────────────────────────────────────────────────────
    /** UserList 缓存，null 表示未缓存 */
    private var listsCache: Pair<List<UserList>, Long>? = null

    /**
     * 仓库缓存：key = listId（null 表示"全部星标"），value = 已加载的完整页列表
     * 切换 listId 时直接读缓存，不重新请求
     */
    private val reposCache = mutableMapOf<String?, RepoCacheEntry>()

    /** 事件监听任务，用于在退出星标模式时取消 */
    private var eventJob: kotlinx.coroutines.Job? = null

    /**
     * 仓库 → 所属列表 ID 集合 的反向映射（key = repo nodeId）
     * 由于 Repository GraphQL 对象不暴露 lists 字段，
     * 该映射通过加载各列表仓库时逐步建立，并在分类操作时同步更新。
     */
    private val repoToListIds = mutableMapOf<String, MutableSet<String>>()

    private fun listsExpired() = listsCache?.let {
        System.currentTimeMillis() - it.second > LISTS_TTL_MS
    } ?: true

    private suspend fun token(): String? = tokenStorage.accessToken.first()

    // ── 模式切换 ─────────────────────────────────────────────────────────────
    fun toggleStarMode() {
        val entering = !_state.value.starModeActive
        if (entering) {
            _state.update { it.copy(starModeActive = true, selectedListId = null) }
            // 启动仓库更新事件监听
            eventJob = viewModelScope.launch {
                RepoUpdateEventBus.events.collect { event ->
                    when (event) {
                        is RepoUpdateEvent.RepoUpdated -> {
                            try {
                                val updatedGHRepo = repo.getRepo(event.owner, event.repo, forceRefresh = true)
                                // 更新所有相关的缓存
                                reposCache.entries.forEach { (k, v) ->
                                    reposCache[k] = v.copy(
                                        repos = v.repos.map {
                                            if (it.nameWithOwner == "${event.owner}/${event.repo}") {
                                                it.copy(
                                                    archived = updatedGHRepo.archived,
                                                    isPrivate = updatedGHRepo.private,
                                                    description = updatedGHRepo.description,
                                                    name = updatedGHRepo.name
                                                )
                                            } else {
                                                it
                                            }
                                        }
                                    )
                                }
                                // 更新当前显示的列表
                                _state.update { s ->
                                    s.copy(
                                        starredRepos = s.starredRepos.map {
                                            if (it.nameWithOwner == "${event.owner}/${event.repo}") {
                                                it.copy(
                                                    archived = updatedGHRepo.archived,
                                                    isPrivate = updatedGHRepo.private,
                                                    description = updatedGHRepo.description,
                                                    name = updatedGHRepo.name
                                                )
                                            } else {
                                                it
                                            }
                                        }
                                    )
                                }
                            } catch (e: Exception) {
                                LogManager.e("StarListViewModel", "更新星标仓库失败", e)
                            }
                        }
                        else -> {}
                    }
                }
            }
            viewModelScope.launch {
                // 同时启动加载 UserList 和 StarredRepos，不互相等待
                launch { loadUserLists(force = false) }
                launch { loadStarredRepos(force = false) }
                // 后台静默预加载各列表，建立 repoToListIds 反向映射
                // 等待 UserList 加载完成后再预加载各列表
                val listsJob = loadUserLists(force = false)
                listsJob.join()
                val lists = _state.value.userLists
                lists.forEach { list ->
                    // 只有缓存未命中时才请求
                    if (reposCache[list.id] == null || reposCache[list.id]!!.isExpired()) {
                        val t = token() ?: return@forEach
                        try {
                            val data = GraphQLClient.queryListRepos(t, list.id) ?: return@forEach
                            val nodes = data.optJSONArray("nodes") ?: return@forEach
                            val repos = (0 until nodes.length()).mapNotNull { nodes.getJSONObject(it).toStarredRepo() }
                            // 建立反向映射
                            repos.forEach { repo ->
                                repoToListIds.getOrPut(repo.nodeId) { mutableSetOf() }.add(list.id)
                            }
                            reposCache[list.id] = RepoCacheEntry(
                                repos = repos.map { repo ->
                                    val known = repoToListIds[repo.nodeId]?.toList() ?: emptyList()
                                    repo.copy(listIds = known)
                                },
                                hasNextPage = repos.size >= 50,
                                endCursor = data.optJSONObject("pageInfo")?.optString("endCursor")?.takeIf { it.isNotBlank() },
                            )
                        } catch (_: Exception) {}
                    }
                }
                // 映射建立后，刷新当前显示的全部星标列表的 listIds
                val current = _state.value.starredRepos
                val enriched = current.map { repo ->
                    val known = repoToListIds[repo.nodeId]?.toList() ?: repo.listIds
                    if (known != repo.listIds) repo.copy(listIds = known) else repo
                }
                if (enriched != current) {
                    _state.update { it.copy(starredRepos = enriched) }
                    reposCache[null]?.let { entry ->
                        reposCache[null] = entry.copy(repos = enriched)
                    }
                }
            }
        } else {
            eventJob?.cancel()
            eventJob = null
            _state.update { StarListState() }
        }
    }

    fun exitStarMode() {
        eventJob?.cancel()
        eventJob = null
        _state.update { StarListState() }
    }

    // ── 列表操作 ─────────────────────────────────────────────────────────────
    fun toggleListsExpanded() = _state.update { it.copy(listsExpanded = !it.listsExpanded) }

    /**
     * 加载 UserList，优先使用缓存。
     * @param force true = 强制刷新忽略缓存
     */
    fun loadUserLists(force: Boolean = false) = viewModelScope.launch {
        // 缓存有效时直接读取
        if (!force && !listsExpired()) {
            listsCache?.let { (cached, _) ->
                _state.update { it.copy(userLists = cached) }
                return@launch
            }
        }
        val t = token() ?: return@launch
        _state.update { it.copy(listsLoading = true) }
        try {
            val nodes = GraphQLClient.queryUserLists(t)
            val lists = nodes.mapNotNull { n -> n.toUserList().takeIf { l -> l.id.isNotBlank() } }
            listsCache = Pair(lists, System.currentTimeMillis())
            _state.update { it.copy(userLists = lists, listsLoading = false) }
        } catch (e: Exception) {
            LogManager.w(TAG, "loadUserLists: ${e.message}")
            _state.update { it.copy(listsLoading = false) }
        }
    }

    fun createList(name: String, description: String, isPrivate: Boolean) = viewModelScope.launch {
        val t = token() ?: return@launch
        try {
            val node = GraphQLClient.createUserList(t, name, description, isPrivate) ?: return@launch
            val newList = node.toUserList()
            val updated = _state.value.userLists + newList
            listsCache = Pair(updated, System.currentTimeMillis())
            _state.update { it.copy(userLists = updated, toast = "已创建「$name」") }
        } catch (e: Exception) {
            _state.update { it.copy(toast = "创建失败: ${e.message}") }
        }
    }

    fun updateList(list: UserList, name: String, description: String, isPrivate: Boolean) {
        val optimistic = list.copy(name = name, description = description, isPrivate = isPrivate)
        val updated = _state.value.userLists.map { if (it.id == list.id) optimistic else it }
        listsCache = Pair(updated, System.currentTimeMillis())
        _state.update { it.copy(userLists = updated) }
        viewModelScope.launch {
            val t = token() ?: return@launch
            val ok = GraphQLClient.updateUserList(t, list.id, name, description, isPrivate)
            if (!ok) {
                val rolled = _state.value.userLists.map { if (it.id == list.id) list else it }
                listsCache = Pair(rolled, System.currentTimeMillis())
                _state.update { it.copy(userLists = rolled, toast = "更新失败") }
            }
        }
    }

    fun deleteList(list: UserList) {
        val updated = _state.value.userLists.filter { it.id != list.id }
        listsCache = Pair(updated, System.currentTimeMillis())
        // 删除当前列表对应的仓库缓存
        reposCache.remove(list.id)
        _state.update { s ->
            s.copy(
                userLists = updated,
                selectedListId = if (s.selectedListId == list.id) null else s.selectedListId,
            )
        }
        viewModelScope.launch {
            val t = token() ?: return@launch
            val ok = GraphQLClient.deleteUserList(t, list.id)
            if (!ok) {
                val rolled = _state.value.userLists
                if (rolled.none { it.id == list.id }) {
                    val restored = (rolled + list).sortedBy { it.name }
                    listsCache = Pair(restored, System.currentTimeMillis())
                    _state.update { it.copy(userLists = restored, toast = "删除失败") }
                }
            }
            if (_state.value.selectedListId == null) loadStarredRepos(force = false)
        }
    }

    // ── 仓库加载 ─────────────────────────────────────────────────────────────
    fun selectList(listId: String?) {
        if (_state.value.selectedListId == listId) return
        _state.update { it.copy(selectedListId = listId) }
        loadStarredRepos(force = false)
    }

    /**
     * 加载星标仓库，支持缓存和分页加载更多。
     * @param force    true = 忽略缓存强制刷新
     * @param loadMore true = 追加下一页（不使用缓存）
     */
    fun loadStarredRepos(force: Boolean = false, loadMore: Boolean = false) = viewModelScope.launch {
        val listId = _state.value.selectedListId

        // 非加载更多时：检查缓存
        if (!loadMore) {
            val cached = reposCache[listId]
            if (!force && cached != null && !cached.isExpired()) {
                // 用最新的 repoToListIds 映射 enrich 缓存（分类操作后书签状态立即更新）
                val enriched = cached.repos.map { repo ->
                    val known = repoToListIds[repo.nodeId]?.toList() ?: repo.listIds
                    if (known != repo.listIds) repo.copy(listIds = known) else repo
                }
                _state.update { it.copy(
                    starredRepos = enriched,
                    hasNextPage  = cached.hasNextPage,
                    endCursor    = cached.endCursor,
                    reposLoading = false,
                )}
                return@launch
            }
        }

        val t = token() ?: return@launch
        val cursor = if (loadMore) _state.value.endCursor else null
        if (!loadMore) _state.update { it.copy(reposLoading = true) }

        try {
            val (repos, hasNext, endCursor) = if (listId == null) {
                val data = GraphQLClient.queryStarredRepos(t, cursor) ?: return@launch
                val nodes = data.optJSONArray("nodes") ?: return@launch
                val list = (0 until nodes.length()).mapNotNull { nodes.getJSONObject(it).toStarredRepo() }
                val pageInfo = data.optJSONObject("pageInfo")
                Triple(list, pageInfo?.optBoolean("hasNextPage") ?: false,
                    pageInfo?.optString("endCursor")?.takeIf { it.isNotBlank() })
            } else {
                val data = GraphQLClient.queryListRepos(t, listId, cursor) ?: return@launch
                val nodes = data.optJSONArray("nodes") ?: return@launch
                val list = (0 until nodes.length()).mapNotNull { nodes.getJSONObject(it).toStarredRepo() }
                val pageInfo = data.optJSONObject("pageInfo")
                Triple(list, pageInfo?.optBoolean("hasNextPage") ?: false,
                    pageInfo?.optString("endCursor")?.takeIf { it.isNotBlank() })
            }

            val allRepos = if (loadMore) _state.value.starredRepos + repos else repos

            // ── 同步 repoToListIds 反向映射 ──────────────────────────────────
            if (listId != null) {
                // 加载特定列表：记录该列表包含这些 repo
                allRepos.forEach { repo ->
                    repoToListIds.getOrPut(repo.nodeId) { mutableSetOf() }.add(listId)
                }
            }
            // 将映射注入到 repos 的 listIds 字段（供书签图标和 Sheet 预勾选使用）
            val enrichedRepos = allRepos.map { repo ->
                val knownListIds = repoToListIds[repo.nodeId]?.toList() ?: emptyList()
                if (knownListIds != repo.listIds) repo.copy(listIds = knownListIds) else repo
            }

            // 写入缓存（loadMore 时追加到缓存，合并完整列表）
            reposCache[listId] = RepoCacheEntry(enrichedRepos, hasNext, endCursor)
            _state.update { it.copy(
                starredRepos = enrichedRepos,
                reposLoading = false,
                hasNextPage  = hasNext,
                endCursor    = endCursor,
            )}
        } catch (e: Exception) {
            LogManager.w(TAG, "loadStarredRepos: ${e.message}")
            _state.update { it.copy(reposLoading = false) }
        }
    }

    // ── 仓库操作 ─────────────────────────────────────────────────────────────
    fun removeStar(repo: StarredRepo) {
        // 乐观删除：从所有缓存和映射中移除
        repoToListIds.remove(repo.nodeId)
        val updated = _state.value.starredRepos.filter { it.nodeId != repo.nodeId }
        reposCache.entries.forEach { (k, v) ->
            reposCache[k] = v.copy(repos = v.repos.filter { it.nodeId != repo.nodeId })
        }
        _state.update { it.copy(starredRepos = updated) }
        viewModelScope.launch {
            val t = token() ?: return@launch
            val ok = GraphQLClient.removeStar(t, repo.nodeId)
            if (!ok) {
                // 回滚：清除缓存让下次重新获取
                reposCache.clear()
                val rolled = (listOf(repo) + _state.value.starredRepos).distinctBy { it.nodeId }
                _state.update { it.copy(starredRepos = rolled, toast = "取消失败") }
            }
        }
    }

    fun updateRepoLists(repo: StarredRepo, newListIds: List<String>) {
        // 同步更新反向映射
        repoToListIds[repo.nodeId] = newListIds.toMutableSet()

        // 乐观更新本地 + 缓存
        fun applyUpdate(list: List<StarredRepo>) =
            list.map { if (it.nodeId == repo.nodeId) it.copy(listIds = newListIds) else it }
        reposCache.entries.forEach { (k, v) -> reposCache[k] = v.copy(repos = applyUpdate(v.repos)) }
        _state.update { it.copy(starredRepos = applyUpdate(it.starredRepos)) }
        viewModelScope.launch {
            val t = token() ?: return@launch
            val ok = GraphQLClient.updateRepoLists(t, repo.nodeId, newListIds)
            if (!ok) {
                // 回滚反向映射
                repoToListIds[repo.nodeId] = repo.listIds.toMutableSet()
                fun rollback(list: List<StarredRepo>) =
                    list.map { if (it.nodeId == repo.nodeId) repo else it }
                reposCache.entries.forEach { (k, v) -> reposCache[k] = v.copy(repos = rollback(v.repos)) }
                _state.update { it.copy(starredRepos = rollback(it.starredRepos), toast = "分类更新失败") }
            }
        }
    }

    /** 增量刷新：只使第一页缓存失效，保留 repoToListIds 映射，避免 UI 闪烁 */
    fun refresh() {
        val listId = _state.value.selectedListId
        // 只清除当前展示的 listId 对应的第一页缓存，不清 repoToListIds
        reposCache.remove(listId)
        // UserList 元数据也刷新（可能有新建/删除）
        listsCache = null
        viewModelScope.launch {
            loadUserLists(force = true).join()
            loadStarredRepos(force = true)
        }
    }

    fun setStarSearch(q: String) = _state.update { it.copy(starSearchQuery = q) }

    /** 设置仓库类型筛选 */
    fun setStarTypeFilter(filter: RepoTypeFilter) = _state.update { 
        it.copy(filterState = it.filterState.copy(typeFilter = filter)) 
    }

    /** 设置排序方式 */
    fun setStarSortBy(sortBy: StarSortBy) = _state.update { 
        it.copy(filterState = it.filterState.copy(sortBy = sortBy)) 
    }
    
    /** 设置语言筛选 */
    fun setStarLanguageFilter(entry: LanguageEntry?) = _state.update {
        it.copy(filterState = it.filterState.copy(languageFilter = entry))
    }
    
    /** 清除所有筛选 */
    fun clearStarFilters() = _state.update { 
        it.copy(filterState = StarFilterState()) 
    }

    /** 按当前搜索词和筛选条件过滤排序后的星标仓库列表 */
    val filteredStarredRepos = _state.map { s ->
        filterAndSortStarredRepos(s.starredRepos, s.starSearchQuery, s.filterState)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

    fun clearToast() = _state.update { it.copy(toast = null) }
}
