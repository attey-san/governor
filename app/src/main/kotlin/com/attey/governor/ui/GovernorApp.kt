package com.attey.governor.ui

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attey.governor.core.GovernorViewModel
import com.attey.governor.core.PendingRevert
import com.attey.governor.core.UiState
import com.attey.governor.core.UsageAccess
import com.attey.governor.ui.screens.BatteryScreen
import com.attey.governor.ui.screens.CapabilityScreen
import com.attey.governor.ui.screens.CpuScreen
import com.attey.governor.ui.screens.GpuScreen
import com.attey.governor.ui.screens.IoScreen
import com.attey.governor.ui.screens.MeasureScreen
import com.attey.governor.ui.screens.MemoryScreen
import com.attey.governor.ui.screens.ProfilesScreen
import com.attey.governor.ui.screens.ThermalScreen
import java.io.File
import java.util.Locale

private val TABS = listOf(
    "CPU", "GPU", "Battery", "Thermal", "I/O", "Memory", "Profiles", "Measure", "Capability",
)

@Composable
fun GovernorApp(vm: GovernorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    // Sampling stops with the UI. See GovernorViewModel.onUiStarted.
    LifecycleStartEffect(vm) {
        vm.onUiStarted()
        onStopOrDispose { vm.onUiStopped() }
    }
    when (val s = state) {
        is UiState.Loading -> LoadingScreen()
        is UiState.NoRoot -> NoRootScreen(message = s.message, onRetry = vm::refresh)
        is UiState.Ready -> ReadyScaffold(s, vm)
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
        Text("probing", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NoRootScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.padding(top = 16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyScaffold(state: UiState.Ready, vm: GovernorViewModel) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    val capabilities by vm.capabilities.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val triggers by vm.triggers.collectAsState()
    val measurement by vm.measurement.collectAsState()
    val lastResult by vm.lastResult.collectAsState()
    val moduleExport by vm.moduleExport.collectAsState()
    val apps by vm.installedApps.collectAsState()
    val hasUsageAccess by vm.hasUsageAccess.collectAsState()
    val context = LocalContext.current

    // The usage-access appop is granted in Settings, so the only way to notice it
    // happened is to look again when this tab comes back into view.
    LaunchedEffect(selected) { vm.recheckUsageAccess() }

    val rejection = state.lastRejection
    LaunchedEffect(rejection) {
        if (rejection != null) {
            snackbarHostState.showSnackbar(rejection)
            vm.dismissRejection()
        }
    }

    Scaffold(
        topBar = {
            Column {
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
                                text = buildString {
                                    append(state.device.rootProvider)
                                    append(" · ")
                                    append(state.device.totalCores)
                                    append(" cores")
                                    state.live.hottestZone?.let {
                                        append(" · ")
                                        append(String.format(Locale.US, "%.1f°C", it.second))
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                PrimaryScrollableTabRow(
                    selectedTabIndex = selected,
                    edgePadding = 8.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    TABS.forEachIndexed { i, label ->
                        Tab(
                            selected = selected == i,
                            onClick = { selected = i },
                            text = { Text(label, style = MaterialTheme.typography.labelLarge) },
                        )
                    }
                }
            }
        },
        bottomBar = {
            state.pending?.let { PendingBar(it, vm::confirmPending, vm::revertPending) }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> CpuScreen(
                    device = state.device,
                    live = state.live,
                    onSetFreq = vm::setPolicyFreq,
                    onSetGovernor = vm::setPolicyGovernor,
                    onSetTunable = vm::setTunable,
                    onSetCoreOnline = vm::setCoreOnline,
                )
                1 -> GpuScreen(
                    gpus = state.device.gpus,
                    live = state.live,
                    onSetFreq = vm::setGpuFreq,
                    onSetGovernor = vm::setGpuGovernor,
                )
                2 -> BatteryScreen(battery = state.device.battery, live = state.live)
                3 -> ThermalScreen(zones = state.device.thermalZones, live = state.live)
                4 -> IoScreen(
                    devices = state.device.blockDevices,
                    onSetScheduler = vm::setScheduler,
                    onSetReadAhead = vm::setReadAhead,
                    onSetNrRequests = vm::setNrRequests,
                )
                5 -> MemoryScreen(
                    device = state.device,
                    onSetVm = vm::setVmTunable,
                    onSetTunable = vm::setTunable,
                )
                6 -> ProfilesScreen(
                    profiles = profiles,
                    triggers = triggers,
                    apps = apps,
                    hasUsageAccess = hasUsageAccess,
                    exportPath = moduleExport,
                    onGrantUsageAccess = {
                        runCatching { context.startActivity(UsageAccess.settingsIntent()) }
                    },
                    onSave = vm::saveCurrentAsProfile,
                    onApply = vm::applyProfile,
                    onDelete = vm::deleteProfile,
                    onAddTrigger = vm::addTrigger,
                    onSetTriggerEnabled = vm::setTriggerEnabled,
                    onDeleteTrigger = vm::deleteTrigger,
                    onExportModule = vm::exportMagiskModule,
                    onShareModule = { path ->
                        val file = File(path)
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.files",
                            file,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Governor module"))
                    },
                )
                7 -> MeasureScreen(
                    device = state.device,
                    live = state.live,
                    profiles = profiles,
                    run = measurement,
                    result = lastResult,
                    onStart = vm::startMeasurement,
                    onStop = vm::stopMeasurement,
                )
                else -> CapabilityScreen(capabilities = capabilities)
            }
        }
    }
}

/**
 * The countdown bar.
 *
 * Not a snackbar and not dismissible by tapping elsewhere: it is the only thing
 * standing between a bad governor and a phone that needs the power button held
 * for ten seconds.
 */
@Composable
private fun PendingBar(pending: PendingRevert, onConfirm: () -> Unit, onRevert: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(pending.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                "reverting in ${pending.secondsLeft}s",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { pending.fractionLeft },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onRevert) { Text("Undo now") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) { Text("Keep") }
            }
        }
    }
}
