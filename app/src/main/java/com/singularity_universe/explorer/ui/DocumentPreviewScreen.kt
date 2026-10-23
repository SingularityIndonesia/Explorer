package com.singularity_universe.explorer.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.data.docs.DocxBlock
import com.singularity_universe.explorer.data.docs.DocxDocument
import com.singularity_universe.explorer.data.docs.DocxImage
import com.singularity_universe.explorer.data.docs.DocxParagraph
import com.singularity_universe.explorer.data.docs.DocxReader
import com.singularity_universe.explorer.data.docs.DocxTable
import com.singularity_universe.explorer.data.docs.Ooxml
import com.singularity_universe.explorer.data.docs.PptxReader
import com.singularity_universe.explorer.data.docs.PptxRenderer
import com.singularity_universe.explorer.data.docs.XlsxReader
import com.singularity_universe.explorer.data.docs.XlsxSheet
import com.singularity_universe.explorer.data.docs.XlsxWorkbook
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Document formats the preview can show. Others (.doc, .ppt, .xls...) open in another app. */
enum class DocumentKind { Pdf, Word, Slides, Sheet }

fun documentKindOf(name: String): DocumentKind? = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> DocumentKind.Pdf
    "docx", "docm", "dotx" -> DocumentKind.Word
    "pptx", "pptm", "ppsx", "potx" -> DocumentKind.Slides
    "xlsx", "xlsm", "xltx" -> DocumentKind.Sheet
    else -> null
}

/** A loaded document: what to show, and what to close when the screen goes away. */
private sealed interface Loaded : Closeable {
    val summary: String

    class Pages(val pageCount: Int, val aspect: (Int) -> Float, val render: (Int, Int) -> Bitmap, private val closer: Closeable, override val summary: String) : Loaded {
        override fun close() = closer.close()
    }

    class Word(val document: DocxDocument) : Loaded {
        override val summary = "Word document"
        override fun close() = Unit
    }

    class Sheet(val workbook: XlsxWorkbook) : Loaded {
        override val summary = "${workbook.sheets.size} ${if (workbook.sheets.size == 1) "sheet" else "sheets"}"
        override fun close() = Unit
    }
}

private fun load(file: File, kind: DocumentKind): Loaded = when (kind) {
    DocumentKind.Pdf -> {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (e: Exception) {
            descriptor.close()
            throw e
        }
        // PdfRenderer allows one page open at a time, so all access goes through this lock.
        val lock = Any()
        val aspects = synchronized(lock) {
            FloatArray(renderer.pageCount) { i -> renderer.openPage(i).use { it.height.toFloat() / it.width.coerceAtLeast(1) } }
        }
        Loaded.Pages(
            pageCount = renderer.pageCount,
            aspect = { aspects[it] },
            render = { index, width ->
                synchronized(lock) {
                    renderer.openPage(index).use { page ->
                        val height = (width * page.height.toFloat() / page.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(AndroidColor.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            },
            closer = Closeable {
                synchronized(lock) { renderer.close() }
                descriptor.close()
            },
            summary = "${renderer.pageCount} ${if (renderer.pageCount == 1) "page" else "pages"}",
        )
    }
    DocumentKind.Slides -> {
        val deck = PptxReader.read(file)
        val renderer = PptxRenderer(file, deck)
        val aspect = deck.height.toFloat() / deck.width.coerceAtLeast(1)
        Loaded.Pages(
            pageCount = deck.slides.size,
            aspect = { aspect },
            render = renderer::render,
            closer = renderer,
            summary = "${deck.slides.size} ${if (deck.slides.size == 1) "slide" else "slides"}",
        )
    }
    DocumentKind.Word -> Loaded.Word(DocxReader.read(file))
    DocumentKind.Sheet -> Loaded.Sheet(XlsxReader.read(file))
}

/** Preview of a PDF, Word, PowerPoint or Excel file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentPreviewScreen(vm: ExplorerViewModel, item: FileItem) {
    val context = LocalContext.current
    val kind = documentKindOf(item.name)
    val result by produceState<Result<Loaded>?>(null, item.path) {
        value = withContext(Dispatchers.IO) { runCatching { load(item.file, kind!!) } }
    }
    val loaded = result?.getOrNull()
    DisposableEffect(loaded) { onDispose { loaded?.let { runCatching { it.close() } } } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        loaded?.let {
                            Text(it.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    OverflowMenu {
                        item("Open with", Icons.AutoMirrored.Outlined.OpenInNew) { context.openFile(item) }
                        item("Locate", Icons.Outlined.FolderOpen) { vm.locate(item) }
                        item("Copy to…", Icons.Outlined.ContentCopy) { vm.startTransfer(listOf(item.path), move = false) }
                        item("Move to…", Icons.AutoMirrored.Outlined.DriveFileMove) { vm.startTransfer(listOf(item.path), move = true) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val failure = result?.exceptionOrNull()
            when {
                result == null -> CircularProgressIndicator()
                failure != null -> Text(
                    if (failure is SecurityException) "This PDF is password-protected. Use Open with to view it in another app."
                    else "Couldn't preview this file. Use Open with to view it in another app.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
                loaded is Loaded.Pages -> PageViewer(loaded)
                loaded is Loaded.Word -> WordView(loaded.document)
                loaded is Loaded.Sheet -> SheetView(loaded.workbook)
            }
        }
    }
}

// ---- Pages (PDF, slides) ----

/**
 * Pages one under another. Pinch or double-tap zooms (up to 4×) and the zoomed pages pan sideways;
 * pages are rendered again at the new size once a zoom settles, so text stays sharp.
 */
@Composable
private fun PageViewer(document: Loaded.Pages) {
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    // Pages are rendered at this scale; it follows [scale] once a pinch pauses.
    var renderScale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(scale) {
        delay(250)
        renderScale = scale
    }
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    val cache = remember(document) {
        val maxBytes = (Runtime.getRuntime().maxMemory() / 6).toInt()
        object : LruCache<String, Bitmap>(maxBytes) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    }

    fun zoomTo(target: Float, focus: androidx.compose.ui.geometry.Offset) {
        val newScale = target.coerceIn(1f, 4f)
        val ratio = newScale / scale
        if (ratio == 1f) return
        scale = newScale
        // Keep the point under the fingers in place.
        scope.launch {
            horizontal.scrollTo(((horizontal.value + focus.x) * ratio - focus.x).roundToInt().coerceAtLeast(0))
        }
        scope.launch {
            listState.scrollBy((listState.firstVisibleItemScrollOffset + focus.y) * (ratio - 1))
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // Two fingers zoom; one finger is left to the lists, which scroll.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) zoomTo(scale * zoom, event.calculateCentroid())
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { zoomTo(if (scale > 1.2f) 1f else 2.5f, it) })
            },
    ) {
        val viewportPx = constraints.maxWidth
        val contentWidth = with(density) { (viewportPx * scale).toDp() }
        Box(Modifier.fillMaxSize().horizontalScroll(horizontal, enabled = scale > 1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(contentWidth).fillMaxHeight(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(document.pageCount) { index ->
                    val pageWidthPx = ((viewportPx * renderScale) - with(density) { 16.dp.toPx() }).roundToInt().coerceIn(64, 4096)
                    val key = "$index@$pageWidthPx"
                    val bitmap by produceState(cache.get(key), key) {
                        if (value == null) {
                            value = withContext(Dispatchers.IO) {
                                runCatching { document.render(index, pageWidthPx) }.getOrNull()?.also { cache.put(key, it) }
                            }
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f / document.aspect(index))
                            .background(Color.White),
                    ) {
                        bitmap?.let {
                            Image(it.asImageBitmap(), contentDescription = "Page ${index + 1}", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

// ---- Word ----

private val PaperText = Color(0xFF202124)

/** The document's text on a white page: headings, lists, tables and pictures, reflowed to fit. */
@Composable
private fun WordView(document: DocxDocument) {
    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentPadding = PaddingValues(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(document.blocks) { block ->
                Box(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 2.dp)) {
                    WordBlock(document.file, block)
                }
            }
            item { Spacer(Modifier.height(4.dp).navigationBarsPadding()) }
        }
    }
}

@Composable
private fun WordBlock(file: File, block: DocxBlock) {
    when (block) {
        is DocxParagraph -> WordParagraph(block)
        is DocxTable -> WordTable(file, block)
        is DocxImage -> WordImage(file, block.path)
    }
}

@Composable
private fun WordParagraph(p: DocxParagraph) {
    if (p.isEmpty) {
        Spacer(Modifier.height(10.dp))
        return
    }
    val heading = p.level in 1..6
    val baseSize = if (heading) HEADING_SIZES[p.level - 1] else 15f
    val text = remember(p) {
        buildAnnotatedString {
            p.prefix?.let { if (it.isNotEmpty()) append("$it  ") }
            for (run in p.runs) {
                withStyle(
                    SpanStyle(
                        fontWeight = if (run.bold || heading) FontWeight.Bold else null,
                        fontStyle = if (run.italic) FontStyle.Italic else null,
                        textDecoration = when {
                            run.underline && run.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                            run.underline -> TextDecoration.Underline
                            run.strike -> TextDecoration.LineThrough
                            else -> null
                        },
                        // Word sizes are points; 1pt ≈ 1.35sp keeps 11pt body text at a comfortable size.
                        fontSize = if (run.size > 0f && !heading) (run.size * 1.35f).coerceIn(9f, 48f).sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                        color = run.color?.let { Color(0xFF000000 or it.toLong()) } ?: Color.Unspecified,
                        background = run.highlight?.let { Color(0xFF000000 or it.toLong()) } ?: Color.Unspecified,
                    )
                ) { append(run.text) }
            }
        }
    }
    Text(
        text,
        style = TextStyle(fontSize = baseSize.sp, lineHeight = (baseSize * 1.45f).sp, color = PaperText),
        textAlign = when (p.align) {
            DocxParagraph.Align.Center -> TextAlign.Center
            DocxParagraph.Align.End -> TextAlign.End
            DocxParagraph.Align.Justify -> TextAlign.Justify
            DocxParagraph.Align.Start -> TextAlign.Start
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (p.indent.coerceIn(0, 8) * 16).dp, top = if (heading) 12.dp else 3.dp, bottom = 3.dp),
    )
}

private val HEADING_SIZES = floatArrayOf(24f, 20f, 18f, 16.5f, 15.5f, 15f)

@Composable
private fun WordTable(file: File, table: DocxTable) {
    val columns = table.rows.maxOfOrNull { it.size } ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).border(0.5.dp, Color.Gray)) {
        table.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                for (i in 0 until columns) {
                    Column(Modifier.weight(1f).fillMaxHeight().border(0.5.dp, Color.Gray).padding(6.dp)) {
                        row.getOrNull(i)?.forEach { WordBlock(file, it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WordImage(file: File, path: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        val maxWidth = constraints.maxWidth
        val bitmap by produceState<Bitmap?>(null, path) {
            value = withContext(Dispatchers.IO) { runCatching { ZipFile(file).use { Ooxml.image(it, path, maxWidth) } }.getOrNull() }
        }
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
    }
}

// ---- Excel ----

private val CELL_WIDTH = 112.dp
private val ROW_HEADER_WIDTH = 52.dp
private val CELL_HEIGHT = 32.dp

/** Sheets as tabs; each a grid with column letters and row numbers. Only visible columns are drawn. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetView(workbook: XlsxWorkbook) {
    var selected by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        if (workbook.sheets.size > 1) {
            PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                workbook.sheets.forEachIndexed { index, sheet ->
                    Tab(selected = index == selected, onClick = { selected = index }, text = { Text(sheet.name, maxLines = 1) })
                }
            }
        }
        val sheet = workbook.sheets.getOrNull(selected)
        if (sheet == null || sheet.rows == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This sheet is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            SheetGrid(sheet)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SheetGrid(sheet: XlsxSheet) {
    val density = LocalDensity.current
    val horizontal = rememberScrollState()
    val columns = maxOf(sheet.columns, 1)
    val totalWidth = ROW_HEADER_WIDTH + CELL_WIDTH * columns
    val gridLine = MaterialTheme.colorScheme.outlineVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceVariant
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cellPx = with(density) { CELL_WIDTH.toPx() }
        val viewportPx = constraints.maxWidth
        // Columns on screen (with one spare each side), from the horizontal scroll position.
        val first = ((horizontal.value - with(density) { ROW_HEADER_WIDTH.toPx() }) / cellPx).toInt().coerceIn(0, columns - 1)
        val last = ((horizontal.value + viewportPx) / cellPx).toInt().plus(1).coerceIn(first, columns - 1)
        val visible = first..last

        Box(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
            LazyColumn(Modifier.width(totalWidth).fillMaxHeight()) {
                stickyHeader {
                    Row(Modifier.background(headerBackground)) {
                        Cell("", ROW_HEADER_WIDTH, gridLine, header = true)
                        Spacer(Modifier.width(CELL_WIDTH * first))
                        for (c in visible) Cell(XlsxReader.columnName(c), CELL_WIDTH, gridLine, header = true)
                    }
                }
                items(sheet.rows) { r ->
                    val row = sheet.cells[r]
                    Row {
                        Cell("${r + 1}", ROW_HEADER_WIDTH, gridLine, header = true, background = headerBackground)
                        Spacer(Modifier.width(CELL_WIDTH * first))
                        for (c in visible) Cell(row?.get(c).orEmpty(), CELL_WIDTH, gridLine, alignEnd = row?.get(c)?.let(::looksNumeric) == true)
                    }
                }
                if (sheet.truncated) {
                    item {
                        Text(
                            "Only the first part of this sheet is shown. Use Open with to see all of it.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    line: Color,
    header: Boolean = false,
    alignEnd: Boolean = false,
    background: Color = Color.Unspecified,
) {
    Box(
        Modifier
            .width(width)
            .height(CELL_HEIGHT)
            .background(background)
            .border(0.25.dp, line)
            .padding(horizontal = 6.dp),
        contentAlignment = when {
            header -> Alignment.Center
            alignEnd -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (header) FontWeight.Medium else null),
            color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun looksNumeric(text: String) = text.isNotEmpty() && text.all { it.isDigit() || it in "-.,%:/ " } && text.any { it.isDigit() }
