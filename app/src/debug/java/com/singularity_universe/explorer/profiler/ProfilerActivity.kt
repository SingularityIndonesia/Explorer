package com.singularity_universe.explorer.profiler

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity_universe.explorer.ui.theme.ExplorerTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug builds only: what the app's functions did while recording, and how memory went. */
class ProfilerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MemorySampler.start()
        setContent { ExplorerTheme { ProfilerScreen() } }
    }
}

private enum class SortBy(val label: String) { Total("Total"), Self("Self"), Calls("Calls"), Average("Average"), Max("Longest") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(CallTrace.recording) }
    // Bumped once a second (and after actions) so the lists re-read the recorder.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            recording = CallTrace.recording
            if (recording || tab == 2) tick++
        }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Profiler")
                        Text(
                            if (recording) "Recording" else if (CallTrace.totalCalls > 0) "Paused" else "Not recording",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (CallTrace.recording) Profiler.stop() else Profiler.start()
                        recording = CallTrace.recording
                        tick++
                    }) {
                        if (recording) Icon(Icons.Default.Pause, "Stop recording")
                        else Icon(Icons.Default.FiberManualRecord, "Start recording", tint = MaterialTheme.colorScheme.error)
                    }
                    var menu by remember { mutableStateOf(false) }
                    var atLaunch by remember { mutableStateOf(Profiler.recordAtLaunch) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Clear") }, onClick = {
                            menu = false
                            Profiler.clear()
                            tick++
                        })
                        DropdownMenuItem(
                            text = { Text("Record from app launch") },
                            trailingIcon = { Checkbox(checked = atLaunch, onCheckedChange = null) },
                            onClick = {
                                atLaunch = !atLaunch
                                Profiler.recordAtLaunch = atLaunch
                            },
                        )
                        DropdownMenuItem(text = { Text("Export as CSV") }, onClick = {
                            menu = false
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { exportCsv(outputFolder(context)) } }
                                toast(result.fold({ "Saved to ${it.path}" }, { "Couldn't export: ${it.message}" }))
                            }
                        })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Functions", "Timeline", "Memory").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            when (tab) {
                0 -> FunctionsTab(tick)
                1 -> TimelineTab(tick)
                else -> MemoryTab(tick, onMessage = ::toast)
            }
        }
    }
}

// ---- Functions ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FunctionsTab(tick: Int) {
    var sort by remember { mutableStateOf(SortBy.Total) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CallTrace.FunctionSnapshot?>(null) }
    val all = remember(tick) { CallTrace.functions() }
    val shown = remember(all, sort, query) {
        all.asSequence()
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .sortedByDescending {
                when (sort) {
                    SortBy.Total -> it.totalNanos
                    SortBy.Self -> it.selfNanos
                    SortBy.Calls -> it.calls
                    SortBy.Average -> it.averageNanos
                    SortBy.Max -> it.maxNanos
                }
            }
            .take(500)
            .toList()
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(query, "Search functions") { query = it }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortBy.entries.forEach { option ->
                FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option.label) })
            }
        }
        Text(
            "${number(all.size.toLong())} functions · ${number(all.sumOf { it.calls })} calls" +
                if (all.size > shown.size) " · top ${shown.size} shown" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (all.isEmpty()) {
            Empty("Nothing recorded yet. Tap the record button, use the app, then come back here.")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.navigationBarsPadding()) {
                items(shown, key = { it.name }) { function ->
                    FunctionRow(function, sort) { selected = function }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                }
            }
        }
    }

    selected?.let { function ->
        ModalBottomSheet(onDismissRequest = { selected = null }) { FunctionDetail(function) }
    }
}

@Composable
private fun FunctionRow(function: CallTrace.FunctionSnapshot, sort: SortBy, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val owner = function.name.substringBeforeLast('.', "")
        Text(function.name.substringAfterLast('.'), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (owner.isNotEmpty()) {
            Text(owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Metric("calls", number(function.calls), sort == SortBy.Calls, Modifier.weight(1f))
            Metric("total", duration(function.totalNanos), sort == SortBy.Total, Modifier.weight(1f))
            Metric("self", duration(function.selfNanos), sort == SortBy.Self, Modifier.weight(1f))
            Metric("avg", duration(function.averageNanos), sort == SortBy.Average, Modifier.weight(1f))
            Metric("max", duration(function.maxNanos), sort == SortBy.Max, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, highlighted: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FunctionDetail(function: CallTrace.FunctionSnapshot) {
    val calls = remember(function) { CallTrace.recentCalls(limit = 300) { it == function.name } }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text(function.name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        DetailLine("Calls", number(function.calls))
        DetailLine("Total time", duration(function.totalNanos))
        DetailLine("Self time", "${duration(function.selfNanos)} (without the traced functions it calls)")
        DetailLine("Average", duration(function.averageNanos))
        DetailLine("Longest", duration(function.maxNanos))
        DetailLine("First call", at(function.firstNanos))
        DetailLine("Last call", at(function.lastNanos))
        Spacer(Modifier.height(12.dp))
        Text("Recent calls", style = MaterialTheme.typography.titleSmall)
        if (calls.isEmpty()) {
            Text("None left in the timeline.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.height(320.dp)) {
            items(calls) { call ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(at(call.startNanos), style = MonoSmall, modifier = Modifier.width(96.dp))
                    Text(call.thread, style = MonoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(duration(call.durationNanos), style = MonoSmall)
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- Timeline ----

@Composable
private fun TimelineTab(tick: Int) {
    var query by remember { mutableStateOf("") }
    var mainOnly by remember { mutableStateOf(false) }
    val calls = remember(tick, query, mainOnly) {
        CallTrace.recentCalls(limit = 3000) { query.isBlank() || it.contains(query, ignoreCase = true) }
            .filter { !mainOnly || it.thread == "main" }
    }
    Column(Modifier.fillMaxSize()) {
        SearchField(query, "Search calls") { query = it }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = mainOnly, onClick = { mainOnly = !mainOnly }, label = { Text("Main thread only") })
            Spacer(Modifier.width(12.dp))
            Text(
                "Newest first · ${number(CallTrace.totalCalls)} recorded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (calls.isEmpty()) {
            Empty("No calls recorded yet.")
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.navigationBarsPadding()) {
                items(calls) { call ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(at(call.startNanos), style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp))
                        Text(
                            call.thread.take(10),
                            style = MonoSmall,
                            color = if (call.thread == "main") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            modifier = Modifier.width(72.dp),
                        )
                        Text(
                            "  ".repeat(minOf(call.depth, 12)) + shortName(call.name),
                            style = MonoSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(duration(call.durationNanos), style = MonoSmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

// ---- Memory ----

@Composable
private fun MemoryTab(tick: Int, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val samples = remember(tick) { MemorySampler.samples() }
    val now = samples.lastOrNull()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .navigationBarsPadding(),
    ) {
        if (now == null) {
            Text("Collecting the first sample…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Java heap (of ${bytes(now.javaMax)})", bytes(now.javaUsed), ChartJava, Modifier.weight(1f))
            StatTile("Native heap", bytes(now.nativeUsed), ChartNative, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Total (PSS)", bytes(now.pss), ChartPss, Modifier.weight(1f))
            StatTile("Garbage collections", number(now.gcCount), null, Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        Text("Last ${samples.size} seconds", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        MemoryChart(samples, Modifier.fillMaxWidth().height(200.dp))
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = {
                Runtime.getRuntime().gc()
                onMessage("Garbage collection requested")
            }) { Text("Run GC") }
            FilledTonalButton(onClick = {
                onMessage("Dumping the heap…")
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { MemorySampler.dumpHeap(outputFolder(context)) } }
                    onMessage(result.fold({ "Heap dump saved to ${it.path}. Open it in Android Studio's profiler." }, { "Couldn't dump the heap: ${it.message}" }))
                }
            }) { Text("Dump heap") }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Java heap: objects the app's code created. Native heap: bitmaps, video and audio codecs, " +
                "and C libraries. PSS: everything the system counts against the app, including code and graphics.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color?, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (color != null) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private val ChartJava = Color(0xFF56A8F5)
private val ChartNative = Color(0xFFCF8E6D)
private val ChartPss = Color(0xFF6AAB73)

@Composable
private fun MemoryChart(samples: List<MemorySampler.Sample>, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val top = samples.maxOf { maxOf(it.javaUsed, it.nativeUsed, it.pss) } * 1.1f
        for (k in 1..3) {
            val y = size.height * k / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y))
        }
        fun series(value: (MemorySampler.Sample) -> Long, color: Color) {
            val path = Path()
            samples.forEachIndexed { index, sample ->
                val x = size.width * index / (samples.size - 1)
                val y = size.height * (1f - value(sample) / top)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
        series({ it.pss }, ChartPss)
        series({ it.nativeUsed }, ChartNative)
        series({ it.javaUsed }, ChartJava)
    }
}

// ---- Shared ----

@Composable
private fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val MonoSmall = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

/** Profiler output goes to Download/Explorer profiler, where the app itself can open it. */
private fun outputFolder(context: android.content.Context): File =
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Explorer profiler")
        .takeIf { Environment.isExternalStorageManager() }
        ?: File(context.getExternalFilesDir(null), "profiler")

private fun exportCsv(directory: File): File {
    directory.mkdirs()
    val file = File(directory, "calls-${System.currentTimeMillis()}.csv")
    file.bufferedWriter().use { out ->
        out.appendLine("function,calls,total_ms,self_ms,average_us,max_ms,first_call_s,last_call_s")
        CallTrace.functions().sortedByDescending { it.totalNanos }.forEach {
            out.appendLine(
                listOf(
                    it.name, it.calls, it.totalNanos / 1e6, it.selfNanos / 1e6, it.averageNanos / 1e3, it.maxNanos / 1e6,
                    seconds(it.firstNanos), seconds(it.lastNanos),
                ).joinToString(","),
            )
        }
    }
    val timeline = File(directory, file.name.replace("calls-", "timeline-"))
    timeline.bufferedWriter().use { out ->
        out.appendLine("start_s,thread,depth,function,duration_us")
        CallTrace.recentCalls().asReversed().forEach {
            out.appendLine("${seconds(it.startNanos)},\"${it.thread}\",${it.depth},${it.name},${it.durationNanos / 1e3}")
        }
    }
    return file
}

/** "ui.FileListScreenKt.FileListScreen" → "FileListScreenKt.FileListScreen": class and function. */
private fun shortName(name: String): String {
    val method = name.lastIndexOf('.')
    val owner = name.lastIndexOf('.', method - 1)
    return if (owner < 0) name else name.substring(owner + 1)
}

private fun seconds(nanos: Long) = String.format(Locale.US, "%.6f", (nanos - CallTrace.startedAt) / 1e9)

/** Time since recording started, like "+12.345 s". */
private fun at(nanos: Long) = String.format(Locale.US, "+%.3f s", (nanos - CallTrace.startedAt) / 1e9)

private fun duration(nanos: Long): String = when {
    nanos < 1_000 -> "$nanos ns"
    nanos < 1_000_000 -> String.format(Locale.US, "%.1f µs", nanos / 1e3)
    nanos < 1_000_000_000 -> String.format(Locale.US, "%.1f ms", nanos / 1e6)
    else -> String.format(Locale.US, "%.2f s", nanos / 1e9)
}

private fun number(value: Long) = String.format(Locale.US, "%,d", value)

private fun bytes(value: Long): String = when {
    value < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", value / 1024.0)
    value < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024 * 1024))
}
