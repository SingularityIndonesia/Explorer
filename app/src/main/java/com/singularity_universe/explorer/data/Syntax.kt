package com.singularity_universe.explorer.data

/** What a piece of highlighted source is; the UI picks a color for each. */
enum class TokenKind { Keyword, Type, Function, String, Number, Constant, Comment, Variable, Meta, Tag, Attribute, Key, Heading, Inserted, Deleted }

private val KINDS = TokenKind.entries.toTypedArray()

/** Colored ranges of a text: in order, never overlapping. Text between them is left plain. */
class SyntaxSpans internal constructor(private val data: IntArray, val size: Int) {
    fun start(index: Int) = data[index * 3]
    fun end(index: Int) = data[index * 3 + 1]
    fun kind(index: Int): TokenKind = KINDS[data[index * 3 + 2]]

    /** Index of the first span that ends after [offset]. */
    fun indexAfter(offset: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (end(mid) <= offset) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        val EMPTY = SyntaxSpans(IntArray(0), 0)
    }
}

private class SpanBuilder {
    // Three ints per span (start, end, kind): a 2 MB file can have hundreds of thousands of them.
    private var data = IntArray(3 * 256)
    private var size = 0

    fun add(start: Int, end: Int, kind: TokenKind) {
        if (end <= start) return
        val last = (size - 1) * 3
        if (size > 0 && data[last + 1] == start && data[last + 2] == kind.ordinal) {
            data[last + 1] = end
            return
        }
        if (size * 3 + 3 > data.size) data = data.copyOf(data.size * 2)
        data[size * 3] = start
        data[size * 3 + 1] = end
        data[size * 3 + 2] = kind.ordinal
        size++
    }

    fun build() = SyntaxSpans(data, size)
}

internal enum class Mode { Code, Markup, Markdown, Diff }

/**
 * How to color one language. Not a parser: a single pass that recognizes comments, strings,
 * numbers and words, which is enough to color source the way editors do, and cheap enough to run
 * on every keystroke.
 */
class Language internal constructor(
    val name: String,
    internal val mode: Mode = Mode.Code,
    internal val lineComments: List<String> = emptyList(),
    internal val blockComments: List<Pair<String, String>> = emptyList(),
    internal val quotes: String = "\"'",
    /** Quoted strings may run over several lines (shell). */
    internal val multilineQuotes: Boolean = false,
    /** Delimiters of strings that may span lines, like """ or `. */
    internal val longStrings: List<String> = emptyList(),
    /** Strings opened by these quotes show `$name` and `${…}` inside them as variables. */
    internal val interpolatingQuotes: String = "",
    /** Backslashes don't escape inside '…' (shell). */
    internal val rawSingleQuotes: Boolean = false,
    internal val keywords: Set<String> = emptySet(),
    internal val constants: Set<String> = emptySet(),
    internal val types: Set<String> = emptySet(),
    internal val builtins: Set<String> = emptySet(),
    /** Capitalized words are types (and ALL_CAPS ones constants). */
    internal val capitalizedTypes: Boolean = false,
    internal val ignoreCase: Boolean = false,
    /** Characters allowed inside words besides letters and digits. */
    internal val identifierChars: String = "_",
    /** Characters that start a variable: `$name`, `%name%`. */
    internal val sigils: String = "",
    /** `@Name` annotations, decorators and at-rules. */
    internal val annotations: Boolean = false,
    /** `#include` and `#[attr]` at the start of a line. */
    internal val preprocessor: Boolean = false,
    /** `name!` is a macro call (Rust). */
    internal val macros: Boolean = false,
    /** `#fff` is a color (CSS). */
    internal val hashColors: Boolean = false,
    /** Inside `{ }`, `name:` is a property (CSS). */
    internal val cssProperties: Boolean = false,
    /** `key: value` / `key = value` lines (config files, Makefiles). */
    internal val keyValue: Boolean = false,
    /** `[section]` lines (INI, TOML). */
    internal val sections: Boolean = false,
    /** A string followed by `:` is an object key (JSON). */
    internal val objectKeys: Boolean = false,
    /** `#` starts a comment only at the start of a word (shell, YAML), not in `a#b`. */
    internal val commentNeedsSpace: Boolean = false,
    /** Quotes start a string only where a value starts, so `it's` in a YAML value stays plain. */
    internal val looseQuotes: Boolean = false,
)

fun highlight(text: CharSequence, language: Language): SyntaxSpans = when (language.mode) {
    Mode.Code -> CodeLexer(text, language).run()
    Mode.Markup -> highlightMarkup(text)
    Mode.Markdown -> highlightMarkdown(text)
    Mode.Diff -> highlightDiff(text)
}

private class CodeLexer(private val text: CharSequence, private val lang: Language) {
    private val out = SpanBuilder()
    private val n = text.length
    private var i = 0
    /** Brace depth, for CSS properties. */
    private var depth = 0

    fun run(): SyntaxSpans {
        var lineStart = true
        while (i < n) {
            val c = text[i]
            when {
                c == '\n' -> { lineStart = true; i++ }
                c == ' ' || c == '\t' || c == '\r' -> i++
                lineStart -> {
                    lineStart = false
                    if (!startOfLine(c)) step(c)
                }
                else -> step(c)
            }
        }
        return out.build()
    }

    /** Things only recognized as the first word of a line. True when something was consumed. */
    private fun startOfLine(c: Char): Boolean {
        if (commentAt(i)) return false
        if (lang.sections && c == '[') {
            val end = lineEnd(i)
            val close = indexOf(']', i, end)
            if (close >= 0) {
                out.add(i, close + 1, TokenKind.Type)
                i = close + 1
                return true
            }
        }
        if (lang.preprocessor && c == '#') {
            directive()
            return true
        }
        return lang.keyValue && key()
    }

    private fun step(c: Char) {
        for ((open, close) in lang.blockComments) {
            if (matches(i, open)) {
                val found = indexOf(close, i + open.length)
                val end = if (found < 0) n else found + close.length
                out.add(i, end, TokenKind.Comment)
                i = end
                return
            }
        }
        if (commentAt(i)) {
            val end = lineEnd(i)
            out.add(i, end, TokenKind.Comment)
            i = end
            return
        }
        for (delimiter in lang.longStrings) {
            if (matches(i, delimiter)) {
                string(delimiter, multiline = true)
                return
            }
        }
        if (c in lang.quotes && quoteAllowed()) {
            string(c.toString(), multiline = lang.multilineQuotes)
            return
        }
        if (c in lang.sigils) {
            val end = variableEnd(i)
            if (end > i) {
                out.add(i, end, TokenKind.Variable)
                i = end
                return
            }
        }
        if (c == '@' && lang.annotations && i + 1 < n && isWordStart(text[i + 1])) {
            val end = wordEnd(i + 1)
            out.add(i, end, TokenKind.Meta)
            i = end
            return
        }
        if (c == '#' && lang.hashColors && i + 1 < n && text[i + 1].isHexDigit()) {
            var end = i + 1
            while (end < n && text[end].isLetterOrDigit()) end++
            out.add(i, end, TokenKind.Number)
            i = end
            return
        }
        if (c.isDigit() || (c == '.' && i + 1 < n && text[i + 1].isDigit())) {
            number()
            return
        }
        if (isWordStart(c)) {
            word()
            return
        }
        if (lang.cssProperties) {
            if (c == '{') depth++ else if (c == '}' && depth > 0) depth--
        }
        i++
    }

    private fun commentAt(at: Int): Boolean {
        for (marker in lang.lineComments) {
            if (!matches(at, marker)) continue
            // A word-like marker (REM) must be a whole word.
            if (marker.last().isLetter() && at + marker.length < n && isWordChar(text[at + marker.length])) continue
            if (marker == "#" && lang.commentNeedsSpace && at > 0 && !text[at - 1].isWhitespace()) continue
            return true
        }
        return false
    }

    private fun quoteAllowed(): Boolean {
        if (!lang.looseQuotes) return true
        var j = i - 1
        while (j >= 0 && (text[j] == ' ' || text[j] == '\t')) j--
        return j < 0 || text[j] == '\n' || text[j] in ":=[{,(-"
    }

    private fun string(delimiter: String, multiline: Boolean) {
        val start = i
        val interpolates = delimiter[0] in lang.interpolatingQuotes
        val escapes = !(lang.rawSingleQuotes && delimiter == "'")
        var segment = start
        var j = i + delimiter.length
        while (j < n) {
            val ch = text[j]
            if (ch == '\\' && escapes) {
                j += 2
                continue
            }
            if (matches(j, delimiter)) {
                j += delimiter.length
                break
            }
            if (ch == '\n' && !multiline) break
            if (interpolates && ch == '$') {
                val end = variableEnd(j)
                if (end > j) {
                    out.add(segment, j, TokenKind.String)
                    out.add(j, end, TokenKind.Variable)
                    j = end
                    segment = j
                    continue
                }
            }
            j++
        }
        val end = minOf(j, n)
        val kind = if (lang.objectKeys && segment == start && nextOnLine(end) == ':') TokenKind.Key else TokenKind.String
        out.add(segment, end, kind)
        i = end
    }

    /** End of the variable starting at [at] (a sigil), or [at] when there isn't one. */
    private fun variableEnd(at: Int): Int {
        val sigil = text[at]
        if (at + 1 >= n) return at
        val next = text[at + 1]
        if (sigil == '%') {
            // Batch: %name%, %1, %%i, %~dp0.
            return when {
                next == '%' -> if (at + 2 < n && text[at + 2].isLetter()) at + 3 else at
                next.isDigit() -> at + 2
                next == '~' -> {
                    var j = at + 2
                    while (j < n && text[j].isLetterOrDigit()) j++
                    j
                }
                next.isLetter() || next == '_' -> {
                    var j = at + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                    if (j < n && text[j] == '%') j + 1 else at
                }
                else -> at
            }
        }
        return when {
            next == '{' -> {
                var j = at + 2
                var nesting = 1
                while (j < n && j - at < 200 && text[j] != '\n') {
                    if (text[j] == '{') nesting++
                    if (text[j] == '}' && --nesting == 0) return j + 1
                    j++
                }
                at + 1
            }
            next == '(' -> {
                // $(NAME) in Makefiles; a $(command) only colors the $.
                var j = at + 2
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                if (j < n && text[j] == ')' && j > at + 2) j + 1 else at + 1
            }
            next.isLetter() || next == '_' || next.isDigit() -> {
                var j = at + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                j
            }
            next in "@#?$!*" -> at + 2
            else -> at
        }
    }

    private fun number() {
        var j = i
        while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || (text[j] == '.' && j + 1 < n && text[j + 1].isDigit()))) j++
        out.add(i, j, TokenKind.Number)
        i = j
    }

    private fun word() {
        val start = i
        var end = wordEnd(i)
        val word = text.subSequence(start, end).toString()
        val key = if (lang.ignoreCase) word.lowercase() else word
        val next = nextOnLine(end)
        val kind = when {
            key in lang.keywords -> TokenKind.Keyword
            key in lang.constants -> TokenKind.Constant
            key in lang.types -> TokenKind.Type
            lang.macros && end < n && text[end] == '!' -> { end++; TokenKind.Function }
            key in lang.builtins -> TokenKind.Function
            lang.cssProperties && depth > 0 && next == ':' -> TokenKind.Attribute
            next == '(' -> TokenKind.Function
            lang.capitalizedTypes && word[0].isUpperCase() ->
                if (word.length == 1 || word.any { it.isLowerCase() }) TokenKind.Type else TokenKind.Constant
            else -> null
        }
        if (kind != null) out.add(start, end, kind)
        i = end
    }

    /** `#include <x.h>`, `#define`, `#[derive(…)]`, `#![…]`. */
    private fun directive() {
        val start = i
        var j = i + 1
        if (j < n && text[j] == '!') j++
        if (j < n && text[j] == '[') {
            val close = indexOf(']', j, lineEnd(j))
            j = if (close < 0) lineEnd(j) else close + 1
            out.add(start, j, TokenKind.Meta)
            i = j
            return
        }
        while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
        val nameStart = j
        j = wordEnd(j)
        out.add(start, j, TokenKind.Meta)
        val name = text.subSequence(nameStart, j)
        i = j
        if (name == "include" || name == "import") {
            while (i < n && (text[i] == ' ' || text[i] == '\t')) i++
            if (i < n && text[i] == '<') {
                val close = indexOf('>', i, lineEnd(i))
                if (close >= 0) {
                    out.add(i, close + 1, TokenKind.String)
                    i = close + 1
                }
            }
        }
    }

    /** `key: value`, `key = value`, `KEY := value`, `target: deps`, `- key: value`. */
    private fun key(): Boolean {
        var j = i
        if (text[j] == '-' && j + 1 < n && text[j + 1] == ' ') {
            j += 2
            while (j < n && text[j] == ' ') j++
        }
        val keyStart = j
        if (j < n && (text[j] == '"' || text[j] == '\'')) {
            val quote = text[j]
            j++
            while (j < n && text[j] != quote && text[j] != '\n') j++
            if (j >= n || text[j] != quote) return false
            j++
        } else {
            while (j < n && text[j] !in " \t\r\n:=#\"'") j++
        }
        if (j == keyStart) return false
        val keyEnd = j
        while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
        if (j >= n) return false
        val separator = text[j]
        val after = if (j + 1 < n) text[j + 1] else '\n'
        val isKey = separator == '=' ||
            (separator == ':' && (after == ' ' || after == '\t' || after == '\r' || after == '\n' || after == '=')) ||
            (separator in "?+!" && after == '=')
        if (!isKey) return false
        out.add(keyStart, keyEnd, TokenKind.Key)
        i = keyEnd
        return true
    }

    private fun isWordStart(c: Char) = c.isLetter() || (c in lang.identifierChars && c != '-')
    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c in lang.identifierChars

    private fun wordEnd(from: Int): Int {
        var j = from
        while (j < n && isWordChar(text[j])) j++
        return j
    }

    private fun nextOnLine(from: Int): Char {
        var j = from
        while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
        return if (j < n) text[j] else '\n'
    }

    private fun lineEnd(from: Int): Int {
        var j = from
        while (j < n && text[j] != '\n') j++
        return j
    }

    private fun matches(at: Int, s: String): Boolean {
        if (at + s.length > n) return false
        for (k in s.indices) {
            if (!text[at + k].equals(s[k], ignoreCase = lang.ignoreCase)) return false
        }
        return true
    }

    private fun indexOf(s: String, from: Int): Int {
        var j = from
        while (j + s.length <= n) {
            if (matches(j, s)) return j
            j++
        }
        return -1
    }

    private fun indexOf(c: Char, from: Int, until: Int): Int {
        for (j in from until until) if (text[j] == c) return j
        return -1
    }
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun CharSequence.startsWithAt(at: Int, s: String): Boolean {
    if (at + s.length > length) return false
    for (k in s.indices) if (this[at + k] != s[k]) return false
    return true
}

private fun CharSequence.find(s: String, from: Int): Int {
    var j = from
    while (j + s.length <= length) {
        if (startsWithAt(j, s)) return j
        j++
    }
    return -1
}

/** XML, HTML, SVG: tags, attributes, values, comments and entities. */
private fun highlightMarkup(text: CharSequence): SyntaxSpans {
    val out = SpanBuilder()
    val n = text.length
    var i = 0
    fun until(close: String, from: Int): Int = text.find(close, from).let { if (it < 0) n else it + close.length }
    while (i < n) {
        val c = text[i]
        when {
            c == '<' && text.startsWithAt(i, "<!--") -> { val end = until("-->", i + 4); out.add(i, end, TokenKind.Comment); i = end }
            c == '<' && text.startsWithAt(i, "<![CDATA[") -> { val end = until("]]>", i + 9); out.add(i, end, TokenKind.String); i = end }
            c == '<' && text.startsWithAt(i, "<?") -> { val end = until("?>", i + 2); out.add(i, end, TokenKind.Meta); i = end }
            c == '<' && text.startsWithAt(i, "<!") -> { val end = until(">", i + 2); out.add(i, end, TokenKind.Meta); i = end }
            c == '<' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '/') -> {
                var j = i + 1
                if (text[j] == '/') j++
                while (j < n && (text[j].isLetterOrDigit() || text[j] in "-_:.")) j++
                out.add(i, j, TokenKind.Tag)
                // Attributes until the tag closes.
                while (j < n) {
                    val ch = text[j]
                    when {
                        ch == '>' -> { out.add(j, j + 1, TokenKind.Tag); j++; break }
                        ch == '/' && j + 1 < n && text[j + 1] == '>' -> { out.add(j, j + 2, TokenKind.Tag); j += 2; break }
                        ch == '<' -> break // Malformed: let the outer loop handle the next tag.
                        ch == '"' || ch == '\'' -> {
                            val close = text.find(ch.toString(), j + 1)
                            val end = if (close < 0) n else close + 1
                            out.add(j, end, TokenKind.String)
                            j = end
                        }
                        ch.isLetter() || ch in "_:@" -> {
                            val start = j
                            while (j < n && !text[j].isWhitespace() && text[j] !in "=/>\"'") j++
                            out.add(start, j, TokenKind.Attribute)
                        }
                        else -> j++
                    }
                }
                i = j
            }
            c == '&' -> {
                var j = i + 1
                while (j < n && j - i <= 10 && (text[j].isLetterOrDigit() || text[j] == '#')) j++
                if (j < n && text[j] == ';' && j > i + 1) {
                    out.add(i, j + 1, TokenKind.Constant)
                    i = j + 1
                } else {
                    i++
                }
            }
            else -> i++
        }
    }
    return out.build()
}

/** Markdown: headings, quotes, list markers, code and links. */
private fun highlightMarkdown(text: CharSequence): SyntaxSpans {
    val out = SpanBuilder()
    val n = text.length
    var fenced = false
    var lineStart = 0
    while (lineStart <= n) {
        var lineEnd = lineStart
        while (lineEnd < n && text[lineEnd] != '\n') lineEnd++
        var t = lineStart
        while (t < lineEnd && t - lineStart < 4 && text[t] == ' ') t++
        val fence = text.startsWithAt(t, "```") || text.startsWithAt(t, "~~~")
        when {
            fence || fenced -> {
                out.add(lineStart, lineEnd, TokenKind.String)
                if (fence) fenced = !fenced
            }
            t < lineEnd && text[t] == '#' -> {
                var h = t
                while (h < lineEnd && text[h] == '#') h++
                if (h - t <= 6 && (h == lineEnd || text[h] == ' ')) out.add(t, lineEnd, TokenKind.Heading)
                else markdownInline(text, out, t, lineEnd)
            }
            t < lineEnd && text[t] == '>' -> out.add(t, lineEnd, TokenKind.Comment)
            else -> {
                var start = t
                if (t + 1 < lineEnd && text[t] in "-*+" && text[t + 1] == ' ') {
                    out.add(t, t + 1, TokenKind.Keyword)
                    start = t + 2
                } else {
                    var d = t
                    while (d < lineEnd && text[d].isDigit()) d++
                    if (d > t && d + 1 < lineEnd && text[d] in ".)" && text[d + 1] == ' ') {
                        out.add(t, d + 1, TokenKind.Keyword)
                        start = d + 2
                    }
                }
                markdownInline(text, out, start, lineEnd)
            }
        }
        lineStart = lineEnd + 1
    }
    return out.build()
}

private fun markdownInline(text: CharSequence, out: SpanBuilder, from: Int, to: Int) {
    var i = from
    while (i < to) {
        val c = text[i]
        if (c == '`') {
            var close = i + 1
            while (close < to && text[close] != '`') close++
            if (close < to) {
                out.add(i, close + 1, TokenKind.String)
                i = close + 1
                continue
            }
        }
        if (c == '[') {
            var close = i + 1
            while (close < to && text[close] != ']') close++
            if (close + 1 < to && text[close + 1] == '(') {
                var paren = close + 2
                while (paren < to && text[paren] != ')') paren++
                if (paren < to) {
                    out.add(i, close + 1, TokenKind.Function)
                    out.add(close + 1, paren + 1, TokenKind.String)
                    i = paren + 1
                    continue
                }
            }
        }
        i++
    }
}

/** Unified diffs and patches: added and removed lines, hunk headers, file headers. */
private fun highlightDiff(text: CharSequence): SyntaxSpans {
    val out = SpanBuilder()
    val n = text.length
    var lineStart = 0
    while (lineStart < n) {
        var lineEnd = lineStart
        while (lineEnd < n && text[lineEnd] != '\n') lineEnd++
        val kind = when {
            text.startsWithAt(lineStart, "+++") || text.startsWithAt(lineStart, "---") ||
                text.startsWithAt(lineStart, "diff ") || text.startsWithAt(lineStart, "index ") -> TokenKind.Meta
            text.startsWithAt(lineStart, "@@") -> TokenKind.Meta
            text[lineStart] == '+' -> TokenKind.Inserted
            text[lineStart] == '-' -> TokenKind.Deleted
            else -> null
        }
        if (kind != null) out.add(lineStart, lineEnd, kind)
        lineStart = lineEnd + 1
    }
    return out.build()
}

// ---- Languages ----

private fun words(s: String): Set<String> = s.split(' ', '\n').filterTo(HashSet()) { it.isNotEmpty() }

private val C_COMMENTS = listOf("//")
private val C_BLOCK = listOf("/*" to "*/")
private val TRUE_FALSE_NULL = words("true false null")

object Languages {
    val Kotlin = Language(
        "Kotlin", lineComments = C_COMMENTS, blockComments = C_BLOCK, longStrings = listOf("\"\"\""),
        interpolatingQuotes = "\"", capitalizedTypes = true, annotations = true, constants = TRUE_FALSE_NULL,
        keywords = words(
            "package import class interface fun val var object companion data sealed enum annotation open abstract " +
                "override private protected public internal inline noinline crossinline reified suspend operator infix " +
                "tailrec lateinit const external vararg value if else when for while do return break continue throw try " +
                "catch finally is in as typealias init constructor this super where by out",
        ),
    )

    private val javaKeywords = words(
        "abstract assert boolean break byte case catch char class const continue default do double else enum " +
            "extends final finally float for goto if implements import instanceof int interface long native new package " +
            "private protected public return short static strictfp super switch synchronized this throw throws transient " +
            "try void volatile while var record yield sealed permits",
    )

    val Java = Language(
        "Java", lineComments = C_COMMENTS, blockComments = C_BLOCK, longStrings = listOf("\"\"\""),
        capitalizedTypes = true, annotations = true, constants = TRUE_FALSE_NULL, keywords = javaKeywords,
    )

    val Groovy = Language(
        "Groovy", lineComments = C_COMMENTS, blockComments = C_BLOCK, longStrings = listOf("\"\"\"", "'''"),
        interpolatingQuotes = "\"", capitalizedTypes = true, annotations = true, constants = TRUE_FALSE_NULL,
        keywords = javaKeywords + words("def in as trait"),
    )

    val Scala = Language(
        "Scala", lineComments = C_COMMENTS, blockComments = C_BLOCK, longStrings = listOf("\"\"\""),
        capitalizedTypes = true, annotations = true, constants = TRUE_FALSE_NULL,
        keywords = words(
            "abstract case catch class def do else extends final finally for forSome if implicit import lazy match new " +
                "object override package private protected return sealed super this throw trait try type val var while " +
                "with yield given using then extension enum export",
        ),
    )

    private val jsKeywords = words(
        "break case catch class const continue debugger default delete do else export extends finally for function " +
            "if import in instanceof let new return super switch this throw try typeof var void while with yield async " +
            "await of static get set from as",
    )
    private val jsConstants = words("true false null undefined NaN Infinity")

    val JavaScript = Language(
        "JavaScript", lineComments = C_COMMENTS, blockComments = C_BLOCK, longStrings = listOf("`"),
        interpolatingQuotes = "`", identifierChars = "_$", capitalizedTypes = true, annotations = true,
        constants = jsConstants, keywords = jsKeywords,
    )

    val TypeScript = Language(
        "TypeScript", lineComments = C_COMMENTS, blockComments = C_BLOCK, longStrings = listOf("`"),
        interpolatingQuotes = "`", identifierChars = "_$", capitalizedTypes = true, annotations = true,
        constants = jsConstants,
        keywords = jsKeywords + words("interface type enum implements namespace declare abstract private protected public readonly keyof infer is satisfies"),
        types = words("any unknown never number string boolean symbol object bigint"),
    )

    private val cKeywords = words(
        "auto break case char const continue default do double else enum extern float for goto if inline int long " +
            "register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while bool",
    )
    private val cTypes = words("size_t ssize_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t uintptr_t FILE")

    val C = Language(
        "C", lineComments = C_COMMENTS, blockComments = C_BLOCK, preprocessor = true,
        constants = words("true false NULL"), types = cTypes, keywords = cKeywords,
    )

    val Cpp = Language(
        "C++", lineComments = C_COMMENTS, blockComments = C_BLOCK, preprocessor = true, capitalizedTypes = true,
        annotations = true, constants = words("true false NULL nullptr this"),
        types = cTypes + words("std string vector map set unique_ptr shared_ptr"),
        keywords = cKeywords + words(
            "class namespace template typename public private protected virtual override final friend operator new " +
                "delete using try catch throw constexpr consteval noexcept explicit mutable static_cast dynamic_cast " +
                "reinterpret_cast const_cast decltype concept requires co_await co_return co_yield",
        ),
    )

    val CSharp = Language(
        "C#", lineComments = C_COMMENTS, blockComments = C_BLOCK, preprocessor = true, capitalizedTypes = true,
        constants = TRUE_FALSE_NULL,
        keywords = words(
            "abstract as base bool break byte case catch char checked class const continue decimal default delegate do " +
                "double else enum event explicit extern finally fixed float for foreach goto if implicit in int interface " +
                "internal is lock long namespace new object operator out override params private protected public readonly " +
                "ref return sbyte sealed short sizeof stackalloc static string struct switch this throw try typeof uint " +
                "ulong unchecked unsafe ushort using virtual void volatile while var async await get set value yield record init",
        ),
    )

    val Go = Language(
        "Go", lineComments = C_COMMENTS, blockComments = C_BLOCK, longStrings = listOf("`"), capitalizedTypes = true,
        constants = words("true false nil iota"),
        types = words("bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr any"),
        keywords = words(
            "break case chan const continue default defer else fallthrough for func go goto if import interface map " +
                "package range return select struct switch type var",
        ),
    )

    val Rust = Language(
        "Rust", lineComments = C_COMMENTS, blockComments = C_BLOCK, quotes = "\"", preprocessor = true, macros = true,
        capitalizedTypes = true, constants = words("true false None Some Ok Err self"),
        types = words("i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str Self"),
        keywords = words(
            "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move " +
                "mut pub ref return static struct super trait type unsafe use where while",
        ),
    )

    val Swift = Language(
        "Swift", lineComments = C_COMMENTS, blockComments = C_BLOCK, quotes = "\"", longStrings = listOf("\"\"\""),
        capitalizedTypes = true, annotations = true, constants = words("true false nil self Self"),
        keywords = words(
            "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator " +
                "private protocol public rethrows static struct subscript typealias var break case continue default defer " +
                "do else fallthrough for guard if in repeat return switch where while as catch is super throw throws try " +
                "async await some any weak unowned lazy mutating override final convenience required",
        ),
    )

    val Dart = Language(
        "Dart", lineComments = C_COMMENTS, blockComments = C_BLOCK, longStrings = listOf("\"\"\"", "'''"),
        interpolatingQuotes = "\"'", capitalizedTypes = true, annotations = true, constants = TRUE_FALSE_NULL,
        types = words("int double num bool void dynamic"),
        keywords = words(
            "abstract as assert async await break case catch class const continue covariant default deferred do else " +
                "enum export extends extension external factory final finally for get if implements import in interface is " +
                "late library mixin new on operator part required rethrow return sealed set show static super switch sync " +
                "this throw try typedef var while with yield",
        ),
    )

    val Php = Language(
        "PHP", lineComments = listOf("//", "#"), blockComments = C_BLOCK, sigils = "$", interpolatingQuotes = "\"",
        capitalizedTypes = true, constants = words("true false null TRUE FALSE NULL"),
        keywords = words(
            "abstract and array as break callable case catch class clone const continue declare default do echo else " +
                "elseif empty enddeclare endfor endforeach endif endswitch endwhile enum extends final finally fn for " +
                "foreach function global goto if implements include include_once instanceof insteadof interface isset list " +
                "match namespace new or print private protected public readonly require require_once return static switch " +
                "throw trait try unset use var while xor yield",
        ),
    )

    val Python = Language(
        "Python", lineComments = listOf("#"), longStrings = listOf("\"\"\"", "'''"), capitalizedTypes = true,
        annotations = true, constants = words("True False None self cls"),
        types = words("int str float bool list dict set tuple bytes object type"),
        keywords = words(
            "and as assert async await break class continue def del elif else except finally for from global if import " +
                "in is lambda nonlocal not or pass raise return try while with yield match case",
        ),
    )

    val Ruby = Language(
        "Ruby", lineComments = listOf("#"), blockComments = listOf("=begin" to "=end"), capitalizedTypes = true,
        annotations = true, constants = words("true false nil self"),
        keywords = words(
            "BEGIN END alias and begin break case class def defined? do else elsif end ensure for if in module next not " +
                "or redo rescue retry return super then undef unless until when while yield require require_relative " +
                "attr_accessor attr_reader attr_writer private protected public raise lambda proc",
        ),
    )

    val Lua = Language(
        "Lua", lineComments = listOf("--"), blockComments = listOf("--[[" to "]]"),
        constants = words("true false nil"),
        keywords = words("and break do else elseif end for function goto if in local not or repeat return then until while"),
    )

    val Shell = Language(
        "Shell", lineComments = listOf("#"), commentNeedsSpace = true, multilineQuotes = true, rawSingleQuotes = true,
        sigils = "$", interpolatingQuotes = "\"", identifierChars = "_-",
        keywords = words(
            "if then else elif fi case esac for select while until do done in function time return exit break continue " +
                "declare typeset local export readonly unset shift source alias set trap eval exec",
        ),
        builtins = words(
            "echo printf read cd pwd pushd popd test let getopts shopt ulimit umask wait kill jobs fg bg type hash command builtin true false",
        ),
    )

    val PowerShell = Language(
        "PowerShell", lineComments = listOf("#"), blockComments = listOf("<#" to "#>"), sigils = "$",
        interpolatingQuotes = "\"", identifierChars = "_-", ignoreCase = true,
        keywords = words(
            "begin break catch class continue data define do dynamicparam else elseif end exit filter finally for " +
                "foreach from function if in param process return switch throw trap try until using var while workflow",
        ),
    )

    val Batch = Language(
        "Batch", lineComments = listOf("rem", "::"), quotes = "\"", sigils = "%", ignoreCase = true, annotations = true,
        keywords = words(
            "if else for in do goto call exit set setlocal endlocal echo not exist defined errorlevel equ neq lss leq gtr geq pause shift start cd",
        ),
    )

    val Sql = Language(
        "SQL", lineComments = listOf("--"), blockComments = C_BLOCK, ignoreCase = true,
        constants = words("null true false"),
        types = words("int integer bigint smallint tinyint decimal numeric float real double varchar char text blob date time timestamp datetime boolean serial uuid json jsonb"),
        keywords = words(
            "select from where and or not insert into values update set delete create table drop alter add column index " +
                "view primary key foreign references unique default join inner left right outer full cross on as group by " +
                "order having limit offset union all distinct case when then else end is in like between exists if begin " +
                "commit rollback transaction trigger procedure function returns return declare with recursive asc desc " +
                "constraint check cascade database schema grant revoke replace",
        ),
    )

    val Perl = Language(
        "Perl", lineComments = listOf("#"), sigils = "$", interpolatingQuotes = "\"",
        keywords = words(
            "my our local sub package use require no if elsif else unless while until for foreach do last next redo " +
                "return print say die warn eval defined undef qw and or not",
        ),
    )

    val R = Language(
        "R", lineComments = listOf("#"), constants = words("TRUE FALSE NULL Inf NaN NA T F"),
        keywords = words("if else repeat while function for in next break return library require"),
    )

    val Julia = Language(
        "Julia", lineComments = listOf("#"), blockComments = listOf("#=" to "=#"), longStrings = listOf("\"\"\""),
        capitalizedTypes = true, annotations = true, constants = words("true false nothing missing"),
        keywords = words("function end if elseif else for while return module using import export struct mutable begin let do try catch finally break continue const global local macro quote abstract type"),
    )

    val Haskell = Language(
        "Haskell", lineComments = listOf("--"), blockComments = listOf("{-" to "-}"), quotes = "\"",
        capitalizedTypes = true, constants = words("True False Nothing Just"),
        keywords = words("module import where let in case of if then else data type newtype class instance deriving do qualified as hiding exposing port"),
    )

    val Elixir = Language(
        "Elixir", lineComments = listOf("#"), longStrings = listOf("\"\"\""), capitalizedTypes = true,
        annotations = true, constants = words("true false nil"),
        keywords = words("def defp defmodule defmacro defstruct defprotocol defimpl do end fn if else unless case cond when with import alias require use quote unquote receive try catch rescue after raise in and or not"),
    )

    val Erlang = Language(
        "Erlang", lineComments = listOf("%"), quotes = "\"", capitalizedTypes = true,
        constants = words("true false undefined"),
        keywords = words("after and andalso band begin bnot bor bsl bsr bxor case catch cond div end fun if let not of or orelse receive rem try when xor module export import record define"),
    )

    val Clojure = Language(
        "Clojure", lineComments = listOf(";"), quotes = "\"", identifierChars = "_-?!*<>=/+.",
        constants = words("true false nil"),
        keywords = words("def defn defn- defmacro defrecord defprotocol let fn if do when when-not cond case loop recur ns require import try catch finally throw"),
    )

    val Css = Language(
        "CSS", lineComments = C_COMMENTS, blockComments = C_BLOCK, identifierChars = "_-", sigils = "$",
        annotations = true, hashColors = true, cssProperties = true, constants = words("important inherit initial unset none auto"),
    )

    val Json = Language(
        "JSON", lineComments = C_COMMENTS, blockComments = C_BLOCK, quotes = "\"", objectKeys = true,
        constants = TRUE_FALSE_NULL,
    )

    val Yaml = Language(
        "YAML", lineComments = listOf("#"), commentNeedsSpace = true, keyValue = true, looseQuotes = true,
        constants = words("true false null yes no on off True False Null ~"),
    )

    val Toml = Language(
        "TOML", lineComments = listOf("#"), longStrings = listOf("\"\"\"", "'''"), keyValue = true, sections = true,
        looseQuotes = true, constants = words("true false"),
    )

    val Ini = Language(
        "INI", lineComments = listOf("#", ";"), keyValue = true, sections = true, looseQuotes = true,
        sigils = "$", constants = words("true false yes no on off"),
    )

    val Makefile = Language(
        "Makefile", lineComments = listOf("#"), commentNeedsSpace = true, keyValue = true, sigils = "$",
        looseQuotes = true,
        keywords = words("ifeq ifneq ifdef ifndef else endif include define endef export override vpath"),
    )

    val Dockerfile = Language(
        "Dockerfile", lineComments = listOf("#"), commentNeedsSpace = true, sigils = "$", ignoreCase = true,
        keywords = words("from run cmd label expose env add copy entrypoint volume user workdir arg onbuild stopsignal healthcheck shell as maintainer"),
    )

    val CMake = Language(
        "CMake", lineComments = listOf("#"), quotes = "\"", sigils = "$", ignoreCase = true,
        keywords = words("if elseif else endif foreach endforeach while endwhile function endfunction macro endmacro return"),
    )

    val GraphQL = Language(
        "GraphQL", lineComments = listOf("#"), quotes = "\"", longStrings = listOf("\"\"\""), sigils = "$",
        annotations = true, capitalizedTypes = true, constants = TRUE_FALSE_NULL,
        keywords = words("query mutation subscription fragment on type interface union enum input scalar schema extend implements directive"),
    )

    val Protobuf = Language(
        "Protocol Buffers", lineComments = C_COMMENTS, blockComments = C_BLOCK, capitalizedTypes = true,
        constants = words("true false"),
        types = words("double float int32 int64 uint32 uint64 sint32 sint64 fixed32 fixed64 sfixed32 sfixed64 bool string bytes"),
        keywords = words("syntax edition package import option message enum service rpc returns stream repeated optional required oneof map reserved extend extensions"),
    )

    val Awk = Language(
        "AWK", lineComments = listOf("#"), quotes = "\"", sigils = "$",
        keywords = words("BEGIN END function if else while for in do break continue next nextfile exit return delete getline print printf"),
    )

    /** Comments only: .gitignore and friends. */
    val Ignore = Language("Ignore file", lineComments = listOf("#"), quotes = "")

    val Markup = Language("XML", mode = Mode.Markup)
    val Markdown = Language("Markdown", mode = Mode.Markdown)
    val Diff = Language("Diff", mode = Mode.Diff)

    private val byExtension: Map<String, Language> = buildMap {
        fun map(extensions: String, language: Language) = words(extensions).forEach { put(it, language) }
        map("kt kts", Kotlin)
        map("java", Java)
        map("groovy gradle gvy", Groovy)
        map("scala sc sbt", Scala)
        map("js mjs cjs jsx", JavaScript)
        map("ts tsx mts cts", TypeScript)
        map("c h", C)
        map("cc cpp cxx hpp hh hxx ino m mm", Cpp)
        map("cs", CSharp)
        map("go", Go)
        map("rs", Rust)
        map("swift", Swift)
        map("dart", Dart)
        map("php phtml", Php)
        map("py pyw pyi gd", Python)
        map("rb rake gemspec", Ruby)
        map("lua", Lua)
        map("sh bash zsh ksh fish command", Shell)
        map("ps1 psm1 psd1", PowerShell)
        map("bat cmd", Batch)
        map("sql", Sql)
        map("pl pm", Perl)
        map("r", R)
        map("jl", Julia)
        map("hs lhs elm", Haskell)
        map("ex exs", Elixir)
        map("erl hrl", Erlang)
        map("clj cljs cljc edn", Clojure)
        map("css scss sass less", Css)
        map("json json5 jsonc geojson webmanifest", Json)
        map("yml yaml", Yaml)
        map("toml", Toml)
        map("ini cfg conf config properties prop env editorconfig gitconfig npmrc desktop service", Ini)
        map("mk", Makefile)
        map("cmake", CMake)
        map("graphql gql", GraphQL)
        map("proto", Protobuf)
        map("awk", Awk)
        map("gitignore gitattributes dockerignore", Ignore)
        map("xml html htm xhtml svg plist xsd xsl xslt xaml csproj vbproj fxml kml gpx rss atom vue svelte", Markup)
        map("md markdown", Markdown)
        map("diff patch", Diff)
    }

    private val byName: Map<String, Language> = buildMap {
        fun map(names: String, language: Language) = words(names).forEach { put(it, language) }
        map("makefile gnumakefile", Makefile)
        map("dockerfile containerfile", Dockerfile)
        map("cmakelists.txt", CMake)
        map("gemfile rakefile podfile vagrantfile brewfile", Ruby)
        map("jenkinsfile", Groovy)
        map(".bashrc .bash_profile .bash_aliases .bash_logout .zshrc .zprofile .zshenv .zlogin .profile .kshrc", Shell)
        map(".env", Ini)
    }

    /** The language of a file, from its name, or from a `#!` line when the name doesn't tell. */
    fun forFile(name: String, firstLine: CharSequence? = null): Language? {
        val lower = name.lowercase()
        byName[lower]?.let { return it }
        if (lower.startsWith("dockerfile.") || lower.endsWith(".dockerfile")) return Dockerfile
        if (lower.startsWith(".env.")) return Ini
        byExtension[lower.substringAfterLast('.', "")]?.let { return it }
        val shebang = firstLine?.takeIf { it.startsWith("#!") }?.toString() ?: return null
        val interpreter = shebang.substringAfterLast('/').removePrefix("env ").trim().substringBefore(' ')
        return when {
            interpreter.startsWith("python") -> Python
            interpreter in setOf("sh", "bash", "zsh", "ksh", "dash", "ash", "fish") -> Shell
            interpreter.startsWith("node") || interpreter == "deno" || interpreter == "bun" -> JavaScript
            interpreter.startsWith("ruby") -> Ruby
            interpreter.startsWith("perl") -> Perl
            interpreter.startsWith("php") -> Php
            interpreter.startsWith("lua") -> Lua
            interpreter == "pwsh" -> PowerShell
            interpreter == "Rscript" -> R
            interpreter == "awk" || interpreter == "gawk" -> Awk
            else -> null
        }
    }

    /** Whether [name] is source code or a script by its name alone. */
    fun isCode(name: String): Boolean {
        val lower = name.lowercase()
        return lower in byName || lower.substringAfterLast('.', "") in byExtension
    }
}
