package com.gitmob.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** Scroll coordinates only; WebView and the HTML document never enter saved instance state. */
class MarkdownViewportState internal constructor(
    internal val documentId: String,
    internal var xDp: Float = 0f,
    internal var yDp: Float = 0f,
) {
    internal companion object {
        fun saver(documentId: String) = listSaver<MarkdownViewportState, Any>(
            save = { listOf(it.documentId, it.xDp, it.yDp) },
            restore = {
                if (it[0] == documentId) MarkdownViewportState(documentId, it[1] as Float, it[2] as Float)
                else MarkdownViewportState(documentId)
            },
        )
    }
}

@Composable
fun rememberMarkdownViewportState(documentId: String): MarkdownViewportState =
    rememberSaveable(documentId, saver = MarkdownViewportState.saver(documentId)) { MarkdownViewportState(documentId) }
