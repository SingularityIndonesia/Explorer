package com.singularity_universe.explorer.ui

import android.app.Activity
import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.data.FileKind
import com.singularity_universe.explorer.data.thumbnail
import kotlinx.coroutines.delay

/** Full-screen viewer: swipe left/right through [items]; images are shown, videos play. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(vm: ExplorerViewModel, items: List<FileItem>, startIndex: Int) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    // While the ⋮ menu is open the controls must not auto-hide (that would close the menu).
    var menuOpen by remember { mutableStateOf(false) }
    ImmersiveSystemBars(visible = chromeVisible)
    // One zoom per page; while the current page is zoomed, drags pan it instead of swiping pages.
    val zoomStates = remember { mutableStateMapOf<Int, ZoomState>() }
    val currentZoomed = zoomStates[pagerState.currentPage]?.isZoomed == true

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            key = { items[it].path },
            beyondViewportPageCount = 1,
            userScrollEnabled = !currentZoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            val zoom = zoomStates.getOrPut(page) { ZoomState() }
            // Coming back to a page shows it unzoomed.
            LaunchedEffect(pagerState.settledPage) {
                if (pagerState.settledPage != page) zoom.reset()
            }
            if (item.kind == FileKind.Video) {
                VideoPage(
                    item = item,
                    zoom = zoom,
                    isCurrent = pagerState.settledPage == page,
                    controlsVisible = chromeVisible,
                    holdControls = menuOpen,
                    onToggleControls = { chromeVisible = !chromeVisible },
                    onHideControls = { chromeVisible = false },
                )
            } else {
                ImagePage(item, zoom, onTap = { chromeVisible = !chromeVisible })
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val current = items[pagerState.currentPage]
            TopAppBar(
                title = {
                    Column {
                        Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (items.size > 1) {
                            Text(
                                "${pagerState.currentPage + 1} of ${items.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    OverflowMenu(onExpandedChange = { menuOpen = it }) {
                        item("Locate", Icons.Outlined.FolderOpen) { vm.locate(current) }
                        item("Copy to…", Icons.Outlined.ContentCopy) { vm.startTransfer(listOf(current.path), move = false) }
                        item("Move to…", Icons.AutoMirrored.Outlined.DriveFileMove) {
                            vm.startTransfer(listOf(current.path), move = true)
                        }
                        item("Open with", Icons.AutoMirrored.Outlined.OpenInNew) {
                            // Other apps can't read files on a server or in Android/data: hand them a copy.
                            if (vm.repo.needsCopyToOpen(current.path)) vm.openRestricted(current) { context.openFile(it) }
                            else context.openFile(current)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.4f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ImagePage(item: FileItem, zoom: ZoomState, onTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .zoomable(zoom, onTap)
            .zoomed(zoom),
    ) {
        // The cached thumbnail shows instantly while the full-resolution image decodes on top.
        AsyncImage(
            model = item.thumbnail(512),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(item.file).size(2560).build(),
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    item: FileItem,
    zoom: ZoomState,
    isCurrent: Boolean,
    controlsVisible: Boolean,
    holdControls: Boolean,
    onToggleControls: () -> Unit,
    onHideControls: () -> Unit,
) {
    val tapModifier = Modifier
        .fillMaxSize()
        .zoomable(zoom, onToggleControls)
    if (!isCurrent) {
        // Neighbouring pages only show a still; the player is created once the page settles.
        Box(tapModifier, contentAlignment = Alignment.Center) {
            AsyncImage(item.thumbnail(512), null, Modifier.fillMaxSize().zoomed(zoom), contentScale = ContentScale.Fit)
            Icon(Icons.Outlined.PlayCircle, null, Modifier.size(72.dp), tint = Color.White)
        }
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(item.path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(item.file.toURI().toString()))
            prepare()
            playWhenReady = true
        }
    }
    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }
    // Like most players, hide the controls a few seconds after playback starts.
    LaunchedEffect(controlsVisible, isPlaying, holdControls) {
        if (controlsVisible && isPlaying && !holdControls) {
            delay(3000)
            onHideControls()
        }
    }

    // Double-tap the left or right third to jump 5 s, like most video players; the middle zooms.
    var seekHint by remember { mutableStateOf<SeekHint?>(null) }
    val onDoubleTap = remember(player) {
        { position: Offset, size: IntSize ->
            val forward = when {
                position.x < size.width / 3f -> false
                position.x > size.width * 2 / 3f -> true
                else -> null
            }
            if (forward != null) {
                val target = player.currentPosition + if (forward) SEEK_STEP_MS else -SEEK_STEP_MS
                val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                player.seekTo(target.coerceIn(0L, end))
                seekHint = SeekHint(forward, System.nanoTime())
            }
            forward != null
        }
    }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(700)
            seekHint = null
        }
    }

    val presentation = rememberPresentationState(player)
    Box(Modifier.fillMaxSize().zoomable(zoom, onToggleControls, onDoubleTap)) {
        // Only the picture zooms; the controls below stay where they are.
        Box(Modifier.fillMaxSize().zoomed(zoom)) {
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier
                    .resizeWithContentScale(ContentScale.Fit, presentation.videoSizeDp)
                    .align(Alignment.Center),
            )
            if (presentation.coverSurface) {
                AsyncImage(item.thumbnail(512), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }

        SeekBubble(visible = seekHint?.forward == false, forward = false, Modifier.align(Alignment.CenterStart))
        SeekBubble(visible = seekHint?.forward == true, forward = true, Modifier.align(Alignment.CenterEnd))

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            IconButton(
                onClick = {
                    when {
                        player.isPlaying -> player.pause()
                        player.playbackState == Player.STATE_ENDED -> {
                            player.seekTo(0)
                            player.play()
                        }
                        else -> player.play()
                    }
                },
                modifier = Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) "Pause" else "Play",
                    Modifier.size(44.dp),
                    tint = Color.White,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shown = if (seeking) seekPosition.toLong() else position
                Text(formatTime(shown), color = Color.White, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = shown.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    onValueChange = {
                        seeking = true
                        seekPosition = it
                    },
                    onValueChangeFinished = {
                        player.seekTo(seekPosition.toLong())
                        seeking = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private const val SEEK_STEP_MS = 5_000L

/** The last double-tap seek, shown briefly on its side. [id] lets repeated taps restart the timer. */
private data class SeekHint(val forward: Boolean, val id: Long)

@Composable
private fun SeekBubble(visible: Boolean, forward: Boolean, modifier: Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier.padding(horizontal = 40.dp)) {
        Row(
            Modifier
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!forward) Icon(Icons.Filled.FastRewind, null, tint = Color.White)
            Text(
                "${SEEK_STEP_MS / 1000}s",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            if (forward) Icon(Icons.Filled.FastForward, null, tint = Color.White)
        }
    }
}

private fun formatTime(millis: Long) = DateUtils.formatElapsedTime(millis / 1000)

/** The viewer is always dark: light bar icons, and the system bars hide along with the controls. */
@Composable
private fun ImmersiveSystemBars(visible: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, view) }
    DisposableEffect(controller) {
        val wasLightStatus = controller.isAppearanceLightStatusBars
        val wasLightNav = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = wasLightStatus
            controller.isAppearanceLightNavigationBars = wasLightNav
        }
    }
    LaunchedEffect(visible) {
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
