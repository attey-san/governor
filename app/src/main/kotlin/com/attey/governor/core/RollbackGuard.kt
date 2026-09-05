package com.attey.governor.core

import java.util.UUID

/** A root-side deadline that survives the activity and app process. */
object RollbackGuard {

    private val TOKEN = Regex("^/data/local/tmp/\\.governor-revert-[a-f0-9-]+$")

    /**
     * Arms the restore before a risky write. The detached shell owns the timer,
     * so pressing Back or having Android kill the app cannot cancel it.
     */
    fun arm(
        shell: RootShell,
        restore: Map<String, String>,
        seconds: Int,
    ): String? {
        if (restore.isEmpty() || seconds <= 0) return null
        val lines = ProfileEngine.restorationWrites(restore).map { (path, value) ->
            Writer.shellWriteLine(path, value) ?: return null
        }
        val token = "/data/local/tmp/.governor-revert-${UUID.randomUUID()}"
        val q = shellQuote(token)
        val firing = shellQuote("$token.firing")
        val script = buildString {
            appendLine("umask 077")
            appendLine(": > $q || exit 1")
            appendLine("(")
            appendLine("  sleep $seconds")
            appendLine("  if mv $q $firing 2>/dev/null; then")
            lines.forEach { appendLine("    $it") }
            appendLine("    rm -f $firing")
            appendLine("  fi")
            appendLine(") </dev/null >/dev/null 2>&1 &")
            appendLine("echo $q")
        }
        return shell.exec(script).lineSequence().lastOrNull()?.takeIf { it == token }
    }

    /** Cancels a guard only if it has not already claimed the rollback. */
    fun disarm(shell: RootShell, token: String): Boolean {
        if (!TOKEN.matches(token)) return false
        val q = shellQuote(token)
        return shell.exec(
            "if [ -e $q ] && rm -f $q; then echo disarmed; else echo missed; fi"
        ).lineSequence().lastOrNull() == "disarmed"
    }
}
