package com.gitmob.app.ui.repocode

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.*
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class RepoFileEditorViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<RepoGitRepository>()
    private val tree = RepoCodeTree("R1", RepoPermission.MAINTAIN, RepoPermission.MAINTAIN.toCapabilities(), false,
        ref = "main", headOid = "HEAD", path = "", entries = emptyList())
    private val file = RepoFileContent("R1", RepoPermission.MAINTAIN, tree.capabilities, false, "main", "HEAD", "a.txt", "BLOB", 6, false, false, "server")
    private fun editor() = RepoFileEditorViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session)

    @Test fun bothTreeAndBodyMustFinishBeforeEditingOrSaving() = runTest {
        val treeResponse = CompletableDeferred<ApiResult<RepoCodeTree>>()
        val fileResponse = CompletableDeferred<ApiResult<RepoFileContent>>()
        coEvery { repository.getCodeTree("o", "r", "main", "") } coAnswers { treeResponse.await() }
        coEvery { repository.getFileContent("o", "r", "main", "a.txt") } coAnswers { fileResponse.await() }
        val vm = editor()
        vm.init("o", "r", "main", "a.txt")
        advanceUntilIdle()
        fun verifyBlocked() {
            assertTrue(vm.state.value.isLoading)
            vm.updatePath("ignored.txt")
            vm.updateContent("ignored")
            vm.updateCommitMessage("ignored")
            vm.save { fail("must not save while loading") }
            assertEquals("a.txt", vm.state.value.path)
            assertEquals("", vm.state.value.content)
            assertEquals("", vm.state.value.commitMessage)
        }
        verifyBlocked()
        treeResponse.complete(ApiResult.Success(tree))
        advanceUntilIdle()
        verifyBlocked()
        coVerify(exactly = 0) { repository.createCommit(any(), any(), any(), any(), any(), any(), any()) }
        fileResponse.complete(ApiResult.Success(file))
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        assertEquals("server", vm.state.value.content)
        vm.updatePath("renamed.txt")
        vm.updateContent("local body")
        vm.updateCommitMessage("Keep this message")
        vm.init("o", "r", "main", "a.txt")
        vm.retry()
        assertEquals("renamed.txt", vm.state.value.path)
        assertEquals("local body", vm.state.value.content)
        assertEquals("Keep this message", vm.state.value.commitMessage)
        coVerify(exactly = 1) { repository.getFileContent("o", "r", "main", "a.txt") }
        vm.viewModelScope.cancel()
    }

    @Test fun bodyFailureStaysBlockedUntilRetryAndFailedSaveKeepsCommitMessage() = runTest {
        coEvery { repository.getCodeTree("o", "r", "main", "") } returns ApiResult.Success(tree)
        coEvery { repository.getFileContent("o", "r", "main", "a.txt") } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError), ApiResult.Success(file))
        val vm = editor()
        vm.init("o", "r", "main", "a.txt")
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.isLoading)
        vm.updateContent("ignored")
        vm.updateCommitMessage("ignored")
        vm.save { fail("must not save a failed load") }
        coVerify(exactly = 0) { repository.createCommit(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals("", vm.state.value.content)
        vm.retry()
        advanceUntilIdle()
        assertFalse(vm.state.value.loadFailed)
        vm.updatePath("b.txt")
        vm.updateContent("new body")
        vm.updateCommitMessage("Rename and edit")
        coEvery { repository.createCommit(any(), any(), any(), any(), any(), any(), any()) } returns ApiResult.Failure(ApiError.NetworkError)
        vm.save { fail("failed commit must not navigate") }
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.createCommit("o", "r", "main", "Rename and edit",
            listOf(RepoPendingFileChange.Addition("b.txt", Base64.getEncoder().encodeToString("new body".toByteArray()))),
            listOf(RepoPendingFileChange.Deletion("a.txt")), "HEAD") }
        assertEquals("Rename and edit", vm.state.value.commitMessage)
        assertEquals("new body", vm.state.value.content)
        assertFalse(vm.state.value.isSaving)
        vm.viewModelScope.cancel()
    }
}
