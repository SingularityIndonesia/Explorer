package com.singularity_universe.explorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhonelinkLock
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
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.remote.RemoteConnection.Status
import com.singularity_universe.explorer.remote.SshConnections
import com.singularity_universe.explorer.remote.SshHost
import com.singularity_universe.explorer.shortcutName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ExplorerViewModel) {
    val remote = LocalRemoteConnection.current
    val ssh = LocalSshConnections.current
    // null = closed; a host with an empty id = adding a new server.
    var editing by remember { mutableStateOf<SshHost?>(null) }
    var pickingShortcut by remember { mutableStateOf(false) }

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
                                item("Edit", Icons.Outlined.Edit) { editing = host }
                                item("Remove", Icons.Outlined.Delete) { ssh.remove(host.id) }
                            }
                        },
                        onClick = { vm.openServer(host.id) },
                    )
                }
                SettingsRow(
                    title = "Add SSH server",
                    subtitle = "Browse a computer or NAS over SSH (SFTP)",
                    leading = { Icon(Icons.Outlined.Add, null) },
                    onClick = { editing = SshHost("", "", "", 22, "", "") },
                )
            }

            Section("Folder shortcuts") {
                vm.shortcuts.forEach { path ->
                    SettingsRow(
                        title = shortcutName(vm, path),
                        subtitle = vm.displayLocation(path),
                        leading = { FolderIcon(onServer = vm.repo.isSsh(path)) },
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

    editing?.let { host ->
        SshServerDialog(
            initial = host,
            onDismiss = { editing = null },
            onSave = {
                ssh.save(it)
                editing = null
            },
        )
    }
}

@Composable
private fun SshServerDialog(initial: SshHost, onDismiss: () -> Unit, onSave: (SshHost) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var startPath by remember { mutableStateOf(initial.startPath) }
    var showPassword by remember { mutableStateOf(false) }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = host.isNotBlank() && username.isNotBlank() && portNumber != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isEmpty()) "Add SSH server" else "Edit SSH server") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialogField("Name (optional)", name) { name = it }
                DialogField("Host or IP address", host, KeyboardType.Uri) { host = it.trim() }
                DialogField("Port", port, KeyboardType.Number) { port = it.filter(Char::isDigit).take(5) }
                DialogField("Username", username) { username = it.trim() }
                OutlinedTextField(
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
                    "The password is encrypted with a key kept in this phone's secure hardware.",
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
                            name = name.trim().ifEmpty { host },
                            host = host,
                            port = portNumber ?: 22,
                            username = username,
                            password = password,
                            startPath = startPath,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

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
