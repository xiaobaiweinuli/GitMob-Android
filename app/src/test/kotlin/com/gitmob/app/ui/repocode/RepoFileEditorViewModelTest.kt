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
import kotlinx.coroutines.flow.first
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
            vm.updateCommitTitle("ignored")
            vm.updateCommitBody("ignored")
            vm.openCommitSheet("Update a.txt")
            assertFalse(vm.state.value.isCommitSheetOpen)
            vm.save { fail("must not save while loading") }
            assertEquals("a.txt", vm.state.value.path)
            assertEquals("", vm.state.value.content)
            assertEquals("", vm.state.value.commitTitle)
        }
        verifyBlocked()
        treeResponse.complete(ApiResult.Success(tree))
        advanceUntilIdle()
        verifyBlocked()
        coVerify(exactly = 0) { repository.createCommit(any(), any(), any(), any(), any(), any(), any(), any()) }
        fileResponse.complete(ApiResult.Success(file))
        vm.state.first { !it.isLoading }
        assertFalse(vm.state.value.isLoading)
        assertEquals("server", vm.state.value.content)
        vm.updatePath("renamed.txt")
        vm.updateContent("local body")
        vm.updateCommitTitle("Keep this message")
        vm.init("o", "r", "main", "a.txt")
        vm.retry()
        assertEquals("renamed.txt", vm.state.value.path)
        assertEquals("local body", vm.state.value.content)
        assertEquals("Keep this message", vm.state.value.commitTitle)
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
        vm.updateCommitTitle("ignored")
        vm.save { fail("must not save a failed load") }
        coVerify(exactly = 0) { repository.createCommit(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals("", vm.state.value.content)
        vm.retry()
        vm.state.first { !it.isLoading }
        assertFalse(vm.state.value.loadFailed)
        vm.updatePath("b.txt")
        vm.state.value.editorState.content.replace(0, vm.state.value.editorState.content.length, "new body")
        vm.openCommitSheet("Update b.txt")
        vm.updateCommitTitle("Rename and edit")
        vm.updateCommitBody("First paragraph\n\n第二段\n- detail")
        coEvery { repository.createCommit(any(), any(), any(), any(), any(), any(), any(), any()) } returns ApiResult.Failure(ApiError.NetworkError)
        vm.save { fail("failed commit must not navigate") }
        vm.state.first { !it.isSaving }
        coVerify(exactly = 1) { repository.createCommit("o", "r", "main", "Rename and edit",
            listOf(RepoPendingFileChange.Addition("b.txt", Base64.getEncoder().encodeToString("new body".toByteArray()))),
            listOf(RepoPendingFileChange.Deletion("a.txt")), "HEAD", "First paragraph\n\n第二段\n- detail") }
        assertEquals("Rename and edit", vm.state.value.commitTitle)
        assertEquals("First paragraph\n\n第二段\n- detail", vm.state.value.commitBody)
        assertTrue(vm.state.value.isCommitSheetOpen)
        assertEquals("new body", vm.state.value.content)
        assertFalse(vm.state.value.isSaving)
        vm.viewModelScope.cancel()
    }

    @Test fun openingAndDismissingNeverCommitAndUntouchedTitleFollowsRenamedFile() = runTest {
        coEvery { repository.getCodeTree(any(), any(), any(), any()) } returns ApiResult.Success(tree)
        val vm = editor()
        vm.init("o", "r", "main", null)
        advanceUntilIdle()
        vm.updatePath("docs/guide.md")
        vm.openCommitSheet("更新 guide.md")
        assertTrue(vm.state.value.isCommitSheetOpen)
        assertEquals("更新 guide.md", vm.state.value.commitTitle)
        vm.updateCommitBody("第一段\n\n第二段")
        vm.dismissCommitSheet()
        assertFalse(vm.state.value.isCommitSheetOpen)
        vm.save { fail("a closed sheet cannot submit") }
        vm.updatePath("docs/renamed.md")
        vm.openCommitSheet("更新 renamed.md")
        assertEquals("更新 renamed.md", vm.state.value.commitTitle)
        assertEquals("第一段\n\n第二段", vm.state.value.commitBody)
        coVerify(exactly = 0) { repository.createCommit(any(), any(), any(), any(), any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun editedAndExplicitlyClearedTitlesSurviveReopeningAndBlankTitleCannotCommit() = runTest {
        coEvery { repository.getCodeTree(any(), any(), any(), any()) } returns ApiResult.Success(tree)
        val vm = editor()
        vm.init("o", "r", "main", null)
        advanceUntilIdle()
        vm.updatePath("a.txt")
        vm.openCommitSheet("Update a.txt")
        vm.updateCommitTitle("Custom title")
        vm.dismissCommitSheet()
        vm.updatePath("b.txt")
        vm.openCommitSheet("Update b.txt")
        assertEquals("Custom title", vm.state.value.commitTitle)
        vm.updateCommitTitle("   ")
        vm.dismissCommitSheet()
        vm.openCommitSheet("Update b.txt")
        assertEquals("   ", vm.state.value.commitTitle)
        vm.save { fail("a title is required") }
        coVerify(exactly = 0) { repository.createCommit(any(), any(), any(), any(), any(), any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun submissionLocksDraftAndDismissalAndOnlySuccessfulCommitClosesTheSheet() = runTest {
        coEvery { repository.getCodeTree(any(), any(), any(), any()) } returns ApiResult.Success(tree)
        val response = CompletableDeferred<ApiResult<RepoCommitSummary>>()
        coEvery { repository.createCommit(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers { response.await() }
        val vm = editor()
        vm.init("o", "r", "main", null)
        advanceUntilIdle()
        vm.updatePath("a.txt")
        vm.updateContent("payload")
        vm.openCommitSheet("Update a.txt")
        vm.updateCommitTitle("  My title  ")
        vm.updateCommitBody("line 1\n\nline 2\n")
        var saved = 0
        val navigation = CompletableDeferred<Unit>()
        vm.save { saved++; navigation.complete(Unit) }
        assertTrue(vm.state.value.isSaving)
        vm.save { saved++ }
        vm.dismissCommitSheet()
        vm.updateCommitTitle("ignored")
        vm.updateCommitBody("ignored")
        vm.updatePath("ignored")
        vm.updateContent("ignored")
        assertTrue(vm.state.value.isCommitSheetOpen)
        assertEquals("  My title  ", vm.state.value.commitTitle)
        assertEquals("line 1\n\nline 2\n", vm.state.value.commitBody)
        assertEquals("payload", vm.state.value.content)
        val commit = mockk<RepoCommitSummary> { every { oid } returns "NEW" }
        response.complete(ApiResult.Success(commit))
        navigation.await()
        assertFalse(vm.state.value.isCommitSheetOpen)
        assertEquals(1, saved)
        coVerify(exactly = 1) { repository.createCommit("o", "r", "main", "My title",
            listOf(RepoPendingFileChange.Addition("a.txt", Base64.getEncoder().encodeToString("payload".toByteArray()))),
            emptyList(), "HEAD", "line 1\n\nline 2\n") }
        vm.viewModelScope.cancel()
    }

    @Test fun archivedReadOnlyOrProtectedTargetsCannotOpenTheCommitSheet() = runTest {
        for (blockedTree in listOf(
            tree.copy(isArchived = true),
            tree.copy(capabilities = RepoPermission.READ.toCapabilities()),
            tree.copy(capabilities = tree.capabilities.copy(canPushToProtectedBranch = false)),
        )) {
            coEvery { repository.getCodeTree(any(), any(), any(), any()) } returns ApiResult.Success(blockedTree)
            val vm = editor()
            vm.init("o", "r", "main", null)
            advanceUntilIdle()
            vm.updatePath("a.txt")
            vm.openCommitSheet("Update a.txt")
            assertFalse(vm.state.value.canCommit)
            assertFalse(vm.state.value.isCommitSheetOpen)
            vm.viewModelScope.cancel()
        }
    }
}
