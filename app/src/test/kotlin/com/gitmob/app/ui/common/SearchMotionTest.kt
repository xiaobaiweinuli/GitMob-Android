package com.gitmob.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchMotionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `reopening search preserves draft and restores focus`() {
        var submissions = 0
        composeRule.setContent {
            MaterialExpressiveTheme {
                var expanded by remember { mutableStateOf(true) }
                var input by remember { mutableStateOf(TextFieldValue()) }
                Column {
                    Button(onClick = { expanded = !expanded }) { Text("Toggle search") }
                    SearchTitle(expanded) { visible ->
                        if (visible) RepositorySearchField(input, { input = it }, "Search repositories", false,
                            onSubmit = { submissions++ }, focusOnExpand = expanded)
                        else Text("Repositories")
                    }
                }
            }
        }
        composeRule.onNodeWithText("Search repositories").performTextInput("material")
        composeRule.onNodeWithText("Toggle search").performClick()
        composeRule.onNodeWithText("Repositories").assertExists()
        composeRule.onNodeWithText("material").assertDoesNotExist()
        composeRule.onNodeWithText("Toggle search").performClick()
        composeRule.onNodeWithText("material").assertIsFocused()
        assertEquals(0, submissions)
    }
}
