package com.gitmob.app.ui.reporeleases

import androidx.lifecycle.ViewModelStore
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoReleaseRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RepoReleaseEditorViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<RepoReleaseRepository>()
    private val errors = mockk<ErrorEventBus>(relaxed = true)
    private val events = mockk<RepoUpdateEventBus>(relaxed = true)
    private fun vm() = RepoReleaseEditorViewModel(repository, errors, events, accounts.session)
    private fun release() = RepoRelease(7, "R7", "v1", "main", "Server title", "Server notes", null, false, false, "", null, null, "", emptyList(), null, null)
    private fun create(vm: RepoReleaseEditorViewModel) { vm.init("o", "r", null, RepoPermission.WRITE); vm.editTag("v2") }

    @Test fun successfulLoadInitializesCleanDraftOnceAndPreservesLaterEdits() = runTest {
        val response = CompletableDeferred<ApiResult<RepoRelease>>()
        coEvery { repository.getRelease("o", "r", 7) } coAnswers { response.await() }
        val vm = vm()
        vm.init("o", "r", 7, RepoPermission.WRITE)
        assertEquals(ReleaseEditorPhase.LOADING, vm.state.value.phase)
        vm.init("o", "r", 7, RepoPermission.WRITE)
        vm.retry()
        coVerify(exactly = 1) { repository.getRelease("o", "r", 7) }
        vm.editTitle("Not editable yet")
        vm.editBody("Not editable yet")
        vm.editTag("v-local")
        vm.editTarget("local-branch")
        vm.editDraft(false)
        vm.editPrerelease(true)
        vm.save()
        vm.generateNotes()
        assertEquals(ReleaseEditorDraft(), vm.state.value.draft)
        assertFalse(vm.state.value.isDirty)
        coVerify(exactly = 0) { repository.updateRelease(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.generateNotes(any(), any(), any(), any(), any()) }
        response.complete(ApiResult.Success(release()))
        assertEquals(ReleaseEditorPhase.EDITABLE, vm.state.value.phase)
        assertEquals(ReleaseEditorDraft("v1", "main", "Server title", "Server notes", false, false), vm.state.value.draft)
        assertFalse(vm.state.value.isDirty)
        vm.editTitle("My title")
        vm.editBody("My notes")
        val edited = vm.state.value
        assertTrue(edited.isDirty)
        vm.init("o", "r", 7, RepoPermission.WRITE)
        vm.retry()
        coVerify(exactly = 1) { repository.getRelease(any(), any(), any()) }
        assertEquals(edited, vm.state.value)
    }

    @Test fun failedLoadBlocksEditingAndSaveThenRetryInitializesServerDraft() = runTest {
        val response = CompletableDeferred<ApiResult<RepoRelease>>()
        coEvery { repository.getRelease(any(), any(), any()) } returns ApiResult.Failure(ApiError.NetworkError)
        val vm = vm()
        vm.init("o", "r", 7, RepoPermission.WRITE)
        assertEquals(ReleaseEditorPhase.LOAD_FAILED, vm.state.value.phase)
        vm.editTitle("Not editable yet")
        vm.save()
        assertEquals(ReleaseEditorDraft(), vm.state.value.draft)
        assertFalse(vm.state.value.isDirty)
        coVerify(exactly = 0) { repository.updateRelease(any(), any(), any(), any()) }
        coEvery { repository.getRelease("o", "r", 7) } coAnswers { response.await() }
        vm.retry()
        assertEquals(ReleaseEditorPhase.LOADING, vm.state.value.phase)
        vm.retry()
        coVerify(exactly = 2) { repository.getRelease("o", "r", 7) }
        response.complete(ApiResult.Success(release()))
        assertEquals(ReleaseEditorPhase.EDITABLE, vm.state.value.phase)
        assertEquals("Server title", vm.state.value.draft.title)
        assertEquals("v1", vm.state.value.draft.tag)
        assertFalse(vm.state.value.isDirty)
        coVerify(exactly = 2) { repository.getRelease("o", "r", 7) }
    }

    @Test fun changingDraftFlagDoesNotDiscardGeneratedNotes() = generatedNotesSurviveFlagChange { editDraft(false) }

    @Test fun changingPrereleaseFlagDoesNotDiscardGeneratedNotes() = generatedNotesSurviveFlagChange { editPrerelease(true) }

    private fun generatedNotesSurviveFlagChange(change: RepoReleaseEditorViewModel.() -> Unit) = runTest {
        val response = CompletableDeferred<ApiResult<ReleaseNotes>>()
        coEvery { repository.generateNotes(any(), any(), any(), any(), any()) } coAnswers { response.await() }
        val vm = vm(); create(vm)
        vm.generateNotes()
        assertTrue(vm.state.value.isGenerating)
        vm.change()
        val draft = vm.state.value.draft
        response.complete(ApiResult.Success(ReleaseNotes("Generated title", "Generated body")))
        assertEquals(draft.copy(title = "Generated title", body = "Generated body"), vm.state.value.draft)
        assertFalse(vm.state.value.isGenerating)
        assertTrue(vm.state.value.isDirty)
        coVerify(exactly = 0) { errors.emitNotice(any(), any()) }
    }

    @Test fun changingTagDiscardsNotesGeneratedForOldTag() = generatedNotesRespectTextEdit { editTag("v3") }

    @Test fun changingTargetDiscardsNotesGeneratedForOldTarget() = generatedNotesRespectTextEdit { editTarget("other-branch") }

    @Test fun changingTitleProtectsUserTextFromGeneratedNotes() = generatedNotesRespectTextEdit { editTitle("My new title") }

    private fun generatedNotesRespectTextEdit(change: RepoReleaseEditorViewModel.() -> Unit) = runTest {
        val response = CompletableDeferred<ApiResult<ReleaseNotes>>()
        coEvery { repository.generateNotes(any(), any(), any(), any(), any()) } coAnswers { response.await() }
        val vm = vm(); create(vm)
        vm.generateNotes()
        vm.change()
        val draft = vm.state.value.draft
        response.complete(ApiResult.Success(ReleaseNotes("Outdated title", "Outdated body")))
        assertEquals(draft, vm.state.value.draft)
        assertFalse(vm.state.value.isGenerating)
        coVerify(exactly = 1) { errors.emitNotice(any(), any()) }
    }

    @Test fun lateGeneratedNotesCannotOverwriteBodyEditedAndReverted() = runTest {
        val response = CompletableDeferred<ApiResult<ReleaseNotes>>()
        coEvery { repository.generateNotes(any(), any(), any(), any(), any()) } coAnswers { response.await() }
        val vm = vm(); create(vm)
        vm.generateNotes(); vm.generateNotes()
        vm.editBody("Typing")
        vm.editBody("")
        response.complete(ApiResult.Success(ReleaseNotes("Generated", "Old result")))
        assertEquals("", vm.state.value.draft.body)
        assertEquals("", vm.state.value.draft.title)
        assertFalse(vm.state.value.isGenerating)
        coVerify(exactly = 1) { repository.generateNotes(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { errors.emitNotice(any(), any()) }
    }

    @Test fun generatedNotesApplyToUnchangedDraftWithoutReplacingNonblankTitle() = runTest {
        coEvery { repository.generateNotes(any(), any(), any(), any(), any()) } returns ApiResult.Success(ReleaseNotes("Generated title", "Generated body"))
        val vm = vm(); create(vm)
        vm.editTitle("My title"); vm.generateNotes()
        assertEquals("My title", vm.state.value.draft.title)
        assertEquals("Generated body", vm.state.value.draft.body)
        assertTrue(vm.state.value.isDirty)
    }

    @Test fun generatedNotesFillBlankTitleAndFailureKeepsDraft() = runTest {
        coEvery { repository.generateNotes(any(), any(), any(), any(), any()) } returns ApiResult.Success(ReleaseNotes("Generated", "Body")) andThen ApiResult.Failure(ApiError.NetworkError)
        val vm = vm(); create(vm); vm.generateNotes()
        val draft = vm.state.value.draft
        assertEquals("Generated", draft.title)
        vm.generateNotes()
        assertEquals(draft, vm.state.value.draft)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test fun failedSaveRetainsAllFieldsAndAllowsRetryWithNoPrematureCompletion() = runTest {
        val pending = CompletableDeferred<ApiResult<RepoRelease>>()
        coEvery { repository.createRelease(any(), any(), any()) } coAnswers { pending.await() }
        val vm = vm(); create(vm)
        vm.editTarget("branch"); vm.editTitle("Title"); vm.editBody("Body"); vm.editDraft(false); vm.editPrerelease(true)
        val draft = vm.state.value.draft
        vm.save(); vm.save(); vm.editBody("Should be blocked")
        assertEquals(ReleaseEditorPhase.SAVING, vm.state.value.phase)
        assertEquals(draft, vm.state.value.draft)
        assertNull(vm.state.value.savedRelease)
        pending.complete(ApiResult.Failure(ApiError.NetworkError))
        assertEquals(draft, vm.state.value.draft)
        assertTrue(vm.state.value.isDirty)
        assertEquals(ReleaseEditorPhase.EDITABLE, vm.state.value.phase)
        assertNull(vm.state.value.savedRelease)
        coVerify(exactly = 1) { repository.createRelease("o", "r", draft.toInput()) }
        coEvery { repository.createRelease(any(), any(), any()) } returns ApiResult.Success(release().copy(tagName = "v2"))
        vm.save()
        assertEquals("v2", vm.state.value.savedRelease?.tagName)
        assertFalse(vm.state.value.isDirty)
        coVerify(exactly = 1) { events.emit(any()) }
    }

    @Test fun existingReleaseUpdatesInsteadOfCreatingAndRevertedDraftIsClean() = runTest {
        coEvery { repository.getRelease(any(), any(), any()) } returns ApiResult.Success(release())
        coEvery { repository.updateRelease(any(), any(), any(), any()) } returns ApiResult.Success(release())
        val vm = vm(); vm.init("o", "r", 7, RepoPermission.WRITE)
        assertFalse(vm.state.value.isDirty)
        vm.editBody("Changed"); assertTrue(vm.state.value.isDirty)
        vm.editBody("Server notes"); assertFalse(vm.state.value.isDirty)
        vm.save()
        coVerify(exactly = 1) { repository.updateRelease("o", "r", 7, any()) }
        coVerify(exactly = 0) { repository.createRelease(any(), any(), any()) }
    }

    @Test fun savingCancelsGenerationAndKeepsTheSavedSnapshot() = runTest {
        var cancelled = false
        coEvery { repository.generateNotes(any(), any(), any(), any(), any()) } coAnswers { try { awaitCancellation() } finally { cancelled = true } }
        coEvery { repository.createRelease(any(), any(), any()) } returns ApiResult.Failure(ApiError.NetworkError)
        val vm = vm(); create(vm); vm.editBody("My body"); vm.generateNotes(); vm.save()
        assertTrue(cancelled)
        assertEquals("My body", vm.state.value.draft.body)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test fun accountChangeCancelsSaveAndCannotPublishOldCompletion() = runTest {
        var cancelled = false
        coEvery { repository.createRelease(any(), any(), any()) } coAnswers { try { awaitCancellation() } finally { cancelled = true } }
        val vm = vm(); create(vm); vm.save()
        accounts.session.replace(emptyList(), null)
        assertTrue(cancelled)
        assertNull(vm.state.value.savedRelease)
        coVerify(exactly = 0) { events.emit(any()) }
        vm.save()
        coVerify(exactly = 1) { repository.createRelease(any(), any(), any()) }
    }

    @Test fun clearingViewModelCancelsGenerationWhileRepeatedInitPreservesDraft() = runTest {
        var cancelled = false
        coEvery { repository.generateNotes(any(), any(), any(), any(), any()) } coAnswers { try { awaitCancellation() } finally { cancelled = true } }
        val vm = vm(); create(vm); vm.editBody("Persistent session draft")
        vm.init("o", "r", null, RepoPermission.WRITE)
        assertEquals("Persistent session draft", vm.state.value.draft.body)
        val store = ViewModelStore(); store.put("editor", vm)
        vm.generateNotes(); store.clear()
        assertTrue(cancelled)
    }

    @Test fun missingWritePermissionBlocksSaveAndGeneration() = runTest {
        val vm = vm(); vm.init("o", "r", null, RepoPermission.READ); vm.editTag("v1"); vm.save(); vm.generateNotes()
        coVerify(exactly = 0) { repository.createRelease(any(), any(), any()) }
        coVerify(exactly = 0) { repository.generateNotes(any(), any(), any(), any(), any()) }
    }
}
