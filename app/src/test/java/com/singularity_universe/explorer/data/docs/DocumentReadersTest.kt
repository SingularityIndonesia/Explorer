package com.singularity_universe.explorer.data.docs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads sample files made with python-docx, openpyxl and python-pptx (see the test resources)
 * and checks what the previews would show.
 */
class DocumentReadersTest {
    private val dir = File("src/test/resources/docs")

    @Test
    fun word() {
        val doc = DocxReader.read(File(dir, "sample.docx"))
        val paragraphs = doc.blocks.filterIsInstance<DocxParagraph>().filterNot { it.isEmpty }
        fun text(p: DocxParagraph) = p.runs.joinToString("") { it.text }

        assertEquals("Quarterly Report", text(paragraphs[0]))
        assertEquals("Title is a level-1 heading", 1, paragraphs[0].level)
        assertEquals("Summary", text(paragraphs[1]))
        assertEquals(1, paragraphs[1].level)

        val styled = paragraphs[2]
        assertEquals("Plain, bold, italic red, underlined 20pt", text(styled))
        val bold = styled.runs.first { it.text == "bold" }
        assertTrue(bold.bold)
        val red = styled.runs.first { it.text == "italic red" }
        assertTrue(red.italic)
        assertEquals(0xC00000, red.color)
        val big = styled.runs.first { it.text == "underlined 20pt" }
        assertTrue(big.underline)
        assertEquals(20f, big.size)

        // Lists numbered through their paragraph style ("List Bullet", "List Number").
        val bullets = paragraphs.filter { text(it).endsWith("bullet") }
        assertEquals(listOf("•", "•"), bullets.map { it.prefix })
        val steps = paragraphs.filter { text(it).startsWith("Step") }
        assertEquals(listOf("1.", "2."), steps.map { it.prefix })

        assertEquals(DocxParagraph.Align.Center, paragraphs.first { text(it) == "Centered line" }.align)
        assertEquals(2, paragraphs.first { text(it) == "Details" }.level)
        assertTrue(paragraphs.any { text(it) == "Line one\tafter tab" })

        val table = doc.blocks.filterIsInstance<DocxTable>().single()
        val cells = table.rows.map { row -> row.map { cell -> cell.filterIsInstance<DocxParagraph>().joinToString("") { text(it) } } }
        assertEquals(listOf(listOf("Region", "Units", "Revenue"), listOf("North", "120", "$4,800")), cells)

        val image = doc.blocks.filterIsInstance<DocxImage>().single()
        assertTrue(image.path, image.path.startsWith("word/media/"))
    }

    @Test
    fun excel() {
        val book = XlsxReader.read(File(dir, "sample.xlsx"))
        assertEquals(listOf("Sales", "Notes"), book.sheets.map { it.name })
        val sales = book.sheets[0]
        fun cell(ref: String): String? {
            val column = ref.takeWhile { it.isLetter() }.fold(0) { n, c -> n * 26 + (c - 'A' + 1) } - 1
            val row = ref.dropWhile { it.isLetter() }.toInt() - 1
            return sales.cells[row]?.get(column)
        }
        assertEquals("Item", cell("A1"))
        assertEquals("Apples", cell("A2"))
        assertEquals("3", cell("B2"))
        assertEquals("float noise is rounded away", "0.3", cell("C2"))
        assertEquals("2026-10-23", cell("D2"))
        assertEquals("25%", cell("E2"))
        assertEquals("TRUE", cell("F2"))
        assertEquals("2026-01-02 13:30", cell("D3"))
        assertEquals("FALSE", cell("F3"))
        assertEquals("far away", cell("AB10"))
        assertEquals(10, sales.rows)
        assertEquals(28, sales.columns)
        assertEquals("AB", XlsxReader.columnName(27))
        assertEquals("Hello, sheet two", book.sheets[1].cells[0]?.get(0))
    }

    @Test
    fun powerPoint() {
        val deck = PptxReader.read(File(dir, "sample.pptx"))
        assertEquals(3, deck.slides.size)
        fun texts(slide: PptxSlide) = slide.shapes.filterIsInstance<PptxShape.Text>()
        fun text(shape: PptxShape.Text) = shape.paragraphs.joinToString("\n") { p -> p.runs.joinToString("") { it.text } }

        // Slide 1: the title placeholder has no position of its own; it comes from the layout.
        val title = texts(deck.slides[0]).first { text(it) == "Deck Title" }
        assertTrue("title box inherited: ${title.box}", title.box.width() > 0 && title.box.height() > 0)
        assertEquals("title size from the master's title style", 44f, title.paragraphs[0].runs[0].size)
        assertTrue(texts(deck.slides[0]).any { text(it) == "A subtitle" })

        // Slide 2: body text with levels.
        val body = texts(deck.slides[1]).first { text(it).contains("Point one") }
        assertEquals(listOf(0, 1), body.paragraphs.map { it.level })
        assertTrue("body text is smaller than titles", body.paragraphs[0].runs[0].size < 44f)

        // Slide 3: explicit formatting, a style-filled shape, a picture, a table and a group.
        val shapes = deck.slides[2].shapes
        val red = texts(deck.slides[2]).first { text(it) == "Red bold 32" }.paragraphs[0].runs[0]
        assertEquals(32f, red.size)
        assertTrue(red.bold)
        assertEquals(0xFFFF0000.toInt(), red.color)
        val rectangle = texts(deck.slides[2]).firstOrNull { it.fill != null }
        assertNotNull("rectangle filled from its shape style (theme accent)", rectangle)
        val picture = shapes.filterIsInstance<PptxShape.Picture>().single()
        assertTrue(picture.path, picture.path.startsWith("ppt/media/"))
        val table = shapes.filterIsInstance<PptxShape.Table>().single()
        val cells = table.rows.map { (_, row) -> row.map { (paragraphs, _) -> paragraphs.joinToString("") { p -> p.runs.joinToString("") { it.text } } } }
        assertEquals(listOf(listOf("A", "B"), listOf("C", "D")), cells)
        val grouped = texts(deck.slides[2]).first { text(it) == "In a group" }
        // 1 inch = 914400 EMU: the group keeps its child at (1", 5").
        assertEquals(914400f, grouped.box.left, 1f)
        assertEquals(5 * 914400f, grouped.box.top, 1f)
    }
}
