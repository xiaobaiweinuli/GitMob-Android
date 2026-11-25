package com.gitmob.app.ui.repoactions

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.compose.ui.platform.LocalView
import com.gitmob.app.ui.common.awaitSoraViewer
import com.gitmob.app.ui.common.awaitSoraLayout
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.*
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoActionsRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
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
class RepoActionLogsScreenTest {
    private val compose = createComposeRule()
    private val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
    private lateinit var inputMode: androidx.compose.ui.input.InputModeManager
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repository = mockk<RepoActionsRepository>()
    private var runVm: RepoWorkflowRunViewModel? = null
    private var logVm: RepoActionStepLogViewModel? = null
    private val key = RepoActionStepLogKey("octo", "repo", 42, 3, 101, 8)
    private val run = RepoWorkflowRun(42, "CI", "Build", "push", "completed", "failure", 7, 8, 3, "main", "abc", null, "", "", "https://github.com/octo/repo/actions/runs/42")
    private var opened: RepoActionStepLogKey? = null
    private var backed = false
    private lateinit var root: View
    @After fun close() { runVm?.viewModelScope?.cancel(); logVm?.viewModelScope?.cancel() }
    private fun label(id: Int) = context.getString(id)

    @Test fun `step names open their real job and number and zip belongs to the section header`() {
        coEvery { repository.downloadRunLogs("octo", "repo", 42, 3) } returns ApiResult.Success(Unit)
        renderRun()
        val zip = compose.onNodeWithContentDescription(label(R.string.actions_logs_download_zip))
        zip.performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithContentDescription(label(R.string.actions_logs_download_zip)).assertCountEquals(1)
        val button = zip.fetchSemanticsNode().boundsInRoot
        val heading = compose.onNodeWithText(label(R.string.actions_jobs)).fetchSemanticsNode().boundsInRoot
        assertTrue(button.left >= heading.right)
        assertTrue(kotlin.math.abs(button.center.y - heading.center.y) < 2f)
        zip.performClick()
        coVerify(exactly = 1) { repository.downloadRunLogs("octo", "repo", 42, 3) }
        compose.onNodeWithText("8. Compile A").performScrollTo().performClick()
        assertEquals(key, opened)
        compose.onNodeWithText("8. Compile B").performScrollTo().performClick()
        assertEquals(key.copy(jobId = 202), opened)
    }

    @Test fun `each rerun confirmation starts with debug off and cancel does not submit`() {
        renderRun()
        for (entry in listOf(R.string.actions_rerun, R.string.actions_rerun_failed)) {
            compose.onNodeWithContentDescription(label(R.string.issue_more)).performClick()
            compose.onNodeWithText(label(entry)).performClick()
            val debug = compose.onNodeWithText(label(R.string.actions_debug_logging))
            debug.assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText(label(R.string.common_cancel)).performClick()
        }
        coVerify(exactly = 0) { repository.rerun(any(), any(), any(), any(), any()) }
    }

    @Test fun `failed rerun confirmation submits debug option only after confirm`() {
        coEvery { repository.rerun("octo", "repo", 42, true, true) } returns ApiResult.Success(Unit)
        renderRun()
        compose.onNodeWithContentDescription(label(R.string.issue_more)).performClick()
        compose.onNodeWithText(label(R.string.actions_rerun_failed)).performClick()
        compose.onNodeWithText(label(R.string.actions_debug_logging)).performClick()
        coVerify(exactly = 0) { repository.rerun(any(), any(), any(), any(), any()) }
        compose.onNodeWithText(label(R.string.common_confirm)).performClick()
        coVerify(exactly = 1) { repository.rerun("octo", "repo", 42, true, true) }
    }

    @Test fun `log toolbar uses icon only wrap and copies raw text at large font scale`() {
        val raw = "\u001B[31mhello\u001B[0m\r\n\tworld\n"
        renderLog(raw, fontScale = 2f)
        val viewer = compose.awaitSoraViewer(root)
        compose.runOnIdle { assertEquals("hello\n    world", viewer.text.toString()) }
        val wrap = compose.onNodeWithContentDescription(label(R.string.actions_logs_wrap))
        wrap.assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText(label(R.string.actions_logs_wrap)).assertDoesNotExist()
        assertTrue(wrap.fetchSemanticsNode().boundsInRoot.width >= 48f)
        assertTrue(wrap.fetchSemanticsNode().boundsInRoot.height >= 48f)
        compose.onNodeWithContentDescription(label(R.string.actions_logs_copy)).performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(raw, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.onNodeWithContentDescription(label(R.string.actions_logs_refresh)).performClick()
        wrap.assertIsOn()
        coVerify(exactly = 2) { repository.getStepLog(key) }
        compose.onNodeWithContentDescription(label(R.string.common_back)).performClick()
        assertTrue(backed)
    }

    @Test fun `truncated preview names partial copying and still copies the original preview`() {
        renderLog("preview\r\n", truncated = true)
        compose.onNodeWithText(label(R.string.actions_logs_truncated)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.actions_logs_copy_partial)).performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("preview\r\n", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test fun `thousands of lines render lazily and an extremely long line can wrap`() {
        val raw = "x".repeat(100_000) + "\n" + (2..10_000).joinToString("\n") { "line-$it" }
        renderLog(raw)
        val viewer = compose.awaitSoraViewer(root)
        compose.runOnIdle {
            assertEquals(10_000, viewer.text.lineCount)
            assertTrue(viewer.lastVisibleLine < 100)
            assertTrue(viewer.height < 2_000)
        }
        compose.onNodeWithContentDescription(label(R.string.actions_logs_wrap)).performClick().assertIsOn()
        compose.awaitSoraLayout(viewer)
        compose.runOnIdle {
            assertTrue(viewer.isWordwrap)
            assertTrue(viewer.layout.rowCount > viewer.text.lineCount)
            assertEquals(raw, viewer.text.toString())
            viewer.setSelection(9_999, 0)
            viewer.ensurePositionVisible(9_999, 0, true)
        }
        compose.awaitSoraLayout(viewer)
        compose.runOnIdle { assertEquals("Last line after scrolling to the final log row", 9_999, viewer.lastVisibleLine) }
        compose.onNodeWithContentDescription(label(R.string.actions_logs_refresh)).assertIsDisplayed()
    }

    @Test fun `wrapped preview keeps original line numbers and Unicode code points`() {
        val first = "a😀中文".repeat(100)
        renderLog(first + "\r\nlast\r\n")
        val viewer = compose.awaitSoraViewer(root)
        compose.onNodeWithContentDescription(label(R.string.actions_logs_wrap)).performClick()
        compose.awaitSoraLayout(viewer)
        compose.runOnIdle {
            assertEquals(2, viewer.text.lineCount)
            assertEquals(first, viewer.text.getLineString(0))
            assertEquals("last", viewer.text.getLineString(1))
            assertTrue(viewer.layout.rowCount > 2)
            for (index in 0 until viewer.layout.rowCount) {
                val row = viewer.layout.getRowAt(index)
                val text = viewer.text.getLineString(row.lineIndex).substring(row.startColumn, row.endColumn)
                assertTrue(text.isEmpty() || (!text.first().isLowSurrogate() && !text.last().isHighSurrogate()))
            }
        }
    }

    @Test fun `one megabyte line wraps without losing text and refresh retains position`() {
        val raw = "x".repeat(1024 * 1024)
        renderLog(raw)
        val viewer = compose.awaitSoraViewer(root)
        compose.onNodeWithContentDescription(label(R.string.actions_logs_wrap)).performClick()
        compose.awaitSoraLayout(viewer)
        compose.runOnIdle {
            assertEquals(raw, viewer.text.toString())
            assertEquals(1, viewer.text.lineCount)
            assertTrue(viewer.layout.rowCount > 1000)
            viewer.setSelection(0, 32_000)
        }
        compose.awaitSoraLayout(viewer)
        val position = compose.runOnIdle { viewer.capturePosition() }
        compose.onNodeWithContentDescription(label(R.string.actions_logs_refresh)).performClick()
        val refreshed = compose.awaitSoraViewer(root)
        compose.awaitSoraLayout(refreshed)
        compose.runOnIdle {
            assertEquals(position.line, refreshed.capturePosition().line)
            assertEquals(position.column, refreshed.capturePosition().column)
            assertEquals(32_000, refreshed.cursor.leftColumn)
        }
    }


    @Test fun `returning to the run restores the selected action and keyboard focus`() {
        renderRun()
        compose.runOnIdle { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        compose.onNodeWithText("8. Compile B").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("8. Compile B").assertIsDisplayed().assertIsFocused()
    }

    private fun renderRun() {
        val jobs = listOf(101L to "A", 202L to "B").map { (id, suffix) ->
            RepoActionJob(id, "Build $suffix", "completed", "success", null, null, null,
                listOf(RepoActionStep("Compile $suffix", "completed", "success", 8)))
        }
        coEvery { repository.getRun("octo", "repo", 42) } returns ApiResult.Success(RepoWorkflowRunDetail(run, jobs, emptyList()))
        val vm = RepoWorkflowRunViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), mockk(relaxed = true), accounts.session)
        runVm = vm
        restoration.setContent { MaterialTheme {
            inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
            RepoWorkflowRunScreen("octo", "repo", 42, RepoPermission.WRITE, false, {}, {}, { _, _ -> }, { opened = it }, vm)
        } }
    }
    private fun renderLog(raw: String, truncated: Boolean = false, fontScale: Float = 1f) {
        coEvery { repository.getStepLog(key) } returns ApiResult.Success(RepoActionStepLog("Build A", "Compile A", ActionLogAvailability.READY, raw, truncated))
        val vm = RepoActionStepLogViewModel(repository, ErrorEventBus(), accounts.session)
        logVm = vm
        compose.setContent {
            root = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            MaterialTheme { RepoActionStepLogScreen(key, { backed = true }, vm) }
        } }
    }
}
