package com.singularity_universe.explorer.data.docs

import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

/** An Excel workbook as text grids, one per sheet. Very large sheets are cut off. */
class XlsxWorkbook(val sheets: List<XlsxSheet>)

/** [cells] is row → column → text, both 0-based. */
class XlsxSheet(val name: String, val cells: Map<Int, Map<Int, String>>, val rows: Int, val columns: Int, val truncated: Boolean)

object XlsxReader {
    private const val WORKBOOK = "xl/workbook.xml"
    private const val MAX_ROWS = 10_000
    private const val MAX_COLUMNS = 200

    @Throws(IOException::class)
    fun read(file: File): XlsxWorkbook = ZipFile(file).use { zip ->
        val rels = Ooxml.relationships(zip, WORKBOOK)
        val strings = sharedStrings(zip)
        val styles = Styles.read(zip)
        val parser = Ooxml.parser(zip, WORKBOOK) ?: throw IOException("Not an Excel workbook")
        val sheets = ArrayList<Pair<String, String>>()
        var date1904 = false
        parser.forEachStart { name ->
            when (name) {
                "workbookPr" -> date1904 = parser.attr("date1904")?.let { it == "1" || it == "true" } == true
                "sheet" -> {
                    val sheetName = parser.attr("name") ?: "Sheet"
                    val path = parser.getAttributeValue(Ooxml.NS_RELATIONSHIPS, "id")?.let { rels[it] }
                    if (path != null) sheets += sheetName to path
                }
            }
        }
        XlsxWorkbook(sheets.map { (name, path) -> sheet(zip, name, path, strings, styles, date1904) })
    }

    private fun sharedStrings(zip: ZipFile): List<String> {
        val parser = Ooxml.parser(zip, "xl/sharedStrings.xml") ?: return emptyList()
        val result = ArrayList<String>()
        parser.forEachStart { name -> if (name == "si") result += richText(parser) }
        return result
    }

    /** The text of an <si> or <is>: plain <t>, or rich-text runs <r><t>; phonetic hints skipped. */
    private fun richText(parser: org.xmlpull.v1.XmlPullParser): String {
        val sb = StringBuilder()
        parser.children { name ->
            when (name) {
                "t" -> parser.children(onText = { sb.append(it) }) {}
                "r" -> parser.children { inner -> if (inner == "t") parser.children(onText = { sb.append(it) }) {} }
            }
        }
        return sb.toString()
    }

    private fun sheet(
        zip: ZipFile,
        name: String,
        path: String,
        strings: List<String>,
        styles: Styles,
        date1904: Boolean,
    ): XlsxSheet {
        val parser = Ooxml.parser(zip, path) ?: return XlsxSheet(name, emptyMap(), 0, 0, false)
        val cells = HashMap<Int, HashMap<Int, String>>()
        var maxRow = -1
        var maxColumn = -1
        var truncated = false
        var nextRow = 0
        parser.forEachStart { tag ->
            if (tag != "row") return@forEachStart
            val row = parser.attr("r")?.toIntOrNull()?.minus(1) ?: nextRow
            nextRow = row + 1
            if (row >= MAX_ROWS) {
                truncated = true
                return@forEachStart
            }
            var nextColumn = 0
            parser.children { c ->
                if (c != "c") return@children
                val column = parser.attr("r")?.let(::columnOf) ?: nextColumn
                nextColumn = column + 1
                val type = parser.attr("t")
                val style = parser.attr("s")?.toIntOrNull() ?: 0
                var value: String? = null
                var inline: String? = null
                parser.children { part ->
                    when (part) {
                        "v" -> parser.children(onText = { value = (value ?: "") + it }) {}
                        "is" -> inline = richText(parser)
                    }
                }
                val text = when (type) {
                    "s" -> value?.toIntOrNull()?.let { strings.getOrNull(it) }
                    "inlineStr" -> inline
                    "b" -> if (value == "1") "TRUE" else "FALSE"
                    "str", "e" -> value
                    else -> value?.let { styles.format(it, style, date1904) }
                }
                if (text.isNullOrEmpty()) return@children
                if (column >= MAX_COLUMNS) {
                    truncated = true
                    return@children
                }
                cells.getOrPut(row) { HashMap() }[column] = text
                if (row > maxRow) maxRow = row
                if (column > maxColumn) maxColumn = column
            }
        }
        return XlsxSheet(name, cells, maxRow + 1, maxColumn + 1, truncated)
    }

    /** "BC12" → 54 (0-based column). */
    private fun columnOf(reference: String): Int? {
        var n = 0
        var any = false
        for (ch in reference) {
            if (ch !in 'A'..'Z') break
            n = n * 26 + (ch - 'A' + 1)
            any = true
        }
        return if (any) n - 1 else null
    }

    /** 0 → "A", 27 → "AB". */
    fun columnName(index: Int): String {
        var v = index + 1
        val sb = StringBuilder()
        while (v > 0) {
            v--
            sb.insert(0, 'A' + v % 26)
            v /= 26
        }
        return sb.toString()
    }

    /** Which cell styles show numbers as dates, times or percentages. */
    private class Styles(private val kinds: List<Kind>) {
        enum class Kind { Plain, Date, Time, DateTime, Percent }

        fun format(raw: String, style: Int, date1904: Boolean): String {
            val number = raw.toBigDecimalOrNull() ?: return raw
            return when (kinds.getOrElse(style) { Kind.Plain }) {
                Kind.Percent -> plain(number.movePointRight(2)) + "%"
                Kind.Date -> dateTime(number, date1904)?.toLocalDate()?.toString() ?: plain(number)
                Kind.Time -> dateTime(number, date1904)?.format(DateTimeFormatter.ofPattern("HH:mm:ss")) ?: plain(number)
                Kind.DateTime -> dateTime(number, date1904)?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: plain(number)
                Kind.Plain -> plain(number)
            }
        }

        private fun plain(n: BigDecimal): String {
            // Excel keeps 15 significant digits; show them without float noise or a trailing ".0".
            val rounded = n.round(java.math.MathContext(15)).stripTrailingZeros()
            return rounded.toPlainString()
        }

        /** Excel stores dates as days since 1899-12-30 (or 1904-01-01), with the time as a fraction. */
        private fun dateTime(serial: BigDecimal, date1904: Boolean): LocalDateTime? = runCatching {
            val days = serial.toDouble()
            if (days < 0 || days > 2_958_465) return null
            val base = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 30)
            val whole = days.toLong()
            val seconds = Math.round((days - whole) * 86_400)
            base.plusDays(whole).atStartOfDay().plusSeconds(seconds)
        }.getOrNull()

        companion object {
            fun read(zip: ZipFile): Styles {
                val parser = Ooxml.parser(zip, "xl/styles.xml") ?: return Styles(emptyList())
                val custom = HashMap<Int, String>()
                val xfs = ArrayList<Int>()
                parser.forEachStart { name ->
                    when (name) {
                        "numFmt" -> {
                            val id = parser.attr("numFmtId")?.toIntOrNull()
                            val code = parser.attr("formatCode")
                            if (id != null && code != null) custom[id] = code
                        }
                        "cellXfs" -> parser.children { child ->
                            if (child == "xf") xfs += parser.attr("numFmtId")?.toIntOrNull() ?: 0
                        }
                    }
                }
                return Styles(xfs.map { kindOf(it, custom[it]) })
            }

            private fun kindOf(id: Int, code: String?): Kind = when (id) {
                in 14..17, 30, 36, in 50..58 -> Kind.Date
                in 18..21, 45, 46, 47 -> Kind.Time
                22 -> Kind.DateTime
                9, 10 -> Kind.Percent
                else -> code?.let(::kindOfCode) ?: Kind.Plain
            }

            /** Reads a custom format code: date/time letters outside quotes and brackets. */
            private fun kindOfCode(code: String): Kind {
                val bare = code.replace(Regex("\"[^\"]*\"|\\[[^\\]]*\\]|\\\\."), "").lowercase()
                if (bare.contains('%')) return Kind.Percent
                val date = bare.any { it == 'y' || it == 'd' } || Regex("(^|[^h:])m+([^:s]|$)").containsMatchIn(bare) && !bare.contains('0')
                val time = bare.contains('h') || bare.contains('s')
                return when {
                    date && time -> Kind.DateTime
                    date -> Kind.Date
                    time -> Kind.Time
                    else -> Kind.Plain
                }
            }
        }
    }
}
