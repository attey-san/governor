package com.attey.governor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileEngineTest {

    @Test
    fun completeFrequencyWindowUsesOrderThatWorksFromEitherDirection() {
        assertEquals(
            listOf(
                "/max" to "1500",
                "/min" to "1000",
                "/max" to "1500",
            ),
            ProfileEngine.frequencyWindowWrites("/min", "/max", 1000, 1500),
        )
    }

    @Test
    fun partialFrequencyWindowWritesOnlyThePresentBound() {
        assertEquals(
            listOf("/min" to "1000"),
            ProfileEngine.frequencyWindowWrites("/min", "/max", 1000, null),
        )
        assertEquals(
            listOf("/max" to "1500"),
            ProfileEngine.frequencyWindowWrites("/min", "/max", null, 1500),
        )
    }

    @Test
    fun restorationRepeatsMaxAfterLoweringMin() {
        val min = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
        val max = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        assertEquals(
            listOf(max to "1500", min to "1000", max to "1500"),
            ProfileEngine.restorationWrites(linkedMapOf(max to "1500", min to "1000")),
        )
    }

    @Test
    fun gpuRestorationWorksWhenMinAppearsFirst() {
        val min = "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq"
        val max = "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"
        assertEquals(
            listOf(max to "600000000", min to "200000000", max to "600000000"),
            ProfileEngine.restorationWrites(linkedMapOf(min to "200000000", max to "600000000")),
        )
    }
}
