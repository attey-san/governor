package com.attey.governor.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.attey.governor.core.DeviceModel
import com.attey.governor.core.LiveStats
import com.attey.governor.core.MeasureResult
import com.attey.governor.core.MeasureRun
import com.attey.governor.core.Profile
import com.attey.governor.ui.components.ChoiceRow
import com.attey.governor.ui.components.Readout
import com.attey.governor.ui.components.SectionCard
import com.attey.governor.ui.components.ValueRow
import com.attey.governor.ui.components.kHzToGHz
import java.util.Locale
import kotlin.math.roundToInt

private const val BASELINE = "baseline (current settings)"

@Composable
fun MeasureScreen(
    device: DeviceModel,
    live: LiveStats,
    profiles: List<Profile>,
    run: MeasureRun?,
    result: MeasureResult?,
    onStart: (profileName: String?, minutes: Int) -> Unit,
    onStop: () -> Unit,
) {
    var target by remember { mutableStateOf(BASELINE) }
    var minutes by remember { mutableIntStateOf(5) }
    val running = run?.running == true

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (live.charging) {
            item {
                SectionCard(title = "plugged in", subtitle = "measure anyway if you like") {
                    Text(
                        "While charging, current_now is the current going into the battery, " +
                            "not what the phone is spending. A run taken now measures the " +
                            "charger. Unplug first if you want the comparison to mean anything.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        item {
            SectionCard(title = "measure", subtitle = "what a profile actually costs") {
                Text(
                    "Samples real battery draw — current × voltage — for a fixed window. " +
                        "Measure a baseline first, then measure a profile against it. " +
                        "Keep the phone doing the same thing in both runs or the number means nothing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChoiceRow(
                    label = "run",
                    options = listOf(BASELINE) + profiles.map { it.name },
                    selected = target,
                    enabled = !running,
                    onSelect = { target = it },
                )
                ChoiceRow(
                    label = "for",
                    options = listOf("1 min", "5 min", "10 min", "30 min"),
                    selected = "$minutes min",
                    enabled = !running,
                    onSelect = { minutes = it.substringBefore(' ').toIntOrNull() ?: 5 },
                )
                if (running) {
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                } else {
                    Button(onClick = { onStart(target.takeIf { it != BASELINE }, minutes) }) {
                        Text("Start")
                    }
                }
            }
        }

        if (run != null) item { RunCard(run) }
        if (result != null) item { ResultCard(result) }
        if (run != null && run.residency.isNotEmpty()) item { ResidencyCard(run, device) }
    }
}

@Composable
private fun RunCard(run: MeasureRun) {
    SectionCard(
        title = run.label,
        subtitle = if (run.running) "running" else "finished",
    ) {
        val avg = run.averageMilliwatts
        if (avg == null) {
            Text(
                "waiting for the first sample",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Readout(value = "$avg", unit = "mW", color = MaterialTheme.colorScheme.primary)
        }
        if (run.running) {
            LinearProgressIndicator(
                progress = { run.progress },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
        }
        Text(
            text = "${clock(run.elapsedSeconds)} of ${clock(run.totalSeconds)} · ${run.samples.size} samples",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Sparkline(run.samples)
    }
}

/**
 * The trace, with no axes.
 *
 * The shape is the point -- whether draw is steady, spiky or drifting -- and an
 * axis on a 64dp strip would cost more room than it explains. The floor and
 * ceiling are printed instead, so the scale is never implied.
 */
@Composable
private fun Sparkline(samples: List<Int>) {
    if (samples.size < 2) return
    val low = samples.min()
    val high = samples.max()
    val span = (high - low).coerceAtLeast(1)
    val stroke = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(surface, RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        val stepX = size.width / (samples.size - 1)
        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - low).toFloat() / span) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, stroke, style = Stroke(width = 2.dp.toPx()))
    }
    Text(
        text = "$low – $high mW",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ResultCard(result: MeasureResult) {
    val significant = result.isSignificant
    SectionCard(title = "result", subtitle = "${result.candidate.label} against baseline") {
        Text(
            text = result.summary,
            style = MaterialTheme.typography.headlineSmall,
            color = if (significant) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ValueRow("baseline", "${result.baseline.averageMilliwatts ?: 0} mW")
        ValueRow(result.candidate.label, "${result.candidate.averageMilliwatts ?: 0} mW")
        if (!significant) {
            Text(
                "Under 5% of the baseline. On a phone that is inside the noise, so this " +
                    "is reported as nothing rather than as a win.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResidencyCard(run: MeasureRun, device: DeviceModel) {
    SectionCard(title = "residency", subtitle = "where the clusters actually sat") {
        run.residency.toSortedMap().forEach { (policyId, shares) ->
            val index = device.policies.indexOfFirst { it.id == policyId }
            Text(
                text = device.clusterLabels.getOrNull(index) ?: "policy$policyId",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            shares.entries.sortedByDescending { it.value }
                .filter { it.value >= 0.01f }
                .forEach { (freq, share) -> ResidencyRow(freq, share) }
        }
    }
}

@Composable
private fun ResidencyRow(freq: Long, share: Float) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = freq.kHzToGHz(),
            modifier = Modifier.width(88.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .height(10.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
            )
        }
        Text(
            text = "${(share * 100).roundToInt()}%",
            modifier = Modifier.width(48.dp).padding(start = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun clock(seconds: Int) =
    String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
