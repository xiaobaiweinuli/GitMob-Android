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
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoActionsViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `completed run is removed only after delete succeeds`() = runTest {
        val repository = mockk<RepoActionsRepository>()
        val run = run(status = "completed")
        coEvery { repository.getActions("octo", "repo") } returns ApiResult.Success(page(run))
        coEvery { repository.deleteRun("octo", "repo", run.id) } returns ApiResult.Success(Unit)
        val viewModel = RepoActionsViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk(relaxed = true), accounts.session)
        viewModel.init("octo", "repo", RepoPermission.WRITE, "main", isArchived = false)
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
        val viewModel = RepoActionsViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk(relaxed = true), accounts.session)
        viewModel.init("octo", "repo", RepoPermission.WRITE, "main", isArchived = false)
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
        val viewModel = RepoActionsViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk(relaxed = true), accounts.session)
        viewModel.init("octo", "repo", RepoPermission.WRITE, "main", isArchived = false)
        advanceUntilIdle()

        viewModel.requestDelete(run)
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingDeleteRun)
        coVerify(exactly = 0) { repository.deleteRun(any(), any(), any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun oldRepositoryDeleteDoesNotRemoveRunsFromNewRepository() = runTest {
        val repository = mockk<RepoActionsRepository>()
        val run = run(status = "completed")
        val deletion = CompletableDeferred<ApiResult<Unit>>()
        coEvery { repository.getActions(any(), any()) } returns ApiResult.Success(page(run))
        coEvery { repository.deleteRun("octo", "repo", run.id) } coAnswers { deletion.await() }
        val viewModel = RepoActionsViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk(relaxed = true), accounts.session)
        viewModel.init("octo", "repo", RepoPermission.WRITE, "main", isArchived = false)
        advanceUntilIdle()
        viewModel.requestDelete(run)
        viewModel.deletePendingRun()
        advanceUntilIdle()
        viewModel.init("octo", "second", RepoPermission.WRITE, "main", isArchived = false)
        advanceUntilIdle()
        deletion.complete(ApiResult.Success(Unit))
        advanceUntilIdle()
        assertEquals(listOf(run), viewModel.state.value.runs)
        assertEquals(1, viewModel.state.value.totalCount)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun refreshDiscardsLatePageAndRestartsPaginationFromFirstPage() = runTest {
        val repository = mockk<RepoActionsRepository>()
        val first = run(status = "completed")
        val second = first.copy(id = 43)
        val third = first.copy(id = 44)
        val firstPage = page(first).copy(totalCount = 3, hasNextPage = true)
        val secondPage = firstPage.copy(runs = listOf(second), page = 2)
        val thirdPage = CompletableDeferred<ApiResult<RepoActionsPage>>()
        coEvery { repository.getActions("octo", "repo") } returns ApiResult.Success(firstPage)
        coEvery { repository.getActions("octo", "repo", 2) } returns ApiResult.Success(secondPage)
        coEvery { repository.getActions("octo", "repo", 3) } coAnswers {
            withContext(NonCancellable) { thirdPage.await() }
        }
        val viewModel = RepoActionsViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk(relaxed = true), accounts.session)
        viewModel.init("octo", "repo", RepoPermission.WRITE, "main", isArchived = false)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isLoadingMore)

        viewModel.refresh()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isLoadingMore)
        thirdPage.complete(ApiResult.Success(firstPage.copy(runs = listOf(third), page = 3, hasNextPage = false)))
        advanceUntilIdle()
        assertEquals(listOf(first), viewModel.state.value.runs)
        assertEquals(1, viewModel.state.value.page)

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(first, second), viewModel.state.value.runs)
        assertEquals(2, viewModel.state.value.page)
        coVerify(exactly = 2) { repository.getActions("octo", "repo", 2) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun repositoryChangeCancelsPendingPagination() = runTest {
        val repository = mockk<RepoActionsRepository>()
        val first = run(status = "completed")
        val nextPage = CompletableDeferred<ApiResult<RepoActionsPage>>()
        val requestFinished = CompletableDeferred<Unit>()
        coEvery { repository.getActions("octo", "repo") } returns ApiResult.Success(page(first).copy(hasNextPage = true))
        coEvery { repository.getActions("octo", "repo", 2) } coAnswers {
            try { nextPage.await() } finally { requestFinished.complete(Unit) }
        }
        val other = first.copy(id = 43)
        coEvery { repository.getActions("octo", "second") } returns ApiResult.Success(page(other))
        val viewModel = RepoActionsViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk(relaxed = true), accounts.session)
        viewModel.init("octo", "repo", RepoPermission.WRITE, "main", isArchived = false)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()
        assertFalse(requestFinished.isCompleted)

        viewModel.init("octo", "second", RepoPermission.WRITE, "main", isArchived = false)
        advanceUntilIdle()
        assertTrue(requestFinished.isCompleted)
        assertFalse(viewModel.state.value.isLoadingMore)
        assertEquals(listOf(other), viewModel.state.value.runs)
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
