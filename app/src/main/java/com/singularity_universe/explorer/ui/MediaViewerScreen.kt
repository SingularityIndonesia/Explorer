package com.singularity_universe.explorer.ui

import android.app.Activity
import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    ImmersiveSystemBars(visible = chromeVisible)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            key = { items[it].path },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            if (item.kind == FileKind.Video) {
                VideoPage(
                    item = item,
                    isCurrent = pagerState.settledPage == page,
                    controlsVisible = chromeVisible,
                    onToggleControls = { chromeVisible = !chromeVisible },
                    onHideControls = { chromeVisible = false },
                )
            } else {
                ImagePage(item, onTap = { chromeVisible = !chromeVisible })
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.4f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ImagePage(item: FileItem, onTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
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
    isCurrent: Boolean,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onHideControls: () -> Unit,
) {
    val tapModifier = Modifier
        .fillMaxSize()
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onToggleControls)
    if (!isCurrent) {
        // Neighbouring pages only show a still; the player is created once the page settles.
        Box(tapModifier, contentAlignment = Alignment.Center) {
            AsyncImage(item.thumbnail(512), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
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
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3000)
            onHideControls()
        }
    }

    val presentation = rememberPresentationState(player)
    Box(tapModifier) {
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
