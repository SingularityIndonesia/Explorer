package com.singularity_universe.explorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.RemoteControlActivity
import com.singularity_universe.explorer.SSHTerminalActivity
import com.singularity_universe.explorer.remote.AdbDevice
import com.singularity_universe.explorer.remote.AdbDevices
import com.singularity_universe.explorer.remote.Endpoint
import com.singularity_universe.explorer.remote.RemoteConnection.Status
import com.singularity_universe.explorer.remote.SshConnections
import com.singularity_universe.explorer.remote.SshHost
import com.singularity_universe.explorer.remote.SshKey
import com.singularity_universe.explorer.shortcutName
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ExplorerViewModel) {
    val context = LocalContext.current
    val remote = LocalRemoteConnection.current
    val ssh = LocalSshConnections.current
    // null = closed; a host with an empty id = adding a new server.
    var editing by remember { mutableStateOf<SshHost?>(null) }
    var showKeys by remember { mutableStateOf(false) }
    var installingOn by remember { mutableStateOf<SshHost?>(null) }
    var forgetting by remember { mutableStateOf<SshHost?>(null) }
    var pickingShortcut by remember { mutableStateOf(false) }
    val adb = LocalAdbDevices.current
    var editingDevice by remember { mutableStateOf<AdbDevice?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Remote access") {
                SettingsRow(
                    title = "This phone",
                    subtitle = "Wireless debugging · ${remote.status.label()} — opens Android/data and Android/obb",
                    leading = { Icon(Icons.Outlined.PhonelinkLock, null) },
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    onClick = { vm.navigate(Route.Remote) },
                )
                ssh.hosts.forEach { host ->
                    SettingsRow(
                        title = host.name,
                        subtitle = "SSH · ${host.address} · ${ssh.status[host.id].label()}",
                        leading = { Icon(Icons.Outlined.Dns, null) },
                        trailing = {
                            OverflowMenu {
                                item("Terminal", Icons.Outlined.Terminal) { SSHTerminalActivity.start(context, host.id) }
                                item("Edit", Icons.Outlined.Edit) { editing = host }
                                item("Install SSH key…", Icons.Outlined.Key) {
                                    if (ssh.keys.isEmpty()) showKeys = true else installingOn = host
                                }
                                item("Forget server identity", Icons.Outlined.GppMaybe) { forgetting = host }
                                item("Remove", Icons.Outlined.Delete) { ssh.remove(host.id) }
                            }
                        },
                        onClick = { vm.openServer(host.id) },
                    )
                }
                adb.devices.forEach { device ->
                    SettingsRow(
                        title = device.name,
                        subtitle = "Android device · ${device.address} · ${adb.status[device.id].label()}",
                        leading = { Icon(Icons.Outlined.Devices, null) },
                        trailing = {
                            OverflowMenu {
                                item("Remote control", Icons.AutoMirrored.Outlined.ScreenShare) {
                                    RemoteControlActivity.start(context, device.id)
                                }
                                item("Edit / pair", Icons.Outlined.Edit) { editingDevice = device }
                                item("Remove", Icons.Outlined.Delete) { adb.remove(device.id) }
                            }
                        },
                        onClick = { vm.openDevice(device.id) },
                    )
                }
                SettingsRow(
                    title = "Add SSH server",
                    subtitle = "Browse a computer or NAS over SSH (SFTP)",
                    leading = { Icon(Icons.Outlined.Add, null) },
                    onClick = { editing = SshHost("", "", emptyList(), 22, "", "") },
                )
                SettingsRow(
                    title = "SSH keys",
                    subtitle = if (ssh.keys.isEmpty()) "Sign in to servers without a password"
                    else "${ssh.keys.size} ${if (ssh.keys.size == 1) "key" else "keys"} · generate, import, copy the public key",
                    leading = { Icon(Icons.Outlined.Key, null) },
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    onClick = { showKeys = true },
                )
                SettingsRow(
                    title = "Add Android device",
                    subtitle = "Browse another phone, tablet or TV over its Wireless debugging",
                    leading = { Icon(Icons.Outlined.Add, null) },
                    onClick = { editingDevice = AdbDevice("", "", emptyList(), 0) },
                )
            }

            Section("Folder shortcuts") {
                vm.shortcuts.forEach { path ->
                    SettingsRow(
                        title = shortcutName(vm, path),
                        subtitle = vm.displayLocation(path),
                        leading = { FolderIcon(onServer = vm.repo.isRemote(path)) },
                        trailing = {
                            IconButton(onClick = { vm.toggleShortcut(path) }) {
                                Icon(Icons.Outlined.BookmarkRemove, "Remove shortcut")
                            }
                        },
                        onClick = { vm.navigate(Route.Folder(path)) },
                    )
                }
                SettingsRow(
                    title = "Add shortcut",
                    subtitle = "Pin a folder from this phone or a server to the drawer",
                    leading = { Icon(Icons.Outlined.Add, null) },
                    onClick = { pickingShortcut = true },
                )
            }

            Section("Browsing") {
                SettingsRow(
                    title = "Show hidden files",
                    subtitle = "Files and folders whose name starts with a dot",
                    leading = { Icon(Icons.Outlined.Visibility, null) },
                    trailing = { Switch(checked = vm.showHidden, onCheckedChange = vm::updateShowHidden) },
                    onClick = { vm.updateShowHidden(!vm.showHidden) },
                )
            }
        }
    }

    if (pickingShortcut) {
        FolderPickerDialog(
            vm = vm,
            exclude = vm.shortcuts,
            onDismiss = { pickingShortcut = false },
            onPick = {
                vm.addShortcut(it)
                pickingShortcut = false
            },
        )
    }

    editingDevice?.let { device ->
        AdbDeviceDialog(
            initial = device,
            onDismiss = { editingDevice = null },
            onSave = { updated, pairingPort, code ->
                val saved = adb.save(updated)
                editingDevice = null
                if (pairingPort != null && code.isNotBlank()) {
                    scope.launch {
                        adb.pair(saved.id, pairingPort, code)
                            .onSuccess { vm.message("Paired with ${saved.name}") }
                            .onFailure { vm.message(it.message ?: "Pairing failed") }
                    }
                }
            },
        )
    }

    editing?.let { host ->
        SshServerDialog(
            initial = host,
            keys = ssh.keys,
            onManageKeys = { showKeys = true },
            onDismiss = { editing = null },
            onSave = {
                ssh.save(it)
                editing = null
            },
        )
    }
    if (showKeys) SshKeysDialog(ssh, onDismiss = { showKeys = false })
    installingOn?.let { host ->
        PickKeyDialog(
            title = "Install a key on ${host.name}",
            text = "Adds the key to ~/.ssh/authorized_keys on the server (signing in as now), then uses it to sign in.",
            keys = ssh.keys,
            onDismiss = { installingOn = null },
        ) { key ->
            installingOn = null
            vm.runOperation("${host.name} now signs in with ${key.name}") { ssh.installKey(host.id, key) }
        }
    }
    forgetting?.let { host ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${host.name}'s identity?") },
            text = {
                Text(
                    "Explorer remembers each server's identity and refuses to connect if it changes, which " +
                        "could mean someone is impersonating it. Forget it only if you know the server was " +
                        "reinstalled or its keys were changed; the next connection then trusts it again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ssh.forgetHostKeys(host.id)
                    forgetting = null
                    vm.message("Forgot ${host.name}'s identity")
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SshServerDialog(
    initial: SshHost,
    keys: List<SshKey>,
    onManageKeys: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (SshHost) -> Unit,
) {
    var keyId by remember { mutableStateOf(initial.keyId?.takeIf { id -> keys.any { it.id == id } }) }
    var useKey by remember { mutableStateOf(keyId != null) }
    var name by remember { mutableStateOf(initial.name) }
    var addresses by remember { mutableStateOf(initial.hosts.joinToString("\n")) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var startPath by remember { mutableStateOf(initial.startPath) }
    var showPassword by remember { mutableStateOf(false) }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val hosts = Endpoint.parseLines(addresses)
    val valid = hosts.isNotEmpty() && username.isNotBlank() && portNumber != null && (!useKey || keyId != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isEmpty()) "Add SSH server" else "Edit SSH server") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialogField("Name (optional)", name) { name = it }
                AddressesField(addresses) { addresses = it }
                DialogField("Port", port, KeyboardType.Number) { port = it.filter(Char::isDigit).take(5) }
                DialogField("Username", username) { username = it.trim() }
                Text("Sign in with", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Password" to false, "SSH key" to true).forEachIndexed { index, (label, key) ->
                        SegmentedButton(
                            selected = useKey == key,
                            onClick = {
                                useKey = key
                                if (key && keyId == null) keyId = keys.firstOrNull()?.id
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            label = { Text(label) },
                        )
                    }
                }
                if (useKey) {
                    if (keys.isEmpty()) {
                        TextButton(onClick = onManageKeys) { Text("Generate or import a key first") }
                    }
                    keys.forEach { key ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = keyId == key.id, onClick = { keyId = key.id })
                            Column(Modifier.weight(1f)) {
                                Text(key.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(key.type.removePrefix("ssh-"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                DialogField("Start folder (optional)", startPath, KeyboardType.Uri) { startPath = it.trim() }
                Text(
                    "Passwords and keys are encrypted with a key kept in this phone's secure hardware.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifEmpty { Endpoint.parse(hosts.first())?.host ?: hosts.first() },
                            hosts = hosts,
                            activeHost = keepActive(initial.hosts, initial.activeHost, hosts),
                            port = portNumber ?: 22,
                            username = username,
                            password = password,
                            startPath = startPath,
                            keyId = if (useKey) keyId else null,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AdbDeviceDialog(
    initial: AdbDevice,
    onDismiss: () -> Unit,
    onSave: (device: AdbDevice, pairingPort: Int?, pairingCode: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var addresses by remember { mutableStateOf(initial.hosts.joinToString("\n")) }
    var port by remember { mutableStateOf(if (initial.port > 0) initial.port.toString() else "") }
    var pairingCode by remember { mutableStateOf("") }
    var pairingPort by remember { mutableStateOf("") }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val pairingPortNumber = pairingPort.toIntOrNull()?.takeIf { it in 1..65535 }
    // Pairing is optional (only the first time), but if started it needs both the code and its port.
    val pairingValid = pairingCode.isBlank() || (pairingCode.length == 6 && pairingPortNumber != null)
    val hosts = Endpoint.parseLines(addresses)
    val valid = hosts.isNotEmpty() && portNumber != null && pairingValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isEmpty()) "Add Android device" else "Edit Android device") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "On the other device: Settings › Developer options › Wireless debugging.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DialogField("Name (optional)", name) { name = it }
                AddressesField(addresses) { addresses = it }
                DialogField("Port (under \"IP address & Port\")", port, KeyboardType.Number) {
                    port = it.filter(Char::isDigit).take(5)
                }
                Text(
                    "First time only — tap \"Pair device with pairing code\" there:",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                DialogField("Pairing code", pairingCode, KeyboardType.Number) {
                    pairingCode = it.filter(Char::isDigit).take(6)
                }
                DialogField("Pairing port (from the pairing dialog)", pairingPort, KeyboardType.Number) {
                    pairingPort = it.filter(Char::isDigit).take(5)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifEmpty { Endpoint.parse(hosts.first())?.host ?: hosts.first() },
                            hosts = hosts,
                            activeHost = keepActive(initial.hosts, initial.activeHost, hosts),
                            port = portNumber ?: 0,
                        ),
                        pairingPortNumber,
                        pairingCode,
                    )
                },
            ) { Text(if (pairingCode.isNotBlank()) "Pair & save" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Addresses, one per line; a line may add its own port (`host:port`). */
@Composable
private fun AddressesField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Addresses (one per line)") },
        supportingText = { Text("e.g. home Wi-Fi IP and VPN IP. Add :port if a route uses another port.") },
        minLines = 2,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** After editing the address list, keep using the same address if it's still there. */
private fun keepActive(oldHosts: List<String>, oldActive: Int, newHosts: List<String>): Int =
    oldHosts.getOrNull(oldActive)?.let { newHosts.indexOf(it) }?.takeIf { it >= 0 } ?: 0

@Composable
private fun DialogField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun AdbDevices.Status?.label(): String = when (this) {
    null, AdbDevices.Status.Idle -> "Not connected"
    AdbDevices.Status.Connecting -> "Connecting…"
    AdbDevices.Status.Connected -> "Connected"
    is AdbDevices.Status.Failed -> message
}

private fun SshConnections.Status?.label(): String = when (this) {
    null, SshConnections.Status.Idle -> "Not connected"
    SshConnections.Status.Connecting -> "Connecting…"
    SshConnections.Status.Connected -> "Connected"
    is SshConnections.Status.Failed -> message
}

private fun Status.label(): String = when (this) {
    Status.Connected -> "Connected"
    Status.Connecting -> "Connecting…"
    Status.Disconnected -> "Disconnected"
    Status.DebuggingOff -> "Wireless debugging off"
    Status.NotPaired -> "Not set up"
    is Status.Failed -> "Couldn't connect"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) { content() }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    leading: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = leading,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
