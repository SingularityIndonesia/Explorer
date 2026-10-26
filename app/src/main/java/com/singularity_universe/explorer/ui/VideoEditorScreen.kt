package com.singularity_universe.explorer.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.TextureView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val VideoBackground = Color(0xFF0E0E0E)
private val VideoPanel = Color(0xFF1C1C1C)
private val VideoOnDark = Color(0xFFEDEDED)

/**
 * Video editor: drag the crop frame and the trim handles, then save. Both are always active.
 *
 * Tablets in landscape show the full frame (with the crop box) beside the result. Everywhere else
 * one preview shows the result: the cropped part fills it, and the rest of the frame appears,
 * dimmed, only while the crop box is being dragged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen(vm: VideoEditorViewModel, onClose: () -> Unit, onSaved: (File) -> Unit, onError: (String) -> Unit) {
    var aspect by remember { mutableStateOf<Float?>(null) }
    var saveMenu by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    fun close() {
        // While saving, leaving is fine: the save carries on in the background.
        if (vm.exportProgress != null || !vm.edited) onClose() else confirmDiscard = true
    }

    fun save(replace: Boolean) {
        saveMenu = false
        vm.export(replace) { file, error -> if (file != null) onSaved(file) else onError(error ?: "Couldn't save") }
    }

    BackHandler { close() }

    // The player draws into this one view, wherever the layout puts it.
    val context = LocalContext.current
    val textureView = remember { TextureView(context) }
    DisposableEffect(vm.player) {
        vm.player.setVideoTextureView(textureView)
        onDispose { vm.player.clearVideoTextureView(textureView) }
    }

    Scaffold(
        containerColor = VideoBackground,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = ::close) { Icon(Icons.Filled.Close, "Close") } },
                actions = {
                    Box {
                        TextButton(
                            onClick = { saveMenu = true },
                            enabled = vm.state == VideoEditorViewModel.State.Ready && vm.edited && vm.exportProgress == null,
                        ) { Text("Save") }
                        DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                            DropdownMenuItem(text = { Text("Save as copy") }, onClick = { save(replace = false) })
                            if (vm.canReplace()) {
                                DropdownMenuItem(text = { Text("Replace original") }, onClick = { save(replace = true) })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VideoBackground,
                    navigationIconContentColor = VideoOnDark,
                    actionIconContentColor = VideoOnDark,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val state = vm.state) {
                    VideoEditorViewModel.State.Loading -> CircularProgressIndicator(color = VideoOnDark)
                    is VideoEditorViewModel.State.Failed -> Text(state.reason, color = VideoOnDark, textAlign = TextAlign.Center)
                    VideoEditorViewModel.State.Ready -> {
                        val sideBySide = maxWidth >= 600.dp && maxWidth > maxHeight
                        if (sideBySide) {
                            Row(Modifier.fillMaxSize()) {
                                FullFramePane(vm, textureView, aspect, Modifier.weight(1f).fillMaxSize())
                                ResultPane(vm, textureView, Modifier.weight(1f).fillMaxSize())
                            }
                        } else {
                            SinglePane(vm, textureView, aspect)
                        }
                    }
                }
            }

            if (vm.state == VideoEditorViewModel.State.Ready) {
                Column(Modifier.fillMaxWidth().background(VideoPanel).navigationBarsPadding().padding(vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = vm::togglePlay) {
                            Icon(
                                if (vm.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (vm.playing) "Pause" else "Play",
                                tint = VideoOnDark,
                            )
                        }
                        Text(
                            "${time(vm.startMs)} – ${time(vm.endMs)}  ·  ${time(vm.endMs - vm.startMs)} kept",
                            color = VideoOnDark,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    TrimStrip(vm)
                    ShapeRow(
                        aspect = aspect,
                        cropped = vm.cropped,
                        onAspect = { ratio ->
                            aspect = ratio
                            vm.crop = fitAspect(vm.crop, resolveAspect(ratio, vm.width, vm.height), vm.width, vm.height)
                        },
                        onReset = {
                            aspect = null
                            vm.resetCrop()
                        },
                    )
                }
            }
        }
    }

    vm.exportProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Saving video…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).roundToInt()}% · Hide to keep saving in the background")
                }
            },
            confirmButton = { TextButton(onClick = ::close) { Text("Hide") } },
            dismissButton = { TextButton(onClick = vm::cancelExport) { Text("Cancel") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard edits?") },
            text = { Text("Your changes to this video won't be saved.") },
            confirmButton = { TextButton(onClick = onClose) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

// ---- Panes ----

/**
 * The one preview (phones, and tablets in portrait): shows the result, i.e. the crop zoomed to
 * fill the view. Grabbing the crop box zooms out around the finger (the point under it stays put)
 * to show the whole frame, dimmed outside the box, so the crop can grow as well as shrink; on
 * release the view glides back to fit the new crop.
 */
@Composable
private fun SinglePane(vm: VideoEditorViewModel, textureView: TextureView, aspect: Float?) {
    val density = LocalDensity.current
    val margin = with(density) { 24.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // While dragging: the zoomed-out view, fixed for the whole drag so the box follows the finger.
    var dragFit by remember { mutableStateOf<Fit?>(null) }
    val target = dragFit ?: fitRegion(size, vm.crop, margin)

    // The shown view moves from where it was to [target]; interpolating scale and offset together
    // keeps the touched point under the finger while zooming out.
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    fun shown(): Fit = lerp(from, to, progress.value)
    LaunchedEffect(target.scale, target.left, target.top) {
        // The first real layout (or a view that wasn't measured yet) appears at once, not zooming in from nothing.
        if (size == IntSize.Zero || shown().scale < 0.001f) {
            from = target
            to = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        from = shown()
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(260))
    }
    val fit = shown()
    // At rest only the result is visible; while dragging the surroundings show, dimmed.
    val outside by animateFloatAsState(if (dragFit != null) 0.55f else 1f, label = "surroundings")

    Box(Modifier.fillMaxSize().onSizeChanged { size = it }.clipToBounds()) {
        if (size != IntSize.Zero) VideoLayer(textureView, fit, vm.width, vm.height, window = null)
        Canvas(
            Modifier
                .fillMaxSize()
                .cropGestures(
                    vm,
                    fit = ::shown,
                    aspect = { aspect },
                    onDragStart = { touch, current ->
                        val whole = fitIn(size, vm.width, vm.height, margin)
                        val scale = minOf(whole.scale, current.scale)
                        val anchor = current.toImage(touch)
                        Fit(scale, touch.x - anchor.x * scale, touch.y - anchor.y * scale).also { dragFit = it }
                    },
                    onDragEnd = { dragFit = null },
                ),
        ) {
            drawCropOverlay(vm.crop, fit, vm.width, vm.height, dimAlpha = outside)
        }
    }
}

private fun lerp(a: Fit, b: Fit, t: Float) =
    Fit(a.scale + (b.scale - a.scale) * t, a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t)

/** Tablet landscape, left: the whole frame with the crop box. Shows frames grabbed from the player. */
@Composable
private fun FullFramePane(vm: VideoEditorViewModel, textureView: TextureView, aspect: Float?, modifier: Modifier) {
    val density = LocalDensity.current
    val margin = with(density) { 24.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val fit = fitIn(size, vm.width, vm.height, margin)

    // A small copy of the current frame, refreshed while it changes (playing, seeking).
    val frame = remember(vm.width, vm.height) {
        val scale = minOf(1f, 960f / maxOf(vm.width, vm.height))
        Bitmap.createBitmap((vm.width * scale).roundToInt().coerceAtLeast(1), (vm.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    }
    var frameVersion by remember { mutableIntStateOf(0) }
    var hasFrame by remember { mutableStateOf(false) }
    val position by rememberUpdatedState(vm.positionMs)
    val playing by rememberUpdatedState(vm.playing)
    LaunchedEffect(textureView, frame) {
        var lastPosition = -1L
        var changedAt = 0L
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (position != lastPosition) {
                lastPosition = position
                changedAt = now
            }
            // After a seek the new frame arrives a moment later: keep grabbing briefly.
            if (textureView.isAvailable && (playing || now - changedAt < 600 || !hasFrame)) {
                textureView.getBitmap(frame)
                hasFrame = true
                frameVersion++
            }
            delay(80)
        }
    }

    Box(modifier.onSizeChanged { size = it }.clipToBounds()) {
        Canvas(Modifier.fillMaxSize().cropGestures(vm, { fit }, { aspect })) {
            frameVersion // Redraw when a new frame was grabbed.
            val image = if (hasFrame) frame else vm.thumbnails.firstOrNull()
            if (image != null) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawBitmap(
                        image,
                        null,
                        android.graphics.RectF(fit.left, fit.top, fit.left + vm.width * fit.scale, fit.top + vm.height * fit.scale),
                        null,
                    )
                }
            }
            drawCropOverlay(vm.crop, fit, vm.width, vm.height)
        }
    }
}

/** Tablet landscape, right: the result, playing live. */
@Composable
private fun ResultPane(vm: VideoEditorViewModel, textureView: TextureView, modifier: Modifier) {
    val density = LocalDensity.current
    val margin = with(density) { 24.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val fit = fitRegion(size, vm.crop, margin)
    val window = Rect(fit.left + vm.crop.left * fit.scale, fit.top + vm.crop.top * fit.scale,
        fit.left + vm.crop.right * fit.scale, fit.top + vm.crop.bottom * fit.scale)
    Box(modifier.onSizeChanged { size = it }.clipToBounds()) {
        if (size != IntSize.Zero) VideoLayer(textureView, fit, vm.width, vm.height, window)
        Text(
            "Result",
            color = VideoOnDark.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
        )
    }
}

/**
 * The player's view, placed so the video sits where [fit] says. With a [window] only that part of
 * the screen shows video (the rest of the frame is cut off).
 */
@Composable
private fun VideoLayer(textureView: TextureView, fit: Fit, width: Int, height: Int, window: Rect?) {
    val density = LocalDensity.current
    val clip = window ?: Rect(fit.left, fit.top, fit.left + width * fit.scale, fit.top + height * fit.scale)
    Box(
        Modifier
            .offset { IntOffset(clip.left.roundToInt(), clip.top.roundToInt()) }
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .requiredSize(with(density) { clip.width.coerceAtLeast(1f).toDp() }, with(density) { clip.height.coerceAtLeast(1f).toDp() })
            .clipToBounds(),
    ) {
        AndroidView(
            factory = {
                // The same view moves between layouts (e.g. on rotation): detach it from the old one.
                (textureView.parent as? ViewGroup)?.removeView(textureView)
                textureView
            },
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset { IntOffset((fit.left - clip.left).roundToInt(), (fit.top - clip.top).roundToInt()) }
                .requiredSize(with(density) { (width * fit.scale).toDp() }, with(density) { (height * fit.scale).toDp() }),
        )
    }
}

/**
 * Dragging the crop box's corners, edges or middle. The handle is found in the view as shown;
 * [onDragStart] may switch to another view for the drag (the single pane zooms out), which then
 * stays fixed until the finger lifts.
 */
private fun Modifier.cropGestures(
    vm: VideoEditorViewModel,
    fit: () -> Fit,
    aspect: () -> Float?,
    onDragStart: (Offset, Fit) -> Fit = { _, current -> current },
    onDragEnd: () -> Unit = {},
): Modifier = pointerInput(vm.width, vm.height) {
    val handleRadius = 28.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        val start = RectF(vm.crop)
        val handle = cropHandleAt(down.position, start, fit(), handleRadius, aspect() != null) ?: return@awaitEachGesture
        val fitNow = onDragStart(down.position, fit())
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val point = fitNow.toImage(change.position)
                val delta = point - fitNow.toImage(down.position)
                vm.crop = dragCrop(start, handle, point, delta, resolveAspect(aspect(), vm.width, vm.height), vm.width, vm.height)
                change.consume()
            }
        } finally {
            onDragEnd()
        }
    }
}

// ---- Controls ----

/** Crop shapes, and Reset once the frame is cropped. */
@Composable
private fun ShapeRow(aspect: Float?, cropped: Boolean, onAspect: (Float?) -> Unit, onReset: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ASPECTS.forEach { (label, ratio) ->
            FilterChip(
                selected = aspect == ratio,
                onClick = { onAspect(ratio) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(labelColor = VideoOnDark),
            )
        }
        if (cropped) TextButton(onClick = onReset) { Text("Reset crop") }
    }
}

private enum class TrimHandle { Start, End }

private const val HOLD_STILL_MS = 450L
private const val ZOOM_FRAME_COUNT = 9
private const val PRECISION_PX_PER_FRAME = 22f

/**
 * Frames along the video with two draggable trim handles over them: the part between them is kept.
 * Holding either handle still for a moment switches it to frame-by-frame precision — a small drag
 * then only nudges it by whole frames — with a zoomed-in strip of the nearby frames to line it up
 * exactly; letting go (or starting to drag again) goes back to the coarse, whole-strip speed.
 */
@Composable
private fun TrimStrip(vm: VideoEditorViewModel) {
    val duration = vm.durationMs.toFloat().coerceAtLeast(1f)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var dragging by remember { mutableStateOf<TrimHandle?>(null) }
    var precision by remember { mutableStateOf(false) }
    var lastMoveAt by remember { mutableLongStateOf(0L) }
    var handleX by remember { mutableFloatStateOf(0f) }
    var zoomFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var zoomJob by remember { mutableStateOf<Job?>(null) }

    fun requestZoom(centerMs: Long) {
        zoomJob?.cancel()
        zoomJob = scope.launch { zoomFrames = vm.frameStrip(centerMs, ZOOM_FRAME_COUNT) }
    }

    // Holding a handle still (no new drag events) for a moment switches it to frame precision.
    LaunchedEffect(lastMoveAt, dragging) {
        val handle = dragging ?: return@LaunchedEffect
        delay(HOLD_STILL_MS)
        if (dragging == handle && !precision) {
            precision = true
            requestZoom(if (handle == TrimHandle.Start) vm.startMs else vm.endMs)
        }
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.DarkGray),
        ) {
            val trackPx = with(density) { maxWidth.toPx() }
            val startFrac = (vm.startMs / duration).coerceIn(0f, 1f)
            val endFrac = (vm.endMs / duration).coerceIn(0f, 1f)

            Row(Modifier.fillMaxSize()) {
                vm.thumbnails.forEach { frame ->
                    Image(
                        frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).height(48.dp),
                    )
                }
            }

            // Dims the trimmed-out parts, left and right of the kept range.
            Box(Modifier.width(with(density) { (startFrac * trackPx).toDp() }).fillMaxHeight().background(Color.Black.copy(alpha = 0.55f)))
            Box(
                Modifier
                    .offset { IntOffset((endFrac * trackPx).roundToInt(), 0) }
                    .width(with(density) { ((1f - endFrac) * trackPx).toDp() })
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.55f)),
            )

            // Where playback currently is within the kept range.
            val played = (vm.positionMs / duration).coerceIn(0f, 1f)
            Box(
                Modifier
                    .offset { IntOffset(played.times(trackPx).roundToInt(), 0) }
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White),
            )

            TrimHandleMark(startFrac, trackPx, active = dragging == TrimHandle.Start)
            TrimHandleMark(endFrac, trackPx, active = dragging == TrimHandle.End)

            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(vm.durationMs) {
                        val hitRadius = 28.dp.toPx()
                        val holdSlop = 6.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val startPx = vm.startMs / duration * size.width
                            val endPx = vm.endMs / duration * size.width
                            val toStart = abs(down.position.x - startPx)
                            val toEnd = abs(down.position.x - endPx)
                            val handle = if (toStart <= toEnd) TrimHandle.Start else TrimHandle.End
                            if (minOf(toStart, toEnd) > hitRadius) return@awaitEachGesture

                            dragging = handle
                            precision = false
                            lastMoveAt = System.currentTimeMillis()
                            handleX = down.position.x
                            var stillOriginX = down.position.x
                            var baseMs = (if (handle == TrimHandle.Start) vm.startMs else vm.endMs).toFloat()
                            var baseX = down.position.x
                            val minGapMs = minOf(500f, duration)

                            fun apply(ms: Long) {
                                when (handle) {
                                    TrimHandle.Start -> vm.setRange(ms.coerceIn(0L, (vm.endMs - minGapMs).toLong()), vm.endMs)
                                    TrimHandle.End -> vm.setRange(vm.startMs, ms.coerceIn((vm.startMs + minGapMs).toLong(), vm.durationMs))
                                }
                            }

                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    handleX = change.position.x

                                    val wasPrecision = precision
                                    val ms = if (precision) {
                                        baseMs + (change.position.x - baseX) / PRECISION_PX_PER_FRAME * vm.frameMs
                                    } else {
                                        baseMs + (change.position.x - baseX) / size.width * duration
                                    }
                                    val snapped = if (precision) ((ms / vm.frameMs).roundToLong() * vm.frameMs).roundToLong() else ms.roundToLong()
                                    apply(snapped)
                                    // Just switched to precision: re-anchor so the small drag so far isn't replayed at frame scale.
                                    if (precision && !wasPrecision) {
                                        baseMs = (if (handle == TrimHandle.Start) vm.startMs else vm.endMs).toFloat()
                                        baseX = change.position.x
                                    } else if (precision) {
                                        requestZoom(if (handle == TrimHandle.Start) vm.startMs else vm.endMs)
                                    }
                                    // Only real movement (not touch noise) resets the "holding still" timer.
                                    if (abs(change.position.x - stillOriginX) > holdSlop) {
                                        stillOriginX = change.position.x
                                        lastMoveAt = System.currentTimeMillis()
                                    }
                                    change.consume()
                                }
                            } finally {
                                dragging = null
                                precision = false
                                zoomFrames = emptyList()
                            }
                        }
                    },
            )

            if (precision && zoomFrames.isNotEmpty()) {
                ZoomFrameStrip(zoomFrames, handleX, density)
            }
        }
    }
}

/** A trim handle: a thin bright bar with a small grip, brighter while it's the one being dragged. */
@Composable
private fun TrimHandleMark(fraction: Float, trackPx: Float, active: Boolean) {
    Box(
        Modifier
            .offset { IntOffset((fraction * trackPx).roundToInt() - 3, 0) }
            .width(6.dp)
            .fillMaxHeight()
            .background(if (active) Color.White else Color.White.copy(alpha = 0.85f), RoundedCornerShape(2.dp)),
    )
}

/** A close-up of individual frames around a handle, shown while it's held in frame precision. */
@Composable
private fun ZoomFrameStrip(frames: List<Bitmap>, handleX: Float, density: androidx.compose.ui.unit.Density) {
    val widthPx = with(density) { (frames.size * 36).dp.toPx() }
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset((handleX - widthPx / 2).roundToInt(), with(density) { (-72).dp.roundToPx() }),
    ) {
        Row(
            Modifier
                .background(Color.Black, RoundedCornerShape(8.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            frames.forEachIndexed { index, frame ->
                Image(
                    frame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(32.dp, 44.dp)
                        .border(if (index == frames.size / 2) 2.dp else 0.dp, Color.White),
                )
            }
        }
    }
}

private fun time(ms: Long): String {
    val text = DateUtils.formatElapsedTime(ms / 1000)
    // Short clips need tenths of a second to trim precisely.
    return if (ms < 60_000) "$text.${(ms % 1000) / 100}" else text
}
