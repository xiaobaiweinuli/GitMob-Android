package com.gitmob.app.ui.shortcuts

import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.LauncherShortcut
import com.gitmob.app.data.model.ShortcutPublishResult
import com.gitmob.app.data.repository.LauncherShortcutRepository
import com.gitmob.app.ui.common.AccountViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ShortcutViewModel @Inject constructor(
    private val repository: LauncherShortcutRepository,
    private val errors: ErrorEventBus,
    session: AccountSession,
) : AccountViewModel(session) {
    val state = repository.state
    private val acting = MutableStateFlow(false)
    val busy = combine(acting, repository.busy) { local, shared -> local || shared }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private var refreshJob: Job? = null
    private var pendingPin: String? = null

    init {
        viewModelScope.launch {
            repository.pinConfirmed.collect { requestId ->
                if (pendingPin == requestId) {
                    pendingPin = null
                    errors.emitNotice(R.string.shortcuts_pinned)
                }
            }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true || !accountEpoch.isCurrent) return
        refreshJob = viewModelScope.launch {
            _failed.value = false
            when (val result = repository.refresh()) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> { _failed.value = true; errors.emit(result.error) }
            }
        }
    }

    fun add(item: LauncherShortcut, replaceId: String? = null, done: (ShortcutPublishResult) -> Unit) = action(done) {
        repository.add(item, replaceId).also {
            if (it is ApiResult.Success && it.data == ShortcutPublishResult.Saved) errors.emitNotice(R.string.shortcuts_saved)
        }
    }

    fun pin(item: LauncherShortcut, done: (ShortcutPublishResult) -> Unit) = action(done) {
        val requestId = UUID.randomUUID().toString()
        pendingPin = requestId
        repository.pin(item, requestId).also {
            if (it !is ApiResult.Success || it.data != ShortcutPublishResult.PinRequested) pendingPin = null
            if (it is ApiResult.Success && it.data == ShortcutPublishResult.PinRequested && pendingPin == requestId) errors.emitNotice(R.string.shortcuts_pin_requested)
        }
    }

    fun rename(item: LauncherShortcut, title: String, done: () -> Unit) = action<Unit>({ done() }) {
        repository.rename(item.id, title)
    }

    fun remove(item: LauncherShortcut, done: () -> Unit) = action<Unit>({ done() }) { repository.remove(item.id) }

    fun reorder(ids: List<String>, done: (Boolean) -> Unit) {
        if (acting.value || repository.busy.value || !accountEpoch.isCurrent) { done(false); return }
        action<Unit>({ done(true) }, failed = { done(false) }) { repository.reorder(ids) }
    }

    fun settingsUnavailable() {
        viewModelScope.launch { errors.emit(ApiError.UserVisible(R.string.shortcuts_settings_unavailable)) }
    }

    private fun <T> action(done: (T) -> Unit, failed: () -> Unit = {}, block: suspend () -> ApiResult<T>) {
        if (acting.value || repository.busy.value || !accountEpoch.isCurrent) return
        acting.value = true
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    is ApiResult.Success -> { accountEpoch.ensureCurrent(); done(result.data) }
                    is ApiResult.Failure -> { accountEpoch.ensureCurrent(); failed(); errors.emit(result.error) }
                }
            } finally { acting.value = false }
        }
    }
}
