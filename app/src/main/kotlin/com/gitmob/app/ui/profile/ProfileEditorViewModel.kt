package com.gitmob.app.ui.profile

import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.error.displayMessageRes
import com.gitmob.app.data.model.EditableProfile
import com.gitmob.app.data.model.ProfileDraft
import com.gitmob.app.data.model.ProfileField
import com.gitmob.app.data.repository.UserRepository
import com.gitmob.app.ui.common.AccountViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileEditorUiState(
    val original: EditableProfile? = null,
    val draft: ProfileDraft = ProfileDraft(),
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
    val validatedFields: Set<ProfileField> = emptySet(),
    val focusRequest: Int = 0,
    val focusField: ProfileField? = null,
    val saveErrorRes: Int? = null,
    val saveStatusRes: Int? = null,
) {
    val hasChanges: Boolean get() = original != null && draft != original.values
    val canSave: Boolean get() = hasChanges && !isLoading && !isSaving
    val errors: Map<ProfileField, Int> get() = draft.validationErrors().filterKeys { it in validatedFields }
}

@HiltViewModel
class ProfileEditorViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val errorEventBus: ErrorEventBus,
    session: AccountSession,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(ProfileEditorUiState())
    val state: StateFlow<ProfileEditorUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        if (!accountEpoch.isCurrent || _state.value.isLoading || _state.value.original != null) return
        _state.update { it.copy(isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            val result = userRepository.getEditableProfile()
            currentCoroutineContext().ensureActive()
            accountEpoch.ensureCurrent()
            when (result) {
                is ApiResult.Success -> _state.value = ProfileEditorUiState(original = result.data, draft = result.data.values)
                is ApiResult.Failure -> {
                    _state.update { it.copy(isLoading = false, loadFailed = true) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }

    fun update(field: ProfileField, value: String) {
        if (!editable()) return
        _state.update { it.copy(draft = it.draft.withValue(field, value), saveErrorRes = null, saveStatusRes = null) }
    }

    fun setHireable(value: Boolean) {
        if (!editable()) return
        _state.update { it.copy(draft = it.draft.copy(hireable = value), saveErrorRes = null, saveStatusRes = null) }
    }

    fun validate(field: ProfileField) {
        if (!editable()) return
        _state.update { it.copy(validatedFields = it.validatedFields + field) }
    }

    fun save() {
        val started = _state.value
        if (!accountEpoch.isCurrent || !started.canSave) return
        val errors = started.draft.validationErrors()
        if (errors.isNotEmpty()) {
            _state.update { it.copy(
                validatedFields = ProfileField.entries.toSet(),
                focusField = ProfileField.entries.first { field -> field in errors },
                focusRequest = it.focusRequest + 1,
            ) }
            return
        }
        _state.update { it.copy(isSaving = true, saveErrorRes = null, saveStatusRes = null) }
        viewModelScope.launch {
            val result = userRepository.saveEditableProfile(checkNotNull(started.original), started.draft)
            currentCoroutineContext().ensureActive()
            accountEpoch.ensureCurrent()
            when (result) {
                is ApiResult.Success -> {
                    val status = if (result.data.localSyncFailed) R.string.profile_local_sync_failed else R.string.profile_saved
                    // GitHub 响应才是新基线，不能把用户输入假装成服务器已经保存的值。
                    _state.value = ProfileEditorUiState(original = result.data.profile, draft = result.data.profile.values, saveStatusRes = status)
                    errorEventBus.emitNotice(status)
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(isSaving = false, saveErrorRes = result.error.displayMessageRes()) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }

    private fun editable(): Boolean = accountEpoch.isCurrent && _state.value.original != null && !_state.value.isSaving
}
