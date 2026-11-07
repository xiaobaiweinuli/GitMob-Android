package com.gitmob.app.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class ImageZoomStateTest {
    private fun zoom(viewport: Size = Size(300f, 600f), image: Size = Size(600f, 300f)) =
        ImageZoomState().apply { resize(viewport, image) }

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, .001f)
        assertEquals(expected.y, actual.y, .001f)
    }

    @Test fun landscapeImageLocksLetterboxedAxisAndClampsHorizontalPan() {
        val state = zoom()
        state.transform(Offset(150f, 300f), Offset(1000f, 1000f), 2f)
        assertEquals(2f, state.scale, .001f)
        assertOffset(Offset(150f, 0f), state.offset)
    }

    @Test fun portraitImageLocksHorizontalAxisAndClampsVerticalPan() {
        val state = zoom(Size(600f, 300f), Size(300f, 600f))
        state.transform(Offset(300f, 150f), Offset(-1000f, -1000f), 2f)
        assertOffset(Offset(0f, -150f), state.offset)
    }

    @Test fun zoomKeepsTouchedPointInPlace() {
        val state = zoom(Size(400f, 400f), Size(400f, 400f))
        state.transform(Offset(250f, 250f), Offset.Zero, 2f)
        assertOffset(Offset(-50f, -50f), state.offset)
    }

    @Test fun scaleStaysWithinOneAndFiveAndFitRecenters() {
        val state = zoom()
        state.transform(Offset.Zero, Offset(1000f, 1000f), 100f)
        assertEquals(5f, state.scale, .001f)
        state.transform(Offset.Zero, Offset(1000f, 1000f), .001f)
        assertEquals(1f, state.scale, .001f)
        assertOffset(Offset.Zero, state.offset)
    }

    @Test fun doubleTapTogglesBetweenMagnifiedAndFit() {
        val state = zoom()
        state.doubleTap(Offset(90f, 50f))
        assertEquals(2.5f, state.scale, .001f)
        state.doubleTap(Offset(200f, 300f))
        assertEquals(1f, state.scale, .001f)
        assertOffset(Offset.Zero, state.offset)
    }

    @Test fun resizingReclampsPanAndInvalidZoomIsIgnored() {
        val state = zoom(Size(400f, 400f), Size(400f, 400f))
        state.transform(Offset(200f, 200f), Offset(999f, 999f), 2f)
        state.resize(Size(200f, 200f), Size(400f, 400f))
        assertOffset(Offset(100f, 100f), state.offset)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f).forEach {
            state.transform(Offset.Zero, Offset.Zero, it)
        }
        assertEquals(2f, state.scale, .001f)
        assertOffset(Offset(100f, 100f), state.offset)
        state.reset()
        assertOffset(Offset.Zero, state.offset)
    }
}
