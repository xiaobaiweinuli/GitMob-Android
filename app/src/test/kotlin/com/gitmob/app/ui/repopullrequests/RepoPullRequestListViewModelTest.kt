package com.gitmob.app.ui.repopullrequests

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.BannerEvent
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.IssueLabel
import com.gitmob.app.data.model.PullRequestCreationPolicy
import com.gitmob.app.data.model.RepoPullRequestPage
import com.gitmob.app.data.repository.RepoPullRequestRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoPullRequestListViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `初始化成功加载仓库标签`() = runTest {
        val labels = listOf(IssueLabel("L1", "bug", "d73a4a", null))
        val repository = repositoryMock()
        coEvery { repository.getLabels("o", "r") } returns ApiResult.Success(labels)
        val viewModel = RepoPullRequestListViewModel(repository, ErrorEventBus())

        viewModel.init("o", "r", RepoPermission.READ)
        advanceUntilIdle()

        assertEquals(labels, viewModel.state.value.labels)
        coVerify(exactly = 1) { repository.getLabels("o", "r") }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `标签加载失败发送全局错误事件`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getLabels("o", "r") } returns ApiResult.Failure(ApiError.NetworkError)
        val errorEventBus = ErrorEventBus()
        val event = async(start = CoroutineStart.UNDISPATCHED) { errorEventBus.events.first() }
        val viewModel = RepoPullRequestListViewModel(repository, errorEventBus)

        viewModel.init("o", "r", RepoPermission.READ)
        advanceUntilIdle()

        assertEquals(BannerEvent.Error(ApiError.NetworkError), event.await())
        assertEquals(emptyList<IssueLabel>(), viewModel.state.value.labels)
        viewModel.viewModelScope.cancel()
    }

    private fun repositoryMock(): RepoPullRequestRepository = mockk {
        coEvery { getPullRequests(any(), any(), any(), null) } returns ApiResult.Success(emptyPage())
        coEvery { getLabels(any(), any()) } returns ApiResult.Success(emptyList())
    }

    private fun emptyPage() = RepoPullRequestPage(
        repositoryId = "R1",
        permission = RepoPermission.READ,
        capabilities = RepoCapabilities.NONE,
        hasPullRequestsEnabled = true,
        creationPolicy = PullRequestCreationPolicy.ALL,
        defaultBranchName = "main",
        allowedMergeMethods = emptySet(),
        totalCount = 0,
        items = emptyList(),
        hasNextPage = false,
        endCursor = null,
    )
}
