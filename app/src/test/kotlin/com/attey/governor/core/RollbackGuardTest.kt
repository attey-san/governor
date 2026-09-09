package com.attey.governor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class RollbackGuardTest {
    @get:Rule val folder = TemporaryFolder()

    private val token = "/data/local/tmp/.governor-revert-abcd"

    @Test
    fun cancellationClaimsTheMarkerOnlyOnce() {
        val marker = folder.newFile(".governor-revert-abcd")
        assertEquals("disarmed", runDisarm())
        assertFalse(marker.exists())
        assertEquals("missed", runDisarm())
    }

    @Test
    fun timerClaimBetweenCheckAndCancellationMustNotReportSuccess() {
        val marker = folder.newFile(".governor-revert-abcd")
        val q = shellQuote(marker.path)
        val firing = shellQuote("${marker.path}.firing")
        // Let the timer win immediately before cancellation's filesystem operation.
        val race = """
            timer_claim() { [ ! -e $q ] || command mv $q $firing; }
            rm() { timer_claim; command rm "${'$'}@"; }
            mv() { timer_claim; command mv "${'$'}@"; }
        """.trimIndent()
        assertEquals("missed", runDisarm(race))
        assertTrue(File("${marker.path}.firing").exists())
    }

    @Test
    fun unrelatedTokensAreRejected() {
        assertNull(RollbackGuard.disarmScript("/data/local/tmp/other"))
        assertNull(RollbackGuard.disarmScript("$token/../other"))
    }

    private fun runDisarm(prefix: String = ""): String {
        val script = requireNotNull(RollbackGuard.disarmScript(token))
            .replace("/data/local/tmp", folder.root.path)
        val process = ProcessBuilder("sh", "-c", "$prefix\n$script").start()
        try {
            assertTrue("cancellation hung", process.waitFor(2, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            return process.inputStream.bufferedReader().readText().trim()
        } finally {
            process.destroy()
        }
    }
}
