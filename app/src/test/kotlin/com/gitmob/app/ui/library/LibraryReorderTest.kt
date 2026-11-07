package com.gitmob.app.ui.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryReorderTest {
    @get:Rule val compose = createComposeRule()
    private var saved: List<String>? = null
    private var commits = 0
    private var longPresses = 0

    private fun showList(saveSucceeds: Boolean = true) {
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { longPresses++ }
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                    LibraryReorderList(
                        listOf("default", "A", "B", "C"), { it }, { it != "default" }, true, false,
                        Modifier.testTag("groups"), { ids, done -> saved = ids; commits++; done(saveSucceeds) },
                    ) { item, modifier -> Text(item, modifier.fillMaxWidth().height(80.dp)) }
                }
            }
        }
    }

    private fun startDrag(label: String = "A") {
        val item = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("groups").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("groups").performTouchInput {
            down(Offset(item.center.x - root.left, item.center.y - root.top))
            advanceEventTime(750)
        }
        compose.mainClock.advanceTimeBy(750)
        compose.waitForIdle()
    }

    private fun moveBy(y: Float, steps: Int = 12) {
        compose.onNodeWithTag("groups").performTouchInput {
            repeat(steps) { moveBy(Offset(0f, y / steps), delayMillis = 16) }
        }
        // Deliver pointer/layout work before advancing the placement animation clock.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
    }

    private fun endDrag(cancelled: Boolean = false) {
        compose.onNodeWithTag("groups").performTouchInput { if (cancelled) cancel() else up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun wholeRowReordersBeforeReleaseAndCommitsOnce() {
        showList()
        val a = compose.onNodeWithText("A").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText("B").fetchSemanticsNode().boundsInRoot
        startDrag()
        assertEquals("Whole row long press starts dragging", 1, longPresses)
        moveBy(b.center.y - a.center.y + 12f)
        assertEquals(0, commits)
        val movedB = compose.onNodeWithText("B").fetchSemanticsNode().boundsInRoot
        assertTrue("B must make room before A is released", movedB.top < b.top)
        endDrag()
        assertEquals(1, commits)
        assertEquals(listOf("default", "B", "A", "C"), saved)
    }

    @Test fun fastMoveCanCrossMultipleRows() {
        showList()
        val a = compose.onNodeWithText("A").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithText("C").fetchSemanticsNode().boundsInRoot
        startDrag()
        moveBy(c.center.y - a.center.y + 24f, steps = 1)
        assertTrue("A=${compose.onNodeWithText("A").fetchSemanticsNode().boundsInRoot}, C=${compose.onNodeWithText("C").fetchSemanticsNode().boundsInRoot}, original=$c, longPresses=$longPresses", compose.onNodeWithText("C").fetchSemanticsNode().boundsInRoot.top < c.top)
        endDrag()
        assertEquals(listOf("default", "B", "C", "A"), saved)
        assertEquals(1, commits)
    }

    @Test fun cancellationRestoresOrderWithoutSaving() {
        showList()
        val b = compose.onNodeWithText("B").fetchSemanticsNode().boundsInRoot
        startDrag()
        moveBy(104f)
        endDrag(cancelled = true)
        assertEquals(0, commits)
        assertEquals(b.top, compose.onNodeWithText("B").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun unchangedDropDoesNotSaveAndDefaultCannotDrag() {
        showList()
        startDrag()
        endDrag()
        assertEquals(0, commits)
        startDrag("default")
        moveBy(180f)
        endDrag()
        assertEquals(1, longPresses)
        assertEquals(0, commits)
    }

    @Test fun failedSaveRestoresSourceOrder() {
        showList(saveSucceeds = false)
        val b = compose.onNodeWithText("B").fetchSemanticsNode().boundsInRoot
        startDrag()
        moveBy(104f)
        endDrag()
        assertEquals(1, commits)
        assertEquals(b.top, compose.onNodeWithText("B").fetchSemanticsNode().boundsInRoot.top, 1f)
    }
}
