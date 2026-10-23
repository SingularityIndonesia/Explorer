package com.singularity_universe.explorer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.remote.SshConnections
import com.singularity_universe.explorer.remote.SshKey
import com.singularity_universe.explorer.remote.SshKeyTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saved SSH keys: copy or share their public key, delete them, generate or import more. */
@Composable
fun SshKeysDialog(ssh: SshConnections, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var generating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SshKey?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Key, null) },
        title = { Text("SSH keys") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Sign in to servers with a key instead of a password. Add a key's public part to the " +
                        "server's ~/.ssh/authorized_keys, or use Install SSH key in the server's menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                if (ssh.keys.isEmpty()) {
                    Text("No keys yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                }
                ssh.keys.forEach { key ->
                    ListItem(
                        headlineContent = { Text(key.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                "${key.type.removePrefix("ssh-")} · ${key.fingerprint}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { copyPublicKey(context, key) }) { Icon(Icons.Outlined.ContentCopy, "Copy public key") }
                                IconButton(onClick = { sharePublicKey(context, key) }) { Icon(Icons.Outlined.Share, "Share public key") }
                                IconButton(onClick = { deleting = key }) { Icon(Icons.Outlined.Delete, "Delete key") }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                    HorizontalDivider()
                }
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { generating = true }) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Generate")
                    }
                    TextButton(onClick = { importing = true }) {
                        Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    if (generating) GenerateKeyDialog(onDismiss = { generating = false }) { ssh.addKey(it); generating = false }
    if (importing) ImportKeyDialog(onDismiss = { importing = false }) { ssh.addKey(it); importing = false }
    deleting?.let { key ->
        val users = ssh.hosts.filter { it.keyId == key.id }.joinToString { it.name }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${key.name}?") },
            text = {
                Text(
                    "The key is removed from this phone." +
                        if (users.isNotEmpty()) " $users will sign in with the password again." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ssh.removeKey(key.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun copyPublicKey(context: android.content.Context, key: SshKey) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Public key", key.publicKey))
    Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
}

private fun sharePublicKey(context: android.content.Context, key: SshKey) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key.publicKey)
    context.startActivity(Intent.createChooser(send, "Public key"))
}

@Composable
private fun GenerateKeyDialog(onDismiss: () -> Unit, onGenerated: (SshKey) -> Unit) {
    var name by remember { mutableStateOf("Explorer on ${Build.MODEL}") }
    var kind by remember { mutableStateOf(SshKeyTools.Kind.Ed25519) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Generate a key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SshKeyTools.Kind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                    }
                }
                Text(
                    if (kind == SshKeyTools.Kind.Ed25519) "Recommended: short and fast. Supported by OpenSSH 6.5 and later."
                    else "For older servers that don't support Ed25519.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (working) {
                CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = {
                    working = true
                    error = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.Default) { SshKeyTools.generate(name.trim(), kind) } }
                            .onSuccess(onGenerated)
                            .onFailure { error = it.message ?: "Couldn't generate the key" }
                        working = false
                    }
                }) { Text("Generate") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } },
    )
}

@Composable
private fun ImportKeyDialog(onDismiss: () -> Unit, onImported: (SshKey) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input -> input.readNBytes(64 * 1024).decodeToString() }
                }.getOrNull()
            }
            if (read == null) {
                error = "Couldn't read that file"
            } else {
                text = read
                if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Private key") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----", fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
                TextButton(onClick = { pick.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Choose a key file")
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase (if the key has one)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.Default) { SshKeyTools.import(name.trim(), text, passphrase) } }
                            .onSuccess(onImported)
                            .onFailure { error = it.message ?: "Couldn't import the key" }
                    }
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks one of the saved keys (e.g. to install on a server). */
@Composable
fun PickKeyDialog(title: String, text: String, keys: List<SshKey>, onDismiss: () -> Unit, onPick: (SshKey) -> Unit) {
    var selected by remember { mutableStateOf(keys.firstOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                keys.forEach { key ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = selected?.id == key.id, onClick = { selected = key })
                        Column {
                            Text(key.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(key.type.removePrefix("ssh-"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { selected?.let(onPick) }, enabled = selected != null) { Text("Install") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
