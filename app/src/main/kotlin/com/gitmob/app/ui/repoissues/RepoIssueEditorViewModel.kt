package com.gitmob.app.ui.repoissues

import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.ui.common.AccountViewModel
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.lifecycle.viewModelScope
import java.io.Serializable
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.ui.common.MarkdownEditorTab
import com.gitmob.app.ui.common.MarkdownEditorUiState
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.IssueFormSubmissionBuilder
import com.gitmob.app.data.repository.RepoIssueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RepoIssueEditorDraft(
    val title: String = "",
    val textValues: Map<String, String> = emptyMap(),
    val selections: Map<String, Set<Int>> = emptyMap(),
    val labelIds: Set<String> = emptySet(),
    val assigneeIds: Set<String> = emptySet(),
    val milestoneId: String? = null,
) : Serializable

data class RepoIssueEditorUiState(
    val draft: RepoIssueEditorDraft = RepoIssueEditorDraft(),
    val repositoryId: String? = null,
    val viewerCanCreateIssues: Boolean = false,
    val existing: RepoIssue? = null,
    val templates: List<IssueTemplate> = emptyList(),
    val templateHtml: Map<String, String> = emptyMap(),
    val blankIssuesEnabled: Boolean = true,
    val invalidTemplateCount: Int = 0,
    val labels: List<IssueLabel> = emptyList(),
    val milestones: List<IssueMilestone> = emptyList(),
    val assignees: List<SimpleUser> = emptyList(),
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
    val bodyEditor: MarkdownEditorUiState = MarkdownEditorUiState(),
)

@OptIn(SavedStateHandleSaveableApi::class)
@HiltViewModel
class RepoIssueEditorViewModel @Inject constructor(
    private val repository: RepoIssueRepository,
    private val errorEventBus: ErrorEventBus,
    private val repoUpdateEventBus: RepoUpdateEventBus,
    private val markdownRenderer: MarkdownRenderer,
    session: AccountSession,
    private val savedStateHandle: SavedStateHandle,
) : AccountViewModel(session) {
    val bodyState = savedStateHandle.saveable(key = "editor.body", saver = TextFieldState.Saver) { TextFieldState() }
    private var draftInitialized = savedStateHandle.contains("editor.draft")
    private val _state = MutableStateFlow(RepoIssueEditorUiState(
        draft = savedStateHandle["editor.draft"] ?: RepoIssueEditorDraft(),
    ))
    val state: StateFlow<RepoIssueEditorUiState> = _state.asStateFlow()
    private var owner = ""
    private var name = ""
    private var number: Int? = null
    private var templateFilename: String? = null
    private var initialized = false
    private var previewJob: kotlinx.coroutines.Job? = null

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
            val template = templateData.templates.firstOrNull { it.filename == templateFilename }
            val issue = (existing as? ApiResult.Success)?.data?.issue
            val availableLabels = (labels as ApiResult.Success).data
            val availableAssignees = (assignees as ApiResult.Success).data
            if (!draftInitialized) {
                bodyState.setTextAndPlaceCursorAtEnd(issue?.body.orEmpty())
                storeDraft(RepoIssueEditorDraft(
                    title = issue?.title ?: template?.title.orEmpty(),
                    textValues = template?.fields.orEmpty().mapNotNull { field ->
                        when (field) {
                            is IssueFormField.Input -> field.id to field.value.orEmpty()
                            is IssueFormField.Textarea -> field.id to field.value.orEmpty()
                            else -> null
                        }
                    }.toMap(),
                    selections = template?.fields.orEmpty().mapNotNull { field ->
                        (field as? IssueFormField.Dropdown)?.defaultIndex?.let { field.id to setOf(it) }
                    }.toMap(),
                    labelIds = issue?.labels?.map { it.id }?.toSet()
                        ?: availableLabels.filter { it.name in template?.labels.orEmpty() }.map { it.id }.toSet(),
                    assigneeIds = issue?.assignees?.mapNotNull { it.id }?.toSet()
                        ?: availableAssignees.filter { it.login in template?.assignees.orEmpty() }.mapNotNull { it.id }.toSet(),
                    milestoneId = issue?.milestone?.id,
                ))
            }
            val templateHtml = renderTemplate(template)
            _state.update {
                it.copy(
                    repositoryId = page.repositoryId,
                    viewerCanCreateIssues = page.viewerCanCreateIssues,
                    existing = issue,
                    templates = templateData.templates,
                    templateHtml = templateHtml,
                    blankIssuesEnabled = templateData.blankIssuesEnabled,
                    invalidTemplateCount = templateData.invalidTemplateCount,
                    labels = availableLabels,
                    milestones = (milestones as ApiResult.Success).data,
                    assignees = availableAssignees,
                    isLoading = false,
                    loadFailed = false,
                )
            }
        }
    }

    // Only explanatory content and checkbox option labels have Markdown semantics.
    // Keep the original template strings for editing and submission.
    private fun renderTemplate(template: IssueTemplate?): Map<String, String> =
        template?.fields.orEmpty().flatMap { field ->
            when (field) {
                is IssueFormField.Markdown -> listOf(field.value)
                is IssueFormField.Input -> listOf(field.description)
                is IssueFormField.Textarea -> listOf(field.description)
                is IssueFormField.Dropdown -> listOf(field.description)
                is IssueFormField.Checkboxes -> listOf(field.description) + field.options.map { it.label }
            }
        }.filterNotNull().filter(String::isNotBlank).distinct().associateWith(markdownRenderer::renderToHtml)

    fun selectBodyEditorTab(tab: MarkdownEditorTab) {
        _state.update { it.copy(bodyEditor = it.bodyEditor.copy(selectedTab = tab, previewFailed = false)) }
        if (tab == MarkdownEditorTab.PREVIEW) renderBodyPreview(bodyState.text.toString())
    }

    private fun renderBodyPreview(markdown: String) {
        previewJob?.cancel()
        if (markdown.isBlank()) {
            _state.update { it.copy(bodyEditor = it.bodyEditor.copy(previewHtml = "", isRenderingPreview = false, previewFailed = false)) }
            return
        }
        previewJob = viewModelScope.launch {
            kotlinx.coroutines.delay(120)
            _state.update { it.copy(bodyEditor = it.bodyEditor.copy(isRenderingPreview = true, previewFailed = false)) }
            runCatching { markdownRenderer.renderToHtml(markdown) }
                .onSuccess { html -> _state.update { it.copy(bodyEditor = it.bodyEditor.copy(previewHtml = html, isRenderingPreview = false)) } }
                .onFailure { error ->
                    errorEventBus.emit(com.gitmob.app.core.error.ApiError.Unknown(error.message ?: "Markdown preview failed"))
                    _state.update { it.copy(bodyEditor = it.bodyEditor.copy(isRenderingPreview = false, previewFailed = true)) }
                }
        }
    }

    private fun storeDraft(draft: RepoIssueEditorDraft) {
        savedStateHandle["editor.draft"] = draft
        draftInitialized = true
        _state.update { it.copy(draft = draft) }
    }

    fun editTitle(title: String) = storeDraft(_state.value.draft.copy(title = title))
    fun editText(id: String, value: String) = storeDraft(_state.value.draft.let {
        it.copy(textValues = it.textValues + (id to value))
    })
    fun editSelection(id: String, selected: Set<Int>) = storeDraft(_state.value.draft.let {
        it.copy(selections = it.selections + (id to selected))
    })
    fun confirmMetadata(labels: Set<String>, assignees: Set<String>, milestone: String?) =
        storeDraft(_state.value.draft.copy(labelIds = labels, assigneeIds = assignees, milestoneId = milestone))

    fun save(done: (RepoIssue) -> Unit) {
        val state = _state.value
        val draft = state.draft
        val title = draft.title
        if (!draftInitialized || state.isLoading || state.loadFailed || title.isBlank() || state.isSaving) return
        val template = state.templates.firstOrNull { it.filename == templateFilename }.takeIf { state.existing == null }
        val body = template?.let { IssueFormSubmissionBuilder.build(it, draft.textValues, draft.selections) }
            ?: bodyState.text.toString()
        val labelIds = draft.labelIds.toList()
        val assigneeIds = draft.assigneeIds.toList()
        val milestoneId = draft.milestoneId
        val existing = state.existing
        if (existing == null && (!state.viewerCanCreateIssues || state.repositoryId == null)) return
        if (existing != null && !existing.viewerCanUpdate) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
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
