package com.attey.governor.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * A persistent root shell.
 *
 * Probing a device touches on the order of a hundred sysfs nodes. Spawning `su`
 * once per read costs ~30ms each and turns a probe into a visible stall, so we
 * keep one shell open and delimit each command's output with a sentinel.
 *
 * Deliberately dependency-free: libsu would do this too, but this is sixty lines
 * and removes a version we would otherwise have to track.
 *
 * IMPORTANT: never do arithmetic inside this shell. Android's /system/bin/sh is
 * mksh and its $(( )) is 32-bit -- it wraps silently above 2^31, which is below
 * several counters we read (block sectors, /proc/<pid>/io, uptime in ns). Emit
 * raw strings here and do the maths in Kotlin.
 */
class RootShell private constructor(
    private val process: Process,
    private val stdin: OutputStreamWriter,
    private val stdout: BufferedReader,
) {
    private val lock = Any()

    /**
     * Set when a command failed to finish in time.
     *
     * A timed-out command leaves unread output in the pipe, which would be handed
     * to whatever runs next as if it were that command's answer. Rather than try
     * to resynchronise, the shell is destroyed and [get] starts a fresh one.
     */
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
                // Either the shell died or it stopped answering. Both mean this
                // instance can no longer be trusted to line up commands with
                // their output.
                broken = true
                runCatching { process.destroy() }
                return sb.toString()
            }
            if (line == sentinel) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        sb.toString()
    }

    /**
     * Blocking read with a deadline. `ready()` is checked first so a shell that
     * has stopped producing output cannot park a coroutine forever -- which is
     * what a hung `su` used to do, leaving the app on "probing" with no way back.
     */
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

    suspend fun execAsync(cmd: String): String = withContext(Dispatchers.IO) { exec(cmd) }

    /**
     * Reads many paths in one round trip. Returns path -> contents, omitting any
     * that do not exist.
     *
     * Uses the shell's `read` builtin rather than `cat`. Measured on the
     * development device, 505 nodes cost 1356 ms through `cat` and 45 ms through
     * `read` -- the whole difference is one fork per node, and a full probe
     * touches roughly nine hundred of them. `v=` before each read matters: a
     * failed read leaves the previous value in place, which would silently
     * attribute one node's contents to the next.
     *
     * Only the first line is returned. Every tunable in this app is single-line;
     * [readMultiline] exists for the ones that are not.
     */
    fun readAll(paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val script = paths.joinToString("\n") { p ->
            "if [ -e '$p' ]; then v=; IFS= read -r v < '$p' 2>/dev/null; echo \"$p%%GOV%%\$v\"; fi"
        }
        return parse(exec(script))
    }

    /**
     * For nodes with more than one line, such as cpufreq's time_in_state.
     *
     * Each line is emitted separately with the path in front, rather than joined
     * with a separator. The obvious shortcut -- join with \r and split again --
     * is silently wrong: BufferedReader.readLine() treats a bare \r as a line
     * terminator, so the joined string comes back already split, and every chunk
     * after the first arrives without the path marker and is discarded. The
     * result is a function that quietly returns only the first line of any file.
     *
     * That bug produced a residency breakdown reading "0.71 GHz, 100%" -- entirely
     * plausible, entirely an artefact of only ever seeing line one.
     */
    fun readMultiline(paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val script = paths.joinToString("\n") { p ->
            "if [ -e '$p' ]; then while IFS= read -r l || [ -n \"\$l\" ]; do " +
                "echo \"$p%%GOV%%\$l\"; done < '$p' 2>/dev/null; fi"
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
            map[line.substring(0, i)] = line.substring(i + 7).replace('\r', '\n').trim()
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
                    // Prove we actually got uid 0 -- `su` existing is not the same
                    // as `su` being granted.
                    // Generous: this is the call the su prompt blocks, and the
                    // user has to find the phone and tap Grant.
                    if (shell.exec("id -u", GRANT_TIMEOUT_MS).trim() != "0") { shell.close(); null }
                    else { instance = shell; shell }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
