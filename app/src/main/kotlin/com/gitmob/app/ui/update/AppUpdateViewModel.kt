package com.gitmob.app.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.BuildConfig
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.preferences.AppUpdatePreferenceStore
import com.gitmob.app.data.model.AppUpdate
import com.gitmob.app.data.model.AppUpdateCheck
import com.gitmob.app.data.model.AppUpdatePreferences
import com.gitmob.app.data.repository.AppUpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppUpdateState(
    val preferences: AppUpdatePreferences? = null,
    val preferenceFailed: Boolean = false,
    val savingAutomatic: Boolean = false,
    val checking: Boolean = false,
    val update: AppUpdate? = null,
    val savingIgnore: Boolean = false,
    val downloading: Boolean = false,
)

/** Activity 持有。网络与偏好不依赖账号，只有手动操作的提示绑定发起时的会话。 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val repository: AppUpdateRepository,
    private val store: AppUpdatePreferenceStore,
    private val errors: ErrorEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(AppUpdateState())
    val state = _state.asStateFlow()
    private var preferenceJob: Job? = null
    private var appReady = false
    private var startupEvaluated = false
    private var request: CheckRequest? = null
    private var automaticDialog = false
    private var pendingAutomatic: Boolean? = null

    init { observePreferences() }

    fun retryPreferences() {
        if (preferenceJob?.isActive != true) observePreferences()
    }

    private fun observePreferences() {
        _state.update { it.copy(preferenceFailed = false) }
        preferenceJob = viewModelScope.launch {
            store.preferences.catch {
                _state.update { it.copy(preferenceFailed = true) }
            }.collect { preferences ->
                _state.update { it.copy(preferences = preferences, preferenceFailed = false) }
                if (automaticDialog && (!preferences.automatic || preferences.ignoredVersionCode == state.value.update?.versionCode)) {
                    dismiss()
                }
                checkAtStartup()
            }
        }
    }

    fun onAppReady() {
        appReady = true
        checkAtStartup()
    }

    private fun checkAtStartup() {
        val preferences = state.value.preferences ?: return
        if (!appReady || startupEvaluated) return
        startupEvaluated = true
        val now = System.currentTimeMillis()
        val last = preferences.lastSuccessfulCheckAt
        if (preferences.automatic && (last <= 0 || now < last || now - last >= CHECK_INTERVAL)) {
            startCheck(null)
        }
    }

    fun checkManually(epoch: AccountEpoch) {
        if (!epoch.isCurrent) return
        startCheck(epoch)
    }

    private fun startCheck(manualEpoch: AccountEpoch?) {
        request?.let { active ->
            if (manualEpoch != null) active.manualEpoch = manualEpoch
            return
        }
        val active = CheckRequest(manualEpoch)
        request = active
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            try {
                val result = repository.check(BuildConfig.VERSION_CODE)
                if (result is ApiResult.Success) {
                    // 时间戳写入失败不丢弃本次有效结果，下次启动仍可再检查。
                    safeCall { store.recordSuccessfulCheck(System.currentTimeMillis()) }
                }
                val feedbackEpoch = active.manualEpoch?.takeIf { it.isCurrent }
                when (result) {
                    is ApiResult.Success -> when (val checked = result.data) {
                        is AppUpdateCheck.Available -> {
                            val preferences = state.value.preferences
                            val showAutomatically = active.manualEpoch == null &&
                                (pendingAutomatic ?: preferences?.automatic) == true &&
                                preferences?.ignoredVersionCode != checked.update.versionCode
                            if (feedbackEpoch != null || showAutomatically) {
                                automaticDialog = feedbackEpoch == null
                                _state.update { it.copy(update = checked.update) }
                            }
                        }
                        AppUpdateCheck.UpToDate -> feedbackEpoch?.let { errors.emitNotice(R.string.app_update_up_to_date, it) }
                        AppUpdateCheck.NoCompatibleRelease -> feedbackEpoch?.let { errors.emitNotice(R.string.app_update_no_package, it) }
                    }
                    is ApiResult.Failure -> feedbackEpoch?.let { errors.emit(result.error, it) }
                }
            } finally {
                request = null
                _state.update { it.copy(checking = false) }
            }
        }
    }

    fun setAutomatic(enabled: Boolean, epoch: AccountEpoch) {
        if (!epoch.isCurrent || state.value.preferences == null || state.value.savingAutomatic) return
        pendingAutomatic = enabled
        _state.update { it.copy(savingAutomatic = true) }
        if (!enabled && automaticDialog) dismiss()
        viewModelScope.launch {
            try {
                when (val result = safeCall { store.setAutomatic(enabled) }) {
                    is ApiResult.Success -> _state.update { it.copy(preferences = result.data) }
                    is ApiResult.Failure -> if (epoch.isCurrent) errors.emit(result.error, epoch)
                }
            } finally {
                pendingAutomatic = null
                _state.update { it.copy(savingAutomatic = false) }
            }
        }
    }

    fun dismiss() {
        if (state.value.savingIgnore || state.value.downloading) return
        automaticDialog = false
        _state.update { it.copy(update = null) }
    }

    fun ignore(epoch: AccountEpoch) {
        val update = state.value.update ?: return
        if (!epoch.isCurrent || state.value.savingIgnore || state.value.downloading) return
        _state.update { it.copy(savingIgnore = true) }
        viewModelScope.launch {
            try {
                when (val result = safeCall { store.ignore(update.versionCode) }) {
                    is ApiResult.Success -> {
                        automaticDialog = false
                        _state.update { it.copy(preferences = result.data, update = null) }
                        if (epoch.isCurrent) errors.emitNotice(R.string.app_update_ignored, epoch)
                    }
                    is ApiResult.Failure -> if (epoch.isCurrent) errors.emit(result.error, epoch)
                }
            } finally {
                _state.update { it.copy(savingIgnore = false) }
            }
        }
    }

    fun download(epoch: AccountEpoch) {
        val update = state.value.update ?: return
        if (!epoch.isCurrent || state.value.savingIgnore || state.value.downloading) return
        _state.update { it.copy(downloading = true) }
        viewModelScope.launch {
            try {
                when (val result = repository.download(update)) {
                    is ApiResult.Success -> {
                        automaticDialog = false
                        _state.update { it.copy(update = null) }
                    }
                    is ApiResult.Failure -> if (epoch.isCurrent) errors.emit(result.error, epoch)
                }
            } finally {
                _state.update { it.copy(downloading = false) }
            }
        }
    }

    private class CheckRequest(var manualEpoch: AccountEpoch?)
    private companion object { const val CHECK_INTERVAL = 24 * 60 * 60 * 1_000L }
}
