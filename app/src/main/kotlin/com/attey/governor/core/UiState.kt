package com.attey.governor.core

/**
 * The contract between the probe layer and the UI.
 *
 * Screens are pure functions of these types. Nothing in the UI package touches
 * a shell, a file, or a sysfs path -- if it needs a value it is here, and if it
 * wants to change one it calls a lambda.
 */
sealed interface UiState {
    data object Loading : UiState

    /** Root was refused or absent. The app is read-only-useless without it; say so plainly. */
    data class NoRoot(val message: String) : UiState

    data class Ready(
        val device: DeviceModel,
        val live: LiveStats,
        /** Non-null while a change is awaiting confirmation. See [PendingRevert]. */
        val pending: PendingRevert? = null,
        /** Set when the last write was rejected by the kernel. Shown once, then cleared. */
        val lastRejection: String? = null,
    ) : UiState
}

/**
 * Values that change while you watch them. Sampled on a timer, kept apart from
 * [DeviceModel] so a refresh of live numbers does not redraw the whole tree.
 */
data class LiveStats(
    /** policy id -> current kHz, from scaling_cur_freq. */
    val policyCurFreq: Map<Int, Long> = emptyMap(),
    val gpuCurFreq: Long = 0,
    /** 0..100, or null if the kernel does not expose it. */
    val gpuBusyPercent: Int? = null,
    /**
     * Battery draw in milliwatts, positive when discharging. Computed as
     * current_now x voltage_now -- never read from power_now, which is wrong by
     * three orders of magnitude on at least one shipping device.
     */
    val batteryMilliwatts: Int? = null,
    val batteryPercent: Int? = null,
    val batteryTempC: Float? = null,
    val charging: Boolean = false,
    /** Hottest populated thermal zone, for the header. */
    val hottestZone: Pair<String, Float>? = null,
    /** zone id -> degrees C, populated zones only. Refreshed on the slow tick. */
    val zoneTemps: Map<Int, Float> = emptyMap(),
    val uptimeSeconds: Long = 0,
)

/**
 * A change that will undo itself.
 *
 * Offlining the wrong core or picking a bad governor can wedge a phone hard
 * enough to need a battery pull. Every write that could do that goes through
 * here: it is applied, a countdown starts, and unless the user confirms within
 * [secondsLeft] the previous value is restored. Desktop display settings have
 * worked this way for twenty years; no kernel manager does it.
 */
data class PendingRevert(
    val description: String,
    val secondsLeft: Int,
    /** path -> value to restore if the user does not confirm. */
    val restore: Map<String, String>,
    /** The countdown this started from, so the bar does not carry its own copy. */
    val totalSeconds: Int = secondsLeft,
) {
    val fractionLeft: Float get() =
        if (totalSeconds <= 0) 0f else secondsLeft.toFloat() / totalSeconds
}

/** One line of the capability report. */
data class Capability(
    val area: String,
    val name: String,
    val path: String,
    val present: Boolean,
    val writable: Boolean,
    /** Set when a node exists but is known not to do anything useful on this kernel. */
    val note: String? = null,
)
