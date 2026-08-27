package com.attey.governor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.attey.governor.core.BlockDevice
import com.attey.governor.ui.components.ChoiceRow
import com.attey.governor.ui.components.SectionCard
import com.attey.governor.ui.components.ValueRow

@Composable
fun IoScreen(
    devices: List<BlockDevice>,
    onSetScheduler: (deviceName: String, scheduler: String) -> Unit,
    onSetReadAhead: (deviceName: String, kb: Long) -> Unit,
) {
    val real = devices.filterNot { it.isVirtual }
    val virtual = devices.filter { it.isVirtual }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(real) { dev ->
            BlockCard(
                dev = dev,
                editable = true,
                onSetScheduler = { onSetScheduler(dev.name, it) },
                onSetReadAhead = { onSetReadAhead(dev.name, it) },
            )
        }
        if (virtual.isNotEmpty()) {
            items(listOf(Unit)) { _ ->
                VirtualCollapsed(virtual = virtual)
            }
        }
    }
}

@Composable
private fun BlockCard(
    dev: BlockDevice,
    editable: Boolean,
    onSetScheduler: (String) -> Unit,
    onSetReadAhead: (Long) -> Unit,
) {
    val subtitle = listOfNotNull(
        dev.sizeLabel.ifEmpty { null },
        dev.mountedAt?.let { "mounted at $it" },
        if (dev.rotational) "rotational" else "non-rotational",
        if (dev.isVirtual) "virtual" else null,
    ).joinToString(" \u00B7 ")
    SectionCard(title = dev.name, subtitle = subtitle.ifEmpty { null }) {
        ChoiceRow(
            label = "scheduler",
            options = dev.availableSchedulers,
            selected = dev.scheduler,
            enabled = editable && dev.availableSchedulers.size > 1,
            onSelect = onSetScheduler,
        )
        ValueRow(
            label = "read_ahead_kb",
            value = dev.readAheadKb.toString(),
            enabled = editable,
        )
        ValueRow(
            label = "nr_requests",
            value = dev.nrRequests.toString(),
            enabled = editable,
        )
    }
}

@Composable
private fun VirtualCollapsed(virtual: List<BlockDevice>) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title = "Virtual devices") {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Show virtual devices (dm, loop, zram)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${virtual.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "collapse" else "expand",
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                virtual.forEach { dev ->
                    VirtualRow(dev)
                }
            }
        }
    }
}

@Composable
private fun VirtualRow(dev: BlockDevice) {
    Text(
        text = dev.name,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ValueRow(
        label = "scheduler",
        value = dev.scheduler,
        enabled = false,
    )
    ValueRow(
        label = "read_ahead_kb",
        value = dev.readAheadKb.toString(),
        enabled = false,
    )
}
