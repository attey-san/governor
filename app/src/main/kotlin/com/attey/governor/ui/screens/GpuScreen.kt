package com.attey.governor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.attey.governor.core.GpuDevice
import com.attey.governor.core.LiveStats
import com.attey.governor.ui.components.ChoiceRow
import com.attey.governor.ui.components.FreqSlider
import com.attey.governor.ui.components.NotExposed
import com.attey.governor.ui.components.SectionCard
import com.attey.governor.ui.components.ValueRow

@Composable
fun GpuScreen(
    gpus: List<GpuDevice>,
    live: LiveStats,
    onSetFreq: (min: Long, max: Long) -> Unit,
    onSetGovernor: (governor: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(gpus) { gpu ->
            GpuCard(
                gpu = gpu,
                curFreq = if (live.gpuCurFreq > 0) live.gpuCurFreq else null,
                busy = live.gpuBusyPercent,
                onSetFreq = onSetFreq,
                onSetGovernor = onSetGovernor,
            )
        }
    }
}

@Composable
private fun GpuCard(
    gpu: GpuDevice,
    curFreq: Long?,
    busy: Int?,
    onSetFreq: (min: Long, max: Long) -> Unit,
    onSetGovernor: (governor: String) -> Unit,
) {
    SectionCard(title = gpu.name, subtitle = gpu.path) {
        if (curFreq == null) {
            NotExposed("current frequency")
        } else {
            Text(
                text = "%.0f MHz".format(curFreq / 1_000_000.0),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (busy == null) {
            NotExposed("busy %")
        } else {
            ValueRow(label = "busy", value = "$busy%")
        }
        var min by remember(gpu.minFreq) { mutableStateOf(gpu.minFreq) }
        var max by remember(gpu.maxFreq) { mutableStateOf(gpu.maxFreq) }
        FreqSlider(
            label = "min",
            steps = gpu.availableFreqs,
            value = min,
            enabled = gpu.availableFreqs.isNotEmpty(),
            onChange = {
                min = it
                if (min > max) max = min
                onSetFreq(min, max)
            },
        )
        FreqSlider(
            label = "max",
            steps = gpu.availableFreqs,
            value = max,
            enabled = gpu.availableFreqs.isNotEmpty(),
            onChange = {
                max = it
                if (max < min) min = max
                onSetFreq(min, max)
            },
        )
        ChoiceRow(
            label = "governor",
            options = gpu.availableGovernors,
            selected = gpu.governor,
            enabled = gpu.availableGovernors.isNotEmpty(),
            onSelect = onSetGovernor,
        )
    }
}
