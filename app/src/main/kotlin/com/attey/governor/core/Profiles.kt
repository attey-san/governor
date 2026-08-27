package com.attey.governor.core

/**
 * A named set of values to apply together.
 *
 * Deliberately cannot offline a core. Profiles are applied by triggers with
 * nobody watching -- there is no 30-second countdown to catch a mistake when the
 * phone applies one in your pocket -- so the profile format only holds settings
 * that are recoverable by applying a different profile. Hotplug stays manual.
 */
data class Profile(
    val name: String,
    /** policy id -> kHz. Missing entries are left alone. */
    val policyMin: Map<Int, Long> = emptyMap(),
    val policyMax: Map<Int, Long> = emptyMap(),
    val governors: Map<Int, String> = emptyMap(),
    val gpuMin: Long? = null,
    val gpuMax: Long? = null,
    val gpuGovernor: String? = null,
    /** Absolute path -> value, for governor tunables and input boost. */
    val tunables: Map<String, String> = emptyMap(),
    /** Bare name under /proc/sys/vm -> value. */
    val vm: Map<String, String> = emptyMap(),
    /** "sda/scheduler", "sda/read_ahead_kb" -> value. */
    val io: Map<String, String> = emptyMap(),
) {
    val settingCount: Int
        get() = policyMin.size + policyMax.size + governors.size + tunables.size +
            vm.size + io.size + listOfNotNull(gpuMin, gpuMax, gpuGovernor).size
}

enum class TriggerType(val label: String, val needsThreshold: Boolean) {
    UNPLUGGED("when unplugged", false),
    PLUGGED_IN("when plugged in", false),
    SCREEN_OFF("when the screen goes off", false),
    SCREEN_ON("when the screen comes on", false),
    BATTERY_BELOW("when battery drops below", true),
    TEMP_ABOVE("when battery temperature rises above", true),
}

data class Trigger(
    val id: Long,
    val type: TriggerType,
    /** Percent for BATTERY_BELOW, degrees C for TEMP_ABOVE, ignored otherwise. */
    val threshold: Int,
    val profileName: String,
    val enabled: Boolean = true,
) {
    val description: String
        get() = if (type.needsThreshold) {
            "${type.label} $threshold${if (type == TriggerType.BATTERY_BELOW) "%" else "°C"} → $profileName"
        } else {
            "${type.label} → $profileName"
        }
}

/**
 * An A/B run: what a profile actually cost in milliwatts, against a baseline
 * measured the same way.
 *
 * This is the reason the project is worth publishing. Every kernel manager lets
 * you change things; none of them tell you whether the change did anything, so
 * the whole category runs on folklore.
 */
data class MeasureRun(
    val label: String,
    val running: Boolean,
    val elapsedSeconds: Int,
    val totalSeconds: Int,
    /** Mean draw over the window. Null until the first sample lands. */
    val averageMilliwatts: Int? = null,
    val samples: List<Int> = emptyList(),
    /** policy id -> (kHz -> share of the window spent there, 0..1). */
    val residency: Map<Int, Map<Long, Float>> = emptyMap(),
) {
    val progress: Float get() = if (totalSeconds <= 0) 0f else elapsedSeconds.toFloat() / totalSeconds
}

/**
 * The comparison itself. [deltaMilliwatts] is negative when the profile saved
 * power.
 */
data class MeasureResult(
    val baseline: MeasureRun,
    val candidate: MeasureRun,
) {
    val deltaMilliwatts: Int?
        get() {
            val a = baseline.averageMilliwatts ?: return null
            val b = candidate.averageMilliwatts ?: return null
            return b - a
        }

    /**
     * Sampling noise on a phone is easily a few percent, and claiming a 2 mW win
     * on a 1800 mW baseline would be exactly the dishonesty this app exists to
     * avoid. Below this, the honest answer is "no measurable difference".
     */
    val isSignificant: Boolean
        get() {
            val base = baseline.averageMilliwatts ?: return false
            val delta = deltaMilliwatts ?: return false
            return base > 0 && kotlin.math.abs(delta).toFloat() / base >= 0.05f
        }

    val summary: String
        get() {
            val delta = deltaMilliwatts ?: return "not enough samples"
            if (!isSignificant) return "no measurable difference"
            return if (delta < 0) "saved ${-delta} mW" else "cost ${delta} mW"
        }
}
