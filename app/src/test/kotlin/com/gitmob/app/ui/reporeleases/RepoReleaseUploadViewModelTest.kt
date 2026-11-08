package com.gitmob.app.ui.reporeleases

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoRelease
import com.gitmob.app.data.model.RepoReleaseAsset
import com.gitmob.app.data.repository.RepoReleaseRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class RepoReleaseUploadViewModelTest {
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts)
    private val repository = mockk<RepoReleaseRepository>()
    private val errors = mockk<ErrorEventBus>(relaxed = true)
    private val uri = mockk<Uri>()
    private val asset = RepoReleaseAsset(8, "asset.zip", null, "application/zip", "uploaded", 100, 0, "", "")
    private val release = RepoRelease(7, "R7", "v1", "main", "Title", "", null, true, false, "", null, null, "https://uploads.example/release{?name,label}", emptyList(), null, null)
    private fun vm(permission: RepoPermission = RepoPermission.WRITE): RepoReleaseDetailViewModel {
        coEvery { repository.getRelease("o", "r", 7) } returns ApiResult.Success(release)
        return RepoReleaseDetailViewModel(repository, errors, RepoUpdateEventBus(), mockk(relaxed = true), accounts.session).also { it.init("o", "r", null, 7, permission) }
    }

    @Test fun preparingAndUploadingBlockDuplicatesAndSuccessAppendsOneAsset() = runTest {
        val response = CompletableDeferred<ApiResult<RepoReleaseAsset>>()
        lateinit var progress: (Long, Long) -> Unit
        coEvery { repository.uploadAsset(any(), any(), any()) } coAnswers { progress = thirdArg(); response.await() }
        val vm = vm(); vm.uploadAsset(uri); vm.uploadAsset(uri)
        assertEquals(ReleaseAssetUploadPhase.PREPARING, vm.state.value.upload.phase)
        progress(0, 100)
        assertEquals(ReleaseAssetUploadPhase.UPLOADING, vm.state.value.upload.phase)
        progress(50, 100)
        assertEquals(50L, vm.state.value.upload.sent)
        vm.uploadAsset(uri)
        response.complete(ApiResult.Success(asset))
        assertEquals(ReleaseAssetUploadPhase.SUCCESS, vm.state.value.upload.phase)
        assertEquals(listOf(asset), vm.state.value.release?.assets)
        progress(90, 100)
        assertEquals(ReleaseAssetUploadPhase.SUCCESS, vm.state.value.upload.phase)
        coVerify(exactly = 1) { repository.uploadAsset(release.uploadUrl, uri, any()) }
    }

    @Test fun failureRetainsAssetsAndAllowsASeparateRetry() = runTest {
        coEvery { repository.uploadAsset(any(), any(), any()) } returns ApiResult.Failure(ApiError.NetworkError) andThen ApiResult.Success(asset)
        val vm = vm(); vm.uploadAsset(uri)
        assertEquals(ReleaseAssetUploadPhase.FAILURE, vm.state.value.upload.phase)
        assertEquals(emptyList<RepoReleaseAsset>(), vm.state.value.release?.assets)
        coVerify(exactly = 1) { errors.emit(ApiError.NetworkError, any()) }
        vm.uploadAsset(uri)
        assertEquals(listOf(asset), vm.state.value.release?.assets)
        assertEquals(ReleaseAssetUploadPhase.SUCCESS, vm.state.value.upload.phase)
    }

    @Test fun cancelDuringPreparationStopsWorkWithoutAnErrorOrAsset() = runTest {
        var cancelled = false
        coEvery { repository.uploadAsset(any(), any(), any()) } coAnswers { try { awaitCancellation() } finally { cancelled = true } }
        val vm = vm(); vm.uploadAsset(uri); vm.cancelUpload()
        assertTrue(cancelled)
        assertEquals(ReleaseAssetUploadPhase.CANCELLED, vm.state.value.upload.phase)
        assertTrue(vm.state.value.release!!.assets.isEmpty())
        coVerify(exactly = 1) { repository.getRelease("o", "r", 7) }
        coVerify(exactly = 0) { errors.emit(any(), any()) }
    }

    @Test fun cancellationWaitsForCleanupBeforeAcceptingTheNextUpload() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<(Long, Long) -> Unit>()
        coEvery { repository.uploadAsset(any(), any(), any()) } coAnswers {
            callbacks += thirdArg<(Long, Long) -> Unit>()
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() } }
        }
        val vm = vm()
        vm.uploadAsset(uri)
        callbacks[0](10, 100)
        vm.cancelUpload()
        assertTrue(vm.state.value.upload.isCancelling)
        vm.uploadAsset(uri)
        callbacks[0](99, 100)
        assertEquals(ReleaseAssetUploadPhase.UPLOADING, vm.state.value.upload.phase)
        assertEquals(10L, vm.state.value.upload.sent)
        coVerify(exactly = 1) { repository.uploadAsset(any(), any(), any()) }

        cleanup.complete(Unit)
        assertEquals(ReleaseAssetUploadPhase.CANCELLED, vm.state.value.upload.phase)
        assertFalse(vm.state.value.upload.isCancelling)
        vm.uploadAsset(uri)
        assertEquals(ReleaseAssetUploadPhase.PREPARING, vm.state.value.upload.phase)
        assertEquals(0L, vm.state.value.upload.sent)
        coVerify(exactly = 2) { repository.uploadAsset(any(), any(), any()) }
        callbacks[1](20, 200)
        assertEquals(20L, vm.state.value.upload.sent)
        vm.cancelUpload()
    }

    @Test fun accountSwitchCancelsUploadAndRejectsOldAccountActions() = runTest {
        var cancelled = false
        coEvery { repository.uploadAsset(any(), any(), any()) } coAnswers { try { thirdArg<(Long, Long) -> Unit>().invoke(100, 100); awaitCancellation() } finally { cancelled = true } }
        val vm = vm(); vm.uploadAsset(uri)
        accounts.session.replace(emptyList(), null)
        assertTrue(cancelled)
        assertEquals(ReleaseAssetUploadPhase.CANCELLED, vm.state.value.upload.phase)
        vm.uploadAsset(uri)
        coVerify(exactly = 1) { repository.uploadAsset(any(), any(), any()) }
        coVerify(exactly = 1) { repository.getRelease("o", "r", 7) }
        coVerify(exactly = 0) { errors.emit(any(), any()) }
    }

    @Test fun clearingDetailViewModelCancelsUpload() = runTest {
        var cancelled = false
        coEvery { repository.uploadAsset(any(), any(), any()) } coAnswers { try { thirdArg<(Long, Long) -> Unit>().invoke(100, 100); awaitCancellation() } finally { cancelled = true } }
        val vm = vm(); val store = ViewModelStore(); store.put("detail", vm)
        vm.uploadAsset(uri); store.clear()
        assertTrue(cancelled)
        assertEquals(ReleaseAssetUploadPhase.CANCELLED, vm.state.value.upload.phase)
        coVerify(exactly = 1) { repository.getRelease("o", "r", 7) }
    }


    @Test fun cancelAfterAllBytesReloadsServerCommittedAsset() = runTest {
        assertCancelledUploadReloadsAsset(sent = 100, total = 100)
    }

    @Test fun cancelEmptyUploadReloadsServerCommittedAsset() = runTest {
        assertCancelledUploadReloadsAsset(sent = 0, total = 0)
    }

    @Test fun cancelWithoutFinalProgressStillReloadsServerState() = runTest {
        assertCancelledUploadReloadsAsset(sent = 10, total = 100)
    }

    private fun assertCancelledUploadReloadsAsset(sent: Long, total: Long) {
        val vm = vm()
        val uploaded = asset.copy(size = total)
        coEvery { repository.getRelease("o", "r", 7) } returns ApiResult.Success(release.copy(assets = listOf(uploaded)))
        coEvery { repository.uploadAsset(any(), any(), any()) } coAnswers {
            thirdArg<(Long, Long) -> Unit>().invoke(sent, total)
            awaitCancellation()
        }
        vm.uploadAsset(uri)
        vm.cancelUpload()
        assertEquals(ReleaseAssetUploadPhase.CANCELLED, vm.state.value.upload.phase)
        assertEquals(listOf(uploaded), vm.state.value.release!!.assets)
        coVerify(exactly = 2) { repository.getRelease("o", "r", 7) }
        coVerify(exactly = 1) { repository.uploadAsset(any(), any(), any()) }
        coVerify(exactly = 0) { errors.emit(any(), any()) }
    }

    @Test fun cancellationRefreshFinishesBeforeAnotherUploadCanStart() = runTest {
        val vm = vm()
        val refresh = CompletableDeferred<ApiResult<RepoRelease>>()
        val nextAsset = asset.copy(id = 9, name = "second.zip")
        coEvery { repository.getRelease("o", "r", 7) } coAnswers { refresh.await() }
        var uploads = 0
        coEvery { repository.uploadAsset(any(), any(), any()) } coAnswers {
            if (++uploads == 1) {
                thirdArg<(Long, Long) -> Unit>().invoke(100, 100)
                awaitCancellation()
            } else ApiResult.Success(nextAsset)
        }
        vm.uploadAsset(uri)
        vm.cancelUpload()
        assertTrue(vm.state.value.isLoading)
        vm.uploadAsset(uri)
        coVerify(exactly = 1) { repository.uploadAsset(any(), any(), any()) }
        refresh.complete(ApiResult.Success(release.copy(assets = listOf(asset))))
        assertFalse(vm.state.value.isLoading)
        vm.uploadAsset(uri)
        assertEquals(listOf(asset, nextAsset), vm.state.value.release!!.assets)
        assertEquals(ReleaseAssetUploadPhase.SUCCESS, vm.state.value.upload.phase)
        coVerify(exactly = 2) { repository.uploadAsset(any(), any(), any()) }
    }

    @Test fun manualRefreshFindsAssetCommittedAfterAutomaticRefresh() = runTest {
        coEvery { repository.uploadAsset(any(), any(), any()) } coAnswers {
            thirdArg<(Long, Long) -> Unit>().invoke(100, 100)
            awaitCancellation()
        }
        val vm = vm()
        vm.uploadAsset(uri)
        vm.cancelUpload()
        assertTrue(vm.state.value.release!!.assets.isEmpty())
        coVerify(exactly = 2) { repository.getRelease("o", "r", 7) }
        coEvery { repository.getRelease("o", "r", 7) } returns ApiResult.Success(release.copy(assets = listOf(asset)))
        vm.load()
        assertEquals(listOf(asset), vm.state.value.release!!.assets)
        assertEquals(ReleaseAssetUploadPhase.CANCELLED, vm.state.value.upload.phase)
        coVerify(exactly = 3) { repository.getRelease("o", "r", 7) }
        coVerify(exactly = 1) { repository.uploadAsset(any(), any(), any()) }
    }

    @Test fun failedCancellationRefreshCanRetryWithoutRestartingUpload() = runTest {
        val vm = vm()
        coEvery { repository.getRelease("o", "r", 7) } returns ApiResult.Failure(ApiError.NetworkError)
        coEvery { repository.uploadAsset(any(), any(), any()) } coAnswers {
            thirdArg<(Long, Long) -> Unit>().invoke(100, 100)
            awaitCancellation()
        }
        vm.uploadAsset(uri)
        vm.cancelUpload()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.isLoading)
        coEvery { repository.getRelease("o", "r", 7) } returns ApiResult.Success(release.copy(assets = listOf(asset)))
        vm.retry()
        assertFalse(vm.state.value.loadFailed)
        assertEquals(listOf(asset), vm.state.value.release!!.assets)
        assertEquals(ReleaseAssetUploadPhase.CANCELLED, vm.state.value.upload.phase)
        coVerify(exactly = 1) { errors.emit(ApiError.NetworkError, any()) }
        coVerify(exactly = 1) { repository.uploadAsset(any(), any(), any()) }
    }

    @Test fun readOnlyPermissionNeverOpensTheAttachment() = runTest {
        val vm = vm(RepoPermission.READ); vm.uploadAsset(uri)
        coVerify(exactly = 0) { repository.uploadAsset(any(), any(), any()) }
        assertEquals(ReleaseAssetUploadPhase.IDLE, vm.state.value.upload.phase)
    }
}
