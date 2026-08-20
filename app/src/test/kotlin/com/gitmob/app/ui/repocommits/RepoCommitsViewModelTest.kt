package com.gitmob.app.ui.repocommits

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.PagedRepoCommits
import com.gitmob.app.data.model.RepoCommitSummary
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoCommitsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial load exposes commits and event reloads matching branch`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val eventBus = RepoUpdateEventBus()
        coEvery { repository.getCommitHistory("o", "r", "main", null, any()) } returns ApiResult.Success(page())
        val viewModel = RepoCommitsViewModel(repository, ErrorEventBus(), eventBus)

        viewModel.init("o", "r", "main", null, RepoPermission.READ)
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.items.size)

        eventBus.emit(RepoUpdateEvent.CodeChanged("o", "r", "main", "next", listOf("README.md")))
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.getCommitHistory("o", "r", "main", null, any()) }
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
