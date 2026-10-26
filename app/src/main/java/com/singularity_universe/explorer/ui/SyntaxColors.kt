package com.singularity_universe.explorer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.singularity_universe.explorer.data.Language
import com.singularity_universe.explorer.data.SyntaxSpans
import com.singularity_universe.explorer.data.TokenKind
import com.singularity_universe.explorer.data.highlight

/**
 * Colors for each [TokenKind]: Darcula on dark backgrounds, IntelliJ Light on light ones (the same
 * palettes as Workshop). Fixed rather than taken from the Material scheme, so token colors stay
 * distinguishable whatever the wallpaper colors are.
 */
@Immutable
class SyntaxPalette private constructor(private val styles: Array<SpanStyle>) {
    fun style(kind: TokenKind) = styles[kind.ordinal]

    companion object {
        private fun palette(
            keyword: Long, string: Long, comment: Long, number: Long, type: Long, annotation: Long,
            tag: Long, attribute: Long, property: Long, added: Long, removed: Long,
        ) = SyntaxPalette(
            TokenKind.entries.map { kind ->
                when (kind) {
                    // true/false/null are keywords in IntelliJ's schemes too.
                    TokenKind.Keyword, TokenKind.Constant -> SpanStyle(color = Color(keyword))
                    TokenKind.Heading -> SpanStyle(color = Color(keyword), fontWeight = FontWeight.Bold)
                    TokenKind.String -> SpanStyle(color = Color(string))
                    TokenKind.Comment -> SpanStyle(color = Color(comment), fontStyle = FontStyle.Italic)
                    TokenKind.Number -> SpanStyle(color = Color(number))
                    TokenKind.Type -> SpanStyle(color = Color(type))
                    TokenKind.Meta -> SpanStyle(color = Color(annotation))
                    TokenKind.Tag -> SpanStyle(color = Color(tag))
                    TokenKind.Attribute -> SpanStyle(color = Color(attribute))
                    TokenKind.Variable, TokenKind.Key -> SpanStyle(color = Color(property))
                    TokenKind.Inserted -> SpanStyle(color = Color(added))
                    TokenKind.Deleted -> SpanStyle(color = Color(removed))
                    // Calls stay the plain text color, as in IntelliJ.
                    TokenKind.Function -> SpanStyle()
                }
            }.toTypedArray(),
        )

        val Dark = palette(
            keyword = 0xFFCF8E6D, string = 0xFF6AAB73, comment = 0xFF7A7E85, number = 0xFF2AACB8,
            type = 0xFF56A8F5, annotation = 0xFFB3AE60, tag = 0xFFE8BF6A, attribute = 0xFFBABABA,
            property = 0xFFC77DBB, added = 0xFF81C784, removed = 0xFFE57373,
        )
        val Light = palette(
            keyword = 0xFF0033B3, string = 0xFF067D17, comment = 0xFF8C8C8C, number = 0xFF1750EB,
            type = 0xFF00627A, annotation = 0xFF9E880D, tag = 0xFF0033B3, attribute = 0xFF174AD4,
            property = 0xFF871094, added = 0xFF067D17, removed = 0xFFC7222D,
        )
    }
}

/** The palette that suits the current theme's background. */
@Composable
fun syntaxPalette(): SyntaxPalette =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) SyntaxPalette.Dark else SyntaxPalette.Light

/** [text], which sits at [offset] in the highlighted source, with its spans' colors applied. */
fun SyntaxSpans.annotate(text: String, offset: Int, palette: SyntaxPalette): AnnotatedString {
    if (size == 0) return AnnotatedString(text)
    val end = offset + text.length
    val ranges = ArrayList<AnnotatedString.Range<SpanStyle>>()
    var index = indexAfter(offset)
    while (index < size && start(index) < end) {
        ranges += AnnotatedString.Range(
            palette.style(kind(index)),
            maxOf(start(index), offset) - offset,
            minOf(end(index), end) - offset,
        )
        index++
    }
    return AnnotatedString(text, ranges)
}

/** Colors the text of an editor as code. Offsets don't change, so the cursor maps one to one. */
class SyntaxTransformation(private val language: Language, private val palette: SyntaxPalette) : VisualTransformation {
    // Recomposition calls filter() again with unchanged text (cursor moves): reuse the last result.
    private var lastText: String? = null
    private var lastResult: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        lastResult?.let { if (raw == lastText) return it }
        val colored = if (raw.length > MAX_EDITOR_HIGHLIGHT) text else highlight(raw, language).annotate(raw, 0, palette)
        return TransformedText(colored, OffsetMapping.Identity).also {
            lastText = raw
            lastResult = it
        }
    }

    override fun equals(other: Any?) = other is SyntaxTransformation && other.language === language && other.palette === palette
    override fun hashCode() = 31 * language.hashCode() + palette.hashCode()

    private companion object {
        /** Bigger files are edited uncolored, so typing stays smooth. */
        const val MAX_EDITOR_HIGHLIGHT = 200_000
    }
}
