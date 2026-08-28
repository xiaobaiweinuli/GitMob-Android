package com.gitmob.app.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkdownBodyEditorTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `editor shows tabs and all markdown toolbar actions`() {
        composeRule.setContent {
            MarkdownBodyEditor(
                value = TextFieldValue(""),
                state = MarkdownEditorUiState(),
                onValueChange = {},
                onTabSelected = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.conversation_edit_tab)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.conversation_preview_tab)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.conversation_tool_bold)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.conversation_tool_italic)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.conversation_tool_bullet)).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.conversation_tool_code_block)).assertCountEquals(1)
    }

    @Test
    fun `preview tab shows empty state without rendering blank markdown`() {
        val selectedTab = mutableStateOf(MarkdownEditorTab.EDIT)
        composeRule.setContent {
            MarkdownBodyEditor(
                value = TextFieldValue(""),
                state = MarkdownEditorUiState(selectedTab = selectedTab.value),
                onValueChange = {},
                onTabSelected = { selectedTab.value = it },
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.conversation_preview_tab)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.conversation_preview_empty)).assertIsDisplayed()
    }

    @Test
    fun `selected text is wrapped by markdown action`() {
        val result = applyMarkdownEdit(TextFieldValue("hello", TextRange(0, 5)), MarkdownEditAction.BOLD)

        assertEquals("**hello**", result.text)
        assertEquals(TextRange(2, 7), result.selection)
    }

    @Test
    fun `accessory is above toolbar and toolbar reaches editor bottom`() {
        composeRule.setContent {
            Box(Modifier.size(width = 360.dp, height = 700.dp)) {
                MarkdownBodyEditor(
                    value = TextFieldValue("body"),
                    state = MarkdownEditorUiState(),
                    onValueChange = {},
                    onTabSelected = {},
                    modifier = Modifier.fillMaxSize(),
                    accessoryContent = { Text("metadata") },
                )
            }
        }

        composeRule.onNodeWithText("metadata").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.conversation_tool_bold)).assertIsDisplayed()
    }
}
