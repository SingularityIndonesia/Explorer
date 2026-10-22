package com.singularity_universe.explorer.remote

import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.data.mimeTypeOf
import java.io.File
import java.io.IOException

/**
 * File operations for folders Android hides from apps (Android/data, Android/obb), performed as
 * the `shell` user over [RemoteConnection].
 */
class RemoteFiles(private val connection: RemoteConnection) {

    val isConnected: Boolean get() = connection.isConnected

    suspend fun list(path: String, showHidden: Boolean): List<FileItem> {
        // One line per entry: type|size|mtime|full path (the path goes last, so it may contain '|').
        val result = connection.exec(listCommand(path))
        if (!result.ok && result.output.isBlank()) throw IOException("Couldn't open this folder")
        return parseStatListing(result.output, showHidden) { it }
    }

    suspend fun createFolder(path: String) =
        check(connection.exec("mkdir -- ${shellQuote(path)}"), "Couldn't create folder")

    suspend fun rename(path: String, target: String) {
        if (exists(target)) throw IOException("\"${File(target).name}\" already exists")
        check(connection.exec("mv -- ${shellQuote(path)} ${shellQuote(target)}"), "Couldn't rename")
    }

    suspend fun delete(paths: Collection<String>) {
        if (paths.isEmpty()) return
        check(connection.exec("rm -rf -- ${paths.joinToString(" ") { shellQuote(it) }}"), "Couldn't delete")
    }

    /** Copies or moves [path] into [destDir], picking "name (1)" style names on conflicts. */
    suspend fun transfer(path: String, destDir: String, move: Boolean): String {
        val target = uniqueTarget(destDir, File(path).name)
        val command = if (move) "mv" else "cp -r"
        check(
            connection.exec("$command -- ${shellQuote(path)} ${shellQuote(target)}"),
            "Couldn't ${if (move) "move" else "copy"} ${File(path).name}",
        )
        return target
    }

    /** Copies a hidden file into app storage so other apps can open it. */
    suspend fun pull(path: String, destination: File) = connection.pull(path, destination)

    private suspend fun exists(path: String): Boolean =
        connection.exec("[ -e ${shellQuote(path)} ] && echo yes || echo no").output.trim() == "yes"

    private suspend fun uniqueTarget(dir: String, name: String): String {
        var candidate = "$dir/$name"
        if (!exists(candidate)) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 1
        while (exists(candidate)) candidate = "$dir/$base ($i)$ext".also { i++ }
        return candidate
    }

    private fun check(result: ShellResult, message: String) {
        if (!result.ok) throw IOException(result.output.lineSequence().lastOrNull { it.isNotBlank() } ?: message)
    }

    companion object {
        /** Folder item counts aren't fetched remotely (it would cost one command per folder). */
        const val UNKNOWN_COUNT = -1

        private val restricted = Regex("/Android/(data|obb)(/|$)")

        /** Folders Android blocks for every normal app, even with "All files access". */
        fun isRestricted(path: String): Boolean = restricted.containsMatchIn(path)

        /** The `find`/`stat` command that lists a folder, one `type|size|mtime|path` line per entry. */
        fun listCommand(path: String, followLinks: Boolean = false) =
            "find ${shellQuote(path)} -mindepth 1 -maxdepth 1 -exec stat ${if (followLinks) "-L " else ""}-c '%F|%s|%Y|%n' {} +"

        /** Parses [listCommand] output; [pathOf] turns a device path into the app's path for it. */
        fun parseStatListing(output: String, showHidden: Boolean, pathOf: (String) -> String): List<FileItem> =
            output.lineSequence()
                .mapNotNull { line ->
                    // type|size|mtime|full path — the path goes last, so it may contain '|'.
                    val parts = line.split('|', limit = 4)
                    if (parts.size < 4 || !parts[3].startsWith("/")) return@mapNotNull null
                    val fullPath = parts[3]
                    val name = fullPath.substringAfterLast('/')
                    val isDirectory = parts[0] == "directory"
                    FileItem(
                        path = pathOf(fullPath),
                        name = name,
                        isDirectory = isDirectory,
                        size = if (isDirectory) 0L else parts[1].toLongOrNull() ?: 0L,
                        lastModified = (parts[2].toLongOrNull() ?: 0L) * 1000,
                        mimeType = if (isDirectory) "inode/directory" else mimeTypeOf(name),
                        childCount = UNKNOWN_COUNT,
                    )
                }
                .filter { showHidden || !it.name.startsWith(".") }
                .toList()
    }
}
