package com.singularity_universe.explorer.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.IOException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One step of a picture edit. Coordinates are pixels of the picture as it is at that step, i.e.
 * after the crops before it. Edits are kept as this list (not as pixels) so they can be undone,
 * shown on screen and saved with the same drawing code.
 */
sealed interface ImageOp

/** Keeps [rect], then turns the result clockwise by [quarterTurns] × 90°. */
data class CropOp(val rect: Rect, val quarterTurns: Int = 0) : ImageOp

/** A freehand line: [points] are x,y pairs. A [marker] is wide and see-through, like a highlighter. */
class StrokeOp(val points: FloatArray, val color: Int, val width: Float, val marker: Boolean) : ImageOp

/** A speech balloon centred at ([x], [y]) whose tail points at ([tailX], [tailY]). */
data class BalloonOp(
    val id: Long,
    val text: String,
    val x: Float,
    val y: Float,
    val textSize: Float,
    val fill: Int,
    val tailX: Float,
    val tailY: Float,
) : ImageOp

object ImageEdits {
    /** Pictures are edited at most this big (longest side), which keeps memory use reasonable. */
    const val MAX_EDIT_SIZE = 4096

    /** Decodes [file] upright (EXIF rotation applied), mutable, at most [MAX_EDIT_SIZE] on its longest side. */
    @Throws(IOException::class)
    fun decode(file: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = true
        val longest = max(info.size.width, info.size.height)
        if (longest > MAX_EDIT_SIZE) {
            val scale = MAX_EDIT_SIZE.toFloat() / longest
            decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
        }
    }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, true) }

    /** The picture after all [ops]. Never changes [source]. */
    fun render(source: Bitmap, ops: List<ImageOp>): Bitmap {
        var bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        var canvas = Canvas(bitmap)
        for (op in ops) {
            when (op) {
                is CropOp -> {
                    val cropped = crop(bitmap, op)
                    if (cropped !== bitmap) bitmap.recycle()
                    bitmap = cropped
                    canvas = Canvas(bitmap)
                }
                is StrokeOp -> drawStroke(canvas, op)
                is BalloonOp -> drawBalloon(canvas, op)
            }
        }
        return bitmap
    }

    private fun crop(bitmap: Bitmap, op: CropOp): Bitmap {
        val rect = Rect(op.rect).apply {
            if (!intersect(0, 0, bitmap.width, bitmap.height)) set(0, 0, bitmap.width, bitmap.height)
        }
        val matrix = Matrix().apply { if (op.quarterTurns % 4 != 0) postRotate(90f * (op.quarterTurns % 4)) }
        val result = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height(), matrix, true)
        return if (result.isMutable) result else result.copy(Bitmap.Config.ARGB_8888, true).also { result.recycle() }
    }

    /** Size after [op] is applied to a picture of [width] × [height]. */
    fun sizeAfter(op: CropOp, width: Int, height: Int): Pair<Int, Int> {
        val w = min(op.rect.width(), width)
        val h = min(op.rect.height(), height)
        return if (op.quarterTurns % 2 == 0) w to h else h to w
    }

    // ---- Drawing (shared by the screen and the saved file) ----

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    fun drawStroke(canvas: Canvas, op: StrokeOp) {
        val points = op.points
        if (points.size < 2) return
        val paint = strokePaint.apply {
            color = if (op.marker) (op.color and 0x00FFFFFF) or (MARKER_ALPHA shl 24) else op.color
            strokeWidth = op.width
            // A highlighter has a flat tip; a pen is round.
            strokeCap = if (op.marker) Paint.Cap.SQUARE else Paint.Cap.ROUND
        }
        if (points.size == 2) {
            // A tap: a dot.
            canvas.drawPoint(points[0], points[1], paint.apply { strokeCap = Paint.Cap.ROUND })
            return
        }
        // One path for the whole line, so a see-through marker crossing itself stays even.
        canvas.drawPath(smoothPath(points), paint)
    }

    /** A curve through the points (quadratic segments between their midpoints), smoother than straight lines. */
    private fun smoothPath(points: FloatArray): Path = Path().apply {
        moveTo(points[0], points[1])
        var i = 2
        while (i + 3 < points.size) {
            val cx = points[i]
            val cy = points[i + 1]
            quadTo(cx, cy, (cx + points[i + 2]) / 2, (cy + points[i + 3]) / 2)
            i += 2
        }
        lineTo(points[points.size - 2], points[points.size - 1])
    }

    /** Where a balloon's parts are, for drawing and for hit-testing taps. */
    class BalloonShape(val bubble: RectF, val outline: Path, val layout: StaticLayout, val textLeft: Float, val textTop: Float)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun balloonShape(op: BalloonOp): BalloonShape {
        val paint = TextPaint(textPaint).apply { textSize = op.textSize }
        val text = op.text.ifEmpty { " " }
        val maxWidth = (op.textSize * 14).toInt()
        val wanted = text.lines().maxOf { paint.measureText(it) }.let { min(it, maxWidth.toFloat()) }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(wanted.toInt() + 1, 1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        val padX = op.textSize * 0.9f
        val padY = op.textSize * 0.6f
        val w = layout.width + 2 * padX
        val h = layout.height + 2 * padY
        val bubble = RectF(op.x - w / 2, op.y - h / 2, op.x + w / 2, op.y + h / 2)
        val radius = min(h / 2, op.textSize * 1.2f)

        val outline = Path().apply { addRoundRect(bubble, radius, radius, Path.Direction.CW) }
        // The tail: a triangle from inside the bubble out to the tip, merged with the bubble.
        val dx = op.tailX - op.x
        val dy = op.tailY - op.y
        val length = hypot(dx, dy)
        if (!bubble.contains(op.tailX, op.tailY) && length > 0f) {
            val angle = atan2(dy, dx)
            val half = min(w, h) * 0.22f
            val nx = -sin(angle) * half
            val ny = cos(angle) * half
            val tail = Path().apply {
                moveTo(op.x + nx, op.y + ny)
                lineTo(op.tailX, op.tailY)
                lineTo(op.x - nx, op.y - ny)
                close()
            }
            outline.op(tail, Path.Op.UNION)
        }
        return BalloonShape(bubble, outline, layout, op.x - layout.width / 2f, op.y - layout.height / 2f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    fun drawBalloon(canvas: Canvas, op: BalloonOp, shape: BalloonShape = balloonShape(op)) {
        canvas.drawPath(shape.outline, fillPaint.apply { color = op.fill })
        canvas.drawPath(
            shape.outline,
            outlinePaint.apply {
                color = if (isLight(op.fill)) Color.BLACK else Color.WHITE
                strokeWidth = max(op.textSize * 0.08f, 1f)
            },
        )
        shape.layout.paint.color = if (isLight(op.fill)) Color.BLACK else Color.WHITE
        canvas.save()
        canvas.translate(shape.textLeft, shape.textTop)
        shape.layout.draw(canvas)
        canvas.restore()
    }

    fun isLight(color: Int): Boolean =
        0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color) > 150

    private const val MARKER_ALPHA = 0x66

    // ---- Saving ----

    /** Formats that can be written back in place; others are saved as a JPEG copy. */
    fun canReplace(file: File): Boolean = file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")

    /** Writes [bitmap] to [target] in the format its extension names, keeping the original's photo details. */
    @Throws(IOException::class)
    fun save(bitmap: Bitmap, target: File, original: File) {
        val format = when (target.extension.lowercase()) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> Bitmap.CompressFormat.JPEG
        }
        // Written next to the target and renamed over it, so a failure never leaves half a picture.
        val temp = File(target.parentFile, ".${target.name}.saving")
        try {
            temp.outputStream().use { out ->
                if (!bitmap.compress(format, 95, out)) throw IOException("Couldn't encode the picture")
                out.fd.sync()
            }
            copyPhotoDetails(original, temp)
            if (!temp.renameTo(target)) {
                temp.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        } finally {
            temp.delete()
        }
    }

    /** When it was taken, where and with what camera; not the orientation, which is now in the pixels. */
    private fun copyPhotoDetails(from: File, to: File) {
        runCatching {
            val source = ExifInterface(from.path)
            val target = ExifInterface(to.path)
            for (tag in KEPT_TAGS) source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
            target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            target.saveAttributes()
        }
    }

    private val KEPT_TAGS = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
    )

    /** "IMG_1234.jpg" → "IMG_1234_edited.jpg" (or "_edited_2", ...), always a format we can write. */
    fun copyTarget(original: File): File {
        val ext = original.extension.lowercase().takeIf { canReplace(original) } ?: "jpg"
        val base = original.nameWithoutExtension
        var candidate = File(original.parentFile, "${base}_edited.$ext")
        var n = 2
        while (candidate.exists()) candidate = File(original.parentFile, "${base}_edited_${n++}.$ext")
        return candidate
    }
}
