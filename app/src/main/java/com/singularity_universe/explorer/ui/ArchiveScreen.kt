package com.singularity_universe.explorer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.data.Archives
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.data.mimeTypeOf
import com.singularity_universe.explorer.data.toFileItem
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Inside an archive (zip, tar, 7z...), like a folder: open folders and files (files are extracted to the cache and
 * opened in the app), select entries, extract everything or a selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(vm: ExplorerViewModel, archive: FileItem, folder: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copy = rememberLocalCopy(vm, archive)
    val local = (copy as? LocalCopy.Ready)?.file
    // For encrypted archives: the password typed, and what to do once it's given.
    var password by rememberSaveable(archive.path) { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf<Boolean?>(null) } // null = not asking; true = the last one was wrong
    var afterPassword by remember { mutableStateOf<(() -> Unit)?>(null) }
    val listing by produceState<Result<List<Archives.Entry>>?>(null, local, password) {
        if (local != null) value = withContext(Dispatchers.IO) { runCatching { Archives.list(local, password) } }
    }
    // Some archives (7z with encrypted names) can't even be listed without the password.
    (listing?.exceptionOrNull() as? Archives.PasswordRequiredException)?.let { needed ->
        LaunchedEffect(needed) { if (askPassword == null) askPassword = needed.wrong }
    }
    val entries = listing?.getOrNull()
    val counts = remember(entries) { entries?.let(Archives::childCounts).orEmpty() }
    val children = remember(entries, folder) {
        entries?.let { all ->
            Archives.children(all, folder).sortedWith(compareByDescending<Archives.Entry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
    }
    var selected by remember(folder) { mutableStateOf(emptySet<String>()) }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    fun itemOf(entry: Archives.Entry) = FileItem(
        path = "${archive.path}!/${entry.path}",
        name = entry.name,
        isDirectory = entry.isDirectory,
        size = entry.size,
        lastModified = entry.time,
        mimeType = if (entry.isDirectory) "inode/directory" else mimeTypeOf(entry.name),
        childCount = counts[entry.path] ?: 0,
    )

    fun extract(only: Collection<String>?, base: String) {
        val zip = local ?: return
        vm.extractArchive(archive, zip, only, base, password) { wrong ->
            afterPassword = { extract(only, base) }
            askPassword = wrong
        }
    }

    fun open(entry: Archives.Entry) {
        val zip = local ?: return
        if (entry.isDirectory) {
            vm.navigate(Route.Archive(archive, entry.path))
            return
        }
        // Files are extracted to the cache once, then shown by the app's own viewers.
        scope.launch {
            val key = MessageDigest.getInstance("SHA-1").digest("${zip.path}:${zip.lastModified()}:${entry.path}".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(20)
            val target = File(context.cacheDir, "archive-files/$key/${entry.name}")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!target.exists() || entry.size <= 0 || target.length() != entry.size) Archives.extractEntry(zip, entry.path, target, password)
                    target.toFileItem()
                }
            }
            result.onSuccess { file -> vm.openInApp(file, listOf(file)) { context.openFile(it) } }
                .onFailure { error ->
                    if (error is Archives.PasswordRequiredException) {
                        target.delete()
                        afterPassword = { open(entry) }
                        askPassword = error.wrong
                    } else {
                        vm.message(error.message ?: "Couldn't open this file")
                    }
                }
        }
    }

    askPassword?.let { wrong ->
        PasswordDialog(
            wrong = wrong,
            onDismiss = {
                askPassword = null
                afterPassword = null
            },
            onDone = { typed ->
                askPassword = null
                password = typed
                afterPassword?.invoke()
                afterPassword = null
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") } },
                    actions = {
                        IconButton(onClick = { selected = children.orEmpty().mapTo(HashSet()) { it.path } }) {
                            Icon(Icons.Outlined.SelectAll, "Select all")
                        }
                        IconButton(onClick = {
                            val chosen = selected
                            extract(chosen, folder)
                            selected = emptySet()
                        }) { Icon(Icons.Outlined.Unarchive, "Extract selected") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(folder.substringAfterLast('/').ifEmpty { archive.name }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (folder.isEmpty()) "Archive · ${children?.size ?: 0} items" else "${archive.name}/$folder",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { extract(if (folder.isEmpty()) null else listOf(folder), folder) }, enabled = entries != null) {
                            Icon(Icons.Outlined.Unarchive, if (folder.isEmpty()) "Extract all" else "Extract this folder")
                        }
                        OverflowMenu {
                            item("Open with", Icons.AutoMirrored.Outlined.OpenInNew) {
                                if (vm.repo.needsCopyToOpen(archive.path)) vm.openRestricted(archive) { context.openFile(it) }
                                else context.openFile(archive)
                            }
                            item("Locate", Icons.Outlined.FolderOpen) { vm.locate(archive) }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val failure = listing?.exceptionOrNull()
            when {
                copy !is LocalCopy.Ready -> LocalCopyStatus(copy, archive)
                failure is Archives.PasswordRequiredException -> Text(
                    "This archive is password-protected.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
                failure != null -> Text(
                    "Couldn't open this archive: ${failure.message ?: "it may be damaged"}",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
                children == null -> CircularProgressIndicator()
                children.isEmpty() -> EmptyState(Icons.Outlined.FolderZip, "This folder is empty")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    items(children, key = { it.path }) { entry ->
                        val isSelected = entry.path in selected
                        FileRow(
                            item = itemOf(entry),
                            selected = isSelected,
                            starred = false,
                            onClick = {
                                if (selected.isNotEmpty()) selected = if (isSelected) selected - entry.path else selected + entry.path
                                else open(entry)
                            },
                            onLongClick = { selected = if (isSelected) selected - entry.path else selected + entry.path },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordDialog(wrong: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password") },
        text = {
            Column {
                Text(
                    if (wrong) "That password didn't work. Try again." else "This archive is protected by a password.",
                    color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotEmpty()) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

