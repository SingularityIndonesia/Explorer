package com.singularity_universe.explorer.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.data.FileKind
import com.singularity_universe.explorer.data.thumbnail
import java.io.File
import java.util.Date

// ---- Formatting ----

fun Context.formatSize(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

fun Context.formatDate(millis: Long): String = DateFormat.getMediumDateFormat(this).format(Date(millis))

fun Context.describe(item: FileItem): String =
    if (item.isDirectory) {
        when {
            item.childCount < 0 -> "Folder · ${formatDate(item.lastModified)}"
            item.childCount == 1 -> "1 item · ${formatDate(item.lastModified)}"
            else -> "${item.childCount} items · ${formatDate(item.lastModified)}"
        }
    } else {
        "${formatSize(item.size)} · ${formatDate(item.lastModified)}"
    }

// ---- Intents ----

private fun Context.uriFor(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

fun Context.openFile(item: FileItem) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriFor(item.file), item.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivity(Intent.createChooser(intent, item.name))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

fun Context.shareFiles(items: List<FileItem>) {
    val files = items.filterNot { it.isDirectory }
    if (files.isEmpty()) {
        Toast.makeText(this, "Folders can't be shared", Toast.LENGTH_SHORT).show()
        return
    }
    val uris = files.map { uriFor(it.file) }
    val mime = files.map { it.mimeType }.distinct().singleOrNull() ?: "*/*"
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = mime
    intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, null))
}

// ---- Icons & thumbnails ----

fun FileKind.icon(): ImageVector = when (this) {
    FileKind.Folder -> Icons.Outlined.Folder
    FileKind.Image -> Icons.Outlined.Image
    FileKind.Video -> Icons.Outlined.Movie
    FileKind.Audio -> Icons.Outlined.Audiotrack
    FileKind.Document -> Icons.Outlined.Description
    FileKind.Archive -> Icons.Outlined.FolderZip
    FileKind.Apk -> Icons.Outlined.Android
    FileKind.Other -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

private val FileItem.hasPreview get() = kind == FileKind.Image || kind == FileKind.Video

@Composable
fun FileThumbnail(item: FileItem, modifier: Modifier = Modifier, iconSize: Int = 22, thumbnailPx: Int = 192) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            item.kind.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(iconSize.dp),
        )
        if (item.hasPreview) {
            AsyncImage(
                model = item.thumbnail(thumbnailPx),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.kind == FileKind.Video) {
                Icon(
                    Icons.Outlined.PlayCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size((iconSize + 4).dp),
                )
            }
        }
    }
}

// ---- List & grid cells ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem,
    selected: Boolean,
    starred: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val context = LocalContext.current
    val background by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            highlighted -> MaterialTheme.colorScheme.tertiaryContainer
            else -> Color.Transparent
        },
        animationSpec = tween(600),
        label = "rowBackground",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FileThumbnail(item, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
            if (selected) SelectedBadge(Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                context.describe(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (starred) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Starred",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp).size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCell(
    item: FileItem,
    selected: Boolean,
    starred: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .then(
                    when {
                        selected -> Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                        highlighted -> Modifier.border(3.dp, MaterialTheme.colorScheme.tertiary, shape)
                        else -> Modifier
                    }
                ),
        ) {
            FileThumbnail(item, Modifier.fillMaxSize(), iconSize = 36, thumbnailPx = 320)
            if (selected) SelectedBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (starred) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Starred",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp),
                )
            }
        }
        Text(
            item.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SelectedBadge(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = "Selected",
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .size(20.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape),
    )
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Dialogs ----

@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Preselect the name without its extension, like most file managers do.
    val selectionEnd = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, selectionEnd))) }
    val focus = remember { FocusRequester() }
    val name = value.text.trim()
    val valid = name.isNotEmpty() && '/' !in name && name != "." && name != ".."
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = valid && name != initial) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
fun DeleteDialog(count: Int, permanentCount: Int = count, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val items = if (count == 1) "1 item" else "$count items"
    val (title, text, action) = when (permanentCount) {
        0 -> Triple(
            "Move $items to Trash?",
            "You can restore ${if (count == 1) "it" else "them"} from Trash for 30 days.",
            "Move to Trash",
        )
        count -> Triple("Delete $items?", "This can't be undone.", "Delete")
        else -> Triple(
            "Delete $items?",
            "$permanentCount of them can't go to Trash (on a server, in Android/data or on an SD card) and " +
                "will be deleted for good. The rest move to Trash.",
            "Delete",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun InfoDialog(item: FileItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoLine("Name", item.name)
                InfoLine("Location", item.file.parent.orEmpty())
                if (item.isDirectory && item.childCount >= 0) InfoLine("Contains", "${item.childCount} items")
                else InfoLine("Size", context.formatSize(item.size))
                InfoLine("Modified", DateFormat.format("d MMM yyyy, HH:mm", item.lastModified).toString())
                if (!item.isDirectory) InfoLine("Type", item.mimeType)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A folder icon; folders on an SSH server get a small server badge so they stand out from local ones. */
@Composable
fun FolderIcon(onServer: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp)) {
        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.fillMaxSize())
        if (onServer) {
            Icon(
                Icons.Filled.Dns,
                contentDescription = "On a server",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .size(14.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)
                    .padding(1.5.dp),
            )
        }
    }
}
