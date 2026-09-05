package com.attey.governor.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Persistent root shell used to batch sysfs reads. Avoid shell arithmetic here:
 * mksh wraps it at 32 bits, below several counters we read.
 */
class RootShell private constructor(
    private val process: Process,
    private val stdin: OutputStreamWriter,
    private val stdout: BufferedReader,
) {
    private val lock = Any()

    // After a timeout, unread output can no longer be matched to a command.
    @Volatile private var broken = false

    val isUsable: Boolean get() = !broken && process.isAlive

    /** Runs [cmd] and returns stdout with the trailing newline stripped. */
    fun exec(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String = synchronized(lock) {
        if (broken) return ""
        val sentinel = "__GOV_${System.nanoTime()}__"
        try {
            stdin.write(cmd)
            stdin.write("\necho $sentinel\n")
            stdin.flush()
        } catch (_: Exception) {
            broken = true
            return ""
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        val sb = StringBuilder()
        while (true) {
            val line = readLineBefore(deadline)
            if (line == null) {
                // Discard partial output and replace the shell on the next call.
                broken = true
                runCatching { process.destroy() }
                return ""
            }
            if (line == sentinel) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        sb.toString()
    }

    /** BufferedReader has no read timeout, so poll ready() until the deadline. */
    private fun readLineBefore(deadline: Long): String? {
        while (System.currentTimeMillis() < deadline) {
            try {
                if (stdout.ready()) return stdout.readLine()
                if (!process.isAlive) return null
                Thread.sleep(4)
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    /**
     * Reads the first line of many paths in one shell round trip. The `read`
     * builtin measured about 30x faster than forking `cat` for each path.
     */
    fun readAll(paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val script = paths.joinToString("\n") { p ->
            val q = shellQuote(p)
            // Clear v because a failed read leaves its previous value intact.
            "if [ -e $q ]; then v=; IFS= read -r v < $q 2>/dev/null; " +
                "echo $q'%%GOV%%'\"\$v\"; fi"
        }
        return parse(exec(script))
    }

    /**
     * Reads multiline nodes such as time_in_state. Each output line repeats the
     * path; a bare '\r' separator is unsafe because BufferedReader treats it as a
     * line ending.
     */
    fun readMultiline(paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val script = paths.joinToString("\n") { p ->
            val q = shellQuote(p)
            "if [ -e $q ]; then while IFS= read -r l || [ -n \"\$l\" ]; do " +
                "echo $q'%%GOV%%'\"\$l\"; done < $q 2>/dev/null; fi"
        }
        val grouped = LinkedHashMap<String, StringBuilder>()
        for (line in exec(script).lineSequence()) {
            val i = line.indexOf("%%GOV%%")
            if (i <= 0) continue
            val key = line.substring(0, i)
            val value = line.substring(i + 7)
            grouped.getOrPut(key) { StringBuilder() }.let {
                if (it.isNotEmpty()) it.append('\n')
                it.append(value)
            }
        }
        return grouped.mapValues { it.value.toString() }
    }

    private fun parse(out: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (line in out.lineSequence()) {
            val i = line.indexOf("%%GOV%%")
            if (i <= 0) continue
            map[line.substring(0, i)] = line.substring(i + 7).trim()
        }
        return map
    }

    fun close() {
        runCatching { stdin.write("exit\n"); stdin.flush() }
        runCatching { process.destroy() }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val GRANT_TIMEOUT_MS = 120_000L

        @Volatile private var instance: RootShell? = null

        /** Returns a live root shell, or null if root was denied or absent. */
        fun get(): RootShell? {
            instance?.let { if (it.isUsable) return it }
            synchronized(this) {
                instance?.let { if (it.isUsable) return it }
                return try {
                    val p = ProcessBuilder("su").redirectErrorStream(true).start()
                    val w = OutputStreamWriter(p.outputStream)
                    val r = BufferedReader(InputStreamReader(p.inputStream))
                    val shell = RootShell(p, w, r)
                    // This call may wait while the user answers the root prompt.
                    if (shell.exec("id -u", GRANT_TIMEOUT_MS).trim() != "0") { shell.close(); null }
                    else { instance = shell; shell }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
