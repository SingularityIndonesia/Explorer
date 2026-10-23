package com.singularity_universe.explorer.data.docs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Reading Office Open XML files (.docx, .pptx, .xlsx): zip archives of XML "parts" that point at
 * each other through relationship files. Sizes are capped, so a malicious file (a zip bomb) can't
 * exhaust memory.
 */
internal object Ooxml {
    const val NS_RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val MAX_PART_BYTES = 64L * 1024 * 1024

    private val factory: XmlPullParserFactory by lazy {
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
    }

    fun read(zip: ZipFile, path: String, limit: Long = MAX_PART_BYTES): ByteArray? {
        val entry = zip.getEntry(path) ?: return null
        zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw IOException("Part $path is too large")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    /** A namespace-aware parser over [path]; element names are then local names ("p", not "w:p"). */
    fun parser(zip: ZipFile, path: String): XmlPullParser? {
        val bytes = read(zip, path) ?: return null
        // The standard factory: Android's built-in parser on the phone, kxml2 in unit tests.
        return factory.newPullParser().apply { setInput(ByteArrayInputStream(bytes), null) }
    }

    /** Relationship id → target part path, for the part at [partPath]. */
    fun relationships(zip: ZipFile, partPath: String): Map<String, String> {
        val dir = partPath.substringBeforeLast('/', "")
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + partPath.substringAfterLast('/') + ".rels"
        val parser = parser(zip, relsPath) ?: return emptyMap()
        val result = HashMap<String, String>()
        parser.forEachStart { name ->
            if (name == "Relationship" && parser.getAttributeValue(null, "TargetMode") != "External") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) result[id] = resolve(dir, target)
            }
        }
        return result
    }

    /** Resolves a relationship target against the folder of the part that refers to it. */
    fun resolve(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = (if (baseDir.isEmpty()) emptyList() else baseDir.split('/')).toMutableList()
        for (segment in target.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    /** A picture inside the document, decoded no wider than [maxWidth] pixels. */
    fun image(zip: ZipFile, path: String, maxWidth: Int): Bitmap? = runCatching {
        val bytes = read(zip, path, limit = 32L * 1024 * 1024) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

/** Calls [onStart] with each start tag's local name, through the whole document. */
internal inline fun XmlPullParser.forEachStart(onStart: (String) -> Unit) {
    while (true) {
        when (next()) {
            XmlPullParser.START_TAG -> onStart(name)
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

/**
 * Walks the children of the current element (the parser is on its start tag) and calls [onChild]
 * for each child start tag; [onChild] may read the child fully or leave it to be skipped.
 * Text inside the element goes to [onText].
 */
internal inline fun XmlPullParser.children(onText: (String) -> Unit = {}, onChild: (String) -> Unit) {
    val depth = depth
    while (true) {
        when (next()) {
            XmlPullParser.START_TAG -> {
                val childDepth = this.depth
                onChild(name)
                // Skip whatever the handler didn't read of this child.
                while (!(eventType == XmlPullParser.END_TAG && this.depth == childDepth)) {
                    if (next() == XmlPullParser.END_DOCUMENT) return
                }
            }
            XmlPullParser.TEXT -> onText(text)
            XmlPullParser.END_TAG -> if (this.depth == depth) return
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

/** An attribute by local name, whatever its namespace prefix. */
internal fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) if (getAttributeName(i) == name) return getAttributeValue(i)
    return null
}

/** "On" unless the element says val="0"/"false"/"off" (how Office writes <w:b/> and <w:b w:val="0"/>). */
internal fun XmlPullParser.flag(): Boolean = attr("val")?.lowercase() !in setOf("0", "false", "off", "none")
