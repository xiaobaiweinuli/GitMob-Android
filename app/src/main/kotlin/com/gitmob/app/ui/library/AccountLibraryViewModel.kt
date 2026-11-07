package com.gitmob.app.ui.library

import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.local.library.LibraryRepository
import com.gitmob.app.data.model.FavoriteGroup
import com.gitmob.app.data.model.HistoryLimitResult
import com.gitmob.app.data.model.LibrarySettings
import com.gitmob.app.ui.common.AccountViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Null means not read yet, rather than a usable default or an empty result.
data class LibrarySettingsState(val value: LibrarySettings? = null, val failed: Boolean = false)
data class FavoriteGroupsState(val value: List<FavoriteGroup>? = null, val failed: Boolean = false)
data class HistoryCountState(val value: Int? = null, val failed: Boolean = false)
enum class LibrarySettingOperation { Favorites, History, Limit, Clear }

/** Owned by the account's parent ViewModelStore, never by an individual navigation entry. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountLibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val errors: ErrorEventBus,
    session: AccountSession,
) : AccountViewModel(session) {
    private val _settings = MutableStateFlow(LibrarySettingsState())
    val settings = _settings.asStateFlow()
    private var settingsJob: Job? = null
    private val groupRetry = MutableStateFlow(0)
    private val countRetry = MutableStateFlow(0)
    private val _pendingSettings = MutableStateFlow<Set<LibrarySettingOperation>>(emptySet())
    val pendingSettings = _pendingSettings.asStateFlow()

    // Only summaries are retained across page visits. No repository or Gist payloads live here.
    val groups: StateFlow<FavoriteGroupsState> = groupRetry.flatMapLatest {
        var latest = groups.value.copy(failed = false)
        repository.observeGroups(accountEpoch)
            .map { accountEpoch.ensureCurrent(); FavoriteGroupsState(it).also { value -> latest = value } }
            .onStart { emit(latest) }
            .catch {
                accountEpoch.ensureCurrent()
                emit(latest.copy(failed = true))
                errors.emit(ApiError.UserVisible(R.string.library_load_failed))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoriteGroupsState())

    val historyCount: StateFlow<HistoryCountState> = countRetry.flatMapLatest {
        var latest = historyCount.value.copy(failed = false)
        repository.observeHistoryCount(accountEpoch)
            .map { accountEpoch.ensureCurrent(); HistoryCountState(it).also { value -> latest = value } }
            .onStart { emit(latest) }
            .catch {
                accountEpoch.ensureCurrent()
                emit(latest.copy(failed = true))
                errors.emit(ApiError.UserVisible(R.string.library_load_failed))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryCountState())

    init { retrySettings() }

    fun retrySettings() {
        if (!accountEpoch.isCurrent) return
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            _settings.update { it.copy(failed = false) }
            repository.observeSettings(accountEpoch).catch {
                accountEpoch.ensureCurrent()
                _settings.update { it.copy(failed = true) }
                errors.emit(ApiError.UserVisible(R.string.library_load_failed))
            }.collect { accountEpoch.ensureCurrent(); _settings.value = LibrarySettingsState(it) }
        }
    }

    fun retryGroups() { if (accountEpoch.isCurrent) groupRetry.update { it + 1 } }
    fun retryHistoryCount() { if (accountEpoch.isCurrent) countRetry.update { it + 1 } }
    fun favoritesEnabled(value: Boolean) = setting(LibrarySettingOperation.Favorites) { repository.setFavoritesEnabled(accountEpoch, value) }
    fun historyEnabled(value: Boolean) = setting(LibrarySettingOperation.History) { repository.setHistoryEnabled(accountEpoch, value) }
    fun historyLimit(value: Int, confirmTrim: Boolean, done: (HistoryLimitResult) -> Unit) =
        setting(LibrarySettingOperation.Limit, done) { repository.setHistoryLimit(accountEpoch, value, confirmTrim) }
    fun clearHistory(done: () -> Unit) = setting(LibrarySettingOperation.Clear, { _: Unit -> done() }) { repository.clearHistory(accountEpoch) }

    private fun <T> setting(operation: LibrarySettingOperation, done: (T) -> Unit = {}, block: suspend () -> ApiResult<T>) {
        if (operation in _pendingSettings.value || settings.value.value == null || !accountEpoch.isCurrent) return
        _pendingSettings.update { it + operation }
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    is ApiResult.Success -> { accountEpoch.ensureCurrent(); done(result.data) }
                    is ApiResult.Failure -> { accountEpoch.ensureCurrent(); errors.emit(result.error) }
                }
            } finally { _pendingSettings.update { it - operation } }
        }
    }
}
