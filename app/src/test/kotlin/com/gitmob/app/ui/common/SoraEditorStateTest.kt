package com.gitmob.app.ui.common

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Test

class SoraEditorStateTest {
    @Test fun nativeChangesAreImmediatelyVisibleWithoutStringSynchronization() {
        val original = "α\r\n中文😀\rfinal\n"
        val state = SoraEditorState(original)
        val document = state.content
        val previous = state.revision
        document.insert(1, 2, "追加")
        assertTrue(state.revision > previous)
        assertEquals("α\r\n中文追加😀\rfinal\n", state.snapshot())
        assertSame(document, state.content)
        state.undo()
        assertEquals(original, state.snapshot())
        state.redo()
        assertEquals("α\r\n中文追加😀\rfinal\n", state.snapshot())
    }

    @Test fun markdownReplacementIsOneUndoStepAndKeepsSelection() {
        val state = SoraEditorState("hello world", TextRange(6, 11))
        applyMarkdownEdit(state, MarkdownEditAction.BOLD)
        assertEquals("hello **world**", state.snapshot())
        assertEquals(TextRange(8, 13), state.selection)
        state.undo()
        assertEquals("hello world", state.snapshot())
        assertFalse(state.canUndo)
        state.redo()
        assertEquals("hello **world**", state.snapshot())
    }

    @Test fun savedDraftRestoresContentSelectionAndViewportWithoutAView() {
        val state = SoraEditorState("line 1\nline 2", TextRange(1, 10))
        state.viewport = SoraEditorViewport(1, 2, .5f, 24f)
        val saved = with(SoraEditorState.Saver) { SaverScope { true }.save(state) }
        val restored = SoraEditorState.Saver.restore(requireNotNull(saved))!!
        assertEquals(state.snapshot(), restored.snapshot())
        assertEquals(state.selection, restored.selection)
        assertEquals(state.viewport, restored.viewport)
        assertNotSame(state.content, restored.content)
        assertFalse(restored.canUndo)
    }

    @Test fun oldTextFieldDraftAndIntentionallyEmptyDraftRemainRestorable() {
        val oldState = TextFieldState("draft", TextRange(1, 4))
        val saved = with(TextFieldState.Saver) { SaverScope { true }.save(oldState) }
        val legacy = SoraEditorState.Saver.restore(requireNotNull(saved))!!
        assertEquals("draft", legacy.snapshot())
        assertEquals(TextRange(1, 4), legacy.selection)
        val empty = SoraEditorState.Saver.restore(listOf("", 0, 0))!!
        assertEquals("", empty.snapshot())
    }

    @Test fun loadingInitialBodyDoesNotBecomeUndoable() {
        val state = SoraEditorState()
        state.loadText("remote body")
        assertFalse(state.canUndo)
        state.edit { replace(length, length, "!") }
        state.undo()
        assertEquals("remote body", state.snapshot())
        assertFalse(state.canUndo)
    }
}
