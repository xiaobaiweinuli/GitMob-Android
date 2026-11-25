package com.gitmob.app.ui.repocode

import android.content.Context
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoCodeTree
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepoFileEditorScreenTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val compose = createComposeRule()
    private val repository = mockk<RepoGitRepository>()
    private lateinit var vm: RepoFileEditorViewModel
    private fun label(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun showEditor() {
        coEvery { repository.getCodeTree("owner", "repo", "main", "") } returns ApiResult.Success(
            RepoCodeTree("R", RepoPermission.MAINTAIN, RepoPermission.MAINTAIN.toCapabilities(),
                false, ref = "main", headOid = "HEAD", path = "", entries = emptyList()),
        )
        vm = RepoFileEditorViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session)
        compose.setContent {
            MaterialTheme {
                RepoFileEditorScreen("owner", "repo", "main", null,
                    onBack = {}, onOwnerClick = {}, onRepositoryClick = { _, _ -> }, onSaved = {}, viewModel = vm)
            }
        }
        compose.waitUntil { !vm.state.value.isLoading && vm.state.value.headOid != null }
        compose.runOnIdle { vm.updatePath("docs/README.md") }
    }

    @After fun cleanup() { if (::vm.isInitialized) vm.viewModelScope.cancel() }

    @Test fun saveOpensSheetWithFilenameTitleAndPreservesMultilineDraftAcrossDismissal() {
        showEditor()
        compose.onNodeWithText(label(R.string.repo_commit_title)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.common_save)).performClick()
        compose.onNodeWithText(label(R.string.repo_commit_title)).assertTextContains("Update README.md")
        compose.onNodeWithText("main").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.repo_commit_submit)).assertIsEnabled()
        coVerify(exactly = 0) { repository.createCommit(any(), any(), any(), any(), any(), any(), any(), any()) }

        compose.onNodeWithText(label(R.string.repo_commit_title)).performTextReplacement("")
        compose.onNodeWithText(label(R.string.repo_commit_submit)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.repo_commit_title)).performTextReplacement("Explain the change")
        val body = "First paragraph\n\n第二段\n- detail"
        compose.onNodeWithText(label(R.string.repo_commit_description)).performTextReplacement(body)
        compose.onNodeWithContentDescription(label(R.string.common_cancel)).performClick()
        compose.onNodeWithText(label(R.string.repo_commit_changes)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.common_save)).performClick()
        compose.onNodeWithText(label(R.string.repo_commit_title)).assertTextContains("Explain the change")
        compose.onNodeWithText(label(R.string.repo_commit_description)).assertTextContains(body)
        coVerify(exactly = 0) { repository.createCommit(any(), any(), any(), any(), any(), any(), any(), any()) }

        coEvery { repository.createCommit(any(), any(), any(), any(), any(), any(), any(), any()) } returns ApiResult.Failure(ApiError.NetworkError)
        compose.onNodeWithText(label(R.string.repo_commit_submit)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            // Resume background encoding on Robolectric's paused main looper.
            shadowOf(Looper.getMainLooper()).idle()
            !vm.state.value.isSaving
        }
        compose.onNodeWithText(label(R.string.repo_commit_changes)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.repo_commit_submit)).assertIsEnabled()
        assertEquals(body, vm.state.value.commitBody)
        coVerify(exactly = 1) { repository.createCommit(any(), any(), any(), "Explain the change", any(), any(), "HEAD", body) }
    }

    @Config(qualifiers = "en-rUS-w360dp-h480dp")
    @Test fun longDescriptionKeepsSubmitVisibleAndEditableOnSmallHeight() {
        showEditor()
        compose.onNodeWithContentDescription(label(R.string.common_save)).performClick()
        compose.onNodeWithText(label(R.string.repo_commit_description))
            .performTextReplacement((1..100).joinToString("\n") { "Description paragraph $it" })
        compose.onNodeWithText(label(R.string.repo_commit_submit)).assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertTrue(vm.state.value.commitBody.contains("paragraph 100")) }
    }
}
