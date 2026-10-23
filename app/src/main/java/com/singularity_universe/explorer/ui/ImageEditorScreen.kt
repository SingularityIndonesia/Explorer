package com.singularity_universe.explorer.ui

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.data.BalloonOp
import com.singularity_universe.explorer.data.ImageEdits
import com.singularity_universe.explorer.data.ImageOp
import com.singularity_universe.explorer.data.StrokeOp
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private enum class Tool(val label: String) { Crop("Crop"), Draw("Draw"), Marker("Marker"), Text("Text") }

private val PALETTE = listOf(
    Color.White, Color.Black, Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835),
    Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFD81B60),
)

private val EditorBackground = Color(0xFF0E0E0E)
private val PanelBackground = Color(0xFF1C1C1C)
private val OnDark = Color(0xFFEDEDED)

/** Picture editor: crop and rotate, draw, highlight, and speech balloons, with undo/redo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditorScreen(vm: ImageEditorViewModel, onClose: () -> Unit, onSaved: (File) -> Unit, onError: (String) -> Unit) {
    var tool by remember { mutableStateOf<Tool?>(null) }
    var penColor by remember { mutableStateOf(PALETTE[2]) }
    var penSize by remember { mutableFloatStateOf(0.008f) }
    var markerColor by remember { mutableStateOf(PALETTE[4]) }
    var markerSize by remember { mutableFloatStateOf(0.03f) }
    var balloonFill by remember { mutableStateOf(Color.White) }
    var selectedBalloon by remember { mutableStateOf<Long?>(null) }
    var balloonDialog by remember { mutableStateOf<BalloonDialog?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }

    val base = vm.base
    // Pending crop rectangle in base pixels; a new base (after a crop or undo) starts uncropped.
    var cropRect by remember(base?.bitmap) {
        mutableStateOf(base?.bitmap?.let { RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()) } ?: RectF())
    }
    var aspect by remember(base?.bitmap) { mutableStateOf<Float?>(null) }
    LaunchedEffect(base?.bitmap) { selectedBalloon = null }

    fun pendingCrop(): Rect? {
        val bitmap = base?.bitmap ?: return null
        val r = Rect(cropRect.left.roundToInt(), cropRect.top.roundToInt(), cropRect.right.roundToInt(), cropRect.bottom.roundToInt())
        return if (r.left <= 0 && r.top <= 0 && r.right >= bitmap.width && r.bottom >= bitmap.height) null else r
    }

    /** Leaving the crop tool (or saving) applies the crop drawn so far, like most editors. */
    fun applyCrop() {
        pendingCrop()?.let(vm::crop)
    }

    fun selectTool(next: Tool?) {
        if (tool == Tool.Crop && next != Tool.Crop) applyCrop()
        tool = if (next == tool) null else next
        selectedBalloon = null
    }

    fun close() {
        if (vm.edited || pendingCrop() != null) confirmDiscard = true else onClose()
    }

    fun save(replace: Boolean) {
        saveMenu = false
        applyCrop()
        vm.save(replace) { file, error -> if (file != null) onSaved(file) else onError(error ?: "Couldn't save") }
    }

    BackHandler { close() }

    Scaffold(
        containerColor = EditorBackground,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = ::close) { Icon(Icons.Filled.Close, "Close") } },
                actions = {
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
                    Box {
                        if (vm.saving) {
                            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), color = OnDark, strokeWidth = 2.dp)
                        } else {
                            TextButton(
                                onClick = { saveMenu = true },
                                enabled = vm.state == ImageEditorViewModel.State.Ready && (vm.edited || pendingCrop() != null),
                            ) { Text("Save") }
                        }
                        DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                            DropdownMenuItem(text = { Text("Save as copy") }, onClick = { save(replace = false) })
                            if (ImageEdits.canReplace(vm.file)) {
                                DropdownMenuItem(text = { Text("Replace original") }, onClick = { save(replace = true) })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = EditorBackground,
                    navigationIconContentColor = OnDark,
                    actionIconContentColor = OnDark,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val state = vm.state) {
                    ImageEditorViewModel.State.Loading -> CircularProgressIndicator(color = OnDark)
                    is ImageEditorViewModel.State.Failed -> Text(state.reason, color = OnDark, textAlign = TextAlign.Center)
                    ImageEditorViewModel.State.Ready -> if (base != null) {
                        val longSide = max(base.bitmap.width, base.bitmap.height).toFloat()
                        EditorCanvas(
                            vm = vm,
                            base = base,
                            tool = tool,
                            cropRect = cropRect,
                            onCropChange = { cropRect = it },
                            aspect = aspect,
                            strokeStyle = when (tool) {
                                Tool.Draw -> StrokeStyle(penColor.toArgb(), penSize * longSide, marker = false)
                                Tool.Marker -> StrokeStyle(markerColor.toArgb(), markerSize * longSide, marker = true)
                                else -> null
                            },
                            selectedBalloon = selectedBalloon,
                            onSelectBalloon = { selectedBalloon = it },
                            onNewBalloon = { x, y -> balloonDialog = BalloonDialog(null, x, y) },
                            onEditBalloon = { balloonDialog = BalloonDialog(it, it.x, it.y) },
                        )
                    }
                }
            }

            Column(Modifier.fillMaxWidth().background(PanelBackground).navigationBarsPadding()) {
                when (tool) {
                    Tool.Crop -> CropOptions(
                        aspect = aspect,
                        onAspect = { ratio ->
                            aspect = ratio
                            base?.bitmap?.let { cropRect = fitAspect(cropRect, resolveAspect(ratio, it.width, it.height), it.width, it.height) }
                        },
                        onRotate = {
                            applyCrop()
                            vm.rotate()
                        },
                        onReset = { base?.bitmap?.let { cropRect = RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()) } },
                        onApply = {
                            applyCrop()
                            tool = null
                        },
                    )
                    Tool.Draw -> StrokeOptions(penColor, { penColor = it }, penSize, { penSize = it }, 0.002f..0.03f)
                    Tool.Marker -> StrokeOptions(markerColor, { markerColor = it }, markerSize, { markerSize = it }, 0.01f..0.08f)
                    Tool.Text -> {
                        val balloon = vm.liveOps().filterIsInstance<BalloonOp>().firstOrNull { it.id == selectedBalloon }
                        val bitmap = base?.bitmap
                        TextOptions(
                            balloon = balloon,
                            fill = balloon?.let { Color(it.fill) } ?: balloonFill,
                            onFill = { color ->
                                balloonFill = color
                                balloon?.let { vm.replaceBalloon(it.id, it.copy(fill = color.toArgb())) }
                            },
                            sizeFraction = if (balloon != null && bitmap != null) balloon.textSize / max(bitmap.width, bitmap.height) else 0f,
                            onSize = { fraction ->
                                if (balloon != null && bitmap != null) {
                                    vm.updateBalloon(balloon.copy(textSize = fraction * max(bitmap.width, bitmap.height)))
                                }
                            },
                            onSizeStart = vm::beginChange,
                            onEdit = { balloon?.let { balloonDialog = BalloonDialog(it, it.x, it.y) } },
                            onDelete = {
                                balloon?.let { vm.replaceBalloon(it.id, null) }
                                selectedBalloon = null
                            },
                        )
                    }
                    null -> Unit
                }
                ToolBar(tool, ::selectTool)
            }
        }
    }

    balloonDialog?.let { dialog ->
        BalloonTextDialog(
            initial = dialog.balloon?.text.orEmpty(),
            onDismiss = { balloonDialog = null },
            onDone = { text ->
                balloonDialog = null
                val existing = dialog.balloon
                when {
                    existing != null && text.isBlank() -> {
                        vm.replaceBalloon(existing.id, null)
                        selectedBalloon = null
                    }
                    existing != null -> vm.replaceBalloon(existing.id, existing.copy(text = text))
                    text.isNotBlank() -> {
                        val bitmap = base?.bitmap ?: return@BalloonTextDialog
                        val size = 0.035f * max(bitmap.width, bitmap.height)
                        val balloon = BalloonOp(
                            id = System.nanoTime(),
                            text = text,
                            x = dialog.x,
                            y = dialog.y,
                            textSize = size,
                            fill = balloonFill.toArgb(),
                            tailX = dialog.x - size * 2.5f,
                            tailY = dialog.y + size * 3.5f,
                        )
                        vm.add(balloon)
                        selectedBalloon = balloon.id
                    }
                }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard edits?") },
            text = { Text("Your changes to this picture won't be saved.") },
            confirmButton = { TextButton(onClick = onClose) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

private class BalloonDialog(val balloon: BalloonOp?, val x: Float, val y: Float)

private class StrokeStyle(val color: Int, val width: Float, val marker: Boolean)

@Composable
private fun EditorCanvas(
    vm: ImageEditorViewModel,
    base: ImageEditorViewModel.Base,
    tool: Tool?,
    cropRect: RectF,
    onCropChange: (RectF) -> Unit,
    aspect: Float?,
    strokeStyle: StrokeStyle?,
    selectedBalloon: Long?,
    onSelectBalloon: (Long?) -> Unit,
    onNewBalloon: (Float, Float) -> Unit,
    onEditBalloon: (BalloonOp) -> Unit,
) {
    val density = LocalDensity.current
    val margin = with(density) { 20.dp.toPx() }
    val touchSlop = with(density) { 8.dp.toPx() }
    val handleRadius = with(density) { 28.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = base.bitmap
    val baseFit = fitIn(size, bitmap.width, bitmap.height, margin)
    // Pinch zoom (two fingers, any tool): null shows the whole picture. A new picture (after a crop) starts unzoomed.
    var zoomFit by remember(bitmap) { mutableStateOf<Fit?>(null) }
    val fit = zoomFit ?: baseFit
    val currentBaseFit by rememberUpdatedState(baseFit)

    // The line being drawn, in picture pixels; [strokeVersion] redraws as it grows.
    val stroke = remember { ArrayList<Float>() }
    var strokeVersion by remember { mutableIntStateOf(0) }

    val currentFit by rememberUpdatedState(fit)
    val currentCrop by rememberUpdatedState(cropRect)
    val currentAspect by rememberUpdatedState(aspect)
    val currentStyle by rememberUpdatedState(strokeStyle)
    val currentSelected by rememberUpdatedState(selectedBalloon)

    val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }

    val gestures: suspend PointerInputScope.() -> Unit = {
        when (tool) {
            Tool.Draw, Tool.Marker -> awaitEachGesture {
                val down = awaitFirstDown()
                val style = currentStyle ?: return@awaitEachGesture
                stroke.clear()
                currentFit.toImage(down.position).let { stroke += it.x; stroke += it.y }
                strokeVersion++
                var last = down.position
                while (true) {
                    val event = awaitPointerEvent()
                    // A second finger isn't drawing: drop the line.
                    if (event.changes.count { it.pressed } > 1) {
                        stroke.clear()
                        strokeVersion++
                        return@awaitEachGesture
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if ((change.position - last).getDistance() > 1.5f) {
                        currentFit.toImage(change.position).let { stroke += it.x; stroke += it.y }
                        last = change.position
                        strokeVersion++
                    }
                    change.consume()
                }
                if (stroke.isNotEmpty()) vm.add(StrokeOp(stroke.toFloatArray(), style.color, style.width, style.marker))
                stroke.clear()
                strokeVersion++
            }
            Tool.Text -> awaitEachGesture {
                val down = awaitFirstDown()
                val fitNow = currentFit
                val start = fitNow.toImage(down.position)
                val balloons = vm.liveOps().filterIsInstance<BalloonOp>()
                val selected = balloons.firstOrNull { it.id == currentSelected }
                // What the finger grabbed: the selected balloon's tail tip, a balloon, or nothing.
                val onTail = selected != null &&
                    (fitNow.toScreen(selected.tailX, selected.tailY) - down.position).getDistance() < handleRadius
                val hit = if (onTail) selected else balloons.lastOrNull { ImageEdits.balloonShape(it).bubble.contains(start.x, start.y) }
                var dragging = false
                while (true) {
                    val event = awaitPointerEvent()
                    // A second finger: that's a pinch, not a balloon drag.
                    if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (hit != null) {
                        if (!dragging && (change.position - down.position).getDistance() > touchSlop) {
                            dragging = true
                            vm.beginChange()
                            onSelectBalloon(hit.id)
                        }
                        if (dragging) {
                            val now = fitNow.toImage(change.position)
                            val dx = now.x - start.x
                            val dy = now.y - start.y
                            vm.updateBalloon(
                                if (onTail) hit.copy(tailX = hit.tailX + dx, tailY = hit.tailY + dy)
                                else hit.copy(x = hit.x + dx, y = hit.y + dy, tailX = hit.tailX + dx, tailY = hit.tailY + dy)
                            )
                            change.consume()
                        }
                    }
                }
                if (dragging) return@awaitEachGesture
                when {
                    hit != null && hit.id == currentSelected -> onEditBalloon(hit)
                    hit != null -> onSelectBalloon(hit.id)
                    currentSelected != null -> onSelectBalloon(null)
                    start.x in 0f..bitmap.width.toFloat() && start.y in 0f..bitmap.height.toFloat() -> onNewBalloon(start.x, start.y)
                }
            }
            Tool.Crop -> awaitEachGesture {
                val down = awaitFirstDown()
                val fitNow = currentFit
                val startRect = RectF(currentCrop)
                val handle = cropHandleAt(down.position, startRect, fitNow, handleRadius, currentAspect != null)
                    ?: return@awaitEachGesture
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val point = fitNow.toImage(change.position)
                    val delta = point - fitNow.toImage(down.position)
                    val ratio = resolveAspect(currentAspect, bitmap.width, bitmap.height)
                    onCropChange(dragCrop(startRect, handle, point, delta, ratio, bitmap.width, bitmap.height))
                    change.consume()
                }
            }
            null -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            // Two fingers zoom and pan, whatever the tool; seen first, so tools don't also act on them.
            .pointerInput(bitmap) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val whole = currentBaseFit
                            val current = zoomFit ?: whole
                            val scale = (current.scale * event.calculateZoom()).coerceIn(whole.scale, whole.scale * 8)
                            val ratio = scale / current.scale
                            val centre = event.calculateCentroid()
                            val pan = event.calculatePan()
                            zoomFit = if (scale <= whole.scale * 1.01f) null
                            else Fit(scale, centre.x - (centre.x - current.left) * ratio + pan.x, centre.y - (centre.y - current.top) * ratio + pan.y)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(tool, base, block = gestures),
    ) {
        strokeVersion // Redraw while a line is being drawn.
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(fit.left, fit.top)
            native.scale(fit.scale, fit.scale)
            native.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
            vm.liveOps().forEach { op -> drawOp(native, op) }
            val style = strokeStyle
            if (stroke.isNotEmpty() && style != null) {
                ImageEdits.drawStroke(native, StrokeOp(stroke.toFloatArray(), style.color, style.width, style.marker))
            }
            native.restore()
        }
        if (tool == Tool.Text) {
            vm.liveOps().filterIsInstance<BalloonOp>().firstOrNull { it.id == selectedBalloon }?.let { balloon ->
                drawBalloonSelection(balloon, fit)
            }
        }
        if (tool == Tool.Crop) drawCropOverlay(cropRect, fit, bitmap.width, bitmap.height)
    }
    if (zoomFit != null) {
        FilterChip(
            selected = true,
            onClick = { zoomFit = null },
            label = { Text("Fit") },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        )
    }
    }
}

private fun drawOp(canvas: android.graphics.Canvas, op: ImageOp) {
    when (op) {
        is StrokeOp -> ImageEdits.drawStroke(canvas, op)
        is BalloonOp -> ImageEdits.drawBalloon(canvas, op)
        else -> Unit
    }
}

private fun DrawScope.drawBalloonSelection(balloon: BalloonOp, fit: Fit) {
    val bubble = ImageEdits.balloonShape(balloon).bubble
    val topLeft = fit.toScreen(bubble.left, bubble.top)
    val pad = 6.dp.toPx()
    drawRoundRect(
        color = Color.White,
        topLeft = topLeft - Offset(pad, pad),
        size = Size(bubble.width() * fit.scale + 2 * pad, bubble.height() * fit.scale + 2 * pad),
        cornerRadius = CornerRadius(8.dp.toPx()),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
    )
    // The tail tip can be dragged to point the balloon at something.
    val tip = fit.toScreen(balloon.tailX, balloon.tailY)
    drawCircle(Color.White, radius = 10.dp.toPx(), center = tip)
    drawCircle(Color(0xFF1E88E5), radius = 7.dp.toPx(), center = tip)
}

// ---- Panels ----

@Composable
private fun ToolBar(tool: Tool?, onSelect: (Tool) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Tool.entries.forEach { t ->
            val selected = t == tool
            Column(
                Modifier
                    .clickable { onSelect(t) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    when (t) {
                        Tool.Crop -> Icons.Outlined.Crop
                        Tool.Draw -> Icons.Outlined.Gesture
                        Tool.Marker -> Icons.Outlined.BorderColor
                        Tool.Text -> Icons.Outlined.ChatBubbleOutline
                    },
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else OnDark,
                )
                Text(
                    t.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else OnDark,
                )
            }
        }
    }
}

@Composable
private fun StrokeOptions(
    color: Color,
    onColor: (Color) -> Unit,
    size: Float,
    onSize: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Swatches(color, onColor)
        SizeSlider(size, onSize, range)
    }
}

@Composable
private fun TextOptions(
    balloon: BalloonOp?,
    fill: Color,
    onFill: (Color) -> Unit,
    sizeFraction: Float,
    onSize: (Float) -> Unit,
    onSizeStart: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Swatches(fill, onFill)
        if (balloon == null) {
            Text(
                "Tap the picture to add a balloon. Tap a balloon to select it; drag it, or its blue dot to aim the tail.",
                color = OnDark.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        } else {
            var started by remember(balloon.id) { mutableStateOf(false) }
            SizeSlider(
                sizeFraction,
                { value ->
                    if (!started) {
                        onSizeStart()
                        started = true
                    }
                    onSize(value)
                },
                0.015f..0.08f,
                onFinished = { started = false },
            )
            Row(Modifier.padding(horizontal = 4.dp)) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit text")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun Swatches(selected: Color, onSelect: (Color) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PALETTE.forEach { color ->
            Box(
                Modifier
                    .size(32.dp)
                    .background(color, CircleShape)
                    .border(
                        width = if (color == selected) 3.dp else 1.dp,
                        color = if (color == selected) MaterialTheme.colorScheme.primary else Color.Gray,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(color) },
            )
        }
    }
}

@Composable
private fun SizeSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, onFinished: () -> Unit = {}) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Size", color = OnDark, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            colors = SliderDefaults.colors(inactiveTrackColor = Color.Gray),
            modifier = Modifier.padding(start = 12.dp).height(40.dp),
        )
    }
}

@Composable
private fun BalloonTextDialog(initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isEmpty()) "Add text" else "Edit text") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
