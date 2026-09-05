package com.attey.governor.core

/** Profiles exclude CPU hotplug because background triggers have no rollback UI. */
data class Profile(
    val name: String,
    val policyMin: Map<Int, Long> = emptyMap(),
    val policyMax: Map<Int, Long> = emptyMap(),
    val governors: Map<Int, String> = emptyMap(),
    val gpuMin: Long? = null,
    val gpuMax: Long? = null,
    val gpuGovernor: String? = null,
    val tunables: Map<String, String> = emptyMap(),
) {
    val settingCount: Int
        get() = policyMin.size + policyMax.size + governors.size + tunables.size +
            listOfNotNull(gpuMin, gpuMax, gpuGovernor).size
}

enum class TriggerType(
    val label: String,
    val needsThreshold: Boolean = false,
    val needsApp: Boolean = false,
) {
    UNPLUGGED("when unplugged"),
    PLUGGED_IN("when plugged in"),
    SCREEN_OFF("when the screen goes off"),
    SCREEN_ON("when the screen comes on"),
    BATTERY_BELOW("when battery drops below", needsThreshold = true),
    TEMP_ABOVE("when battery temperature rises above", needsThreshold = true),
    APP_FOREGROUND("while an app is open", needsApp = true),
}

data class Trigger(
    val id: Long,
    val type: TriggerType,
    val threshold: Int,
    val profileName: String,
    val enabled: Boolean = true,
    val packageName: String = "",
    val appLabel: String = "",
) {
    val description: String
        get() = when {
            type.needsThreshold ->
                "${type.label} $threshold${if (type == TriggerType.BATTERY_BELOW) "%" else "°C"} → $profileName"
            type.needsApp ->
                "${type.label.replace("an app", appLabel.ifEmpty { packageName })} → $profileName"
            else -> "${type.label} → $profileName"
        }
}

data class MeasureRun(
    val label: String,
    val running: Boolean,
    val elapsedSeconds: Int,
    val totalSeconds: Int,
    val averageMilliwatts: Int? = null,
    val samples: List<Int> = emptyList(),
    val residency: Map<Int, Map<Long, Float>> = emptyMap(),
) {
    val progress: Float get() = if (totalSeconds <= 0) 0f else elapsedSeconds.toFloat() / totalSeconds
}

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

    /** Differences below 5% of baseline are treated as sampling noise. */
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
