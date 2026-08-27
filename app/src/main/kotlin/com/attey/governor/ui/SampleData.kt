package com.attey.governor.ui

import com.attey.governor.core.BatteryNodes
import com.attey.governor.core.BlockDevice
import com.attey.governor.core.Capability
import com.attey.governor.core.CpuPolicy
import com.attey.governor.core.DeviceModel
import com.attey.governor.core.GpuDevice
import com.attey.governor.core.LiveStats
import com.attey.governor.core.SysNode
import com.attey.governor.core.ThermalZone

private fun node(path: String, value: String, writable: Boolean = true): SysNode =
    SysNode(path = path, exists = true, writable = writable, value = value)

val sampleDevice: DeviceModel = DeviceModel(
    policies = listOf(
        CpuPolicy(
            id = 0,
            path = "/sys/devices/system/cpu/cpufreq/policy0",
            cpus = listOf(0, 1, 2, 3),
            availableFreqs = listOf(
                300_000, 403_200, 518_400, 614_400, 691_200, 787_200, 883_200, 979_200,
                1_075_200, 1_171_200, 1_248_000, 1_344_000, 1_420_800, 1_516_800,
                1_612_800, 1_708_800, 1_804_800,
            ),
            hwMin = 300_000,
            hwMax = 1_804_800,
            scalingMin = 691_200,
            scalingMax = 1_804_800,
            governor = "schedutil",
            availableGovernors = listOf("userspace", "powersave", "performance", "schedutil"),
            governorTunables = mapOf(
                "rate_limit_us" to node(
                    "/sys/devices/system/cpu/cpufreq/policy0/schedutil/rate_limit_us",
                    "10000",
                ),
                "hispeed_load" to node(
                    "/sys/devices/system/cpu/cpufreq/policy0/schedutil/hispeed_load",
                    "80",
                ),
                "hispeed_freq" to node(
                    "/sys/devices/system/cpu/cpufreq/policy0/schedutil/hispeed_freq",
                    "0",
                ),
            ),
        ),
        CpuPolicy(
            id = 4,
            path = "/sys/devices/system/cpu/cpufreq/policy4",
            cpus = listOf(4, 5, 6),
            availableFreqs = listOf(
                710_400, 844_800, 960_000, 1_075_200, 1_248_000, 1_420_800, 1_612_800,
                1_804_800, 1_996_800, 2_246_400,
            ),
            hwMin = 710_400,
            hwMax = 2_419_200,
            scalingMin = 710_400,
            scalingMax = 2_246_400,
            governor = "schedutil",
            availableGovernors = listOf("userspace", "powersave", "performance", "schedutil"),
        ),
        CpuPolicy(
            id = 7,
            path = "/sys/devices/system/cpu/cpufreq/policy7",
            cpus = listOf(7),
            availableFreqs = listOf(
                844_800, 1_248_000, 1_612_800, 1_804_800, 1_996_800, 2_246_400, 2_496_000,
                2_745_600,
            ),
            hwMin = 844_800,
            hwMax = 3_187_200,
            scalingMin = 844_800,
            scalingMax = 2_745_600,
            governor = "schedutil",
            availableGovernors = listOf("userspace", "powersave", "performance", "schedutil"),
        ),
    ),
    gpus = listOf(
        GpuDevice(
            name = "Adreno650v3",
            path = "/sys/class/kgsl/kgsl-3d0/devfreq",
            availableFreqs = listOf(
                305_000_000, 430_000_000, 525_000_000, 596_000_000, 670_000_000,
            ),
            minFreq = 305_000_000,
            maxFreq = 670_000_000,
            governor = "msm-adreno-tz",
            availableGovernors = listOf("msm-adreno-tz", "performance", "powersave"),
        ),
    ),
    battery = BatteryNodes(
        path = "/sys/class/power_supply/battery",
        hasCurrent = true,
        hasVoltage = true,
        hasChargeFull = true,
        hasCycleCount = false,
    ),
    blockDevices = listOf(
        BlockDevice(
            name = "sda",
            path = "/sys/block/sda",
            scheduler = "cfq",
            availableSchedulers = listOf("noop", "cfq", "deadline", "bfq"),
            readAheadKb = 512,
            nrRequests = 128,
            rotational = true,
            isVirtual = false,
            mountedAt = "/data",
        ),
        BlockDevice(
            name = "zram0",
            path = "/sys/block/zram0",
            scheduler = "none",
            availableSchedulers = listOf("none"),
            readAheadKb = 128,
            nrRequests = 0,
            rotational = false,
            isVirtual = true,
        ),
        BlockDevice(
            name = "dm-46",
            path = "/sys/block/dm-46",
            scheduler = "none",
            availableSchedulers = listOf("none"),
            readAheadKb = 128,
            nrRequests = 0,
            rotational = false,
            isVirtual = true,
        ),
    ),
    thermalZones = listOf(
        ThermalZone(0, "cpu-thermal", 44_200),
        ThermalZone(1, "gpu-thermal", 41_500),
    ),
    vmTunables = emptyMap(),
    boost = emptyMap(),
    rootProvider = "30.7:MAGISKSU",
    kernel = "4.19.325-cip131-st15-perf",
)

val sampleLive: LiveStats = LiveStats(
    policyCurFreq = mapOf(0 to 1_420_800L, 4 to 1_804_800L, 7 to 2_246_400L),
    gpuCurFreq = 525_000_000L,
    gpuBusyPercent = 37,
    batteryMilliwatts = 2_840,
    batteryPercent = 78,
    batteryTempC = 32.4f,
    charging = false,
    hottestZone = "cpu-thermal" to 44.2f,
    uptimeSeconds = 14_532L,
)

val sampleCapabilities: List<Capability> = listOf(
    Capability("cpu", "schedutil rate_limit_us", "/sys/.../schedutil/rate_limit_us", true, true, null),
    Capability("cpu", "interactive hispeed_freq", "/sys/.../interactive/hispeed_freq", false, false, "governor not loaded"),
    Capability("cpu", "boost input_boost_freq", "/sys/.../boost/input_boost_freq", true, true, null),
    Capability("gpu", "Adreno min_freq", "/sys/.../kgsl-3d0/min_freq", true, true, null),
    Capability("gpu", "Mali max_freq", "/sys/.../mali/max_freq", false, false, "no Mali on this SoC"),
    Capability("io", "scheduler", "/sys/block/sda/queue/scheduler", true, true, null),
    Capability("io", "read_ahead_kb", "/sys/block/sda/queue/read_ahead_kb", true, true, null),
    Capability("battery", "current_now", "/sys/.../battery/current_now", true, true, null),
    Capability("battery", "cycle_count", "/sys/.../battery/cycle_count", true, false, "read-only on this kernel"),
    Capability("vm", "dirty_ratio", "/proc/sys/vm/dirty_ratio", true, true, null),
)
