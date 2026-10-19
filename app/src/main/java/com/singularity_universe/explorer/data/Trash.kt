package com.singularity_universe.explorer.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class TrashItem(
    val id: String,
    val name: String,
    val originalPath: String,
    val deletedAt: Long,
    val size: Long,
    val isDirectory: Boolean,
)

/**
 * Deleted files on this phone go here first, so they can be restored. Items live in a hidden
 * `.Explorer-Trash` folder on the same storage (moving is an instant rename, even for big
 * files), under random ids so names never clash; an index keeps where each came from.
 * Items older than [RETENTION_DAYS] are removed for good by [purgeExpired].
 */
class Trash(root: File) {
    val dir = File(root, ".Explorer-Trash")
    private val index = File(dir, ".index.json")

    @Synchronized
    fun items(): List<TrashItem> {
        // Entries whose file vanished (e.g. removed by another app) are dropped.
        val present = load().filter { File(dir, it.id).exists() }
        return present.sortedByDescending { it.deletedAt }
    }

    fun fileOf(item: TrashItem) = File(dir, item.id)

    /** Moves local files into the trash. Returns the paths that were moved (for rescanning). */
    @Synchronized
    fun moveIn(paths: Collection<String>): List<String> {
        ensureDir()
        val entries = load().toMutableList()
        val moved = mutableListOf<String>()
        try {
            for (path in paths) {
                val source = File(path)
                if (!source.exists()) continue
                val id = UUID.randomUUID().toString()
                val target = File(dir, id)
                val size = if (source.isDirectory) source.walkTopDown().filter { it.isFile }.sumOf { it.length() } else source.length()
                if (!source.renameTo(target)) {
                    if (!source.copyRecursively(target)) throw IOException("Couldn't move ${source.name} to trash")
                    source.deleteRecursively()
                }
                entries += TrashItem(id, source.name, source.path, System.currentTimeMillis(), size, target.isDirectory)
                moved += path
            }
        } finally {
            save(entries)
        }
        return moved
    }

    /** Puts items back where they were (renamed "name (1)" if that name is taken). Returns restored paths. */
    @Synchronized
    fun restore(ids: Collection<String>): List<String> {
        val entries = load().toMutableList()
        val restored = mutableListOf<String>()
        try {
            for (item in entries.filter { it.id in ids }) {
                val source = File(dir, item.id)
                val original = File(item.originalPath)
                original.parentFile?.mkdirs()
                val target = uniqueTarget(original)
                if (!source.renameTo(target)) {
                    if (!source.copyRecursively(target)) throw IOException("Couldn't restore ${item.name}")
                    source.deleteRecursively()
                }
                entries.remove(item)
                restored += target.path
            }
        } finally {
            save(entries)
        }
        return restored
    }

    @Synchronized
    fun deleteForever(ids: Collection<String>) {
        val entries = load().toMutableList()
        entries.filter { it.id in ids }.forEach { item ->
            File(dir, item.id).deleteRecursively()
            entries.remove(item)
        }
        save(entries)
    }

    @Synchronized
    fun empty() = deleteForever(load().map { it.id })

    @Synchronized
    fun purgeExpired(now: Long = System.currentTimeMillis()) {
        val cutoff = now - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val expired = load().filter { it.deletedAt < cutoff }.map { it.id }
        if (expired.isNotEmpty()) deleteForever(expired)
    }

    fun isInside(path: String): Boolean = path == dir.path || path.startsWith(dir.path + "/")

    private fun ensureDir() {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Couldn't create the trash folder")
        // Keeps gallery apps from showing trashed photos and videos.
        File(dir, ".nomedia").takeIf { !it.exists() }?.createNewFile()
    }

    private fun uniqueTarget(file: File): File {
        if (!file.exists()) return file
        val name = file.name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 1
        var candidate: File
        do {
            candidate = File(file.parentFile, "$base ($i)$ext")
            i++
        } while (candidate.exists())
        return candidate
    }

    private fun load(): List<TrashItem> = runCatching {
        val array = JSONArray(index.readText())
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            TrashItem(
                id = o.getString("id"),
                name = o.getString("name"),
                originalPath = o.getString("originalPath"),
                deletedAt = o.getLong("deletedAt"),
                size = o.optLong("size"),
                isDirectory = o.optBoolean("isDirectory"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(entries: List<TrashItem>) {
        ensureDir()
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("originalPath", it.originalPath)
                    .put("deletedAt", it.deletedAt)
                    .put("size", it.size)
                    .put("isDirectory", it.isDirectory)
            )
        }
        index.writeText(array.toString())
    }

    companion object {
        const val RETENTION_DAYS = 30L
    }
}
