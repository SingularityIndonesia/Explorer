package com.singularity_universe.explorer.remote

import com.singularity_universe.explorer.data.FileItem
import java.io.File
import java.io.IOException

/** A location on another Android device: `adb://<deviceId><path>`. */
data class AdbPath(val deviceId: String, val path: String) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
    val parent: String get() = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
    fun child(name: String) = AdbPath(deviceId, path.trimEnd('/') + "/" + name)
    override fun toString() = "$SCHEME$deviceId$path"

    companion object {
        const val SCHEME = "adb://"

        /** Where browsing a device starts: its internal storage (`/sdcard` is only a symlink to it). */
        const val START = "/storage/emulated/0"

        fun parse(value: String): AdbPath? {
            if (!value.startsWith(SCHEME)) return null
            val rest = value.removePrefix(SCHEME)
            val id = rest.substringBefore('/')
            val path = "/" + rest.substringAfter('/', "")
            return AdbPath(id, path.trimEnd('/').ifEmpty { "/" })
        }
    }
}

/** File operations on other Android devices, as their `shell` user over [AdbDevices]. */
class AdbFiles(val devices: AdbDevices) {

    suspend fun list(folder: String, showHidden: Boolean): List<FileItem> {
        val target = parse(folder)
        // Follow links so folders like /sdcard show up as folders.
        val result = devices.exec(target.deviceId, RemoteFiles.listCommand(target.path, followLinks = true))
        if (!result.ok && result.output.isBlank()) throw IOException("Couldn't open this folder")
        return RemoteFiles.parseStatListing(result.output, showHidden) { AdbPath(target.deviceId, it).toString() }
    }

    suspend fun createFolder(parent: String, name: String) {
        val target = parse(parent).child(name)
        if (exists(target)) throw IOException("\"$name\" already exists")
        run(target.deviceId, "mkdir -- ${shellQuote(target.path)}", "Couldn't create folder")
    }

    suspend fun rename(path: String, newName: String) {
        val source = parse(path)
        val target = AdbPath(source.deviceId, source.parent.trimEnd('/') + "/" + newName)
        if (exists(target)) throw IOException("\"$newName\" already exists")
        run(source.deviceId, "mv -- ${shellQuote(source.path)} ${shellQuote(target.path)}", "Couldn't rename")
    }

    suspend fun delete(paths: Collection<String>) {
        paths.map(::parse).groupBy { it.deviceId }.forEach { (id, targets) ->
            run(id, "rm -rf -- ${targets.joinToString(" ") { shellQuote(it.path) }}", "Couldn't delete")
        }
    }

    /** Copies or moves between folders on the same device. Returns the new path. */
    suspend fun transferWithin(source: String, destDir: String, move: Boolean): String {
        val from = parse(source)
        val target = uniqueTarget(parse(destDir), from.name)
        val command = if (move) "mv" else "cp -r"
        run(from.deviceId, "$command -- ${shellQuote(from.path)} ${shellQuote(target.path)}", "Couldn't copy ${from.name}")
        return target.toString()
    }

    /** Downloads a file or folder into [localDir], picking a free name. Returns the local copy. */
    suspend fun download(source: String, localDir: File): File {
        val from = parse(source)
        var target = File(localDir, from.name)
        var i = 1
        while (target.exists()) target = File(localDir, numbered(from.name, i++))
        downloadRecursively(from, target)
        return target
    }

    /** Downloads one file to exactly [target] (used to open files in other apps). */
    suspend fun downloadTo(source: String, target: File) {
        val from = parse(source)
        devices.pull(from.deviceId, from.path, target)
    }

    /** Uploads a local file or folder into [destDir] on the device. Returns the remote path. */
    suspend fun upload(local: File, destDir: String): String {
        val target = uniqueTarget(parse(destDir), local.name)
        uploadRecursively(local, target)
        return target.toString()
    }

    private suspend fun downloadRecursively(from: AdbPath, target: File) {
        if (isDirectory(from)) {
            target.mkdirs()
            list(from.toString(), showHidden = true).forEach { child ->
                downloadRecursively(parse(child.path), File(target, child.name))
            }
        } else {
            devices.pull(from.deviceId, from.path, target)
        }
    }

    private suspend fun uploadRecursively(local: File, target: AdbPath) {
        if (local.isDirectory) {
            run(target.deviceId, "mkdir -p -- ${shellQuote(target.path)}", "Couldn't create ${local.name}")
            local.listFiles().orEmpty().forEach { uploadRecursively(it, target.child(it.name)) }
        } else {
            devices.push(target.deviceId, local, target.path)
        }
    }

    private suspend fun isDirectory(path: AdbPath): Boolean =
        devices.exec(path.deviceId, "[ -d ${shellQuote(path.path)} ] && echo yes || echo no").output.trim() == "yes"

    private suspend fun exists(path: AdbPath): Boolean =
        devices.exec(path.deviceId, "[ -e ${shellQuote(path.path)} ] && echo yes || echo no").output.trim() == "yes"

    private suspend fun uniqueTarget(dir: AdbPath, name: String): AdbPath {
        var candidate = dir.child(name)
        var i = 1
        while (exists(candidate)) candidate = dir.child(numbered(name, i++))
        return candidate
    }

    private fun numbered(name: String, i: Int): String {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        return "$base ($i)$ext"
    }

    private suspend fun run(id: String, command: String, message: String) {
        val result = devices.exec(id, command)
        if (!result.ok) throw IOException(result.output.lineSequence().lastOrNull { it.isNotBlank() } ?: message)
    }

    private fun parse(value: String) = AdbPath.parse(value) ?: throw IOException("Not a device path: $value")
}
