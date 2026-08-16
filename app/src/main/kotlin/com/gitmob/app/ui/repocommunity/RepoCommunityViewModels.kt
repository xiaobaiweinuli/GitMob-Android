package com.gitmob.app.ui.repocommunity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.RepoContributor
import com.gitmob.app.data.model.RepoLicenseDocument
import com.gitmob.app.data.repository.RepoCommunityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoContributorsUiState(val items: List<RepoContributor> = emptyList(), val page: Int = 1, val hasNextPage: Boolean = false, val isLoading: Boolean = false, val loadFailed: Boolean = false)
@HiltViewModel class RepoContributorsViewModel @Inject constructor(private val repository: RepoCommunityRepository, private val errorEventBus: ErrorEventBus) : ViewModel() { private val _state = MutableStateFlow(RepoContributorsUiState()); val state: StateFlow<RepoContributorsUiState> = _state.asStateFlow(); private var owner = ""; private var name = ""; private var initialized = false; fun init(owner: String, name: String) { if (initialized) return; initialized = true; this.owner = owner; this.name = name; load() }; fun load() { viewModelScope.launch { _state.update { it.copy(isLoading = true, loadFailed = false) }; when (val r = repository.getContributors(owner, name)) { is ApiResult.Success -> _state.update { it.copy(items = r.data.first, page = 1, hasNextPage = r.data.second, isLoading = false) }; is ApiResult.Failure -> { errorEventBus.emit(r.error); _state.update { it.copy(isLoading = false, loadFailed = true) } } } } }; fun loadMore() { val s = _state.value; if (!s.hasNextPage) return; viewModelScope.launch { when (val r = repository.getContributors(owner, name, s.page + 1)) { is ApiResult.Success -> _state.update { it.copy(items = it.items + r.data.first, page = s.page + 1, hasNextPage = r.data.second) }; is ApiResult.Failure -> errorEventBus.emit(r.error) } } } }
data class RepoLicenseUiState(val document: RepoLicenseDocument? = null, val isLoading: Boolean = false, val loadFailed: Boolean = false)
@HiltViewModel class RepoLicenseViewModel @Inject constructor(private val repository: RepoCommunityRepository, private val errorEventBus: ErrorEventBus) : ViewModel() { private val _state = MutableStateFlow(RepoLicenseUiState()); val state: StateFlow<RepoLicenseUiState> = _state.asStateFlow(); private var owner = ""; private var name = ""; private var ref = ""; private var initialized = false; fun init(owner: String, name: String, ref: String) { if (initialized) return; initialized = true; this.owner = owner; this.name = name; this.ref = ref; load() }; fun load() { viewModelScope.launch { _state.update { it.copy(isLoading = true, loadFailed = false) }; when (val r = repository.getLicense(owner, name, ref)) { is ApiResult.Success -> _state.update { it.copy(document = r.data, isLoading = false) }; is ApiResult.Failure -> { errorEventBus.emit(r.error); _state.update { it.copy(isLoading = false, loadFailed = true) } } } } } }
