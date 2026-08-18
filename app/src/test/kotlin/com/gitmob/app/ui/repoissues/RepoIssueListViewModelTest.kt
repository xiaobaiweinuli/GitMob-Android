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
    fun `template picker opens when valid templates exist`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.getIssueTemplates("o", "r") } returns ApiResult.Success(
            IssueTemplateLoadResult(true, listOf(IssueTemplate("Bug", "About", null, "bug.yml", emptyList(), emptyList(), emptyList()))),
        )
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        vm.init("o", "r", RepoPermission.ADMIN, true)
        advanceUntilIdle()
        vm.beginCreate()
        advanceUntilIdle()
        assertTrue(vm.state.value.templatePickerVisible)
        assertEquals("bug.yml", vm.state.value.templates.single().filename)
        assertFalse(vm.state.value.blankCreateRequested)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `blank issue starts directly when no templates are available`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.getIssueTemplates("o", "r") } returns ApiResult.Success(IssueTemplateLoadResult(true, emptyList()))
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        vm.init("o", "r", RepoPermission.ADMIN, true)
        advanceUntilIdle()
        vm.beginCreate()
        advanceUntilIdle()
        assertTrue(vm.state.value.blankCreateRequested)
        assertFalse(vm.state.value.templatePickerVisible)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `template dialog remains visible when blank issues are disabled`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.getIssueTemplates("o", "r") } returns ApiResult.Success(IssueTemplateLoadResult(false, emptyList()))
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        vm.init("o", "r", RepoPermission.ADMIN, true)
        advanceUntilIdle()
        vm.beginCreate()
        advanceUntilIdle()
        assertTrue(vm.state.value.templatePickerVisible)
        assertFalse(vm.state.value.blankCreateRequested)
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
