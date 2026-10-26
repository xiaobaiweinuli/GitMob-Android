package com.gitmob.app.ui.settings

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsDropdownTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var popupRoot: View? = null
    private var pageBackCount = 0
    private var popupComposed = false

    @Test
    fun `opening options does not move following page content`() {
        setMenuContent()
        val before = composeRule.onNodeWithText("Following section").fetchSemanticsNode().boundsInRoot

        openMenu()

        composeRule.onNode(isPopup()).assertIsDisplayed()
        composeRule.onNodeWithText("Description 0").assertIsDisplayed()
        composeRule.onNodeWithText("Option 0").assertIsSelected()
        composeRule.onNodeWithText("Option 1").assertIsNotSelected()
        assertEquals(before, composeRule.onNodeWithText("Following section").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun `choosing another option closes menu and invokes callback once`() {
        val selections = mutableListOf<Int>()
        setMenuContent(onSelected = selections::add)
        openMenu()

        composeRule.onNodeWithText("Option 1").performClick()

        composeRule.onNode(isPopup()).assertDoesNotExist()
        composeRule.onNodeWithText("Current 1").assertIsDisplayed()
        assertEquals(listOf(1), selections)
        openMenu()
        composeRule.onNodeWithText("Option 1").assertIsSelected()
    }

    @Test
    fun `choosing current option also closes menu`() {
        val selections = mutableListOf<Int>()
        setMenuContent(onSelected = selections::add)
        openMenu()

        composeRule.onNodeWithText("Option 0").performClick()

        composeRule.onNode(isPopup()).assertDoesNotExist()
        assertEquals(listOf(0), selections)
    }

    @Test
    fun `back dismisses popup without leaving page or saving a value`() {
        val selections = mutableListOf<Int>()
        setMenuContent(onSelected = selections::add)
        openMenu()

        composeRule.runOnIdle {
            val root = requireNotNull(popupRoot)
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }

        composeRule.onNode(isPopup()).assertDoesNotExist()
        composeRule.onNodeWithText("Following section").assertIsDisplayed()
        assertEquals(0, pageBackCount)
        assertEquals(emptyList<Int>(), selections)
    }

    @Test
    fun `outside touch dismisses without selecting and menu can reopen`() {
        val selections = mutableListOf<Int>()
        setMenuContent(onSelected = selections::add)
        openMenu()

        composeRule.runOnIdle {
            val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try {
                requireNotNull(popupRoot).onTouchEvent(event)
            } finally {
                event.recycle()
            }
        }

        composeRule.onNode(isPopup()).assertDoesNotExist()
        assertEquals(emptyList<Int>(), selections)
        openMenu()
        composeRule.onNodeWithText("Option 0").assertIsSelected()
    }

    @Test
    fun `long option list scrolls to its last value`() {
        val selections = mutableListOf<Int>()
        setMenuContent(optionCount = 9, onSelected = selections::add)
        openMenu()

        composeRule.onNodeWithText("Option 8").performScrollTo().assertIsDisplayed().performClick()

        composeRule.onNode(isPopup()).assertDoesNotExist()
        assertEquals(listOf(8), selections)
    }

    @Test
    fun `closed callback waits for popup removal and fires once`() {
        var closedCount = 0
        setMenuContent(onClosed = {
            assertFalse(popupComposed)
            closedCount++
        })
        openMenu()
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithText("Option 1").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNode(isPopup()).assertExists()
        assertEquals(0, closedCount)

        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNode(isPopup()).assertDoesNotExist()
        assertEquals(1, closedCount)
        composeRule.mainClock.advanceTimeBy(1000)
        assertEquals(1, closedCount)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun `reopening while closing is ignored but reopening after removal works`() {
        var closedCount = 0
        setMenuContent(onClosed = { closedCount++ })
        openMenu()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Option 1").performClick()
        composeRule.mainClock.advanceTimeByFrame()

        openMenu()
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNode(isPopup()).assertDoesNotExist()
        assertEquals(1, closedCount)

        composeRule.mainClock.autoAdvance = true
        openMenu()
        composeRule.onNode(isPopup()).assertIsDisplayed()
        composeRule.onNodeWithText("Option 1").assertIsSelected()
    }

    @Test
    fun `removing parent during closing does not emit closed callback`() {
        val visible = mutableStateOf(true)
        var closedCount = 0
        setMenuContent(visible = visible, onClosed = { closedCount++ })
        openMenu()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Option 1").performClick()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.runOnIdle { visible.value = false }
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.onNode(isPopup()).assertDoesNotExist()
        assertEquals(0, closedCount)
        composeRule.mainClock.autoAdvance = true
    }

    private fun openMenu() {
        composeRule.onNodeWithText("Selection").performClick()
        composeRule.waitForIdle()
    }

    private fun setMenuContent(
        optionCount: Int = 3,
        onSelected: (Int) -> Unit = {},
        onClosed: () -> Unit = {},
        visible: State<Boolean>? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                BackHandler { pageBackCount++ }
                var selected by remember { mutableStateOf(0) }
                if (visible?.value != false) Column {
                    SettingsDropdownRow(
                        title = "Selection",
                        currentValue = "Current $selected",
                        onClosed = onClosed,
                    ) { dismiss ->
                        DisposableEffect(Unit) {
                            popupComposed = true
                            onDispose { popupComposed = false }
                        }
                        val root = LocalView.current.rootView
                        SideEffect { popupRoot = root }
                        repeat(optionCount) { index ->
                            SelectionRow(
                                title = "Option $index",
                                description = "Description $index",
                                selected = selected == index,
                                onClick = {
                                    dismiss()
                                    selected = index
                                    onSelected(index)
                                },
                            )
                        }
                    }
                    Text("Following section")
                }
            }
        }
    }
}
