package com.attey.governor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.attey.governor.core.LiveStats
import com.attey.governor.core.ThermalZone
import com.attey.governor.ui.components.Readout
import com.attey.governor.ui.components.SectionCard
import java.util.Locale

@Composable
fun ThermalScreen(zones: List<ThermalZone>, live: LiveStats) {
    var showAll by remember { mutableStateOf(false) }

    // Use probe-time values until the first live sample arrives.
    val readings = zones.mapNotNull { z ->
        val t = live.zoneTemps[z.id] ?: z.celsius.takeIf { z.isPopulated }
        if (t == null) null else z to t
    }.sortedByDescending { it.second }

    val shown = if (showAll) readings else readings.take(12)
    val hottest = readings.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard(title = "hottest", subtitle = hottest?.first?.type ?: "no populated zones") {
                if (hottest == null) {
                    Text(
                        "This kernel exposes no readable thermal zones.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Readout(
                        value = String.format(Locale.US, "%.1f", hottest.second),
                        unit = "°C",
                        color = colorFor(hottest.second),
                        caption = "${readings.size} of ${zones.size} zones reporting",
                    )
                }
                Text(
                    "Read-only. A userspace thermal governor re-parks any change within " +
                        "seconds; overriding it can leave the device without effective " +
                        "thermal control.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "zones", subtitle = "hottest first") {
                shown.forEach { (zone, temp) -> ZoneRow(zone, temp) }
                if (readings.size > 12) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "Show fewer" else "Show all (${readings.size})")
                    }
                }
                val unpopulated = zones.size - readings.size
                if (unpopulated > 0) {
                    Text(
                        "$unpopulated zone(s) hidden: unreadable or below -30 °C. These are " +
                            "not usable temperature reports.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoneRow(zone: ThermalZone, temp: Float) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = zone.type.ifEmpty { "zone${zone.id}" },
            modifier = Modifier.width(140.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
        ) {
            val share = ((temp - 20f) / 80f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(share)
                    .height(8.dp)
                    .background(colorFor(temp), RoundedCornerShape(3.dp))
            )
        }
        Text(
            text = String.format(Locale.US, "%.1f°", temp),
            modifier = Modifier.width(64.dp).padding(start = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun colorFor(temp: Float) = when {
    temp >= 70f -> MaterialTheme.colorScheme.error
    temp >= 50f -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}
