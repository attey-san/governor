package com.attey.governor.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Measures what a setting actually costs.
 *
 * Every kernel manager lets you change things and none of them tell you whether
 * the change did anything, which is why the whole category runs on folklore.
 * This samples real draw for a fixed window and diffs two windows.
 *
 * It is honest about its limits: a phone is not a lab. Background work, signal
 * strength and screen content all move the number, so the comparison is only
 * meaningful when the two windows are run back to back doing the same thing, and
 * [MeasureResult.isSignificant] refuses to call anything under 5% a win.
 */
object Measurement {

    private const val SAMPLE_MS = 2_000L

    /**
     * Runs one window, calling [onTick] after every sample so the UI can draw a
     * live trace. Returns the completed run, or whatever was gathered if the
     * coroutine is cancelled.
     */
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

    /**
     * Share of the window each frequency held, from the difference of two
     * cumulative time_in_state reads.
     *
     * The counters are absolute since boot and 64-bit; the difference is what
     * happened during the window. A frequency that gained no time is dropped
     * rather than shown as 0%, so the list is what the CPU did, not a table of
     * everything it could have done.
     */
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
        if (isEmpty()) null else (sum().toDouble() / size).toInt()
}
