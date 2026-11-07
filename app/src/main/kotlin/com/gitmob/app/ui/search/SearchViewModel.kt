package com.gitmob.app.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.data.local.search.SearchHistoryStore
import com.gitmob.app.data.model.SearchCategory
import com.gitmob.app.data.repository.SearchRepository
import com.gitmob.app.ui.common.AccountViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val historyStore: SearchHistoryStore,
    private val errors: ErrorEventBus,
    session: AccountSession,
    private val savedStateHandle: SavedStateHandle,
) : AccountViewModel(session) {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state = mutableState.asStateFlow()
    private val requests = mutableMapOf<SearchCategory, Job>()
    private var historyJob: Job? = null
    private var initialized = false

    fun init(query: String? = null, category: SearchCategory? = null) {
        if (initialized) return
        initialized = true
        if (category == null) {
            observeHistory()
            savedStateHandle.get<String>("submittedQuery")?.let { submit(it, recordHistory = false) }
        }
        else if (query != null) submit(query, category, recordHistory = false)
    }

    fun observeHistory() {
        historyJob?.cancel()
        mutableState.update { it.copy(historyFailed = false) }
        historyJob = viewModelScope.launch {
            val result = safeCall {
                historyStore.observe(accountEpoch).collect { values ->
                    mutableState.update { it.copy(history = values, historyFailed = false) }
                }
            }
            if (result is ApiResult.Failure) {
                mutableState.update { it.copy(historyFailed = true) }
                errors.emit(result.error)
            }
        }
    }

    fun recordQuery(query: String) {
        viewModelScope.launch {
            val result = historyStore.record(accountEpoch, query)
            if (result is ApiResult.Failure) errors.emit(result.error)
        }
    }

    fun submit(query: String, category: SearchCategory? = null, recordHistory: Boolean = true) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        val current = state.value
        if (current.submittedQuery == normalized && current.category == category && current.sections.values.any { it.loading }) return
        requests.values.forEach(Job::cancel)
        requests.clear()
        val categories = category?.let(::listOf) ?: SearchCategory.entries
        mutableState.value = current.copy(
            submittedQuery = normalized, category = category, generation = current.generation + 1,
            sections = categories.associateWith { SearchSectionState() },
        )
        if (category == null) savedStateHandle["submittedQuery"] = normalized
        if (recordHistory) recordQuery(normalized)
        categories.forEach { request(it, append = false) }
    }

    fun retry(category: SearchCategory) {
        val section = state.value.sections[category] ?: return
        if (section.error == null || section.loading) return
        request(category, append = section.page?.next != null && state.value.category != null)
    }

    fun loadMore() {
        val category = state.value.category ?: return
        if (state.value.sections[category]?.canLoadMore != true) return
        request(category, append = true)
    }

    private fun request(category: SearchCategory, append: Boolean) {
        val current = state.value
        val previous = current.sections[category] ?: return
        if (previous.loading || (append && previous.page?.next == null)) return
        val generation = current.generation
        mutableState.update { it.copy(sections = it.sections + (category to previous.copy(loading = true, error = null))) }
        requests[category] = viewModelScope.launch {
            val result = repository.search(current.submittedQuery, category,
                preview = current.category == null, continuation = if (append) previous.page?.next else null)
            if (!accountEpoch.isCurrent || state.value.generation != generation) return@launch
            val next = when (result) {
                is ApiResult.Success -> {
                    val page = result.data
                    val items = ((if (append) previous.page?.items.orEmpty() else emptyList()) + page.items)
                        .distinctBy { it.key }
                    SearchSectionState(page.copy(
                        items = items, incomplete = page.incomplete || (append && previous.page?.incomplete == true),
                    ), completedPages = previous.completedPages + 1)
                }
                is ApiResult.Failure -> previous.copy(loading = false, error = result.error)
            }
            mutableState.update { it.copy(sections = it.sections + (category to next)) }
            if (result is ApiResult.Failure) errors.emit(result.error)
        }
    }

    fun clearHistory() {
        if (state.value.clearingHistory) return
        mutableState.update { it.copy(clearingHistory = true) }
        viewModelScope.launch {
            val result = historyStore.clear(accountEpoch)
            mutableState.update { it.copy(clearingHistory = false) }
            if (result is ApiResult.Failure) errors.emit(result.error)
            else if (state.value.historyFailed) observeHistory()
        }
    }
}
