package com.singularity_universe.explorer.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.singularity_universe.explorer.audio.AudioPlayback
import kotlinx.coroutines.delay

/** The playing song along the bottom: tap for Now playing, play/pause, next, close. */
@Composable
fun AudioMiniPlayer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Shows what's playing in the background when the app is reopened.
    LaunchedEffect(Unit) { AudioPlayback.connect(context) }
    val now = AudioPlayback.nowPlaying ?: return
    var sheet by remember { mutableStateOf(false) }
    val position = rememberPosition(now.playing)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Column(Modifier.clickable { sheet = true }) {
            Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(now, Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(now.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    now.artist?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = AudioPlayback::togglePlay) {
                    Icon(if (now.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (now.playing) "Pause" else "Play")
                }
                if (now.count > 1) IconButton(onClick = AudioPlayback::next) { Icon(Icons.Filled.SkipNext, "Next") }
                IconButton(onClick = AudioPlayback::stop) { Icon(Icons.Filled.Close, "Stop") }
            }
            LinearProgressIndicator(
                progress = { if (now.duration > 0) (position.toFloat() / now.duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
            )
        }
    }
    if (sheet) NowPlayingSheet(onDismiss = { sheet = false })
}

/** The full player: cover, seek bar, previous / play / next, shuffle and repeat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingSheet(onDismiss: () -> Unit) {
    val now = AudioPlayback.nowPlaying
    if (now == null) {
        // Playback was stopped from elsewhere (e.g. the notification).
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val position = rememberPosition(now.playing)
    var seeking by remember { mutableStateOf(false) }
    var seekTo by remember { mutableFloatStateOf(0f) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Artwork(now, Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.size(20.dp))
            Text(now.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            now.artist?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            if (now.count > 1) {
                Text("${now.index + 1} of ${now.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(12.dp))
            val shown = if (seeking) seekTo.toLong() else position
            Slider(
                value = shown.toFloat().coerceIn(0f, now.duration.toFloat().coerceAtLeast(1f)),
                valueRange = 0f..now.duration.toFloat().coerceAtLeast(1f),
                onValueChange = {
                    seeking = true
                    seekTo = it
                },
                onValueChangeFinished = {
                    AudioPlayback.seekTo(seekTo.toLong())
                    seeking = false
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(DateUtils.formatElapsedTime(shown / 1000), style = MaterialTheme.typography.labelMedium)
                Text(DateUtils.formatElapsedTime(now.duration / 1000), style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = AudioPlayback::toggleShuffle) {
                    Icon(
                        Icons.Filled.Shuffle, "Shuffle",
                        tint = if (now.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = AudioPlayback::previous) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(32.dp)) }
                FilledIconButton(onClick = AudioPlayback::togglePlay, modifier = Modifier.size(64.dp)) {
                    Icon(if (now.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (now.playing) "Pause" else "Play", Modifier.size(36.dp))
                }
                IconButton(onClick = AudioPlayback::next) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(32.dp)) }
                IconButton(onClick = AudioPlayback::cycleRepeat) {
                    Icon(
                        if (now.repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        when (now.repeat) {
                            Player.REPEAT_MODE_ALL -> "Repeat all"
                            Player.REPEAT_MODE_ONE -> "Repeat one"
                            else -> "Repeat off"
                        },
                        tint = if (now.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Artwork(now: AudioPlayback.NowPlaying, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val art = now.artwork
        if (art != null) {
            Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** The playback position, refreshed while playing. */
@Composable
private fun rememberPosition(playing: Boolean): Long {
    var position by remember { mutableLongStateOf(AudioPlayback.position()) }
    LaunchedEffect(playing) {
        position = AudioPlayback.position()
        while (playing) {
            delay(500)
            position = AudioPlayback.position()
        }
    }
    return position
}
