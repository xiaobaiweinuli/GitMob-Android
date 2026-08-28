package com.gitmob.app.ui.repopullrequests

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.IssueCreationPolicy
import com.gitmob.app.data.model.PagedBranches
import com.gitmob.app.data.model.PullRequestCreationPolicy
import com.gitmob.app.data.model.RepoBranch
import com.gitmob.app.data.model.RepoDetail
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.data.repository.RepoPullRequestRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoPullRequestCreateViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `ordinary repository initializes base to default and leaves head empty`() = runTest {
        val detailRepository = mockk<RepoDetailRepository>()
        val gitRepository = mockk<RepoGitRepository>(relaxed = true)
        val pullRequestRepository = mockk<RepoPullRequestRepository>(relaxed = true)
        coEvery { detailRepository.getRepoDetail("octo", "repo") } returns ApiResult.Success(repo())
        val viewModel = RepoPullRequestCreateViewModel(detailRepository, gitRepository, pullRequestRepository, ErrorEventBus())

        viewModel.init("octo", "repo")
        advanceUntilIdle()

        assertEquals(RepoPullRequestCreatePage.COMPARE, viewModel.state.value.page)
        assertEquals("main", viewModel.state.value.baseBranch?.name)
        assertNull(viewModel.state.value.headBranch)
        assertFalse(viewModel.state.value.canCreate)
        coVerify(exactly = 0) { gitRepository.compare(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `selecting identical branch does not call compare`() = runTest {
        val detailRepository = mockk<RepoDetailRepository>()
        val gitRepository = mockk<RepoGitRepository>(relaxed = true)
        val pullRequestRepository = mockk<RepoPullRequestRepository>(relaxed = true)
        coEvery { detailRepository.getRepoDetail(any(), any()) } returns ApiResult.Success(repo())
        coEvery { detailRepository.getBranches("octo", "repo", null) } returns ApiResult.Success(
            PagedBranches(listOf(RepoBranch("B1", "main", true, "oid")), false, null),
        )
        val viewModel = RepoPullRequestCreateViewModel(detailRepository, gitRepository, pullRequestRepository, ErrorEventBus())
        viewModel.init("octo", "repo")
        advanceUntilIdle()

        viewModel.openHeadBranches()
        advanceUntilIdle()
        viewModel.selectBranch(viewModel.state.value.branches.single())
        advanceUntilIdle()

        assertEquals(true, viewModel.state.value.sameBranch)
        assertNull(viewModel.state.value.comparison)
        coVerify(exactly = 0) { gitRepository.compare(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `fork upstream target uses upstream as base and current fork as head repository`() = runTest {
        val detailRepository = mockk<RepoDetailRepository>()
        val gitRepository = mockk<RepoGitRepository>(relaxed = true)
        val pullRequestRepository = mockk<RepoPullRequestRepository>(relaxed = true)
        val fork = repo(owner = "viewer", name = "repo", id = "FORK", isFork = true, parentOwner = "upstream", parentName = "repo", defaultBranch = "feature")
        val upstream = repo(owner = "upstream", name = "repo", id = "BASE", defaultBranch = "main")
        coEvery { detailRepository.getRepoDetail("viewer", "repo") } returns ApiResult.Success(fork)
        coEvery { detailRepository.getRepoDetail("upstream", "repo") } returns ApiResult.Success(upstream)
        val viewModel = RepoPullRequestCreateViewModel(detailRepository, gitRepository, pullRequestRepository, ErrorEventBus())

        viewModel.init("viewer", "repo")
        advanceUntilIdle()
        assertEquals(RepoPullRequestCreatePage.TARGET, viewModel.state.value.page)

        viewModel.selectForkTarget(upstream = true)
        advanceUntilIdle()

        assertEquals("upstream", viewModel.state.value.baseRepository?.ownerLogin)
        assertEquals("main", viewModel.state.value.baseBranch?.name)
        assertEquals("viewer", viewModel.state.value.headRepository?.ownerLogin)
        assertNull(viewModel.state.value.headBranch)
    }

    @Test
    fun `reset clears temporary session and next initialization starts fork target selection again`() = runTest {
        val detailRepository = mockk<RepoDetailRepository>()
        val gitRepository = mockk<RepoGitRepository>(relaxed = true)
        val pullRequestRepository = mockk<RepoPullRequestRepository>(relaxed = true)
        val fork = repo(
            owner = "viewer",
            name = "repo",
            id = "FORK",
            isFork = true,
            parentOwner = "upstream",
            parentName = "repo",
        )
        coEvery { detailRepository.getRepoDetail("viewer", "repo") } returns ApiResult.Success(fork)

        val viewModel = RepoPullRequestCreateViewModel(detailRepository, gitRepository, pullRequestRepository, ErrorEventBus())
        viewModel.init("viewer", "repo")
        advanceUntilIdle()
        assertEquals(RepoPullRequestCreatePage.TARGET, viewModel.state.value.page)

        viewModel.resetCreateSession()
        assertEquals(RepoPullRequestCreateUiState(), viewModel.state.value)

        viewModel.init("viewer", "repo")
        advanceUntilIdle()
        assertEquals(RepoPullRequestCreatePage.TARGET, viewModel.state.value.page)
        coVerify(exactly = 2) { detailRepository.getRepoDetail("viewer", "repo") }
    }

    private fun repo(
        owner: String = "octo",
        name: String = "repo",
        id: String = "R1",
        isFork: Boolean = false,
        parentOwner: String? = null,
        parentName: String? = null,
        defaultBranch: String = "main",
    ) = RepoDetail(
        id = id,
        name = name,
        ownerLogin = owner,
        ownerAvatarUrl = null,
        description = null,
        homepageUrl = null,
        isPrivate = false,
        isArchived = false,
        isTemplate = false,
        isFork = isFork,
        forkedFromOwner = parentOwner,
        forkedFromName = parentName,
        stargazerCount = 0,
        viewerHasStarred = false,
        forkCount = 0,
        openIssueCount = 0,
        openPrCount = 0,
        watcherCount = 0,
        viewerSubscription = "UNSUBSCRIBED",
        licenseName = null,
        licenseSpdxId = null,
        branchCount = 1,
        defaultBranchName = defaultBranch,
        releaseCount = 0,
        latestReleaseName = null,
        latestReleaseTag = null,
        languageName = null,
        languageColor = null,
        topics = emptyList(),
        capabilities = RepoCapabilities.NONE,
        permission = RepoPermission.WRITE,
        viewerCanCreateIssues = true,
        hasIssuesEnabled = true,
        isBlankIssuesEnabled = true,
        issueCreationPolicy = IssueCreationPolicy.ALL,
        hasPullRequestsEnabled = true,
        pullRequestCreationPolicy = PullRequestCreationPolicy.ALL,
    )
}
