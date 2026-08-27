package com.attey.governor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.attey.governor.core.DeviceModel
import com.attey.governor.core.SysNode
import com.attey.governor.ui.components.ChoiceRow
import com.attey.governor.ui.components.NotExposed
import com.attey.governor.ui.components.SectionCard
import com.attey.governor.ui.components.ValueRow

@Composable
fun MemoryScreen(
    device: DeviceModel,
    onSetVm: (name: String, value: String) -> Unit,
    onSetTunable: (path: String, value: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ZramCard(device, onSetTunable) }
        item { BoostCard(device, onSetTunable) }
        item { VmCard(device, onSetVm) }
    }
}

@Composable
private fun ZramCard(device: DeviceModel, onSet: (String, String) -> Unit) {
    SectionCard(title = "zram", subtitle = "compressed swap in RAM") {
        if (device.zram.isEmpty()) {
            NotExposed("zram")
            return@SectionCard
        }
        device.zram["disksize"]?.value?.toLongOrNull()?.let {
            ValueRow("size", "${it / 1024 / 1024} MB")
        }
        // "lzo [lz4] zstd" -- the brackets mark the algorithm in use.
        device.zram["comp_algorithm"]?.let { node ->
            val tokens = node.value.split(Regex("\\s+")).filter { it.isNotBlank() }
            val current = tokens.firstOrNull { it.startsWith("[") }?.trim('[', ']')
                ?: tokens.firstOrNull().orEmpty()
            ChoiceRow(
                label = "algorithm",
                options = tokens.map { it.trim('[', ']') },
                selected = current,
                enabled = node.isUsable,
                onSelect = { onSet(node.path, it) },
            )
        }
        device.zram["max_comp_streams"]?.let { ValueRow("streams", it.value) }
        // mm_stat: orig compressed mem_used ... -- the first two are the ratio.
        device.zram["mm_stat"]?.value?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }
            ?.takeIf { it.size >= 2 && it[1] > 0 }?.let { f ->
                ValueRow("stored", "${f[0] / 1024 / 1024} MB in ${f[1] / 1024 / 1024} MB")
                ValueRow("ratio", String.format(java.util.Locale.US, "%.2fx", f[0].toFloat() / f[1]))
            }
    }
}

@Composable
private fun BoostCard(device: DeviceModel, onSet: (String, String) -> Unit) {
    val node = device.boost["input_boost_freq"]
    SectionCard(title = "input boost", subtitle = "frequency floor held briefly after a touch") {
        if (node == null || !node.exists) {
            NotExposed("input_boost_freq")
        } else {
            // Per-CPU pairs, "0:1344000 1:0 2:0 ...". Writing a bare number here
            // boosts cpu0 and silently leaves every other core alone -- the bug
            // every other kernel manager ships.
            val pairs = remember(node.value) {
                node.value.split(Regex("\\s+")).filter { it.contains(':') }
                    .mapNotNull { p ->
                        val cpu = p.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
                        cpu to p.substringAfter(':')
                    }
            }
            if (pairs.isEmpty()) {
                ValueRow("input_boost_freq", node.value, enabled = node.isUsable)
            } else {
                pairs.forEach { (cpu, value) ->
                    EditableRow(
                        label = "cpu$cpu",
                        value = value,
                        enabled = node.isUsable,
                        onCommit = { newValue ->
                            val rebuilt = pairs.joinToString(" ") { (c, v) ->
                                "$c:${if (c == cpu) newValue else v}"
                            }
                            onSet(node.path, rebuilt)
                        },
                    )
                }
            }
        }
        device.boost["input_boost_ms"]?.let { n ->
            EditableRow("duration ms", n.value, n.isUsable) { onSet(n.path, it) }
        }
        device.boost["sched_boost_on_input"]?.let { n ->
            EditableRow("sched boost", n.value, n.isUsable) { onSet(n.path, it) }
        }
    }
}

@Composable
private fun VmCard(device: DeviceModel, onSetVm: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val all = remember(device.vmTunables) { device.vmTunables.entries.sortedBy { it.key } }
    val shown = if (expanded) all else all.take(8)

    SectionCard(title = "vm", subtitle = "/proc/sys/vm") {
        shown.forEach { (name, node) ->
            if (node.isUsable) {
                EditableRow(name, node.value, true) { onSetVm(name, it) }
            } else {
                ValueRow(name, node.value, enabled = false)
            }
        }
        if (all.size > 8) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show fewer" else "Show all (${all.size})")
            }
        }
    }
}

/** A label with an editable value that commits on the keyboard's done action. */
@Composable
private fun EditableRow(
    label: String,
    value: String,
    enabled: Boolean,
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.width(150.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(text.trim()) }),
        )
    }
}

/** Kept for callers that only need a read-only node row. */
@Composable
fun NodeRow(name: String, node: SysNode) = ValueRow(name, node.value, enabled = node.isUsable)
