package com.gitmob.app.ui.repoissues

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoIssueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoIssueEditorUiState(
    val repositoryId: String? = null,
    val viewerCanCreateIssues: Boolean = false,
    val existing: RepoIssue? = null,
    val templates: List<IssueTemplate> = emptyList(),
    val blankIssuesEnabled: Boolean = true,
    val invalidTemplateCount: Int = 0,
    val labels: List<IssueLabel> = emptyList(),
    val milestones: List<IssueMilestone> = emptyList(),
    val assignees: List<SimpleUser> = emptyList(),
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
)

@HiltViewModel
class RepoIssueEditorViewModel @Inject constructor(
    private val repository: RepoIssueRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
) : ViewModel() {
    private val _state = MutableStateFlow(RepoIssueEditorUiState())
    val state: StateFlow<RepoIssueEditorUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var number: Int? = null
    private var templateFilename: String? = null
    private var initialized = false

    fun init(owner: String, name: String, number: Int?, templateFilename: String? = null) {
        if (initialized) return
        initialized = true
        this.owner = owner
        this.name = name
        this.number = number
        this.templateFilename = templateFilename
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadFailed = false) }
            val context = repository.getIssues(owner, name, RepoIssueFilter())
            if (context is ApiResult.Failure) return@launch failLoad(context)
            val page = (context as ApiResult.Success).data
            val templates = if (number == null) repository.getIssueTemplates(owner, name) else ApiResult.Success(IssueTemplateLoadResult(true, emptyList()))
            if (templates is ApiResult.Failure) return@launch failLoad(templates)
            val existing = number?.let { repository.getIssue(owner, name, it) }
            if (existing is ApiResult.Failure) return@launch failLoad(existing)
            val labels = repository.getLabels(owner, name)
            if (labels is ApiResult.Failure) return@launch failLoad(labels)
            val milestones = repository.getMilestones(owner, name)
            if (milestones is ApiResult.Failure) return@launch failLoad(milestones)
            val assignees = repository.getAssignableUsers(owner, name)
            if (assignees is ApiResult.Failure) return@launch failLoad(assignees)
            val templateData = (templates as ApiResult.Success).data
            _state.update {
                it.copy(
                    repositoryId = page.repositoryId,
                    viewerCanCreateIssues = page.viewerCanCreateIssues,
                    existing = (existing as? ApiResult.Success)?.data?.issue,
                    templates = templateData.templates,
                    blankIssuesEnabled = templateData.blankIssuesEnabled,
                    invalidTemplateCount = templateData.invalidTemplateCount,
                    labels = (labels as ApiResult.Success).data,
                    milestones = (milestones as ApiResult.Success).data,
                    assignees = (assignees as ApiResult.Success).data,
                    isLoading = false,
                    loadFailed = false,
                )
            }
        }
    }

    fun save(
        title: String,
        body: String,
        labelIds: List<String>,
        assigneeIds: List<String>,
        milestoneId: String?,
        done: (RepoIssue) -> Unit,
    ) {
        val state = _state.value
        if (title.isBlank() || state.isSaving) return
        val existing = state.existing
        if (existing == null && (!state.viewerCanCreateIssues || state.repositoryId == null)) return
        if (existing != null && !existing.viewerCanUpdate) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val result = if (existing == null) {
                repository.createIssue(CreateRepoIssueInput(state.repositoryId!!, title.trim(), body, labelIds, assigneeIds, milestoneId))
            } else {
                repository.updateIssue(UpdateRepoIssueInput(existing.id, title.trim(), body, labelIds, assigneeIds, milestoneId))
            }
            when (result) {
                is ApiResult.Success -> {
                    _state.update { it.copy(existing = result.data, isSaving = false) }
                    emitRefresh()
                    done(result.data)
                }
                is ApiResult.Failure -> {
                    errorEventBus.emit(result.error)
                    _state.update { it.copy(isSaving = false) }
                }
            }
        }
    }

    private suspend fun failLoad(result: ApiResult.Failure) {
        errorEventBus.emit(result.error)
        _state.update { it.copy(isLoading = false, loadFailed = true) }
    }

    private suspend fun emitRefresh() {
        val result = repository.getIssues(owner, name, RepoIssueFilter())
        val count = (result as? ApiResult.Success)?.data?.totalCount ?: return
        repoUpdateEventBus.emit(RepoUpdateEvent.IssueCountChanged(owner, name, count))
    }
}
