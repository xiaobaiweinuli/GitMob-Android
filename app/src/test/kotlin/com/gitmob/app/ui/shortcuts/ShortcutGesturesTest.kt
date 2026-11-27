package com.gitmob.app.ui.shortcuts

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.gitmob.app.R
import com.gitmob.app.data.model.LauncherShortcut
import com.gitmob.app.data.model.ShortcutKind
import com.gitmob.app.ui.common.ReorderableList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class ShortcutGesturesTest {
    @get:Rule val compose = createComposeRule()
    private var clicks = 0
    private var removals = 0
    private var commits = 0
    private var saved = emptyList<String>()

    private fun show(editing: Boolean = true, blocked: Boolean = false) {
        val items = listOf("A", "B", "C").map {
            LauncherShortcut(it, ShortcutKind.REPOSITORY, it, "owner/$it", "https://github.com/owner/$it", dynamic = true)
        }
        compose.setContent {
            MaterialTheme {
                ReorderableList(items, { it.id }, { true }, editing, blocked, Modifier.fillMaxSize().testTag("list"),
                    commit = { ids, done -> saved = ids; commits++; done(true) }, grouped = true) { item, modifier ->
                    ShortcutSwipeRow(!editing && !blocked, { removals++ }, modifier.testTag("row:" + item.id)) {
                        ShortcutRow(item, editing, !blocked, { clicks++ }, {})
                    }
                }
            }
        }
    }

    @Test fun tapInEditModeRenamesWithoutRemovingOrReordering() {
        show()
        compose.onNodeWithText("A").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(1, clicks)
        assertEquals(0, removals)
        assertEquals(0, commits)
    }

    @Test fun directLeftSwipeInNormalModeRequestsRemovalOnly() {
        show(editing = false)
        compose.onNodeWithTag("row:A").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, removals)
        assertEquals(0, clicks)
        assertEquals(0, commits)
    }

    @Test fun editModeDoesNotRemoveOnSwipe() {
        show()
        compose.onNodeWithTag("row:A").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, removals)
        assertEquals(0, commits)
    }

    @Test fun blockedNormalModeDoesNotRemoveOnSwipe() {
        show(editing = false, blocked = true)
        compose.onNodeWithTag("row:A").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, removals)
        assertEquals(0, clicks)
        assertEquals(0, commits)
    }

    @Test fun longPressWholeRowReordersWithoutRenameOrRemoval() {
        show()
        val a = compose.onNodeWithTag("row:A").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("row:B").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("list").performTouchInput {
            down(Offset(a.center.x - list.left, a.center.y - list.top))
            advanceEventTime(750)
        }
        compose.mainClock.advanceTimeBy(750)
        compose.waitForIdle()
        compose.onNodeWithTag("list").performTouchInput {
            repeat(12) { moveBy(Offset(0f, (b.center.y - a.center.y + 12f) / 12), delayMillis = 16) }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
        compose.onNodeWithTag("list").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf("B", "A", "C"), saved)
        assertEquals(1, commits)
        assertEquals(0, clicks)
        assertEquals(0, removals)
    }

    @Test fun accessibilityOffersRemovalOnlyInNormalMode() {
        show(editing = false)
        val context = RuntimeEnvironment.getApplication()
        val row = compose.onNodeWithTag("row:A")
        val config = row.fetchSemanticsNode().config
        assertEquals(context.getString(R.string.shortcuts_remove), config[SemanticsActions.Dismiss].label)
        assertTrue(config[SemanticsActions.CustomActions].isEmpty())
        row.performSemanticsAction(SemanticsActions.Dismiss) { it() }
        assertEquals(1, removals)
        assertEquals(0, commits)
        assertEquals(0, clicks)
    }

    @Test fun accessibilityOffersReorderOnlyInEditMode() {
        show()
        val context = RuntimeEnvironment.getApplication()
        val moveLabel = context.getString(R.string.library_move_after)
        val config = compose.onNodeWithTag("row:A").fetchSemanticsNode().config
        val actions = config[SemanticsActions.CustomActions]
        assertFalse(config.contains(SemanticsActions.Dismiss))
        assertTrue("Reordering actions are preserved", actions.any { it.label == moveLabel })
        compose.runOnIdle { actions.first { it.label == moveLabel }.action() }
        assertEquals(listOf("B", "A", "C"), saved)
        assertEquals(1, commits)
        assertEquals(0, removals)
        assertEquals(0, clicks)
    }
}
