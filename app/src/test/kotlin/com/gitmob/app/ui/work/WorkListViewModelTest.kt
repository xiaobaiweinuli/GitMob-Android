package com.gitmob.app.ui.work

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.PagedWorkIssues
import com.gitmob.app.data.model.PagedWorkDiscussions
import com.gitmob.app.data.model.UserDiscussionAnswerFilter
import com.gitmob.app.data.model.UserIssueStateFilter
import com.gitmob.app.data.model.UserPullRequestStateFilter
import com.gitmob.app.data.repository.WorkRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class WorkListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val emptyPage = PagedWorkIssues(
        totalCount = 4,
        items = emptyList(),
        hasNextPage = false,
        endCursor = null,
    )

    @Test
    fun `issue view model loads only once`() = runTest {
        val repository = mockk<WorkRepository>()
        coEvery { repository.getUserIssues(any(), after = null) } returns ApiResult.Success(emptyPage)
        val viewModel = WorkIssueListViewModel(repository, ErrorEventBus())

        viewModel.loadIfNeeded()
        viewModel.loadIfNeeded()

        assertEquals(4, viewModel.state.value.totalCount)
        assertFalse(viewModel.state.value.isLoading)
        coVerify(exactly = 1) { repository.getUserIssues(any(), after = null) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `pull request view model uses pull request repository entry`() = runTest {
        val repository = mockk<WorkRepository>()
        coEvery { repository.getUserPullRequests(any(), after = null) } returns ApiResult.Success(emptyPage)
        val viewModel = WorkPullRequestListViewModel(repository, ErrorEventBus())

        viewModel.loadIfNeeded()

        assertEquals(4, viewModel.state.value.totalCount)
        assertFalse(viewModel.state.value.isLoading)
        coVerify(exactly = 1) { repository.getUserPullRequests(any(), after = null) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `issue filter selection resets data and reloads only when changed`() = runTest {
        val repository = mockk<WorkRepository>()
        coEvery { repository.getUserIssues(any(), after = null) } returns ApiResult.Success(emptyPage)
        val viewModel = WorkIssueListViewModel(repository, ErrorEventBus())
        viewModel.loadIfNeeded()

        viewModel.setStateFilter(UserIssueStateFilter.CLOSED)
        viewModel.setStateFilter(UserIssueStateFilter.CLOSED)

        assertEquals(UserIssueStateFilter.CLOSED, viewModel.state.value.filter.state)
        assertEquals(4, viewModel.state.value.totalCount)
        coVerify(exactly = 2) { repository.getUserIssues(any(), after = null) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `pull request filter selection reloads with merged state`() = runTest {
        val repository = mockk<WorkRepository>()
        coEvery { repository.getUserPullRequests(any(), after = null) } returns ApiResult.Success(emptyPage)
        val viewModel = WorkPullRequestListViewModel(repository, ErrorEventBus())
        viewModel.loadIfNeeded()

        viewModel.setStateFilter(UserPullRequestStateFilter.MERGED)
        viewModel.setStateFilter(UserPullRequestStateFilter.MERGED)

        assertEquals(UserPullRequestStateFilter.MERGED, viewModel.state.value.filter.state)
        coVerify(exactly = 2) { repository.getUserPullRequests(any(), after = null) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `discussion answer filter reloads and ignores duplicate selection`() = runTest {
        val repository = mockk<WorkRepository>()
        val page = PagedWorkDiscussions(2, emptyList(), false, null)
        coEvery { repository.getUserDiscussions(any(), after = null) } returns ApiResult.Success(page)
        val viewModel = WorkDiscussionListViewModel(repository, ErrorEventBus())
        viewModel.loadIfNeeded()

        viewModel.setAnswerFilter(UserDiscussionAnswerFilter.UNANSWERED)
        viewModel.setAnswerFilter(UserDiscussionAnswerFilter.UNANSWERED)

        assertEquals(UserDiscussionAnswerFilter.UNANSWERED, viewModel.state.value.filter.answer)
        assertEquals(2, viewModel.state.value.totalCount)
        coVerify(exactly = 2) { repository.getUserDiscussions(any(), after = null) }
        viewModel.viewModelScope.cancel()
    }
}
