package com.attey.governor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.attey.governor.core.Capability
import com.attey.governor.ui.components.SectionCard

@Composable
fun CapabilityScreen(capabilities: List<Capability>) {
    val present = capabilities.count { it.present }
    val total = capabilities.size
    val grouped = capabilities.groupBy { it.area }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "summary") {
                Text(
                    text = if (total == 0) "probing"
                    else "this kernel exposes $present of $total known tunables",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (total > 0) {
                    Text(
                        text = "rw = writable · ro = present but read-only · absent = not in this kernel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(grouped.entries.toList()) { (area, items) ->
            SectionCard(title = area) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.forEach { cap ->
                        CapabilityRow(cap)
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityRow(cap: Capability) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        val indicator = when {
            !cap.present -> "absent"
            !cap.writable -> "ro"
            else -> "rw"
        }
        val indicatorColor = when {
            !cap.present -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            !cap.writable -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cap.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (cap.present) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Text(
                text = cap.path,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
            if (cap.note != null) {
                Text(
                    text = cap.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Text(
            text = indicator,
            style = MaterialTheme.typography.labelSmall,
            color = indicatorColor,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
        )
    }
}
