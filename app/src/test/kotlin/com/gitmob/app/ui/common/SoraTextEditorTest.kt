package com.gitmob.app.ui.common

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Column
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoraTextEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mountingAndReconfiguringBodyPreservesFocusedTitleAndRestoredSelection() {
        val mounted = mutableStateOf(false)
        val wrap = mutableStateOf(false)
        val focus = FocusRequester()
        val state = SoraEditorState("body text", TextRange(2, 6))
        lateinit var controller: SoraEditorController
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    OutlinedTextField("title", {}, modifier = Modifier.testTag("title").focusRequester(focus))
                    controller = rememberSoraEditorController(state)
                    if (mounted.value) SoraTextEditor(state, Modifier.weight(1f), wrapLines = wrap.value, controller = controller)
                }
            }
        }
        compose.runOnIdle { focus.requestFocus(); mounted.value = true }
        val editor = compose.awaitSoraEditor(root)
        compose.onNodeWithTag("title").assertIsFocused()
        compose.runOnIdle {
            assertFalse(editor.isFocused)
            assertEquals(2, editor.cursor.left)
            assertEquals(6, editor.cursor.right)
            wrap.value = true
        }
        compose.awaitSoraLayout(editor)
        compose.onNodeWithTag("title").assertIsFocused()
        compose.runOnIdle {
            editor.requestFocus()
            editor.onCreateInputConnection(EditorInfo())!!.commitText("new", 1)
            assertEquals("bonewext", state.snapshot())
            assertTrue(editor.isFocused)
            focus.requestFocus()
        }
        compose.onNodeWithTag("title").assertIsFocused()
        compose.runOnIdle {
            controller.edit { applyMarkdownEdit(it, MarkdownEditAction.BOLD) }
            assertTrue("Explicit toolbar edits focus the body", editor.isFocused)
        }
    }

    @Test fun largeDocumentEditsAtEndAndWrapChangesKeepTheSameDocumentAndHistory() {
        val original = "line\n".repeat(30_000) + "END"
        val state = SoraEditorState(original)
        val wrap = mutableStateOf(false)
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            MaterialTheme { Box(Modifier.size(360.dp, 600.dp)) {
                SoraTextEditor(state, Modifier.fillMaxSize(), wrapLines = wrap.value)
            } }
        }
        val editor = compose.awaitSoraEditor(root)
        compose.runOnIdle {
            assertSame(state.content, editor.text)
            assertTrue(editor.isLineNumberEnabled)
            assertTrue(editor.height in 1..2000)
            editor.setSelection(30_000, 3)
            editor.requestFocus()
            editor.onCreateInputConnection(EditorInfo())!!.commitText("!", 1)
            assertEquals(original + "!", state.snapshot())
            wrap.value = true
        }
        compose.awaitSoraLayout(editor)
        compose.runOnIdle {
            assertSame(state.content, editor.text)
            assertEquals(TextRange(original.length + 1), state.selection)
            editor.undo()
            assertEquals(original, state.snapshot())
        }
    }

    @Test fun chineseCompositionSelectionReplacementAndDisabledInputUseSora() {
        val state = SoraEditorState("hello", TextRange(0, 5))
        val enabled = mutableStateOf(true)
        lateinit var controller: SoraEditorController
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            controller = rememberSoraEditorController(state)
            Box(Modifier.size(360.dp, 600.dp)) { SoraTextEditor(state, enabled = enabled.value, showLineNumbers = false, controller = controller) }
        }
        val editor = compose.awaitSoraEditor(root)
        compose.runOnIdle {
            editor.requestFocus()
            val connection = editor.onCreateInputConnection(EditorInfo())!!
            connection.setComposingText("ni", 1)
            connection.setComposingText("你", 1)
            connection.commitText("你好😀", 1)
            connection.finishComposingText()
            assertEquals("你好😀", state.snapshot())
            assertFalse(editor.isLineNumberEnabled)
            enabled.value = false
        }
        compose.runOnIdle {
            assertFalse(editor.isEditable)
            editor.onCreateInputConnection(EditorInfo())?.commitText("ignored", 1)
            controller.edit { applyMarkdownEdit(it, MarkdownEditAction.BOLD) }
            controller.undo()
            controller.redo()
            assertEquals("你好😀", state.snapshot())
        }
    }

    @Test fun minifiedSingleLineCanBeEditedAndWrappedWithoutReplacingTheDocument() {
        val state = SoraEditorState("x".repeat(100_000))
        val wrap = mutableStateOf(true)
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            Box(Modifier.size(360.dp, 600.dp)) { SoraTextEditor(state, wrapLines = wrap.value) }
        }
        val editor = compose.awaitSoraEditor(root)
        compose.runOnIdle {
            editor.setSelection(0, 100_000)
            editor.commitText("END")
            assertEquals(100_003, state.content.length)
            wrap.value = false
        }
        compose.awaitSoraLayout(editor)
        compose.runOnIdle {
            assertSame(state.content, editor.text)
            assertTrue(state.snapshot().endsWith("END"))
            assertTrue(editor.scrollMaxX > editor.width)
        }
    }
}
