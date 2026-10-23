package com.singularity_universe.explorer.ui

import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.singularity_universe.explorer.SSHTerminalActivity
import com.singularity_universe.explorer.data.AppItem
import com.singularity_universe.explorer.data.Category
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.data.toFileItem
import com.singularity_universe.explorer.remote.AdbPath
import com.singularity_universe.explorer.remote.RemoteAccessRequiredException
import com.singularity_universe.explorer.remote.RemoteConnection
import com.singularity_universe.explorer.remote.SshPath
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun FolderScreen(vm: ExplorerViewModel, path: String, highlight: String? = null) {
    // Breadcrumbs start at the storage root, or at "/" on an SSH server.
    val root = vm.breadcrumbRoot(path)
    val data = vm.folder(path).observe()
    val error = data.error
    val needsRemote = error is RemoteAccessRequiredException
    val ssh = LocalSshConnections.current
    val adb = LocalAdbDevices.current
    // On a server or device: the address in use, tap to switch route (then reload through it).
    val subtitle: (@Composable () -> Unit)? =
        SshPath.parse(path)?.let { ssh.host(it.hostId) }?.let { host ->
            {
                AddressSwitcher(host.hosts.indices.map(host::addressOf), host.activeHost, onSelect = { index ->
                    ssh.useAddress(host.id, index)
                    data.refresh()
                })
            }
        } ?: AdbPath.parse(path)?.let { adb.device(it.deviceId) }?.let { device ->
            {
                AddressSwitcher(device.hosts.indices.map(device::addressOf), device.activeHost, onSelect = { index ->
                    adb.useAddress(device.id, index)
                    data.refresh()
                })
            }
        }
    val context = LocalContext.current
    FileListScreen(
        vm = vm,
        title = vm.displayName(path),
        subtitle = subtitle,
        actions = {
            // On an SSH server: a terminal in this folder.
            SshPath.parse(path)?.let { ssh ->
                IconButton(onClick = { SSHTerminalActivity.start(context, ssh.hostId, directory = ssh.path) }) {
                    Icon(Icons.Outlined.Terminal, "Open terminal here")
                }
            }
        },
        items = if (error != null && data.value == null) emptyList() else data.value,
        refreshing = data.refreshing,
        onRefresh = data::refresh,
        emptyText = when {
            needsRemote -> "Android hides this folder from apps"
            error != null -> error.message ?: "Couldn't open this folder"
            else -> "This folder is empty"
        },
        currentFolder = path,
        highlightPath = highlight,
        header = {
            Column {
                Breadcrumbs(vm, root, path)
                if (needsRemote) RemoteRequiredCard(onSetUp = { vm.navigate(Route.Remote) })
                // A reload failed (e.g. after switching to an unreachable address) but older contents are shown.
                if (error != null && !needsRemote && data.value != null) {
                    Text(
                        "Couldn't refresh: ${error.message ?: "connection failed"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun RemoteRequiredCard(onSetUp: () -> Unit) {
    val status = LocalRemoteConnection.current.status
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Remote access needed", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Android blocks Android/data and Android/obb for every app. Explorer can open them " +
                    "through this phone's own Wireless debugging — nothing leaves your device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (status == RemoteConnection.Status.Connecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Connecting…", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                FilledTonalButton(onClick = onSetUp) { Text("Set up remote access") }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(vm: ExplorerViewModel, root: String, path: String) {
    val segments = buildList {
        add(vm.displayName(root) to root)
        if (path.startsWith(root)) {
            var current = root
            path.removePrefix(root).split('/').filter { it.isNotEmpty() }.forEach {
                current = "$current/$it"
                add(it to current)
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState(Int.MAX_VALUE))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Where this folder lives: a server or this phone.
        Icon(
            when {
                vm.repo.isSsh(path) -> Icons.Outlined.Dns
                vm.repo.isAdbDevice(path) -> Icons.Outlined.Devices
                else -> Icons.Outlined.Smartphone
            },
            contentDescription = when {
                vm.repo.isSsh(path) -> "Server"
                vm.repo.isAdbDevice(path) -> "Android device"
                else -> "This phone"
            },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 2.dp).size(18.dp),
        )
        segments.forEachIndexed { index, (name, segmentPath) ->
            val last = index == segments.lastIndex
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !last) { jumpTo(vm, segmentPath) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            if (!last) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Pops back to an ancestor folder already on the stack, or opens it fresh. */
private fun jumpTo(vm: ExplorerViewModel, path: String) {
    val index = vm.backStack.indexOfLast { it is Route.Folder && it.path == path }
    if (index >= 0) {
        while (vm.backStack.lastIndex > index) vm.backStack.removeAt(vm.backStack.lastIndex)
    } else {
        vm.navigate(Route.Folder(path))
    }
}

@Composable
fun CategoryScreen(vm: ExplorerViewModel, category: Category) {
    if (category == Category.Apps) {
        AppsScreen(vm)
        return
    }
    val data = vm.category(category).observe()
    FileListScreen(
        vm = vm,
        title = category.label,
        items = data.value,
        refreshing = data.refreshing,
        onRefresh = data::refresh,
        emptyText = "No ${category.label.lowercase()} found",
    )
}

@Composable
fun RecentsScreen(vm: ExplorerViewModel) {
    val data = vm.recents().observe()
    FileListScreen(
        vm, "Recents", data.value, "No recent files",
        sortable = false, refreshing = data.refreshing, onRefresh = data::refresh,
        sectionOf = { monthLabel(it.lastModified) },
    )
}

/** "This month", "Last month", then "September 2026" style headers. */
private fun monthLabel(millis: Long): String {
    val month = YearMonth.from(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    val now = YearMonth.now()
    return when (month) {
        now -> "This month"
        now.minusMonths(1) -> "Last month"
        else -> DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()).format(month)
    }
}

@Composable
fun StarredScreen(vm: ExplorerViewModel) {
    val data = vm.starredFiles().observe()
    FileListScreen(
        vm, "Starred", data.value, "Star files to find them here quickly",
        refreshing = data.refreshing, onRefresh = data::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: ExplorerViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<Category?>(null) }
    val focus = remember { FocusRequester() }
    var results by remember { mutableStateOf<List<FileItem>?>(emptyList()) }
    var searchRun by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query, filter, searchRun) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        searching = true
        results = vm.repo.search(query.trim(), filter)
        searching = false
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    FileListScreen(
        vm = vm,
        title = "Search",
        items = results,
        emptyText = if (query.isBlank()) "Search for files by name" else "No results for \"$query\"",
        sortable = false,
        refreshing = searching,
        onRefresh = { searchRun++ },
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search in Files") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        header = {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                Category.entries.filter { it != Category.Apps && it != Category.Downloads }.forEach { category ->
                    FilterChip(
                        selected = filter == category,
                        onClick = { filter = if (filter == category) null else category },
                        label = { Text(category.label) },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsScreen(vm: ExplorerViewModel) {
    val context = LocalContext.current
    val data = vm.apps().observe()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Apps") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        val list = data.value
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        PullToRefreshBox(
            isRefreshing = data.refreshing,
            onRefresh = data::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                item {
                    Text(
                        "${list.size} apps · ${context.formatSize(list.sumOf { it.size })}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                items(list, key = { it.packageName }) { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app.packageName)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                context.formatSize(app.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!app.isSystem) {
                            FilledTonalButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
                            }) { Text("Uninstall") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(128, 128).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        val bitmap = icon
        if (bitmap != null) Image(bitmap, null, Modifier.size(44.dp))
        else Icon(Icons.Outlined.Apps, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherStorageScreen(vm: ExplorerViewModel) {
    val context = LocalContext.current
    val ssh = LocalSshConnections.current
    val adb = LocalAdbDevices.current
    val volumes = remember {
        context.getSystemService(StorageManager::class.java).storageVolumes
            .mapNotNull { volume -> volume.directory?.let { volume to it } }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Other storage") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            items(volumes, key = { it.second.path }) { (volume, dir) ->
                StorageRow(
                    icon = when {
                        volume.isPrimary -> Icons.Outlined.Smartphone
                        volume.isRemovable -> Icons.Outlined.SdStorage
                        else -> Icons.Outlined.Usb
                    },
                    title = volume.getDescription(context),
                    subtitle = "${context.formatSize(dir.freeSpace)} free · ${dir.toFileItem().childCount} items",
                ) { vm.navigate(Route.Folder(dir.path)) }
            }
            item {
                Text(
                    "Connect an SD card or USB drive to browse it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                Text(
                    "Servers and devices",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(ssh.hosts, key = { it.id }) { host ->
                StorageRow(Icons.Outlined.Dns, host.name, host.address) { vm.openServer(host.id) }
            }
            items(adb.devices, key = { it.id }) { device ->
                StorageRow(Icons.Outlined.Devices, device.name, device.address) { vm.openDevice(device.id) }
            }
            item {
                StorageRow(Icons.Outlined.Add, "Add SSH server or Android device", "Manage them in Settings") {
                    vm.navigate(Route.Settings)
                }
            }
        }
    }
}

@Composable
fun PermissionScreen() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text("Allow access to your files", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Files needs \"All files access\" to browse, organise and manage everything on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        FilledTonalButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
        }) { Text("Continue") }
    }
}

@Composable
private fun StorageRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(20.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
