package com.attey.governor.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

object Measurement {

    private const val SAMPLE_MS = 2_000L

    suspend fun window(
        shell: RootShell,
        model: DeviceModel,
        label: String,
        seconds: Int,
        onTick: (MeasureRun) -> Unit,
    ): MeasureRun {
        val startStates = DeviceProbe.timeInState(shell, model.policies)
        val samples = mutableListOf<Int>()
        var elapsed = 0

        while (elapsed < seconds && coroutineContext.isActive) {
            delay(SAMPLE_MS)
            elapsed += (SAMPLE_MS / 1000).toInt()
            DeviceProbe.sampleLive(shell, model).batteryMilliwatts?.let { samples += it }
            onTick(
                MeasureRun(
                    label = label,
                    running = true,
                    elapsedSeconds = elapsed,
                    totalSeconds = seconds,
                    averageMilliwatts = samples.averageOrNull(),
                    samples = samples.toList(),
                    residency = residency(startStates, DeviceProbe.timeInState(shell, model.policies)),
                )
            )
        }

        val endStates = DeviceProbe.timeInState(shell, model.policies)
        return MeasureRun(
            label = label,
            running = false,
            elapsedSeconds = elapsed,
            totalSeconds = seconds,
            averageMilliwatts = samples.averageOrNull(),
            samples = samples.toList(),
            residency = residency(startStates, endStates),
        )
    }

    /** Converts cumulative time_in_state counters into shares of one window. */
    private fun residency(
        start: Map<Int, Map<Long, Long>>,
        end: Map<Int, Map<Long, Long>>,
    ): Map<Int, Map<Long, Float>> = end.mapValues { (policyId, endStates) ->
        val startStates = start[policyId].orEmpty()
        val deltas = endStates.mapNotNull { (freq, ticks) ->
            val d = ticks - (startStates[freq] ?: 0)
            if (d > 0) freq to d else null
        }.toMap()
        val total = deltas.values.sum()
        if (total <= 0) emptyMap() else deltas.mapValues { it.value.toFloat() / total }
    }

    private fun List<Int>.averageOrNull(): Int? =
        if (isEmpty()) null else (sumOf { it.toLong() }.toDouble() / size).toInt()
}
