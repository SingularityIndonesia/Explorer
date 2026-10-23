package com.singularity_universe.explorer.data

import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File

enum class FileKind { Folder, Image, Video, Audio, Document, Archive, Apk, Other }

data class FileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val childCount: Int = 0,
    /** MediaStore content URI, when the file is indexed — used for cheap system thumbnails. */
    val mediaUri: Uri? = null,
) {
    val file: File get() = File(path)

    val parentName: String get() = File(path).parentFile?.name.orEmpty()

    val kind: FileKind
        get() = when {
            isDirectory -> FileKind.Folder
            mimeType.startsWith("image/") -> FileKind.Image
            mimeType.startsWith("video/") -> FileKind.Video
            mimeType.startsWith("audio/") -> FileKind.Audio
            mimeType == APK_MIME -> FileKind.Apk
            mimeType in ARCHIVE_MIMES -> FileKind.Archive
            mimeType.startsWith("text/") || mimeType in DOCUMENT_MIMES || mimeType in TEXT_MIMES -> FileKind.Document
            else -> FileKind.Other
        }
}

data class AppItem(
    val packageName: String,
    val label: String,
    val size: Long,
    val apkPath: String,
    val isSystem: Boolean,
)

data class StorageInfo(val total: Long, val free: Long) {
    val used: Long get() = total - free
}

enum class Category(val label: String) {
    Downloads("Downloads"),
    Images("Images"),
    Videos("Videos"),
    Audio("Audio"),
    Documents("Documents"),
    Apps("Apps"),
}

enum class SortOrder(val label: String) {
    NameAsc("Name (A to Z)"),
    NameDesc("Name (Z to A)"),
    DateDesc("Date (newest first)"),
    DateAsc("Date (oldest first)"),
    SizeDesc("Size (largest first)"),
    SizeAsc("Size (smallest first)"),
}

const val APK_MIME = "application/vnd.android.package-archive"

val ARCHIVE_MIMES = setOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/x-rar-compressed",
    "application/vnd.rar",
    "application/x-7z-compressed",
    "application/x-tar",
    "application/gzip",
)

val DOCUMENT_MIMES = setOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/rtf",
    "application/epub+zip",
    "application/json",
    "application/xml",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.oasis.opendocument.spreadsheet",
)

fun mimeTypeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return "application/octet-stream"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: if (ext in TEXT_EXTENSIONS) "text/plain" else "application/octet-stream"
}

/** [showHidden] only affects a folder's item count. */
fun File.toFileItem(showHidden: Boolean = true): FileItem {
    val dir = isDirectory
    return FileItem(
        path = absolutePath,
        name = name,
        isDirectory = dir,
        size = if (dir) 0L else length(),
        lastModified = lastModified(),
        mimeType = if (dir) "inode/directory" else mimeTypeOf(name),
        childCount = if (dir) list()?.count { showHidden || !it.startsWith(".") } ?: 0 else 0,
    )
}

fun List<FileItem>.sortedBy(order: SortOrder): List<FileItem> {
    val comparator: Comparator<FileItem> = when (order) {
        SortOrder.NameAsc -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortOrder.NameDesc -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortOrder.DateDesc -> compareByDescending { it.lastModified }
        SortOrder.DateAsc -> compareBy { it.lastModified }
        SortOrder.SizeDesc -> compareByDescending { it.size }
        SortOrder.SizeAsc -> compareBy { it.size }
    }
    return sortedWith(compareByDescending<FileItem> { it.isDirectory }.then(comparator))
}

/** Zip archives open in the app like folders. */
val FileItem.isZip: Boolean get() = !isDirectory && name.substringAfterLast('.', "").equals("zip", ignoreCase = true)

/** Images and videos can be shown in the in-app viewer. */
val FileItem.isPreviewable: Boolean get() = kind == FileKind.Image || kind == FileKind.Video
