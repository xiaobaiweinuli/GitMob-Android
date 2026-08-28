package com.gitmob.app.ui.repoissues

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoIssueRepository
import com.gitmob.app.data.repository.ConversationEditRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class RepoIssueDetailViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `detail loads and comment is appended`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssue("o", "r", 1, null) } returns ApiResult.Success(detail())
        coEvery { repository.addComment("I1", "hello") } returns ApiResult.Success(comment())
        val vm = RepoIssueDetailViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk<ConversationEditRepository>())
        vm.init("o", "r", 1, RepoPermission.READ); advanceUntilIdle()
        vm.addComment("hello") {}; advanceUntilIdle()
        assertEquals("Title", vm.state.value.issue?.title)
        assertEquals(1, vm.state.value.comments.size)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `close mutation is not called without node permission`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssue("o", "r", 1, null) } returns ApiResult.Success(detail())
        val vm = RepoIssueDetailViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk<ConversationEditRepository>())
        vm.init("o", "r", 1, null); advanceUntilIdle()
        vm.closeIssue(IssueStateReason.COMPLETED); advanceUntilIdle()
        coVerify(exactly = 0) { repository.closeIssue(any(), any()) }
        vm.viewModelScope.cancel()
    }

    private fun repositoryMock(): RepoIssueRepository = mockk {
        coEvery { getLabels(any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { getMilestones(any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { getAssignableUsers(any(), any()) } returns ApiResult.Success(emptyList())
    }

    private fun detail() = RepoIssueDetail("R1", RepoPermission.READ, RepoPermission.READ.toCapabilities(), issue(), IssueCommentPage(emptyList(), false, null))
    private fun issue() = RepoIssue(id = "I1", number = 1, title = "Title", body = "body", bodyHtml = "<p>body</p>", state = IssueState.OPEN, stateReason = null, author = null, createdAt = "2026-01-01", updatedAt = "2026-01-02", commentCount = 0, labels = emptyList(), assignees = emptyList(), milestone = null, locked = false, viewerCanClose = false, viewerCanDelete = false, viewerCanLabel = false, viewerCanSetMilestone = false, viewerCanUpdate = false, viewerCanSubscribe = false, viewerCanReopen = false, viewerSubscription = null)
    private fun comment() = IssueComment("C1", null, "hello", "<p>hello</p>", "2026-01-01", "2026-01-01", true, true, true, true)
}
