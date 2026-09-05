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
            val modes = modes(shell, paths)
            val values = shell.readAll(paths)
            return paths.associateWith { p ->
                val mode = modes[p].orEmpty()
                SysNode(
                    path = p,
                    exists = mode.isNotEmpty(),
                    writable = ownerWritable(mode),
                    value = values[p].orEmpty(),
                )
            }
        }

        /** Returns path -> mode for many nodes with one `stat` process. */
        internal fun modes(shell: RootShell, paths: List<String>): Map<String, String> {
            if (paths.isEmpty()) return emptyMap()
            // One stat for every path, not one stat per path. `stat` is a real
            // binary, so each invocation is a fork: 43 paths cost 784 ms one at a
            // time and 29 ms batched, on the development device.
            val modes = HashMap<String, String>(paths.size)
            val statOut = shell.exec(
                "stat -c '%n %a' ${paths.joinToString(" ") { shellQuote(it) }} 2>/dev/null"
            )
            for (line in statOut.lineSequence()) {
                val i = line.lastIndexOf(' ')
                if (i <= 0) continue
                modes[line.substring(0, i)] = line.substring(i + 1).trim()
            }
            return modes
        }

        internal fun writable(mode: String?): Boolean = ownerWritable(mode.orEmpty())

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

    private fun isDenied(path: String): Boolean = DENY.any { it.containsMatchIn(path) }

    /** The only kernel surfaces Governor is allowed to change. */
    private val ALLOW = listOf(
        Regex("^/sys/devices/system/cpu/cpufreq/policy\\d+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?$"),
        Regex("^/sys/devices/system/cpu/cpu\\d+/online$"),
        Regex("^/sys/devices/system/cpu/cpu_boost/[A-Za-z0-9_.-]+$"),
        Regex("^/sys/module/cpu_boost/parameters/[A-Za-z0-9_.-]+$"),
        Regex("^/sys/class/kgsl/kgsl-3d0/devfreq/[A-Za-z0-9_.-]+$"),
        Regex("^/sys/class/devfreq/[A-Za-z0-9_.:@,+-]+/[A-Za-z0-9_.-]+$"),
        Regex("^/sys/block/[A-Za-z0-9_.-]+/queue/[A-Za-z0-9_.-]+$"),
        Regex("^/sys/block/zram0/comp_algorithm$"),
        Regex("^/proc/sys/vm/[A-Za-z0-9_.-]+$"),
    )

    internal fun isAllowedPath(path: String): Boolean =
        !isDenied(path) && ALLOW.any { it.matches(path) }

    private fun invalidValue(value: String): Boolean =
        value.any(Char::isISOControl)

    /**
     * Writes [value] to [path] and reads it back. sysfs frequently accepts a
     * write and then stores something else -- a clamped frequency, or nothing at
     * all -- so the read-back is the only honest confirmation.
     */
    fun write(shell: RootShell, path: String, value: String): WriteResult {
        if (isDenied(path)) return WriteResult.Refused("thermal nodes are not writable by this app")
        if (!isAllowedPath(path)) return WriteResult.Refused("path is outside Governor's write allowlist")
        // A control character from a damaged profile can split or terminate the
        // shell command. Quotes are safe because shellQuote encodes them.
        if (invalidValue(value)) {
            return WriteResult.Refused("value contains a control character")
        }
        // No trailing whitespace: at least one kernel interface (cpu_boost's
        // input_boost_freq) rejects a value written with a trailing space and
        // silently keeps the old one.
        val v = value.trim()
        shell.exec("print -nr -- ${shellQuote(v)} > ${shellQuote(path)} 2>/dev/null")
        val back = shell.readAll(listOf(path))[path].orEmpty().trim()
        return if (accepted(back, v)) WriteResult.Ok else WriteResult.Rejected(v, back)
    }

    /** A guarded write for generated boot scripts, or null for unsafe input. */
    internal fun shellWriteLine(path: String, value: String): String? {
        if (!isAllowedPath(path) || invalidValue(value)) return null
        return "[ -e ${shellQuote(path)} ] && print -nr -- ${shellQuote(value.trim())} > ${shellQuote(path)}"
    }

    /**
     * Whether the read-back means the write landed.
     *
     * Some nodes echo the value straight back. Some answer with the whole menu
     * and brackets round the active entry -- `none [mq-deadline] kyber` -- so a
     * plain token match calls a successful scheduler change a rejection, which
     * is the worst answer available: the write worked and the app says it did
     * not.
     */
    internal fun accepted(back: String, wanted: String): Boolean {
        if (back == wanted) return true
        val active = Regex("(?:^|\\s)\\[([^]]+)](?:\\s|$)").find(back)?.groupValues?.get(1)
        return active == wanted
    }
}
