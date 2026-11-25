package com.gitmob.app.ui.common

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.LocalView
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownBodyEditorTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `editor shows tabs and all markdown toolbar actions`() {
        composeRule.setContent {
            MarkdownBodyEditor(
                textState = SoraEditorState(""),
                state = MarkdownEditorUiState(),
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
                textState = SoraEditorState(""),
                state = MarkdownEditorUiState(selectedTab = selectedTab.value),
                onTabSelected = { selectedTab.value = it },
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.conversation_preview_tab)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.conversation_preview_empty)).assertIsDisplayed()
    }

    @Test
    fun `selected text is wrapped by markdown action`() {
        val result = SoraEditorState("hello", TextRange(0, 5))
        applyMarkdownEdit(result, MarkdownEditAction.BOLD)

        assertEquals("**hello**", result.text.toString())
        assertEquals(TextRange(2, 7), result.selection)
    }

    @Test
    fun `IME and toolbar edits preserve document selection and undo across preview`() {
        val textState = SoraEditorState()
        lateinit var root: View
        val selectedTab = mutableStateOf(MarkdownEditorTab.EDIT)
        composeRule.setContent {
            root = LocalView.current
            Box(Modifier.size(width = 360.dp, height = 700.dp)) {
                MarkdownBodyEditor(
                    textState = textState,
                    state = MarkdownEditorUiState(selectedTab = selectedTab.value),
                    onTabSelected = { selectedTab.value = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val editor = composeRule.awaitSoraEditor(root)
        composeRule.runOnIdle {
            editor.requestFocus()
            editor.onCreateInputConnection(EditorInfo())!!.commitText("hello", 1)
        }
        composeRule.runOnIdle { textState.edit { selection = TextRange(0, 5) } }
        composeRule.onNodeWithContentDescription(context.getString(R.string.conversation_tool_bold)).performClick()
        composeRule.runOnIdle {
            assertEquals("**hello**", textState.text.toString())
            assertEquals(TextRange(2, 7), textState.selection)
        }
        composeRule.onNodeWithText(context.getString(R.string.conversation_preview_tab)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.conversation_edit_tab)).performClick()
        val restored = composeRule.awaitSoraEditor(root, editor)
        composeRule.runOnIdle {
            assertEquals("**hello**", textState.text.toString())
            assertEquals(TextRange(2, 7), textState.selection)
            org.junit.Assert.assertSame(textState.content, restored.text)
            restored.undo()
            assertEquals("hello", textState.snapshot())
            restored.redo()
            assertEquals("**hello**", textState.snapshot())
        }
    }

    @Test
    fun `accessory is above toolbar and toolbar reaches editor bottom`() {
        composeRule.setContent {
            Box(Modifier.size(width = 360.dp, height = 700.dp)) {
                MarkdownBodyEditor(
                    textState = SoraEditorState("body"),
                    state = MarkdownEditorUiState(),
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
