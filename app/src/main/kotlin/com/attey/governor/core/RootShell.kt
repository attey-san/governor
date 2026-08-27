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

    /** Runs [cmd] and returns stdout with the trailing newline stripped. */
    fun exec(cmd: String): String = synchronized(lock) {
        val sentinel = "__GOV_${System.nanoTime()}__"
        stdin.write(cmd)
        stdin.write("\necho $sentinel\n")
        stdin.flush()
        val sb = StringBuilder()
        while (true) {
            val line = stdout.readLine() ?: break
            if (line == sentinel) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        sb.toString()
    }

    suspend fun execAsync(cmd: String): String = withContext(Dispatchers.IO) { exec(cmd) }

    /**
     * Reads many paths in one round trip. Returns path -> contents, omitting any
     * that could not be read. Far cheaper than one exec per node.
     */
    fun readAll(paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val script = paths.joinToString("\n") { p ->
            // %%GOV%% separates the key from the value; unreadable nodes emit nothing.
            "if [ -r '$p' ]; then echo \"$p%%GOV%%\$(cat '$p' 2>/dev/null | head -c 8192 | tr '\\n' '\\r')\"; fi"
        }
        val out = exec(script)
        val map = HashMap<String, String>(paths.size)
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
        @Volatile private var instance: RootShell? = null

        /** Returns a live root shell, or null if root was denied or absent. */
        fun get(): RootShell? {
            instance?.let { if (it.process.isAlive) return it }
            synchronized(this) {
                instance?.let { if (it.process.isAlive) return it }
                return try {
                    val p = ProcessBuilder("su").redirectErrorStream(true).start()
                    val w = OutputStreamWriter(p.outputStream)
                    val r = BufferedReader(InputStreamReader(p.inputStream))
                    val shell = RootShell(p, w, r)
                    // Prove we actually got uid 0 -- `su` existing is not the same
                    // as `su` being granted.
                    if (shell.exec("id -u").trim() != "0") { shell.close(); null }
                    else { instance = shell; shell }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
