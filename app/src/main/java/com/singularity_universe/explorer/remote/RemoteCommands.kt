package com.singularity_universe.explorer.remote

/** Shell commands run on servers and devices (POSIX sh, zsh and Android's toybox sh). */
object RemoteCommands {
    /**
     * Compresses [names] (files and folders in [folder]) into an archive named [base] in that folder,
     * picking a free name ("base (1).zip"...). Uses zip when [allowZip] and it's installed, otherwise
     * tar.gz. Prints "CREATED:<file name>" on success.
     */
    fun compress(folder: String, base: String, names: List<String>, allowZip: Boolean): String {
        // "./name": names starting with "-" aren't taken for options.
        val items = names.joinToString(" ") { shellQuote("./$it") }
        fun archive(ext: String, command: String) =
            "n=\"\$b.$ext\"; i=1; while [ -e \"\$n\" ]; do n=\"\$b (\$i).$ext\"; i=\$((i+1)); done; " +
                "$command \"\$n\" $items && echo \"CREATED:\$n\""
        val tar = archive("tar.gz", "tar czf")
        val body = if (allowZip) "if command -v zip >/dev/null 2>&1; then ${archive("zip", "zip -r -q")}; else $tar; fi" else tar
        return "cd ${shellQuote(folder)} && b=${shellQuote(base)}; $body"
    }

    /** The file name a [compress] command reports, or null if it failed. */
    fun created(output: String): String? = output.lineSequence().lastOrNull { it.startsWith("CREATED:") }?.removePrefix("CREATED:")

    /**
     * A content checksum for [path], from whichever hash tool is installed. It's only ever compared
     * against this same host's own past output, so it doesn't matter which tool ran or what format
     * its digest is in — only that the same one keeps being picked for as long as it's installed.
     */
    fun checksum(path: String): String {
        val q = shellQuote(path)
        return "sha256sum -- $q 2>/dev/null || shasum -a 256 -- $q 2>/dev/null || md5sum -- $q 2>/dev/null || cksum -- $q"
    }

    /** The digest out of a [checksum] command's output (its first field). */
    fun parseChecksum(output: String): String? = output.trim().substringBefore(' ').takeIf { it.isNotEmpty() }
}
