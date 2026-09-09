package com.attey.governor.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriterTest {

    @Test
    fun menuReadbackRequiresBracketedValueToBeActive() {
        assertTrue(Writer.accepted("none [mq-deadline] kyber", "mq-deadline"))
        assertFalse(Writer.accepted("[none] mq-deadline kyber", "mq-deadline"))
        assertFalse(Writer.accepted("none mq-deadline kyber", "mq-deadline"))
    }

    @Test
    fun scalarReadbackMustMatchExactly() {
        assertTrue(Writer.accepted("1200000", "1200000"))
        assertFalse(Writer.accepted("12000000", "1200000"))
    }

    @Test
    fun writeAllowlistRejectsTraversalAndUnrelatedFiles() {
        assertTrue(Writer.isAllowedPath("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(Writer.isAllowedPath("/sys/block/sda/queue/scheduler"))
        assertTrue(Writer.isAllowedPath("/sys/class/devfreq/1c00000.qcom,kgsl-3d0/max_freq"))
        assertFalse(Writer.isAllowedPath("/sys/class/thermal/thermal_zone0/mode"))
        assertFalse(Writer.isAllowedPath("/sys/block/sda/queue/../../../kernel/uevent_seqnum"))
        assertFalse(Writer.isAllowedPath("/data/local/tmp/value"))
    }

    @Test
    fun generatedWriteRejectsShellControlCharacters() {
        assertTrue(
            Writer.shellWriteLine(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                "1200000",
            ) != null
        )
        assertTrue(
            Writer.shellWriteLine(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                "1200000\u0000ignored",
            ) == null
        )
    }

    @Test
    fun dotSegmentsCannotEscapeAnAllowedDirectory() {
        val paths = listOf(
            "/sys/devices/system/cpu/cpufreq/policy0/../uevent",
            "/sys/devices/system/cpu/cpufreq/policy0/./scaling_max_freq",
            "/sys/class/devfreq/../uevent",
            "/proc/sys/vm/..",
        )
        for (path in paths) {
            assertFalse(path, Writer.isAllowedPath(path))
            assertTrue(path, Writer.shellWriteLine(path, "1") == null)
        }
    }
}
