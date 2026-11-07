package com.gitmob.app.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** Zoom is relative to the fitted image; letterboxed axes cannot be dragged offscreen. */
internal class ImageZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var viewport = Size.Zero
    private var image = Size.Zero

    fun resize(viewport: Size, image: Size) {
        this.viewport = viewport
        this.image = image
        offset = clamp(offset)
    }

    fun reset() { scale = 1f; offset = Offset.Zero }

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        if (!zoom.isFinite() || zoom <= 0f) return
        val previous = scale
        scale = (scale * zoom).coerceIn(1f, 5f)
        val anchor = centroid - Offset(viewport.width / 2, viewport.height / 2)
        offset = clamp(anchor - (anchor - offset) * (scale / previous) + pan)
    }

    fun doubleTap(point: Offset) {
        if (scale > 1.01f) reset() else transform(point, Offset.Zero, 2.5f)
    }

    private fun clamp(value: Offset): Offset {
        if (image.width <= 0 || image.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
        val fit = minOf(viewport.width / image.width, viewport.height / image.height)
        val x = ((image.width * fit * scale - viewport.width) / 2).coerceAtLeast(0f)
        val y = ((image.height * fit * scale - viewport.height) / 2).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
}
