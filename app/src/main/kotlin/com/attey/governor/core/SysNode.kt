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
                "printf '%s\\t%s\\t%s\\n' '$p' " +
                    "\"\$(stat -c %a '$p' 2>/dev/null)\" " +
                    "\"\$(cat '$p' 2>/dev/null | head -c 4096 | tr '\\n' ' ')\""
            }
            val out = shell.exec(script)
            val map = HashMap<String, SysNode>(paths.size)
            for (line in out.lineSequence()) {
                val f = line.split('\t')
                if (f.size < 2) continue
                val mode = f[1].trim()
                map[f[0]] = SysNode(
                    path = f[0],
                    exists = mode.isNotEmpty(),
                    writable = ownerWritable(mode),
                    value = if (f.size > 2) f[2].trim() else "",
                )
            }
            return map
        }

        /**
         * Writability from the mode bits, not from `[ -w ]`.
         *
         * `[ -w ]` calls access(2), which consults SELinux. On the development
         * device every cpufreq node failed that test while writes to them
         * demonstrably worked -- scaling_max_freq is system:system 0664 and the
         * shell's domain is denied by policy, yet the write goes through. Trusting
         * access(2) greys out every control in the app on a device where all of
         * them work, which is the worst kind of wrong: quiet and plausible.
         */
        private fun ownerWritable(mode: String): Boolean {
            val m = mode.trim()
            if (m.length < 3) return false
            val owner = m[m.length - 3].digitToIntOrNull() ?: return false
            return owner and 2 != 0
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
