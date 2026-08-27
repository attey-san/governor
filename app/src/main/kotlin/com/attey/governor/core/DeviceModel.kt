package com.attey.governor.core

/**
 * What this particular kernel actually exposes.
 *
 * Everything here is discovered at runtime. There are no device names, no SoC
 * checks and no hardcoded core indices anywhere in this file -- that is the
 * difference between working on one phone and working on any of them.
 */
data class DeviceModel(
    val policies: List<CpuPolicy> = emptyList(),
    val gpus: List<GpuDevice> = emptyList(),
    val battery: BatteryNodes? = null,
    val blockDevices: List<BlockDevice> = emptyList(),
    val thermalZones: List<ThermalZone> = emptyList(),
    val vmTunables: Map<String, SysNode> = emptyMap(),
    val boost: Map<String, SysNode> = emptyMap(),
    val rootProvider: String = "unknown",
    val kernel: String = "",
) {
    val totalCores: Int get() = policies.sumOf { it.cpus.size }
    /** Big cluster last: policies are keyed by their first CPU, which sorts naturally. */
    val clusterLabels: List<String> get() = when (policies.size) {
        1 -> listOf("CPU")
        2 -> listOf("Little", "Big")
        3 -> listOf("Little", "Big", "Prime")
        4 -> listOf("Little", "Mid", "Big", "Prime")
        else -> policies.indices.map { "Cluster $it" }
    }
}

/**
 * One cpufreq policy -- a cluster of cores that scale together.
 *
 * Found by enumerating /sys/devices/system/cpu/cpufreq/policy* and reading
 * related_cpus. This handles 4+4, 4+3+1, 2+4+2 and anything else a vendor ships,
 * because it never assumes how many clusters there are or which cores are in them.
 */
data class CpuPolicy(
    val id: Int,
    val path: String,
    val cpus: List<Int>,
    val availableFreqs: List<Long>,
    val hwMin: Long,
    val hwMax: Long,
    val scalingMin: Long,
    val scalingMax: Long,
    val governor: String,
    val availableGovernors: List<String>,
    /** Auto-discovered from policyN/<governor>/ -- never a hardcoded list. */
    val governorTunables: Map<String, SysNode> = emptyMap(),
    val coreCtl: Map<String, SysNode> = emptyMap(),
) {
    val maxNode get() = "$path/scaling_max_freq"
    val minNode get() = "$path/scaling_min_freq"
    val govNode get() = "$path/scaling_governor"

    /**
     * True when the kernel's ceiling sits below the silicon's. Something else --
     * usually a vendor thermal daemon -- is holding it down, and the UI should
     * say so rather than showing a slider that appears broken.
     */
    val isCappedBelowHardware: Boolean get() = scalingMax < hwMax

    fun nearestFreq(target: Long): Long =
        availableFreqs.minByOrNull { kotlin.math.abs(it - target) } ?: target
}

data class GpuDevice(
    val name: String,
    val path: String,
    val availableFreqs: List<Long>,
    val minFreq: Long,
    val maxFreq: Long,
    val governor: String,
    val availableGovernors: List<String>,
)

data class BatteryNodes(
    val path: String,
    val hasCurrent: Boolean,
    val hasVoltage: Boolean,
    val hasChargeFull: Boolean,
    val hasCycleCount: Boolean,
)

data class BlockDevice(
    val name: String,
    val path: String,
    val scheduler: String,
    val availableSchedulers: List<String>,
    val readAheadKb: Long,
    val nrRequests: Long,
    val rotational: Boolean,
    /**
     * dm-*, loop*, ram* and zram* are stacked or virtual. They have queues and
     * will happily show up in a naive listing -- on the development device eight
     * dm devices preceded the first real one -- but tuning them is meaningless.
     */
    val isVirtual: Boolean,
    val mountedAt: String? = null,
)

data class ThermalZone(val id: Int, val type: String, val tempMilliC: Int) {
    /** Zones reading -40C or -273C are unpopulated, not cold. Never max across them blindly. */
    val isPopulated: Boolean get() = tempMilliC > -30_000
    val celsius: Float get() = tempMilliC / 1000f
}
