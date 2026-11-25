package com.gitmob.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gitmob.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListFeedbackTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `feedback transitions keep list composed and retry explicit`() {
        var status by mutableStateOf(ListFeedbackState.LOADING)
        var compositions = 0
        var disposals = 0
        var retries = 0
        composeRule.setContent {
            MaterialExpressiveTheme {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { DisposableEffect(Unit) { compositions++; onDispose { disposals++ } } }
                    }
                    ListFeedback(status, { retries++ }, emptyMessage = "No matching entries")
                }
            }
        }
        composeRule.runOnIdle { status = ListFeedbackState.ERROR }
        composeRule.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.common_retry)).performClick()
        composeRule.runOnIdle { status = ListFeedbackState.EMPTY }
        composeRule.onNodeWithText("No matching entries").assertExists()
        composeRule.runOnIdle { status = ListFeedbackState.CONTENT }
        composeRule.onNodeWithText("No matching entries").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, compositions); assertEquals(0, disposals); assertEquals(1, retries) }
    }
}
