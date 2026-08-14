package com.gitmob.app.ui.stars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.PagedStarredRepos
import com.gitmob.app.data.model.StarFilter
import com.gitmob.app.data.model.StarredRepo
import com.gitmob.app.data.model.UserListSummary
import com.gitmob.app.data.repository.StarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StarsUiState(
    val lists: List<UserListSummary> = emptyList(),
    val selectedFilter: StarFilter = StarFilter.All,
    val repos: List<StarredRepo> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val hasNextPage: Boolean = false,
    val listsSectionExpanded: Boolean = true,
    val showCreateListDialog: Boolean = false,
    val isCreatingList: Boolean = false,
    val editingList: UserListSummary? = null,
    val isSavingListEdit: Boolean = false,
    // "添加到列表"弹窗状态
    val addToListTarget: StarredRepo? = null,
    val addToListSelection: Set<String> = emptySet(),
    val isLoadingAddToListSelection: Boolean = false,
    val isSavingAddToList: Boolean = false,
)

@HiltViewModel
class StarsViewModel @Inject constructor(
    private val starRepository: StarRepository,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(StarsUiState())
    val state: StateFlow<StarsUiState> = _state.asStateFlow()

    private var endCursor: String? = null
    private var loadedOnce = false

    init {
        observeRepoUpdates()
    }

    /**
     * 仓库详情页星标切换后同步：取消星标的话从当前列表移除（不管当前在哪个筛选态），
     * 重新标星的话只更新已存在项的计数，不会凭一个事件就把完整卡片插进列表——
     * 那需要完整的仓库信息，事件里只有 owner/name/count，插入交给下次刷新处理。
     */
    private fun observeRepoUpdates() {
        viewModelScope.launch {
            repoUpdateEventBus.events
                .filterIsInstance<RepoUpdateEvent.StarChanged>()
                .collect { event ->
                    _state.update { state ->
                        if (!event.isStarred) {
                            state.copy(repos = state.repos.filterNot { it.ownerLogin == event.owner && it.name == event.name })
                        } else {
                            state.copy(repos = state.repos.map {
                                if (it.ownerLogin == event.owner && it.name == event.name) {
                                    it.copy(stargazerCount = event.stargazerCount)
                                } else it
                            })
                        }
                    }
                }
        }
    }

    fun loadIfNeeded() {
        if (loadedOnce) return
        loadedOnce = true
        loadLists()
        loadRepos()
    }

    private fun loadLists() {
        viewModelScope.launch {
            when (val result = starRepository.getLists()) {
                is ApiResult.Success -> _state.update { it.copy(lists = result.data) }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun selectFilter(filter: StarFilter) {
        if (_state.value.selectedFilter == filter) return
        _state.update { it.copy(selectedFilter = filter, repos = emptyList()) }
        loadRepos()
    }

    fun loadRepos() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            applyFirstPage(fetch(after = null))
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            refreshLists()
            val result = when (val filter = _state.value.selectedFilter) {
                StarFilter.All -> starRepository.getAllViewerStarredFresh()
                is StarFilter.ByList -> starRepository.getListItems(filter.list.id, after = null)
            }
            applyFirstPage(result)
        }
    }

    /** 下拉刷新列表 chip 时绕过列表缓存，确保数量和名称都是最新值。 */
    private suspend fun refreshLists() {
        when (val result = starRepository.getListsFresh()) {
            is ApiResult.Success -> _state.update { it.copy(lists = result.data) }
            is ApiResult.Failure -> errorEventBus.emit(result.error)
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = fetch(after = endCursor)) {
                is ApiResult.Success -> {
                    endCursor = result.data.endCursor
                    _state.update {
                        it.copy(repos = it.repos + result.data.items, hasNextPage = result.data.hasNextPage, isLoadingMore = false)
                    }
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    fun retry() = loadRepos()

    fun toggleListsSectionExpanded() {
        _state.update { it.copy(listsSectionExpanded = !it.listsSectionExpanded) }
    }

    // ---- 编辑 / 删除列表 ----

    fun openEditListDialog(list: UserListSummary) = _state.update { it.copy(editingList = list) }
    fun dismissEditListDialog() = _state.update { it.copy(editingList = null) }

    fun saveListEdit(name: String, description: String?, isPrivate: Boolean) {
        val editing = _state.value.editingList ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSavingListEdit = true) }
            when (val result = starRepository.updateList(editing.id, name, description, isPrivate)) {
                is ApiResult.Success -> _state.update { state ->
                    state.copy(
                        lists = state.lists.map { if (it.id == editing.id) result.data else it },
                        isSavingListEdit = false,
                        editingList = null,
                    )
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isSavingListEdit = false) }
                }
            }
        }
    }

    fun deleteList(listId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isSavingListEdit = true) }
            when (val result = starRepository.deleteList(listId)) {
                is ApiResult.Success -> _state.update { state ->
                    val stillOnDeletedList = (state.selectedFilter as? StarFilter.ByList)?.list?.id == listId
                    state.copy(
                        lists = state.lists.filterNot { it.id == listId },
                        isSavingListEdit = false,
                        editingList = null,
                        selectedFilter = if (stillOnDeletedList) StarFilter.All else state.selectedFilter,
                    )
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isSavingListEdit = false) }
                }
            }
            if ((_state.value.selectedFilter as? StarFilter.ByList)?.list?.id == null) loadRepos()
        }
    }

    // ---- 取消星标 ----

    fun unstarRepo(repo: StarredRepo) {
        viewModelScope.launch {
            when (val result = starRepository.unstarRepo(repo.id)) {
                is ApiResult.Success -> {
                    _state.update { state ->
                        state.copy(repos = state.repos.filterNot { it.id == repo.id })
                    }
                    repoUpdateEventBus.emit(
                        RepoUpdateEvent.StarChanged(
                            owner = repo.ownerLogin,
                            name = repo.name,
                            isStarred = false,
                            stargazerCount = (repo.stargazerCount - 1).coerceAtLeast(0),
                        ),
                    )
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    private suspend fun fetch(after: String?): ApiResult<PagedStarredRepos> =
        when (val filter = _state.value.selectedFilter) {
            StarFilter.All -> starRepository.getViewerStarred(after = after)
            is StarFilter.ByList -> starRepository.getListItems(filter.list.id, after)
        }

    private fun applyFirstPage(result: ApiResult<PagedStarredRepos>) {
        when (result) {
            is ApiResult.Success -> {
                endCursor = result.data.endCursor
                _state.update {
                    it.copy(
                        repos = result.data.items, totalCount = result.data.totalCount,
                        hasNextPage = result.data.hasNextPage,
                        isLoading = false, isRefreshing = false, loadFailed = false,
                    )
                }
            }
            is ApiResult.Failure -> {
                viewModelScope.launch { errorEventBus.emit(result.error) }
                _state.update { it.copy(isLoading = false, isRefreshing = false, loadFailed = it.repos.isEmpty()) }
            }
        }
    }

    // ---- 新建列表 ----

    fun openCreateListDialog() = _state.update { it.copy(showCreateListDialog = true) }
    fun dismissCreateListDialog() = _state.update { it.copy(showCreateListDialog = false) }

    fun createList(name: String, description: String?, isPrivate: Boolean) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isCreatingList = true) }
            when (val result = starRepository.createList(name, description, isPrivate)) {
                is ApiResult.Success -> _state.update {
                    it.copy(lists = it.lists + result.data, isCreatingList = false, showCreateListDialog = false)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isCreatingList = false) }
                }
            }
        }
    }

    // ---- 添加到列表 ----

    fun openAddToList(repo: StarredRepo) {
        _state.update { it.copy(addToListTarget = repo, isLoadingAddToListSelection = true, addToListSelection = emptySet()) }
        viewModelScope.launch {
            when (val result = starRepository.getListsContaining(repo.id)) {
                is ApiResult.Success -> _state.update {
                    it.copy(addToListSelection = result.data, isLoadingAddToListSelection = false)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingAddToListSelection = false) }
                }
            }
        }
    }

    fun toggleListSelection(listId: String) {
        _state.update { state ->
            val newSelection = if (listId in state.addToListSelection) {
                state.addToListSelection - listId
            } else {
                state.addToListSelection + listId
            }
            state.copy(addToListSelection = newSelection)
        }
    }

    fun dismissAddToList() = _state.update { it.copy(addToListTarget = null) }

    fun confirmAddToList() {
        val target = _state.value.addToListTarget ?: return
        val selection = _state.value.addToListSelection
        viewModelScope.launch {
            _state.update { it.copy(isSavingAddToList = true) }
            when (val result = starRepository.updateListsForItem(target.id, selection)) {
                is ApiResult.Success -> _state.update {
                    it.copy(isSavingAddToList = false, addToListTarget = null)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isSavingAddToList = false) }
                }
            }
            loadLists() // 列表内的数量可能变了，刷新一下 chip 计数
        }
    }
}
