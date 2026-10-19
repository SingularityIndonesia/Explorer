package com.singularity_universe.explorer.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.data.Trash
import com.singularity_universe.explorer.data.TrashItem
import com.singularity_universe.explorer.data.mimeTypeOf
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(vm: ExplorerViewModel) {
    val data = vm.trash().observe()
    val items = data.value
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf<TrashConfirm?>(null) }

    LaunchedEffect(items) {
        val ids = items.orEmpty().mapTo(HashSet()) { it.id }
        selected = selected.filterTo(HashSet()) { it in ids }
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    fun toggle(item: TrashItem) {
        selected = if (item.id in selected) selected - item.id else selected + item.id
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") }
                    },
                    actions = {
                        IconButton(onClick = { selected = items.orEmpty().mapTo(HashSet()) { it.id } }) {
                            Icon(Icons.Default.SelectAll, "Select all")
                        }
                        IconButton(onClick = {
                            vm.restoreFromTrash(selected)
                            selected = emptySet()
                        }) { Icon(Icons.Outlined.RestoreFromTrash, "Restore") }
                        IconButton(onClick = { confirm = TrashConfirm.DeleteSelected }) {
                            Icon(Icons.Outlined.DeleteForever, "Delete forever")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            } else {
                TopAppBar(
                    title = { Text("Trash") },
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        if (!items.isNullOrEmpty()) {
                            OverflowMenu {
                                item("Empty trash", Icons.Outlined.DeleteSweep) { confirm = TrashConfirm.Empty }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = data.refreshing,
            onRefresh = data::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                items == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp)) {
                    item { RetentionNote() }
                    if (items.isEmpty()) {
                        item { EmptyState(Icons.Outlined.DeleteSweep, "Trash is empty", Modifier.padding(top = 64.dp)) }
                    }
                    items(items, key = { it.id }) { item ->
                        TrashRow(
                            vm = vm,
                            item = item,
                            selected = item.id in selected,
                            // Tapping always selects: trashed files aren't opened, only restored or deleted.
                            onClick = { toggle(item) },
                        )
                    }
                }
            }
            if (vm.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    when (confirm) {
        TrashConfirm.DeleteSelected -> ConfirmDialog(
            title = if (selected.size == 1) "Delete 1 item forever?" else "Delete ${selected.size} items forever?",
            onDismiss = { confirm = null },
        ) {
            vm.deleteFromTrash(selected)
            selected = emptySet()
            confirm = null
        }
        TrashConfirm.Empty -> ConfirmDialog(
            title = "Empty trash?",
            onDismiss = { confirm = null },
        ) {
            vm.emptyTrash()
            confirm = null
        }
        null -> Unit
    }
}

private enum class TrashConfirm { DeleteSelected, Empty }

@Composable
private fun ConfirmDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text("This can't be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RetentionNote() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            "Items are deleted for good after ${Trash.RETENTION_DAYS} days. Tap items to restore or delete them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashRow(vm: ExplorerViewModel, item: TrashItem, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "trashRow",
    )
    // Shown like any file, using the name it had before it was deleted.
    val preview = FileItem(
        path = vm.repo.trash.fileOf(item).path,
        name = item.name,
        isDirectory = item.isDirectory,
        size = item.size,
        lastModified = item.deletedAt,
        mimeType = if (item.isDirectory) "inode/directory" else mimeTypeOf(item.name),
    )
    val now = System.currentTimeMillis()
    val deleted = if (now - item.deletedAt < DateUtils.MINUTE_IN_MILLIS) "just now"
    else DateUtils.getRelativeTimeSpanString(item.deletedAt, now, DateUtils.MINUTE_IN_MILLIS)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumbnail(preview, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Deleted $deleted · ${context.formatSize(item.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                "From ${File(item.originalPath).parent?.let(vm::displayName) ?: "?"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
