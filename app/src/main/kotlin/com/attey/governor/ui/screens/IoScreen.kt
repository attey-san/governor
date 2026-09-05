package com.attey.governor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.attey.governor.ui.components.EditableRow
import com.attey.governor.ui.components.SectionCard
import com.attey.governor.ui.components.ValueRow

@Composable
fun IoScreen(
    devices: List<BlockDevice>,
    onSetScheduler: (deviceName: String, scheduler: String) -> Unit,
    onSetReadAhead: (deviceName: String, kb: Long) -> Unit,
    onSetNrRequests: (deviceName: String, requests: Long) -> Unit,
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
                onSetScheduler = { onSetScheduler(dev.name, it) },
                onSetReadAhead = { onSetReadAhead(dev.name, it) },
                onSetNrRequests = { onSetNrRequests(dev.name, it) },
            )
        }
        if (virtual.isNotEmpty()) {
            item { VirtualCollapsed(virtual = virtual) }
        }
    }
}

@Composable
private fun BlockCard(
    dev: BlockDevice,
    onSetScheduler: (String) -> Unit,
    onSetReadAhead: (Long) -> Unit,
    onSetNrRequests: (Long) -> Unit,
) {
    val subtitle = listOfNotNull(
        dev.sizeLabel.ifEmpty { null },
        dev.mountedAt?.let { "mounted at $it" },
        if (dev.rotational) "rotational" else "non-rotational",
    ).joinToString(" · ")
    SectionCard(title = dev.name, subtitle = subtitle.ifEmpty { null }) {
        ChoiceRow(
            label = "scheduler",
            options = dev.availableSchedulers,
            selected = dev.scheduler,
            enabled = dev.availableSchedulers.size > 1 && dev.schedulerWritable,
            onSelect = onSetScheduler,
        )
        // Both take a plain integer and reject anything else, so a value that
        // will not parse is dropped here rather than sent to the kernel to be
        // refused with a message about a node the user never typed.
        EditableRow("read_ahead_kb", dev.readAheadKb.toString(), dev.readAheadWritable) { v ->
            v.toLongOrNull()?.let(onSetReadAhead)
        }
        EditableRow("nr_requests", dev.nrRequests.toString(), dev.nrRequestsWritable) { v ->
            v.toLongOrNull()?.let(onSetNrRequests)
        }
    }
}

@Composable
private fun VirtualCollapsed(virtual: List<BlockDevice>) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title = "Virtual devices") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Show virtual devices (dm, loop, zram)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${virtual.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "collapse" else "expand",
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                virtual.forEach { dev -> VirtualRow(dev) }
            }
        }
    }
}

/**
 * Read-only. A dm target's queue sits above the real device's and tuning it
 * moves nothing; showing the values is still worth it, because a scheduler of
 * `none` on dm-46 is the usual reason /data looks untuned.
 */
@Composable
private fun VirtualRow(dev: BlockDevice) {
    Text(
        text = dev.name,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ValueRow(label = "scheduler", value = dev.scheduler, enabled = false)
    ValueRow(label = "read_ahead_kb", value = dev.readAheadKb.toString(), enabled = false)
}
