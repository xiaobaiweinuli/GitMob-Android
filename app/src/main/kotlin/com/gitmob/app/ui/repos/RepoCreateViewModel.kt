package com.gitmob.app.ui.repos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.CreatedRepository
import com.gitmob.app.data.model.RepositoryCreateInput
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerPage
import com.gitmob.app.data.model.RepositoryCreateOwnerType
import com.gitmob.app.data.model.RepositoryLicense
import com.gitmob.app.data.repository.RepoRepository
import com.gitmob.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RepoCreatePicker {
    OWNER,
    GITIGNORE,
    LICENSE,
}

data class RepoCreateUiState(
    val activePicker: RepoCreatePicker? = null,
    val owner: RepositoryCreateOwner? = null,
    val draftOwner: RepositoryCreateOwner? = null,
    val owners: List<RepositoryCreateOwner> = emptyList(),
    val ownersEndCursor: String? = null,
    val ownersHasNextPage: Boolean = false,
    val isLoadingOwners: Boolean = false,
    val ownersLoadFailed: Boolean = false,
    val name: String = "",
    val description: String = "",
    val isPrivate: Boolean = false,
    val addReadme: Boolean = false,
    val license: RepositoryLicense? = null,
    val draftLicense: RepositoryLicense? = null,
    val licenses: List<RepositoryLicense> = emptyList(),
    val licenseQuery: String = "",
    val isLoadingLicenses: Boolean = false,
    val licensesLoadFailed: Boolean = false,
    val gitignore: String? = null,
    val draftGitignore: String? = null,
    val gitignoreTemplates: List<String> = emptyList(),
    val gitignoreQuery: String = "",
    val isLoadingGitignore: Boolean = false,
    val gitignoreLoadFailed: Boolean = false,
    val isCreating: Boolean = false,
)

@HiltViewModel
class RepoCreateViewModel @Inject constructor(
    private val repoRepository: RepoRepository,
    private val userRepository: UserRepository,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    private var initialized = false

    private val _state = MutableStateFlow(RepoCreateUiState())
    val state: StateFlow<RepoCreateUiState> = _state.asStateFlow()

    private val _createdEvents = MutableSharedFlow<CreatedRepository>(extraBufferCapacity = 1)
    val createdEvents: SharedFlow<CreatedRepository> = _createdEvents.asSharedFlow()

    fun initialize(defaultOwner: RepositoryCreateOwner) {
        if (initialized) return
        initialized = true
        _state.value = RepoCreateUiState(owner = defaultOwner)
        loadOwners()
    }

    fun updateName(value: String) = _state.update { it.copy(name = value) }

    fun updateDescription(value: String) = _state.update { it.copy(description = value) }

    fun updatePrivate(value: Boolean) = _state.update { it.copy(isPrivate = value) }

    fun updateReadme(value: Boolean) = _state.update { it.copy(addReadme = value) }

    fun openOwnerPicker() {
        _state.update { it.copy(activePicker = RepoCreatePicker.OWNER, draftOwner = it.owner) }
        if (_state.value.owners.isEmpty() && !_state.value.isLoadingOwners) loadOwners()
    }

    fun selectOwner(owner: RepositoryCreateOwner) {
        if (owner.canCreateRepository) _state.update { it.copy(draftOwner = owner) }
    }

    fun confirmOwner() {
        _state.update {
            it.copy(
                owner = it.draftOwner ?: it.owner,
                activePicker = null,
                draftOwner = null,
            )
        }
    }

    fun openLicensePicker() {
        _state.update {
            it.copy(
                activePicker = RepoCreatePicker.LICENSE,
                draftLicense = it.license,
                licenseQuery = "",
            )
        }
        if (_state.value.licenses.isEmpty() && !_state.value.isLoadingLicenses) loadLicenses()
    }

    fun updateLicenseQuery(value: String) = _state.update { it.copy(licenseQuery = value) }

    fun selectLicense(license: RepositoryLicense?) = _state.update { it.copy(draftLicense = license) }

    fun confirmLicense() {
        _state.update {
            it.copy(
                license = it.draftLicense,
                activePicker = null,
                draftLicense = null,
            )
        }
    }

    fun openGitignorePicker() {
        _state.update {
            it.copy(
                activePicker = RepoCreatePicker.GITIGNORE,
                draftGitignore = it.gitignore,
                gitignoreQuery = "",
            )
        }
        if (_state.value.gitignoreTemplates.isEmpty() && !_state.value.isLoadingGitignore) loadGitignore()
    }

    fun updateGitignoreQuery(value: String) = _state.update { it.copy(gitignoreQuery = value) }

    fun selectGitignore(template: String?) = _state.update { it.copy(draftGitignore = template) }

    fun confirmGitignore() {
        _state.update {
            it.copy(
                gitignore = it.draftGitignore,
                activePicker = null,
                draftGitignore = null,
            )
        }
    }

    fun cancelPicker() {
        _state.update {
            it.copy(
                activePicker = null,
                draftOwner = null,
                draftLicense = null,
                draftGitignore = null,
            )
        }
    }

    fun loadMoreOwners() {
        val current = _state.value
        if (current.isLoadingOwners || !current.ownersHasNextPage) return
        val after = current.ownersEndCursor ?: return
        loadOwners(after)
    }

    fun retryOwners() {
        val current = _state.value
        val after = if (current.owners.isEmpty()) null else current.ownersEndCursor ?: return
        loadOwners(after)
    }

    fun retryLicenses() = loadLicenses()

    fun retryGitignore() = loadGitignore()

    fun create() {
        val current = _state.value
        val owner = current.owner ?: return
        if (current.isCreating || current.name.trim().isEmpty() || !owner.canCreateRepository) return
        viewModelScope.launch {
            _state.update { it.copy(isCreating = true) }
            val result = repoRepository.createRepository(
                RepositoryCreateInput(
                    owner = owner,
                    name = current.name.trim(),
                    description = current.description.trim().takeIf { it.isNotEmpty() },
                    isPrivate = current.isPrivate,
                    addReadme = current.addReadme,
                    licenseTemplate = current.license?.key,
                    gitignoreTemplate = current.gitignore,
                ),
            )
            when (result) {
                is ApiResult.Success -> {
                    if (owner.type == RepositoryCreateOwnerType.USER) {
                        userRepository.invalidateAllCaches()
                    }
                    repoUpdateEventBus.emit(RepoUpdateEvent.RepositoryCreated(result.data.owner, result.data.name))
                    _state.update { it.copy(isCreating = false) }
                    _createdEvents.emit(result.data)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isCreating = false) }
                }
            }
        }
    }

    private fun loadOwners(after: String? = null) {
        if (_state.value.isLoadingOwners || _state.value.owner == null) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingOwners = true, ownersLoadFailed = false) }
            when (val result = repoRepository.getRepositoryCreateOwners(after)) {
                is ApiResult.Success -> applyOwners(result.data)
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingOwners = false, ownersLoadFailed = true) }
                }
            }
        }
    }

    private fun applyOwners(page: RepositoryCreateOwnerPage) {
        _state.update { state ->
            val merged = (
                listOf(page.viewer) +
                    listOfNotNull(state.owner) +
                    state.owners +
                    page.organizations
                ).distinctBy { it.id }
            state.copy(
                owners = merged,
                ownersEndCursor = page.endCursor,
                ownersHasNextPage = page.hasNextPage,
                isLoadingOwners = false,
                ownersLoadFailed = false,
            )
        }
    }

    private fun loadLicenses() {
        if (_state.value.isLoadingLicenses) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingLicenses = true, licensesLoadFailed = false) }
            when (val result = repoRepository.getLicenseTemplates()) {
                is ApiResult.Success -> _state.update {
                    it.copy(licenses = result.data, isLoadingLicenses = false, licensesLoadFailed = false)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingLicenses = false, licensesLoadFailed = true) }
                }
            }
        }
    }

    private fun loadGitignore() {
        if (_state.value.isLoadingGitignore) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingGitignore = true, gitignoreLoadFailed = false) }
            when (val result = repoRepository.getGitignoreTemplates()) {
                is ApiResult.Success -> _state.update {
                    it.copy(gitignoreTemplates = result.data, isLoadingGitignore = false, gitignoreLoadFailed = false)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingGitignore = false, gitignoreLoadFailed = true) }
                }
            }
        }
    }
}
