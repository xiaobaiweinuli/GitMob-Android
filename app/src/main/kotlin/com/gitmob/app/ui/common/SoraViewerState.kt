package com.gitmob.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** Logical coordinates survive wrapping and viewport changes without saving the source payload. */
internal data class SourceReadingPosition(
    val line: Int = 0,
    val column: Int = 0,
    val rowFraction: Float = 0f,
    val horizontalDp: Float = 0f,
    val selectionStartLine: Int = 0,
    val selectionStartColumn: Int = 0,
    val selectionEndLine: Int = 0,
    val selectionEndColumn: Int = 0,
)

internal class SoraViewerState(
    val documentId: String,
    internal var position: SourceReadingPosition = SourceReadingPosition(),
) {
    // Bound only while the UI owns a live editor; captures the current frame of an ongoing fling.
    internal var readPosition: (() -> SourceReadingPosition)? = null
    internal fun snapshot(): SourceReadingPosition = readPosition?.invoke() ?: position

    companion object {
        fun saver(documentId: String) = listSaver<SoraViewerState, Any>(
            save = { state ->
                val p = state.snapshot()
                listOf(state.documentId, p.line, p.column, p.rowFraction, p.horizontalDp,
                    p.selectionStartLine, p.selectionStartColumn, p.selectionEndLine, p.selectionEndColumn)
            },
            restore = { saved ->
                if (saved[0] != documentId) SoraViewerState(documentId)
                else SoraViewerState(documentId, SourceReadingPosition(
                    saved[1] as Int, saved[2] as Int, saved[3] as Float, saved[4] as Float,
                    saved[5] as Int, saved[6] as Int, saved[7] as Int, saved[8] as Int,
                ))
            },
        )
    }
}

@Composable
internal fun rememberSoraViewerState(documentId: String): SoraViewerState =
    rememberSaveable(documentId, saver = SoraViewerState.saver(documentId)) { SoraViewerState(documentId) }
