package com.gitmob.app.ui.repocommits

import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoContentSource
import com.gitmob.app.data.model.RepoLinkKind
import com.gitmob.app.data.model.RepoLinkTarget
import com.gitmob.app.data.model.PagedRepoCommits
import com.gitmob.app.data.model.RepoCommitSummary
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoCommitsViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial load exposes commits and event reloads matching branch`() = runTest(accounts.context) {
        val repository = mockk<RepoGitRepository>()
        val eventBus = RepoUpdateEventBus()
        coEvery { repository.getCommitHistory("o", "r", "main", null, any()) } returns ApiResult.Success(page())
        val viewModel = RepoCommitsViewModel(repository, ErrorEventBus(), eventBus, accounts.session)

        viewModel.init("o", "r", RepoLinkTarget("main", ""), RepoPermission.READ)
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.items.size)

        eventBus.emit(RepoUpdateEvent.CodeChanged("o", "r", "main", "next", listOf("README.md")))
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getCommitHistory("o", "r", "main", null, any()) }
    }

    @Test
    fun `history link retries resolution and reuses the resolved filter for pagination and updates`() = runTest(accounts.context) {
        val repository = mockk<RepoGitRepository>()
        val events = RepoUpdateEventBus()
        val source = RepoContentSource.Link(listOf("feature", "docs", "api"))
        val target = RepoLinkTarget("feature/docs", "api")
        coEvery { repository.resolveLink("o", "r", RepoLinkKind.HISTORY, source.refAndPath) } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError), ApiResult.Success(target),
        )
        coEvery { repository.getCommitHistory("o", "r", target.ref, target.path, null) } returns ApiResult.Success(
            page().copy(ref = target.ref, hasNextPage = true, endCursor = "next"),
        )
        coEvery { repository.getCommitHistory("o", "r", target.ref, target.path, "next") } returns ApiResult.Success(page().copy(ref = target.ref))
        val vm = RepoCommitsViewModel(repository, ErrorEventBus(), events, accounts.session)
        vm.init("o", "r", source, null)
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.isLoading)
        coVerify(exactly = 0) { repository.getCommitHistory(any(), any(), any(), any(), any()) }
        vm.retry()
        advanceUntilIdle()
        assertEquals(target, vm.state.value.target)
        assertFalse(vm.state.value.loadFailed)
        vm.loadMore()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.getCommitHistory("o", "r", target.ref, target.path, "next") }
        events.emit(RepoUpdateEvent.CodeChanged("o", "r", target.ref, "new-head", listOf("api/README.md")))
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.getCommitHistory("o", "r", target.ref, target.path, null) }
        coVerify(exactly = 2) { repository.resolveLink(any(), any(), any(), any()) }
    }

    @Test
    fun `branch history link sends no path filter`() = runTest(accounts.context) {
        val repository = mockk<RepoGitRepository>()
        val source = RepoContentSource.Link(listOf("feature", "docs"))
        val target = RepoLinkTarget("feature/docs", "")
        coEvery { repository.resolveLink("o", "r", RepoLinkKind.HISTORY, source.refAndPath) } returns ApiResult.Success(target)
        coEvery { repository.getCommitHistory("o", "r", target.ref, null, null) } returns ApiResult.Success(page().copy(ref = target.ref))
        val vm = RepoCommitsViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session)
        vm.init("o", "r", source, null)
        advanceUntilIdle()
        assertEquals(target, vm.state.value.target)
        coVerify(exactly = 1) { repository.getCommitHistory("o", "r", target.ref, null, null) }
    }

    @Test
    fun `whitespace filename retains its filter across pagination and change events`() = runTest(accounts.context) {
        val repository = mockk<RepoGitRepository>()
        val events = RepoUpdateEventBus()
        val source = RepoContentSource.Link(listOf("feature", "docs", " "))
        val target = RepoLinkTarget("feature/docs", " ")
        coEvery { repository.resolveLink("o", "r", RepoLinkKind.HISTORY, source.refAndPath) } returns ApiResult.Success(target)
        coEvery { repository.getCommitHistory("o", "r", target.ref, " ", null) } returns ApiResult.Success(
            page().copy(ref = target.ref, hasNextPage = true, endCursor = "next"),
        )
        coEvery { repository.getCommitHistory("o", "r", target.ref, " ", "next") } returns ApiResult.Success(page())
        val vm = RepoCommitsViewModel(repository, ErrorEventBus(), events, accounts.session)
        vm.init("o", "r", source, null)
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.getCommitHistory("o", "r", target.ref, " ", "next") }
        events.emit(RepoUpdateEvent.CodeChanged("o", "r", target.ref, "unrelated", listOf("README.md")))
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.getCommitHistory("o", "r", target.ref, " ", null) }
        events.emit(RepoUpdateEvent.CodeChanged("o", "r", target.ref, "changed", listOf(" ")))
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.getCommitHistory("o", "r", target.ref, " ", null) }
        coVerify(exactly = 0) { repository.getCommitHistory("o", "r", target.ref, null, any()) }
    }

    private fun page() = PagedRepoCommits(
        repositoryId = "repo",
        permission = RepoPermission.READ,
        capabilities = RepoCapabilities.NONE,
        isArchived = false,
        ref = "main",
        headOid = "head",
        totalCount = 1,
        items = listOf(
            RepoCommitSummary(
                oid = "1234567890",
                abbreviatedOid = "1234567",
                headline = "Initial",
                body = "",
                authoredDate = null,
                committedDate = "2026-08-18T00:00:00Z",
                author = null,
                committer = null,
                additions = 1,
                deletions = 0,
                changedFiles = 1,
            ),
        ),
        hasNextPage = false,
        endCursor = null,
    )
}
