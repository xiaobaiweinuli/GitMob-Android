package com.gitmob.app.ui.repoactions

import android.content.Context
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.share.ShareLinkLauncher
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoActionsRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepoActionsRecoveryScreenTest {
    private val compose = createComposeRule()
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repository = mockk<RepoActionsRepository>()
    private val share = mockk<ShareLinkLauncher>(relaxed = true)
    private lateinit var vm: RepoActionsViewModel
    private val workflow = RepoWorkflow(7, "W7", "CI", ".github/workflows/ci.yml", "active", null, null)
    private val inputs = listOf(
        WorkflowDispatchInput("message", null, true, WorkflowDispatchInputType.STRING, "original", emptyList()),
        WorkflowDispatchInput("flag", null, false, WorkflowDispatchInputType.BOOLEAN, "false", emptyList()),
    )

    @After fun close() { if (::vm.isInitialized) vm.viewModelScope.cancel() }
    private fun text(resource: Int) = compose.onNodeWithText(context.getString(resource))
    private fun menu() { compose.onNodeWithContentDescription(context.getString(R.string.common_more)).performClick() }
    private fun render(permission: RepoPermission = RepoPermission.ADMIN, enabled: Boolean = true,
        initialSettings: ApiResult<Boolean> = ApiResult.Success(enabled), isArchived: Boolean = false) {
        coEvery { repository.getActions(any(), any()) } returns ApiResult.Success(RepoActionsPage(0, listOf(workflow), emptyList(), 1, false))
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns initialSettings
        coEvery { repository.getDispatchInputs(any(), any(), any(), any()) } returns ApiResult.Success(inputs)
        vm = RepoActionsViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), share, accounts.session)
        compose.setContent {
            MaterialTheme {
                RepoActionsScreen("octo", "repo", permission, "main", isArchived, {}, {}, { _, _ -> }, {},
                    viewModel = vm)
            }
        }
    }
    private fun openDispatch() {
        text(R.string.actions_workflows).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.actions_run_workflow)).performClick()
    }
    private fun submitButton() = compose.onNode(hasText(context.getString(R.string.actions_run_workflow)) and hasClickAction())
    private fun submit() = submitButton().performClick()
    private fun assertNoRecoveryContent() {
        compose.onNode(isDialog()).assertExists()
        text(R.string.actions_repository_disabled).assertDoesNotExist()
        text(R.string.actions_repository_not_confirmed).assertDoesNotExist()
        text(R.string.actions_repository_enable).assertDoesNotExist()
        text(R.string.actions_repository_disable).assertDoesNotExist()
        text(R.string.common_retry).assertDoesNotExist()
    }

    @Test fun archivedAdminSeesOnlyShareAndReadOnlyWorkflows() {
        render(isArchived = true)
        menu()
        text(R.string.conversation_share).assertIsDisplayed()
        text(R.string.actions_repository_enable).assertDoesNotExist()
        text(R.string.actions_repository_disable).assertDoesNotExist()
        text(R.string.actions_repository_unknown).assertDoesNotExist()
        text(R.string.conversation_share).performClick()
        text(R.string.actions_workflows).performClick()
        compose.onNodeWithText("CI").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.actions_run_workflow)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.actions_disable_workflow)).assertDoesNotExist()
        compose.onNode(isDialog()).assertDoesNotExist()
        coVerify(exactly = 0) { repository.getRepositoryActionsEnabled(any(), any()) }
        coVerify(exactly = 0) { repository.setRepositoryActionsEnabled(any(), any(), any()) }
    }

    @Test fun `shareOnlyMenuHasNoManagementPlaceholderOrExcessWidth`() {
        render(RepoPermission.WRITE)
        menu()
        text(R.string.conversation_share).assertIsDisplayed()
        val bounds = text(R.string.conversation_share).getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left <= 100.dp)
        text(R.string.actions_repository_enable).assertDoesNotExist()
        text(R.string.actions_repository_disable).assertDoesNotExist()
        text(R.string.actions_repository_unknown).assertDoesNotExist()
        val popupView = compose.onNode(isPopup()).fetchSemanticsNode().root as View
        compose.runOnIdle { assertTrue(popupView.requestFocusFromTouch()) }
        compose.onNode(isPopup()).performKeyInput { pressKey(Key.DirectionDown) }
        text(R.string.conversation_share).assertIsFocused()
        text(R.string.conversation_share).performKeyInput { pressKey(Key.Enter) }
        verify { share.share("https://github.com/octo/repo/actions") }
    }

    @Test fun `adminMenuIsSharedAcrossTabsAndUsesConfirmedDirection`() {
        render(enabled = false)
        menu()
        text(R.string.actions_repository_enable).assertIsDisplayed().assertIsEnabled()
        text(R.string.conversation_share).performClick()
        text(R.string.actions_workflows).performClick()
        menu()
        coEvery { repository.setRepositoryActionsEnabled(any(), any(), true) } returns ApiResult.Success(Unit)
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Success(true)
        text(R.string.actions_repository_enable).performClick()
        text(R.string.actions_repository_enable).assertDoesNotExist()
        menu()
        text(R.string.actions_repository_disable).assertIsDisplayed()
    }

    @Test fun writerRejectedDialogKeepsFormWithoutRecoveryContent() {
        render(RepoPermission.WRITE)
        openDispatch()
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } returns ApiResult.Failure(ApiError.UserVisible(R.string.actions_repository_disabled))
        submit()
        assertNoRecoveryContent()
        compose.onNodeWithText("main").assertIsDisplayed()
        compose.onNodeWithText("original").assertIsDisplayed()
        submitButton().assertIsNotEnabled()
        text(R.string.common_cancel).assertIsEnabled().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        coVerify(exactly = 0) { repository.getRepositoryActionsEnabled(any(), any()) }
        coVerify(exactly = 0) { repository.setRepositoryActionsEnabled(any(), any(), any()) }
    }

    @Test fun adminRecoversThroughMenuThenManuallyReopensAndRunsWorkflow() {
        render()
        openDispatch()
        compose.onNodeWithText("main").performTextReplacement("release")
        text(R.string.actions_load_inputs).performClick()
        compose.onNodeWithText("original").performTextReplacement("edited")
        compose.onNode(isToggleable() and hasText("flag")).performClick()
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } returns ApiResult.Failure(ApiError.UserVisible(R.string.actions_repository_disabled))
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Success(false)
        submit()
        assertNoRecoveryContent()
        compose.onNodeWithText("release").assertIsDisplayed()
        compose.onNodeWithText("edited").assertIsDisplayed()
        compose.onNode(isToggleable() and hasText("flag")).assertIsOn()
        submitButton().assertIsNotEnabled()
        text(R.string.common_cancel).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.actions_run_workflow)).assertIsNotEnabled()
        menu()
        coEvery { repository.setRepositoryActionsEnabled(any(), any(), true) } returns ApiResult.Success(Unit)
        coEvery { repository.getRepositoryActionsEnabled(any(), any()) } returns ApiResult.Success(true)
        text(R.string.actions_repository_enable).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        coVerify(exactly = 1) { repository.dispatch(any(), any(), any(), any(), any()) }
        openDispatch()
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } returns ApiResult.Success(Unit)
        submit()
        coVerify(exactly = 2) { repository.dispatch(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { repository.dispatch("octo", "repo", 7, "release", mapOf("message" to "original", "flag" to "false")) }
    }

    @Test fun unknownSettingIsDisabledInMenuButWriterCanStillRun() {
        render(initialSettings = ApiResult.Failure(ApiError.Forbidden))
        menu()
        text(R.string.actions_repository_unknown).assertIsNotEnabled()
        text(R.string.conversation_share).performClick()
        openDispatch()
        submitButton().assertIsEnabled()
        assertNoRecoveryContent()
    }

    @Test fun adminRejectedDialogHasNoStatusOrRecoveryActions() {
        render()
        openDispatch()
        coEvery { repository.dispatch(any(), any(), any(), any(), any()) } returns ApiResult.Failure(ApiError.UserVisible(R.string.actions_repository_disabled))
        submit()
        assertNoRecoveryContent()
        submitButton().assertIsNotEnabled()
        text(R.string.common_cancel).assertIsEnabled().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        menu()
        text(R.string.actions_repository_disable).assertIsDisplayed().assertIsEnabled()
    }

    @Test fun confirmedDisabledRepositoryCannotOpenDispatchDialog() {
        render(enabled = false)
        text(R.string.actions_workflows).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.actions_run_workflow)).assertIsNotEnabled()
        compose.onNode(isDialog()).assertDoesNotExist()
        coVerify(exactly = 0) { repository.getDispatchInputs(any(), any(), any(), any()) }
        menu()
        text(R.string.actions_repository_enable).assertIsEnabled()
    }

}
