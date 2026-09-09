package com.attey.governor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RootShellTest {

    @Test(timeout = 5_000)
    fun partialLineDoesNotBlockPastTheDeadline() {
        val process = ProcessBuilder("sh").redirectErrorStream(true).start()
        val shell = RootShell(process)
        try {
            val start = System.nanoTime()
            val output = shell.exec("printf partial; sleep 2", timeoutMs = 100)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertEquals("", output)
            assertTrue("command took ${elapsed}ms", elapsed < 1_500)
            assertFalse(shell.isUsable)
        } finally {
            shell.close()
        }
    }

    @Test(timeout = 5_000)
    fun completeLinesAndCarriageReturnsKeepCommandsSeparated() {
        val process = ProcessBuilder("sh").redirectErrorStream(true).start()
        val shell = RootShell(process)
        try {
            assertEquals("one\ntwo\nthree", shell.exec("printf 'one\\r\\ntwo\\rthree\\n'"))
            assertEquals("next", shell.exec("printf 'next\\n'"))
            assertTrue(shell.isUsable)
        } finally {
            shell.close()
        }
    }
}
