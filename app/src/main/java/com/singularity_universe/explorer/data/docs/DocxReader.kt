package com.singularity_universe.explorer.data.docs

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

/** A Word document reduced to what a reading view needs: paragraphs, tables and pictures, in order. */
class DocxDocument(val file: File, val blocks: List<DocxBlock>)

sealed interface DocxBlock

/** [level] 1..6 for headings (0 = body text); [prefix] is a list bullet or number. */
class DocxParagraph(
    val runs: List<DocxRun>,
    val level: Int,
    val prefix: String?,
    val indent: Int,
    val align: Align,
) : DocxBlock {
    enum class Align { Start, Center, End, Justify }
    val isEmpty: Boolean get() = runs.all { it.text.isBlank() } && prefix == null
}

class DocxRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Points, or 0 for the default size. */
    val size: Float = 0f,
    /** RGB, or null for the default colour. */
    val color: Int? = null,
    val highlight: Int? = null,
)

class DocxTable(val rows: List<List<List<DocxBlock>>>) : DocxBlock

/** A picture: [path] inside the archive. */
class DocxImage(val path: String) : DocxBlock

object DocxReader {
    private const val DOCUMENT = "word/document.xml"

    @Throws(IOException::class)
    fun read(file: File): DocxDocument = ZipFile(file).use { zip ->
        val rels = Ooxml.relationships(zip, DOCUMENT)
        val styles = paragraphStyles(zip)
        val numbering = Numbering.read(zip)
        val parser = Ooxml.parser(zip, DOCUMENT) ?: throw IOException("Not a Word document")
        val reader = BodyReader(parser, rels, styles, numbering)
        val blocks = ArrayList<DocxBlock>()
        parser.forEachStart { name -> if (name == "body") blocks += reader.blocks() }
        DocxDocument(file, blocks)
    }

    /** What a paragraph style implies: a heading level, and list numbering (e.g. "List Bullet"). */
    private class ParagraphStyle(val level: Int, val numId: String?, val ilvl: Int)

    /** Paragraph styles from styles.xml ("heading 1" → level 1, "Title" → 1, "Subtitle" → 2). */
    private fun paragraphStyles(zip: ZipFile): Map<String, ParagraphStyle> {
        val parser = Ooxml.parser(zip, "word/styles.xml") ?: return emptyMap()
        val result = HashMap<String, ParagraphStyle>()
        parser.forEachStart { name ->
            if (name == "style") {
                val id = parser.attr("styleId") ?: return@forEachStart
                var level = 0
                var numId: String? = null
                var ilvl = 0
                parser.children { child ->
                    when (child) {
                        "name" -> {
                            val styleName = parser.attr("val").orEmpty().lowercase()
                            level = when {
                                styleName == "title" -> 1
                                styleName == "subtitle" -> 2
                                styleName.startsWith("heading ") -> styleName.removePrefix("heading ").toIntOrNull()?.coerceIn(1, 6) ?: 0
                                else -> 0
                            }
                        }
                        "pPr" -> parser.children { prop ->
                            if (prop == "numPr") parser.children { n ->
                                when (n) {
                                    "numId" -> numId = parser.attr("val")
                                    "ilvl" -> ilvl = parser.attr("val")?.toIntOrNull() ?: 0
                                }
                            }
                        }
                    }
                }
                if (level > 0 || numId != null) result[id] = ParagraphStyle(level, numId, ilvl)
            }
        }
        return result
    }

    private class BodyReader(
        val parser: XmlPullParser,
        val rels: Map<String, String>,
        val styles: Map<String, ParagraphStyle>,
        val numbering: Numbering,
    ) {
        /** The blocks inside the current element (body, table cell, content control...). */
        fun blocks(): List<DocxBlock> {
            val out = ArrayList<DocxBlock>()
            parser.children { name ->
                when (name) {
                    "p" -> paragraph(out)
                    "tbl" -> out += table()
                    // Content controls and tracked insertions just wrap more content.
                    "sdt", "sdtContent", "customXml", "ins" -> out += blocks()
                }
            }
            return out
        }

        private fun table(): DocxTable {
            val rows = ArrayList<List<List<DocxBlock>>>()
            parser.children { name ->
                if (name == "tr") {
                    val cells = ArrayList<List<DocxBlock>>()
                    parser.children { cell -> if (cell == "tc") cells += blocks() }
                    rows += cells
                }
            }
            return DocxTable(rows)
        }

        /** A paragraph, plus any pictures in it as blocks of their own after it. */
        private fun paragraph(out: MutableList<DocxBlock>) {
            var level = 0
            var styleNumId: String? = null
            var styleIlvl = 0
            var numId: String? = null
            var ilvl: Int? = null
            var align = DocxParagraph.Align.Start
            var indent = 0
            val runs = ArrayList<DocxRun>()
            val images = ArrayList<String>()
            parser.children { name ->
                when (name) {
                    "pPr" -> parser.children { prop ->
                        when (prop) {
                            "pStyle" -> parser.attr("val")?.let { styles[it] }?.let { style ->
                                level = style.level
                                styleNumId = style.numId
                                styleIlvl = style.ilvl
                            }
                            "numPr" -> parser.children { n ->
                                when (n) {
                                    "numId" -> numId = parser.attr("val")
                                    "ilvl" -> ilvl = parser.attr("val")?.toIntOrNull() ?: 0
                                }
                            }
                            "jc" -> align = when (parser.attr("val")) {
                                "center" -> DocxParagraph.Align.Center
                                "right", "end" -> DocxParagraph.Align.End
                                "both", "distribute" -> DocxParagraph.Align.Justify
                                else -> DocxParagraph.Align.Start
                            }
                            "ind" -> indent = ((parser.attr("left") ?: parser.attr("start"))?.toIntOrNull() ?: 0) / 360
                        }
                    }
                    "r" -> run(runs, images)
                    // Links, fields and tracked insertions contain runs.
                    "hyperlink", "ins", "smartTag", "fldSimple", "sdt", "sdtContent" -> runsIn(runs, images)
                }
            }
            // The paragraph's own numbering wins over its style's.
            val list = numId ?: styleNumId
            val listLevel = ilvl ?: styleIlvl
            val prefix = list?.takeIf { it != "0" }?.let { numbering.next(it, listLevel) }
            out += DocxParagraph(runs, level, prefix, indent + if (prefix != null) listLevel else 0, align)
            images.forEach { out += DocxImage(it) }
        }

        private fun runsIn(runs: MutableList<DocxRun>, images: MutableList<String>) {
            parser.children { name ->
                when (name) {
                    "r" -> run(runs, images)
                    "hyperlink", "ins", "smartTag", "sdt", "sdtContent" -> runsIn(runs, images)
                }
            }
        }

        private fun run(runs: MutableList<DocxRun>, images: MutableList<String>) {
            var bold = false
            var italic = false
            var underline = false
            var strike = false
            var size = 0f
            var color: Int? = null
            var highlight: Int? = null
            val text = StringBuilder()
            parser.children { name ->
                when (name) {
                    "rPr" -> parser.children { prop ->
                        when (prop) {
                            "b" -> bold = parser.flag()
                            "i" -> italic = parser.flag()
                            "u" -> underline = parser.flag()
                            "strike", "dstrike" -> strike = parser.flag()
                            "sz" -> size = (parser.attr("val")?.toFloatOrNull() ?: 0f) / 2f
                            "color" -> color = parser.attr("val")?.takeIf { it != "auto" }?.let(::rgb)
                            "highlight" -> highlight = HIGHLIGHTS[parser.attr("val")]
                            "shd" -> if (highlight == null) highlight = parser.attr("fill")?.takeIf { it != "auto" }?.let(::rgb)
                        }
                    }
                    "t" -> parser.children(onText = { text.append(it) }) {}
                    "tab" -> text.append('\t')
                    "br", "cr" -> text.append('\n')
                    "noBreakHyphen" -> text.append('‑')
                    "drawing", "pict", "object" -> images += imagesIn()
                }
            }
            if (text.isNotEmpty()) runs += DocxRun(text.toString(), bold, italic, underline, strike, size, color, highlight)
        }

        /** Picture parts referenced inside a drawing (a:blip r:embed, or v:imagedata r:id in older files). */
        private fun imagesIn(): List<String> {
            val found = ArrayList<String>()
            fun walk() {
                parser.children { name ->
                    val id = when (name) {
                        "blip" -> parser.getAttributeValue(Ooxml.NS_RELATIONSHIPS, "embed")
                        "imagedata" -> parser.getAttributeValue(Ooxml.NS_RELATIONSHIPS, "id")
                        else -> null
                    }
                    id?.let { rels[it] }?.let { found += it }
                    walk()
                }
            }
            walk()
            return found
        }
    }

    /** List numbering: numId → format per level, with running counters. */
    private class Numbering(private val formats: Map<String, Map<Int, Pair<String, String>>>) {
        private val counters = HashMap<String, IntArray>()

        fun next(numId: String, level: Int): String {
            val (format, text) = formats[numId]?.get(level) ?: ("bullet" to "•")
            val counts = counters.getOrPut(numId) { IntArray(9) }
            val lvl = level.coerceIn(0, 8)
            counts[lvl]++
            for (deeper in lvl + 1 until 9) counts[deeper] = 0
            if (format == "bullet" || format == "none") return if (format == "none") "" else bulletFor(text)
            // "%1.%2." → "3.1."
            var label = text.ifEmpty { "%${lvl + 1}." }
            for (i in 0..lvl) label = label.replace("%${i + 1}", number(counts[i].coerceAtLeast(1), formatOf(numId, i)))
            return label
        }

        private fun formatOf(numId: String, level: Int) = formats[numId]?.get(level)?.first ?: "decimal"

        private fun bulletFor(text: String) = when {
            text.isEmpty() -> "•"
            // Symbol-font bullets (private use characters) show as boxes: use a plain one.
            text[0].code in 0xF000..0xF0FF -> if (text[0].code == 0xF0A7) "▪" else "•"
            else -> text
        }

        private fun number(n: Int, format: String): String = when (format) {
            "lowerLetter" -> letters(n).lowercase()
            "upperLetter" -> letters(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            else -> n.toString()
        }

        private fun letters(n: Int): String {
            var v = n
            val sb = StringBuilder()
            while (v > 0) {
                v--
                sb.insert(0, 'A' + v % 26)
                v /= 26
            }
            return sb.toString()
        }

        private fun roman(n: Int): String {
            val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var v = n.coerceIn(1, 3999)
            val sb = StringBuilder()
            values.forEachIndexed { i, value -> while (v >= value) { sb.append(symbols[i]); v -= value } }
            return sb.toString()
        }

        companion object {
            fun read(zip: ZipFile): Numbering {
                val parser = Ooxml.parser(zip, "word/numbering.xml") ?: return Numbering(emptyMap())
                val abstract = HashMap<String, Map<Int, Pair<String, String>>>()
                val nums = HashMap<String, String>()
                parser.forEachStart { name ->
                    when (name) {
                        "abstractNum" -> {
                            val id = parser.attr("abstractNumId") ?: return@forEachStart
                            val levels = HashMap<Int, Pair<String, String>>()
                            parser.children { child ->
                                if (child == "lvl") {
                                    val ilvl = parser.attr("ilvl")?.toIntOrNull() ?: 0
                                    var format = "decimal"
                                    var text = ""
                                    parser.children { prop ->
                                        when (prop) {
                                            "numFmt" -> format = parser.attr("val") ?: format
                                            "lvlText" -> text = parser.attr("val").orEmpty()
                                        }
                                    }
                                    levels[ilvl] = format to text
                                }
                            }
                            abstract[id] = levels
                        }
                        "num" -> {
                            val numId = parser.attr("numId") ?: return@forEachStart
                            parser.children { child -> if (child == "abstractNumId") parser.attr("val")?.let { nums[numId] = it } }
                        }
                    }
                }
                return Numbering(nums.mapValues { (_, abstractId) -> abstract[abstractId].orEmpty() })
            }
        }
    }

    private fun rgb(hex: String): Int? = hex.takeIf { it.length == 6 }?.toIntOrNull(16)

    private val HIGHLIGHTS = mapOf(
        "yellow" to 0xFFFF00, "green" to 0x00FF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF,
        "blue" to 0x0000FF, "red" to 0xFF0000, "darkBlue" to 0x000080, "darkCyan" to 0x008080,
        "darkGreen" to 0x008000, "darkMagenta" to 0x800080, "darkRed" to 0x800000, "darkYellow" to 0x808000,
        "darkGray" to 0x808080, "lightGray" to 0xC0C0C0, "black" to 0x000000,
    )
}
