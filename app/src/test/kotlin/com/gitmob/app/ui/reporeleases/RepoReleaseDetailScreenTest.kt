package com.gitmob.app.ui.reporeleases

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoRelease
import com.gitmob.app.data.repository.RepoReleaseRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepoReleaseDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    private val release = RepoRelease(7, "R7", "v1", "main", "Title", "", null, true, false, "", null, null, "", emptyList(), null, null)
    private val state = MutableStateFlow(RepoReleaseDetailUiState(
        release = release,
        permission = RepoPermission.WRITE,
        capabilities = RepoPermission.WRITE.toCapabilities(),
        upload = ReleaseAssetUploadState(phase = ReleaseAssetUploadPhase.CANCELLED),
    ))
    private val vm = mockk<RepoReleaseDetailViewModel>(relaxed = true) {
        every { state } returns this@RepoReleaseDetailScreenTest.state
    }
    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)
    private fun show() {
        compose.setContent {
            MaterialTheme {
                RepoReleaseDetailScreen("o", "r", null, 7, RepoPermission.WRITE, {}, {}, { _, _ -> }, {}, vm)
            }
        }
    }

    @Test fun cancelledUploadCanRefreshAndBlocksRefreshAndUploadWhileLoading() {
        show()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.common_refresh)))
        compose.onNodeWithText(text(R.string.common_refresh)).performClick()
        verify(exactly = 1) { vm.load() }
        compose.runOnIdle { state.value = state.value.copy(isLoading = true) }
        compose.onNodeWithText(text(R.string.common_refresh)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.release_upload_asset)).assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(isLoading = false) }
        compose.onNodeWithText(text(R.string.common_refresh)).assertIsEnabled()
        compose.onNodeWithContentDescription(text(R.string.release_upload_asset)).assertIsEnabled()
    }

    @Test fun activeUploadKeepsCancelActionWithoutRefreshAction() {
        state.value = state.value.copy(upload = ReleaseAssetUploadState(phase = ReleaseAssetUploadPhase.UPLOADING, sent = 100, total = 100))
        show()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.common_cancel)))
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        verify(exactly = 1) { vm.cancelUpload() }
        compose.onNodeWithText(text(R.string.common_refresh)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.release_upload_asset)).assertIsNotEnabled()
    }
}

/** Retains the detail ViewModel while the real editor publishes account-scoped changes. */
class RepoReleaseDetailRefreshTest {
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts)
    private val repository = mockk<RepoReleaseRepository>()
    private val errors = mockk<ErrorEventBus>(relaxed = true)
    private val updates = RepoUpdateEventBus()
    private var stored = RepoRelease(7, "R7", "v1", "main", "Original title", "Original body", null, true, false, "", null, null, "", emptyList(), null, null)

    private fun detail(): RepoReleaseDetailViewModel {
        coEvery { repository.getReleaseByTag("o", "r", "v1") } coAnswers { ApiResult.Success(stored) }
        return RepoReleaseDetailViewModel(repository, errors, updates, mockk(relaxed = true), accounts.session)
            .also { it.init("o", "r", "v1", null, RepoPermission.WRITE) }
    }

    private fun change(owner: String = "o", name: String = "r", tag: String = "v1") =
        RepoUpdateEvent.ReleaseChanged(owner, name, -1, stored.name, tag)

    @Test fun savingTitleWithSameTagRefreshesRetainedDetail() = runTest {
        assertEditorSaveRefreshesDetail(title = "Saved title", body = stored.body)
    }

    @Test fun savingOnlyBodyWithSameTagRefreshesRetainedDetail() = runTest {
        assertEditorSaveRefreshesDetail(title = stored.name.orEmpty(), body = "Saved body")
    }

    private fun assertEditorSaveRefreshesDetail(title: String, body: String) {
        val detail = detail()
        // Re-entering the retained page must not create a second event subscription.
        detail.init("o", "r", "v1", null, RepoPermission.WRITE)
        coEvery { repository.getRelease("o", "r", 7) } coAnswers { ApiResult.Success(stored) }
        coEvery { repository.updateRelease("o", "r", 7, any()) } coAnswers {
            stored = stored.copy(name = title, body = body)
            ApiResult.Success(stored)
        }
        val editor = RepoReleaseEditorViewModel(repository, errors, updates, accounts.session)
        editor.init("o", "r", 7, RepoPermission.WRITE)
        editor.editTitle(title)
        editor.editBody(body)
        editor.save()
        assertEquals(stored, editor.state.value.savedRelease)
        assertEquals(stored, detail.state.value.release)
        assertEquals("v1", detail.state.value.release!!.tagName)
        coVerify(exactly = 2) { repository.getReleaseByTag("o", "r", "v1") }
        coVerify(exactly = 1) { repository.updateRelease("o", "r", 7, any()) }
    }

    @Test fun otherRepositoriesTagsAndEventTypesDoNotRefreshDetail() = runTest(accounts.context) {
        val detail = detail()
        updates.emit(change(owner = "another-owner"))
        updates.emit(change(name = "another-repo"))
        updates.emit(change(tag = "v2"))
        updates.emit(RepoUpdateEvent.IssueCountChanged("o", "r", 5))
        assertEquals(stored, detail.state.value.release)
        coVerify(exactly = 1) { repository.getReleaseByTag("o", "r", "v1") }
    }

    @Test fun accountSwitchStopsOldDetailFromRefreshing() = runTest {
        detail()
        accounts.session.replace(emptyList(), null)
        withContext(accounts.context) { updates.emit(change()) }
        coVerify(exactly = 1) { repository.getReleaseByTag("o", "r", "v1") }
    }

    @Test fun clearingDetailStopsItsRefreshSubscription() = runTest(accounts.context) {
        val store = ViewModelStore()
        store.put("detail", detail())
        store.clear()
        updates.emit(change())
        coVerify(exactly = 1) { repository.getReleaseByTag("o", "r", "v1") }
    }

    @Test fun deletingReleaseDoesNotReloadTheDeletedDetail() = runTest {
        val detail = detail()
        coEvery { repository.deleteRelease("o", "r", 7) } returns ApiResult.Success(Unit)
        var exited = false
        detail.delete { exited = true }
        assertTrue(exited)
        assertNull(detail.state.value.release)
        coVerify(exactly = 1) { repository.getReleaseByTag("o", "r", "v1") }
        coVerify(exactly = 0) { errors.emit(any(), any()) }
    }
}
