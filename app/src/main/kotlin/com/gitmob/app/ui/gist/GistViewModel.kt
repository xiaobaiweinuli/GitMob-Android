package com.gitmob.app.ui.gist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.GistUpdateEventBus
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.GistListItem
import com.gitmob.app.data.model.GistPage
import com.gitmob.app.data.model.GistSort
import com.gitmob.app.data.model.GistVisibility
import com.gitmob.app.data.repository.GistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GistCategoryUiState(
    val items: List<GistListItem> = emptyList(),
    val nextCursor: String? = null,
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val loadedSort: GistSort? = null,
    val loadedVisibility: GistVisibility? = null,
)

data class GistUiState(
    val selectedCategory: GistCategory = GistCategory.ALL,
    val selectedSort: GistSort = GistSort.RECENTLY_UPDATED,
    val selectedVisibility: GistVisibility = GistVisibility.ALL,
    val all: GistCategoryUiState = GistCategoryUiState(),
    val original: GistCategoryUiState = GistCategoryUiState(),
    val forked: GistCategoryUiState = GistCategoryUiState(),
) {
    val current: GistCategoryUiState
        get() = when (selectedCategory) {
            GistCategory.ALL -> all
            GistCategory.ORIGINAL -> original
            GistCategory.FORKED -> forked
        }

    val items: List<GistListItem>
        get() = current.items
    val hasNextPage: Boolean
        get() = current.hasNextPage
    val isLoading: Boolean
        get() = current.isLoading
    val isRefreshing: Boolean
        get() = current.isRefreshing
    val isLoadingMore: Boolean
        get() = current.isLoadingMore
    val loadFailed: Boolean
        get() = current.loadFailed
}

@HiltViewModel
class GistViewModel @Inject constructor(
    private val gistRepository: GistRepository,
    private val errorEventBus: ErrorEventBus,
    private val savedStateHandle: SavedStateHandle,
    private val gistUpdateEventBus: GistUpdateEventBus,
) : ViewModel() {

    private companion object {
        const val CATEGORY_KEY = "gist.selectedCategory"
        const val SORT_KEY = "gist.selectedSort"
        const val VISIBILITY_KEY = "gist.selectedVisibility"
    }

    private val _state = MutableStateFlow(
        GistUiState(
            selectedCategory = savedStateHandle.get<String>(CATEGORY_KEY)
                ?.let { value -> runCatching { GistCategory.valueOf(value) }.getOrNull() }
                ?: GistCategory.ALL,
            selectedSort = savedStateHandle.get<String>(SORT_KEY)
                ?.let { value -> runCatching { GistSort.valueOf(value) }.getOrNull() }
                ?: GistSort.RECENTLY_UPDATED,
            selectedVisibility = savedStateHandle.get<String>(VISIBILITY_KEY)
                ?.let { value -> runCatching { GistVisibility.valueOf(value) }.getOrNull() }
                ?: GistVisibility.ALL,
        ),
    )
    val state: StateFlow<GistUiState> = _state.asStateFlow()

    private var login: String? = null
    private var initialized = false
    private val requestGenerations = mutableMapOf<GistCategory, Long>()

    init {
        savedStateHandle[CATEGORY_KEY] = _state.value.selectedCategory.name
        savedStateHandle[SORT_KEY] = _state.value.selectedSort.name
        savedStateHandle[VISIBILITY_KEY] = _state.value.selectedVisibility.name
        viewModelScope.launch {
            gistUpdateEventBus.listEvents.collect { onListChanged() }
        }
    }

    fun init(login: String?) {
        if (initialized) return
        initialized = true
        this.login = login
        val current = _state.value
        loadFirstPage(current.selectedCategory, current.selectedSort, current.selectedVisibility, preserveItems = false)
    }

    fun selectCategory(category: GistCategory) {
        if (category == _state.value.selectedCategory) return
        savedStateHandle[CATEGORY_KEY] = category.name
        _state.update { it.copy(selectedCategory = category) }
        val current = _state.value
        if (current.current.loadedSort != current.selectedSort || current.current.loadedVisibility != current.selectedVisibility) {
            loadFirstPage(category, current.selectedSort, current.selectedVisibility, preserveItems = false)
        }
    }

    fun selectSort(sort: GistSort) {
        if (sort == _state.value.selectedSort) return
        savedStateHandle[SORT_KEY] = sort.name
        _state.update { it.copy(selectedSort = sort) }
        val current = _state.value
        loadFirstPage(current.selectedCategory, sort, current.selectedVisibility, preserveItems = false)
    }

    fun selectVisibility(visibility: GistVisibility) {
        if (visibility == _state.value.selectedVisibility) return
        savedStateHandle[VISIBILITY_KEY] = visibility.name
        _state.update { it.copy(selectedVisibility = visibility) }
        val current = _state.value
        loadFirstPage(current.selectedCategory, current.selectedSort, visibility, preserveItems = false)
    }

    fun refresh() {
        val current = _state.value
        val category = current.selectedCategory
        val sort = current.selectedSort
        loadFirstPage(category, sort, current.selectedVisibility, preserveItems = true, forceFresh = true)
    }

    fun retry() {
        val current = _state.value
        loadFirstPage(current.selectedCategory, current.selectedSort, current.selectedVisibility, preserveItems = false)
    }

    fun loadMore() {
        val snapshot = _state.value
        val category = snapshot.selectedCategory
        val sort = snapshot.selectedSort
        val visibility = snapshot.selectedVisibility
        val bucket = snapshot.current
        val cursor = bucket.nextCursor
        if (
            bucket.isLoading ||
            bucket.isRefreshing ||
            bucket.isLoadingMore ||
            !bucket.hasNextPage ||
            bucket.loadedSort != sort ||
            bucket.loadedVisibility != visibility ||
            cursor == null
        ) return

        val generation = nextRequestGeneration(category)
        updateBucket(category) { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val result = gistRepository.getGists(login, category, sort, cursor, visibility)
            if (!isCurrentRequest(category, sort, visibility, generation, cursor)) return@launch
            when (result) {
                is ApiResult.Success -> applyNextPage(category, result.data)
                is ApiResult.Failure -> {
                    updateBucket(category) { it.copy(isLoadingMore = false) }
                    if (_state.value.selectedCategory == category) errorEventBus.emit(result.error)
                }
            }
        }
    }

    private fun onListChanged() {
        if (!initialized) return
        val selected = _state.value.selectedCategory
        _state.update { state ->
            state.copy(
                all = if (selected == GistCategory.ALL) state.all else GistCategoryUiState(),
                original = if (selected == GistCategory.ORIGINAL) state.original else GistCategoryUiState(),
                forked = if (selected == GistCategory.FORKED) state.forked else GistCategoryUiState(),
            )
        }
        refresh()
    }

    private fun loadFirstPage(
        category: GistCategory,
        sort: GistSort,
        visibility: GistVisibility,
        preserveItems: Boolean,
        forceFresh: Boolean = false,
    ) {
        val current = _state.value.bucket(category)
        if (
            current.loadedSort == sort && current.loadedVisibility == visibility &&
            (current.isLoading || current.isRefreshing)
        ) return
        val generation = nextRequestGeneration(category)
        updateBucket(category) {
            it.copy(
                items = if (preserveItems) it.items else emptyList(),
                nextCursor = if (preserveItems) it.nextCursor else null,
                hasNextPage = preserveItems && it.hasNextPage,
                isLoading = !preserveItems,
                isRefreshing = preserveItems,
                isLoadingMore = false,
                loadFailed = false,
                loadedSort = sort,
                loadedVisibility = visibility,
            )
        }

        viewModelScope.launch {
            val result = if (forceFresh) {
                gistRepository.getGistsFresh(login, category, sort, visibility)
            } else {
                gistRepository.getGists(login, category, sort, after = null, visibility = visibility)
            }
            if (!isCurrentRequest(category, sort, visibility, generation)) return@launch
            when (result) {
                is ApiResult.Success -> applyFirstPage(category, result.data)
                is ApiResult.Failure -> {
                    updateBucket(category) {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            loadFailed = it.items.isEmpty(),
                        )
                    }
                    if (_state.value.selectedCategory == category) errorEventBus.emit(result.error)
                }
            }
        }
    }

    private fun applyFirstPage(category: GistCategory, page: GistPage) {
        updateBucket(category) {
            it.copy(
                items = page.items,
                nextCursor = page.nextCursor,
                hasNextPage = page.hasNextPage,
                isLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                loadFailed = false,
            )
        }
    }

    private fun applyNextPage(
        category: GistCategory,
        page: GistPage,
    ) {
        updateBucket(category) {
            it.copy(
                items = (it.items + page.items).distinctBy(GistListItem::id),
                nextCursor = page.nextCursor,
                hasNextPage = page.hasNextPage,
                isLoadingMore = false,
            )
        }
    }

    private fun updateBucket(
        category: GistCategory,
        transform: (GistCategoryUiState) -> GistCategoryUiState,
    ) {
        _state.update { it.withBucket(category, transform) }
    }

    private fun nextRequestGeneration(category: GistCategory): Long {
        val generation = (requestGenerations[category] ?: 0L) + 1
        requestGenerations[category] = generation
        return generation
    }

    private fun isCurrentRequest(
        category: GistCategory,
        sort: GistSort,
        visibility: GistVisibility,
        generation: Long,
        cursor: String? = null,
    ): Boolean {
        val bucket = _state.value.bucket(category)
        return requestGenerations[category] == generation &&
            bucket.loadedSort == sort && bucket.loadedVisibility == visibility &&
            (cursor == null || (bucket.nextCursor == cursor && bucket.isLoadingMore))
    }
}

private fun GistUiState.bucket(category: GistCategory): GistCategoryUiState = when (category) {
    GistCategory.ALL -> all
    GistCategory.ORIGINAL -> original
    GistCategory.FORKED -> forked
}

private fun GistUiState.withBucket(
    category: GistCategory,
    transform: (GistCategoryUiState) -> GistCategoryUiState,
): GistUiState = when (category) {
    GistCategory.ALL -> copy(all = transform(all))
    GistCategory.ORIGINAL -> copy(original = transform(original))
    GistCategory.FORKED -> copy(forked = transform(forked))
}
