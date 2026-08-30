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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.attey.governor.core.CpuPolicy
import com.attey.governor.core.DeviceModel
import com.attey.governor.core.LiveStats
import com.attey.governor.core.SysNode
import com.attey.governor.ui.components.ChoiceRow
import com.attey.governor.ui.components.FreqSlider
import com.attey.governor.ui.components.NotExposed
import com.attey.governor.ui.components.Readout
import com.attey.governor.ui.components.kHzValue
import com.attey.governor.ui.components.SectionCard
import com.attey.governor.ui.components.kHzToGHz

@Composable
fun CpuScreen(
    device: DeviceModel,
    live: LiveStats,
    onSetFreq: (policyId: Int, min: Long, max: Long) -> Unit,
    onSetGovernor: (policyId: Int, governor: String) -> Unit,
    onSetTunable: (path: String, value: String) -> Unit,
    onSetCoreOnline: (cpu: Int, online: Boolean) -> Unit,
) {
    val labels = device.clusterLabels
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(device.policies.withIndex().toList()) { (index, policy) ->
            CpuPolicyCard(
                policy = policy,
                clusterLabel = labels.getOrNull(index) ?: "Cluster $index",
                online = device.coresOnline,
                currentFreq = live.policyCurFreq[policy.id],
                onSetFreq = onSetFreq,
                onSetGovernor = onSetGovernor,
                onSetTunable = onSetTunable,
                onSetCoreOnline = onSetCoreOnline,
            )
        }
    }
}

@Composable
private fun CpuPolicyCard(
    policy: CpuPolicy,
    clusterLabel: String,
    online: Map<Int, Boolean>,
    currentFreq: Long?,
    onSetFreq: (policyId: Int, min: Long, max: Long) -> Unit,
    onSetGovernor: (policyId: Int, governor: String) -> Unit,
    onSetTunable: (path: String, value: String) -> Unit,
    onSetCoreOnline: (cpu: Int, online: Boolean) -> Unit,
) {
    val cpuList = policy.cpus.joinToString(",")
    val sub = "cpu$cpuList"
    SectionCard(title = clusterLabel, subtitle = sub) {
        if (currentFreq == null) {
            NotExposed("current frequency")
        } else {
            Readout(
                value = currentFreq.kHzValue(),
                unit = "GHz",
                color = MaterialTheme.colorScheme.primary,
                caption = policy.governor,
            )
        }
        var min by remember(policy.scalingMin) { mutableLongStateOf(policy.scalingMin) }
        var max by remember(policy.scalingMax) { mutableLongStateOf(policy.scalingMax) }
        FreqSlider(
            label = "min",
            steps = policy.availableFreqs,
            value = min,
            enabled = policy.availableFreqs.isNotEmpty(),
            onChange = {
                min = it
                if (min > max) max = min
                onSetFreq(policy.id, min, max)
            },
        )
        FreqSlider(
            label = "max",
            steps = policy.availableFreqs,
            value = max,
            enabled = policy.availableFreqs.isNotEmpty(),
            onChange = {
                max = it
                if (max < min) min = max
                onSetFreq(policy.id, min, max)
            },
        )
        if (policy.isCappedBelowHardware) {
            Text(
                // Careful with the claim here: the app cannot tell its own cap
                // from the vendor thermal daemon's, and asserting "something else
                // did this" right after the user did it would be a lie in an app
                // whose whole argument is that it does not tell you any.
                text = "ceiling ${policy.scalingMax.kHzToGHz()}, silicon goes to " +
                    "${policy.hwMax.kHzToGHz()}. If you did not set this, something else did.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ChoiceRow(
            label = "governor",
            options = policy.availableGovernors,
            selected = policy.governor,
            enabled = policy.availableGovernors.isNotEmpty(),
            onSelect = { onSetGovernor(policy.id, it) },
        )
        TunablesSection(
            tunables = policy.governorTunables,
            onSetTunable = onSetTunable,
        )
        CoresSection(
            cpus = policy.cpus,
            online = online,
            onSetCoreOnline = onSetCoreOnline,
        )
    }
}

@Composable
private fun TunablesSection(
    tunables: Map<String, SysNode>,
    onSetTunable: (path: String, value: String) -> Unit,
) {
    if (tunables.isEmpty()) {
        NotExposed("tunables")
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Tunables (${tunables.size})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = { expanded = !expanded }) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "collapse" else "expand",
            )
        }
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tunables.toSortedMap().forEach { (name, node) ->
                TunableRow(name = name, node = node, onSetTunable = onSetTunable)
            }
        }
    }
}

@Composable
private fun TunableRow(
    name: String,
    node: SysNode,
    onSetTunable: (path: String, value: String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (node.isUsable) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Text(
                text = node.path,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (node.isUsable) {
            var text by remember(node.path, node.value) { mutableStateOf(node.value) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                // Committing per keystroke sends a root write and a full re-probe for
                // every character: typing "1200000" wrote 1, 12, 120 ... to a live
                // governor tunable. Wait for the keyboard's done action.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSetTunable(node.path, text.trim()) }),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                text = node.value.ifEmpty { "n/a" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun CoresSection(
    cpus: List<Int>,
    online: Map<Int, Boolean>,
    onSetCoreOnline: (cpu: Int, online: Boolean) -> Unit,
) {
    if (cpus.isEmpty()) {
        NotExposed("cores")
        return
    }
    Text(
        text = "cores",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cpus.forEach { cpu ->
            val canOffline = cpu != 0
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "cpu$cpu",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (canOffline) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Text(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f),
                    text = if (canOffline) "" else "cannot be offlined",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Switch(
                    // Read the node, do not assume. A core offlined by the vendor's
                    // core_ctl, or by this app before a restart, is still offline.
                    checked = online[cpu] ?: true,
                    onCheckedChange = { on -> onSetCoreOnline(cpu, on) },
                    enabled = canOffline,
                )
            }
        }
    }
}

