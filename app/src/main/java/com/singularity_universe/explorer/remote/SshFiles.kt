package com.singularity_universe.explorer.remote

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.singularity_universe.explorer.data.CopyCancelledException
import com.singularity_universe.explorer.data.CopyProgress
import com.singularity_universe.explorer.data.FileItem
import com.singularity_universe.explorer.data.mimeTypeOf
import java.io.File
import java.io.IOException

/** A location on an SSH server: `ssh://<hostId><path>`. */
data class SshPath(val hostId: String, val path: String) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
    val parent: String get() = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
    fun child(name: String) = SshPath(hostId, path.trimEnd('/') + "/" + name)
    override fun toString() = "$SCHEME$hostId$path"

    companion object {
        const val SCHEME = "ssh://"

        fun parse(value: String): SshPath? {
            if (!value.startsWith(SCHEME)) return null
            val rest = value.removePrefix(SCHEME)
            val id = rest.substringBefore('/')
            val path = "/" + rest.substringAfter('/', "")
            return SshPath(id, path.trimEnd('/').ifEmpty { "/" })
        }
    }
}

/** File operations on SSH servers, over the shared sessions in [SshConnections]. */
class SshFiles(val connections: SshConnections) {

    suspend fun list(folder: String, showHidden: Boolean): List<FileItem> {
        val target = parse(folder)
        return connections.sftp(target.hostId) { sftp, _ ->
            sftp.ls(target.path).mapNotNull { entry ->
                val name = entry.filename
                if (name == "." || name == "..") return@mapNotNull null
                if (!showHidden && name.startsWith(".")) return@mapNotNull null
                val attrs = entry.attrs
                val isDirectory = attrs.isDir || (attrs.isLink && isDirectoryLink(sftp, target.child(name).path))
                FileItem(
                    path = target.child(name).toString(),
                    name = name,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0L else attrs.size,
                    lastModified = attrs.mTime * 1000L,
                    mimeType = if (isDirectory) "inode/directory" else mimeTypeOf(name),
                    childCount = RemoteFiles.UNKNOWN_COUNT,
                )
            }
        }
    }

    suspend fun createFolder(parent: String, name: String) {
        val target = parse(parent).child(name)
        connections.sftp(target.hostId) { sftp, _ ->
            if (exists(sftp, target.path)) throw IOException("\"$name\" already exists")
            sftp.mkdir(target.path)
        }
    }

    suspend fun rename(path: String, newName: String) {
        val source = parse(path)
        val target = SshPath(source.hostId, source.parent.trimEnd('/') + "/" + newName)
        connections.sftp(source.hostId) { sftp, _ ->
            if (exists(sftp, target.path)) throw IOException("\"$newName\" already exists")
            sftp.rename(source.path, target.path)
        }
    }

    suspend fun delete(paths: Collection<String>) {
        paths.map(::parse).groupBy { it.hostId }.forEach { (hostId, targets) ->
            connections.sftp(hostId) { sftp, _ -> targets.forEach { deleteRecursively(sftp, it.path) } }
        }
    }

    /** Copies or moves between folders on the same server. Returns the new path. */
    suspend fun transferWithin(source: String, destDir: String, move: Boolean): String {
        val from = parse(source)
        val dest = parse(destDir)
        val target = connections.sftp(dest.hostId) { sftp, _ -> uniqueTarget(sftp, dest, from.name) }
        if (move) {
            connections.sftp(from.hostId) { sftp, _ -> sftp.rename(from.path, target.path) }
        } else {
            // SFTP has no copy; let the server do it.
            val result = connections.exec(from.hostId, "cp -r -- ${shellQuote(from.path)} ${shellQuote(target.path)}")
            if (!result.ok) throw IOException(result.output.lineSequence().lastOrNull { it.isNotBlank() } ?: "Couldn't copy")
        }
        return target.toString()
    }

    /** Downloads a file or folder into [localDir], picking a free name. Returns the local copy. */
    suspend fun download(source: String, localDir: File, progress: CopyProgress? = null): File {
        val from = parse(source)
        var target = File(localDir, from.name)
        var i = 1
        while (target.exists()) target = File(localDir, numbered(from.name, i++))
        // Transfers use a channel of their own, so browsing the server isn't blocked meanwhile.
        connections.separateSftp(from.hostId) { sftp -> downloadRecursively(sftp, from.path, target, progress) }
        return target
    }

    /** Downloads a single file to exactly [target] (used to open files in other apps). */
    suspend fun downloadTo(source: String, target: File) {
        val from = parse(source)
        target.parentFile?.mkdirs()
        connections.separateSftp(from.hostId) { sftp -> sftp.get(from.path, target.path) }
    }

    /** Size and modification time of a file, to notice when it changed (or null if it's gone). */
    suspend fun stamp(path: String): String? {
        val at = parse(path)
        return connections.sftp(at.hostId) { sftp, _ -> stat(sftp, at.path)?.let { "${it.size}:${it.mTime}" } }
    }

    /** Saves a local file over [path] on the server (an edited copy going back). */
    suspend fun uploadTo(local: File, path: String) {
        val to = parse(path)
        connections.separateSftp(to.hostId) { sftp -> sftp.put(local.path, to.path, ChannelSftp.OVERWRITE) }
    }

    /** Uploads a local file or folder into [destDir] on the server. Returns the remote path. */
    suspend fun upload(local: File, destDir: String, progress: CopyProgress? = null): String {
        val dest = parse(destDir)
        return connections.separateSftp(dest.hostId) { sftp ->
            val target = uniqueTarget(sftp, dest, local.name)
            uploadRecursively(sftp, local, target.path, progress)
            target.toString()
        }
    }

    private fun parse(value: String) = SshPath.parse(value) ?: throw IOException("Not an SSH path: $value")

    private fun isDirectoryLink(sftp: ChannelSftp, path: String): Boolean =
        runCatching { sftp.stat(path).isDir }.getOrDefault(false)

    private fun stat(sftp: ChannelSftp, path: String): SftpATTRS? = try {
        sftp.lstat(path)
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e
    }

    private fun exists(sftp: ChannelSftp, path: String) = stat(sftp, path) != null

    private fun uniqueTarget(sftp: ChannelSftp, dir: SshPath, name: String): SshPath {
        var candidate = dir.child(name)
        var i = 1
        while (exists(sftp, candidate.path)) candidate = dir.child(numbered(name, i++))
        return candidate
    }

    private fun numbered(name: String, i: Int): String {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        return "$base ($i)$ext"
    }

    private fun deleteRecursively(sftp: ChannelSftp, path: String) {
        val attrs = stat(sftp, path) ?: return
        if (attrs.isDir) {
            sftp.ls(path).forEach { entry ->
                if (entry.filename != "." && entry.filename != "..") deleteRecursively(sftp, "$path/${entry.filename}")
            }
            sftp.rmdir(path)
        } else {
            sftp.rm(path)
        }
    }

    private fun downloadRecursively(sftp: ChannelSftp, path: String, target: File, progress: CopyProgress?) {
        if (sftp.stat(path).isDir) {
            target.mkdirs()
            sftp.ls(path).forEach { entry ->
                if (entry.filename != "." && entry.filename != "..") {
                    downloadRecursively(sftp, "$path/${entry.filename}", File(target, entry.filename), progress)
                }
            }
        } else {
            progress?.check()
            target.parentFile?.mkdirs()
            sftp.get(path, target.path, monitor(progress))
            if (progress?.cancelled == true) {
                target.delete()
                throw CopyCancelledException()
            }
        }
    }

    private fun uploadRecursively(sftp: ChannelSftp, local: File, path: String, progress: CopyProgress?) {
        if (local.isDirectory) {
            sftp.mkdir(path)
            local.listFiles().orEmpty().forEach { uploadRecursively(sftp, it, "$path/${it.name}", progress) }
        } else {
            progress?.check()
            sftp.put(local.path, path, monitor(progress), ChannelSftp.OVERWRITE)
            if (progress?.cancelled == true) {
                runCatching { sftp.rm(path) }
                throw CopyCancelledException()
            }
        }
    }

    /** Reports transferred bytes; returning false makes JSch stop in the middle of the file. */
    private fun monitor(progress: CopyProgress?): SftpProgressMonitor? = progress?.let {
        object : SftpProgressMonitor {
            override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
            override fun count(count: Long): Boolean {
                it.add(count)
                return !it.cancelled
            }
            override fun end() = Unit
        }
    }
}
