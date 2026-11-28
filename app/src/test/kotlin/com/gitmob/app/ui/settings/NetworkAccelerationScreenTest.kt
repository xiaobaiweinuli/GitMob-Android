package com.gitmob.app.ui.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.preferences.*
import com.gitmob.app.data.repository.AccelerationProbeResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp")
class NetworkAccelerationScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val first = RawAccelerationService("a", "First", "https://one.example/")
    private val second = RawAccelerationService("b", "Second", "https://two.example/path/")
    private fun text(id: Int) = context.getString(id)

    @Test fun emptyScreenHasOnlyToolbarAddAndLightningAndNoProviders() {
        compose.setContent { MaterialTheme {
            NetworkAccelerationContent(NetworkAccelerationState(RawAccelerationSettings()), {}, {}, {}, {}, {}, { _, _, _, _ -> }, {})
        } }
        compose.onNodeWithTag("acceleration/test").assertIsNotEnabled()
        compose.onNodeWithTag("acceleration/add").assertIsEnabled()
        compose.onNodeWithText(text(R.string.acceleration_empty)).assertIsDisplayed()
        compose.onNodeWithTag("acceleration/enable").assertIsOff().performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithText(text(R.string.library_cancel)).performClick()
        compose.onNodeWithTag("acceleration/add").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
    }

    @Test fun toolbarAddDialogValidatesAndSavesWithoutStartingATest() {
        var saved: Pair<String, String>? = null
        var tested = false
        compose.setContent { MaterialTheme {
            NetworkAccelerationContent(NetworkAccelerationState(RawAccelerationSettings()), {}, {}, {}, {}, { tested = true },
                { _, name, address, done -> saved = name to address; done() }, {})
        } }
        compose.onNodeWithTag("acceleration/add").performClick()
        compose.onNodeWithText(text(R.string.library_save)).performClick()
        compose.onNodeWithText(text(R.string.acceleration_invalid_name)).assertExists()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("My proxy")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("http://proxy.example")
        compose.onNodeWithText(text(R.string.library_save)).performClick()
        assertNull(saved)
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("proxy.example/path")
        compose.onNodeWithText(text(R.string.library_save)).performClick()
        assertEquals("My proxy" to "proxy.example/path", saved)
        assertFalse(tested)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun responsesAreOnRightSortedByLatencyAndSelectionRemainsWhenDisabled() {
        val settings = RawAccelerationSettings(listOf(first, second), selectedId = "a", enabled = false)
        compose.setContent { MaterialTheme {
            NetworkAccelerationContent(NetworkAccelerationState(settings, results = mapOf("a" to AccelerationProbeResult.Success(240), "b" to AccelerationProbeResult.Success(35))),
                {}, {}, {}, {}, {}, { _, _, _, _ -> }, {})
        } }
        compose.onNodeWithTag("acceleration/service/a").assertIsSelected()
        compose.onNodeWithTag("acceleration/enable").assertIsOff()
        val a = compose.onNodeWithTag("acceleration/service/a").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("acceleration/service/b").fetchSemanticsNode().boundsInRoot
        assertTrue(b.top < a.top)
        val response = compose.onNodeWithText(context.getString(R.string.acceleration_millis, 35), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithText("Second", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(response.left > name.right)
        val bolt = compose.onNodeWithTag("acceleration/test").fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithTag("acceleration/add").fetchSemanticsNode().boundsInRoot
        assertTrue(bolt.left < add.left)
    }

    @Test fun testingUsesSpinnerWithoutVisibleTestingTextAndLightningCanStop() {
        var clicked = false
        compose.setContent { MaterialTheme {
            NetworkAccelerationContent(NetworkAccelerationState(RawAccelerationSettings(listOf(first)), pending = setOf("a"), testing = true),
                {}, {}, {}, {}, { clicked = true }, { _, _, _, _ -> }, {})
        } }
        compose.onNodeWithTag("acceleration/progress/a", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.acceleration_testing)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.acceleration_stop)).performClick()
        assertTrue(clicked)
    }

    @Test fun swipingAnotherRowClosesThePreviousRevealAndTapClosesWithoutSelecting() {
        var selected: String? = null
        compose.setContent { MaterialTheme {
            NetworkAccelerationContent(NetworkAccelerationState(RawAccelerationSettings(listOf(first, second))),
                {}, {}, {}, { selected = it }, {}, { _, _, _, _ -> }, {})
        } }
        compose.onNodeWithTag("acceleration/service/a").performTouchInput { swipeLeft() }
        compose.onAllNodesWithText(text(R.string.acceleration_delete)).assertCountEquals(1)
        compose.onNodeWithTag("acceleration/service/b").performTouchInput { swipeLeft() }
        compose.onAllNodesWithText(text(R.string.acceleration_delete)).assertCountEquals(1)
        compose.onNodeWithTag("acceleration/service/b").performClick()
        compose.onNodeWithText(text(R.string.acceleration_delete)).assertDoesNotExist()
        assertNull(selected)
        compose.onNodeWithTag("acceleration/service/b").performClick()
        assertEquals("b", selected)
    }

    @Test fun longPressEditsAndLeftSwipeRevealsConfirmedDelete() {
        var deleted: String? = null
        compose.setContent { MaterialTheme {
            NetworkAccelerationContent(NetworkAccelerationState(RawAccelerationSettings(listOf(first), "a", true)),
                {}, {}, {}, {}, {}, { _, _, _, _ -> }, { deleted = it })
        } }
        compose.onNodeWithTag("acceleration/service/a").performTouchInput { longClick() }
        compose.onNodeWithText(text(R.string.acceleration_edit_active)).assertExists()
        compose.onNodeWithText(text(R.string.library_cancel)).performClick()
        compose.onNodeWithText(text(R.string.acceleration_delete)).assertDoesNotExist()
        compose.onNodeWithTag("acceleration/service/a").performTouchInput { swipeLeft() }
        compose.onNodeWithText(text(R.string.acceleration_delete)).performClick()
        compose.onNodeWithText(text(R.string.acceleration_delete_active)).assertExists()
        assertNull(deleted)
        compose.onAllNodesWithText(text(R.string.acceleration_delete)).onLast().performClick()
        assertEquals("a", deleted)
    }
}
