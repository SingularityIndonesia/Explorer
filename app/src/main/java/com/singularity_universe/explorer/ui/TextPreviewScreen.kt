package com.singularity_universe.explorer.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.singularity_universe.explorer.TextEditorActivity
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.data.MAX_EDIT_BYTES
import com.singularity_universe.explorer.data.MAX_PREVIEW_BYTES
import com.singularity_universe.explorer.data.TextContent
import com.singularity_universe.explorer.data.readTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only view of a text or script file, with line numbers; Edit opens [TextEditorActivity]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextPreviewScreen(vm: ExplorerViewModel, item: FileItem) {
    val context = LocalContext.current
    var content by remember { mutableStateOf<TextContent?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // A file on a server or device is downloaded once; the editor then works on that copy.
    val copy = rememberLocalCopy(vm, item)
    val local = (copy as? LocalCopy.Ready)?.file
    val remote = vm.repo.needsCopyToOpen(item.path)

    // Reads the file each time the screen is shown, so edits made in the editor appear on return.
    var loads by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        loads++
        onPauseOrDispose { }
    }
    LaunchedEffect(loads, local) {
        if (loads == 0 || local == null) return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { readTextFile(local) } }
            .onSuccess { loaded ->
                val previous = content
                if (previous != null && previous.lastModified != loaded.lastModified) vm.fileChanged()
                content = loaded
                error = null
            }
            .onFailure { error = it.message ?: "Couldn't read this file" }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        content?.let { loaded ->
                            Text(
                                remember(loaded) { summary(context, loaded) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    OverflowMenu {
                        item("Locate", Icons.Outlined.FolderOpen) { vm.locate(item) }
                        item("Copy to…", Icons.Outlined.ContentCopy) { vm.startTransfer(listOf(item.path), move = false) }
                        item("Move to…", Icons.AutoMirrored.Outlined.DriveFileMove) {
                            vm.startTransfer(listOf(item.path), move = true)
                        }
                        item("Open with", Icons.AutoMirrored.Outlined.OpenInNew) {
                            if (remote) vm.openRestricted(item) { context.openFile(it) } else context.openFile(item)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            val loaded = content
            if (loaded != null) ExtendedFloatingActionButton(
                onClick = {
                    when {
                        !loaded.utf8 -> vm.message("Only UTF-8 text can be edited")
                        !loaded.editable -> vm.message("Files over ${Formatter.formatShortFileSize(context, MAX_EDIT_BYTES)} can't be edited here")
                        remote && !vm.repo.canWriteBack(item.path) -> vm.message("This file can't be changed from here")
                        !remote && !item.file.canWrite() -> vm.message("This file is read-only")
                        // A remote file is edited as its local copy, and each save goes back to the server.
                        local != null -> TextEditorActivity.start(context, local.path, remotePath = if (remote) item.path else null)
                    }
                },
                icon = { Icon(Icons.Outlined.Edit, null) },
                text = { Text("Edit") },
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val loaded = content
            when {
                loaded != null -> TextLines(loaded)
                copy !is LocalCopy.Ready -> Box(Modifier.align(Alignment.Center)) { LocalCopyStatus(copy, item) }
                error != null -> CenteredMessage(error!!)
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun TextLines(content: TextContent) {
    if (content.text.isEmpty() && content.utf8) {
        CenteredMessage("This file is empty")
        return
    }
    // Very long lines (minified files) are cut into chunks, so the list never lays out megabytes at once.
    val rows = remember(content) { previewRows(content.text) }
    val lineCount = rows.lastOrNull()?.number ?: 0

    val codeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
    val numberStyle = codeStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gutter = remember(lineCount, density) {
        with(density) { measurer.measure("0".repeat(lineCount.toString().length), codeStyle).size.width.toDp() }
    }

    SelectionContainer {
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            notices(content).forEach { notice ->
                item {
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium)
                            .padding(12.dp),
                    )
                }
            }
            items(rows) { row ->
                Row(horizontalArrangement = Arrangement.Start) {
                    // Line numbers aren't part of the text: copying a selection gives just the code.
                    DisableSelection {
                        Text(
                            if (row.continued) "" else row.number.toString(),
                            style = numberStyle,
                            modifier = Modifier.width(gutter),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(row.text, style = codeStyle.copy(color = MaterialTheme.colorScheme.onSurface))
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** One row of the preview: a line, or a chunk of a very long line ([continued] after the first). */
private class PreviewRow(val number: Int, val text: String, val continued: Boolean)

private fun previewRows(text: String): List<PreviewRow> {
    val lines = text.split('\n').let { if (text.endsWith('\n')) it.dropLast(1) else it }
    val rows = ArrayList<PreviewRow>(lines.size)
    lines.forEachIndexed { index, line ->
        if (line.length <= CHUNK) {
            rows += PreviewRow(index + 1, line, continued = false)
        } else {
            line.chunked(CHUNK).forEachIndexed { part, chunk -> rows += PreviewRow(index + 1, chunk, continued = part > 0) }
        }
    }
    return rows
}

private fun summary(context: Context, content: TextContent): String {
    val lines = if (content.text.isEmpty()) 0 else content.text.count { it == '\n' } + if (content.text.endsWith('\n')) 0 else 1
    val size = Formatter.formatShortFileSize(context, content.size)
    return "${if (content.truncated) "$lines+" else lines} ${if (lines == 1) "line" else "lines"} · $size"
}

private fun notices(content: TextContent): List<String> = buildList {
    if (content.truncated) add("Showing the first ${MAX_PREVIEW_BYTES / (1024 * 1024)} MB. Open it in another app to see all of it.")
    if (!content.utf8) add("This file isn't UTF-8 text, so some characters may look wrong. It can't be edited here.")
}

private const val CHUNK = 4_000
