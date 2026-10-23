package com.gitmob.app.ui.repoactions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipe
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.RepoActionsPage
import com.gitmob.app.data.model.RepoWorkflowRun
import com.gitmob.app.data.repository.RepoActionsRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepoActionsScreenTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repository = mockk<RepoActionsRepository>()
    private lateinit var viewModel: RepoActionsViewModel
    private lateinit var contentView: View
    private lateinit var inputModeManager: InputModeManager
    private var background = Color.Unspecified
    private var openedRunId: Long? = null
    private val run = RepoWorkflowRun(
        id = 42, name = "CI", displayTitle = "Build", event = "push", status = "completed",
        conclusion = "success", workflowId = 7, runNumber = 8, runAttempt = 1,
        headBranch = "main", headSha = "abcdef", actor = null,
        createdAt = "2026-10-23T00:00:00Z", updatedAt = "2026-10-23T00:01:00Z",
        htmlUrl = "https://github.com/octo/repo/actions/runs/42",
    )

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
    }

    @Test
    fun `closed run has an opaque theme background and no delete entry`() {
        render()

        assertRowBackground()
        deleteEntries().assertCountEquals(0)
    }

    @Test
    fun `left swipe directly requests confirmation without deleting the run`() {
        render()

        composeRule.onNodeWithText(run.displayTitle).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(run, viewModel.state.value.pendingDeleteRun)
        composeRule.onNodeWithText(run.displayTitle).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.actions_delete_run_message, run.runNumber, run.displayTitle)).assertIsDisplayed()
        deleteEntries().assertCountEquals(0)
        assertNull(openedRunId)
        coVerify(exactly = 0) { repository.deleteRun(any(), any(), any()) }
    }

    @Test
    fun `read permission has no swipe deletion entry`() {
        render(RepoPermission.READ)

        composeRule.onNodeWithText(run.displayTitle).performTouchInput { swipeLeft() }
        deleteEntries().assertCountEquals(0)
        assertNull(viewModel.state.value.pendingDeleteRun)
        assertTrue(customActions().isEmpty())
    }

    @Test
    fun `right swipe does nothing and a normal click opens the run`() {
        render()
        composeRule.onNodeWithText(run.displayTitle).performTouchInput { swipeRight() }
        deleteEntries().assertCountEquals(0)
        assertRowBackground()

        composeRule.onNodeWithText(run.displayTitle).performClick()
        assertEquals(run.id, openedRunId)
        assertNull(viewModel.state.value.pendingDeleteRun)
        coVerify(exactly = 0) { repository.deleteRun(any(), any(), any()) }
    }

    @Test
    fun `short left drag returns without requesting deletion`() {
        render()
        composeRule.onNodeWithText(run.displayTitle).performTouchInput {
            swipe(center, center - Offset(width * .1f, 0f), durationMillis = 600)
        }

        assertNull(viewModel.state.value.pendingDeleteRun)
        assertNull(openedRunId)
        assertRowBackground()
        deleteEntries().assertCountEquals(0)
        coVerify(exactly = 0) { repository.deleteRun(any(), any(), any()) }
    }

    @Test
    fun `focused row Delete key opens the same confirmation on key up`() {
        render()
        composeRule.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        val row = composeRule.onNodeWithText(run.displayTitle)
        row.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        row.assertIsFocused()
        row.performKeyInput { keyDown(Key.Delete) }
        assertNull(viewModel.state.value.pendingDeleteRun)
        row.performKeyInput { keyUp(Key.Delete) }

        assertEquals(run, viewModel.state.value.pendingDeleteRun)
        confirmation().assertIsDisplayed()
        deleteEntries().assertCountEquals(0)
        coVerify(exactly = 0) { repository.deleteRun(any(), any(), any()) }
    }

    @Test
    fun `custom accessibility action opens confirmation without a visible delete button`() {
        render()
        val actions = customActions()
        assertEquals(listOf(context.getString(R.string.actions_delete_run)), actions.map { it.label })
        composeRule.runOnIdle { assertTrue(actions.single().action()) }

        assertEquals(run, viewModel.state.value.pendingDeleteRun)
        confirmation().assertIsDisplayed()
        deleteEntries().assertCountEquals(0)
        coVerify(exactly = 0) { repository.deleteRun(any(), any(), any()) }
    }

    @Test
    fun `delete requires a separate confirmation and cancel keeps the run`() {
        render()
        swipeToConfirm()

        confirmation().assertIsDisplayed()
        coVerify(exactly = 0) { repository.deleteRun(any(), any(), any()) }
        composeRule.onNodeWithText(context.getString(R.string.common_cancel)).performClick()

        composeRule.onNodeWithText(run.displayTitle).assertIsDisplayed()
        assertNull(viewModel.state.value.pendingDeleteRun)
        assertEquals(listOf(run), viewModel.state.value.runs)
        assertRowBackground()
        deleteEntries().assertCountEquals(0)
        coVerify(exactly = 0) { repository.deleteRun(any(), any(), any()) }
    }

    @Test
    fun `failed confirmed deletion retains the row and allows another swipe`() {
        coEvery { repository.deleteRun("octo", "repo", run.id) } returns ApiResult.Failure(ApiError.Forbidden)
        render()
        swipeToConfirm()
        composeRule.onNodeWithText(context.getString(R.string.common_delete)).performClick()

        composeRule.onNodeWithText(run.displayTitle).assertIsDisplayed()
        assertEquals(listOf(run), viewModel.state.value.runs)
        assertRowBackground()
        swipeToConfirm()
        confirmation().assertIsDisplayed()
        coVerify(exactly = 1) { repository.deleteRun("octo", "repo", run.id) }
    }

    @Test
    fun `confirmed deletion disables duplicate interaction and removes only after success`() {
        val pending = CompletableDeferred<ApiResult<Unit>>()
        coEvery { repository.deleteRun("octo", "repo", run.id) } coAnswers { pending.await() }
        render()
        swipeToConfirm()
        composeRule.onNodeWithText(context.getString(R.string.common_delete)).performClick()

        composeRule.onNodeWithText(run.displayTitle).assertIsDisplayed().assertIsNotEnabled()
        assertTrue(customActions().isEmpty())
        swipeToConfirm()
        assertNull(viewModel.state.value.pendingDeleteRun)
        assertEquals(listOf(run), viewModel.state.value.runs)
        composeRule.runOnIdle { pending.complete(ApiResult.Success(Unit)) }
        composeRule.onNodeWithText(run.displayTitle).assertDoesNotExist()
        coVerify(exactly = 1) { repository.deleteRun("octo", "repo", run.id) }
    }

    @Test
    fun `running record stays visible without automatic cancellation or deletion`() {
        render(record = run.copy(status = "in_progress", conclusion = null))
        swipeToConfirm()

        composeRule.onNodeWithText(run.displayTitle).assertIsDisplayed()
        assertRowBackground()
        assertNull(viewModel.state.value.pendingDeleteRun)
        coVerify(exactly = 0) { repository.deleteRun(any(), any(), any()) }
        coVerify(exactly = 0) { repository.cancel(any(), any(), any(), any()) }
    }

    private fun swipeToConfirm() {
        composeRule.onNodeWithText(run.displayTitle).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
    }

    private fun confirmation() = composeRule.onNodeWithText(
        context.getString(R.string.actions_delete_run_message, run.runNumber, run.displayTitle),
    )

    private fun customActions() = composeRule.onNodeWithText(run.displayTitle)
        .fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun assertRowBackground() {
        val top = composeRule.onNodeWithText(run.displayTitle).fetchSemanticsNode().boundsInRoot.top.toInt()
        composeRule.runOnIdle {
            val bitmap = Bitmap.createBitmap(contentView.width, contentView.height, Bitmap.Config.ARGB_8888)
            contentView.draw(Canvas(bitmap))
            assertEquals(background.toArgb(), bitmap.getPixel(2, top + 2))
            assertEquals(background.toArgb(), bitmap.getPixel(bitmap.width - 2, top + 2))
            bitmap.recycle()
        }
    }

    private fun deleteEntries() = composeRule.onAllNodesWithContentDescription(context.getString(R.string.actions_delete_run))

    private fun render(permission: RepoPermission = RepoPermission.WRITE, record: RepoWorkflowRun = run) {
        coEvery { repository.getActions("octo", "repo") } returns ApiResult.Success(
            RepoActionsPage(1, emptyList(), listOf(record), 1, false),
        )
        viewModel = RepoActionsViewModel(repository, ErrorEventBus(), RepoUpdateEventBus())
        composeRule.setContent {
            MaterialTheme {
                background = MaterialTheme.colorScheme.background
                contentView = LocalView.current
                inputModeManager = LocalInputModeManager.current
                RepoActionsScreen(
                    owner = "octo", name = "repo", permission = permission, defaultRef = "main",
                    onBack = {}, onOwnerClick = {}, onRepositoryClick = { _, _ -> }, onRunClick = { openedRunId = it },
                    viewModel = viewModel,
                )
            }
        }
    }
}
