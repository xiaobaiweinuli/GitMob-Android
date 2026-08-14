package com.gitmob.app.ui.gist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.GistListItem
import com.gitmob.app.data.model.GistPage
import com.gitmob.app.data.model.GistSort
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
)

data class GistUiState(
    val selectedCategory: GistCategory = GistCategory.ORIGINAL,
    val selectedSort: GistSort = GistSort.RECENTLY_UPDATED,
    val original: GistCategoryUiState = GistCategoryUiState(),
    val forked: GistCategoryUiState = GistCategoryUiState(),
) {
    val current: GistCategoryUiState
        get() = when (selectedCategory) {
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
) : ViewModel() {

    private companion object {
        const val CATEGORY_KEY = "gist.selectedCategory"
        const val SORT_KEY = "gist.selectedSort"
    }

    private val _state = MutableStateFlow(
        GistUiState(
            selectedCategory = savedStateHandle.get<String>(CATEGORY_KEY)
                ?.let { value -> runCatching { GistCategory.valueOf(value) }.getOrNull() }
                ?: GistCategory.ORIGINAL,
            selectedSort = savedStateHandle.get<String>(SORT_KEY)
                ?.let { value -> runCatching { GistSort.valueOf(value) }.getOrNull() }
                ?: GistSort.RECENTLY_UPDATED,
        ),
    )
    val state: StateFlow<GistUiState> = _state.asStateFlow()

    private var login: String? = null
    private var initialized = false

    init {
        savedStateHandle[CATEGORY_KEY] = _state.value.selectedCategory.name
        savedStateHandle[SORT_KEY] = _state.value.selectedSort.name
    }

    fun init(login: String?) {
        if (initialized) return
        initialized = true
        this.login = login
        loadFirstPage(_state.value.selectedCategory, _state.value.selectedSort, preserveItems = false)
    }

    fun selectCategory(category: GistCategory) {
        if (category == _state.value.selectedCategory) return
        savedStateHandle[CATEGORY_KEY] = category.name
        _state.update { it.copy(selectedCategory = category) }
        val sort = _state.value.selectedSort
        if (_state.value.current.loadedSort != sort) {
            loadFirstPage(category, sort, preserveItems = false)
        }
    }

    fun selectSort(sort: GistSort) {
        if (sort == _state.value.selectedSort) return
        savedStateHandle[SORT_KEY] = sort.name
        val category = _state.value.selectedCategory
        _state.update { state ->
            state.copy(selectedSort = sort).withBucket(category) {
                GistCategoryUiState(loadedSort = sort)
            }
        }
        loadFirstPage(category, sort, preserveItems = false)
    }

    fun refresh() {
        val current = _state.value
        val category = current.selectedCategory
        val sort = current.selectedSort
        loadFirstPage(category, sort, preserveItems = true, forceFresh = true)
    }

    fun retry() {
        val current = _state.value
        loadFirstPage(current.selectedCategory, current.selectedSort, preserveItems = false)
    }

    fun loadMore() {
        val snapshot = _state.value
        val category = snapshot.selectedCategory
        val sort = snapshot.selectedSort
        val bucket = snapshot.current
        val cursor = bucket.nextCursor
        if (
            bucket.isLoading ||
            bucket.isRefreshing ||
            bucket.isLoadingMore ||
            !bucket.hasNextPage ||
            bucket.loadedSort != sort ||
            cursor == null
        ) return

        updateBucket(category) { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            when (val result = gistRepository.getGists(login, category, sort, cursor)) {
                is ApiResult.Success -> applyNextPage(category, sort, cursor, result.data)
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    updateBucketIfCurrent(category, sort, cursor) { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    private fun loadFirstPage(
        category: GistCategory,
        sort: GistSort,
        preserveItems: Boolean,
        forceFresh: Boolean = false,
    ) {
        val current = _state.value.bucket(category)
        if (current.isLoading || current.isRefreshing) return
        updateBucket(category) {
            it.copy(
                items = if (preserveItems) it.items else emptyList(),
                nextCursor = null,
                hasNextPage = false,
                isLoading = !preserveItems,
                isRefreshing = preserveItems,
                isLoadingMore = false,
                loadFailed = false,
                loadedSort = sort,
            )
        }

        viewModelScope.launch {
            val result = if (forceFresh) {
                gistRepository.getGistsFresh(login, category, sort)
            } else {
                gistRepository.getGists(login, category, sort, after = null)
            }
            when (result) {
                is ApiResult.Success -> applyFirstPage(category, sort, result.data)
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    updateBucketIfSortCurrent(category, sort) {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            loadFailed = it.items.isEmpty(),
                        )
                    }
                }
            }
        }
    }

    private fun applyFirstPage(category: GistCategory, sort: GistSort, page: GistPage) {
        updateBucketIfSortCurrent(category, sort) {
            it.copy(
                items = page.items,
                nextCursor = page.nextCursor,
                hasNextPage = page.hasNextPage,
                isLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                loadFailed = false,
                loadedSort = sort,
            )
        }
    }

    private fun applyNextPage(
        category: GistCategory,
        sort: GistSort,
        cursor: String,
        page: GistPage,
    ) {
        updateBucketIfCurrent(category, sort, cursor) {
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

    private fun updateBucketIfSortCurrent(
        category: GistCategory,
        sort: GistSort,
        transform: (GistCategoryUiState) -> GistCategoryUiState,
    ) {
        _state.update { state ->
            if (state.bucket(category).loadedSort == sort) state.withBucket(category, transform) else state
        }
    }

    private fun updateBucketIfCurrent(
        category: GistCategory,
        sort: GistSort,
        cursor: String,
        transform: (GistCategoryUiState) -> GistCategoryUiState,
    ) {
        _state.update { state ->
            val bucket = state.bucket(category)
            if (bucket.loadedSort == sort && bucket.nextCursor == cursor && bucket.isLoadingMore) {
                state.withBucket(category, transform)
            } else {
                state
            }
        }
    }
}

private fun GistUiState.bucket(category: GistCategory): GistCategoryUiState = when (category) {
    GistCategory.ORIGINAL -> original
    GistCategory.FORKED -> forked
}

private fun GistUiState.withBucket(
    category: GistCategory,
    transform: (GistCategoryUiState) -> GistCategoryUiState,
): GistUiState = when (category) {
    GistCategory.ORIGINAL -> copy(original = transform(original))
    GistCategory.FORKED -> copy(forked = transform(forked))
}
