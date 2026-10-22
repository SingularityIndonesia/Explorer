package com.singularity_universe.explorer.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.singularity_universe.explorer.remote.ScrcpySession
import com.singularity_universe.explorer.remote.ScrcpySession.Companion.ACTION_DOWN
import com.singularity_universe.explorer.remote.ScrcpySession.Companion.ACTION_MOVE
import com.singularity_universe.explorer.remote.ScrcpySession.Companion.ACTION_UP

/**
 * Shows another Android device's screen and forwards touches and keys to it. Hosted by
 * RemoteControlActivity, which owns [session]. In picture-in-picture only the video shows; it stays
 * in the same place in the layout, so entering or leaving PiP doesn't restart the stream.
 */
@Composable
fun RemoteControlScreen(
    name: String,
    session: ScrcpySession,
    allowed: Boolean,
    inPictureInPicture: Boolean,
    addresses: List<String>,
    activeAddress: Int,
    onSelectAddress: (Int) -> Unit,
    onAllow: () -> Unit,
    onClose: () -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    val chrome = !inPictureInPicture

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(if (chrome) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier),
    ) {
        if (chrome) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close remote control", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    AddressSwitcher(addresses, activeAddress, onSelect = onSelectAddress, color = Color.White.copy(alpha = 0.7f))
                    if (allowed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(Color(0xFFE53935), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Remote control active",
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
        if (!allowed) {
            Consent(name, onAllow)
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            RemoteVideo(session, interactive = chrome)
            when (val state = session.state) {
                ScrcpySession.State.Starting -> CircularProgressIndicator(color = Color.White)
                is ScrcpySession.State.Failed ->
                    Text(state.message, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                else -> Unit
            }
        }
        if (chrome) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { session.key(ScrcpySession.KEYCODE_BACK) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back on device", tint = Color.White)
                }
                IconButton(onClick = { session.key(ScrcpySession.KEYCODE_HOME) }) {
                    Icon(Icons.Outlined.Circle, "Home", tint = Color.White)
                }
                IconButton(onClick = { session.key(ScrcpySession.KEYCODE_APP_SWITCH) }) {
                    Icon(Icons.Outlined.CropSquare, "Recent apps", tint = Color.White)
                }
                IconButton(onClick = { typing = true }) {
                    Icon(Icons.Outlined.Keyboard, "Type", tint = Color.White)
                }
            }
        }
    }

    if (typing && chrome) TypeDialog(session, onDismiss = { typing = false })
}

@Composable
private fun Consent(name: String, onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ScreenShare, null, Modifier.size(56.dp), tint = Color.White)
        Spacer(Modifier.height(16.dp))
        Text("Control $name from here?", color = Color.White, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "You'll see its screen and your touches and typing will act on it. It runs only while this " +
                "screen is open, over this device's own Wireless debugging connection — nothing is installed " +
                "on it and nothing goes over the internet.",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAllow) { Text("Start remote control") }
    }
}

/** The device's screen at its aspect ratio; with [interactive], touches are forwarded to it. */
@Composable
private fun RemoteVideo(session: ScrcpySession, interactive: Boolean) {
    val size = session.videoSize
    val frame = if (size != null) Modifier.aspectRatio(size.first.toFloat() / size.second) else Modifier.fillMaxSize()
    Box(frame) {
        // The decoder draws straight onto this surface. It only exists while the window is visible
        // (full screen, split screen or picture-in-picture), so the stream runs exactly then.
        AndroidView(
            factory = {
                SurfaceView(it).apply {
                    keepScreenOn = true
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = session.start(holder.surface)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) = session.stop()
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Touch layer: maps fingers from this view onto the device's screen.
        if (interactive) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(session) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val (width, height) = session.videoSize ?: continue
                                event.changes.forEach { change ->
                                    val action = when {
                                        change.changedToDown() -> ACTION_DOWN
                                        change.changedToUp() -> ACTION_UP
                                        change.pressed && change.positionChanged() -> ACTION_MOVE
                                        else -> null
                                    } ?: return@forEach
                                    val x = (change.position.x / this.size.width * width).toInt().coerceIn(0, width - 1)
                                    val y = (change.position.y / this.size.height * height).toInt().coerceIn(0, height - 1)
                                    session.touch(action, change.id.value, x, y)
                                    change.consume()
                                }
                            }
                        }
                    },
            )
        }
    }
}

/** Types into the focused field on the device: text, backspace and enter. */
@Composable
private fun TypeDialog(session: ScrcpySession, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    fun send() {
        if (value.isNotEmpty()) session.text(value)
        value = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type on the device") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    placeholder = { Text("Text to send") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    IconButton(onClick = { session.key(ScrcpySession.KEYCODE_DEL) }) { Icon(Icons.AutoMirrored.Outlined.Backspace, "Backspace") }
                    IconButton(onClick = { session.key(ScrcpySession.KEYCODE_ENTER) }) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardReturn, "Enter")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::send) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
