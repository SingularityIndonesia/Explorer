package com.singularity_universe.explorer.data.docs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.LruCache
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.roundToInt
import org.xmlpull.v1.XmlPullParser

/**
 * A PowerPoint deck: slide size (in EMU, 914400 per inch) and each slide's shapes, with positions,
 * colours and font sizes already resolved through the slide's layout, master and theme.
 * Charts, SmartArt, rotation and effects aren't drawn; this is a preview, not PowerPoint.
 */
class PptxDeck(val width: Long, val height: Long, val slides: List<PptxSlide>)

class PptxSlide(val background: PptxFill?, val shapes: List<PptxShape>)

sealed interface PptxFill {
    data class Solid(val color: Int) : PptxFill
    data class Picture(val path: String) : PptxFill
}

sealed interface PptxShape {
    val box: Box

    /** A filled shape, possibly with text in it (a text box has no fill). */
    class Text(
        override val box: Box,
        val fill: Int?,
        val outline: Int?,
        val geometry: String,
        val paragraphs: List<PptxParagraph>,
        val anchor: String,
        val insets: Box,
        val wrap: Boolean,
        val fontScale: Float,
    ) : PptxShape

    class Picture(override val box: Box, val path: String) : PptxShape

    /** A table: column widths and row heights in EMU, and each cell's paragraphs and fill. */
    class Table(
        override val box: Box,
        val columns: List<Float>,
        val rows: List<Pair<Float, List<Pair<List<PptxParagraph>, Int?>>>>,
    ) : PptxShape
}

class PptxParagraph(val runs: List<PptxRun>, val align: String, val bullet: String?, val level: Int, val emptySize: Float)

/** [size] in points. */
class PptxRun(val text: String, val size: Float, val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val color: Int)

object PptxReader {
    private const val PRESENTATION = "ppt/presentation.xml"
    private const val EMU_PER_POINT = 12700f

    @Throws(IOException::class)
    fun read(file: File): PptxDeck = ZipFile(file).use { zip ->
        val rels = Ooxml.relationships(zip, PRESENTATION)
        val parser = Ooxml.parser(zip, PRESENTATION) ?: throw IOException("Not a PowerPoint file")
        var width = 9144000L
        var height = 6858000L
        val slidePaths = ArrayList<String>()
        parser.forEachStart { name ->
            when (name) {
                "sldSz" -> {
                    width = parser.attr("cx")?.toLongOrNull() ?: width
                    height = parser.attr("cy")?.toLongOrNull() ?: height
                }
                "sldId" -> parser.getAttributeValue(Ooxml.NS_RELATIONSHIPS, "id")?.let { rels[it] }?.let { slidePaths += it }
            }
        }
        val context = Context(zip)
        PptxDeck(width, height, slidePaths.map { context.slide(it) })
    }

    /** What a part (slide, layout or master) contributes: its own shapes, placeholders and background. */
    private class Part(
        val shapes: List<PptxShape>,
        val placeholders: Map<String, Placeholder>,
        val background: PptxFill?,
        val showMasterShapes: Boolean,
        val parent: Part?,
        val textStyles: TextStyles?,
        val theme: Theme,
    )

    /** A placeholder's position and text look, for slides that leave them to their layout. */
    private class Placeholder(val type: String, val idx: String?, val box: Box?, val levelSizes: Map<Int, Float>, val color: Int?)

    /** Master text defaults per level: (size in points, colour). */
    private class TextStyles(val title: Map<Int, Pair<Float?, Int?>>, val body: Map<Int, Pair<Float?, Int?>>, val other: Map<Int, Pair<Float?, Int?>>)

    private class Context(val zip: ZipFile) {
        private val parts = HashMap<String, Part>()
        private val themes = HashMap<String, Theme>()

        fun slide(path: String): PptxSlide {
            val part = part(path, kind = 0)
            val shapes = ArrayList<PptxShape>()
            // Decorations from the master and layout (logos, bars) come first, unless turned off.
            val layout = part.parent
            val master = layout?.parent
            if (part.showMasterShapes) {
                if (master != null && layout.showMasterShapes) shapes += master.shapes
                if (layout != null) shapes += layout.shapes
            }
            shapes += part.shapes
            val background = part.background ?: layout?.background ?: master?.background
            return PptxSlide(background, shapes)
        }

        /** [kind]: 0 slide, 1 layout, 2 master. Placeholders' prompt text is skipped on layouts and masters. */
        fun part(path: String, kind: Int): Part = parts.getOrPut(path) {
            val rels = Ooxml.relationships(zip, path)
            val parentPath = rels.values.firstOrNull {
                if (kind == 0) it.contains("slideLayouts/") else it.contains("slideMasters/")
            }
            val parent = if (kind < 2 && parentPath != null) part(parentPath, kind + 1) else null
            val themePath = if (kind == 2) rels.values.firstOrNull { it.contains("theme/") } else null
            val theme = themePath?.let { theme(it) } ?: parent?.theme ?: Theme.DEFAULT
            ShapeParser(zip, path, rels, kind, parent, theme).read()
        }

        private fun theme(path: String) = themes.getOrPut(path) { Theme.read(zip, path) }
    }

    /** Colour scheme of the theme: scheme name ("accent1", "dk1"...) → RGB. */
    private class Theme(val colors: Map<String, Int>) {
        fun color(name: String): Int? = colors[ALIASES[name] ?: name]

        companion object {
            private val ALIASES = mapOf("tx1" to "dk1", "bg1" to "lt1", "tx2" to "dk2", "bg2" to "lt2")
            val DEFAULT = Theme(mapOf("dk1" to Argb.BLACK, "lt1" to Argb.WHITE, "dk2" to 0xFF44546A.toInt(), "lt2" to 0xFFE7E6E6.toInt(),
                "accent1" to 0xFF4472C4.toInt(), "accent2" to 0xFFED7D31.toInt(), "accent3" to 0xFFA5A5A5.toInt(),
                "accent4" to 0xFFFFC000.toInt(), "accent5" to 0xFF5B9BD5.toInt(), "accent6" to 0xFF70AD47.toInt()))

            fun read(zip: ZipFile, path: String): Theme {
                val parser = Ooxml.parser(zip, path) ?: return DEFAULT
                val colors = HashMap(DEFAULT.colors)
                parser.forEachStart { name ->
                    if (name == "clrScheme") {
                        parser.children { slot ->
                            parser.children { value ->
                                when (value) {
                                    "srgbClr" -> parser.attr("val")?.toIntOrNull(16)?.let { colors[slot] = it or 0xFF000000.toInt() }
                                    "sysClr" -> (parser.attr("lastClr") ?: parser.attr("val"))?.toIntOrNull(16)?.let { colors[slot] = it or 0xFF000000.toInt() }
                                }
                            }
                        }
                    }
                }
                return Theme(colors)
            }
        }
    }

    /** Reads one part's shape tree. */
    private class ShapeParser(
        val zip: ZipFile,
        val path: String,
        val rels: Map<String, String>,
        val kind: Int,
        val parent: Part?,
        val theme: Theme,
    ) {
        val parser: XmlPullParser = Ooxml.parser(zip, path) ?: throw IOException("Missing part $path")
        val shapes = ArrayList<PptxShape>()
        val placeholders = HashMap<String, Placeholder>()
        var background: PptxFill? = null
        var showMasterShapes = true
        var textStyles: TextStyles? = null

        fun read(): Part {
            parser.forEachStart { name ->
                when (name) {
                    "sld", "sldLayout" -> showMasterShapes = parser.attr("showMasterSp") != "0"
                    "bg" -> background = backgroundFill()
                    "spTree" -> tree(Transform.IDENTITY)
                    "txStyles" -> textStyles = txStyles()
                }
            }
            return Part(shapes, placeholders, background, showMasterShapes, parent, textStyles, theme)
        }

        private fun backgroundFill(): PptxFill? {
            var fill: PptxFill? = null
            parser.children { name ->
                when (name) {
                    "bgPr" -> parser.children { f -> fill(f)?.let { fill = it } }
                    "bgRef" -> parser.children { c -> color(c)?.let { fill = PptxFill.Solid(it) } }
                }
            }
            return fill
        }

        /** A fill element: solid colour, first gradient stop, or picture. */
        private fun fill(name: String): PptxFill? = when (name) {
            "solidFill" -> {
                var c: Int? = null
                parser.children { child -> color(child)?.let { c = it } }
                c?.let { PptxFill.Solid(it) }
            }
            "gradFill" -> {
                var c: Int? = null
                parser.children { child ->
                    if (child == "gsLst") parser.children { gs -> if (gs == "gs" && c == null) parser.children { k -> color(k)?.let { c = it } } }
                }
                c?.let { PptxFill.Solid(it) }
            }
            "blipFill" -> {
                var p: String? = null
                parser.children { child -> if (child == "blip") p = parser.getAttributeValue(Ooxml.NS_RELATIONSHIPS, "embed")?.let { rels[it] } }
                p?.let { PptxFill.Picture(it) }
            }
            else -> null
        }

        /** A colour element (srgbClr, schemeClr, sysClr, prstClr) with lumMod/lumOff/tint/shade/alpha applied. */
        private fun color(name: String): Int? {
            val base = when (name) {
                "srgbClr" -> parser.attr("val")?.toIntOrNull(16)?.or(0xFF000000.toInt())
                "schemeClr" -> parser.attr("val")?.let { theme.color(it) }
                "sysClr" -> (parser.attr("lastClr") ?: "000000").toIntOrNull(16)?.or(0xFF000000.toInt())
                "prstClr" -> PRESET_COLORS[parser.attr("val")]
                else -> return null
            } ?: return null
            var lumMod = 1f
            var lumOff = 0f
            var tint: Float? = null
            var shade: Float? = null
            parser.children { mod ->
                val v = (parser.attr("val")?.toFloatOrNull() ?: 100000f) / 100000f
                when (mod) {
                    "lumMod" -> lumMod = v
                    "lumOff" -> lumOff = v
                    "tint" -> tint = v
                    "shade" -> shade = v
                }
            }
            return adjust(base, lumMod, lumOff, tint, shade)
        }

        private fun tree(transform: Transform) {
            parser.children { name ->
                when (name) {
                    "sp" -> shape(transform)
                    "pic" -> picture(transform)
                    "grpSp" -> group(transform)
                    "graphicFrame" -> graphicFrame(transform)
                    "AlternateContent" -> parser.children { alt -> if (alt == "Fallback") tree(transform) }
                }
            }
        }

        private fun group(outer: Transform) {
            var transform = outer
            parser.children { name ->
                when (name) {
                    "grpSpPr" -> parser.children { p -> if (p == "xfrm") transform = outer.then(groupTransform()) }
                    "sp" -> shape(transform)
                    "pic" -> picture(transform)
                    "grpSp" -> group(transform)
                    "graphicFrame" -> graphicFrame(transform)
                }
            }
        }

        /** Maps a group's child coordinates (chOff/chExt) to its own box (off/ext). */
        private fun groupTransform(): Transform {
            var off = floatArrayOf(0f, 0f)
            var ext = floatArrayOf(1f, 1f)
            var chOff = floatArrayOf(0f, 0f)
            var chExt = floatArrayOf(1f, 1f)
            parser.children { name ->
                val pair = when (name) {
                    "off", "chOff" -> floatArrayOf(parser.attr("x")?.toFloatOrNull() ?: 0f, parser.attr("y")?.toFloatOrNull() ?: 0f)
                    "ext", "chExt" -> floatArrayOf(parser.attr("cx")?.toFloatOrNull() ?: 1f, parser.attr("cy")?.toFloatOrNull() ?: 1f)
                    else -> null
                }
                when (name) {
                    "off" -> off = pair!!
                    "ext" -> ext = pair!!
                    "chOff" -> chOff = pair!!
                    "chExt" -> chExt = pair!!
                }
            }
            val sx = if (chExt[0] != 0f) ext[0] / chExt[0] else 1f
            val sy = if (chExt[1] != 0f) ext[1] / chExt[1] else 1f
            return Transform(sx, sy, off[0] - chOff[0] * sx, off[1] - chOff[1] * sy)
        }

        private fun xfrm(): Box {
            var x = 0f
            var y = 0f
            var w = 0f
            var h = 0f
            parser.children { name ->
                when (name) {
                    "off" -> {
                        x = parser.attr("x")?.toFloatOrNull() ?: 0f
                        y = parser.attr("y")?.toFloatOrNull() ?: 0f
                    }
                    "ext" -> {
                        w = parser.attr("cx")?.toFloatOrNull() ?: 0f
                        h = parser.attr("cy")?.toFloatOrNull() ?: 0f
                    }
                }
            }
            return Box(x, y, x + w, y + h)
        }

        private fun placeholderKey(type: String, idx: String?) = if (idx != null) "idx:$idx" else "type:$type"

        /** The layout's (or master's) placeholder a slide's placeholder inherits from. */
        private fun inherited(type: String, idx: String?): Placeholder? {
            var part = parent
            while (part != null) {
                val match = idx?.let { part!!.placeholders["idx:$it"] }?.takeIf { it.box != null || it.levelSizes.isNotEmpty() }
                    ?: part.placeholders["type:${normalize(type)}"]
                    ?: part.placeholders.values.firstOrNull { normalize(it.type) == normalize(type) }
                if (match != null) return match
                part = part.parent
            }
            return null
        }

        private fun normalize(type: String) = when (type) {
            "ctrTitle" -> "title"
            "subTitle", "obj", "" -> "body"
            else -> type
        }

        private fun shape(transform: Transform) {
            var box: Box? = null
            var phType: String? = null
            var phIdx: String? = null
            var fill: Int? = null
            var noFill = false
            var styleFill: Int? = null
            var outline: Int? = null
            var geometry = "rect"
            var body: TextBody? = null
            parser.children { name ->
                when (name) {
                    "nvSpPr" -> parser.children { nv ->
                        if (nv == "nvPr") parser.children { p ->
                            if (p == "ph") {
                                phType = parser.attr("type") ?: ""
                                phIdx = parser.attr("idx")
                            }
                        }
                    }
                    "spPr" -> parser.children { p ->
                        when (p) {
                            "xfrm" -> box = xfrm()
                            "prstGeom" -> geometry = parser.attr("prst") ?: "rect"
                            "noFill" -> noFill = true
                            "solidFill", "gradFill" -> (fill(p) as? PptxFill.Solid)?.let { fill = it.color }
                            "ln" -> parser.children { l ->
                                if (l == "solidFill") (fill(l) as? PptxFill.Solid)?.let { outline = it.color }
                            }
                        }
                    }
                    "style" -> parser.children { s ->
                        if (s == "fillRef" && (parser.attr("idx")?.toIntOrNull() ?: 0) > 0) {
                            parser.children { c -> color(c)?.let { styleFill = it } }
                        }
                    }
                    "txBody" -> body = textBody()
                }
            }
            val type = phType
            val inherit = type?.let { inherited(it, phIdx) }
            val resolved = (box ?: inherit?.box)?.let { transform.map(it) }

            // Layouts and masters: remember placeholders (position and text defaults) for slides.
            if (kind > 0 && type != null) {
                val ph = Placeholder(type, phIdx, resolved, body?.levelSizes.orEmpty(), body?.levelColor)
                placeholders[placeholderKey(type, phIdx)] = ph
                if (phIdx != null) placeholders.putIfAbsent("type:${normalize(type)}", ph)
                return
            }
            if (resolved == null || resolved.width() <= 0f || resolved.height() <= 0f) return

            val shapeFill = if (noFill) null else fill ?: styleFill
            val paragraphs = body?.let { b -> resolveParagraphs(b, type, inherit, shapeFill) }.orEmpty()
            if (shapeFill == null && outline == null && paragraphs.none { p -> p.runs.any { it.text.isNotBlank() } }) return
            shapes += PptxShape.Text(
                resolved, shapeFill, outline, geometry, paragraphs,
                body?.anchor ?: (if (normalize(type ?: "") == "title") "ctr" else "t"),
                body?.insets ?: DEFAULT_INSETS, body?.wrap ?: true, body?.fontScale ?: 1f,
            )
        }

        /** Text with sizes and colours filled in from the placeholder, master styles and theme. */
        private fun resolveParagraphs(body: TextBody, type: String?, inherit: Placeholder?, fill: Int?): List<PptxParagraph> {
            val styles = generateSequence(parent) { it.parent }.firstNotNullOfOrNull { it.textStyles }
            val styleSet = when {
                type == null -> styles?.other
                normalize(type) == "title" -> styles?.title
                normalize(type) == "body" -> styles?.body
                else -> styles?.other
            }
            // Text on a dark shape defaults to the light theme colour.
            val fallbackColor = if (fill != null && !isLight(fill)) theme.color("lt1") ?: Argb.WHITE else theme.color("dk1") ?: Argb.BLACK
            return body.paragraphs.map { p ->
                val defaultSize = body.levelSizes[p.level] ?: inherit?.levelSizes?.get(p.level)
                    ?: styleSet?.get(p.level)?.first ?: styleSet?.get(0)?.first
                    ?: if (type != null && normalize(type) == "title") 44f else 18f
                val defaultColor = body.levelColor ?: inherit?.color ?: styleSet?.get(p.level)?.second ?: styleSet?.get(0)?.second ?: fallbackColor
                PptxParagraph(
                    p.runs.map { r ->
                        PptxRun(r.text, r.size ?: defaultSize, r.bold, r.italic, r.underline, r.strike, r.color ?: defaultColor)
                    },
                    p.align ?: "l",
                    p.bullet,
                    p.level,
                    p.endSize ?: defaultSize,
                )
            }
        }

        private class RawRun(val text: String, val size: Float?, val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val color: Int?)
        private class RawParagraph(val runs: List<RawRun>, val align: String?, val bullet: String?, val level: Int, val endSize: Float?)
        private class TextBody(
            val paragraphs: List<RawParagraph>,
            val anchor: String?,
            val insets: Box?,
            val wrap: Boolean?,
            val fontScale: Float?,
            val levelSizes: Map<Int, Float>,
            val levelColor: Int?,
        )

        private fun textBody(): TextBody {
            val paragraphs = ArrayList<RawParagraph>()
            var anchor: String? = null
            var insets: Box? = null
            var wrap: Boolean? = null
            var fontScale: Float? = null
            val levelSizes = HashMap<Int, Float>()
            var levelColor: Int? = null
            val autoNumbers = HashMap<Int, Int>()
            parser.children { name ->
                when (name) {
                    "bodyPr" -> {
                        anchor = parser.attr("anchor")
                        val l = parser.attr("lIns")?.toFloatOrNull()
                        val t = parser.attr("tIns")?.toFloatOrNull()
                        val r = parser.attr("rIns")?.toFloatOrNull()
                        val b = parser.attr("bIns")?.toFloatOrNull()
                        if (l != null || t != null || r != null || b != null) {
                            insets = Box(l ?: DEFAULT_INSETS.left, t ?: DEFAULT_INSETS.top, r ?: DEFAULT_INSETS.right, b ?: DEFAULT_INSETS.bottom)
                        }
                        wrap = parser.attr("wrap")?.let { it != "none" }
                        parser.children { child ->
                            if (child == "normAutofit") fontScale = parser.attr("fontScale")?.toFloatOrNull()?.div(100000f)
                        }
                    }
                    "lstStyle" -> parser.children { lvl ->
                        val level = Regex("lvl(\\d)pPr").matchEntire(lvl)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
                        if (level != null) parser.children { d ->
                            if (d == "defRPr") {
                                parser.attr("sz")?.toFloatOrNull()?.let { levelSizes[level] = it / 100f }
                                parser.children { f -> if (f == "solidFill") (fill(f) as? PptxFill.Solid)?.let { if (level == 0) levelColor = it.color } }
                            }
                        }
                    }
                    "p" -> paragraphs += paragraph(autoNumbers)
                }
            }
            return TextBody(paragraphs, anchor, insets, wrap, fontScale, levelSizes, levelColor)
        }

        private fun paragraph(autoNumbers: MutableMap<Int, Int>): RawParagraph {
            val runs = ArrayList<RawRun>()
            var align: String? = null
            var level = 0
            var bullet: String? = null
            var autoNumber = false
            var endSize: Float? = null
            parser.children { name ->
                when (name) {
                    "pPr" -> {
                        align = parser.attr("algn")
                        level = parser.attr("lvl")?.toIntOrNull() ?: 0
                        parser.children { b ->
                            when (b) {
                                "buChar" -> bullet = parser.attr("char")?.let { if (it.isNotEmpty() && it[0].code in 0xF000..0xF0FF) "•" else it }
                                "buAutoNum" -> autoNumber = true
                                "buNone" -> bullet = null
                            }
                        }
                    }
                    "r", "fld" -> runs += run()
                    "br" -> runs += RawRun("\n", null, false, false, false, false, null)
                    "endParaRPr" -> endSize = parser.attr("sz")?.toFloatOrNull()?.div(100f)
                }
            }
            if (autoNumber) {
                val n = (autoNumbers[level] ?: 0) + 1
                autoNumbers[level] = n
                bullet = "$n."
            } else {
                autoNumbers.remove(level)
            }
            return RawParagraph(runs, align, bullet, level, endSize)
        }

        private fun run(): RawRun {
            var size: Float? = null
            var bold = false
            var italic = false
            var underline = false
            var strike = false
            var color: Int? = null
            val text = StringBuilder()
            parser.children { name ->
                when (name) {
                    "rPr" -> {
                        size = parser.attr("sz")?.toFloatOrNull()?.div(100f)
                        bold = parser.attr("b").let { it == "1" || it == "true" }
                        italic = parser.attr("i").let { it == "1" || it == "true" }
                        underline = parser.attr("u").let { it != null && it != "none" }
                        strike = parser.attr("strike").let { it != null && it != "noStrike" }
                        parser.children { f -> if (f == "solidFill") (fill(f) as? PptxFill.Solid)?.let { color = it.color } }
                    }
                    "t" -> parser.children(onText = { text.append(it) }) {}
                }
            }
            return RawRun(text.toString(), size, bold, italic, underline, strike, color)
        }

        private fun picture(transform: Transform) {
            var box: Box? = null
            var image: String? = null
            parser.children { name ->
                when (name) {
                    "blipFill" -> parser.children { b -> if (b == "blip") image = parser.getAttributeValue(Ooxml.NS_RELATIONSHIPS, "embed")?.let { rels[it] } }
                    "spPr" -> parser.children { p -> if (p == "xfrm") box = xfrm() }
                }
            }
            val b = box ?: return
            val path = image ?: return
            shapes += PptxShape.Picture(transform.map(b), path)
        }

        /** Tables are drawn; charts and diagrams inside graphic frames are left out. */
        private fun graphicFrame(transform: Transform) {
            var box: Box? = null
            var table: PptxShape.Table? = null
            parser.children { name ->
                when (name) {
                    "xfrm" -> box = xfrm()
                    "graphic" -> parser.children { g ->
                        if (g == "graphicData") parser.children { d -> if (d == "tbl") table = table(Box(0f, 0f, 0f, 0f)) }
                    }
                }
            }
            val b = box ?: return
            val t = table ?: return
            shapes += PptxShape.Table(transform.map(b), t.columns, t.rows)
        }

        private fun table(box: Box): PptxShape.Table {
            val columns = ArrayList<Float>()
            val rows = ArrayList<Pair<Float, List<Pair<List<PptxParagraph>, Int?>>>>()
            parser.children { name ->
                when (name) {
                    "tblGrid" -> parser.children { c -> if (c == "gridCol") columns += parser.attr("w")?.toFloatOrNull() ?: 0f }
                    "tr" -> {
                        val height = parser.attr("h")?.toFloatOrNull() ?: 0f
                        val cells = ArrayList<Pair<List<PptxParagraph>, Int?>>()
                        parser.children { c ->
                            if (c == "tc") {
                                var paragraphs: List<PptxParagraph> = emptyList()
                                var cellFill: Int? = null
                                parser.children { part ->
                                    when (part) {
                                        "txBody" -> paragraphs = resolveParagraphs(textBody(), null, null, null)
                                        "tcPr" -> parser.children { f -> (fill(f) as? PptxFill.Solid)?.let { cellFill = it.color } }
                                    }
                                }
                                cells += paragraphs to cellFill
                            }
                        }
                        rows += height to cells
                    }
                }
            }
            return PptxShape.Table(box, columns, rows)
        }

        private fun txStyles(): TextStyles {
            fun levels(): Map<Int, Pair<Float?, Int?>> {
                val result = HashMap<Int, Pair<Float?, Int?>>()
                parser.children { lvl ->
                    val level = Regex("lvl(\\d)pPr").matchEntire(lvl)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: return@children
                    parser.children { d ->
                        if (d == "defRPr") {
                            val size = parser.attr("sz")?.toFloatOrNull()?.div(100f)
                            var c: Int? = null
                            parser.children { f -> if (f == "solidFill") (fill(f) as? PptxFill.Solid)?.let { c = it.color } }
                            result[level] = size to c
                        }
                    }
                }
                return result
            }
            var title: Map<Int, Pair<Float?, Int?>> = emptyMap()
            var body: Map<Int, Pair<Float?, Int?>> = emptyMap()
            var other: Map<Int, Pair<Float?, Int?>> = emptyMap()
            parser.children { name ->
                when (name) {
                    "titleStyle" -> title = levels()
                    "bodyStyle" -> body = levels()
                    "otherStyle" -> other = levels()
                }
            }
            return TextStyles(title, body, other)
        }
    }

    private class Transform(val sx: Float, val sy: Float, val dx: Float, val dy: Float) {
        fun map(r: Box) = Box(r.left * sx + dx, r.top * sy + dy, r.right * sx + dx, r.bottom * sy + dy)
        fun then(inner: Transform) = Transform(sx * inner.sx, sy * inner.sy, inner.dx * sx + dx, inner.dy * sy + dy)

        companion object {
            val IDENTITY = Transform(1f, 1f, 0f, 0f)
        }
    }

    private val DEFAULT_INSETS = Box(91440f, 45720f, 91440f, 45720f)

    private val PRESET_COLORS = mapOf(
        "black" to Argb.BLACK, "white" to Argb.WHITE, "red" to Argb.RED, "green" to 0xFF008000.toInt(),
        "blue" to Argb.BLUE, "yellow" to Argb.YELLOW, "gray" to Argb.GRAY, "orange" to 0xFFFFA500.toInt(),
    )

    private fun isLight(color: Int) = 0.299 * Argb.red(color) + 0.587 * Argb.green(color) + 0.114 * Argb.blue(color) > 150

    /** Office colour modifiers, on HSL lightness (lumMod/lumOff) and towards white/black (tint/shade). */
    private fun adjust(color: Int, lumMod: Float, lumOff: Float, tint: Float?, shade: Float?): Int {
        var c = color
        if (lumMod != 1f || lumOff != 0f) {
            val hsl = Argb.toHsl(c)
            hsl[2] = (hsl[2] * lumMod + lumOff).coerceIn(0f, 1f)
            c = Argb.fromHsl(hsl)
        }
        if (tint != null) c = mix(c, Argb.WHITE, 1 - tint)
        if (shade != null) c = mix(c, Argb.BLACK, 1 - shade)
        return c or 0xFF000000.toInt()
    }

    private fun mix(a: Int, b: Int, t: Float) = Argb.rgb(
        (Argb.red(a) + (Argb.red(b) - Argb.red(a)) * t).roundToInt(),
        (Argb.green(a) + (Argb.green(b) - Argb.green(a)) * t).roundToInt(),
        (Argb.blue(a) + (Argb.blue(b) - Argb.blue(a)) * t).roundToInt(),
    )
}

/** A rectangle in slide units (EMU). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width() = right - left
    fun height() = bottom - top
}

/** ARGB colour maths, kept free of Android classes so the reader runs in unit tests. */
internal object Argb {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val RED = 0xFFFF0000.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()
    const val GRAY = 0xFF888888.toInt()

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Hue (0..360), saturation and lightness (0..1). */
    fun toHsl(c: Int): FloatArray {
        val r = red(c) / 255f
        val g = green(c) / 255f
        val b = blue(c) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6 else 0))
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        return floatArrayOf(h, s, l)
    }

    fun fromHsl(hsl: FloatArray): Int {
        val (h, s, l) = Triple(hsl[0] / 360f, hsl[1], hsl[2])
        if (s == 0f) return rgb((l * 255).roundToInt(), (l * 255).roundToInt(), (l * 255).roundToInt())
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun channel(t0: Float): Int {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            val v = when {
                t < 1f / 6 -> p + (q - p) * 6 * t
                t < 1f / 2 -> q
                t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                else -> p
            }
            return (v * 255).roundToInt()
        }
        return rgb(channel(h + 1f / 3), channel(h), channel(h - 1f / 3))
    }
}

/**
 * Draws slides to bitmaps. Everything is converted from slide units (EMU) to pixels before drawing,
 * so text is laid out at its real on-screen size. Keeps the deck's archive open for pictures; close
 * it when done.
 */
class PptxRenderer(file: File, private val deck: PptxDeck) : Closeable {
    private val zip = ZipFile(file)
    private val images = LruCache<String, Bitmap>(24)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Slide [index] drawn [width] pixels wide. */
    @Synchronized
    fun render(index: Int, width: Int): Bitmap {
        val slide = deck.slides[index]
        val s = width / deck.width.toFloat()
        val height = max(1, (deck.height * s).roundToInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        when (val bg = slide.background) {
            is PptxFill.Solid -> canvas.drawColor(bg.color)
            is PptxFill.Picture -> image(bg.path, width)?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint) }
            null -> Unit
        }
        for (shape in slide.shapes) {
            val box = scaled(shape.box, s)
            when (shape) {
                is PptxShape.Picture -> image(shape.path, box.width().roundToInt().coerceAtLeast(1))?.let { canvas.drawBitmap(it, null, box, paint) }
                is PptxShape.Text -> drawText(canvas, shape, box, s)
                is PptxShape.Table -> drawTable(canvas, shape, box, s)
            }
        }
        return bitmap
    }

    private fun scaled(r: Box, s: Float) = RectF(r.left * s, r.top * s, r.right * s, r.bottom * s)

    private fun drawText(canvas: Canvas, shape: PptxShape.Text, box: RectF, s: Float) {
        val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = if (shape.geometry == "roundRect") minOf(box.width(), box.height()) * 0.16f else 0f
        fun outline() = if (shape.geometry == "ellipse") canvas.drawOval(box, shapePaint) else canvas.drawRoundRect(box, radius, radius, shapePaint)
        shape.fill?.let {
            shapePaint.color = it
            outline()
        }
        shape.outline?.let {
            shapePaint.color = it
            shapePaint.style = Paint.Style.STROKE
            shapePaint.strokeWidth = max(1f, EMU_PER_POINT * s)
            outline()
        }
        val inner = RectF(
            box.left + shape.insets.left * s, box.top + shape.insets.top * s,
            box.right - shape.insets.right * s, box.bottom - shape.insets.bottom * s,
        )
        val layouts = paragraphLayouts(shape.paragraphs, inner.width(), shape.wrap, shape.fontScale, s)
        val total = layouts.sumOf { it.height.toDouble() }.toFloat()
        var y = when (shape.anchor) {
            "ctr" -> inner.top + (inner.height() - total) / 2
            "b" -> inner.bottom - total
            else -> inner.top
        }
        for (layout in layouts) {
            canvas.save()
            canvas.translate(inner.left, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height
        }
    }

    /** One layout per paragraph, in pixels: point sizes × 12700 EMU × [s]. */
    private fun paragraphLayouts(paragraphs: List<PptxParagraph>, width: Float, wrap: Boolean, fontScale: Float, s: Float): List<StaticLayout> =
        paragraphs.map { p ->
            val text = SpannableStringBuilder()
            val indent = p.level * 457200f * s
            p.bullet?.let { text.append(it).append(' ') }
            if (p.runs.isEmpty()) text.append(' ')
            for (run in p.runs) {
                val start = text.length
                text.append(run.text)
                val end = text.length
                if (end == start) continue
                text.setSpan(AbsoluteSizeSpan(max(1, (run.size * EMU_PER_POINT * s * fontScale).roundToInt())), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(run.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val style = (if (run.bold) Typeface.BOLD else 0) or (if (run.italic) Typeface.ITALIC else 0)
                if (style != 0) text.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (run.underline) text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (run.strike) text.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // The bullet (and an empty line) take the paragraph's own size and first colour.
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = max(1f, p.emptySize * EMU_PER_POINT * s * fontScale)
                color = p.runs.firstOrNull()?.color ?: Color.BLACK
            }
            val layoutWidth = if (wrap) (width - indent).coerceAtLeast(1f).roundToInt() else (width * 4).roundToInt().coerceAtLeast(1)
            StaticLayout.Builder.obtain(text, 0, text.length, textPaint, layoutWidth)
                .setAlignment(
                    when (p.align) {
                        "ctr" -> Layout.Alignment.ALIGN_CENTER
                        "r" -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    }
                )
                .setIndents(intArrayOf(indent.roundToInt()), intArrayOf(0))
                .setIncludePad(false)
                .build()
        }

    private fun drawTable(canvas: Canvas, table: PptxShape.Table, box: RectF, s: Float) {
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, 9525f * s)
            color = Color.GRAY
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val padX = 91440f * s
        val padY = 45720f * s
        val totalWidth = table.columns.sum().takeIf { it > 0f } ?: table.box.width()
        val columnScale = box.width() / totalWidth
        var y = box.top
        for ((height, cells) in table.rows) {
            val widths = cells.indices.map { table.columns.getOrElse(it) { 0f } * columnScale }
            val layouts = cells.mapIndexed { i, (paragraphs, _) -> paragraphLayouts(paragraphs, (widths[i] - 2 * padX).coerceAtLeast(1f), true, 1f, s) }
            val contentHeight = layouts.maxOfOrNull { l -> l.sumOf { it.height.toDouble() }.toFloat() + 2 * padY } ?: 0f
            val rowHeight = max(height * s, contentHeight)
            var x = box.left
            cells.forEachIndexed { i, (_, cellFill) ->
                val cell = RectF(x, y, x + widths[i], y + rowHeight)
                cellFill?.let { fill.color = it; canvas.drawRect(cell, fill) }
                canvas.drawRect(cell, border)
                var ty = y + padY
                for (layout in layouts[i]) {
                    canvas.save()
                    canvas.translate(x + padX, ty)
                    layout.draw(canvas)
                    canvas.restore()
                    ty += layout.height
                }
                x += widths[i]
            }
            y += rowHeight
        }
    }

    private fun image(path: String, width: Int): Bitmap? {
        val bucket = Integer.highestOneBit(width.coerceAtLeast(64)) * 2
        val key = "$path@$bucket"
        images.get(key)?.let { return it }
        return Ooxml.image(zip, path, bucket)?.also { images.put(key, it) }
    }

    override fun close() = zip.close()

    private companion object {
        const val EMU_PER_POINT = 12700f
    }
}
