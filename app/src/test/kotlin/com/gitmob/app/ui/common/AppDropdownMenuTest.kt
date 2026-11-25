package com.gitmob.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppDropdownMenuTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `action menu scrolls to last option and dispatches once`() {
        var calls = 0
        composeRule.setContent {
            MaterialExpressiveTheme {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    Button(onClick = { expanded = true }) { Text("Open actions") }
                    AppDropdownMenu(expanded, { expanded = false }) {
                        AppDropdownMenuItem(text = { Text("Unavailable") }, onClick = { calls++ }, enabled = false)
                        repeat(30) { index ->
                            AppDropdownMenuItem(text = { Text("Action $index") }, onClick = { calls++; expanded = false })
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText("Open actions").performClick()
        composeRule.onNodeWithText("Unavailable").assertIsNotEnabled()
        composeRule.onNodeWithText("Action 29").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNode(isPopup()).assertDoesNotExist()
        assertEquals(1, calls)
    }
}
