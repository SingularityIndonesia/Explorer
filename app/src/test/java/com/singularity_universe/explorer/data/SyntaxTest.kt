package com.singularity_universe.explorer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SyntaxTest {
    /** Each colored piece of [text] with its kind, in order. */
    private fun tokens(text: String, language: Language): List<Pair<String, TokenKind>> {
        val spans = highlight(text, language)
        return (0 until spans.size).map { text.substring(spans.start(it), spans.end(it)) to spans.kind(it) }
    }

    @Test
    fun kotlin() {
        val code = """
            @Composable
            fun greet(name: String) = "Hi ${'$'}name" // say hi
            val n = 42 /* the answer */
        """.trimIndent()
        assertEquals(
            listOf(
                "@Composable" to TokenKind.Meta,
                "fun" to TokenKind.Keyword,
                "greet" to TokenKind.Function,
                "String" to TokenKind.Type,
                "\"Hi " to TokenKind.String,
                "\$name" to TokenKind.Variable,
                "\"" to TokenKind.String,
                "// say hi" to TokenKind.Comment,
                "val" to TokenKind.Keyword,
                "42" to TokenKind.Number,
                "/* the answer */" to TokenKind.Comment,
            ),
            tokens(code, Languages.Kotlin),
        )
    }

    @Test
    fun shellCommentsNeedAWordBoundary() {
        val code = "echo \"\${#items} of \$HOME\" # done\nx=a#b"
        assertEquals(
            listOf(
                "echo" to TokenKind.Function,
                "\"" to TokenKind.String,
                "\${#items}" to TokenKind.Variable,
                " of " to TokenKind.String,
                "\$HOME" to TokenKind.Variable,
                "\"" to TokenKind.String,
                "# done" to TokenKind.Comment,
            ),
            tokens(code, Languages.Shell),
        )
    }

    @Test
    fun shellSingleQuotesDontEscape() {
        assertEquals(
            listOf("'a\\'" to TokenKind.String, "'b'" to TokenKind.String),
            tokens("'a\\' 'b'", Languages.Shell),
        )
    }

    @Test
    fun jsonKeysAndValues() {
        assertEquals(
            listOf(
                "\"name\"" to TokenKind.Key,
                "\"x\"" to TokenKind.String,
                "\"n\"" to TokenKind.Key,
                "1.5e3" to TokenKind.Number,
                "\"ok\"" to TokenKind.Key,
                "true" to TokenKind.Constant,
            ),
            tokens("{\"name\": \"x\", \"n\": -1.5e3, \"ok\" : true}", Languages.Json),
        )
    }

    @Test
    fun yamlKeysWithoutApostropheStrings() {
        val code = "# config\nname: Don't panic\nlist:\n  - id: 7\nurl: http://x"
        assertEquals(
            listOf(
                "# config" to TokenKind.Comment,
                "name" to TokenKind.Key,
                "list" to TokenKind.Key,
                "id" to TokenKind.Key,
                "7" to TokenKind.Number,
                "url" to TokenKind.Key,
            ),
            tokens(code, Languages.Yaml),
        )
    }

    @Test
    fun cPreprocessor() {
        assertEquals(
            listOf("#include" to TokenKind.Meta, "<stdio.h>" to TokenKind.String, "int" to TokenKind.Keyword, "main" to TokenKind.Function),
            tokens("#include <stdio.h>\nint main()", Languages.C),
        )
    }

    @Test
    fun rustAttributesAndMacros() {
        assertEquals(
            listOf("#[derive(Debug)]" to TokenKind.Meta, "println!" to TokenKind.Function, "\"hi\"" to TokenKind.String),
            tokens("#[derive(Debug)]\nprintln!(\"hi\")", Languages.Rust),
        )
    }

    @Test
    fun markup() {
        assertEquals(
            listOf(
                "<!-- c -->" to TokenKind.Comment,
                "<a" to TokenKind.Tag,
                "href" to TokenKind.Attribute,
                "\"x\"" to TokenKind.String,
                ">" to TokenKind.Tag,
                "&amp;" to TokenKind.Constant,
                "</a>" to TokenKind.Tag,
            ),
            tokens("<!-- c --><a href=\"x\">&amp;</a>", Languages.Markup),
        )
    }

    @Test
    fun markdown() {
        assertEquals(
            listOf(
                "# Title" to TokenKind.Heading,
                "-" to TokenKind.Keyword,
                "`code`" to TokenKind.String,
                "[link]" to TokenKind.Function,
                "(u)" to TokenKind.String,
                "```" to TokenKind.String,
                "x = 1" to TokenKind.String,
                "```" to TokenKind.String,
            ),
            tokens("# Title\n- `code` and [link](u)\n```\nx = 1\n```", Languages.Markdown),
        )
    }

    @Test
    fun batchIsCaseInsensitive() {
        assertEquals(
            listOf("@echo" to TokenKind.Meta, "REM note" to TokenKind.Comment, "SET" to TokenKind.Keyword, "%PATH%" to TokenKind.Variable),
            tokens("@echo off\nREM note\nSET X=%PATH%", Languages.Batch),
        )
    }

    @Test
    fun unterminatedThingsEndAtTheText() {
        assertEquals(listOf("/* open" to TokenKind.Comment), tokens("/* open", Languages.Java))
        assertEquals(listOf("\"open" to TokenKind.String), tokens("\"open\n", Languages.Java))
    }

    @Test
    fun languageFromNameOrShebang() {
        assertSame(Languages.Kotlin, Languages.forFile("Main.kt"))
        assertSame(Languages.Makefile, Languages.forFile("Makefile"))
        assertSame(Languages.Shell, Languages.forFile(".bashrc"))
        assertSame(Languages.Python, Languages.forFile("run", "#!/usr/bin/env python3"))
        assertSame(Languages.Shell, Languages.forFile("deploy", "#!/bin/bash -e"))
        assertNull(Languages.forFile("notes.txt"))
        assertNull(Languages.forFile("README"))
    }

    @Test
    fun spansFindTheirOffset() {
        val spans = highlight("val a = 1 // x", Languages.Kotlin)
        assertEquals(0, spans.indexAfter(0))
        assertEquals(1, spans.indexAfter(3))
        assertEquals(2, spans.indexAfter(9))
        assertEquals(spans.size, spans.indexAfter(100))
    }
}
