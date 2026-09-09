package com.attey.governor.core

data class SysNode(
    val path: String,
    val exists: Boolean = false,
    val writable: Boolean = false,
    val value: String = "",
) {
    val isUsable: Boolean get() = exists && writable

    companion object {
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

        internal fun modes(shell: RootShell, paths: List<String>): Map<String, String> {
            if (paths.isEmpty()) return emptyMap()
            // One stat process for the whole batch; toybox stat is not a builtin.
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

        // `[ -w ]` consults SELinux and reports false for writable cpufreq nodes
        // on some rooted devices. The owner write bit matches app-su behavior.
        private fun ownerWritable(mode: String): Boolean {
            val m = mode.trim()
            if (m.length < 3) return false
            val owner = m[m.length - 3].digitToIntOrNull() ?: return false
            return owner and 2 != 0
        }
    }
}

sealed interface WriteResult {
    data object Ok : WriteResult
    data class Rejected(val wanted: String, val actual: String) : WriteResult
    data class Refused(val reason: String) : WriteResult
}

object Writer {
    // Thermal stays read-only. Vendor thermal control is a closed loop and
    // overwrites manual cooling-state changes.
    private val DENY = listOf(
        Regex("^/sys/class/thermal/cooling_device\\d+/cur_state$"),
        Regex("^/sys/class/thermal/thermal_zone\\d+/"),
        Regex("^/sys/class/thermal/thermal_message/"),
    )

    private fun isDenied(path: String): Boolean = DENY.any { it.containsMatchIn(path) }

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
        path.split('/').none { it == "." || it == ".." } &&
            !isDenied(path) && ALLOW.any { it.matches(path) }

    private fun invalidValue(value: String): Boolean =
        value.any(Char::isISOControl)

    /** Writes a value and verifies the value exposed by the node afterward. */
    fun write(shell: RootShell, path: String, value: String): WriteResult {
        if (isDenied(path)) return WriteResult.Refused("thermal nodes are not writable by this app")
        if (!isAllowedPath(path)) return WriteResult.Refused("path is outside Governor's write allowlist")
        // Control characters could split or terminate the command.
        if (invalidValue(value)) {
            return WriteResult.Refused("value contains a control character")
        }
        // input_boost_freq rejects otherwise valid values with trailing spaces.
        val v = value.trim()
        shell.exec("print -nr -- ${shellQuote(v)} > ${shellQuote(path)} 2>/dev/null")
        val back = shell.readAll(listOf(path))[path].orEmpty().trim()
        return if (accepted(back, v)) WriteResult.Ok else WriteResult.Rejected(v, back)
    }

    internal fun shellWriteLine(path: String, value: String): String? {
        if (!isAllowedPath(path) || invalidValue(value)) return null
        return "[ -e ${shellQuote(path)} ] && print -nr -- ${shellQuote(value.trim())} > ${shellQuote(path)}"
    }

    /** Handles both scalar read-back and bracketed scheduler menus. */
    internal fun accepted(back: String, wanted: String): Boolean {
        if (back == wanted) return true
        val active = Regex("(?:^|\\s)\\[([^]]+)](?:\\s|$)").find(back)?.groupValues?.get(1)
        return active == wanted
    }
}
