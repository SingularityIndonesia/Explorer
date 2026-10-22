package com.singularity_universe.explorer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/** Zoom and pan of one viewer page. The content is scaled around its center, then translated. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    internal var size = IntSize.Zero

    val isZoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** Applies a pinch/pan, keeping the content under [centroid] in place. */
    internal fun transform(zoom: Float, pan: Offset, centroid: Offset) {
        val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
        val focus = centroid - Offset(size.width / 2f, size.height / 2f)
        val newOffset = (offset - focus) * (newScale / scale) + focus + pan
        scale = newScale
        offset = clamp(newOffset)
        if (!isZoomed) reset()
    }

    /** Double-tap: zoom in around [at], or back out if already zoomed. */
    internal fun toggle(at: Offset) {
        if (isZoomed) reset() else transform(DOUBLE_TAP_SCALE, Offset.Zero, at)
    }

    /** Keeps the scaled content covering the page, so it can't be dragged off-screen. */
    private fun clamp(value: Offset): Offset {
        val maxX = size.width * (scale - 1) / 2
        val maxY = size.height * (scale - 1) / 2
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    private companion object {
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

/**
 * Pinch to zoom, drag to pan while zoomed, double-tap to zoom in/out, single tap for [onTap].
 * While not zoomed, one-finger drags are left alone so the pager can still swipe.
 * [onDoubleTap] gets the tap position and page size first; returning true skips the zoom
 * (videos use it to seek when the sides are double-tapped). Pass a remembered lambda.
 */
fun Modifier.zoomable(
    state: ZoomState,
    onTap: () -> Unit,
    onDoubleTap: (position: Offset, size: IntSize) -> Boolean = NoDoubleTapAction,
): Modifier = this
    .onSizeChanged { state.size = it }
    .pointerInput(state, onDoubleTap) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { if (!onDoubleTap(it, state.size)) state.toggle(it) },
        )
    }
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                // A child (e.g. the video seek bar) already handled this drag.
                if (event.changes.any { it.isConsumed }) continue
                val pinching = event.changes.count { it.pressed } > 1
                if (pinching || state.isZoomed) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f || pan != Offset.Zero) {
                        state.transform(zoom, pan, event.calculateCentroid(useCurrent = false))
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }

/** Shared so the gesture detector isn't restarted by a fresh default lambda on every recomposition. */
private val NoDoubleTapAction: (Offset, IntSize) -> Boolean = { _, _ -> false }

/** Draws content with the zoom and pan from [state]. */
fun Modifier.zoomed(state: ZoomState): Modifier = graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
}
