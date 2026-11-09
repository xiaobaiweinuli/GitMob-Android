package com.gitmob.app.ui.repoactions

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.*
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoActionsRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoActionLogsViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<RepoActionsRepository>()
    private val key = RepoActionStepLogKey("octo", "repo", 42, 3, 101, 8)
    private val log = RepoActionStepLog("Build", "Compile", ActionLogAvailability.READY, "raw\r\nlog")
    private val run = RepoWorkflowRun(42, "CI", "Build", "push", "completed", "failure", 7, 8, 3, "main", "abc", null, "", "", "https://github.com/octo/repo/actions/runs/42")
    private fun runVm(permission: RepoPermission = RepoPermission.WRITE): RepoWorkflowRunViewModel {
        coEvery { repository.getRun("octo", "repo", 42) } returns ApiResult.Success(RepoWorkflowRunDetail(run, emptyList(), emptyList()))
        return RepoWorkflowRunViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk(relaxed = true), accounts.session).apply { init("octo", "repo", 42, permission) }
    }
    private fun logVm() = RepoActionStepLogViewModel(repository, ErrorEventBus(), accounts.session)

    @Test fun `wrap persists through refresh and resets on close`() = runTest {
        coEvery { repository.getStepLog(key) } returns ApiResult.Success(log)
        val vm = logVm()
        vm.init(key); advanceUntilIdle()
        assertFalse(vm.state.value.wrapLines)
        vm.setWrapLines(true)
        vm.load(); advanceUntilIdle()
        assertTrue(vm.state.value.wrapLines)
        assertEquals(log.text, vm.state.value.log?.text)
        vm.close()
        assertNull(vm.state.value.log)
        vm.init(key); advanceUntilIdle()
        assertFalse(vm.state.value.wrapLines)
        vm.viewModelScope.cancel()
    }
    @Test fun `late response from a closed step cannot replace a new step`() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { repository.getStepLog(key) } coAnswers { withContext(NonCancellable) { release.await(); ApiResult.Success(log) } }
        val next = key.copy(jobId = 202)
        coEvery { repository.getStepLog(next) } returns ApiResult.Success(log.copy(stepName = "Next"))
        val vm = logVm()
        vm.init(key); runCurrent()
        vm.close(); vm.init(next); runCurrent()
        release.complete(Unit); advanceUntilIdle()
        assertEquals("Next", vm.state.value.log?.stepName)
        vm.viewModelScope.cancel()
    }
    @Test fun `account replacement clears loaded logs and blocks new requests`() = runTest {
        coEvery { repository.getStepLog(key) } returns ApiResult.Success(log)
        val vm = logVm()
        vm.init(key); advanceUntilIdle()
        accounts.session.replace(emptyList(), null); advanceUntilIdle()
        assertNull(vm.state.value.log)
        vm.load(); advanceUntilIdle()
        coVerify(exactly = 1) { repository.getStepLog(key) }
    }
    @Test fun `failure is retryable and clears after a successful refresh`() = runTest {
        coEvery { repository.getStepLog(key) } returnsMany listOf(ApiResult.Failure(ApiError.RateLimited), ApiResult.Success(log))
        val vm = logVm()
        vm.init(key); advanceUntilIdle()
        assertEquals(ApiError.RateLimited, vm.state.value.error)
        vm.load(); advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(log, vm.state.value.log)
        vm.viewModelScope.cancel()
    }
    @Test fun `zip ignores duplicate taps and can retry after failure`() = runTest {
        val pending = CompletableDeferred<ApiResult<Unit>>()
        coEvery { repository.downloadRunLogs("octo", "repo", 42, 3) } coAnswers { pending.await() }
        val vm = runVm(); advanceUntilIdle()
        vm.downloadLogs(); vm.downloadLogs(); runCurrent()
        assertTrue(vm.state.value.openingLogs)
        coVerify(exactly = 1) { repository.downloadRunLogs("octo", "repo", 42, 3) }
        pending.complete(ApiResult.Failure(ApiError.NetworkError)); advanceUntilIdle()
        assertFalse(vm.state.value.openingLogs)
        vm.downloadLogs(); advanceUntilIdle()
        coVerify(exactly = 2) { repository.downloadRunLogs("octo", "repo", 42, 3) }
        vm.viewModelScope.cancel()
    }
    @Test fun `refresh cancels zip preparation before the attempt changes`() = runTest {
        var cancelled = false
        coEvery { repository.downloadRunLogs(any(), any(), any(), any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val vm = runVm(); advanceUntilIdle()
        vm.downloadLogs(); runCurrent()
        coEvery { repository.getRun("octo", "repo", 42) } returns ApiResult.Success(RepoWorkflowRunDetail(run.copy(runAttempt = 4), emptyList(), emptyList()))
        vm.load(); advanceUntilIdle()
        assertTrue(cancelled)
        assertFalse(vm.state.value.openingLogs)
        assertEquals(4, vm.state.value.detail?.run?.runAttempt)
        vm.viewModelScope.cancel()
    }
    @Test fun `both rerun scopes reset debug on open and submit it only once`() = runTest {
        val vm = runVm(); advanceUntilIdle()
        for (failed in listOf(false, true)) {
            val pending = CompletableDeferred<ApiResult<Unit>>()
            coEvery { repository.rerun("octo", "repo", 42, failed, true) } coAnswers { pending.await() }
            vm.requestRerun(failed)
            assertFalse(vm.state.value.enableDebugLogging)
            vm.setDebugLogging(true)
            vm.confirmRerun(); vm.confirmRerun(); runCurrent()
            coVerify(exactly = 1) { repository.rerun("octo", "repo", 42, failed, true) }
            pending.complete(ApiResult.Success(Unit)); advanceUntilIdle()
            assertNull(vm.state.value.pendingRerunFailedOnly)
        }
        vm.requestRerun(false); vm.setDebugLogging(true); vm.dismissRerun()
        vm.requestRerun(true)
        assertFalse(vm.state.value.enableDebugLogging)
        vm.dismissRerun()
        coVerify(exactly = 2) { repository.rerun(any(), any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }
    @Test fun `read permission cannot open or submit a rerun`() = runTest {
        val vm = runVm(RepoPermission.READ); advanceUntilIdle()
        vm.requestRerun(false); vm.confirmRerun(); advanceUntilIdle()
        assertNull(vm.state.value.pendingRerunFailedOnly)
        coVerify(exactly = 0) { repository.rerun(any(), any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }
    @Test fun `rerun failure keeps the selected options for retry`() = runTest {
        coEvery { repository.rerun("octo", "repo", 42, true, true) } returns ApiResult.Failure(ApiError.Forbidden)
        val vm = runVm(); advanceUntilIdle()
        vm.requestRerun(true); vm.setDebugLogging(true); vm.confirmRerun(); advanceUntilIdle()
        assertEquals(true, vm.state.value.pendingRerunFailedOnly)
        assertTrue(vm.state.value.enableDebugLogging)
        assertFalse(vm.state.value.isMutating)
        vm.viewModelScope.cancel()
    }
}
