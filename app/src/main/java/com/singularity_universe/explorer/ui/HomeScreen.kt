package com.singularity_universe.explorer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.data.Category
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.remote.AdbDevices
import com.singularity_universe.explorer.remote.SshConnections

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: ExplorerViewModel, onOpenDrawer: () -> Unit) {
    val context = LocalContext.current
    val ssh = LocalSshConnections.current
    val adb = LocalAdbDevices.current
    val recentsData = vm.recents().observe()
    val sizesData = vm.categorySizes().observe()
    val storageData = vm.storage().observe()
    val recents = recentsData.value?.take(30)
    val sizes = sizesData.value.orEmpty()
    val storage = storageData.value
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }
    var infoTarget by remember { mutableStateOf<FileItem?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        SearchPill(onMenu = onOpenDrawer, onSearch = { vm.navigate(Route.Search) })
        Spacer(Modifier.height(12.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
          PullToRefreshBox(
            isRefreshing = recentsData.refreshing || sizesData.refreshing || storageData.refreshing,
            onRefresh = {
                recentsData.refresh()
                sizesData.refresh()
                storageData.refresh()
            },
          ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                SectionHeader("Recents") {
                    TextButton(onClick = { vm.navigate(Route.Recents) }) { Text("See all") }
                }
                RecentsRow(
                    items = recents,
                    starred = vm.starred,
                    onOpen = { vm.openInApp(it, recents.orEmpty()) { file -> context.openFile(file) } },
                    onShare = { context.shareFiles(listOf(it)) },
                    onStar = { vm.toggleStar(listOf(it.path)) },
                    onInfo = { infoTarget = it },
                    onDelete = { deleteTarget = it },
                )

                SectionHeader("Categories")
                val categories = Category.entries.map { category ->
                    Tile(
                        icon = category.icon(),
                        title = category.label,
                        subtitle = sizes[category]?.let(context::formatSize) ?: "…",
                        onClick = { vm.navigate(Route.CategoryFiles(category)) },
                    )
                }
                TileGrid(categories)

                SectionHeader("Collections")
                TileGrid(
                    listOf(
                        Tile(Icons.Outlined.StarBorder, "Starred", null) { vm.navigate(Route.Starred) },
                        Tile(Icons.Outlined.CleaningServices, "Free up space", "Large files, duplicates…") { vm.navigate(Route.Clean) },
                    )
                )

                SectionHeader("All storage")
                TileGrid(
                    listOf(
                        Tile(
                            Icons.Outlined.TabletAndroid,
                            "Internal storage",
                            storage?.let { "${context.formatSize(it.free)} free" } ?: "…",
                        ) { vm.navigate(Route.Folder(vm.repo.rootDir.path)) },
                        Tile(Icons.Outlined.Storage, "Other storage", "SD cards and USB drives") {
                            vm.navigate(Route.OtherStorage)
                        },
                    ) + ssh.hosts.map { host ->
                        val connected = ssh.status[host.id] == SshConnections.Status.Connected
                        Tile(Icons.Outlined.Dns, host.name, if (connected) "Connected · ${host.address}" else host.address) {
                            vm.openServer(host.id)
                        }
                    } + adb.devices.map { device ->
                        val connected = adb.status[device.id] == AdbDevices.Status.Connected
                        Tile(Icons.Outlined.Devices, device.name, if (connected) "Connected · ${device.address}" else device.address) {
                            vm.openDevice(device.id)
                        }
                    }
                )
            }
          }
        }
    }

    deleteTarget?.let { target ->
        DeleteDialog(
            count = 1,
            permanentCount = if (vm.repo.canTrash(target.path)) 0 else 1,
            onDismiss = { deleteTarget = null },
        ) {
            vm.delete(listOf(target.path))
            deleteTarget = null
        }
    }
    infoTarget?.let { InfoDialog(it) { infoTarget = null } }
}

@Composable
private fun SearchPill(onMenu: () -> Unit, onSearch: () -> Unit) {
    Surface(
        onClick = onSearch,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Open menu") }
            Text(
                "Search in Files",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 8.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun RecentsRow(
    items: List<FileItem>?,
    starred: Set<String>,
    onOpen: (FileItem) -> Unit,
    onShare: (FileItem) -> Unit,
    onStar: (FileItem) -> Unit,
    onInfo: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Roughly 4.5 cards visible on tablets, 2.5 on phones — like the real app.
        val cardWidth: Dp = (if (maxWidth > 600.dp) (maxWidth - 48.dp) / 4.5f else (maxWidth - 32.dp) / 2.3f)
            .coerceIn(140.dp, 240.dp)
        val cardHeight = cardWidth * 1.2f
        when {
            items == null -> Spacer(Modifier.height(cardHeight))
            items.isEmpty() -> Text(
                "No recent files",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.path }) { item ->
                    RecentCard(
                        item = item,
                        isStarred = item.path in starred,
                        modifier = Modifier.width(cardWidth).height(cardHeight),
                        onOpen = { onOpen(item) },
                        onShare = { onShare(item) },
                        onStar = { onStar(item) },
                        onInfo = { onInfo(item) },
                        onDelete = { onDelete(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentCard(
    item: FileItem,
    isStarred: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onStar: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onOpen)) {
        FileThumbnail(item, Modifier.fillMaxSize(), iconSize = 48, thumbnailPx = 512, showText = true)
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                item.name,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.parentName,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Surface(
                onClick = { menu = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MoreVert, "More options", Modifier.size(20.dp))
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem("Open", Icons.AutoMirrored.Outlined.OpenInNew) { menu = false; onOpen() }
                MenuItem("Share", Icons.Outlined.Share) { menu = false; onShare() }
                MenuItem(if (isStarred) "Remove from Starred" else "Add to Starred", Icons.Outlined.StarBorder) {
                    menu = false; onStar()
                }
                MenuItem("File info", Icons.Outlined.Info) { menu = false; onInfo() }
                MenuItem("Delete", Icons.Outlined.Delete) { menu = false; onDelete() }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

private data class Tile(
    val icon: ImageVector,
    val title: String,
    val subtitle: String?,
    val onClick: () -> Unit,
)

@Composable
private fun TileGrid(tiles: List<Tile>) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { TileCard(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TileCard(tile: Tile, modifier: Modifier) {
    Surface(
        onClick = tile.onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(0.dp, Color.Transparent),
        // Grows for two-line titles; tiles in a row share the tallest height.
        modifier = modifier.fillMaxHeight().heightIn(min = 76.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(tile.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(20.dp))
            Column {
                Text(tile.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                tile.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun Category.icon(): ImageVector = when (this) {
    Category.Downloads -> Icons.Outlined.Download
    Category.Images -> Icons.Outlined.Image
    Category.Videos -> Icons.Outlined.Movie
    Category.Audio -> Icons.Outlined.Audiotrack
    Category.Documents -> Icons.Outlined.Description
    Category.Apps -> Icons.Outlined.Apps
}
