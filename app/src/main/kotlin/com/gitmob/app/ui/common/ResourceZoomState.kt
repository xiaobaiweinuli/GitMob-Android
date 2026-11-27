package com.gitmob.app.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.gitmob.app.R
import kotlin.math.roundToInt

/** Zoom is relative to the fitted content; letterboxed axes cannot be dragged offscreen. */
internal class ResourceZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var viewport = Size.Zero
    private var content = Size.Zero

    fun resize(viewport: Size, content: Size) {
        this.viewport = viewport
        this.content = content
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

    fun zoomBy(factor: Float) { transform(Offset(viewport.width / 2, viewport.height / 2), Offset.Zero, factor) }

    fun doubleTap(point: Offset) {
        if (scale > 1.01f) reset() else transform(point, Offset.Zero, 2.5f)
    }

    private fun clamp(value: Offset): Offset {
        if (content.width <= 0 || content.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
        val fit = minOf(viewport.width / content.width, viewport.height / content.height)
        val x = ((content.width * fit * scale - viewport.width) / 2).coerceAtLeast(0f)
        val y = ((content.height * fit * scale - viewport.height) / 2).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
}

@Composable
internal fun Modifier.resourceGestures(
    zoom: ResourceZoomState,
    onTap: (() -> Unit)? = null,
    onDoubleTap: (Offset) -> Unit = zoom::doubleTap,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
): Modifier {
    val currentTap by rememberUpdatedState(onTap)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val transform = rememberTransformableState { centroid, factor, pan, _ -> zoom.transform(centroid, pan, factor) }
    val zoomInLabel = stringResource(R.string.image_zoom_in)
    val zoomOutLabel = stringResource(R.string.image_zoom_out)
    val resetLabel = stringResource(R.string.image_fit_screen)
    val description = stringResource(R.string.image_zoom_percent, (zoom.scale * 100).roundToInt())
    return pointerInput(zoom) {
        detectTapGestures(onTap = { currentTap?.invoke() }, onDoubleTap = { currentDoubleTap(it) })
    }.transformable(transform, canPan = { zoom.scale > 1.01f })
        .semantics {
            stateDescription = description
            if (onTap != null) onClick { currentTap?.invoke(); true }
            customActions = accessibilityActions + listOf(
                CustomAccessibilityAction(zoomInLabel) { zoom.zoomBy(1.5f); true },
                CustomAccessibilityAction(zoomOutLabel) { zoom.zoomBy(1 / 1.5f); true },
                CustomAccessibilityAction(resetLabel) { zoom.reset(); true },
            )
        }
}

internal fun Modifier.resourceTransform(zoom: ResourceZoomState?): Modifier = graphicsLayer {
    scaleX = zoom?.scale ?: 1f
    scaleY = zoom?.scale ?: 1f
    translationX = zoom?.offset?.x ?: 0f
    translationY = zoom?.offset?.y ?: 0f
}
