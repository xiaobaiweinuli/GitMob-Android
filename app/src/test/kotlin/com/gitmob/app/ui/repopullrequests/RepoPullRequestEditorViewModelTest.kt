package com.gitmob.app.ui.repopullrequests

import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.*
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.markdown.CommonMarkRenderer
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoPullRequestRepository
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class RepoPullRequestEditorViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<RepoPullRequestRepository>()
    private val metadata = RepoPullRequestCreateMetadata("R1", "viewer", "main",
        listOf(RepoPullRequestHeadRepository("R1", "o", "r", emptyList())), emptyList(), emptyList(), emptyList(), emptyList())

    private fun editor(handle: SavedStateHandle = SavedStateHandle()) =
        RepoPullRequestEditorViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), CommonMarkRenderer(), accounts.session, handle)

    private fun init(vm: RepoPullRequestEditorViewModel) = vm.init("o", "r", null,
        baseRef = "release", headOwner = "fork", headRef = "feature", headRepositoryId = "R2")

    @Test fun routeDefaultsAndAllConfirmedInputsRestoreWithBodySelection() = runTest {
        coEvery { repository.getCreateMetadata("o", "r") } returns ApiResult.Success(metadata)
        val handle = SavedStateHandle()
        val first = editor(handle)
        init(first)
        advanceUntilIdle()
        assertEquals("release", first.state.value.draft.baseRef)
        assertEquals("feature", first.state.value.draft.headRef)
        assertEquals("R2", first.state.value.draft.headRepositoryId)
        first.editTitle("Draft title")
        first.editBaseRef("develop")
        first.editDraft(true)
        first.confirmMetadata(setOf("L1"), setOf("U1"), "M1", setOf("V1"))
        first.bodyState.edit { replace(0, length, "Draft body"); selection = TextRange(1, 5) }
        val expected = first.state.value.draft
        val saved = handle.savedStateProvider().saveState()
        first.viewModelScope.cancel()
        val restored = editor(SavedStateHandle.createHandle(saved, null))
        init(restored)
        advanceUntilIdle()
        assertEquals(expected, restored.state.value.draft)
        assertEquals("Draft body", restored.bodyState.text.toString())
        assertEquals(TextRange(1, 5), restored.bodyState.selection)
        restored.confirmMetadata(emptySet(), emptySet(), null, emptySet())
        restored.editTitle("")
        restored.editBaseRef("")
        restored.load()
        advanceUntilIdle()
        assertEquals("", restored.state.value.draft.title)
        assertEquals("", restored.state.value.draft.baseRef)
        assertTrue(restored.state.value.draft.labelIds.isEmpty())
        assertNull(restored.state.value.draft.milestoneId)
        restored.viewModelScope.cancel()
    }

    @Test fun loadingSnapshotDoesNotSuppressDefaultsAndClearedBodyRestores() = runTest {
        val pending = CompletableDeferred<ApiResult<RepoPullRequestCreateMetadata>>()
        coEvery { repository.getCreateMetadata("o", "r") } coAnswers { pending.await() }
        val handle = SavedStateHandle()
        val first = editor(handle)
        first.init("o", "r", 7)
        advanceUntilIdle()
        assertTrue(first.state.value.isLoading)
        val saved = handle.savedStateProvider().saveState()
        first.viewModelScope.cancel()
        val existing = mockk<RepoPullRequest>(relaxed = true) {
            every { title } returns "Server title"
            every { body } returns "Server body"
            every { baseRefName } returns "main"
            every { headRefName } returns "feature"
            every { milestone } returns null
        }
        coEvery { repository.getCreateMetadata("o", "r") } returns ApiResult.Success(metadata)
        coEvery { repository.getPullRequest("o", "r", 7) } returns ApiResult.Success(mockk { every { pullRequest } returns existing })
        val restoredHandle = SavedStateHandle.createHandle(saved, null)
        val restored = editor(restoredHandle)
        restored.init("o", "r", 7)
        advanceUntilIdle()
        assertEquals("Server body", restored.bodyState.text.toString())
        assertFalse(restored.bodyState.canUndo)
        restored.bodyState.edit { replace(0, length, "") }
        restored.editTitle("")
        val cleared = restoredHandle.savedStateProvider().saveState()
        restored.viewModelScope.cancel()
        val second = editor(SavedStateHandle.createHandle(cleared, null))
        second.init("o", "r", 7)
        advanceUntilIdle()
        assertEquals("", second.bodyState.text.toString())
        assertEquals("", second.state.value.draft.title)
        second.viewModelScope.cancel()
    }

    @Test fun failedCreationRetainsInputsAndSubmitSnapshotsCurrentBody() = runTest {
        coEvery { repository.getCreateMetadata("o", "r") } returns ApiResult.Success(metadata)
        val response = CompletableDeferred<ApiResult<RepoPullRequest>>()
        coEvery { repository.createPullRequest(any()) } coAnswers { response.await() }
        val vm = editor()
        init(vm)
        advanceUntilIdle()
        vm.editTitle("Title")
        vm.bodyState.content.replace(0, vm.bodyState.content.length, "Latest body")
        vm.save {}
        vm.bodyState.edit { replace(0, length, "Later edit") }
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.createPullRequest(match {
            it.title == "Title" && it.body == "Latest body" && it.headRepositoryId == "R2" && it.headOwner == "fork"
        }) }
        response.complete(ApiResult.Failure(ApiError.NetworkError))
        advanceUntilIdle()
        assertEquals("Later edit", vm.bodyState.text.toString())
        assertEquals("Title", vm.state.value.draft.title)
        assertFalse(vm.state.value.isSaving)
        vm.viewModelScope.cancel()
    }

    @Test fun metadataAndReviewerFailuresStillCompleteCreatedPullRequest() = runTest {
        coEvery { repository.getCreateMetadata("o", "r") } returns ApiResult.Success(metadata)
        val created = mockk<RepoPullRequest>(relaxed = true) { every { id } returns "PR1" }
        coEvery { repository.createPullRequest(any()) } returns ApiResult.Success(created)
        coEvery { repository.updatePullRequest(any()) } returns ApiResult.Failure(ApiError.NetworkError)
        coEvery { repository.requestReviews("PR1", listOf("V1")) } returns ApiResult.Failure(ApiError.NetworkError)
        coEvery { repository.getPullRequests("o", "r", any<RepoPullRequestFilter>()) } returns ApiResult.Failure(ApiError.NetworkError)
        val vm = editor()
        init(vm)
        advanceUntilIdle()
        vm.editTitle("Title")
        vm.confirmMetadata(setOf("L1"), setOf("U1"), "M1", setOf("V1"))
        var result: RepoPullRequest? = null
        vm.save { result = it }
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.updatePullRequest(match { it.labelIds == listOf("L1") && it.assigneeIds == listOf("U1") && it.milestoneId == "M1" }) }
        coVerify(exactly = 1) { repository.requestReviews("PR1", listOf("V1")) }
        assertSame(created, result)
        assertFalse(vm.state.value.isSaving)
        vm.viewModelScope.cancel()
    }
}
