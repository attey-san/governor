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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.attey.governor.core.BatteryNodes
import com.attey.governor.core.LiveStats
import com.attey.governor.ui.components.NotExposed
import com.attey.governor.ui.components.SectionCard
import com.attey.governor.ui.components.ValueRow

@Composable
fun BatteryScreen(
    battery: BatteryNodes?,
    live: LiveStats,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(listOf(Unit)) { _ ->
            NowCard(live = live)
        }
        items(listOf(Unit)) { _ ->
            HealthCard(battery = battery)
        }
    }
}

@Composable
private fun NowCard(live: LiveStats) {
    SectionCard(title = "now") {
        val mw = live.batteryMilliwatts
        if (mw == null) {
            NotExposed("draw")
        } else {
            Text(
                text = "$mw mW",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (live.charging) "charging" else "discharging",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            ValueRow(label = "temperature", value = "%.1f \u00B0C".format(temp))
        }
    }
}

@Composable
private fun HealthCard(battery: BatteryNodes?) {
    SectionCard(title = "health") {
        if (battery == null) {
            NotExposed("battery nodes")
            return@SectionCard
        }
        if (battery.hasCurrent) {
            ValueRow(label = "current_now", value = "(read from /sys)")
        } else {
            NotExposed("current_now")
        }
        if (battery.hasVoltage) {
            ValueRow(label = "voltage_now", value = "(read from /sys)")
        } else {
            NotExposed("voltage_now")
        }
        if (battery.hasChargeFull) {
            ValueRow(
                label = "charge_full / design",
                value = "shown as % when populated",
            )
        } else {
            NotExposed("charge_full")
        }
        if (battery.hasCycleCount) {
            ValueRow(label = "cycle_count", value = "(read from /sys)")
        } else {
            NotExposed("cycle_count")
        }
    }
}
