package com.singularity_universe.explorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.remote.SshPath
import java.io.File
import kotlinx.coroutines.launch

/**
 * Lets the user walk to a folder (on this phone or a saved SSH server) and pick it.
 * Folders already in [exclude] can be browsed through but not picked again.
 */
@Composable
fun FolderPickerDialog(
    vm: ExplorerViewModel,
    exclude: Collection<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val ssh = LocalSshConnections.current
    val scope = rememberCoroutineScope()
    val localRoot = vm.repo.rootDir.path
    var path by remember { mutableStateOf(localRoot) }
    var folders by remember { mutableStateOf<List<FileItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        folders = null
        error = null
        folders = try {
            vm.repo.listFolder(path, vm.showHidden)
                .filter { it.isDirectory }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        } catch (e: Exception) {
            error = e.message ?: "Couldn't open this folder"
            emptyList()
        }
    }

    val parent: String? = SshPath.parse(path)
        ?.let { if (it.path == "/") null else SshPath(it.hostId, it.parent).toString() }
        ?: path.takeIf { it != localRoot && it.startsWith(localRoot) }?.let { File(it).parent }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Add shortcut")
                Text(
                    vm.displayName(path),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column {
                // Where to start: this phone or one of the saved servers.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = SshPath.parse(path) == null,
                        onClick = { path = localRoot },
                        label = { Text("Internal storage") },
                        leadingIcon = { Icon(Icons.Outlined.TabletAndroid, null, Modifier.size(18.dp)) },
                    )
                    ssh.hosts.forEach { host ->
                        FilterChip(
                            selected = SshPath.parse(path)?.hostId == host.id,
                            onClick = {
                                scope.launch {
                                    try {
                                        path = SshPath(host.id, ssh.startPath(host.id)).toString()
                                    } catch (e: Exception) {
                                        error = e.message ?: "Couldn't connect"
                                    }
                                }
                            },
                            label = { Text(host.name) },
                            leadingIcon = { Icon(Icons.Outlined.Dns, null, Modifier.size(18.dp)) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.heightIn(min = 120.dp, max = 360.dp)) {
                    val list = folders
                    when {
                        list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else -> LazyColumn {
                            if (parent != null) {
                                item { PickerRow(Icons.Filled.ArrowUpward, "..") { path = parent } }
                            }
                            items(list, key = { it.path }) { folder ->
                                PickerRow(Icons.Outlined.Folder, folder.name) { path = folder.path }
                            }
                            if (list.isEmpty()) {
                                item {
                                    Text(
                                        error ?: "No folders here",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (error != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val alreadyAdded = path in exclude
            TextButton(enabled = !alreadyAdded && error == null, onClick = { onPick(path) }) {
                Text(if (alreadyAdded) "Already added" else "Add this folder")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickerRow(icon: ImageVector, name: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
