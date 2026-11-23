package com.gitmob.app.ui.repoactions

import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.core.share.ShareLinkLauncher
import com.gitmob.app.data.model.RepoActionsPage
import com.gitmob.app.data.model.RepoWorkflow
import com.gitmob.app.data.repository.RepoActionsRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoActionsRecoveryViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val repository = mockk<RepoActionsRepository>()
    private val share = mockk<ShareLinkLauncher>(relaxed = true)
    private val errors = mockk<ErrorEventBus>(relaxed = true)
    private lateinit var vm: RepoActionsViewModel
    private val workflow = RepoWorkflow(7, "W7", "CI", ".github/workflows/ci.yml", "active", null, null)
    private val disabled = ApiResult.Failure(ApiError.UserVisible(R.string.actions_repository_disabled))

    @After fun close() { if (::vm.isInitialized) vm.viewModelScope.cancel() }

    private fun start(enabled: Boolean = true, permission: RepoPermission? = RepoPermission.ADMIN,
        initialSettings: ApiResult<Boolean> = ApiResult.Success(enabled), isArchived: Boolean? = false) {
        coEvery { repository.getActions(any(), any()) } returns ApiResult.Success(RepoActionsPage(0, listOf(workflow), emptyList(), 1, false))
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns initialSettings
        coEvery { repository.getDispatchInputs(any(), any(), any(), any()) } returns ApiResult.Success(emptyList())
        vm = RepoActionsViewModel(repository, errors, RepoUpdateEventBus(), share, accounts.session)
        vm.init("octo", "repo", permission, "main", isArchived)
    }

    @Test fun archivedRepositoryFromDetailBlocksActionsWrites() = runTest {
        start(isArchived = true)
        advanceUntilIdle()
        assertFalse(vm.state.value.capabilities.canManageRepositoryActions)
        assertFalse(vm.state.value.capabilities.canDeleteActionRuns)
        assertFalse(vm.state.value.canDispatch)
        assertEquals(listOf(workflow), vm.state.value.workflows)

        vm.onActionsMenuOpened()
        vm.setRepositoryActionsEnabled(true)
        vm.prepareDispatch(workflow)
        vm.setWorkflowEnabled(workflow, false)
        advanceUntilIdle()

        assertNull(vm.state.value.dispatchWorkflow)
        coVerify(exactly = 0) { repository.getRepositoryCapabilities(any(), any()) }
        coVerify(exactly = 0) { repository.getRepositoryActionsEnabled(any(), any()) }
        coVerify(exactly = 0) { repository.setRepositoryActionsEnabled(any(), any(), any()) }
        coVerify(exactly = 0) { repository.getDispatchInputs(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.enableWorkflow(any(), any(), any(), any()) }
    }

    @Test fun missingArchiveContextWaitsForCapabilitiesWithoutTrustingAdminPermission() = runTest {
        val read = CompletableDeferred<ApiResult<RepoCapabilities>>()
        coEvery { repository.getRepositoryCapabilities("octo", "repo") } coAnswers { read.await() }
        start(isArchived = null)
        advanceUntilIdle()
        assertFalse(vm.state.value.capabilities.canManageRepositoryActions)
        assertFalse(vm.state.value.canDispatch)

        read.complete(ApiResult.Success(RepoPermission.ADMIN.toCapabilities(isArchived = true)))
        advanceUntilIdle()

        assertFalse(vm.state.value.capabilities.canManageRepositoryActions)
        assertFalse(vm.state.value.canDispatch)
        coVerify(exactly = 1) { repository.getRepositoryCapabilities("octo", "repo") }
        coVerify(exactly = 0) { repository.getRepositoryActionsEnabled(any(), any()) }
    }

    @Test fun archivedDeepLinkRemainsReadOnly() = runTest {
        coEvery { repository.getRepositoryCapabilities("octo", "repo") } returns
            ApiResult.Success(RepoPermission.ADMIN.toCapabilities(isArchived = true))
        start(permission = null, isArchived = null)
        advanceUntilIdle()

        assertFalse(vm.state.value.capabilities.canManageRepositoryActions)
        assertFalse(vm.state.value.canDispatch)
        assertEquals(listOf(workflow), vm.state.value.workflows)
        coVerify(exactly = 0) { repository.getRepositoryActionsEnabled(any(), any()) }
    }

    @Test fun failedCapabilityQueryKeepsActionsReadOnly() = runTest {
        coEvery { repository.getRepositoryCapabilities("octo", "repo") } returns ApiResult.Failure(ApiError.NetworkError)
        start(permission = null, isArchived = null)
        advanceUntilIdle()

        assertFalse(vm.state.value.capabilities.canManageRepositoryActions)
        assertFalse(vm.state.value.canDispatch)
        assertEquals(listOf(workflow), vm.state.value.workflows)
        coVerify(exactly = 0) { repository.getRepositoryActionsEnabled(any(), any()) }
    }

    @Test fun `toggleWaitsForConfirmationAndRejectsDuplicateWrites`() = runTest {
        start(false)
        advanceUntilIdle()
        val write = CompletableDeferred<ApiResult<Unit>>()
        val read = CompletableDeferred<ApiResult<Boolean>>()
        coEvery { repository.setRepositoryActionsEnabled("octo", "repo", true) } coAnswers { write.await() }
        coEvery { repository.getRepositoryActionsEnabled("octo", "repo") } coAnswers { read.await() }
        vm.prepareDispatch(workflow)
        advanceUntilIdle()
        vm.setRepositoryActionsEnabled(true)
        vm.setRepositoryActionsEnabled(true)
        assertEquals(true, vm.state.value.pendingRepositoryActionsEnabled)
        assertFalse(vm.state.value.canDispatch)
        write.complete(ApiResult.Success(Unit))
        advanceUntilIdle()
        assertEquals(false, vm.state.value.repositoryActionsEnabled)
        assertEquals(true, vm.state.value.pendingRepositoryActionsEnabled)
        vm.refreshRepositoryActions()
        vm.setRepositoryActionsEnabled(true)
        read.complete(ApiResult.Success(true))
        advanceUntilIdle()
        assertEquals(true, vm.state.value.repositoryActionsEnabled)
        assertNull(vm.state.value.pendingRepositoryActionsEnabled)
        assertNull(vm.state.value.dispatchWorkflow)
        assertNull(vm.state.value.dispatchInputsRef)
        coVerify(exactly = 0) { repository.getDispatchInputs(any(), any(), any(), any()) }
        coVerify(exactly = 1) { repository.setRepositoryActionsEnabled(any(), any(), any()) }
        coVerify(exactly = 0) { repository.dispatch(any(), any(), any(), any(), any()) }
    }

    @Test fun `confirmedDisableKeepsWorkflowAndHistoryState`() = runTest {
        start()
        advanceUntilIdle()
        coEvery { repository.setRepositoryActionsEnabled(any(), any(), false) } returns ApiResult.Success(Unit)
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Success(false)
        val original = vm.state.value.workflows
        vm.setRepositoryActionsEnabled(false)
        advanceUntilIdle()
        assertEquals(false, vm.state.value.repositoryActionsEnabled)
        assertEquals(original, vm.state.value.workflows)
        assertFalse(vm.state.value.canDispatch)
        coVerify(exactly = 0) { repository.enableWorkflow(any(), any(), any(), any()) }
    }

    @Test fun `explicitFailureKeepsDirectionAndAllowsBothRetries`() = runTest {
        for (initial in listOf(false, true)) {
            start(initial)
            advanceUntilIdle()
            coEvery { repository.setRepositoryActionsEnabled(any(), any(), !initial) } returns ApiResult.Failure(ApiError.Forbidden)
            vm.setRepositoryActionsEnabled(!initial)
            advanceUntilIdle()
            assertEquals(initial, vm.state.value.repositoryActionsEnabled)
            assertFalse(vm.state.value.repositoryActionsNeedsConfirmation)
            vm.setRepositoryActionsEnabled(!initial)
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.setRepositoryActionsEnabled(any(), any(), !initial) }
            vm.viewModelScope.cancel()
        }
    }

    @Test fun `successfulWriteWithFailedReadRequiresRecheck`() = runTest {
        start(false)
        advanceUntilIdle()
        coEvery { repository.setRepositoryActionsEnabled(any(), any(), true) } returns ApiResult.Success(Unit)
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Failure(ApiError.NetworkError)
        vm.setRepositoryActionsEnabled(true)
        advanceUntilIdle()
        assertNull(vm.state.value.repositoryActionsEnabled)
        assertTrue(vm.state.value.repositoryActionsNeedsConfirmation)
        assertFalse(vm.state.value.canDispatch)
        vm.setRepositoryActionsEnabled(true)
        coVerify(exactly = 1) { repository.setRepositoryActionsEnabled(any(), any(), any()) }
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.state.value.repositoryActionsNeedsConfirmation)
        assertFalse(vm.state.value.canDispatch)
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Success(true)
        vm.onActionsMenuOpened()
        advanceUntilIdle()
        assertEquals(true, vm.state.value.repositoryActionsEnabled)
        assertFalse(vm.state.value.repositoryActionsNeedsConfirmation)
    }

    @Test fun `networkWriteFailureDoesNotGuessDirection`() = runTest {
        start()
        advanceUntilIdle()
        coEvery { repository.setRepositoryActionsEnabled(any(), any(), false) } returns ApiResult.Failure(ApiError.NetworkError)
        vm.setRepositoryActionsEnabled(false)
        advanceUntilIdle()
        assertNull(vm.state.value.repositoryActionsEnabled)
        assertTrue(vm.state.value.repositoryActionsNeedsConfirmation)
    }

    @Test fun `confirmationMismatchUsesServerStateAndReportsFailure`() = runTest {
        start(false)
        advanceUntilIdle()
        coEvery { repository.setRepositoryActionsEnabled(any(), any(), true) } returns ApiResult.Success(Unit)
        vm.setRepositoryActionsEnabled(true)
        advanceUntilIdle()
        assertEquals(false, vm.state.value.repositoryActionsEnabled)
        coVerify { errors.emit(ApiError.UserVisible(R.string.actions_repository_not_confirmed), any()) }
        coVerify(exactly = 0) { errors.emitNotice(any(), any()) }
    }

    @Test fun `unknownReadDoesNotBlockDispatchAndMenuRetries`() = runTest {
        start(initialSettings = ApiResult.Failure(ApiError.Forbidden))
        advanceUntilIdle()
        assertNull(vm.state.value.repositoryActionsEnabled)
        assertTrue(vm.state.value.canDispatch)
        vm.setRepositoryActionsEnabled(true)
        coVerify(exactly = 0) { repository.setRepositoryActionsEnabled(any(), any(), any()) }
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Success(false)
        vm.onActionsMenuOpened()
        advanceUntilIdle()
        assertEquals(false, vm.state.value.repositoryActionsEnabled)
    }

    @Test fun `writerCanDispatchButCannotManageRepository`() = runTest {
        start(permission = RepoPermission.WRITE)
        advanceUntilIdle()
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } returns ApiResult.Success(Unit)
        vm.setRepositoryActionsEnabled(false)
        vm.prepareDispatch(workflow)
        advanceUntilIdle()
        vm.dispatch("main", emptyMap())
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.setRepositoryActionsEnabled(any(), any(), any()) }
        coVerify(exactly = 0) { repository.getRepositoryActionsEnabled(any(), any()) }
        coVerify(exactly = 1) { repository.dispatch("octo", "repo", 7, "main", emptyMap()) }
    }

    @Test fun `disabledDispatchPreservesFormAndChecksRepository`() = runTest {
        start()
        advanceUntilIdle()
        vm.prepareDispatch(workflow)
        vm.loadDispatchInputs("release")
        advanceUntilIdle()
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } returns disabled
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Success(false)
        vm.dispatch("release", mapOf("flag" to "true"))
        advanceUntilIdle()
        assertEquals(false, vm.state.value.repositoryActionsEnabled)
        assertFalse(vm.state.value.canDispatch)
        assertEquals(workflow, vm.state.value.dispatchWorkflow)
        assertEquals("release", vm.state.value.dispatchInputsRef)
        vm.dispatch("release", mapOf("flag" to "true"))
        coVerify(exactly = 1) { repository.dispatch(any(), any(), any(), any(), any()) }
    }

    @Test fun `enabledButRejectedBlocksDispatchAndCannotBeEnabledAgain`() = runTest {
        start()
        advanceUntilIdle()
        vm.prepareDispatch(workflow)
        advanceUntilIdle()
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } returns disabled
        vm.dispatch("main", emptyMap())
        advanceUntilIdle()
        assertTrue(vm.state.value.dispatchRejectedByRepository)
        assertEquals(true, vm.state.value.repositoryActionsEnabled)
        assertFalse(vm.state.value.canDispatch)
        vm.setRepositoryActionsEnabled(true)
        coVerify(exactly = 0) { repository.setRepositoryActionsEnabled(any(), any(), any()) }
    }

    @Test fun `otherDispatchErrorsDoNotBecomeRepositoryDisabled`() = runTest {
        start()
        advanceUntilIdle()
        vm.prepareDispatch(workflow)
        advanceUntilIdle()
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } returns ApiResult.Failure(ApiError.Unknown("HTTP 422: Invalid inputs"))
        vm.dispatch("main", emptyMap())
        advanceUntilIdle()
        assertEquals(true, vm.state.value.repositoryActionsEnabled)
        assertFalse(vm.state.value.dispatchRejectedByRepository)
        assertTrue(vm.state.value.canDispatch)
    }

    @Test fun `accountSwitchDiscardsPendingConfirmation`() = runTest {
        start(false)
        advanceUntilIdle()
        val read = CompletableDeferred<ApiResult<Boolean>>()
        coEvery { repository.setRepositoryActionsEnabled(any(), any(), true) } returns ApiResult.Success(Unit)
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } coAnswers { read.await() }
        vm.setRepositoryActionsEnabled(true)
        advanceUntilIdle()
        accounts.session.replace(emptyList(), null)
        read.complete(ApiResult.Success(true))
        advanceUntilIdle()
        assertEquals(false, vm.state.value.repositoryActionsEnabled)
        vm.setRepositoryActionsEnabled(true)
        coVerify(exactly = 1) { repository.setRepositoryActionsEnabled(any(), any(), any()) }
    }

    @Test fun `repositoryChangeDiscardsOldReadAndShareUsesNewRepository`() = runTest {
        start(false)
        advanceUntilIdle()
        val read = CompletableDeferred<ApiResult<Boolean>>()
        coEvery { repository.getRepositoryActionsEnabled("octo", "repo") } coAnswers { read.await() }
        vm.refreshRepositoryActions()
        coEvery { repository.getRepositoryActionsEnabled("octo", "second") } returns ApiResult.Success(true)
        vm.init("octo", "second", RepoPermission.ADMIN, "release", isArchived = false)
        read.complete(ApiResult.Success(false))
        advanceUntilIdle()
        assertEquals(true, vm.state.value.repositoryActionsEnabled)
        assertEquals("release", vm.state.value.dispatchRef)
        vm.shareActions()
        verify { share.share("https://github.com/octo/second/actions") }
    }

    @Test fun `workflowEnableRemainsSeparateFromRepositorySettings`() = runTest {
        start(false)
        advanceUntilIdle()
        coEvery { repository.enableWorkflow(any(), any(), any(), false) } returns ApiResult.Success(Unit)
        vm.setWorkflowEnabled(workflow, false)
        advanceUntilIdle()
        assertEquals("disabled_manually", vm.state.value.workflows.single().state)
        assertEquals(false, vm.state.value.repositoryActionsEnabled)
        coVerify(exactly = 0) { repository.setRepositoryActionsEnabled(any(), any(), any()) }
    }

    @Test fun duplicateDispatchAndSettingWriteAreBlockedWhileDispatching() = runTest {
        start()
        advanceUntilIdle()
        vm.prepareDispatch(workflow)
        advanceUntilIdle()
        val dispatch = CompletableDeferred<ApiResult<Unit>>()
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } coAnswers { dispatch.await() }
        vm.dispatch("main", emptyMap())
        vm.dispatch("main", emptyMap())
        vm.setRepositoryActionsEnabled(false)
        advanceUntilIdle()
        assertTrue(vm.state.value.isDispatching)
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.canDispatch)
        vm.dispatch("main", emptyMap())
        coVerify(exactly = 1) { repository.dispatch(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.setRepositoryActionsEnabled(any(), any(), any()) }
        dispatch.complete(disabled)
        advanceUntilIdle()
        assertFalse(vm.state.value.isDispatching)
        assertTrue(vm.state.value.dispatchRejectedByRepository)
    }

    @Test fun rejectedDispatchStaysBlockedOnOrdinaryReadsUntilExplicitRefresh() = runTest {
        start()
        advanceUntilIdle()
        vm.prepareDispatch(workflow)
        advanceUntilIdle()
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } returns disabled
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Failure(ApiError.NetworkError)
        vm.dispatch("main", emptyMap())
        advanceUntilIdle()
        assertTrue(vm.state.value.dispatchRejectedByRepository)
        assertFalse(vm.state.value.canDispatch)
        assertEquals(true, vm.state.value.repositoryActionsEnabled)
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Success(true)
        vm.refreshRepositoryActions()
        advanceUntilIdle()
        assertTrue(vm.state.value.dispatchRejectedByRepository)
        assertFalse(vm.state.value.canDispatch)
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.dispatchRejectedByRepository)
        assertTrue(vm.state.value.canDispatch)
        coVerify(exactly = 1) { repository.dispatch(any(), any(), any(), any(), any()) }
    }

    @Test fun pendingDisableRejectsDuplicatesAndKeepsLastConfirmedDirection() = runTest {
        start()
        advanceUntilIdle()
        val write = CompletableDeferred<ApiResult<Unit>>()
        coEvery { repository.setRepositoryActionsEnabled(any(), any(), false) } coAnswers { write.await() }
        vm.setRepositoryActionsEnabled(false)
        vm.setRepositoryActionsEnabled(false)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.repositoryActionsEnabled)
        assertEquals(false, vm.state.value.pendingRepositoryActionsEnabled)
        assertFalse(vm.state.value.canDispatch)
        coVerify(exactly = 1) { repository.setRepositoryActionsEnabled(any(), any(), false) }
        write.complete(ApiResult.Failure(ApiError.Forbidden))
        advanceUntilIdle()
        assertEquals(true, vm.state.value.repositoryActionsEnabled)
        assertNull(vm.state.value.pendingRepositoryActionsEnabled)
        assertTrue(vm.state.value.canDispatch)
    }

    @Test fun staleReadCannotOverwriteConfirmedSettingWrite() = runTest {
        start(false)
        advanceUntilIdle()
        val oldRead = CompletableDeferred<ApiResult<Boolean>>()
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } coAnswers { oldRead.await() }
        vm.refreshRepositoryActions()
        advanceUntilIdle()
        coEvery { repository.setRepositoryActionsEnabled(any(), any(), true) } returns ApiResult.Success(Unit)
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Success(true)
        vm.setRepositoryActionsEnabled(true)
        advanceUntilIdle()
        oldRead.complete(ApiResult.Success(false))
        advanceUntilIdle()
        assertEquals(true, vm.state.value.repositoryActionsEnabled)
        assertFalse(vm.state.value.isLoadingRepositoryActions)
    }


    @Test fun failedEnableConfirmationCanRecoverByRecheckingWithoutDispatching() = runTest {
        start(false)
        advanceUntilIdle()
        coEvery { repository.setRepositoryActionsEnabled(any(), any(), true) } returns ApiResult.Success(Unit)
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Failure(ApiError.NetworkError)
        vm.setRepositoryActionsEnabled(true)
        advanceUntilIdle()
        assertTrue(vm.state.value.repositoryActionsNeedsConfirmation)
        assertFalse(vm.state.value.canDispatch)
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Success(true)
        vm.onActionsMenuOpened()
        advanceUntilIdle()
        assertTrue(vm.state.value.canDispatch)
        assertFalse(vm.state.value.repositoryActionsNeedsConfirmation)
        assertNull(vm.state.value.dispatchWorkflow)
        coVerify(exactly = 0) { repository.dispatch(any(), any(), any(), any(), any()) }
    }

    @Test fun ordinaryRefreshFailurePreservesLastConfirmedSetting() = runTest {
        for (enabled in listOf(false, true)) {
            start(enabled)
            advanceUntilIdle()
            coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Failure(ApiError.NetworkError)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(enabled, vm.state.value.repositoryActionsEnabled)
            assertEquals(enabled, vm.state.value.canDispatch)
            assertFalse(vm.state.value.repositoryActionsNeedsConfirmation)
            vm.viewModelScope.cancel()
        }
    }

    @Test fun writerCanRetryRejectedDispatchAfterExplicitRefresh() = runTest {
        start(permission = RepoPermission.WRITE)
        advanceUntilIdle()
        vm.prepareDispatch(workflow)
        advanceUntilIdle()
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } returns disabled
        vm.dispatch("main", emptyMap())
        advanceUntilIdle()
        assertTrue(vm.state.value.dispatchRejectedByRepository)
        assertFalse(vm.state.value.canDispatch)
        coVerify { errors.emit(ApiError.UserVisible(R.string.actions_repository_disabled), any()) }
        vm.dispatch("main", emptyMap())
        vm.dismissDispatch()
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.state.value.dispatchRejectedByRepository)
        assertTrue(vm.state.value.canDispatch)
        coVerify(exactly = 1) { repository.dispatch(any(), any(), any(), any(), any()) }
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } returns ApiResult.Success(Unit)
        vm.prepareDispatch(workflow)
        advanceUntilIdle()
        vm.dispatch("main", emptyMap())
        advanceUntilIdle()
        assertNull(vm.state.value.dispatchWorkflow)
        coVerify(exactly = 2) { repository.dispatch(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.getRepositoryActionsEnabled(any(), any()) }
        coVerify(exactly = 0) { repository.setRepositoryActionsEnabled(any(), any(), any()) }
    }

    @Test fun successfulDispatchRefreshesActionsOnceThroughEventBus() = runTest {
        start()
        advanceUntilIdle()
        vm.prepareDispatch(workflow)
        advanceUntilIdle()
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } returns ApiResult.Success(Unit)
        vm.dispatch("main", emptyMap())
        advanceUntilIdle()
        assertNull(vm.state.value.dispatchWorkflow)
        assertFalse(vm.state.value.isDispatching)
        coVerify(exactly = 1) { repository.dispatch("octo", "repo", workflow.id, "main", emptyMap()) }
        coVerify(exactly = 2) { repository.getActions("octo", "repo") }
    }

    @Test fun confirmedDisabledCannotOpenDispatchDialog() = runTest {
        start(false)
        advanceUntilIdle()
        vm.prepareDispatch(workflow)
        advanceUntilIdle()
        assertNull(vm.state.value.dispatchWorkflow)
        coVerify(exactly = 0) { repository.getDispatchInputs(any(), any(), any(), any()) }
    }
}
