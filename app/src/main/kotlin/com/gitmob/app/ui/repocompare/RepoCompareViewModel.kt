package com.gitmob.app.ui.repocompare

import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.RepoComparison
import com.gitmob.app.data.model.RepoComparisonRefs
import com.gitmob.app.data.model.RepoComparisonResult
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.ui.common.AccountViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoCompareUiState(
    val comparison: RepoComparison? = null,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val noCommonAncestor: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailed: Boolean = false,
)

@HiltViewModel
class RepoCompareViewModel @Inject constructor(
    private val repository: RepoGitRepository,
    private val errorEventBus: ErrorEventBus,
    session: AccountSession,
) : AccountViewModel(session) {
    private val _state = MutableStateFlow(RepoCompareUiState())
    val state = _state.asStateFlow()
    private var requestedRefs: RepoComparisonRefs? = null
    private var resolvedRefs: RepoComparisonRefs? = null
    private var loadJob: Job? = null

    fun init(owner: String, name: String, base: String, head: String) {
        if (requestedRefs != null) return
        requestedRefs = RepoComparisonRefs(owner, name, base, owner, name, head)
        retry()
    }

    fun retry() {
        val requested = requestedRefs ?: return
        if (!accountEpoch.isCurrent || loadJob?.isActive == true || _state.value.comparison != null) return
        _state.update { it.copy(isLoading = true, loadFailed = false, noCommonAncestor = false) }
        loadJob = viewModelScope.launch {
            val refs = resolvedRefs ?: when (val result = repository.resolveComparisonRefs(
                requested.baseOwner, requested.baseRepository, requested.baseRef, requested.headRef,
            )) {
                is ApiResult.Success -> result.data.also { resolvedRefs = it }
                is ApiResult.Failure -> {
                    fail(result.error)
                    return@launch
                }
            }
            when (val result = compare(refs, 1)) {
                is ApiResult.Success -> if (accountEpoch.isCurrent) {
                    when (val value = result.data) {
                        is RepoComparisonResult.Available -> _state.update {
                            it.copy(comparison = value.comparison.copy(commits = value.comparison.commits.distinctBy { commit -> commit.oid }), isLoading = false)
                        }
                        RepoComparisonResult.NoCommonAncestor -> _state.update { it.copy(isLoading = false, noCommonAncestor = true) }
                    }
                }
                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val comparison = current.comparison ?: return
        val refs = resolvedRefs ?: return
        if (!accountEpoch.isCurrent || current.isLoadingMore || current.loadMoreFailed || !comparison.commitsHasNextPage) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            when (val result = compare(refs, comparison.commitsPage + 1)) {
                is ApiResult.Success -> if (accountEpoch.isCurrent) {
                    when (val value = result.data) {
                        is RepoComparisonResult.Available -> _state.update {
                            val page = value.comparison
                            it.copy(
                                comparison = comparison.copy(
                                    commits = (comparison.commits + page.commits).distinctBy { commit -> commit.oid },
                                    commitsPage = page.commitsPage,
                                    commitsHasNextPage = page.commitsHasNextPage && page.commits.isNotEmpty(),
                                ),
                                isLoadingMore = false,
                            )
                        }
                        RepoComparisonResult.NoCommonAncestor -> fail(ApiError.UserVisible(R.string.compare_no_common_ancestor), more = true)
                    }
                }
                is ApiResult.Failure -> fail(result.error, more = true)
            }
        }
    }

    fun retryMore() {
        if (!_state.value.loadMoreFailed) return
        _state.update { it.copy(loadMoreFailed = false) }
        loadMore()
    }

    private suspend fun compare(refs: RepoComparisonRefs, page: Int) = repository.compare(
        refs.baseOwner, refs.baseRepository, refs.baseRef,
        refs.headOwner, refs.headRepository, refs.headRef, page,
    )

    private suspend fun fail(error: ApiError, more: Boolean = false) {
        if (!accountEpoch.isCurrent) return
        errorEventBus.emit(error, accountEpoch)
        _state.update {
            if (more) it.copy(isLoadingMore = false, loadMoreFailed = true)
            else it.copy(isLoading = false, loadFailed = true)
        }
    }
}
