package com.gitmob.app.ui.repodetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.data.model.RepoDetail
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.data.repository.RepoRepository
import com.gitmob.app.data.repository.StarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class RepoDetailUiState(
    val detail: RepoDetail? = null,
    val readmeHtml: String? = null,
    val readmeTruncated: Boolean = false,
    val isLoadingReadme: Boolean = false,
    val currentRef: String? = null, // null 表示还没加载出 defaultBranchRef
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
)

@HiltViewModel
class RepoDetailViewModel @Inject constructor(
    private val repoDetailRepository: RepoDetailRepository,
    private val repoRepository: RepoRepository,
    private val starRepository: StarRepository,
    private val markdownRenderer: MarkdownRenderer,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    private val _state = MutableStateFlow(RepoDetailUiState())
    val state: StateFlow<RepoDetailUiState> = _state.asStateFlow()

    private var initialized = false
    private lateinit var owner: String
    private lateinit var name: String

    fun init(owner: String, name: String) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        load()
        observeRepoUpdates()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = repoDetailRepository.getRepoDetail(owner, name)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(detail = result.data, isLoading = false, currentRef = result.data.defaultBranchName) }
                    loadReadme()
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isLoading = false, loadFailed = true) }
                }
            }
        }
    }

    fun retry() = load()

    /**
     * 订阅仓库更新事件——BranchesViewModel 切分支时会 emit BranchSwitched，
     * 这里按 owner+name 过滤后联动更新 currentRef 并重新加载 README。
     * 两个 ViewModel 完全不互相引用、不共享实例，只通过事件总线解耦通信，
     * 见 core/event/RepoUpdateEventBus.kt。
     */
    private fun observeRepoUpdates() {
        viewModelScope.launch {
            repoUpdateEventBus.events
                .filterIsInstance<RepoUpdateEvent.BranchSwitched>()
                .collect { event ->
                    if (event.owner == owner && event.name == name) {
                        _state.update { it.copy(currentRef = event.ref) }
                        loadReadme()
                    }
                }
        }
        viewModelScope.launch {
            repoUpdateEventBus.events
                .filterIsInstance<RepoUpdateEvent.IssueCountChanged>()
                .collect { event ->
                    if (event.owner == owner && event.name == name) {
                        _state.update { state -> state.copy(detail = state.detail?.copy(openIssueCount = event.openIssueCount)) }
                    }
                }
        }
    }

    private fun loadReadme() {
        val ref = _state.value.currentRef ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingReadme = true) }
            when (val result = repoDetailRepository.getReadme(owner, name, ref)) {
                is ApiResult.Success -> {
                    val markdown = result.data.markdown
                    val html = markdown?.let { source ->
                        // 大型 README 的 CommonMark 解析和 HTML 生成不应占用主线程。
                        withContext(Dispatchers.Default) {
                            markdownRenderer.renderToHtml(source)
                        }
                    }
                    _state.update {
                        it.copy(readmeHtml = html, readmeTruncated = result.data.isTruncated, isLoadingReadme = false)
                    }
                }
                is ApiResult.Failure -> {
                    // README 拿不到不算整页失败（很多仓库确实没有 README），静默处理即可
                    _state.update { it.copy(readmeHtml = null, isLoadingReadme = false) }
                }
            }
        }
    }

    fun toggleStar() {
        val detail = _state.value.detail ?: return
        viewModelScope.launch {
            val result = if (detail.viewerHasStarred) {
                starRepository.unstarRepo(detail.id)
            } else {
                starRepository.starRepo(detail.id)
            }
            when (result) {
                is ApiResult.Success -> {
                    val newStarred = !detail.viewerHasStarred
                    val newCount = detail.stargazerCount + if (detail.viewerHasStarred) -1 else 1
                    _state.update { state ->
                        state.copy(detail = state.detail?.copy(viewerHasStarred = newStarred, stargazerCount = newCount))
                    }
                    // 广播给"仓库"/"星标"两个 Tab 的卡片列表，让它们同步更新，不用各自重新拉取
                    repoUpdateEventBus.emit(RepoUpdateEvent.StarChanged(owner, name, newStarred, newCount))
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }
}
