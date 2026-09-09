package com.attey.governor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.attey.governor.core.BatteryNodes
import com.attey.governor.core.LiveStats
import com.attey.governor.ui.components.MonoText
import com.attey.governor.ui.components.NotExposed
import com.attey.governor.ui.components.Readout
import com.attey.governor.ui.components.SectionCard
import com.attey.governor.ui.components.ValueRow
import java.util.Locale

@Composable
fun BatteryScreen(
    battery: BatteryNodes?,
    live: LiveStats,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { NowCard(live = live) }
        item { HealthCard(battery = battery) }
    }
}

@Composable
private fun NowCard(live: LiveStats) {
    SectionCard(title = "now") {
        val mw = live.batteryMilliwatts
        if (mw == null) {
            NotExposed("draw")
        } else {
            Readout(
                value = "$mw",
                unit = "mW",
                color = if (live.charging) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary,
                caption = if (live.charging) "external power" else "discharging",
            )
        }
        val pct = live.batteryPercent
        if (pct == null) {
            NotExposed("percent")
        } else {
            ValueRow(label = "percent", value = "$pct%")
        }
        val temp = live.batteryTempC
        if (temp == null) {
            NotExposed("temperature")
        } else {
            ValueRow(label = "temperature", value = String.format(Locale.US, "%.1f \u00B0C", temp))
        }
    }
}

@Composable
private fun HealthCard(battery: BatteryNodes?) {
    SectionCard(title = "health", subtitle = "reported full-charge capacity versus design") {
        if (battery == null) {
            NotExposed("battery nodes")
            return@SectionCard
        }
        val health = battery.healthPercent
        if (health == null) {
            NotExposed("charge_full")
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$health",
                    style = MaterialTheme.typography.headlineMedium,
                    color = when {
                        health >= 85 -> MaterialTheme.colorScheme.primary
                        health >= 70 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    text = " % of design",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            ValueRow("full charge", "${battery.fullMah} mAh")
            ValueRow("design", "${battery.designMah} mAh")
        }
        val cycles = battery.cycleCount
        if (cycles == null) {
            NotExposed("cycle_count")
            Text(
                "Cycle count is unavailable or appears unimplemented by this fuel gauge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ValueRow("cycles", cycles.toString())
        }
        MonoText(battery.path)
    }
}
