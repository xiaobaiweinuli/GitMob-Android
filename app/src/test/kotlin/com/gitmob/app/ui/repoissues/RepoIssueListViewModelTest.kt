package com.gitmob.app.ui.repoissues

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
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
class RepoIssueListViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial load and filter change request first page`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        vm.init("o", "r", RepoPermission.ADMIN, true); advanceUntilIdle()
        vm.setState(RepoIssueStateFilter.CLOSED); advanceUntilIdle()
        assertEquals(RepoIssueStateFilter.CLOSED, vm.state.value.filter.state)
        assertEquals(1, vm.state.value.items.size)
        coVerify(exactly = 2) { repository.getIssues("o", "r", any(), null) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `successful delete removes item after api success`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.deleteIssue("I1") } returns ApiResult.Success(Unit)
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        vm.init("o", "r", RepoPermission.ADMIN, true); advanceUntilIdle()
        vm.confirmDelete(vm.state.value.items.single()); vm.deletePending(); advanceUntilIdle()
        assertEquals(0, vm.state.value.items.size)
        coVerify(exactly = 1) { repository.deleteIssue("I1") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `failed delete keeps item`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.deleteIssue("I1") } returns ApiResult.Failure(ApiError.NetworkError)
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        vm.init("o", "r", RepoPermission.ADMIN, true); advanceUntilIdle()
        vm.confirmDelete(vm.state.value.items.single()); vm.deletePending(); advanceUntilIdle()
        assertEquals(1, vm.state.value.items.size)
        assertFalse(vm.state.value.pendingDelete != null)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `template load failure is distinct from an empty template list and can retry`() = runTest {
        val repository = repositoryMock()
        val template = IssueTemplate("Bug report", "Report a bug", "[Bug] ", "bug.yml", listOf("bug"), listOf("octo"), listOf(IssueFormField.Input("version", "Version", required = true)))
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.getIssueTemplates("o", "r") } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError),
            ApiResult.Success(IssueTemplateLoadResult(true, listOf(template))),
        )
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())

        vm.init("o", "r", RepoPermission.ADMIN, true)
        vm.loadIssueTemplates()
        advanceUntilIdle()
        assertFalse(vm.state.value.templatesLoaded)
        assertTrue(vm.state.value.templatesLoadFailed)

        vm.loadIssueTemplates()
        advanceUntilIdle()
        assertTrue(vm.state.value.templatesLoaded)
        assertFalse(vm.state.value.templatesLoadFailed)
        assertEquals("Bug report", vm.state.value.templates.single().name)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `creating issue forwards the generated markdown body`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.createIssue(any()) } returns ApiResult.Success(issue())
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())

        vm.init("o", "r", RepoPermission.ADMIN, true)
        advanceUntilIdle()
        vm.createIssue("Title", "### Version\n\n1.0", emptyList(), emptyList(), null) {}
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.createIssue(match { it.body == "### Version\n\n1.0" }) }
        vm.viewModelScope.cancel()
    }

    private fun repositoryMock(): RepoIssueRepository = mockk {
        coEvery { getLabels(any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { getMilestones(any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { getAssignableUsers(any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { getIssueTemplates(any(), any()) } returns ApiResult.Success(IssueTemplateLoadResult(true, emptyList()))
    }

    private fun page() = RepoIssuePage("R1", RepoPermission.ADMIN, RepoPermission.ADMIN.toCapabilities(), true, true, 1, listOf(issue()), false, null)
    private fun issue() = RepoIssue("I1", 1, "Title", "body", "<p>body</p>", IssueState.OPEN, null, null, "2026-01-01", "2026-01-02", 0, emptyList(), emptyList(), null, false, true, true, true, true, true, true, false, "UNSUBSCRIBED")
}
