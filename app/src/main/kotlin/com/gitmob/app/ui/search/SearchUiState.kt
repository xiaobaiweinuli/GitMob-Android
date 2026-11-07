package com.gitmob.app.ui.search

import com.gitmob.app.core.error.ApiError
import com.gitmob.app.data.model.SearchCategory
import com.gitmob.app.data.model.SearchPage

/** Each category owns its data and request state, including when shown in the overview. */
data class SearchSectionState(
    val page: SearchPage? = null,
    val loading: Boolean = false,
    val error: ApiError? = null,
    val completedPages: Int = 0,
) {
    val canLoadMore: Boolean get() = !loading && error == null && page?.next != null
}

data class SearchUiState(
    val submittedQuery: String = "",
    val category: SearchCategory? = null,
    val generation: Long = 0,
    val sections: Map<SearchCategory, SearchSectionState> = emptyMap(),
    val history: List<String> = emptyList(),
    val historyFailed: Boolean = false,
    val clearingHistory: Boolean = false,
)
