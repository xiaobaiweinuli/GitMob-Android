package com.gitmob.app.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

internal fun findSoraEditor(view: View): SoraEditingView? {
    if (view is SoraEditingView) return view
    if (view is ViewGroup) for (index in 0 until view.childCount) {
        findSoraEditor(view.getChildAt(index))?.let { return it }
    }
    return null
}

internal fun ComposeTestRule.awaitSoraEditor(root: View, previous: SoraEditingView? = null): SoraEditingView {
    waitUntil(10_000) { runOnIdle { findSoraEditor(root)?.let { it !== previous } == true } }
    return requireNotNull(findSoraEditor(root)).also { awaitSoraLayout(it) }
}

internal fun ComposeTestRule.awaitSoraLayout(editor: io.github.rosemoe.sora.widget.CodeEditor) {
    waitUntil(15_000) {
        runOnIdle {
            shadowOf(Looper.getMainLooper()).idle()
            if (!ReflectionHelpers.getField<Boolean>(editor, "layoutBusy") && editor.width > 0 && editor.height > 0) {
                val bitmap = Bitmap.createBitmap(editor.width, editor.height, Bitmap.Config.ARGB_8888)
                try { editor.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
                shadowOf(Looper.getMainLooper()).idle()
                // Drawing may start another wrapped layout when the gutter width settles.
                // Await that layout and its reading-position restore before interacting.
                val pendingField = if (editor is SoraViewerView) "pendingPosition" else "pendingViewport"
                val embedded = if (editor is SoraViewerView) editor.embeddedState else (editor as SoraEditingView).embeddedState
                !ReflectionHelpers.getField<Boolean>(editor, "layoutBusy") &&
                    ReflectionHelpers.getField<Any?>(editor, pendingField) == null &&
                    (embedded == null || ReflectionHelpers.getField<Any?>(embedded, "pendingScroll") == null)
            } else false
        }
    }
}

internal fun findSoraViewer(view: View): SoraViewerView? {
    if (view is SoraViewerView) return view
    if (view is ViewGroup) for (index in 0 until view.childCount) {
        findSoraViewer(view.getChildAt(index))?.let { return it }
    }
    return null
}

internal fun ComposeTestRule.awaitSoraViewer(root: View, previous: SoraViewerView? = null): SoraViewerView {
    waitUntil(15_000) { runOnIdle { findSoraViewer(root)?.let { it !== previous && !it.isReleased } == true } }
    return requireNotNull(findSoraViewer(root)).also { awaitSoraLayout(it) }
}
