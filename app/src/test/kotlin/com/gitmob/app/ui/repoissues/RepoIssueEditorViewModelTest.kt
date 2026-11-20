package com.gitmob.app.ui.repoissues

import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.markdown.CommonMarkRenderer
import com.gitmob.app.data.model.IssueLabel
import com.gitmob.app.data.model.SimpleUser
import com.gitmob.app.ui.common.MarkdownEditorTab
import com.gitmob.app.data.model.IssueFormCheckboxOption
import com.gitmob.app.data.repository.IssueFormSubmissionBuilder
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.IssueFormField
import com.gitmob.app.data.model.IssueState
import com.gitmob.app.data.model.IssueTemplate
import com.gitmob.app.data.model.IssueTemplateLoadResult
import com.gitmob.app.data.model.RepoIssue
import com.gitmob.app.data.model.RepoIssueFilter
import com.gitmob.app.data.model.RepoIssuePage
import com.gitmob.app.data.repository.RepoIssueRepository
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class RepoIssueEditorViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `template load failure remains retryable in editor`() = runTest {
        val repository = repositoryMock()
        val template = IssueTemplate(
            "Bug report",
            "Report a bug",
            "[Bug] ",
            "bug.yml",
            listOf("bug"),
            listOf("octo"),
            listOf(IssueFormField.Input("version", "Version", required = true)),
        )
        coEvery { repository.getIssueTemplates("o", "r") } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError),
            ApiResult.Success(IssueTemplateLoadResult(true, listOf(template))),
        )
        val viewModel = RepoIssueEditorViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk(), accounts.session, SavedStateHandle())

        viewModel.init("o", "r", null)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.loadFailed)

        viewModel.load()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.loadFailed)
        assertEquals("Bug report", viewModel.state.value.templates.single().name)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `creating issue forwards generated markdown from editor`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.createIssue(any()) } returns ApiResult.Success(issue())
        val viewModel = RepoIssueEditorViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk(), accounts.session, SavedStateHandle())

        viewModel.init("o", "r", null)
        advanceUntilIdle()
        viewModel.editTitle("Title")
        viewModel.bodyState.edit { replace(0, length, "### Version\n\n1.0") }
        viewModel.save {}
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.createIssue(match { it.title == "Title" && it.body == "### Version\n\n1.0" })
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `renders only selected template markdown fields and keeps source for submission`() = runTest {
        val repository = repositoryMock()
        val template = IssueTemplate(
            "**Name**", "**About**", "**Title**", "bug.yml", emptyList(), emptyList(),
            listOf(
                IssueFormField.Markdown(value = "# Instructions"),
                IssueFormField.Input("version", "**Version**", "[Help](https://example.com/input)", "**Hint**", "**Initial**"),
                IssueFormField.Textarea("logs", "Logs", "- **System**\n- _Version_", render = "shell"),
                IssueFormField.Dropdown("os", "OS", "Choose _one_", listOf("**Linux**")),
                IssueFormField.Checkboxes("terms", "Checklist", "Read **carefully**",
                    listOf(IssueFormCheckboxOption("Accept [terms](https://example.com/terms)", required = true))),
                IssueFormField.Input("optional", "Optional", "  "),
            ),
        )
        coEvery { repository.getIssueTemplates("o", "r") } returns ApiResult.Success(
            IssueTemplateLoadResult(true, listOf(template, template.copy(filename = "other.yml",
                fields = listOf(IssueFormField.Markdown(value = "Unselected")))))
        )
        val viewModel = RepoIssueEditorViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), CommonMarkRenderer(), accounts.session, SavedStateHandle())

        viewModel.init("o", "r", null, "bug.yml")
        advanceUntilIdle()

        val state = viewModel.state.value
        val html = state.templateHtml
        assertEquals(setOf("# Instructions", "[Help](https://example.com/input)", "- **System**\n- _Version_",
            "Choose _one_", "Read **carefully**", "Accept [terms](https://example.com/terms)"), html.keys)
        assertTrue(html.getValue("# Instructions").contains("<h1>Instructions</h1>"))
        assertTrue(html.getValue("[Help](https://example.com/input)").contains("<a href=\"https://example.com/input\">Help</a>"))
        assertTrue(html.getValue("- **System**\n- _Version_").contains("<ul>"))
        assertTrue(html.getValue("- **System**\n- _Version_").contains("<strong>System</strong>"))
        assertTrue(html.getValue("Choose _one_").contains("<em>one</em>"))
        assertEquals(template, state.templates.first())
        val submitted = IssueFormSubmissionBuilder.build(state.templates.first(),
            mapOf("version" to "1.0", "logs" to "echo ok"), mapOf("os" to setOf(0), "terms" to setOf(0)))
        assertTrue(submitted.contains("- [x] Accept [terms](https://example.com/terms)"))
        assertTrue(submitted.contains("```shell\necho ok\n```"))
        assertTrue(submitted.contains("**Linux**"))
        assertFalse(submitted.contains("Instructions"))
        assertFalse(submitted.contains("Read **carefully**"))
        assertFalse(submitted.contains("<a"))
        assertFalse(submitted.contains("terms) *"))
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `blank editor does not render templates that were not selected`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssueTemplates("o", "r") } returns ApiResult.Success(IssueTemplateLoadResult(true,
            listOf(IssueTemplate("Bug", null, null, "bug.yml", emptyList(), emptyList(),
                listOf(IssueFormField.Markdown(value = "**Instructions**"))))))
        val viewModel = RepoIssueEditorViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk(), accounts.session, SavedStateHandle())
        viewModel.init("o", "r", null)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.templateHtml.isEmpty())
        assertFalse(viewModel.state.value.loadFailed)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun templateAnswersAndConfirmedMetadataRestoreIncludingClearedValues() = runTest {
        val repository = repositoryMock()
        val template = IssueTemplate("Bug", null, "Default title", "bug.yml", listOf("bug"), listOf("octo"), listOf(
            IssueFormField.Input("version", "Version", value = "default"),
            IssueFormField.Textarea("logs", "Logs", value = "default logs"),
            IssueFormField.Dropdown("os", "OS", options = listOf("Linux", "Windows"), defaultIndex = 0),
            IssueFormField.Checkboxes("terms", "Terms", options = listOf(IssueFormCheckboxOption("Accept"))),
        ))
        coEvery { repository.getIssueTemplates("o", "r") } returns ApiResult.Success(IssueTemplateLoadResult(true, listOf(template)))
        coEvery { repository.getLabels("o", "r") } returns ApiResult.Success(listOf(IssueLabel("L1", "bug", "ffffff", null)))
        coEvery { repository.getAssignableUsers("o", "r") } returns ApiResult.Success(listOf(SimpleUser("octo", null, null, null, "U1")))
        val handle = SavedStateHandle()
        val original = editor(repository, handle)
        original.init("o", "r", null, "bug.yml")
        advanceUntilIdle()
        assertEquals("Default title", original.state.value.draft.title)
        assertEquals(setOf("L1"), original.state.value.draft.labelIds)
        assertEquals(setOf("U1"), original.state.value.draft.assigneeIds)
        assertEquals(setOf(0), original.state.value.draft.selections["os"])
        original.editTitle("")
        original.editText("version", "")
        original.editText("logs", "user logs")
        original.editSelection("os", emptySet())
        original.editSelection("terms", setOf(0))
        original.confirmMetadata(emptySet(), setOf("U2"), "M2")
        val expected = original.state.value.draft
        val saved = handle.savedStateProvider().saveState()
        original.viewModelScope.cancel()
        val restored = editor(repository, SavedStateHandle.createHandle(saved, null))
        restored.init("o", "r", null, "bug.yml")
        advanceUntilIdle()
        assertEquals(expected, restored.state.value.draft)
        restored.confirmMetadata(emptySet(), emptySet(), null)
        restored.load()
        advanceUntilIdle()
        assertEquals(expected.copy(assigneeIds = emptySet(), milestoneId = null), restored.state.value.draft)
        restored.viewModelScope.cancel()
    }

    @Test
    fun savedBodyDuringLoadingStillReceivesDefaultsButUserClearedBodyDoesNot() = runTest {
        val repository = repositoryMock()
        val pending = CompletableDeferred<ApiResult<RepoIssuePage>>()
        coEvery { repository.getIssues("o", "r", any<RepoIssueFilter>(), null) } coAnswers { pending.await() }
        val handle = SavedStateHandle()
        val original = editor(repository, handle)
        original.init("o", "r", 1)
        advanceUntilIdle()
        assertTrue(original.state.value.isLoading)
        val loadingSnapshot = handle.savedStateProvider().saveState()
        original.viewModelScope.cancel()
        coEvery { repository.getIssues("o", "r", any<RepoIssueFilter>(), null) } returns ApiResult.Success(page())
        val serverIssue = issue()
        coEvery { repository.getIssue("o", "r", 1) } returns ApiResult.Success(mockk { io.mockk.every { issue } returns serverIssue })
        val restoredHandle = SavedStateHandle.createHandle(loadingSnapshot, null)
        val initialized = editor(repository, restoredHandle)
        initialized.init("o", "r", 1)
        advanceUntilIdle()
        assertEquals("body", initialized.bodyState.text.toString())
        initialized.bodyState.edit { replace(0, length, "") }
        initialized.editTitle("")
        val clearedSnapshot = restoredHandle.savedStateProvider().saveState()
        initialized.viewModelScope.cancel()
        val cleared = editor(repository, SavedStateHandle.createHandle(clearedSnapshot, null))
        cleared.init("o", "r", 1)
        advanceUntilIdle()
        assertEquals("", cleared.bodyState.text.toString())
        assertEquals("", cleared.state.value.draft.title)
        cleared.viewModelScope.cancel()
    }

    @Test
    fun restoredBodySelectionAndImmediateSubmissionUseCurrentInputsAndKeepFailedDraft() = runTest {
        val repository = repositoryMock()
        val pending = CompletableDeferred<ApiResult<RepoIssue>>()
        coEvery { repository.createIssue(any()) } coAnswers { pending.await() }
        val handle = SavedStateHandle()
        val original = editor(repository, handle)
        original.init("o", "r", null)
        advanceUntilIdle()
        original.editTitle("My title")
        original.bodyState.edit { replace(0, length, "unsent body"); selection = TextRange(2, 6) }
        val saved = handle.savedStateProvider().saveState()
        original.viewModelScope.cancel()
        val restored = editor(repository, SavedStateHandle.createHandle(saved, null))
        restored.init("o", "r", null)
        advanceUntilIdle()
        assertEquals("unsent body", restored.bodyState.text.toString())
        assertEquals(TextRange(2, 6), restored.bodyState.selection)
        val bodyState = restored.bodyState
        restored.selectBodyEditorTab(MarkdownEditorTab.PREVIEW)
        restored.load()
        advanceUntilIdle()
        assertEquals(MarkdownEditorTab.PREVIEW, restored.state.value.bodyEditor.selectedTab)
        org.junit.Assert.assertSame(bodyState, restored.bodyState)
        restored.bodyState.edit { replace(0, length, "latest input") }
        restored.save {}
        restored.editTitle("Edited while pending")
        restored.bodyState.edit { replace(0, length, "keep after failure") }
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.createIssue(match { it.title == "My title" && it.body == "latest input" }) }
        pending.complete(ApiResult.Failure(ApiError.NetworkError))
        advanceUntilIdle()
        assertEquals("Edited while pending", restored.state.value.draft.title)
        assertEquals("keep after failure", restored.bodyState.text.toString())
        assertFalse(restored.state.value.isSaving)
        restored.viewModelScope.cancel()
    }

    private fun editor(repository: RepoIssueRepository, handle: SavedStateHandle) =
        RepoIssueEditorViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), CommonMarkRenderer(), accounts.session, handle)

    private fun repositoryMock(): RepoIssueRepository = mockk {
        coEvery { getIssues("o", "r", any<RepoIssueFilter>(), null) } returns ApiResult.Success(page())
        coEvery { getIssueTemplates("o", "r") } returns ApiResult.Success(IssueTemplateLoadResult(true, emptyList()))
        coEvery { getLabels("o", "r") } returns ApiResult.Success(emptyList())
        coEvery { getMilestones("o", "r") } returns ApiResult.Success(emptyList())
        coEvery { getAssignableUsers("o", "r") } returns ApiResult.Success(emptyList())
    }

    private fun page() = RepoIssuePage(
        "R1",
        RepoPermission.ADMIN,
        RepoPermission.ADMIN.toCapabilities(),
        true,
        true,
        1,
        listOf(issue()),
        false,
        null,
    )

    private fun issue() = RepoIssue(
        "I1",
        1,
        "Title",
        "body",
        "<p>body</p>",
        IssueState.OPEN,
        null,
        null,
        "2026-01-01",
        "2026-01-02",
        0,
        emptyList(),
        emptyList(),
        null,
        false,
        true,
        true,
        true,
        true,
        true,
        true,
        false,
        "UNSUBSCRIBED",
    )
}
