package com.attey.governor.core

/**
 * One sysfs node, with its capabilities discovered rather than assumed.
 *
 * Every tunable in this app is a SysNode. Nothing is hardcoded to a device: if a
 * node is absent the UI greys it out instead of the app crashing, which is the
 * whole reason this is portable.
 */
data class SysNode(
    val path: String,
    val exists: Boolean = false,
    val writable: Boolean = false,
    val value: String = "",
) {
    val isUsable: Boolean get() = exists && writable

    companion object {
        /** Probes [paths] in one round trip, returning a node for each. */
        fun probe(shell: RootShell, paths: List<String>): Map<String, SysNode> {
            if (paths.isEmpty()) return emptyMap()
            val script = paths.joinToString("\n") { p ->
                "printf '%s\\t%s\\t%s\\t%s\\n' '$p' " +
                    "\"\$([ -e '$p' ] && echo 1 || echo 0)\" " +
                    "\"\$([ -w '$p' ] && echo 1 || echo 0)\" " +
                    "\"\$(cat '$p' 2>/dev/null | head -c 4096 | tr '\\n' ' ')\""
            }
            val out = shell.exec(script)
            val map = HashMap<String, SysNode>(paths.size)
            for (line in out.lineSequence()) {
                val f = line.split('\t')
                if (f.size < 3) continue
                map[f[0]] = SysNode(
                    path = f[0],
                    exists = f[1] == "1",
                    writable = f[2] == "1",
                    value = if (f.size > 3) f[3].trim() else "",
                )
            }
            return map
        }
    }
}

/** Result of attempting a write. Never throws -- sysfs rejects values routinely. */
sealed interface WriteResult {
    data object Ok : WriteResult
    data class Rejected(val wanted: String, val actual: String) : WriteResult
    data class Refused(val reason: String) : WriteResult
}

object Writer {
    /**
     * Paths this app will never write, regardless of what the UI asks for.
     *
     * Thermal cooling devices are a hard no. On the development device the
     * userspace thermal governor re-parks any change within 15 seconds, and the
     * same phone was measured at 82C junction under load. Fighting a working
     * closed-loop thermal governor is both futile and a burn risk, so the option
     * does not exist rather than being merely discouraged.
     */
    private val DENY = listOf(
        Regex("^/sys/class/thermal/cooling_device\\d+/cur_state$"),
        Regex("^/sys/class/thermal/thermal_zone\\d+/"),
        Regex("^/sys/class/thermal/thermal_message/"),
    )

    fun isDenied(path: String): Boolean = DENY.any { it.containsMatchIn(path) }

    /**
     * Writes [value] to [path] and reads it back. sysfs frequently accepts a
     * write and then stores something else -- a clamped frequency, or nothing at
     * all -- so the read-back is the only honest confirmation.
     */
    fun write(shell: RootShell, path: String, value: String): WriteResult {
        if (isDenied(path)) return WriteResult.Refused("thermal nodes are not writable by this app")
        // No trailing whitespace: at least one kernel interface (cpu_boost's
        // input_boost_freq) rejects a value written with a trailing space and
        // silently keeps the old one.
        val v = value.trim()
        shell.exec("printf '%s' '$v' > '$path' 2>/dev/null")
        val back = shell.exec("cat '$path' 2>/dev/null").trim()
        return if (back == v || back.split(Regex("\\s+")).contains(v)) WriteResult.Ok
        else WriteResult.Rejected(v, back)
    }
}
