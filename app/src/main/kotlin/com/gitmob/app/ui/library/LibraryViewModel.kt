package com.gitmob.app.ui.library

import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.local.library.LibraryRepository
import com.gitmob.app.data.model.LibraryState
import com.gitmob.app.data.model.LibraryContent
import com.gitmob.app.data.model.HistoryItem
import com.gitmob.app.data.model.FavoriteItem
import com.gitmob.app.ui.common.AccountViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val errors: ErrorEventBus,
    session: AccountSession,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null

    private var content: LibraryContent? = null

    fun show(value: LibraryContent) {
        if (content == value && loadJob != null) return
        content = value
        _state.value = LibraryState()
        load()
    }

    fun load() {
        val requested = content ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _failed.value = false
            repository.observe(accountEpoch, requested).catch {
                accountEpoch.ensureCurrent()
                _failed.value = true
                errors.emit(ApiError.UserVisible(R.string.library_load_failed))
            }.collect { accountEpoch.ensureCurrent(); _state.value = it }
        }
    }

    fun createGroup(name: String, done: (String) -> Unit) = action({ done(it.id) }) { repository.createGroup(accountEpoch, name) }
    fun renameGroup(id: String, name: String, done: () -> Unit) = change(done) { repository.renameGroup(accountEpoch, id, name) }
    fun deleteGroup(id: String, remove: Boolean, done: () -> Unit) = change(done) { repository.deleteGroup(accountEpoch, id, remove) }
    fun reorderGroups(ids: List<String>, done: (Boolean) -> Unit) = reorder(done) { repository.reorderGroups(accountEpoch, ids) }
    fun saveFavorite(item: FavoriteItem, group: String, done: () -> Unit) = change(done) { repository.saveFavorite(accountEpoch, item, group) }
    fun removeFavorite(item: FavoriteItem, done: () -> Unit) = change(done) { repository.removeFavorite(accountEpoch, item) }
    fun reorderFavorites(group: String, ids: List<String>, done: (Boolean) -> Unit) = reorder(done) { repository.reorderFavorites(accountEpoch, group, ids) }
    fun deleteVisit(id: String, done: () -> Unit) = change(done) { repository.deleteVisit(accountEpoch, id) }

    fun deleteVisit(item: HistoryItem, done: () -> Unit) = change(done) {
        when (item) {
            is HistoryItem.Repository -> repository.deleteVisit(accountEpoch, item.id)
            is HistoryItem.Gist -> repository.deleteGistVisit(accountEpoch, item.id)
        }
    }

    private fun reorder(done: (Boolean) -> Unit, block: suspend () -> ApiResult<Unit>) {
        if (_busy.value || !accountEpoch.isCurrent) { done(false); return }
        _busy.value = true
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    is ApiResult.Success -> { accountEpoch.ensureCurrent(); done(true) }
                    is ApiResult.Failure -> { accountEpoch.ensureCurrent(); errors.emit(result.error); done(false) }
                }
            } finally { _busy.value = false }
        }
    }

    private fun change(done: () -> Unit = {}, block: suspend () -> ApiResult<Unit>) = action({ done() }, block)
    private fun <T> action(done: (T) -> Unit, block: suspend () -> ApiResult<T>) {
        if (_busy.value || !accountEpoch.isCurrent) return
        _busy.value = true
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    is ApiResult.Success -> { accountEpoch.ensureCurrent(); done(result.data) }
                    is ApiResult.Failure -> { accountEpoch.ensureCurrent(); errors.emit(result.error) }
                }
            } finally { _busy.value = false }
        }
    }
}
