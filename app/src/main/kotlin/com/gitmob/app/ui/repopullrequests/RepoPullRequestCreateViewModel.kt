package com.gitmob.app.ui.repopullrequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.ExistingRepoPullRequest
import com.gitmob.app.data.model.PagedBranches
import com.gitmob.app.data.model.RepoBranch
import com.gitmob.app.data.model.RepoComparison
import com.gitmob.app.data.model.RepoComparisonResult
import com.gitmob.app.data.model.RepoDetail
import com.gitmob.app.data.model.RepoPullRequestCreateSelection
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.data.repository.RepoPullRequestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RepoPullRequestCreatePage { TARGET, COMPARE, SELECT_BASE, SELECT_HEAD, FILES, COMMITS }

data class RepoPullRequestCreateUiState(
    val page: RepoPullRequestCreatePage = RepoPullRequestCreatePage.COMPARE,
    val currentRepository: RepoDetail? = null,
    val baseRepository: RepoDetail? = null,
    val headRepository: RepoDetail? = null,
    val baseBranch: RepoBranch? = null,
    val headBranch: RepoBranch? = null,
    val branches: List<RepoBranch> = emptyList(),
    val branchSearch: String = "",
    val branchesHasNextPage: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingBranches: Boolean = false,
    val isLoadingMoreBranches: Boolean = false,
    val isComparing: Boolean = false,
    val isLoadingMoreCommits: Boolean = false,
    val loadFailed: Boolean = false,
    val branchLoadFailed: Boolean = false,
    val compareFailed: Boolean = false,
    val noCommonAncestor: Boolean = false,
    val sameBranch: Boolean = false,
    val comparison: RepoComparison? = null,
    val existingPullRequest: ExistingRepoPullRequest? = null,
) {
    val filteredBranches: List<RepoBranch>
        get() = branchSearch.trim().takeIf(String::isNotEmpty)?.let { query ->
            branches.filter { it.name.contains(query, ignoreCase = true) }
        } ?: branches

    val canCreate: Boolean
        get() = comparison?.aheadBy?.let { it > 0 } == true && existingPullRequest == null && !noCommonAncestor

    val selection: RepoPullRequestCreateSelection?
        get() {
            val baseRepo = baseRepository ?: return null
            val headRepo = headRepository ?: return null
            val base = baseBranch ?: return null
            val head = headBranch ?: return null
            if (!canCreate) return null
            return RepoPullRequestCreateSelection(
                baseRepo.ownerLogin, baseRepo.name, baseRepo.id, base.name,
                headRepo.ownerLogin, headRepo.name, headRepo.id, head.name,
            )
        }
}

@HiltViewModel
class RepoPullRequestCreateViewModel @Inject constructor(
    private val detailRepository: RepoDetailRepository,
    private val gitRepository: RepoGitRepository,
    private val pullRequestRepository: RepoPullRequestRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoPullRequestCreateUiState())
    val state: StateFlow<RepoPullRequestCreateUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var initialized = false
    private var branchCursor: String? = null
    private var compareJob: Job? = null
    private var loadJob: Job? = null
    private var branchJob: Job? = null
    private var targetJob: Job? = null

    fun init(owner: String, name: String) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        loadRepository()
    }

    fun loadRepository() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, loadFailed = false) }
        when (val result = detailRepository.getRepoDetail(owner, name)) {
            is ApiResult.Success -> {
                val current = result.data
                _state.update {
                    it.copy(
                        currentRepository = current,
                        isLoading = false,
                        page = if (current.isFork && current.forkedFromOwner != null && current.forkedFromName != null) RepoPullRequestCreatePage.TARGET else RepoPullRequestCreatePage.COMPARE,
                    )
                }
                if (!current.isFork) configureTarget(current)
            }
            is ApiResult.Failure -> {
                errorEventBus.emit(result.error)
                _state.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
        }
    }

    fun selectForkTarget(upstream: Boolean) {
        targetJob?.cancel()
        targetJob = viewModelScope.launch {
        val current = _state.value.currentRepository ?: return@launch
        if (!upstream) {
            configureTarget(current)
            return@launch
        }
        val upstreamOwner = current.forkedFromOwner ?: return@launch
        val upstreamName = current.forkedFromName ?: return@launch
        _state.update { it.copy(isLoading = true, loadFailed = false) }
        when (val result = detailRepository.getRepoDetail(upstreamOwner, upstreamName)) {
            is ApiResult.Success -> configureTarget(result.data, current)
            is ApiResult.Failure -> {
                errorEventBus.emit(result.error)
                _state.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
        }
    }

    private fun configureTarget(baseRepository: RepoDetail, headRepository: RepoDetail = baseRepository) {
        val default = baseRepository.defaultBranchName.orEmpty()
        _state.update {
            it.copy(
                page = RepoPullRequestCreatePage.COMPARE,
                baseRepository = baseRepository,
                headRepository = headRepository,
                baseBranch = default.takeIf(String::isNotBlank)?.let { name -> RepoBranch("base:$name", name, true, null) },
                headBranch = null,
                comparison = null,
                existingPullRequest = null,
                noCommonAncestor = false,
                sameBranch = false,
                compareFailed = false,
                isLoading = false,
            )
        }
    }

    fun openBaseBranches() = openBranches(RepoPullRequestCreatePage.SELECT_BASE)
    fun openHeadBranches() = openBranches(RepoPullRequestCreatePage.SELECT_HEAD)

    private fun openBranches(page: RepoPullRequestCreatePage) {
        _state.update { it.copy(page = page, branches = emptyList(), branchSearch = "", branchLoadFailed = false) }
        branchCursor = null
        loadBranches(false)
    }

    fun setBranchSearch(value: String) = _state.update { it.copy(branchSearch = value) }
    fun retryBranches() = loadBranches(false)
    fun loadMoreBranches() = loadBranches(true)

    private fun loadBranches(more: Boolean) {
        val state = _state.value
        if (state.isLoadingBranches || state.isLoadingMoreBranches || (more && !state.branchesHasNextPage)) return
        val repository = if (state.page == RepoPullRequestCreatePage.SELECT_BASE) state.baseRepository else state.headRepository
        repository ?: return
        branchJob?.cancel()
        branchJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingBranches = !more, isLoadingMoreBranches = more, branchLoadFailed = false) }
            when (val result = detailRepository.getBranches(repository.ownerLogin, repository.name, if (more) branchCursor else null)) {
                is ApiResult.Success -> applyBranches(result.data, more)
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingBranches = false, isLoadingMoreBranches = false, branchLoadFailed = true) }
                }
            }
        }
    }

    /** Clears the temporary creation session. A reopened sheet starts from a new selection flow. */
    fun resetCreateSession() {
        compareJob?.cancel()
        loadJob?.cancel()
        branchJob?.cancel()
        targetJob?.cancel()
        compareJob = null
        loadJob = null
        branchJob = null
        targetJob = null
        branchCursor = null
        initialized = false
        owner = ""
        name = ""
        _state.value = RepoPullRequestCreateUiState()
    }

    private fun applyBranches(page: PagedBranches, more: Boolean) {
        branchCursor = page.endCursor
        _state.update {
            it.copy(
                branches = if (more) (it.branches + page.items).distinctBy(RepoBranch::id) else page.items.sortedByDescending(RepoBranch::isDefault),
                branchesHasNextPage = page.hasNextPage,
                isLoadingBranches = false,
                isLoadingMoreBranches = false,
            )
        }
    }

    fun selectBranch(branch: RepoBranch) {
        val selectingBase = _state.value.page == RepoPullRequestCreatePage.SELECT_BASE
        _state.update { if (selectingBase) it.copy(baseBranch = branch, page = RepoPullRequestCreatePage.COMPARE) else it.copy(headBranch = branch, page = RepoPullRequestCreatePage.COMPARE) }
        compare()
    }

    fun showCompare() = _state.update { it.copy(page = RepoPullRequestCreatePage.COMPARE) }
    fun showFiles() = _state.update { it.copy(page = RepoPullRequestCreatePage.FILES) }
    fun showCommits() = _state.update { it.copy(page = RepoPullRequestCreatePage.COMMITS) }
    fun retryCompare() = compare()

    private fun compare() {
        compareJob?.cancel()
        val state = _state.value
        val baseRepo = state.baseRepository ?: return
        val headRepo = state.headRepository ?: return
        val base = state.baseBranch ?: return
        val head = state.headBranch ?: run {
            _state.update { it.copy(comparison = null, existingPullRequest = null, sameBranch = false, noCommonAncestor = false, compareFailed = false) }
            return
        }
        val same = baseRepo.ownerLogin == headRepo.ownerLogin && baseRepo.name == headRepo.name && base.name == head.name
        if (same) {
            _state.update { it.copy(comparison = null, existingPullRequest = null, sameBranch = true, noCommonAncestor = false, compareFailed = false, isComparing = false) }
            return
        }
        compareJob = viewModelScope.launch {
            _state.update { it.copy(isComparing = true, sameBranch = false, noCommonAncestor = false, compareFailed = false, comparison = null, existingPullRequest = null) }
            val comparison = async { gitRepository.compare(baseRepo.ownerLogin, baseRepo.name, base.name, headRepo.ownerLogin, headRepo.name, head.name) }
            val existing = async { pullRequestRepository.findOpenPullRequest(baseRepo.ownerLogin, baseRepo.name, base.name, headRepo.ownerLogin, head.name) }
            when (val result = comparison.await()) {
                is ApiResult.Success -> when (val value = result.data) {
                    is RepoComparisonResult.Available -> _state.update { it.copy(comparison = value.comparison, isComparing = false) }
                    RepoComparisonResult.NoCommonAncestor -> _state.update { it.copy(noCommonAncestor = true, isComparing = false) }
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isComparing = false, compareFailed = true) }
                }
            }
            when (val result = existing.await()) {
                is ApiResult.Success -> _state.update { it.copy(existingPullRequest = result.data) }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun loadMoreCommits() {
        val state = _state.value
        val comparison = state.comparison ?: return
        if (state.isLoadingMoreCommits || !comparison.commitsHasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMoreCommits = true) }
            when (val result = gitRepository.compare(
                comparison.refs.baseOwner, comparison.refs.baseRepository, comparison.refs.baseRef,
                comparison.refs.headOwner, comparison.refs.headRepository, comparison.refs.headRef,
                comparison.commitsPage + 1,
            )) {
                is ApiResult.Success -> {
                    val page = (result.data as? RepoComparisonResult.Available)?.comparison
                    if (page != null) _state.update {
                        it.copy(comparison = comparison.copy(
                            commits = (comparison.commits + page.commits).distinctBy { commit -> commit.oid },
                            commitsPage = page.commitsPage,
                            commitsHasNextPage = page.commitsHasNextPage,
                        ), isLoadingMoreCommits = false)
                    } else _state.update { it.copy(isLoadingMoreCommits = false) }
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingMoreCommits = false) }
                }
            }
        }
    }
}
