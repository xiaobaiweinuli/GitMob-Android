package com.gitmob.app.ui.repoissues

import androidx.lifecycle.viewModelScope
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoIssueEditorViewModelTest {
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
        val viewModel = RepoIssueEditorViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())

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
        val viewModel = RepoIssueEditorViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())

        viewModel.init("o", "r", null)
        advanceUntilIdle()
        viewModel.save("Title", "### Version\n\n1.0", emptyList(), emptyList(), null) {}
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.createIssue(match { it.title == "Title" && it.body == "### Version\n\n1.0" })
        }
        viewModel.viewModelScope.cancel()
    }

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
