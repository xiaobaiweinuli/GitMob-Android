package com.gitmob.app.ui.repodiscussions

import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.*
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.markdown.CommonMarkRenderer
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoDiscussionRepository
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
class RepoDiscussionEditorViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<RepoDiscussionRepository>()
    private val category = RepoDiscussionCategory("C1", "General", "", null, false)
    private val page = RepoDiscussionPage("R1", RepoPermission.WRITE, RepoPermission.WRITE.toCapabilities(), true, listOf(category), 0, emptyList(), false, null)

    private fun editor(handle: SavedStateHandle = SavedStateHandle()): RepoDiscussionEditorViewModel {
        coEvery { repository.getLabels("o", "r") } returns ApiResult.Success(emptyList())
        return RepoDiscussionEditorViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), CommonMarkRenderer(), accounts.session, handle)
    }

    @Test fun restoresCategoryLabelsBodyAndSelectionWithoutReapplyingDefaults() = runTest {
        coEvery { repository.getDiscussions("o", "r", any<RepoDiscussionFilter>()) } returns ApiResult.Success(page)
        val handle = SavedStateHandle()
        val first = editor(handle)
        first.init("o", "r", null)
        advanceUntilIdle()
        assertEquals("C1", first.state.value.draft.categoryId)
        first.editTitle("Draft title")
        first.confirmMetadata("C2", setOf("L1"))
        first.bodyState.edit { replace(0, length, "Draft body"); selection = TextRange(2, 4) }
        val saved = handle.savedStateProvider().saveState()
        val expected = first.state.value.draft
        first.viewModelScope.cancel()
        val restoredHandle = SavedStateHandle.createHandle(saved, null)
        val restored = editor(restoredHandle)
        restored.init("o", "r", null)
        advanceUntilIdle()
        assertEquals(expected, restored.state.value.draft)
        assertEquals("Draft body", restored.bodyState.text.toString())
        assertEquals(TextRange(2, 4), restored.bodyState.selection)
        restored.editTitle("")
        restored.confirmMetadata("", emptySet())
        restored.bodyState.edit { replace(0, length, "") }
        val cleared = restoredHandle.savedStateProvider().saveState()
        restored.viewModelScope.cancel()
        val second = editor(SavedStateHandle.createHandle(cleared, null))
        second.init("o", "r", null)
        advanceUntilIdle()
        assertEquals("", second.state.value.draft.title)
        assertEquals("", second.state.value.draft.categoryId)
        assertTrue(second.state.value.draft.labelIds.isEmpty())
        assertEquals("", second.bodyState.text.toString())
        second.viewModelScope.cancel()
    }

    @Test fun snapshotTakenDuringLoadingStillInitializesServerBody() = runTest {
        val pending = CompletableDeferred<ApiResult<RepoDiscussionPage>>()
        coEvery { repository.getDiscussions("o", "r", any<RepoDiscussionFilter>()) } coAnswers { pending.await() }
        val handle = SavedStateHandle()
        val first = editor(handle)
        first.init("o", "r", 7)
        advanceUntilIdle()
        assertTrue(first.state.value.isLoading)
        val saved = handle.savedStateProvider().saveState()
        first.viewModelScope.cancel()
        val server = mockk<RepoDiscussion>(relaxed = true) {
            every { title } returns "Server title"
            every { body } returns "Server body"
            every { category } returns this@RepoDiscussionEditorViewModelTest.category
        }
        coEvery { repository.getDiscussions("o", "r", any<RepoDiscussionFilter>()) } returns ApiResult.Success(page)
        coEvery { repository.getDiscussion("o", "r", 7) } returns ApiResult.Success(mockk { every { discussion } returns server })
        val restored = editor(SavedStateHandle.createHandle(saved, null))
        restored.init("o", "r", 7)
        advanceUntilIdle()
        assertEquals("Server title", restored.state.value.draft.title)
        assertEquals("Server body", restored.bodyState.text.toString())
        assertFalse(restored.bodyState.canUndo)
        restored.viewModelScope.cancel()
    }

    @Test fun failedCreateKeepsDraftAndLaterLabelFailureStillCompletesCreatedDiscussion() = runTest {
        coEvery { repository.getDiscussions("o", "r", any<RepoDiscussionFilter>()) } returns ApiResult.Success(page)
        val response = CompletableDeferred<ApiResult<RepoDiscussion>>()
        coEvery { repository.createDiscussion(any(), any(), any(), any()) } coAnswers { response.await() }
        val vm = editor()
        vm.init("o", "r", null)
        advanceUntilIdle()
        vm.editTitle("Title")
        vm.confirmMetadata("C2", setOf("L1"))
        vm.bodyState.content.replace(0, vm.bodyState.content.length, "Latest body")
        vm.save {}
        vm.bodyState.edit { replace(0, length, "Later edit") }
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.createDiscussion("R1", "Title", "Latest body", "C2") }
        response.complete(ApiResult.Failure(ApiError.NetworkError))
        advanceUntilIdle()
        assertEquals("Later edit", vm.bodyState.text.toString())
        assertEquals(setOf("L1"), vm.state.value.draft.labelIds)
        assertFalse(vm.state.value.isSaving)
        val created = mockk<RepoDiscussion> { every { id } returns "D1" }
        coEvery { repository.createDiscussion(any(), any(), any(), any()) } returns ApiResult.Success(created)
        coEvery { repository.addLabelsToDiscussion("D1", listOf("L1")) } returns ApiResult.Failure(ApiError.NetworkError)
        var result: RepoDiscussion? = null
        vm.save { result = it }
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.createDiscussion("R1", "Title", "Later edit", "C2") }
        coVerify(exactly = 1) { repository.addLabelsToDiscussion("D1", listOf("L1")) }
        assertSame(created, result)
        assertFalse(vm.state.value.isSaving)
        vm.viewModelScope.cancel()
    }
}
