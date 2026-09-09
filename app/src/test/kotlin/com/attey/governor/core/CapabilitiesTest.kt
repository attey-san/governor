package com.attey.governor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilitiesTest {

    @Test
    fun candidatePathsCoverEveryDiscoveredSubsystem() {
        val model = DeviceModel(
            policies = listOf(policy(0, listOf(0, 1)), policy(4, listOf(4, 5))),
            gpus = listOf(gpu("/sys/class/devfreq/gpu0"), gpu("/sys/class/devfreq/gpu1")),
        )

        assertEquals(
            listOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq",
            ),
            Capabilities.pathsFor("{policy}/scaling_max_freq", model),
        )
        assertEquals(
            listOf(
                "/sys/devices/system/cpu/cpu1/online",
                "/sys/devices/system/cpu/cpu4/online",
                "/sys/devices/system/cpu/cpu5/online",
            ),
            Capabilities.pathsFor("{cpu}/online", model),
        )
        assertEquals(
            listOf("/sys/class/devfreq/gpu0/min_freq", "/sys/class/devfreq/gpu1/min_freq"),
            Capabilities.pathsFor("{gpu}/min_freq", model),
        )
    }

    private fun policy(id: Int, cpus: List<Int>) = CpuPolicy(
        id = id,
        path = "/sys/devices/system/cpu/cpufreq/policy$id",
        cpus = cpus,
        availableFreqs = emptyList(),
        hwMin = 0,
        hwMax = 0,
        scalingMin = 0,
        scalingMax = 0,
        governor = "schedutil",
        availableGovernors = emptyList(),
        minWritable = false,
        maxWritable = false,
        governorWritable = false,
    )

    private fun gpu(path: String) = GpuDevice(
        name = path.substringAfterLast('/'),
        path = path,
        availableFreqs = emptyList(),
        minFreq = 0,
        maxFreq = 0,
        governor = "",
        availableGovernors = emptyList(),
        minWritable = false,
        maxWritable = false,
        governorWritable = false,
    )
}
