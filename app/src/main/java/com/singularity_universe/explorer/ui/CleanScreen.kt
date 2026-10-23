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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.data.FileRepository
import kotlin.math.roundToInt

private enum class CleanSection(val title: String, val icon: ImageVector, val hint: String) {
    Large("Large files", Icons.Outlined.SdCard, "Files of 100 MB or more"),
    Duplicates("Duplicate files", Icons.Outlined.ContentCopy, "The same file stored more than once; the oldest copy stays unselected"),
    OldDownloads("Old downloads", Icons.Outlined.Download, "In Download and unchanged for 90 days"),
    Apks("Installer files", Icons.Outlined.Android, "APK files; installed apps don't need them"),
}

/** Free up space: what can be removed, by kind. Deleting moves files to Trash (kept for 30 days). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanScreen(vm: ExplorerViewModel) {
    val report = vm.cleanupReport
    val stage = vm.cleanupStage
    var open by rememberSaveable { mutableStateOf<CleanSection?>(null) }
    LaunchedEffect(Unit) { if (vm.cleanupReport == null) vm.scanCleanup() }
    BackHandler(enabled = open != null) { open = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(open?.title ?: "Free up space") },
                navigationIcon = {
                    IconButton(onClick = { if (open != null) open = null else vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (open == null) IconButton(onClick = vm::scanCleanup, enabled = stage == null) { Icon(Icons.Outlined.Refresh, "Scan again") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                stage != null && report == null -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp).widthIn(max = 360.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val progress = stage.second
                    if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    else CircularProgressIndicator()
                    Text("${stage.first}…${progress?.let { " ${(it * 100).roundToInt()}%" } ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                report == null -> Unit
                open == null -> Overview(report, scanning = stage != null) { open = it }
                else -> SectionList(vm, report, open!!)
            }
        }
    }
}

@Composable
private fun Overview(report: FileRepository.CleanupReport, scanning: Boolean, onOpen: (CleanSection) -> Unit) {
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(12.dp), modifier = Modifier.navigationBarsPadding()) {
        if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
        CleanSection.entries.forEach { section ->
            val items = itemsOf(report, section)
            val bytes = savingsOf(report, section)
            item(key = section.name) {
                ListItem(
                    headlineContent = { Text(section.title) },
                    supportingContent = {
                        Text(
                            if (items.isEmpty()) "Nothing found"
                            else "${items.size} ${if (items.size == 1) "file" else "files"} · ${context.formatSize(bytes)}",
                        )
                    },
                    leadingContent = { Icon(section.icon, null) },
                    modifier = Modifier.padding(vertical = 2.dp).clickable(enabled = items.isNotEmpty()) { onOpen(section) },
                )
            }
        }
        item {
            Text(
                "Deleted files go to Trash, where they stay for 30 days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private fun itemsOf(report: FileRepository.CleanupReport, section: CleanSection): List<FileItem> = when (section) {
    CleanSection.Large -> report.large
    CleanSection.Duplicates -> report.duplicates.flatten()
    CleanSection.OldDownloads -> report.oldDownloads
    CleanSection.Apks -> report.apks.map { it.item }
}

/** What deleting would free: everything listed, except one copy of each duplicate. */
private fun savingsOf(report: FileRepository.CleanupReport, section: CleanSection): Long = when (section) {
    CleanSection.Duplicates -> report.duplicates.sumOf { g -> g.drop(1).sumOf { it.size } }
    else -> itemsOf(report, section).sumOf { it.size }
}

@Composable
private fun SectionList(vm: ExplorerViewModel, report: FileRepository.CleanupReport, section: CleanSection) {
    val context = LocalContext.current
    // Duplicates start with every copy but the oldest selected; installed apps' installers too.
    var selected by remember(section, report) {
        mutableStateOf<Set<String>>(
            when (section) {
                CleanSection.Duplicates -> report.duplicates.flatMap { it.drop(1) }.mapTo(HashSet()) { it.path }
                CleanSection.Apks -> report.apks.filter { it.installed }.mapTo(HashSet()) { it.item.path }
                else -> emptySet()
            }
        )
    }
    val all = itemsOf(report, section)
    val chosenBytes = all.filter { it.path in selected }.sumOf { it.size }
    fun toggle(item: FileItem) {
        selected = if (item.path in selected) selected - item.path else selected + item.path
    }
    fun open(item: FileItem) = vm.openInApp(item, listOf(item)) { context.openFile(it) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            item {
                Text(
                    "${section.hint}. Tap to select, long-press to open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            when (section) {
                CleanSection.Duplicates -> report.duplicates.forEachIndexed { i, group ->
                    item(key = "header-$i") {
                        Text(
                            "${group.size} copies · ${context.formatSize(group.first().size)} each",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(group, key = { it.path }) { item ->
                        FileRow(item, selected = item.path in selected, starred = false, onClick = { toggle(item) }, onLongClick = { open(item) })
                    }
                }
                CleanSection.Apks -> items(report.apks, key = { it.item.path }) { apk ->
                    Column {
                        FileRow(apk.item, selected = apk.item.path in selected, starred = false, onClick = { toggle(apk.item) }, onLongClick = { open(apk.item) })
                        Text(
                            listOfNotNull(apk.label, if (apk.installed) "already installed" else null).joinToString(" · ").ifEmpty { "Not installed" },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (apk.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 84.dp, bottom = 6.dp),
                        )
                    }
                }
                else -> items(all, key = { it.path }) { item ->
                    FileRow(item, selected = item.path in selected, starred = false, onClick = { toggle(item) }, onLongClick = { open(item) })
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        selected = if (selected.size == all.size) emptySet() else all.mapTo(HashSet()) { it.path }
                    }) { Text(if (selected.size == all.size) "Select none" else "Select all") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        vm.deleteFromCleanup(selected)
                        selected = emptySet()
                    }, enabled = selected.isNotEmpty()) {
                        Text(
                            if (selected.isEmpty()) "Move to Trash" else "Move ${selected.size} to Trash · ${context.formatSize(chosenBytes)}",
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
