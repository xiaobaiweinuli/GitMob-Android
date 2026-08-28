package com.gitmob.app.ui.branches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.RepoBranch
import com.gitmob.app.data.model.BranchCreationSpec
import com.gitmob.app.data.repository.RepoDetailRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BranchesUiState(
    val branches: List<RepoBranch> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasNextPage: Boolean = false,
    val loadFailed: Boolean = false,
    val isCreating: Boolean = false,
)

@HiltViewModel
class BranchesViewModel @Inject constructor(
    private val repoDetailRepository: RepoDetailRepository,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(BranchesUiState())
    val state: StateFlow<BranchesUiState> = _state.asStateFlow()

    private var initialized = false
    private lateinit var owner: String
    private lateinit var name: String
    private var endCursor: String? = null

    fun init(owner: String, name: String) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = repoDetailRepository.getBranches(owner, name)) {
                is ApiResult.Success -> {
                    endCursor = result.data.endCursor
                    _state.update {
                        it.copy(
                            branches = result.data.items,
                            hasNextPage = result.data.hasNextPage,
                            isLoading = false,
                            loadFailed = false,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoading = false, loadFailed = true) }
                }
            }
        }
    }

    /**
     * 加载下一页分支。
     * 防重入：正在 loadMore 或没有下一页时直接返回。
     */
    fun loadMore() {
        if (_state.value.isLoadingMore || !_state.value.hasNextPage) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = repoDetailRepository.getBranches(owner, name, after = endCursor)) {
                is ApiResult.Success -> {
                    endCursor = result.data.endCursor
                    _state.update {
                        it.copy(
                            branches = it.branches + result.data.items,
                            hasNextPage = result.data.hasNextPage,
                            isLoadingMore = false,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    fun retry() = load()

    fun createBranch(
        newBranchName: String,
        spec: BranchCreationSpec,
        onFinished: (Boolean) -> Unit = {},
    ) {
        if (_state.value.isCreating) return
        viewModelScope.launch {
            _state.update { it.copy(isCreating = true) }
            when (val result = repoDetailRepository.createBranch(owner, name, newBranchName, spec)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(isCreating = false) }
                    load()
                    onFinished(true)
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(isCreating = false) }
                    errorEventBus.emit(result.error)
                    onFinished(false)
                }
            }
        }
    }

    fun renameBranch(branchName: String, newBranchName: String, onFinished: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            when (val result = repoDetailRepository.renameBranch(owner, name, branchName, newBranchName)) {
                is ApiResult.Success -> {
                    load()
                    onFinished(true)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    onFinished(false)
                }
            }
        }
    }

    /**
     * 切换分支：不需要发网络请求，纯本地状态广播——通过 RepoUpdateEventBus 通知
     * 仓库详情页（以及其它任何关心这个仓库当前分支的地方）联动更新，
     * 不需要 Nav3 的"导航结果回传"机制，两个 ViewModel 完全解耦。
     */
    fun switchBranch(ref: String) {
        viewModelScope.launch {
            repoUpdateEventBus.emit(RepoUpdateEvent.BranchSwitched(owner, name, ref))
        }
    }

    fun setDefaultBranch(branchName: String) {
        viewModelScope.launch {
            when (val result = repoDetailRepository.setDefaultBranch(owner, name, branchName)) {
                is ApiResult.Success -> load() // 重新拉一次分支列表，isDefault 标记会跟着变
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    fun deleteBranch(refId: String) {
        viewModelScope.launch {
            when (val result = repoDetailRepository.deleteBranch(refId, owner, name)) {
                // Repository 已主动失效缓存，直接走 load() 重新拉取（避免缓存命中后仍显示旧列表）
                is ApiResult.Success -> load()
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }
}
