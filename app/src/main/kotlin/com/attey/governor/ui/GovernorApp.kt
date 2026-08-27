package com.attey.governor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attey.governor.core.GovernorViewModel
import com.attey.governor.core.PendingRevert
import com.attey.governor.core.UiState
import com.attey.governor.ui.screens.BatteryScreen
import com.attey.governor.ui.screens.CapabilityScreen
import com.attey.governor.ui.screens.CpuScreen
import com.attey.governor.ui.screens.GpuScreen
import com.attey.governor.ui.screens.IoScreen

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("CPU", Icons.Filled.Memory),
    Tab("GPU", Icons.Filled.VideogameAsset),
    Tab("Battery", Icons.Filled.BatteryAlert),
    Tab("I/O", Icons.Filled.Storage),
    Tab("Capability", Icons.Filled.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GovernorApp(vm: GovernorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val capabilities by vm.capabilities.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    when (val s = state) {
        is UiState.Loading -> LoadingScreen()
        is UiState.NoRoot -> NoRootScreen(message = s.message, onRetry = vm::refresh)
        is UiState.Ready -> {
            ReadyScaffold(
                state = s,
                capabilities = capabilities,
                snackbarHostState = snackbarHostState,
                onConfirm = vm::confirmPending,
                onRevert = vm::revertPending,
                onDismissRejection = vm::dismissRejection,
                onSetPolicyFreq = vm::setPolicyFreq,
                onSetPolicyGovernor = vm::setPolicyGovernor,
                onSetTunable = vm::setTunable,
                onSetCoreOnline = vm::setCoreOnline,
                onSetGpuFreq = vm::setGpuFreq,
                onSetGpuGovernor = vm::setGpuGovernor,
                onSetScheduler = vm::setScheduler,
                onSetReadAhead = vm::setReadAhead,
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.padding(top = 12.dp))
        Text(text = "probing", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NoRootScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.padding(top = 16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyScaffold(
    state: UiState.Ready,
    capabilities: List<com.attey.governor.core.Capability>,
    snackbarHostState: SnackbarHostState,
    onConfirm: () -> Unit,
    onRevert: () -> Unit,
    onDismissRejection: () -> Unit,
    onSetPolicyFreq: (Int, Long, Long) -> Unit,
    onSetPolicyGovernor: (Int, String) -> Unit,
    onSetTunable: (String, String) -> Unit,
    onSetCoreOnline: (Int, Boolean) -> Unit,
    onSetGpuFreq: (Long, Long) -> Unit,
    onSetGpuGovernor: (String) -> Unit,
    onSetScheduler: (String, String) -> Unit,
    onSetReadAhead: (String, Long) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(0) }
    val rejection = state.lastRejection
    LaunchedEffect(rejection) {
        if (rejection != null) {
            snackbarHostState.showSnackbar(rejection)
            onDismissRejection()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.device.kernel.ifEmpty { "kernel ?" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "root: ${state.device.rootProvider}" +
                                (state.live.hottestZone?.let { " \u00B7 hottest ${"%.1f\u00B0C".format(it.second)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column {
                if (state.pending != null) {
                    PendingBar(
                        pending = state.pending,
                        onConfirm = onConfirm,
                        onRevert = onRevert,
                    )
                }
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> CpuScreen(
                    device = state.device,
                    live = state.live,
                    onSetFreq = onSetPolicyFreq,
                    onSetGovernor = onSetPolicyGovernor,
                    onSetTunable = onSetTunable,
                    onSetCoreOnline = onSetCoreOnline,
                )
                1 -> GpuScreen(
                    gpus = state.device.gpus,
                    live = state.live,
                    onSetFreq = onSetGpuFreq,
                    onSetGovernor = onSetGpuGovernor,
                )
                2 -> BatteryScreen(
                    battery = state.device.battery,
                    live = state.live,
                )
                3 -> IoScreen(
                    devices = state.device.blockDevices,
                    onSetScheduler = onSetScheduler,
                    onSetReadAhead = onSetReadAhead,
                )
                else -> CapabilityScreen(capabilities = capabilities)
            }
        }
    }
}

@Composable
private fun PendingBar(
    pending: PendingRevert,
    onConfirm: () -> Unit,
    onRevert: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = pending.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "reverting in ${pending.secondsLeft}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onRevert) {
                    Text("Undo now")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text("Keep")
                }
            }
        }
    }
}
