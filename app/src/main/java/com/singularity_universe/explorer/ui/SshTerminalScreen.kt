package com.singularity_universe.explorer.ui

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.singularity_universe.explorer.remote.SshTerminal
import com.singularity_universe.explorer.terminal.ExtraKey
import com.singularity_universe.explorer.terminal.ExtraKeysSettings
import com.singularity_universe.explorer.terminal.KeysPosition
import com.singularity_universe.explorer.terminal.StickyModifiers
import com.singularity_universe.explorer.terminal.TerminalColors
import com.singularity_universe.explorer.terminal.TerminalView

private val TerminalBackground = Color(TerminalColors.BACKGROUND)
private val BarText = Color(0xFFE6E6E6)
private val BarTextDim = Color(0xFF9E9E9E)
// The keys sit on the terminal's own background; a quiet line marks where they start.
private val KeysBackground = TerminalBackground
private val Divider = Color(0xFF333333)
private val KEY_CELL = 64.dp

/**
 * An SSH terminal: the screen, a top bar (close, keyboard, paste) and a row of keys phones lack
 * (Esc, Tab, Ctrl, Alt, arrows...), which programs like nano need (Ctrl+X, Ctrl+O).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshTerminalScreen(
    name: String,
    /** The session on screen, and all of the window's sessions (tabs). */
    terminal: SshTerminal,
    sessions: List<SshTerminal>,
    current: Int,
    onSelectSession: (Int) -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: (Int) -> Unit,
    fontSize: Float,
    onFontSizeChanged: (Float) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    /** Asks for the battery exemption that keeps sessions alive in the background; null when granted. */
    onKeepConnected: (() -> Unit)?,
    /** The server's saved addresses (home Wi-Fi, VPN, ...), the one in use, and switching to another. */
    addresses: List<String>,
    activeAddress: Int,
    onSelectAddress: (Int) -> Unit,
) {
    // Switching route means a new connection: confirm before ending a live session.
    var switchTo by remember { mutableStateOf<Int?>(null) }
    val modifiers = remember { StickyModifiers() }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // The view is recreated when the keys move (side panel ↔ row): it keeps the current text size.
    var currentFontSize by remember { mutableFloatStateOf(fontSize) }
    val status = terminal.status
    val keysSettings = remember { ExtraKeysSettings(context) }
    var keysDialog by remember { mutableStateOf(false) }
    // A hardware keyboard has Esc, Ctrl and arrows: the extra keys step aside (unless turned off).
    val configuration = LocalConfiguration.current
    val hardwareKeyboard = configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    val showKeys = !(hardwareKeyboard && keysSettings.hideWithKeyboard)
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = TerminalBackground,
        topBar = {
            Column {
            TopAppBar(
                title = {
                    Column {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AddressSwitcher(
                                addresses,
                                activeAddress,
                                onSelect = { index ->
                                    if (status == SshTerminal.Status.Connected) switchTo = index else onSelectAddress(index)
                                },
                                color = BarTextDim,
                            )
                            Text(
                                " · " + when (status) {
                                    SshTerminal.Status.Connecting -> "Connecting…"
                                    SshTerminal.Status.Connected -> terminal.title ?: "Connected"
                                    is SshTerminal.Status.Ended -> status.message
                                    is SshTerminal.Status.Failed -> "Not connected"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = BarTextDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close terminal") }
                },
                actions = {
                    IconButton(onClick = { view?.toggleKeyboard() }) { Icon(Icons.Outlined.Keyboard, "Keyboard") }
                    IconButton(onClick = { clipboard.getText()?.text?.let { view?.paste(it) } }) {
                        Icon(Icons.Outlined.ContentPaste, "Paste")
                    }
                    OverflowMenu {
                        item("Copy screen", Icons.Outlined.ContentCopy) {
                            val text = synchronized(terminal.emulator) {
                                (0 until terminal.emulator.rows).joinToString("\n") { terminal.emulator.textOf(it) }.trimEnd()
                            }
                            clipboard.setText(AnnotatedString(text))
                        }
                        item("New session", Icons.Outlined.Add) { onNewSession() }
                        item("Reconnect", Icons.Outlined.Refresh) { terminal.reconnect() }
                        item("Extra keys…", Icons.Outlined.Tune) { keysDialog = true }
                        if (onKeepConnected != null) {
                            item("Stay connected in background", Icons.Outlined.BatteryAlert) { onKeepConnected() }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TerminalBackground,
                    titleContentColor = BarText,
                    navigationIconContentColor = BarText,
                    actionIconContentColor = BarText,
                ),
            )
            if (sessions.size > 1) SessionTabs(sessions, current, onSelectSession, onCloseSession)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            val press: (ExtraKey) -> Unit = { key ->
                when (key) {
                    is ExtraKey.Special -> view?.sendKey(key.keyCode)
                    is ExtraKey.Control -> view?.sendControl(key.letter)
                    is ExtraKey.Text -> view?.sendText(key.text)
                    is ExtraKey.Modifier -> if (key.id == "ctrl") modifiers.ctrl = !modifiers.ctrl else modifiers.alt = !modifiers.alt
                    ExtraKey.Gap -> Unit
                }
            }
            val terminalArea: @Composable (Modifier) -> Unit = { areaModifier ->
            Box(areaModifier) {
                AndroidView(
                    factory = { context ->
                        TerminalView(context, modifiers).apply {
                            this.fontSize = currentFontSize
                            this.terminal = terminal
                            this.onFontSizeChanged = {
                                currentFontSize = it
                                onFontSizeChanged(it)
                            }
                            view = this
                            // Ready to type as soon as the terminal opens.
                            post { showKeyboard() }
                        }
                    },
                    // Switching tabs shows another session in the same view.
                    update = { it.terminal = terminal },
                    modifier = Modifier.fillMaxSize(),
                )
                StatusOverlay(status, onReconnect = terminal::reconnect, onClose = onClose)
            }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val side = when {
                    !showKeys -> false
                    else -> when (keysSettings.position) {
                    KeysPosition.Side -> true
                    KeysPosition.Bottom -> false
                    // Tablets: a panel beside the terminal, easier to reach than a long row.
                    KeysPosition.Auto -> maxWidth >= 600.dp
                    }
                }
                if (side) {
                    Row(Modifier.fillMaxSize()) {
                        val panel: @Composable () -> Unit = {
                            KeysPanel(
                                keysSettings.keys,
                                keysSettings.columns,
                                modifiers,
                                press,
                                onSwapSide = { keysSettings.updateLeftHanded(!keysSettings.leftHanded) },
                                onSettings = { keysDialog = true },
                            )
                        }
                        if (keysSettings.leftHanded) {
                            panel()
                            VerticalDivider(color = Divider)
                        }
                        terminalArea(Modifier.weight(1f).fillMaxHeight())
                        if (!keysSettings.leftHanded) {
                            VerticalDivider(color = Divider)
                            panel()
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        terminalArea(Modifier.weight(1f).fillMaxWidth())
                        if (showKeys) {
                            HorizontalDivider(color = Divider)
                            KeysRow(keysSettings.keys, modifiers, keysSettings.leftHanded, press) { keysDialog = true }
                        }
                    }
                }
            }
            if (keysDialog) KeysSettingsDialog(keysSettings) { keysDialog = false }
        }
    }

    switchTo?.let { index ->
        AlertDialog(
            onDismissRequest = { switchTo = null },
            title = { Text("Reconnect through ${addresses.getOrElse(index) { "this address" }}?") },
            text = { Text("The current session ends, and a new one starts through this address.") },
            confirmButton = {
                TextButton(onClick = {
                    switchTo = null
                    onSelectAddress(index)
                }) { Text("Reconnect") }
            },
            dismissButton = { TextButton(onClick = { switchTo = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusOverlay(status: SshTerminal.Status, onReconnect: () -> Unit, onClose: () -> Unit) {
    when (status) {
        SshTerminal.Status.Connected -> Unit
        SshTerminal.Status.Connecting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BarText)
        }
        is SshTerminal.Status.Ended, is SshTerminal.Status.Failed -> Box(
            Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Column(Modifier.padding(20.dp).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (status is SshTerminal.Status.Failed) status.message else (status as SshTerminal.Status.Ended).message,
                        textAlign = TextAlign.Center,
                    )
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onClose) { Text("Close") }
                        Button(onClick = onReconnect) { Text("Reconnect") }
                    }
                }
            }
        }
    }
}

/**
 * Keys a phone keyboard lacks, under the terminal. They hug the right edge (the left edge when
 * left-handed), near the thumb, and scroll from there when there are many; ⚙ is at the far end.
 */
@Composable
private fun KeysRow(
    keys: List<ExtraKey>,
    modifiers: StickyModifiers,
    leftHanded: Boolean,
    onPress: (ExtraKey) -> Unit,
    onSettings: () -> Unit,
) {
    val settings: @Composable () -> Unit = {
        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Tune, "Customize keys", tint = BarTextDim) }
    }
    Row(
        Modifier.fillMaxWidth().background(KeysBackground).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!leftHanded) settings()
        Box(Modifier.weight(1f), contentAlignment = if (leftHanded) Alignment.CenterStart else Alignment.CenterEnd) {
            Row(
                // Reverse scrolling starts the row at its right end when it doesn't all fit.
                Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = !leftHanded).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                keys.forEach { key -> KeyButton(key, modifiers, onPress) }
            }
        }
        if (leftHanded) settings()
    }
}

/** The keys as a two-column panel beside the terminal (tablets), ⚙ at the bottom. */
@Composable
private fun KeysPanel(
    keys: List<ExtraKey>,
    columns: Int,
    modifiers: StickyModifiers,
    onPress: (ExtraKey) -> Unit,
    onSwapSide: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxHeight().width(KEY_CELL * columns + 6.dp * (columns + 1)).background(KeysBackground).padding(6.dp),
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            keys.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { key -> KeyButton(key, modifiers, onPress, Modifier.weight(1f).height(44.dp)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Row(Modifier.align(Alignment.CenterHorizontally)) {
            IconButton(onClick = onSwapSide) { Icon(Icons.Outlined.SwapHoriz, "Move panel to the other side", tint = BarTextDim) }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Tune, "Customize keys", tint = BarTextDim) }
        }
    }
}

@Composable
private fun KeyButton(key: ExtraKey, modifiers: StickyModifiers, onPress: (ExtraKey) -> Unit, modifier: Modifier = Modifier) {
    if (key == ExtraKey.Gap) {
        // An empty slot: takes a key's room, does nothing.
        Spacer(modifier.height(36.dp).widthIn(min = 44.dp))
        return
    }
    val active = key is ExtraKey.Modifier && (if (key.id == "ctrl") modifiers.ctrl else modifiers.alt)
    ExtraKey(key.label, active, modifier) { onPress(key) }
}

@Composable
private fun ExtraKey(label: String, active: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier
            .height(36.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else Color(0xFF3A3A3A))
            // A bare tap handler rather than clickable: keys never take focus from the terminal,
            // so the keyboard stays open.
            .pointerInput(Unit) {
                detectTapGestures {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentOnClick()
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) MaterialTheme.colorScheme.onPrimary else BarText,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/** Choose, order and place the extra keys. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeysSettingsDialog(settings: ExtraKeysSettings, onDismiss: () -> Unit) {
    val ids = settings.keys.map { it.id }
    var custom by remember { mutableStateOf("") }
    fun move(index: Int, by: Int) {
        val target = index + by
        if (target !in ids.indices) return
        settings.updateKeys(ids.toMutableList().apply { add(target, removeAt(index)) })
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 760.dp),
        ) {
            Column(Modifier.padding(vertical = 20.dp)) {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Extra keys", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = settings::reset) { Text("Reset") }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsTitle("Position")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        KeysPosition.entries.forEachIndexed { index, position ->
                            SegmentedButton(
                                selected = settings.position == position,
                                onClick = { settings.updatePosition(position) },
                                shape = SegmentedButtonDefaults.itemShape(index, KeysPosition.entries.size),
                                label = { Text(position.label) },
                            )
                        }
                    }
                    Text(
                        "Auto: a panel beside the terminal on tablets, a row under it on phones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsTitle("Side")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("Right" to false, "Left" to true).forEachIndexed { index, (label, left) ->
                            SegmentedButton(
                                selected = settings.leftHanded == left,
                                onClick = { settings.updateLeftHanded(left) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        "Which side the panel sits on, and which edge the row of keys starts from.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hide when a keyboard is connected", modifier = Modifier.weight(1f))
                        Switch(checked = settings.hideWithKeyboard, onCheckedChange = settings::updateHideWithKeyboard)
                    }

                    SettingsTitle("Side panel columns")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        (2..5).forEachIndexed { index, n ->
                            SegmentedButton(
                                selected = settings.columns == n,
                                onClick = { settings.updateColumns(n) },
                                shape = SegmentedButtonDefaults.itemShape(index, 4),
                                label = { Text("$n") },
                            )
                        }
                    }

                    SettingsTitle("Your keys")
                    if (ids.isEmpty()) {
                        Text("No keys. Add some below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    settings.keys.forEachIndexed { index, key ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (key == ExtraKey.Gap) {
                                Text(
                                    "Empty space",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Text(key.label, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            }
                            IconButton(onClick = { move(index, -1) }, enabled = index > 0) {
                                Icon(Icons.Outlined.KeyboardArrowUp, "Move up")
                            }
                            IconButton(onClick = { move(index, 1) }, enabled = index < ids.lastIndex) {
                                Icon(Icons.Outlined.KeyboardArrowDown, "Move down")
                            }
                            IconButton(onClick = { settings.updateKeys(ids.toMutableList().apply { removeAt(index) }) }) {
                                Icon(Icons.Outlined.Close, "Remove")
                            }
                        }
                    }
                    TextButton(onClick = { settings.updateKeys(ids + ExtraKey.Gap.id) }) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add empty space")
                    }

                    SettingsTitle("Add keys")
                    ExtraKey.catalog.forEach { (group, keys) ->
                        Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            keys.forEach { key ->
                                val chosen = key.id in ids
                                FilterChip(
                                    selected = chosen,
                                    onClick = { settings.updateKeys(if (chosen) ids - key.id else ids + key.id) },
                                    label = { Text(key.label, fontFamily = FontFamily.Monospace) },
                                )
                            }
                        }
                    }

                    SettingsTitle("Custom key")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = custom,
                            onValueChange = { custom = it.take(24) },
                            placeholder = { Text("A character or text, e.g. sudo ") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                settings.updateKeys(ids + ExtraKey.Text(custom).id)
                                custom = ""
                            },
                            enabled = custom.isNotEmpty(),
                        ) { Text("Add") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** One tab per session: its number and what the shell is doing, with a close button. */
@Composable
private fun SessionTabs(sessions: List<SshTerminal>, current: Int, onSelect: (Int) -> Unit, onClose: (Int) -> Unit) {
    PrimaryScrollableTabRow(
        selectedTabIndex = current.coerceIn(0, sessions.lastIndex),
        containerColor = TerminalBackground,
        contentColor = BarText,
        edgePadding = 8.dp,
    ) {
        sessions.forEachIndexed { index, session ->
            Tab(
                selected = index == current,
                onClick = { onSelect(index) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val label = when (val status = session.status) {
                            SshTerminal.Status.Connected -> session.title ?: "Session ${index + 1}"
                            SshTerminal.Status.Connecting -> "Connecting…"
                            is SshTerminal.Status.Ended -> status.message
                            is SshTerminal.Status.Failed -> "Not connected"
                        }
                        Text("${index + 1} · $label", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                        Icon(
                            Icons.Filled.Close,
                            "Close session ${index + 1}",
                            Modifier.padding(start = 6.dp).size(16.dp).clickable { onClose(index) },
                            tint = BarTextDim,
                        )
                    }
                },
            )
        }
    }
}

