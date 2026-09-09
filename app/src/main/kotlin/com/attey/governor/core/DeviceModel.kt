package com.attey.governor.core

import java.util.Locale

data class DeviceModel(
    val policies: List<CpuPolicy> = emptyList(),
    val gpus: List<GpuDevice> = emptyList(),
    val battery: BatteryNodes? = null,
    val blockDevices: List<BlockDevice> = emptyList(),
    val thermalZones: List<ThermalZone> = emptyList(),
    val vmTunables: Map<String, SysNode> = emptyMap(),
    val boost: Map<String, SysNode> = emptyMap(),
    val zram: Map<String, SysNode> = emptyMap(),
    val coresOnline: Map<Int, Boolean> = emptyMap(),
    val hotpluggableCores: Set<Int> = emptySet(),
    val rootProvider: String = "unknown",
    val kernel: String = "",
) {
    val totalCores: Int get() = policies.sumOf { it.cpus.size }
    val clusterLabels: List<String> get() = when (policies.size) {
        1 -> listOf("CPU")
        2 -> listOf("Little", "Big")
        3 -> listOf("Little", "Big", "Prime")
        4 -> listOf("Little", "Mid", "Big", "Prime")
        else -> policies.indices.map { "Cluster $it" }
    }
}

/** One cpufreq policy and the cores that share it. */
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
    val minWritable: Boolean,
    val maxWritable: Boolean,
    val governorWritable: Boolean,
    val governorTunables: Map<String, SysNode> = emptyMap(),
) {
    val maxNode get() = "$path/scaling_max_freq"
    val minNode get() = "$path/scaling_min_freq"
    val govNode get() = "$path/scaling_governor"

    val isCappedBelowHardware: Boolean get() = scalingMax < hwMax
}

data class GpuDevice(
    val name: String,
    val path: String,
    val availableFreqs: List<Long>,
    val minFreq: Long,
    val maxFreq: Long,
    val governor: String,
    val availableGovernors: List<String>,
    val minWritable: Boolean,
    val maxWritable: Boolean,
    val governorWritable: Boolean,
)

data class BatteryNodes(
    val path: String,
    val chargeFullUah: Long = 0,
    val chargeFullDesignUah: Long = 0,
    val cycleCount: Long? = null,
) {
    val healthPercent: Int?
        get() = if (chargeFullUah > 0 && chargeFullDesignUah > 0)
            (chargeFullUah * 100 / chargeFullDesignUah).toInt() else null

    val fullMah: Long get() = chargeFullUah / 1000
    val designMah: Long get() = chargeFullDesignUah / 1000
}

data class BlockDevice(
    val name: String,
    val path: String,
    val scheduler: String,
    val availableSchedulers: List<String>,
    val schedulerWritable: Boolean,
    val readAheadKb: Long,
    val readAheadWritable: Boolean,
    val nrRequests: Long,
    val nrRequestsWritable: Boolean,
    val rotational: Boolean,
    /** Stacked and virtual devices are displayed but never tuned. */
    val isVirtual: Boolean,
    val mountedAt: String? = null,
    val sizeBytes: Long = 0,
) {
    val sizeLabel: String
        get() = when {
            sizeBytes >= 1_000_000_000L ->
                String.format(Locale.US, "%.0f GB", sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000L ->
                String.format(Locale.US, "%.0f MB", sizeBytes / 1_000_000.0)
            sizeBytes > 0 -> "$sizeBytes B"
            else -> ""
        }
}

data class ThermalZone(val id: Int, val type: String, val tempMilliC: Int) {
    /** Common sentinel temperatures are below -30 C. */
    val isPopulated: Boolean get() = tempMilliC > -30_000
    val celsius: Float get() = tempMilliC / 1000f
}
