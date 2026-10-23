package com.gitmob.app.ui.repoactions

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoActionsPage
import com.gitmob.app.data.model.RepoWorkflowRun
import com.gitmob.app.data.repository.RepoActionsRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoActionsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `completed run is removed only after delete succeeds`() = runTest {
        val repository = mockk<RepoActionsRepository>()
        val run = run(status = "completed")
        coEvery { repository.getActions("octo", "repo") } returns ApiResult.Success(page(run))
        coEvery { repository.deleteRun("octo", "repo", run.id) } returns ApiResult.Success(Unit)
        val viewModel = RepoActionsViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        viewModel.init("octo", "repo", RepoPermission.WRITE, "main")
        advanceUntilIdle()

        viewModel.requestDelete(run)
        assertEquals(run, viewModel.state.value.pendingDeleteRun)
        viewModel.deletePendingRun()
        advanceUntilIdle()

        assertEquals(emptyList<RepoWorkflowRun>(), viewModel.state.value.runs)
        assertEquals(0, viewModel.state.value.totalCount)
        coVerify(exactly = 1) { repository.deleteRun("octo", "repo", run.id) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `failed delete keeps run in list`() = runTest {
        val repository = mockk<RepoActionsRepository>()
        val run = run(status = "completed")
        coEvery { repository.getActions("octo", "repo") } returns ApiResult.Success(page(run))
        coEvery { repository.deleteRun("octo", "repo", run.id) } returns ApiResult.Failure(ApiError.Forbidden)
        val viewModel = RepoActionsViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        viewModel.init("octo", "repo", RepoPermission.WRITE, "main")
        advanceUntilIdle()

        viewModel.requestDelete(run)
        viewModel.deletePendingRun()
        advanceUntilIdle()

        assertEquals(listOf(run), viewModel.state.value.runs)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `running run asks for cancellation without deleting`() = runTest {
        val repository = mockk<RepoActionsRepository>()
        val run = run(status = "in_progress")
        coEvery { repository.getActions("octo", "repo") } returns ApiResult.Success(page(run))
        val viewModel = RepoActionsViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        viewModel.init("octo", "repo", RepoPermission.WRITE, "main")
        advanceUntilIdle()

        viewModel.requestDelete(run)
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingDeleteRun)
        coVerify(exactly = 0) { repository.deleteRun(any(), any(), any()) }
        viewModel.viewModelScope.cancel()
    }

    private fun page(run: RepoWorkflowRun) = RepoActionsPage(1, emptyList(), listOf(run), 1, false)

    private fun run(status: String) = RepoWorkflowRun(
        id = 42,
        name = "CI",
        displayTitle = "Build",
        event = "push",
        status = status,
        conclusion = if (status == "completed") "success" else null,
        workflowId = 7,
        runNumber = 8,
        runAttempt = 1,
        headBranch = "main",
        headSha = "abcdef",
        actor = null,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:01:00Z",
        htmlUrl = "https://github.com/octo/repo/actions/runs/42",
    )
}
