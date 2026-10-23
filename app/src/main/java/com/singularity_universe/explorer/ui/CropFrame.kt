package com.singularity_universe.explorer.ui

import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * The crop frame shared by the picture and video editors: where the content sits on screen,
 * the handles and how dragging them changes the frame, the overlay, and the shape presets.
 * Coordinates are content pixels (picture pixels, or video pixels as displayed).
 */

private val OnDarkCrop = Color(0xFFEDEDED)

/** Crop shapes: null is free-form, 0 keeps the picture's own proportions. */
internal val ASPECTS = listOf<Pair<String, Float?>>(
    "Free" to null, "Original" to 0f, "Square" to 1f, "4:3" to 4f / 3, "3:4" to 3f / 4, "16:9" to 16f / 9, "9:16" to 9f / 16,
)

/** Where the picture sits on screen: picture pixel × [scale] + ([left], [top]). */
internal class Fit(val scale: Float, val left: Float, val top: Float) {
    fun toImage(p: Offset) = Offset((p.x - left) / scale, (p.y - top) / scale)
    fun toScreen(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)
}

/** Fits [region] (content pixels) into [size] with [margin]: the content zoomed so the region fills the view. */
internal fun fitRegion(size: IntSize, region: RectF, margin: Float): Fit {
    val w = region.width().coerceAtLeast(1f)
    val h = region.height().coerceAtLeast(1f)
    val scale = min((size.width - 2 * margin) / w, (size.height - 2 * margin) / h).coerceAtLeast(0.0001f)
    return Fit(scale, (size.width - w * scale) / 2 - region.left * scale, (size.height - h * scale) / 2 - region.top * scale)
}

internal fun fitIn(size: IntSize, width: Int, height: Int, margin: Float): Fit {
    val scale = min((size.width - 2 * margin) / width, (size.height - 2 * margin) / height).coerceAtLeast(0.0001f)
    return Fit(scale, (size.width - width * scale) / 2, (size.height - height * scale) / 2)
}


internal enum class CropHandle { TopLeft, TopRight, BottomLeft, BottomRight, Left, Top, Right, Bottom, Move }

internal fun cropHandleAt(p: Offset, rect: RectF, fit: Fit, radius: Float, ratioLocked: Boolean): CropHandle? {
    val tl = fit.toScreen(rect.left, rect.top)
    val br = fit.toScreen(rect.right, rect.bottom)
    fun near(x: Float, y: Float) = hypot(p.x - x, p.y - y) < radius
    return when {
        near(tl.x, tl.y) -> CropHandle.TopLeft
        near(br.x, tl.y) -> CropHandle.TopRight
        near(tl.x, br.y) -> CropHandle.BottomLeft
        near(br.x, br.y) -> CropHandle.BottomRight
        // Edges only for free-form crops: with a fixed shape, corners keep the proportions.
        !ratioLocked && abs(p.x - tl.x) < radius && p.y in tl.y..br.y -> CropHandle.Left
        !ratioLocked && abs(p.x - br.x) < radius && p.y in tl.y..br.y -> CropHandle.Right
        !ratioLocked && abs(p.y - tl.y) < radius && p.x in tl.x..br.x -> CropHandle.Top
        !ratioLocked && abs(p.y - br.y) < radius && p.x in tl.x..br.x -> CropHandle.Bottom
        p.x in tl.x..br.x && p.y in tl.y..br.y -> CropHandle.Move
        else -> null
    }
}

internal fun dragCrop(start: RectF, handle: CropHandle, point: Offset, delta: Offset, ratio: Float?, width: Int, height: Int): RectF {
    val minSize = max(32f, min(width, height) * 0.05f)
    val w = width.toFloat()
    val h = height.toFloat()
    val px = point.x.coerceIn(0f, w)
    val py = point.y.coerceIn(0f, h)
    return when (handle) {
        CropHandle.Move -> {
            val dx = delta.x.coerceIn(-start.left, w - start.right)
            val dy = delta.y.coerceIn(-start.top, h - start.bottom)
            RectF(start.left + dx, start.top + dy, start.right + dx, start.bottom + dy)
        }
        CropHandle.Left -> RectF(px.coerceAtMost(start.right - minSize), start.top, start.right, start.bottom)
        CropHandle.Right -> RectF(start.left, start.top, px.coerceAtLeast(start.left + minSize), start.bottom)
        CropHandle.Top -> RectF(start.left, py.coerceAtMost(start.bottom - minSize), start.right, start.bottom)
        CropHandle.Bottom -> RectF(start.left, start.top, start.right, py.coerceAtLeast(start.top + minSize))
        else -> {
            // A corner: the opposite corner stays put.
            val anchorX = if (handle == CropHandle.TopLeft || handle == CropHandle.BottomLeft) start.right else start.left
            val anchorY = if (handle == CropHandle.TopLeft || handle == CropHandle.TopRight) start.bottom else start.top
            val signX = if (anchorX == start.right) -1f else 1f
            val signY = if (anchorY == start.bottom) -1f else 1f
            val roomX = if (signX < 0) anchorX else w - anchorX
            val roomY = if (signY < 0) anchorY else h - anchorY
            var dx = (abs(px - anchorX)).coerceIn(minSize, roomX)
            var dy = (abs(py - anchorY)).coerceIn(minSize, roomY)
            if (ratio != null) {
                if (dx / dy > ratio) dx = dy * ratio else dy = dx / ratio
                // Keep it within the picture after matching the shape.
                if (dx > roomX) { dx = roomX; dy = dx / ratio }
                if (dy > roomY) { dy = roomY; dx = dy * ratio }
            }
            val x2 = anchorX + signX * dx
            val y2 = anchorY + signY * dy
            RectF(min(anchorX, x2), min(anchorY, y2), max(anchorX, x2), max(anchorY, y2))
        }
    }
}

internal fun resolveAspect(aspect: Float?, width: Int, height: Int): Float? = when (aspect) {
    null -> null
    0f -> width.toFloat() / height
    else -> aspect
}

/** The biggest rectangle of [ratio] inside [rect] (or the whole picture, if that fits better), centred on it. */
internal fun fitAspect(rect: RectF, ratio: Float?, width: Int, height: Int): RectF {
    if (ratio == null) return rect
    var w = width.toFloat()
    var h = w / ratio
    if (h > height) {
        h = height.toFloat()
        w = h * ratio
    }
    val cx = rect.centerX().coerceIn(w / 2, width - w / 2)
    val cy = rect.centerY().coerceIn(h / 2, height - h / 2)
    return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
}

internal fun DrawScope.drawCropOverlay(rect: RectF, fit: Fit, width: Int, height: Int, dimAlpha: Float = 0.6f) {
    val image = Rect(0, 0, width, height)
    val outerTl = fit.toScreen(image.left.toFloat(), image.top.toFloat())
    val outerBr = fit.toScreen(image.right.toFloat(), image.bottom.toFloat())
    val tl = fit.toScreen(rect.left, rect.top)
    val br = fit.toScreen(rect.right, rect.bottom)
    val dim = Color.Black.copy(alpha = dimAlpha)
    // Darken what will be cut away.
    drawRect(dim, outerTl, Size(outerBr.x - outerTl.x, tl.y - outerTl.y))
    drawRect(dim, Offset(outerTl.x, br.y), Size(outerBr.x - outerTl.x, outerBr.y - br.y))
    drawRect(dim, Offset(outerTl.x, tl.y), Size(tl.x - outerTl.x, br.y - tl.y))
    drawRect(dim, Offset(br.x, tl.y), Size(outerBr.x - br.x, br.y - tl.y))

    val w = br.x - tl.x
    val h = br.y - tl.y
    drawRect(Color.White, tl, Size(w, h), style = Stroke(1.5.dp.toPx()))
    // Rule-of-thirds grid.
    val grid = Color.White.copy(alpha = 0.4f)
    for (i in 1..2) {
        drawLine(grid, Offset(tl.x + w * i / 3, tl.y), Offset(tl.x + w * i / 3, br.y), 1.dp.toPx())
        drawLine(grid, Offset(tl.x, tl.y + h * i / 3), Offset(br.x, tl.y + h * i / 3), 1.dp.toPx())
    }
    // Corner handles.
    val len = min(20.dp.toPx(), min(w, h) / 2)
    val thick = 4.dp.toPx()
    for ((x, y, sx, sy) in listOf(
        listOf(tl.x, tl.y, 1f, 1f), listOf(br.x, tl.y, -1f, 1f), listOf(tl.x, br.y, 1f, -1f), listOf(br.x, br.y, -1f, -1f),
    )) {
        drawLine(Color.White, Offset(x, y), Offset(x + sx * len, y), thick)
        drawLine(Color.White, Offset(x, y), Offset(x, y + sy * len), thick)
    }
}

@Composable
internal fun CropOptions(aspect: Float?, onAspect: (Float?) -> Unit, onRotate: (() -> Unit)?, onReset: () -> Unit, onApply: () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ASPECTS.forEach { (label, ratio) ->
                FilterChip(
                    selected = aspect == ratio,
                    onClick = { onAspect(ratio) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(labelColor = OnDarkCrop),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onRotate != null) IconButton(onClick = onRotate) { Icon(Icons.AutoMirrored.Outlined.RotateRight, "Rotate", tint = OnDarkCrop) }
            TextButton(onClick = onReset) { Text("Reset") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onApply) {
                Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Apply")
            }
        }
    }
}

