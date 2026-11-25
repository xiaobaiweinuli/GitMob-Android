package com.gitmob.app.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.text.TextRange
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener

/** The editable document survives View recreation; no Android View or input connection is retained. */
class SoraEditorState(initialText: String = "", initialSelection: TextRange = TextRange.Zero) {
    internal val content = Content(initialText)
    var revision by mutableIntStateOf(0)
        private set
    var selection by mutableStateOf(initialSelection)
        private set
    internal var selectionRequest by mutableIntStateOf(0)
        private set
    internal var viewport = SoraEditorViewport()
    private var snapshotRevision = -1
    private var cachedSnapshot = ""

    val text: CharSequence get() { revision; return content }
    val canUndo: Boolean get() { revision; return content.canUndo() }
    val canRedo: Boolean get() { revision; return content.canRedo() }

    init {
        moveCursor(initialSelection)
        content.addContentListener(object : ContentListener {
            override fun beforeReplace(content: Content) = Unit
            override fun afterInsert(content: Content, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, insertedContent: CharSequence) = changed()
            override fun afterDelete(content: Content, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, deletedContent: CharSequence) = changed()
        })
    }

    private fun changed() {
        revision++
        recordSelection(TextRange(content.cursor.left, content.cursor.right))
    }

    /** Materialize the payload only for preview, persistence or submission. */
    fun snapshot(): String {
        if (snapshotRevision != revision) {
            cachedSnapshot = content.toString()
            snapshotRevision = revision
        }
        return cachedSnapshot
    }

    fun matches(value: String): Boolean {
        revision
        return content.length == value.length && value.indices.all { content[it] == value[it] }
    }

    internal fun recordSelection(value: TextRange) { selection = value }

    private fun moveCursor(value: TextRange) {
        val start = content.indexer.getCharPosition(value.min.coerceIn(0, content.length))
        val end = content.indexer.getCharPosition(value.max.coerceIn(0, content.length))
        content.cursor.setLeft(start.line, start.column)
        content.cursor.setRight(end.line, end.column)
        selection = TextRange(value.start.coerceIn(0, content.length), value.end.coerceIn(0, content.length))
    }

    fun edit(block: EditBuffer.() -> Unit) {
        content.beginBatchEdit()
        try {
            val buffer = EditBuffer(content, selection)
            buffer.block()
            moveCursor(buffer.selection)
        } finally {
            content.endBatchEdit()
            selectionRequest++
            revision++
        }
    }

    fun loadText(value: String) {
        content.isUndoEnabled = false
        try { setTextAndPlaceCursorAtEnd(value) } finally { content.isUndoEnabled = true }
        viewport = SoraEditorViewport()
    }

    fun setTextAndPlaceCursorAtEnd(value: String) = edit {
        replace(0, length, value)
        selection = TextRange(length)
    }

    fun undo() {
        val range = content.undo()
        moveCursor(if (range == null) TextRange(content.cursor.left, content.cursor.right) else TextRange(range.start.index, range.end.index))
        selectionRequest++
        revision++
    }

    fun redo() {
        content.redo()
        moveCursor(TextRange(content.cursor.left, content.cursor.right))
        selectionRequest++
        revision++
    }

    class EditBuffer internal constructor(private val content: Content, initialSelection: TextRange) {
        val length: Int get() = content.length
        var selection: TextRange = initialSelection
        fun asCharSequence(): CharSequence = content
        fun replace(start: Int, end: Int, value: String) {
            content.replace(start, end, value)
            selection = TextRange(content.cursor.left, content.cursor.right)
        }
    }

    companion object {
        val Saver = listSaver<SoraEditorState, Any>(
            save = { listOf(it.snapshot(), it.selection.start, it.selection.end,
                it.viewport.line, it.viewport.column, it.viewport.rowFraction, it.viewport.horizontalDp) },
            restore = { saved ->
                SoraEditorState(saved[0] as String, TextRange(saved[1] as Int, saved[2] as Int)).also {
                    // Older TextFieldState drafts contain the same leading text and selection fields.
                    if (saved.size == 7) it.viewport = SoraEditorViewport(saved[3] as Int, saved[4] as Int, saved[5] as Float, saved[6] as Float)
                }
            },
        )
    }
}

internal data class SoraEditorViewport(
    val line: Int = 0,
    val column: Int = 0,
    val rowFraction: Float = 0f,
    val horizontalDp: Float = 0f,
)
