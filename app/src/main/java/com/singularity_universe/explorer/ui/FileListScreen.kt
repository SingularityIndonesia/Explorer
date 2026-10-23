package com.singularity_universe.explorer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.data.SortOrder
import com.singularity_universe.explorer.data.sortedBy
import kotlinx.coroutines.delay

/**
 * Shared screen for anything that shows a list of files: folders, categories, starred, search results.
 * Handles selection mode, sorting, list/grid view and the copy/move "paste here" bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(
    vm: ExplorerViewModel,
    title: String,
    items: List<FileItem>?,
    emptyText: String,
    modifier: Modifier = Modifier,
    currentFolder: String? = null,
    sortable: Boolean = true,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    highlightPath: String? = null,
    /** Groups the list under headers (e.g. per month); items must already be in that order. */
    sectionOf: ((FileItem) -> String)? = null,
    /** Shown under the title, e.g. the address a remote folder is reached through. */
    subtitle: (@Composable () -> Unit)? = null,
    header: @Composable () -> Unit = {},
    topBar: (@Composable () -> Unit)? = null,
    /** Extra buttons at the start of the top bar's actions (e.g. a server folder's terminal). */
    actions: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var showSort by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var infoTarget by remember { mutableStateOf<FileItem?>(null) }

    val shown = remember(items, vm.sortOrder, sortable) {
        if (sortable) items?.sortedBy(vm.sortOrder) else items
    }
    val selectedItems = shown.orEmpty().filter { it.path in selected }

    // Drop selections that disappeared after a reload (deleted, moved, ...).
    LaunchedEffect(items) {
        val paths = items.orEmpty().mapTo(HashSet()) { it.path }
        selected = selected.filterTo(HashSet()) { it in paths }
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    fun toggle(item: FileItem) {
        selected = if (item.path in selected) selected - item.path else selected + item.path
    }

    fun open(item: FileItem) {
        when {
            selected.isNotEmpty() -> toggle(item)
            item.isDirectory -> vm.navigate(Route.Folder(item.path))
            // Other apps can't read Android/data or a server, so hand them a copy.
            vm.repo.needsCopyToOpen(item.path) -> vm.openRestricted(item) { context.openFile(it) }
            else -> vm.openInApp(item, shown.orEmpty()) { context.openFile(it) }
        }
    }

    fun startTransfer(move: Boolean) {
        vm.pendingTransfer = PendingTransfer(selected.toList(), move)
        selected = emptySet()
        if (currentFolder == null) vm.navigate(Route.Folder(vm.repo.rootDir.path))
        vm.message("Open a folder, then tap ${if (move) "Move here" else "Copy here"}")
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            when {
                selected.isNotEmpty() -> SelectionTopBar(
                    count = selected.size,
                    allStarred = selected.all { it in vm.starred },
                    single = selectedItems.singleOrNull(),
                    onClose = { selected = emptySet() },
                    onSelectAll = { selected = shown.orEmpty().mapTo(HashSet()) { it.path } },
                    onShare = {
                        if (selectedItems.any { vm.repo.needsCopyToOpen(it.path) }) {
                            vm.message("These files can't be shared directly — copy them to the phone first")
                        } else {
                            context.shareFiles(selectedItems)
                        }
                    },
                    onDelete = { showDelete = true },
                    onStar = { vm.toggleStar(selected) },
                    onCopy = { startTransfer(move = false) },
                    onMove = { startTransfer(move = true) },
                    onRename = { renameTarget = it },
                    onInfo = { infoTarget = it },
                    isShortcut = { it.path in vm.shortcuts },
                    onToggleShortcut = { vm.toggleShortcut(it.path) },
                )

                topBar != null -> topBar()

                else -> TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            subtitle?.invoke()
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        actions()
                        IconButton(onClick = { vm.navigate(Route.Search) }) { Icon(Icons.Default.Search, "Search") }
                        IconButton(onClick = { vm.updateGridView(!vm.gridView) }) {
                            Icon(
                                if (vm.gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                if (vm.gridView) "List view" else "Grid view",
                            )
                        }
                        OverflowMenu {
                            if (sortable) item("Sort by", Icons.AutoMirrored.Filled.Sort) { showSort = true }
                            if (currentFolder != null) {
                                item("New folder", Icons.Default.CreateNewFolder) { showNewFolder = true }
                                val pinned = currentFolder in vm.shortcuts
                                item(
                                    if (pinned) "Remove from drawer" else "Add to drawer",
                                    if (pinned) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkAdd,
                                ) { vm.toggleShortcut(currentFolder) }
                            }
                            if (!shown.isNullOrEmpty()) {
                                item("Select all", Icons.Default.SelectAll) {
                                    selected = shown.mapTo(HashSet()) { it.path }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        },
        bottomBar = {
            vm.pendingTransfer?.let { PasteBar(vm, it, currentFolder) }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { onRefresh?.invoke() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // The header (breadcrumbs, filters) stays put while a folder loads; only the
            // content area below it shows the spinner.
            FileCollection(
                items = shown.orEmpty(),
                loading = shown == null,
                grid = vm.gridView,
                selected = selected,
                starred = vm.starred,
                header = header,
                emptyText = emptyText,
                highlightPath = highlightPath,
                sectionOf = sectionOf,
                onClick = ::open,
                onLongClick = ::toggle,
            )
            if (vm.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    if (showSort) {
        SortSheet(current = vm.sortOrder, onDismiss = { showSort = false }) {
            vm.updateSortOrder(it)
            showSort = false
        }
    }
    if (showDelete) {
        DeleteDialog(
            count = selected.size,
            permanentCount = selected.count { !vm.repo.canTrash(it) },
            onDismiss = { showDelete = false },
        ) {
            vm.delete(selected)
            selected = emptySet()
            showDelete = false
        }
    }
    if (showNewFolder && currentFolder != null) {
        NameDialog("New folder", "", "Create", onDismiss = { showNewFolder = false }) {
            vm.createFolder(currentFolder, it)
            showNewFolder = false
        }
    }
    renameTarget?.let { target ->
        NameDialog("Rename", target.name, "Rename", onDismiss = { renameTarget = null }) {
            vm.rename(target.path, it)
            selected = emptySet()
            renameTarget = null
        }
    }
    infoTarget?.let { InfoDialog(it) { infoTarget = null } }
}

@Composable
private fun FileCollection(
    items: List<FileItem>,
    loading: Boolean,
    grid: Boolean,
    selected: Set<String>,
    starred: Set<String>,
    header: @Composable () -> Unit,
    emptyText: String,
    highlightPath: String?,
    sectionOf: ((FileItem) -> String)?,
    onClick: (FileItem) -> Unit,
    onLongClick: (FileItem) -> Unit,
) {
    val contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp)
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // Consecutive runs of items under one header; a single unlabeled run when not sectioned.
    val sections = remember(items, sectionOf) { sectionsOf(items, sectionOf) }

    // Scroll to the highlighted file once it appears, then let the highlight fade out.
    var flashing by rememberSaveable(highlightPath) { mutableStateOf(highlightPath) }
    val highlightIndex = flashing?.let { path -> lazyIndexOf(sections, path) } ?: -1
    LaunchedEffect(highlightIndex) {
        if (highlightIndex < 0) return@LaunchedEffect
        if (grid) gridState.animateScrollToItem(highlightIndex) else listState.animateScrollToItem(highlightIndex)
        delay(2500)
        flashing = null
    }

    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            state = gridState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            else if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Empty(emptyText) }
            sections.forEach { (label, group) ->
                if (label != null) {
                    item(key = "section:$label", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(label) }
                }
                items(group, key = { it.path }) { item ->
                    FileGridCell(
                        item, item.path in selected, item.path in starred, { onClick(item) }, { onLongClick(item) },
                        highlighted = item.path == flashing,
                    )
                }
            }
        }
    } else {
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item { header() }
            if (loading) item { Loading() }
            else if (items.isEmpty()) item { Empty(emptyText) }
            sections.forEach { (label, group) ->
                if (label != null) item(key = "section:$label") { SectionHeader(label) }
                items(group, key = { it.path }) { item ->
                    FileRow(
                        item, item.path in selected, item.path in starred, { onClick(item) }, { onLongClick(item) },
                        highlighted = item.path == flashing,
                    )
                }
            }
        }
    }
}

private fun sectionsOf(items: List<FileItem>, sectionOf: ((FileItem) -> String)?): List<Pair<String?, List<FileItem>>> {
    if (sectionOf == null) return listOf(null to items)
    val result = mutableListOf<Pair<String?, MutableList<FileItem>>>()
    for (item in items) {
        val label = sectionOf(item)
        if (result.lastOrNull()?.first == label) result.last().second += item else result += label to mutableListOf(item)
    }
    return result
}

/** Position of [path] in the lazy list: after the screen header and any section headers before it. */
private fun lazyIndexOf(sections: List<Pair<String?, List<FileItem>>>, path: String): Int {
    var index = 1 // the screen header
    for ((label, group) in sections) {
        if (label != null) index++
        val inGroup = group.indexOfFirst { it.path == path }
        if (inGroup >= 0) return index + inGroup
        index += group.size
    }
    return -1
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Empty(text: String) {
    EmptyState(Icons.Outlined.FolderOff, text, Modifier.padding(top = 64.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    allStarred: Boolean,
    single: FileItem?,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onStar: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: (FileItem) -> Unit,
    onInfo: (FileItem) -> Unit,
    isShortcut: (FileItem) -> Boolean,
    onToggleShortcut: (FileItem) -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Clear selection") } },
        actions = {
            IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            IconButton(onClick = onStar) {
                Icon(if (allStarred) Icons.Default.Star else Icons.Outlined.StarBorder, "Star")
            }
            OverflowMenu {
                item("Select all", Icons.Default.SelectAll, onSelectAll)
                item("Copy to…", Icons.Default.ContentCopy, onCopy)
                item("Move to…", Icons.AutoMirrored.Filled.DriveFileMove, onMove)
                if (single != null) {
                    item("Rename", Icons.Default.DriveFileRenameOutline) { onRename(single) }
                    item("File info", Icons.Default.Info) { onInfo(single) }
                    if (single.isDirectory) {
                        val pinned = isShortcut(single)
                        item(
                            if (pinned) "Remove from drawer" else "Add to drawer",
                            if (pinned) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkAdd,
                        ) { onToggleShortcut(single) }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

@Composable
private fun PasteBar(vm: ExplorerViewModel, transfer: PendingTransfer, currentFolder: String?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${if (transfer.move) "Moving" else "Copying"} ${ExplorerViewModel.plural(transfer.paths.size)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.pendingTransfer = null }) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { currentFolder?.let(vm::pasteInto) }, enabled = currentFolder != null) {
                Text(if (transfer.move) "Move here" else "Copy here")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(current: SortOrder, onDismiss: () -> Unit, onSelect: (SortOrder) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SortOrder.entries.forEach { order ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(order) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RadioButton(selected = order == current, onClick = { onSelect(order) })
                    Text(order.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

class OverflowMenuScope(private val dismiss: () -> Unit) {
    val entries = mutableListOf<Triple<String, ImageVector, () -> Unit>>()
    fun item(label: String, icon: ImageVector, onClick: () -> Unit) {
        entries += Triple(label, icon, { dismiss(); onClick() })
    }
}

@Composable
fun OverflowMenu(onExpandedChange: (Boolean) -> Unit = {}, content: OverflowMenuScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun setExpanded(value: Boolean) {
        expanded = value
        onExpandedChange(value)
    }
    Box {
        IconButton(onClick = { setExpanded(true) }) { Icon(Icons.Default.MoreVert, "More options") }
        DropdownMenu(expanded = expanded, onDismissRequest = { setExpanded(false) }) {
            OverflowMenuScope { setExpanded(false) }.apply(content).entries.forEach { (label, icon, onClick) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(icon, null) },
                    onClick = onClick,
                )
            }
        }
    }
}
