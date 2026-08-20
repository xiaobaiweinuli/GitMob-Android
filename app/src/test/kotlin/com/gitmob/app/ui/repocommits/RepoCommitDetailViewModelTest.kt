package com.gitmob.app.ui.repocommits

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoChangedFile
import com.gitmob.app.data.model.RepoChangedFileStatus
import com.gitmob.app.data.model.RepoCommitDetail
import com.gitmob.app.data.model.RepoCommitSummary
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoCommitDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `revert file creates reverse commit and calls completion`() = runTest {
        val repository = mockk<RepoGitRepository>()
        coEvery { repository.getCommitDetail("o", "r", "main", "sha") } returns ApiResult.Success(detail(RepoPermission.ADMIN))
        coEvery { repository.revertFile("o", "r", "main", "sha", "src/A.kt", "Revert file change", null) } returns ApiResult.Success(summary("new-sha"))
        val viewModel = RepoCommitDetailViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        viewModel.init("o", "r", "main", "sha", RepoPermission.ADMIN)
        advanceUntilIdle()

        var completed = false
        viewModel.revertFile(viewModel.state.value.detail!!.changedFiles.single(), "Revert file change") { completed = true }
        advanceUntilIdle()

        assertTrue(completed)
        assertFalse(viewModel.state.value.isReverting)
        coVerify(exactly = 1) { repository.revertFile("o", "r", "main", "sha", "src/A.kt", "Revert file change", null) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `read permission does not call revert mutation`() = runTest {
        val repository = mockk<RepoGitRepository>()
        coEvery { repository.getCommitDetail("o", "r", "main", "sha") } returns ApiResult.Success(detail(RepoPermission.READ))
        val viewModel = RepoCommitDetailViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        viewModel.init("o", "r", "main", "sha", RepoPermission.READ)
        advanceUntilIdle()

        viewModel.revertFile(viewModel.state.value.detail!!.changedFiles.single(), "Revert file change")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.revertFile(any(), any(), any(), any(), any(), any(), any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `revert commit creates one reverse commit`() = runTest {
        val repository = mockk<RepoGitRepository>()
        coEvery { repository.getCommitDetail("o", "r", "main", "sha") } returns ApiResult.Success(detail(RepoPermission.ADMIN))
        coEvery { repository.revertCommit("o", "r", "main", "sha", "Revert commit change") } returns ApiResult.Success(summary("new-sha"))
        val viewModel = RepoCommitDetailViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        viewModel.init("o", "r", "main", "sha", RepoPermission.ADMIN)
        advanceUntilIdle()

        var completed = false
        viewModel.revertCommit("Revert commit change") { completed = true }
        advanceUntilIdle()

        assertTrue(completed)
        assertFalse(viewModel.state.value.isReverting)
        coVerify(exactly = 1) { repository.revertCommit("o", "r", "main", "sha", "Revert commit change") }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `merge commit does not call whole commit revert`() = runTest {
        val repository = mockk<RepoGitRepository>()
        coEvery { repository.getCommitDetail("o", "r", "main", "sha") } returns ApiResult.Success(detail(RepoPermission.ADMIN, listOf("p1", "p2")))
        val viewModel = RepoCommitDetailViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        viewModel.init("o", "r", "main", "sha", RepoPermission.ADMIN)
        advanceUntilIdle()

        viewModel.revertCommit("Revert commit change")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.revertCommit(any(), any(), any(), any(), any()) }
        viewModel.viewModelScope.cancel()
    }

    private fun detail(permission: RepoPermission, parentOids: List<String> = listOf("parent")) = RepoCommitDetail(
        repositoryId = "R1",
        permission = permission,
        capabilities = permission.toCapabilities(),
        isArchived = false,
        commit = summary("sha", parentOids),
        changedFiles = listOf(
            RepoChangedFile("src/A.kt", null, RepoChangedFileStatus.MODIFIED, 1, 1, 2, null, null, null, null, null),
        ),
        changedFilesTruncated = false,
    )

    private fun summary(oid: String, parentOids: List<String> = listOf("parent")) = RepoCommitSummary(
        oid = oid,
        abbreviatedOid = oid.take(7),
        headline = "Change",
        body = "",
        authoredDate = null,
        committedDate = null,
        author = null,
        committer = null,
        additions = 1,
        deletions = 1,
        changedFiles = 1,
        parentOids = parentOids,
    )
}
