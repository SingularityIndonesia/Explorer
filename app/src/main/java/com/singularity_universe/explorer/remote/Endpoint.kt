package com.singularity_universe.explorer.remote

/**
 * One way to reach a server or device: a host (name or IP), optionally with its own port when
 * that route needs a different one (e.g. a router port-forward). Written as `host`, `host:port`
 * or `[ipv6]:port`.
 */
data class Endpoint(val host: String, val port: Int? = null) {
    override fun toString(): String {
        val shown = if (':' in host) "[$host]" else host
        return if (port != null) "$shown:$port" else shown
    }

    companion object {
        fun parse(text: String): Endpoint? {
            val value = text.trim()
            if (value.isEmpty()) return null
            if (value.startsWith("[")) {
                // [ipv6] or [ipv6]:port
                val host = value.substringAfter('[').substringBefore(']')
                val port = value.substringAfter("]:", "").toIntOrNull()
                return Endpoint(host, port?.takeIf { it in 1..65535 })
            }
            if (value.count { it == ':' } == 1) {
                val port = value.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                return Endpoint(value.substringBefore(':'), port)
            }
            // A bare host, or an IPv6 address without brackets (no port then).
            return Endpoint(value)
        }

        /** Parses the addresses typed one per line in a dialog. */
        fun parseLines(text: String): List<String> =
            text.lines().mapNotNull { parse(it)?.toString() }.distinct()
    }
}
