package com.gitmob.app.ui.common

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.core.share.ShareLinkLauncher
import com.gitmob.app.data.model.RepoPullRequest
import com.gitmob.app.data.model.RepoPullRequestDetail
import com.gitmob.app.data.model.RepoRelease
import com.gitmob.app.data.model.RepoWorkflowRun
import com.gitmob.app.data.model.RepoWorkflowRunDetail
import com.gitmob.app.data.repository.RepoActionsRepository
import com.gitmob.app.data.repository.RepoPullRequestRepository
import com.gitmob.app.data.repository.RepoReleaseRepository
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.ui.repoactions.RepoWorkflowRunViewModel
import com.gitmob.app.ui.repopullrequests.RepoPullRequestDetailViewModel
import com.gitmob.app.ui.reporeleases.RepoReleaseDetailViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailShareViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `release shares response URL with read permission and ignores missing or blank data`() = runTest {
        val url = "https://github.com/o/r/releases/tag/untagged-draft"
        val release = mockk<RepoRelease> { every { htmlUrl } returns url }
        val empty = mockk<RepoRelease> { every { htmlUrl } returns "" }
        val repository = mockk<RepoReleaseRepository>()
        coEvery { repository.getRelease("o", "r", 7) } returns ApiResult.Success(release) andThen ApiResult.Success(empty)
        val share = mockk<ShareLinkLauncher>(relaxUnitFun = true)
        val vm = RepoReleaseDetailViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), share)
        vm.share()
        verify(exactly = 0) { share.share(any()) }
        vm.init("o", "r", null, 7, RepoPermission.READ); advanceUntilIdle()
        vm.share()
        verify(exactly = 1) { share.share(url) }
        vm.retry(); advanceUntilIdle()
        vm.share()
        verify(exactly = 1) { share.share(any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `run shares this run rather than workflow URL with read permission`() = runTest {
        val url = "https://github.com/o/r/actions/runs/7"
        val run = mockk<RepoWorkflowRun> { every { htmlUrl } returns url }
        val detail = RepoWorkflowRunDetail(run, emptyList(), emptyList())
        val repository = mockk<RepoActionsRepository>()
        coEvery { repository.getRun("o", "r", 7) } returns ApiResult.Success(detail)
        val share = mockk<ShareLinkLauncher>(relaxUnitFun = true)
        val vm = RepoWorkflowRunViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), share)
        vm.share()
        verify(exactly = 0) { share.share(any()) }
        vm.init("o", "r", 7, RepoPermission.READ); advanceUntilIdle()
        vm.share()
        verify(exactly = 1) { share.share(url) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `pull request shares response URL with read permission and ignores empty URL`() = runTest {
        val expectedUrl = "https://github.com/o/r/pull/7"
        val pr = mockk<RepoPullRequest> { every { url } returns expectedUrl }
        val empty = mockk<RepoPullRequest> { every { url } returns "" }
        val detail = RepoPullRequestDetail("R1", RepoPermission.READ, RepoPermission.READ.toCapabilities(), emptySet(), pr,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false, null)
        val repository = mockk<RepoPullRequestRepository>()
        coEvery { repository.getPullRequest("o", "r", 7, null) } returns ApiResult.Success(detail) andThen ApiResult.Success(detail.copy(pullRequest = empty))
        val share = mockk<ShareLinkLauncher>(relaxUnitFun = true)
        val vm = RepoPullRequestDetailViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk(), share)
        vm.share()
        verify(exactly = 0) { share.share(any()) }
        vm.init("o", "r", 7, RepoPermission.READ); advanceUntilIdle()
        vm.share()
        verify(exactly = 1) { share.share(expectedUrl) }
        vm.retry(); advanceUntilIdle()
        vm.share()
        verify(exactly = 1) { share.share(any()) }
        vm.viewModelScope.cancel()
    }
}
