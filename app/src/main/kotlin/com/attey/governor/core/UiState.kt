package com.attey.governor.core

sealed interface UiState {
    data object Loading : UiState

    data class NoRoot(val message: String) : UiState

    data class Ready(
        val device: DeviceModel,
        val live: LiveStats,
        val pending: PendingRevert? = null,
        val lastRejection: String? = null,
    ) : UiState
}

/** Values sampled while the UI is visible. */
data class LiveStats(
    val policyCurFreq: Map<Int, Long> = emptyMap(),
    val gpuCurFreq: Map<String, Long> = emptyMap(),
    val gpuBusyPercent: Map<String, Int> = emptyMap(),
    /** Positive draw in mW, computed from current_now and voltage_now. */
    val batteryMilliwatts: Int? = null,
    val batteryPercent: Int? = null,
    val batteryTempC: Float? = null,
    val charging: Boolean = false,
    val hottestZone: Pair<String, Float>? = null,
    val zoneTemps: Map<Int, Float> = emptyMap(),
)

/** A risky change with a root-side automatic rollback. */
data class PendingRevert(
    val description: String,
    val secondsLeft: Int,
    val restore: Map<String, String>,
    val guardToken: String,
    val totalSeconds: Int = secondsLeft,
) {
    val fractionLeft: Float get() =
        if (totalSeconds <= 0) 0f else secondsLeft.toFloat() / totalSeconds
}

data class Capability(
    val area: String,
    val name: String,
    val path: String,
    val present: Boolean,
    val writable: Boolean,
    val note: String? = null,
)
