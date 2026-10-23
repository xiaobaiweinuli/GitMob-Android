package com.gitmob.app.ui.repocode

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoCodeTree
import com.gitmob.app.data.model.ExistingRepoPullRequest
import com.gitmob.app.data.model.RepoForkSyncInfo
import com.gitmob.app.data.model.RepoForkSyncStatus
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.data.repository.RepoPullRequestRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoCodeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `folder export reports progress and writes generated zip`() = runTest {
        val repository = mockk<RepoGitRepository>()
        coEvery { repository.getCodeTree("o", "r", "main", "docs") } returns ApiResult.Success(tree())
        val expected = byteArrayOf(1, 2, 3)
        coEvery { repository.createFolderZip("o", "r", "main", "docs", any()) } coAnswers {
            val progress = arg<(Int, Int) -> Unit>(4)
            progress(1, 2)
            progress(2, 2)
            ApiResult.Success(expected)
        }
        val viewModel = RepoCodeViewModel(repository, mockk<RepoPullRequestRepository>(), ErrorEventBus(), mockk<ExternalDownloadLauncher>(), RepoUpdateEventBus())
        viewModel.init("o", "r", "main", "docs", RepoPermission.READ)
        advanceUntilIdle()

        var saved: ByteArray? = null
        viewModel.exportCurrentFolder { saved = it }
        advanceUntilIdle()

        assertArrayEquals(expected, saved)
        assertFalse(viewModel.state.value.isFolderExporting)
        assertEquals(0, viewModel.state.value.folderExportCompleted)
        assertEquals(0, viewModel.state.value.folderExportTotal)
        coVerify(exactly = 1) { repository.createFolderZip("o", "r", "main", "docs", any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `folder export can be cancelled without invoking writer`() = runTest {
        val repository = mockk<RepoGitRepository>()
        coEvery { repository.getCodeTree("o", "r", "main", "docs") } returns ApiResult.Success(tree())
        coEvery { repository.createFolderZip("o", "r", "main", "docs", any()) } coAnswers {
            suspendCancellableCoroutine { }
        }
        val viewModel = RepoCodeViewModel(repository, mockk<RepoPullRequestRepository>(), ErrorEventBus(), mockk<ExternalDownloadLauncher>(), RepoUpdateEventBus())
        viewModel.init("o", "r", "main", "docs", RepoPermission.READ)
        advanceUntilIdle()

        var writerCalled = false
        viewModel.exportCurrentFolder { writerCalled = true }
        assertTrue(viewModel.state.value.isFolderExporting)
        viewModel.cancelFolderExport()
        advanceUntilIdle()

        assertFalse(writerCalled)
        assertFalse(viewModel.state.value.isFolderExporting)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `folder zip filename keeps repository folder and branch identity`() {
        assertEquals("repo-guides-feature-docs.zip", folderZipFileName("repo", "feature/docs", "docs/guides"))
    }

    @Test
    fun `archive download delegates signed url to external launcher`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val launcher = mockk<ExternalDownloadLauncher>(relaxed = true)
        coEvery { repository.getCodeTree("o", "r", "main", "") } returns ApiResult.Success(tree().copy(path = ""))
        coEvery { repository.resolveArchiveDownloadUrl("o", "r", "main") } returns ApiResult.Success("https://example.test/archive.zip")
        val viewModel = RepoCodeViewModel(repository, mockk<RepoPullRequestRepository>(), ErrorEventBus(), launcher, RepoUpdateEventBus())
        viewModel.init("o", "r", "main", "", RepoPermission.READ)
        advanceUntilIdle()

        viewModel.downloadArchive()
        advanceUntilIdle()

        verify(exactly = 1) { launcher.open("https://example.test/archive.zip") }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `failed sync refresh clears comparison and discard confirmation`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val sync = syncInfo()
        coEvery { repository.getCodeTree("viewer", "fork", "main", "") } returns ApiResult.Success(
            tree().copy(isFork = true, capabilities = RepoPermission.WRITE.toCapabilities()),
        )
        coEvery { repository.getForkSync("viewer", "fork", "main") } returnsMany listOf(
            ApiResult.Success(sync), ApiResult.Failure(ApiError.NetworkError),
        )
        val vm = RepoCodeViewModel(repository, mockk(), ErrorEventBus(), mockk(), RepoUpdateEventBus())
        vm.init("viewer", "fork", "main", "", RepoPermission.WRITE)
        vm.openSync()
        advanceUntilIdle()
        vm.showDiscardConfirmation(true)
        vm.openSync()
        advanceUntilIdle()

        assertNull(vm.state.value.syncInfo)
        assertFalse(vm.state.value.showDiscardConfirmation)
        vm.mergeUpstream()
        vm.discardAndAlign("main")
        coVerify(exactly = 0) { repository.mergeUpstream(any(), any(), any()) }
        coVerify(exactly = 0) { repository.forceAlignFork(any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `changed SHA closes discard confirmation and requires a new review`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val sync = syncInfo()
        coEvery { repository.getCodeTree("viewer", "fork", "main", "") } returns ApiResult.Success(
            tree().copy(isFork = true, capabilities = RepoPermission.WRITE.toCapabilities()),
        )
        coEvery { repository.getForkSync("viewer", "fork", "main") } returnsMany listOf(
            ApiResult.Success(sync), ApiResult.Success(sync.copy(currentOid = "new-head")),
        )
        coEvery { repository.forceAlignFork("viewer", "fork", sync) } returns
            ApiResult.Failure(ApiError.UserVisible(R.string.repo_sync_head_changed))
        val vm = RepoCodeViewModel(repository, mockk(), ErrorEventBus(), mockk(), RepoUpdateEventBus())
        vm.init("viewer", "fork", "main", "", RepoPermission.WRITE)
        vm.openSync()
        advanceUntilIdle()
        vm.showDiscardConfirmation(true)
        vm.discardAndAlign("main")
        advanceUntilIdle()

        assertFalse(vm.state.value.showDiscardConfirmation)
        assertEquals("new-head", vm.state.value.syncInfo?.currentOid)
        vm.discardAndAlign("main")
        coVerify(exactly = 1) { repository.forceAlignFork(any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    private fun syncInfo() = RepoForkSyncInfo(
        repositoryId = "fork-id", branch = "main", currentOid = "forksha",
        upstreamRepositoryId = "upstream-id", upstreamOwner = "upstream", upstreamName = "repo", upstreamOid = "upsha",
        status = RepoForkSyncStatus.READY, aheadBy = 2, behindBy = 3,
    )

    @Test
    fun `sync pull request checks fork base and upstream head before opening existing`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val pullRequests = mockk<RepoPullRequestRepository>()
        val sync = RepoForkSyncInfo(
            repositoryId = "fork-id",
            branch = "main",
            currentOid = "forksha",
            upstreamRepositoryId = "upstream-id",
            upstreamOwner = "upstream",
            upstreamName = "repo",
            upstreamOid = "upsha",
            status = RepoForkSyncStatus.READY,
            behindBy = 2,
        )
        coEvery { repository.getCodeTree("viewer", "fork", "main", "") } returns ApiResult.Success(tree().copy(isFork = true, path = ""))
        coEvery { repository.getForkSync("viewer", "fork", "main") } returns ApiResult.Success(sync)
        coEvery {
            pullRequests.findOpenPullRequest("viewer", "fork", "main", "upstream", "main")
        } returns ApiResult.Success(ExistingRepoPullRequest(12, "Sync", "https://example.test/pr/12"))
        val viewModel = RepoCodeViewModel(repository, pullRequests, ErrorEventBus(), mockk<ExternalDownloadLauncher>(), RepoUpdateEventBus())
        viewModel.init("viewer", "fork", "main", "", RepoPermission.WRITE)
        advanceUntilIdle()
        viewModel.openSync()
        advanceUntilIdle()

        var existing: Int? = null
        var created = false
        viewModel.openSyncPullRequest({ existing = it }, { created = true })
        coVerify(exactly = 0) { pullRequests.findOpenPullRequest(any(), any(), any(), any(), any()) }
        viewModel.showSyncPullRequestConfirmation(true)
        viewModel.openSyncPullRequest({ existing = it }, { created = true })
        advanceUntilIdle()

        assertEquals(12, existing)
        assertFalse(created)
        assertFalse(viewModel.state.value.isSyncDialogOpen)
        assertFalse(viewModel.state.value.showSyncPullRequestConfirmation)
        coVerify(exactly = 1) {
            pullRequests.findOpenPullRequest("viewer", "fork", "main", "upstream", "main")
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `failed PR lookup keeps direction confirmation and retry opens editor only when no PR exists`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val pullRequests = mockk<RepoPullRequestRepository>()
        coEvery { pullRequests.findOpenPullRequest("viewer", "fork", "main", "upstream", "main") } returnsMany listOf(
            ApiResult.Failure(ApiError.Forbidden), ApiResult.Success(null),
        )
        val vm = syncViewModel(repository, pullRequests)
        vm.showSyncPullRequestConfirmation(true)
        var created: RepoForkSyncInfo? = null
        vm.openSyncPullRequest({ error("Unexpected existing PR") }, { created = it })
        advanceUntilIdle()

        assertNull(created)
        assertFalse(vm.state.value.isSyncBusy)
        assertTrue(vm.state.value.showSyncPullRequestConfirmation)
        vm.openSyncPullRequest({ error("Unexpected existing PR") }, { created = it })
        advanceUntilIdle()
        assertEquals(syncInfo(), created)
        assertFalse(vm.state.value.isSyncDialogOpen)
        coVerify(exactly = 2) { pullRequests.findOpenPullRequest("viewer", "fork", "main", "upstream", "main") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `PR lookup blocks duplicate requests merge discard refresh and dismiss`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val pullRequests = mockk<RepoPullRequestRepository>()
        val pending = CompletableDeferred<ApiResult<ExistingRepoPullRequest?>>()
        coEvery { pullRequests.findOpenPullRequest(any(), any(), any(), any(), any()) } coAnswers { pending.await() }
        val vm = syncViewModel(repository, pullRequests)
        var createCount = 0
        vm.showSyncPullRequestConfirmation(true)
        vm.openSyncPullRequest({}, { createCount++ })
        assertTrue(vm.state.value.isCheckingSyncPullRequest)

        vm.openSyncPullRequest({}, { createCount++ })
        vm.mergeUpstream()
        vm.showDiscardConfirmation(true)
        vm.discardAndAlign("main")
        vm.openSync()
        vm.showSyncPullRequestConfirmation(false)
        vm.dismissSync()

        assertTrue(vm.state.value.isSyncDialogOpen)
        assertTrue(vm.state.value.showSyncPullRequestConfirmation)
        assertFalse(vm.state.value.showDiscardConfirmation)
        coVerify(exactly = 1) { pullRequests.findOpenPullRequest(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { repository.getForkSync(any(), any(), any()) }
        coVerify(exactly = 0) { repository.mergeUpstream(any(), any(), any()) }
        coVerify(exactly = 0) { repository.forceAlignFork(any(), any(), any()) }
        pending.complete(ApiResult.Success(null))
        advanceUntilIdle()
        assertEquals(1, createCount)
        assertFalse(vm.state.value.isSyncBusy)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `merge is exclusive and success refreshes comparison and publishes branch update`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val pullRequests = mockk<RepoPullRequestRepository>()
        val events = RepoUpdateEventBus()
        val pending = CompletableDeferred<ApiResult<Unit>>()
        coEvery { repository.mergeUpstream("viewer", "fork", "main") } coAnswers { pending.await() }
        val vm = syncViewModel(repository, pullRequests, events = events)
        val updated = syncInfo().copy(currentOid = "merged-head", behindBy = 0)
        coEvery { repository.getForkSync("viewer", "fork", "main") } returns ApiResult.Success(updated)

        events.events.test {
            vm.mergeUpstream()
            assertTrue(vm.state.value.isSyncing)
            vm.mergeUpstream()
            vm.showDiscardConfirmation(true)
            vm.discardAndAlign("main")
            vm.showSyncPullRequestConfirmation(true)
            vm.openSyncPullRequest({}, {})
            vm.openSync()
            vm.dismissSync()

            assertTrue(vm.state.value.isSyncDialogOpen)
            assertFalse(vm.state.value.showDiscardConfirmation)
            assertFalse(vm.state.value.showSyncPullRequestConfirmation)
            coVerify(exactly = 1) { repository.mergeUpstream(any(), any(), any()) }
            coVerify(exactly = 1) { repository.getForkSync(any(), any(), any()) }
            coVerify(exactly = 0) { repository.forceAlignFork(any(), any(), any()) }
            coVerify(exactly = 0) { pullRequests.findOpenPullRequest(any(), any(), any(), any(), any()) }

            pending.complete(ApiResult.Success(Unit))
            advanceUntilIdle()
            assertEquals(RepoUpdateEvent.BranchSynced("viewer", "fork", "main"), awaitItem())
        }
        assertFalse(vm.state.value.isSyncBusy)
        assertEquals(updated, vm.state.value.syncInfo)
        coVerify(exactly = 2) { repository.getCodeTree("viewer", "fork", "main", "") }
        coVerify(exactly = 2) { repository.getForkSync("viewer", "fork", "main") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `discard requires exact confirmation blocks other operations and retains confirmation after failure`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val pullRequests = mockk<RepoPullRequestRepository>()
        val pending = CompletableDeferred<ApiResult<Unit>>()
        coEvery { repository.forceAlignFork("viewer", "fork", syncInfo()) } coAnswers { pending.await() }
        val vm = syncViewModel(repository, pullRequests)
        vm.discardAndAlign("main")
        vm.showDiscardConfirmation(true)
        vm.discardAndAlign("Main")
        coVerify(exactly = 0) { repository.forceAlignFork(any(), any(), any()) }

        vm.mergeUpstream()
        vm.showSyncPullRequestConfirmation(true)
        assertFalse(vm.state.value.showSyncPullRequestConfirmation)
        vm.discardAndAlign("main")
        assertTrue(vm.state.value.isSyncing)
        vm.discardAndAlign("main")
        vm.openSync()
        vm.showDiscardConfirmation(false)
        vm.dismissSync()
        assertTrue(vm.state.value.showDiscardConfirmation)
        coVerify(exactly = 1) { repository.forceAlignFork(any(), any(), any()) }
        coVerify(exactly = 1) { repository.getForkSync(any(), any(), any()) }
        coVerify(exactly = 0) { repository.mergeUpstream(any(), any(), any()) }
        coVerify(exactly = 0) { pullRequests.findOpenPullRequest(any(), any(), any(), any(), any()) }

        pending.complete(ApiResult.Failure(ApiError.Forbidden))
        advanceUntilIdle()
        assertFalse(vm.state.value.isSyncBusy)
        assertTrue(vm.state.value.showDiscardConfirmation)
        assertEquals(syncInfo(), vm.state.value.syncInfo)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `refresh immediately invalidates comparison and PR confirmation while request is pending`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val pullRequests = mockk<RepoPullRequestRepository>()
        val vm = syncViewModel(repository, pullRequests)
        vm.showSyncPullRequestConfirmation(true)
        val pending = CompletableDeferred<ApiResult<RepoForkSyncInfo>>()
        coEvery { repository.getForkSync("viewer", "fork", "main") } coAnswers { pending.await() }
        vm.openSync()

        assertTrue(vm.state.value.isLoadingSync)
        assertNull(vm.state.value.syncInfo)
        assertFalse(vm.state.value.showSyncPullRequestConfirmation)
        vm.openSync()
        vm.mergeUpstream()
        vm.showDiscardConfirmation(true)
        vm.discardAndAlign("main")
        vm.showSyncPullRequestConfirmation(true)
        vm.openSyncPullRequest({}, {})
        coVerify(exactly = 2) { repository.getForkSync(any(), any(), any()) }
        coVerify(exactly = 0) { repository.mergeUpstream(any(), any(), any()) }
        coVerify(exactly = 0) { repository.forceAlignFork(any(), any(), any()) }
        coVerify(exactly = 0) { pullRequests.findOpenPullRequest(any(), any(), any(), any(), any()) }

        pending.complete(ApiResult.Failure(ApiError.NetworkError))
        advanceUntilIdle()
        assertFalse(vm.state.value.isSyncBusy)
        assertNull(vm.state.value.syncInfo)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `archived fork offers no merge discard or sync PR`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val pullRequests = mockk<RepoPullRequestRepository>()
        val vm = syncViewModel(repository, pullRequests, archived = true)
        vm.mergeUpstream()
        vm.showDiscardConfirmation(true)
        vm.discardAndAlign("main")
        vm.showSyncPullRequestConfirmation(true)
        vm.openSyncPullRequest({}, {})

        assertFalse(vm.state.value.capabilities.canPush)
        assertFalse(vm.state.value.showDiscardConfirmation)
        assertFalse(vm.state.value.showSyncPullRequestConfirmation)
        coVerify(exactly = 0) { repository.mergeUpstream(any(), any(), any()) }
        coVerify(exactly = 0) { repository.forceAlignFork(any(), any(), any()) }
        coVerify(exactly = 0) { pullRequests.findOpenPullRequest(any(), any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `read permission can review a sync PR but cannot merge or discard`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val vm = syncViewModel(repository, permission = RepoPermission.READ)
        vm.mergeUpstream()
        vm.showDiscardConfirmation(true)
        vm.discardAndAlign("main")
        assertFalse(vm.state.value.showDiscardConfirmation)
        vm.showSyncPullRequestConfirmation(true)
        assertTrue(vm.state.value.showSyncPullRequestConfirmation)
        coVerify(exactly = 0) { repository.mergeUpstream(any(), any(), any()) }
        coVerify(exactly = 0) { repository.forceAlignFork(any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `unavailable comparisons and identical heads cannot start sync operations`() = runTest {
        val comparisons = RepoForkSyncStatus.entries.filter { it != RepoForkSyncStatus.READY }
            .map { syncInfo().copy(status = it) } + syncInfo().copy(aheadBy = 0, behindBy = 0)
        comparisons.forEach { comparison ->
            val repository = mockk<RepoGitRepository>()
            val pullRequests = mockk<RepoPullRequestRepository>()
            val vm = syncViewModel(repository, pullRequests, comparison = comparison)
            vm.mergeUpstream()
            vm.showDiscardConfirmation(true)
            vm.discardAndAlign("main")
            vm.showSyncPullRequestConfirmation(true)
            vm.openSyncPullRequest({}, {})
            assertFalse(vm.state.value.showDiscardConfirmation)
            assertFalse(vm.state.value.showSyncPullRequestConfirmation)
            coVerify(exactly = 0) { repository.mergeUpstream(any(), any(), any()) }
            coVerify(exactly = 0) { repository.forceAlignFork(any(), any(), any()) }
            coVerify(exactly = 0) { pullRequests.findOpenPullRequest(any(), any(), any(), any(), any()) }
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `merge conflict remains visible but other errors are not labeled conflicts`() = runTest {
        val repository = mockk<RepoGitRepository>()
        val vm = syncViewModel(repository)
        coEvery { repository.mergeUpstream("viewer", "fork", "main") } returnsMany listOf(
            ApiResult.Failure(ApiError.UserVisible(R.string.repo_sync_conflict)),
            ApiResult.Failure(ApiError.Forbidden),
        )
        vm.mergeUpstream()
        advanceUntilIdle()
        assertTrue(vm.state.value.syncHasConflict)
        assertFalse(vm.state.value.isSyncBusy)
        assertEquals(syncInfo(), vm.state.value.syncInfo)

        vm.mergeUpstream()
        advanceUntilIdle()
        assertFalse(vm.state.value.syncHasConflict)
        assertFalse(vm.state.value.isSyncBusy)
        assertEquals(syncInfo(), vm.state.value.syncInfo)
        vm.viewModelScope.cancel()
    }

    private fun syncViewModel(
        repository: RepoGitRepository,
        pullRequests: RepoPullRequestRepository = mockk(),
        archived: Boolean = false,
        permission: RepoPermission = RepoPermission.WRITE,
        comparison: RepoForkSyncInfo = syncInfo(),
        events: RepoUpdateEventBus = RepoUpdateEventBus(),
    ): RepoCodeViewModel {
        coEvery { repository.getCodeTree("viewer", "fork", "main", "") } returns ApiResult.Success(
            tree().copy(isFork = true, path = "", permission = permission, isArchived = archived, capabilities = permission.toCapabilities(archived)),
        )
        coEvery { repository.getForkSync("viewer", "fork", "main") } returns ApiResult.Success(comparison)
        return RepoCodeViewModel(repository, pullRequests, ErrorEventBus(), mockk(), events).apply {
            init("viewer", "fork", "main", "", permission)
            openSync()
        }
    }

    private fun tree() = RepoCodeTree(
        repositoryId = "R1",
        permission = RepoPermission.READ,
        capabilities = RepoPermission.READ.toCapabilities(),
        isArchived = false,
        ref = "main",
        headOid = "head",
        path = "docs",
        entries = emptyList(),
    )
}
