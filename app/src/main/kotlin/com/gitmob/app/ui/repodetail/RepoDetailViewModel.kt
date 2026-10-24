package com.gitmob.app.ui.repodetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.event.PendingRepositoryTransfer
import com.gitmob.app.core.event.RepoTransferPendingStore
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.core.share.ShareLinkLauncher
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.CreatedRepository
import com.gitmob.app.data.model.RepoDetail
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryTransferTarget
import com.gitmob.app.data.model.UserListSummary
import com.gitmob.app.data.model.areValidRepositoryTopics
import com.gitmob.app.data.model.isValidRepositoryName
import com.gitmob.app.data.model.isValidRepositoryWebsite
import com.gitmob.app.data.model.parseRepositoryTopics
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.data.repository.RepoRepository
import com.gitmob.app.data.repository.StarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
    val managementAction: RepoManagementAction? = null,
    val isManaging: Boolean = false,
    val isStarring: Boolean = false,
    val isForking: Boolean = false,
    val starLists: List<UserListSummary> = emptyList(),
    val starListMembership: Set<String> = emptySet(),
    val starListSelection: Set<String> = emptySet(),
    val starListsLoaded: Boolean = false,
    val showStarListSelector: Boolean = false,
    val isLoadingStarLists: Boolean = false,
    val isSavingStarLists: Boolean = false,
    val showCreateListDialog: Boolean = false,
    val isCreatingList: Boolean = false,
    val editName: String = "",
    val editDescription: String = "",
    val editWebsite: String = "",
    val editTopics: String = "",
    val topicsLoaded: Boolean = false,
    val isLoadingTopics: Boolean = false,
    val confirmationName: String = "",
    val subscription: String = "UNSUBSCRIBED",
    val transferTargets: List<RepositoryCreateOwner> = emptyList(),
    val transferHasNextPage: Boolean = false,
    val isLoadingTargets: Boolean = false,
    val targetsLoadFailed: Boolean = false,
    val selectedTransferLogin: String? = null,
    val transferOtherSelected: Boolean = false,
    val transferOtherLogin: String = "",
    val checkedTransferTarget: RepositoryTransferTarget? = null,
    val isCheckingTarget: Boolean = false,
    val showTransferConfirmation: Boolean = false,
    val transferPending: Boolean = false,
    val isCheckingTransfer: Boolean = false,
    val deleted: Boolean = false,
) {
    val actionsEnabled: Boolean
        get() = !isLoading && !isManaging && !isStarring && !isForking &&
            !isSavingStarLists && !isCreatingList &&
            !transferPending && !deleted
}

enum class RepoManagementAction { RENAME, INFORMATION, VISIBILITY, TRANSFER, ARCHIVE, NOTIFICATIONS, ISSUES, DISCUSSIONS, DELETE }

@HiltViewModel
class RepoDetailViewModel @Inject constructor(
    private val repoDetailRepository: RepoDetailRepository,
    private val repoRepository: RepoRepository,
    private val starRepository: StarRepository,
    private val markdownRenderer: MarkdownRenderer,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val errorEventBus: ErrorEventBus,
    private val shareLinkLauncher: ShareLinkLauncher,
    private val transferPendingStore: RepoTransferPendingStore,
) : ViewModel() {

    private val _state = MutableStateFlow(RepoDetailUiState())
    val state: StateFlow<RepoDetailUiState> = _state.asStateFlow()

    private var initialized = false
    private lateinit var owner: String
    private lateinit var name: String
    private var loadJob: Job? = null
    private var readmeJob: Job? = null
    private var topicsJob: Job? = null
    private var transferCheckJob: Job? = null
    private var starListsJob: Job? = null
    private var transferCursor: String? = null

    fun init(owner: String, name: String) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        observePendingTransfer()
        load()
        observeRepoUpdates()
    }

    fun load() {
        val state = _state.value
        if (state.deleted || state.isManaging || state.isStarring || state.showStarListSelector ||
            state.showCreateListDialog || state.isSavingStarLists || state.isCreatingList
        ) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            when (val result = repoDetailRepository.getRepoDetail(owner, name)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            detail = result.data,
                            isLoading = false,
                            currentRef = it.currentRef ?: result.data.defaultBranchName,
                            transferPending = transferPendingStore.find(result.data.id, owner, name) != null,
                        )
                    }
                    loadReadme()
                    loadStarLists(refresh = true)
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
            repoUpdateEventBus.events.collect { event ->
                if (event.owner != owner || event.name != name) return@collect
                when (event) {
                    is RepoUpdateEvent.WatchStateChanged -> _state.update {
                        it.copy(detail = it.detail?.copy(viewerSubscription = event.viewerSubscription, watcherCount = event.watcherCount))
                    }
                    is RepoUpdateEvent.RepositoryChanged -> _state.update { it.copy(detail = event.detail) }
                    is RepoUpdateEvent.BranchSynced -> if (event.ref == _state.value.currentRef) loadReadme()
                    else -> Unit
                }
            }
        }
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
        viewModelScope.launch {
            repoUpdateEventBus.events
                .filterIsInstance<RepoUpdateEvent.PullRequestCountChanged>()
                .collect { event ->
                    if (event.owner == owner && event.name == name) {
                        _state.update { state -> state.copy(detail = state.detail?.copy(openPrCount = event.openPrCount)) }
                    }
                }
        }
        viewModelScope.launch {
            repoUpdateEventBus.events
                .filterIsInstance<RepoUpdateEvent.DiscussionCountChanged>()
                .collect { event ->
                    if (event.owner == owner && event.name == name) {
                        _state.update { state ->
                            state.copy(detail = state.detail?.copy(openDiscussionCount = event.openDiscussionCount))
                        }
                    }
                }
        }
        viewModelScope.launch {
            repoUpdateEventBus.events
                .filterIsInstance<RepoUpdateEvent.ReleaseChanged>()
                .collect { event ->
                    if (event.owner == owner && event.name == name) {
                        if (event.releaseCount >= 0) {
                            _state.update { state ->
                                state.copy(
                                    detail = state.detail?.copy(
                                        releaseCount = event.releaseCount,
                                        latestReleaseName = event.latestReleaseName,
                                        latestReleaseTag = event.latestReleaseTag,
                                    ),
                                )
                            }
                        } else {
                            load()
                        }
                    }
                }
        }
    }

    private fun observePendingTransfer() {
        viewModelScope.launch {
            transferPendingStore.transfers.collect {
                val detail = _state.value.detail
                _state.update { state ->
                    state.copy(transferPending = transferPendingStore.find(detail?.id, owner, name) != null)
                }
            }
        }
    }

    private fun loadReadme() {
        val ref = _state.value.currentRef ?: return
        readmeJob?.cancel()
        readmeJob = viewModelScope.launch {
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
        val state = _state.value
        val detail = state.detail ?: return
        if (!state.actionsEnabled) return
        starListsJob?.cancel()
        _state.update { it.copy(isStarring = true, isLoadingStarLists = false, starListsLoaded = false) }
        viewModelScope.launch {
            try {
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
                        state.copy(
                            detail = state.detail?.copy(viewerHasStarred = newStarred, stargazerCount = newCount),
                        )
                    }
                    // 广播给"仓库"/"星标"两个 Tab 的卡片列表，让它们同步更新，不用各自重新拉取
                    repoUpdateEventBus.emit(RepoUpdateEvent.StarChanged(owner, name, newStarred, newCount))
                }
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
            } finally {
                _state.update { it.copy(isStarring = false) }
                loadStarLists()
            }
        }
    }

    fun openStarListSelector() {
        val state = _state.value
        val detail = state.detail ?: return
        if (!detail.viewerHasStarred || !state.actionsEnabled) return
        _state.update { it.copy(showStarListSelector = true, starListSelection = it.starListMembership) }
        loadStarLists()
    }

    private fun loadStarLists(refresh: Boolean = false) {
        val state = _state.value
        val detail = state.detail ?: return
        if (state.deleted) return
        if (!detail.viewerHasStarred) {
            starListsJob?.cancel()
            _state.update {
                it.copy(
                    starLists = emptyList(), starListMembership = emptySet(), starListSelection = emptySet(),
                    starListsLoaded = false, isLoadingStarLists = false,
                    showStarListSelector = false, showCreateListDialog = false,
                )
            }
            return
        }
        if (state.isLoadingStarLists || (state.starListsLoaded && !refresh)) return
        _state.update { it.copy(isLoadingStarLists = true, starListsLoaded = false) }
        starListsJob = viewModelScope.launch {
            val listsResult = starRepository.getLists()
            ensureActive()
            val lists = when (listsResult) {
                is ApiResult.Success -> listsResult.data
                is ApiResult.Failure -> {
                    _state.update { it.copy(showStarListSelector = false, isLoadingStarLists = false) }
                    errorEventBus.emit(listsResult.error)
                    return@launch
                }
            }
            val result = starRepository.getListsContaining(detail.id)
            ensureActive()
            when (result) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        starLists = lists, starListMembership = result.data,
                        starListSelection = result.data, starListsLoaded = true, isLoadingStarLists = false,
                    )
                }
                is ApiResult.Failure -> {
                    // Membership is a full-replacement write; close instead of risking removal after a failed prefetch.
                    _state.update { it.copy(showStarListSelector = false, isLoadingStarLists = false) }
                    errorEventBus.emit(result.error)
                }
            }
        }
    }

    fun dismissStarListSelector() {
        if (_state.value.isSavingStarLists || _state.value.isCreatingList) return
        _state.update {
            it.copy(showStarListSelector = false, showCreateListDialog = false, starListSelection = it.starListMembership)
        }
    }

    fun toggleStarList(listId: String) {
        val state = _state.value
        if (!state.showStarListSelector || !state.starListsLoaded || state.isLoadingStarLists ||
            state.isSavingStarLists || state.starLists.none { it.id == listId }
        ) return
        _state.update {
            it.copy(
                starListSelection = if (listId in it.starListSelection) {
                    it.starListSelection - listId
                } else {
                    it.starListSelection + listId
                },
            )
        }
    }

    fun saveStarLists() {
        val state = _state.value
        val detail = state.detail ?: return
        if (!detail.viewerHasStarred || !state.showStarListSelector || !state.starListsLoaded ||
            state.showCreateListDialog || state.isLoadingStarLists || state.isSavingStarLists || state.isCreatingList
        ) return
        _state.update { it.copy(isSavingStarLists = true) }
        viewModelScope.launch {
            when (val result = starRepository.updateListsForItem(detail.id, state.starListSelection)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(starListMembership = state.starListSelection, isSavingStarLists = false, showStarListSelector = false)
                    }
                    errorEventBus.emitNotice(R.string.repo_star_lists_saved)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isSavingStarLists = false) }
                }
            }
        }
    }

    fun openCreateListDialog() {
        val state = _state.value
        if (state.showStarListSelector && state.starListsLoaded && !state.isLoadingStarLists && !state.isSavingStarLists) {
            _state.update { it.copy(showCreateListDialog = true) }
        }
    }

    fun dismissCreateListDialog() {
        if (!_state.value.isCreatingList) _state.update { it.copy(showCreateListDialog = false) }
    }

    fun createList(name: String, description: String?, isPrivate: Boolean) {
        val state = _state.value
        if (name.isBlank() || !state.showStarListSelector || !state.showCreateListDialog || !state.starListsLoaded ||
            state.isLoadingStarLists || state.isSavingStarLists || state.isCreatingList
        ) return
        _state.update { it.copy(isCreatingList = true) }
        viewModelScope.launch {
            when (val result = starRepository.createList(name, description, isPrivate)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        starLists = it.starLists + result.data,
                        starListSelection = it.starListSelection + result.data.id,
                        showCreateListDialog = false,
                        isCreatingList = false,
                    )
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isCreatingList = false) }
                }
            }
        }
    }

    fun createFork() {
        val state = _state.value
        val detail = state.detail ?: return
        if (!state.actionsEnabled || detail.ownerIsViewer || !detail.forkingAllowed) return
        _state.update { it.copy(isForking = true) }
        viewModelScope.launch {
            try {
                when (val result = repoDetailRepository.createFork(owner, name)) {
                    is ApiResult.Success -> errorEventBus.emitNotice(R.string.repo_fork_accepted)
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally {
                _state.update { it.copy(isForking = false) }
            }
        }
    }

    fun share() {
        val detail = _state.value.detail ?: return
        runCatching { shareLinkLauncher.share(detail.url ?: "https://github.com/$owner/$name") }
            .onFailure { viewModelScope.launch { errorEventBus.emit(ApiError.Unknown(it.message.orEmpty())) } }
    }

    fun openManagement(action: RepoManagementAction) {
        val state = _state.value
        val detail = state.detail ?: return
        if (!state.actionsEnabled || !isAllowed(action, detail)) return
        transferCheckJob?.cancel()
        topicsJob?.cancel()
        _state.update {
            it.copy(
                managementAction = action, editName = detail.name,
                editDescription = detail.description.orEmpty(), editWebsite = detail.homepageUrl.orEmpty(),
                editTopics = "", topicsLoaded = false, isLoadingTopics = false, confirmationName = "",
                subscription = detail.viewerSubscription, selectedTransferLogin = null,
                transferOtherSelected = false, transferOtherLogin = "", checkedTransferTarget = null,
                showTransferConfirmation = false, isCheckingTarget = false,
            )
        }
        if (action == RepoManagementAction.INFORMATION) loadTopics()
        if (action == RepoManagementAction.TRANSFER && state.transferTargets.isEmpty()) loadTransferTargets()
    }

    fun dismissManagement() {
        if (_state.value.isManaging) return
        topicsJob?.cancel()
        transferCheckJob?.cancel()
        _state.update { it.copy(managementAction = null, isLoadingTopics = false, isCheckingTarget = false) }
    }

    fun editName(value: String) {
        if (_state.value.isManaging) return
        invalidateTransferCheck()
        _state.update { it.copy(editName = value) }
    }

    fun editDescription(value: String) { if (!_state.value.isManaging) _state.update { it.copy(editDescription = value) } }
    fun editWebsite(value: String) { if (!_state.value.isManaging) _state.update { it.copy(editWebsite = value) } }
    fun editTopics(value: String) { if (!_state.value.isManaging && _state.value.topicsLoaded) _state.update { it.copy(editTopics = value) } }
    fun editConfirmation(value: String) { if (!_state.value.isManaging) _state.update { it.copy(confirmationName = value) } }
    fun selectSubscription(value: String) { if (!_state.value.isManaging) _state.update { it.copy(subscription = value) } }

    fun loadTopics() {
        if (_state.value.isLoadingTopics || _state.value.isManaging) return
        _state.update { it.copy(isLoadingTopics = true, topicsLoaded = false) }
        topicsJob = viewModelScope.launch {
            when (val result = repoDetailRepository.getAllTopics(owner, name)) {
                is ApiResult.Success -> _state.update { it.copy(editTopics = result.data.joinToString(" "), topicsLoaded = true, isLoadingTopics = false) }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoadingTopics = false) } }
            }
        }
    }

    fun loadTransferTargets() {
        val state = _state.value
        val detail = state.detail ?: return
        if (state.isLoadingTargets || (state.transferTargets.isNotEmpty() && !state.transferHasNextPage && !state.targetsLoadFailed)) return
        val after = transferCursor
        _state.update { it.copy(isLoadingTargets = true, targetsLoadFailed = false) }
        viewModelScope.launch {
            when (val result = repoRepository.getRepositoryCreateOwners(after)) {
                is ApiResult.Success -> {
                    val page = result.data
                    if (page.hasNextPage && (page.endCursor == null || page.endCursor == after)) {
                        errorEventBus.emit(ApiError.Unknown("Transfer targets cursor is missing or unchanged"))
                        _state.update { it.copy(isLoadingTargets = false, targetsLoadFailed = true) }
                        return@launch
                    }
                    transferCursor = page.endCursor
                    val candidates = (if (detail.ownerIsOrganization) listOf(page.viewer) else emptyList()) + page.organizations
                    _state.update {
                        it.copy(
                            transferTargets = (it.transferTargets + candidates).distinctBy { target -> target.login.lowercase() }
                                .filterNot { target -> target.login.equals(owner, true) },
                            transferHasNextPage = page.hasNextPage, isLoadingTargets = false,
                        )
                    }
                }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isLoadingTargets = false, targetsLoadFailed = true) } }
            }
        }
    }

    fun selectTransferTarget(login: String?) {
        if (_state.value.isManaging) return
        if (login != null && _state.value.transferTargets.none { it.login == login && it.canCreateRepository }) return
        invalidateTransferCheck()
        _state.update { it.copy(selectedTransferLogin = login, transferOtherSelected = login == null) }
    }

    fun editTransferLogin(value: String) {
        if (_state.value.isManaging) return
        invalidateTransferCheck()
        _state.update { it.copy(transferOtherLogin = value) }
    }

    private fun invalidateTransferCheck() {
        transferCheckJob?.cancel()
        _state.update { it.copy(checkedTransferTarget = null, isCheckingTarget = false, showTransferConfirmation = false) }
    }

    fun checkTransferTarget() {
        val state = _state.value
        val login = if (state.transferOtherSelected) state.transferOtherLogin.trim() else state.selectedTransferLogin ?: return
        if (state.managementAction != RepoManagementAction.TRANSFER || state.isManaging || state.isCheckingTarget ||
            !isValidRepositoryName(state.editName) || login.isBlank() || '/' in login
        ) return
        if (login.equals(owner, true)) {
            viewModelScope.launch { errorEventBus.emit(ApiError.UserVisible(R.string.repo_transfer_same_owner)) }
            return
        }
        _state.update { it.copy(isCheckingTarget = true, checkedTransferTarget = null, showTransferConfirmation = false) }
        transferCheckJob = viewModelScope.launch {
            when (val result = repoDetailRepository.getTransferTarget(login, state.editName)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        checkedTransferTarget = result.data,
                        isCheckingTarget = false,
                        showTransferConfirmation = !result.data.hasNameConflict && result.data.account.canCreateRepository,
                    )
                }
                is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(isCheckingTarget = false) } }
            }
        }
    }

    fun showTransferConfirmation(value: Boolean) {
        val target = _state.value.checkedTransferTarget
        if (!_state.value.isManaging && (!value || (target != null && !target.hasNameConflict && target.account.canCreateRepository))) {
            _state.update { it.copy(showTransferConfirmation = value) }
        }
    }

    fun confirmManagement() {
        val state = _state.value
        val detail = state.detail ?: return
        val action = state.managementAction ?: return
        if (state.isManaging || state.isStarring || state.isForking || state.isLoading ||
            state.isSavingStarLists || state.isCreatingList ||
            state.showStarListSelector || state.showCreateListDialog || state.transferPending || state.deleted ||
            !isAllowed(action, detail)
        ) return
        when (action) {
            RepoManagementAction.RENAME -> if (!isValidRepositoryName(state.editName)) return
            RepoManagementAction.INFORMATION -> if (!state.topicsLoaded || !isValidRepositoryWebsite(state.editWebsite) || !areValidRepositoryTopics(parseRepositoryTopics(state.editTopics))) return
            RepoManagementAction.DELETE -> if (state.confirmationName != detail.name) return
            RepoManagementAction.TRANSFER -> {
                val target = state.checkedTransferTarget ?: return
                if (!state.showTransferConfirmation || target.hasNameConflict || !target.account.canCreateRepository || !isValidRepositoryName(state.editName)) return
            }
            RepoManagementAction.NOTIFICATIONS -> if (state.subscription !in listOf("SUBSCRIBED", "UNSUBSCRIBED", "IGNORED")) return
            else -> Unit
        }
        loadJob?.cancel()
        _state.update { it.copy(isManaging = true) }
        viewModelScope.launch {
            try {
                when (action) {
                    RepoManagementAction.RENAME -> when (val result = repoDetailRepository.updateSettings(detail.id, newName = state.editName)) {
                        is ApiResult.Success -> { applyAddress(result.data); managementSaved(detail.copy(name = result.data.name, ownerLogin = result.data.owner, url = "https://github.com/${result.data.owner}/${result.data.name}")) }
                        is ApiResult.Failure -> errorEventBus.emit(result.error)
                    }
                    RepoManagementAction.INFORMATION -> saveInformation(detail, state)
                    RepoManagementAction.VISIBILITY -> when (val result = repoDetailRepository.setVisibility(owner, name, if (detail.isPrivate) "public" else "private")) {
                        is ApiResult.Success -> managementSaved(detail.copy(isPrivate = !detail.isPrivate, visibility = if (detail.isPrivate) "PUBLIC" else "PRIVATE"))
                        is ApiResult.Failure -> errorEventBus.emit(result.error)
                    }
                    RepoManagementAction.ARCHIVE -> when (val result = repoDetailRepository.setArchived(detail.id, !detail.isArchived)) {
                        is ApiResult.Success -> managementSaved(detail.copy(isArchived = !detail.isArchived, capabilities = detail.permission.toCapabilities(!detail.isArchived)))
                        is ApiResult.Failure -> errorEventBus.emit(result.error)
                    }
                    RepoManagementAction.ISSUES, RepoManagementAction.DISCUSSIONS -> when (val result = repoDetailRepository.updateSettings(
                        detail.id,
                        hasIssuesEnabled = if (action == RepoManagementAction.ISSUES) !detail.hasIssuesEnabled else null,
                        hasDiscussionsEnabled = if (action == RepoManagementAction.DISCUSSIONS) !detail.hasDiscussionsEnabled else null,
                    )) {
                        is ApiResult.Success -> managementSaved(if (action == RepoManagementAction.ISSUES) detail.copy(hasIssuesEnabled = !detail.hasIssuesEnabled) else detail.copy(hasDiscussionsEnabled = !detail.hasDiscussionsEnabled))
                        is ApiResult.Failure -> errorEventBus.emit(result.error)
                    }
                    RepoManagementAction.NOTIFICATIONS -> when (val result = repoDetailRepository.setSubscription(detail.id, state.subscription)) {
                        is ApiResult.Success -> {
                            _state.update { it.copy(managementAction = null, detail = detail.copy(viewerSubscription = result.data.state, watcherCount = result.data.watcherCount)) }
                            repoUpdateEventBus.emit(RepoUpdateEvent.WatchStateChanged(owner, name, result.data.state, result.data.watcherCount))
                            errorEventBus.emitNotice(R.string.repo_manage_saved)
                        }
                        is ApiResult.Failure -> errorEventBus.emit(result.error)
                    }
                    RepoManagementAction.TRANSFER -> when (val result = repoDetailRepository.transferRepository(owner, name, state.checkedTransferTarget!!.account.login, state.editName)) {
                        is ApiResult.Success -> {
                            transferPendingStore.mark(
                                PendingRepositoryTransfer(
                                    repositoryId = detail.id,
                                    owner = owner,
                                    name = name,
                                    targetOwner = state.checkedTransferTarget.account.login,
                                    targetName = state.editName,
                                ),
                            )
                            _state.update { it.copy(managementAction = null, transferPending = true) }
                            invalidateManagementCaches()
                            errorEventBus.emitNotice(R.string.repo_transfer_accepted)
                        }
                        is ApiResult.Failure -> errorEventBus.emit(result.error)
                    }
                    RepoManagementAction.DELETE -> when (val result = repoDetailRepository.deleteRepository(owner, name)) {
                        is ApiResult.Success -> {
                            readmeJob?.cancel()
                            starListsJob?.cancel()
                            _state.update { it.copy(managementAction = null, deleted = true, isLoadingStarLists = false) }
                            invalidateManagementCaches()
                            repoUpdateEventBus.emit(RepoUpdateEvent.RepositoryDeleted(owner, name))
                        }
                        is ApiResult.Failure -> errorEventBus.emit(result.error)
                    }
                }
            } finally {
                _state.update { it.copy(isManaging = false) }
            }
        }
    }

    private suspend fun saveInformation(detail: RepoDetail, state: RepoDetailUiState) {
        when (val result = repoDetailRepository.updateSettings(detail.id, description = state.editDescription, homepageUrl = state.editWebsite)) {
            is ApiResult.Failure -> { errorEventBus.emit(result.error); return }
            is ApiResult.Success -> Unit
        }
        val topics = parseRepositoryTopics(state.editTopics)
        val result = repoDetailRepository.updateTopics(detail.id, topics)
        val complete = result is ApiResult.Success && result.data.isEmpty()
        if (result is ApiResult.Failure) errorEventBus.emit(result.error)
        managementSaved(
            detail.copy(description = state.editDescription, homepageUrl = state.editWebsite, topics = if (complete) topics else detail.topics),
            notice = if (complete) R.string.repo_manage_saved else R.string.repo_manage_partial_information,
        )
    }

    private fun invalidateManagementCaches() {
        repoDetailRepository.invalidateAllCaches()
        repoRepository.invalidateAllCaches()
        starRepository.invalidateAllCaches()
    }

    private suspend fun managementSaved(detail: RepoDetail, notice: Int = R.string.repo_manage_saved) {
        invalidateManagementCaches()
        _state.update { it.copy(detail = detail, managementAction = null) }
        repoUpdateEventBus.emit(RepoUpdateEvent.RepositoryChanged(owner, name, detail))
        when (val result = repoDetailRepository.getRepoDetail(owner, name)) {
            is ApiResult.Success -> {
                _state.update { it.copy(detail = result.data, loadFailed = false) }
                loadStarLists(refresh = true)
                repoUpdateEventBus.emit(RepoUpdateEvent.RepositoryChanged(owner, name, result.data))
            }
            is ApiResult.Failure -> { errorEventBus.emit(result.error); _state.update { it.copy(loadFailed = true) } }
        }
        errorEventBus.emitNotice(notice)
    }

    private suspend fun applyAddress(address: CreatedRepository) {
        if (address.owner == owner && address.name == name) return
        val oldOwner = owner
        val oldName = name
        owner = address.owner
        name = address.name
        readmeJob?.cancel()
        repoUpdateEventBus.emit(RepoUpdateEvent.RepositoryAddressChanged(oldOwner, oldName, owner, name))
    }

    fun checkTransferStatus() {
        val state = _state.value
        val detail = state.detail ?: return
        if (!state.transferPending || state.isCheckingTransfer) return
        _state.update { it.copy(isCheckingTransfer = true) }
        viewModelScope.launch {
            try {
                when (val result = repoDetailRepository.getManagementIdentity(detail.id)) {
                    is ApiResult.Success -> {
                        if (result.data.owner == owner && result.data.name == name) errorEventBus.emitNotice(R.string.repo_transfer_still_pending)
                        else {
                            transferPendingStore.clear(detail.id)
                            applyAddress(result.data)
                            invalidateManagementCaches()
                            _state.update { it.copy(transferPending = false) }
                            load()
                        }
                    }
                    is ApiResult.Failure -> errorEventBus.emit(result.error)
                }
            } finally { _state.update { it.copy(isCheckingTransfer = false) } }
        }
    }

    private fun isAllowed(action: RepoManagementAction, detail: RepoDetail): Boolean = when (action) {
        RepoManagementAction.RENAME -> detail.capabilities.canRenameRepo
        RepoManagementAction.INFORMATION -> detail.capabilities.canManageSomeSettings
        RepoManagementAction.VISIBILITY -> detail.capabilities.canChangeVisibility && !detail.isFork && detail.visibility in listOf("PUBLIC", "PRIVATE")
        RepoManagementAction.TRANSFER -> detail.capabilities.canTransferRepo
        RepoManagementAction.ARCHIVE -> detail.capabilities.canArchiveRepo
        RepoManagementAction.NOTIFICATIONS -> detail.viewerCanSubscribe
        RepoManagementAction.ISSUES -> detail.capabilities.canToggleIssues
        RepoManagementAction.DISCUSSIONS -> detail.capabilities.canToggleDiscussions
        RepoManagementAction.DELETE -> detail.capabilities.canDeleteRepo
    }
}
